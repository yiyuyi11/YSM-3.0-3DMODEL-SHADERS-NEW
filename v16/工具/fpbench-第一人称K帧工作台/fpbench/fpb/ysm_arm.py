"""The YSM first-person arm model of an imported humanoid, as our importer builds it (run offline through java/FpDump).

Arm model: HumanoidImport.arm() (mod/src/com/elfmcys/ysm/format/parser/HumanoidImport.java:367-470): the LeftArm and
RightArm subtrees with ArmFit applied (hand x1.8 about the wrist, arm x1.45 across, scaled so shoulder->palm is 11.45 px,
shoulders on (-+1.15, 29.775, 0) px; :103-117, :556-590). Pivots in model pixels, vertices in blocks (zero-rotation bind
space: the native skin matrix is the bone pose itself, YesSteveModel-Native gfx/src/renderer/model_state_impl.inc:36-48).
Animation: HumanoidImport.armAnimation() = `parallel2` only (HumanoidImport.java:1204-1306).
"""
import hashlib
import json
import math
import os
import subprocess

import numpy as np

from . import molang

HERE = os.path.dirname(os.path.abspath(__file__))
JAVA_SRC = os.path.join(os.path.dirname(HERE), "java", "com", "elfmcys", "ysm", "format", "parser", "FpDump.java")
DEFAULT_JAR = os.path.expanduser("~/mc/client/mods/ysm-3.0-dev-forge+mc1.20.1-pelica-motion.jar")
DEFAULT_LIBS = os.path.expanduser("~/mc/client/libraries")
DEFAULT_NATIVE = os.path.expanduser("~/mc/client/home/.ysm/unstable/libysm-core-3.0-dev-forge+mc1.20.1.so")
FINGERS = ("Thumb", "Index", "Middle", "Ring", "Little")

# TaczGrips.GRIPS (YesSteveModel src client/compat/tacz/TaczGrips.java:28-30, identical in the deployed jar):
# gun id -> (left hand, right hand), each (palmX, palmY, palmZ, wristX, wristY, wristZ).
TACZ_GRIPS = {"tacz:m4a1": ((5.0, 18.282, 1.155, 80.64, -63.68, -49.49), (-7.092, 13.806, -1.283, 38.73, 0.0, 9.0))}


def _md5(path, n=1 << 20):
    h = hashlib.md5()
    with open(path, "rb") as f:
        while True:
            b = f.read(n)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def cache_root():
    d = os.environ.get("FPBENCH_CACHE", os.path.expanduser("~/.cache/fpbench"))
    os.makedirs(d, exist_ok=True)
    return d


def ensure_dump(model, jar=None, libs=None, native=None, log=print):
    """Directory holding arm.json/arm.bin/atlas for ``model``; runs FpDump once per (model, jar, FpDump) content."""
    jar = jar or os.environ.get("FPBENCH_YSM_JAR", DEFAULT_JAR)
    libs = libs or os.environ.get("FPBENCH_MC_LIBS", DEFAULT_LIBS)
    native = native or os.environ.get("YSM_NATIVE_PATH", DEFAULT_NATIVE)
    key = hashlib.md5((_md5(model) + _md5(jar) + _md5(JAVA_SRC)).encode()).hexdigest()[:16]
    out = os.path.join(cache_root(), "arm-" + key)
    if os.path.exists(os.path.join(out, "arm.json")):
        return out
    classes = os.path.join(cache_root(), "fpdump-" + hashlib.md5((_md5(JAVA_SRC) + _md5(jar)).encode()).hexdigest()[:12])
    cp = jar + ":" + ":".join(os.path.join(r, f) for r, _, fs in os.walk(libs) for f in fs if f.endswith(".jar"))
    if not os.path.exists(os.path.join(classes, "com/elfmcys/ysm/format/parser/FpDump.class")):
        log(f"fpbench: compiling FpDump against {jar}")
        subprocess.run(["javac", "--release", "17", "-nowarn", "-encoding", "UTF-8", "-d", classes, "-cp", cp, JAVA_SRC],
                       check=True)
    log(f"fpbench: importing {model} with YSM's importer (once per model/jar, ~25 s)")
    env = dict(os.environ, YSM_NATIVE_PATH=native)
    r = subprocess.run(["java", "-cp", classes + ":" + cp, "com.elfmcys.ysm.format.parser.FpDump", model, out + ".tmp"],
                       env=env, capture_output=True, text=True)
    if r.returncode != 0 or not os.path.exists(os.path.join(out + ".tmp", "arm.json")):
        raise RuntimeError("FpDump failed:\n" + r.stdout[-3000:] + r.stderr[-3000:])
    os.replace(out + ".tmp", out)
    return out


def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rot_z(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def euler_zyx_matrix(e):
    """Rz(e.z) Ry(e.y) Rx(e.x): the native RotateZYX order and JOML getEulerAnglesZYX's inverse."""
    return rot_z(e[2]) @ rot_y(e[1]) @ rot_x(e[0])


def matrix_euler_zyx(m):
    """JOML Matrix3f.getEulerAnglesZYX for a column-vector matrix m."""
    return np.array([math.atan2(m[2, 1], m[2, 2]), math.asin(max(-1.0, min(1.0, -m[2, 0]))), math.atan2(m[1, 0], m[0, 0])])


def axis_angle(axis, deg):
    a = np.asarray(axis, float) / np.linalg.norm(axis)
    t = math.radians(deg)
    k = np.array([[0, -a[2], a[1]], [a[2], 0, -a[0]], [-a[1], a[0], 0]])
    return np.eye(3) + math.sin(t) * k + (1 - math.cos(t)) * (k @ k)


class ArmModel:
    def __init__(self, dump_dir):
        meta = json.load(open(os.path.join(dump_dir, "arm.json")))
        self.dir = dump_dir
        self.bones = meta["bones"]
        self.names = [b["name"] for b in self.bones]
        self.index = {n: i for i, n in enumerate(self.names)}
        self.parent = [self.index.get(b["parent"], -1) if b["parent"] else -1 for b in self.bones]
        self.pivot = np.array([b["pivot"] for b in self.bones], float)  # model pixels
        self.rest = np.array([b["rotate"] for b in self.bones], float)  # radians
        c = meta["counts"]
        raw = np.fromfile(os.path.join(dump_dir, "arm.bin"), dtype="<f4", count=c["positions"] + c["normals"] + c["uvs"]
                          + c["weights"])
        o = 0
        self.positions = raw[o:o + c["positions"]].reshape(-1, 3).astype(float); o += c["positions"]
        self.normals = raw[o:o + c["normals"]].reshape(-1, 3).astype(float); o += c["normals"]
        self.uvs = raw[o:o + c["uvs"]].reshape(-1, 2).astype(np.float32); o += c["uvs"]
        self.weights = raw[o:o + c["weights"]].reshape(-1, 4).astype(float)
        ints = np.fromfile(os.path.join(dump_dir, "arm.bin"), dtype="<i4", offset=4 * len(raw))
        self.joints = ints[:c["joints"]].reshape(-1, 4)
        self.triangles = ints[c["joints"]:c["joints"] + c["triangles"]].reshape(-1, 3)
        self.animation = meta["animation"]
        self.atlas_path = os.path.join(dump_dir, meta["atlas"])
        self._atlas = None
        self.order = self._topo()
        self._labels()

    def _topo(self):
        order, seen = [], set()

        def visit(i):
            if i in seen:
                return
            if self.parent[i] >= 0:
                visit(self.parent[i])
            seen.add(i)
            order.append(i)
        for i in range(len(self.bones)):
            visit(i)
        return order

    @property
    def atlas(self):
        if self._atlas is None:
            npy = self.atlas_path + ".npy"
            if not os.path.exists(npy):
                from PIL import Image
                np.save(npy, np.asarray(Image.open(self.atlas_path).convert("RGBA")))
            self._atlas = np.load(npy, mmap_mode="r")
        return self._atlas

    def descends(self, i, name):
        while i >= 0:
            if self.names[i] == name:
                return True
            i = self.parent[i]
        return False

    def _labels(self):
        """Per bone: side (1 left, 2 right), part ('arm', 'palm', finger name) and segment (1-3)."""
        self.bone_side = np.array([1 if self.descends(i, "LeftArm") else 2 if self.descends(i, "RightArm") else 0
                                   for i in range(len(self.names))])
        self.bone_part = []
        for i, n in enumerate(self.names):
            part = "arm"
            j = i
            while j >= 0:
                nm = self.names[j]
                f = next((f for f in FINGERS if nm in ("Left" + f + s for s in "123") or nm in ("Right" + f + s for s in "123")), None)
                if f:
                    part = f
                    break
                if nm in ("LeftHand", "RightHand"):
                    part = "palm"
                    break
                j = self.parent[j]
            self.bone_part.append(part)
        strongest = self.joints[np.arange(len(self.joints)), self.weights.argmax(1)]
        self.vert_bone = strongest
        self.vert_side = self.bone_side[strongest]
        self.vert_part = np.array(self.bone_part, dtype=object)[strongest]
        # hand weight (wrist and below) per vertex, for "hand" vertex sets
        inhand = np.array([self.descends(i, "LeftHand") or self.descends(i, "RightHand") for i in range(len(self.names))])
        self.vert_hand = (self.weights * inhand[self.joints]).sum(1)

    # ---------------------------------------------------------------- fingers
    def chain(self, side, finger):
        """Bone indices of one finger, root to tip ('Left'/'Right', 'Index'...)."""
        return [self.index[side + finger + s] for s in "123" if side + finger + s in self.index]

    def curl_axis(self, side, finger):
        """The importer's curl axis: finger direction x (+-1, 0, 0) (HumanoidImport.java:1222-1227)."""
        ch = self.chain(side, finger)
        if not ch:
            return None
        if len(ch) > 1:
            d = self.pivot[ch[1]] - self.pivot[ch[0]]
        else:
            d = self.pivot[ch[0]] - self.pivot[self.index[side + "Hand"]]
        a = np.cross(d / np.linalg.norm(d), [1.0 if side == "Left" else -1.0, 0, 0])
        n = np.linalg.norm(a)
        return a / n if n > 1e-3 else None

    def curl_keyframe(self, bone, axis, curl_deg, spread_deg=0.0):
        """Keyframe (deg, X/Y negated) whose rest+key equals rot(axis, curl) [after a spread turn about -X], exactly as
        HumanoidImport.java:1253-1270 computes `placed`."""
        m = axis_angle(axis, curl_deg)
        if spread_deg:
            m = axis_angle([-1, 0, 0], spread_deg) @ m
        e = matrix_euler_zyx(m)
        k = np.degrees(e - self.rest[bone])
        k[0] *= -1
        k[1] *= -1
        return k

    def keyframe_curl(self, bone, axis, key):
        """Inverse of curl_keyframe without spread: the curl about ``axis`` closest to a keyframe."""
        e = self.rest[bone] + np.radians([-key[0], -key[1], key[2]])
        m = euler_zyx_matrix(e)
        # angle of m projected on the axis
        w = np.array([m[2, 1] - m[1, 2], m[0, 2] - m[2, 0], m[1, 0] - m[0, 1]])
        return math.degrees(math.atan2(np.dot(w, axis) / 2, (np.trace(m) - 1) / 2))

    # ---------------------------------------------------------------- pose
    def keyframes(self, env, override=None):
        """Bone -> keyframe rotation (deg) of parallel2 under the Molang env, with overrides replacing whole bones."""
        out = {}
        anim = self.animation.get("animations", {}).get("parallel2", {})
        for name, ch in anim.get("bones", {}).items():
            rot = ch.get("rotation")
            if rot is None or name not in self.index:
                continue
            if isinstance(rot, dict):  # timeline: take the first key (generated clips are constant)
                rot = rot[sorted(rot, key=float)[0]]
            out[name] = np.array([molang.evaluate(v, env) for v in rot], float)
        for name, rot in (override or {}).items():
            if name in self.index:
                out[name] = np.array(rot, float)
        return out

    def world(self, keys):
        """Bone pose matrices (4x4, blocks), native ApplyLocalState with rest + keyframe (X/Y negated)."""
        W = [None] * len(self.names)
        for i in self.order:
            k = keys.get(self.names[i])
            e = self.rest[i] + (np.radians([-k[0], -k[1], k[2]]) if k is not None else 0)
            p = self.pivot[i] / 16
            L = np.eye(4)
            L[:3, :3] = euler_zyx_matrix(e)
            L[:3, 3] = p - L[:3, :3] @ p
            W[i] = L if self.parent[i] < 0 else W[self.parent[i]] @ L
        return np.array(W)

    def skin(self, W, idx=None):
        """Linear-blend skinned positions/normals (blocks) for all vertices or ``idx``."""
        j = self.joints if idx is None else self.joints[idx]
        w = self.weights if idx is None else self.weights[idx]
        p = self.positions if idx is None else self.positions[idx]
        nrm = self.normals if idx is None else self.normals[idx]
        M = np.einsum("nk,nkij->nij", w, W[j])
        pos = np.einsum("nij,nj->ni", M[:, :3, :3], p) + M[:, :3, 3]
        n = np.einsum("nij,nj->ni", M[:, :3, :3], nrm)
        n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-9)
        return pos, n


def fp_env(gun_id, grips=None, gun=True):
    """Molang env of the first-person arm holding a TACZ gun in the main hand, standing (HumanoidImport.java:1175-1186)."""
    grips = TACZ_GRIPS if grips is None else grips
    grip = grips.get(gun_id)
    keys = ("left_x", "left_y", "left_z", "right_x", "right_y", "right_z")

    def tac_grip(key):
        if grip is None:
            return 0.0
        if key == "fitted":
            return 1.0
        if key in keys:
            i = keys.index(key)
            return grip[0][3 + i] if i < 3 else grip[1][i]
        return 0.0
    return {"ctrl.tac_grip": tac_grip, "ctrl.tac_hold_gun": 1.0 if gun else 0.0, "ysm.has_mainhand": 1.0 if gun else 0.0,
            "ysm.has_offhand": 0.0, "ctrl.elytra_fly": 0.0, "ctrl.swim": 0.0, "ctrl.run": 0.0}


def load(model, **kw):
    return ArmModel(ensure_dump(model, **kw))
