"""TACZ 1.2.0 gun: pack lookup, Bedrock geo -> triangles, Bedrock animations and the first-person pose, matching the
decompiled TACZ 1.2.0-hotfix classes (class names below; see README "Transform chain" for the full list with lines).
"""
import io
import json
import math
import os
import re
import zipfile

import numpy as np

DEFAULT_TACZ_JAR = os.environ.get("FPBENCH_TACZ_JAR",
                                  os.path.expanduser("~/ysm/bfm-port/libs/tacz-forge-1.20.1-1.2.0-hotfix.jar"))
ATTACHMENT_TYPES = ("scope", "muzzle", "stock", "grip", "laser", "extended_mag")


# ------------------------------------------------------------------ pack sources
def strip_json_comments(text):
    """TACZ pack JSON allows // and /* */ comments (outside strings)."""
    out, i, n, in_str = [], 0, len(text), False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == "\\":
                out.append(text[i + 1])
                i += 1
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
            out.append(c)
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        else:
            out.append(c)
        i += 1
    s = "".join(out)
    return re.sub(r",(\s*[}\]])", r"\1", s)  # trailing commas


class Source:
    """A jar, zip or directory; every pack root under it (a prefix holding data/<ns>/index/guns)."""

    def __init__(self, path):
        self.path = path
        self.zip = None if os.path.isdir(path) else zipfile.ZipFile(path)
        if self.zip:
            self.files = set(n for n in self.zip.namelist() if not n.endswith("/"))
        else:
            self.files = set()
            for r, _, fs in os.walk(path):
                for f in fs:
                    self.files.add(os.path.relpath(os.path.join(r, f), path).replace(os.sep, "/"))
        self.guns = {}
        pat = re.compile(r"^(.*?)data/([^/]+)/index/guns/(.+)\.json$")
        for f in self.files:
            m = pat.match(f)
            if m:
                self.guns.setdefault(f"{m.group(2)}:{m.group(3)}", m.group(1))

    def read(self, name):
        if self.zip:
            return self.zip.read(name)
        with open(os.path.join(self.path, name), "rb") as fh:
            return fh.read()

    def has(self, name):
        return name in self.files

    def json(self, name):
        return json.loads(strip_json_comments(self.read(name).decode("utf-8-sig")))


def _rl(rid, default_ns="tacz"):
    ns, _, p = rid.partition(":") if ":" in rid else (default_ns, ":", rid)
    return ns, p


def find_gun(gun_id, packs=None):
    """(source, pack root) for a gun id such as 'tacz:ak47', searching ``packs`` then the TACZ jar's default pack."""
    srcs = [Source(p) for p in (packs or [])]
    if os.path.exists(DEFAULT_TACZ_JAR):
        srcs.append(Source(DEFAULT_TACZ_JAR))
    for s in srcs:
        if gun_id in s.guns:
            return s, s.guns[gun_id]
    known = sorted(set(g for s in srcs for g in s.guns))
    raise KeyError(f"gun {gun_id} not found; known: {', '.join(known)}")


def list_guns(packs=None):
    srcs = [Source(p) for p in (packs or [])] + ([Source(DEFAULT_TACZ_JAR)] if os.path.exists(DEFAULT_TACZ_JAR) else [])
    return sorted(set(g for s in srcs for g in s.guns))


# ------------------------------------------------------------------ math
def rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1.0]])


def ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1.0]])


def rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1.0]])


def tr(x, y, z):
    m = np.eye(4)
    m[:3, 3] = (x, y, z)
    return m


def sc(x, y, z):
    return np.diag([x, y, z, 1.0])


def quat_euler(x, y, z):
    """MathUtil.toQuaternion(pitch=x, yaw=y, roll=z) = q(Rz(z) Ry(y) Rx(x)); (x, y, z, w)."""
    cy, sy = math.cos(z * .5), math.sin(z * .5)
    cp, sp = math.cos(y * .5), math.sin(y * .5)
    cr, sr = math.cos(x * .5), math.sin(x * .5)
    return np.array([cy * cp * sr - sy * sp * cr, sy * cp * sr + cy * sp * cr, sy * cp * cr - cy * sp * sr,
                     cy * cp * cr + sy * sp * sr])


def quat_to_euler(q):
    """MathUtil.toEulerAngles."""
    x, y, z, w = q
    a0 = math.atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y))
    sinp = 2 * (w * y - x * z)
    a1 = math.copysign(math.pi / 2, sinp) if abs(sinp) >= 1 else math.asin(sinp)
    a2 = math.atan2(2 * (w * z + y * x), 1 - 2 * (y * y + z * z))
    return np.array([a0, a1, a2])


def quat_matrix(q):
    x, y, z, w = q / np.linalg.norm(q)
    m = np.eye(4)
    m[:3, :3] = [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                 [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                 [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]
    return m


def _qlog(q):
    q = q / np.linalg.norm(q)
    v = q[:3]
    s = np.linalg.norm(v)
    th = math.atan2(s, q[3])
    return np.r_[v * (th / s if s > 5e-4 else 1), 0.0]


def _qexp(q):
    th = np.linalg.norm(q[:3])
    c = math.cos(th)
    v = q[:3] * (math.sin(th) / th if c < 0.9995 else 1)
    return np.r_[v, c]


def quat_blend(to, frm):
    """MathUtil.blendQuaternion(to, from)."""
    r = _qexp(_qlog(to) + _qlog(frm))
    return r / np.linalg.norm(r)


# ------------------------------------------------------------------ geometry
class Part:
    __slots__ = ("name", "parent", "children", "x", "y", "z", "rot", "tris", "uvs", "boxes")

    def __init__(self, name):
        self.name, self.parent, self.children = name, None, []
        self.x = self.y = self.z = 0.0
        self.rot = np.zeros(3)
        self.tris, self.uvs, self.boxes = [], [], []


# Direction -> pack face key (FaceUVsItem.getFace switch: EAST->west, WEST->east, UP->down, DOWN->up)
_FACE = {"down": "up", "up": "down", "west": "east", "north": "north", "east": "west", "south": "south"}
_NORMAL = {"down": (0, -1, 0), "up": (0, 1, 0), "west": (-1, 0, 0), "north": (0, 0, -1), "east": (1, 0, 0),
           "south": (0, 0, 1)}


def _box_faces(x, y, z, w, h, d, delta, mirror):
    """BedrockCubeBox/BedrockCubePerFace vertex layout: (direction, 4 corner positions) in polygon order."""
    xe, ye, ze = x + w, y + h, z + d
    x, y, z, xe, ye, ze = x - delta, y - delta, z - delta, xe + delta, ye + delta, ze + delta
    if mirror:
        x, xe = xe, x
    v = {1: (x, y, z), 2: (xe, y, z), 3: (xe, ye, z), 4: (x, ye, z), 5: (x, y, ze), 6: (xe, y, ze), 7: (xe, ye, ze),
         8: (x, ye, ze)}
    order = [("down", (6, 5, 1, 2)), ("up", (3, 4, 8, 7)), ("west", (1, 5, 8, 4)), ("north", (2, 1, 4, 3)),
             ("east", (6, 2, 3, 7)), ("south", (5, 6, 7, 8))]
    return [(dirn, np.array([v[i] for i in idx], float)) for dirn, idx in order]


def _polygon(corners, u1, v1, u2, v2, tw, th, mirror):
    """BedrockPolygon: corner uvs (u2,v1) (u1,v1) (u1,v2) (u2,v2), reversed when mirrored; two triangles."""
    uv = np.array([[u2 / tw, v1 / th], [u1 / tw, v1 / th], [u1 / tw, v2 / th], [u2 / tw, v2 / th]])
    if mirror:
        corners, uv = corners[::-1], uv[::-1]
    return [corners[[0, 1, 2]], corners[[0, 2, 3]]], [uv[[0, 1, 2]], uv[[0, 2, 3]]]


def load_geo(data):
    """Parts of a Bedrock geo (BedrockModel.loadNewModel / loadLegacyModel)."""
    if "minecraft:geometry" in data:
        geo = data["minecraft:geometry"][0]
        desc = geo.get("description", {})
        tw, th = desc.get("texture_width", 64), desc.get("texture_height", 64)
        bones = geo.get("bones", [])
    else:
        key = next(k for k in data if k.startswith("geometry."))
        geo = data[key]
        tw, th = geo.get("texturewidth", 64), geo.get("textureheight", 64)
        bones = geo.get("bones", [])
    index = {}
    for b in bones:
        index.setdefault(b["name"], b)
    parts = {b["name"]: Part(b["name"]) for b in bones}
    roots = []
    for b in bones:
        p = parts[b["name"]]
        piv = b.get("pivot", [0, 0, 0])
        par = b.get("parent")
        if par is not None and par in index:
            pp = index[par].get("pivot", [0, 0, 0])
            p.x, p.y, p.z = piv[0] - pp[0], pp[1] - piv[1], piv[2] - pp[2]
            p.parent = par
            if p not in parts[par].children:
                parts[par].children.append(p)
        else:
            p.x, p.y, p.z = piv[0], 24.0 - piv[1], piv[2]
            roots.append(p)
        if b.get("rotation") is not None:
            p.rot = np.radians(np.array(b["rotation"], float))
        for cube in b.get("cubes", []) or []:
            size = cube.get("size", [0, 0, 0])
            origin = cube.get("origin", [0, 0, 0])
            inflate = cube.get("inflate", 0.0)
            mirror = cube.get("mirror", b.get("mirror", False))
            crot = cube.get("rotation")
            transform = None
            if crot is None:
                x, y, z = origin[0] - piv[0], piv[1] - origin[1] - size[1], origin[2] - piv[2]
            else:
                cp = cube.get("pivot", [0, 0, 0])
                x, y, z = origin[0] - cp[0], cp[1] - origin[1] - size[1], origin[2] - cp[2]
                transform = (tr(cp[0] - piv[0], piv[1] - cp[1], cp[2] - piv[2]) @ rz(math.radians(crot[2]))
                             @ ry(math.radians(crot[1])) @ rx(math.radians(crot[0])))
            uv = cube.get("uv", [0, 0])
            per_face = isinstance(uv, dict)
            faces = _box_faces(x, y, z, size[0], size[1], size[2], inflate, mirror and not per_face)
            # box frame for metrics: corners v1 (min), v2 (+x), v4 (+y), v5 (+z) in part pixels
            corners4 = np.array([faces[3][1][1], faces[3][1][0], faces[3][1][2], faces[2][1][1]])
            if transform is not None:
                corners4 = (transform[:3, :3] @ corners4.T).T + transform[:3, 3]
            p.boxes.append(corners4)
            if not per_face:
                dx, dy, dz = int(size[0]), int(size[1]), int(size[2])
                u0, v0 = uv[0], uv[1]
                p1, p2, p3, p4, p5 = u0 + dz, u0 + dz + dx, u0 + dz + dx + dx, u0 + dz + dx + dz, u0 + dz + dx + dz + dx
                p6, p7 = v0 + dz, v0 + dz + dy
                rects = {"down": (p1, v0, p2, p6), "up": (p2, p6, p3, v0), "west": (u0, p6, p1, p7),
                         "north": (p1, p6, p2, p7), "east": (p2, p6, p4, p7), "south": (p4, p6, p5, p7)}
            for dirn, corners in faces:
                if transform is not None:
                    corners = (transform[:3, :3] @ corners.T).T + transform[:3, 3]
                if per_face:
                    f = uv.get(_FACE[dirn])
                    if not f or "uv" not in f:
                        continue
                    fu, fv = f["uv"]
                    su, sv = f.get("uv_size", [0, 0])
                    rect, mir = (fu, fv, fu + su, fv + sv), False
                else:
                    rect, mir = rects[dirn], mirror
                ts, us = _polygon(corners, *rect, tw, th, mir)
                p.tris += ts
                p.uvs += us
    for p in parts.values():
        p.tris = np.array(p.tris, float).reshape(-1, 3, 3)
        p.uvs = np.array(p.uvs, float).reshape(-1, 3, 2)
        p.boxes = np.array(p.boxes, float).reshape(-1, 4, 3)
    return parts, roots


# ------------------------------------------------------------------ animation
class Channel:
    """ObjectAnimationChannel + CustomInterpolator for one bedrock channel; values in TACZ units (rad, blocks)."""

    def __init__(self, kind, spec):
        self.kind = kind
        if isinstance(spec, (list, int, float, str)):
            spec = {"0": spec}
        keys = []
        for t, v in spec.items():
            pre = post = None
            mode = "linear"
            if isinstance(v, dict):
                if "pre" in v or "post" in v:
                    pre, post = v.get("pre"), v.get("post")
                elif "vector" in v:
                    pre = v["vector"]
                mode = v.get("lerp_mode", "linear")
            else:
                pre = v
            keys.append((float(t), self._vec(pre), self._vec(post), mode))
        keys.sort(key=lambda k: k[0])
        self.times = np.array([k[0] for k in keys])
        self.values = []
        for _, pre, post, _m in keys:
            if pre is not None and post is not None:
                self.values.append(np.r_[pre, post])
            else:
                self.values.append(pre if pre is not None else post)
        # Only CATMULLROM differs from linear for bedrock keys (other lerp modes need quaternion values)
        self.modes = ["catmullrom" if str(k[3]).lower() == "catmullrom" else "linear" for k in keys]

    def _vec(self, v):
        if v is None:
            return None
        if isinstance(v, (int, float, str)):
            v = [v, v, v]
        a = np.array([float(x) for x in v[:3]], float)
        if self.kind == "rotation":
            return np.radians(a)
        if self.kind == "position":
            return a * 0.0625
        return a

    def end(self):
        return float(self.times[-1]) if len(self.times) else 0.0

    def _get(self, i, post):
        v = self.values[i]
        return v[3:6] if (post and len(v) == 6) else v[:3]

    def value(self, t):
        times = self.times
        i = int(np.searchsorted(times, t, side="right")) - 1
        i = max(0, i)
        j = min(len(times) - 1, i + 1)
        if t <= times[0]:
            alpha = 0.0
        elif t >= times[-1]:
            alpha = 1.0
        else:
            alpha = (t - times[i]) / (times[i + 1] - times[i])
        if self.modes[i] != "catmullrom" and self.modes[j] != "catmullrom":
            if i == j:
                return self._get(j, alpha > 0)
            return self._get(i, True) * (1 - alpha) + self._get(j, False) * alpha
        if len(self.values) == 1:
            return self._get(0, alpha > 0)
        prv = 0 if i == 0 else i - 1
        nxt = len(self.values) - 1 if j == len(self.values) - 1 else j + 1
        y = [self._get(prv, True), self._get(i, False), self._get(j, False), self._get(nxt, False)]
        v0, v1 = (y[2] - y[0]) * 0.5, (y[3] - y[1]) * 0.5
        t2, t3 = alpha * alpha, alpha ** 3
        return ((2 * t3 - 3 * t2 + 1) * y[1] + (-2 * t3 + 3 * t2) * y[2] + (t3 - 2 * t2 + alpha) * v0
                + (t3 - t2) * v1)


class Clip:
    def __init__(self, name, spec):
        self.name = name
        self.loop = spec.get("loop", False)
        self.channels = []  # (bone, kind, Channel)
        for bone, chs in (spec.get("bones") or {}).items():
            for kind in ("position", "rotation", "scale"):
                if kind in chs:
                    self.channels.append((bone, kind, Channel(kind, chs[kind])))
        self.length = spec.get("animation_length") or max([c.end() for _, _, c in self.channels] or [0.0])

    def bones(self):
        return set(b for b, _, _ in self.channels)


# ------------------------------------------------------------------ gun
class Gun:
    def __init__(self, gun_id, packs=None, ammo=True, bullet_in_barrel=True):
        self.id = gun_id
        src, root = find_gun(gun_id, packs)
        self.source, self.root = src, root
        ns, path = _rl(gun_id)
        self.index = src.json(f"{root}data/{ns}/index/guns/{path}.json")
        dns, dpath = _rl(self.index["display"], ns)
        self.display_file = f"{root}assets/{dns}/display/guns/{dpath}.json"
        self.display = src.json(self.display_file)
        mns, mpath = _rl(self.display["model"], dns)
        self.model_file = f"{root}assets/{mns}/geo_models/{mpath}.json"
        self.parts, self.roots = load_geo(src.json(self.model_file))
        tns, tpath = _rl(self.display["texture"], dns)
        from PIL import Image
        self.texture_file = f"{root}assets/{tns}/textures/{tpath}.png"
        self.texture = np.asarray(Image.open(io.BytesIO(src.read(self.texture_file))).convert("RGBA"))
        self.model_fov = float(self.display.get("model_fov", 70.0))  # GunDisplay.modelFov default 70.0f
        self.clips = {}
        default = self.display.get("use_default_animation")
        if default and os.path.exists(DEFAULT_TACZ_JAR):
            jar = Source(DEFAULT_TACZ_JAR)
            f = f"assets/tacz/animations/{default}_default.animation.json"
            if jar.has(f):
                for k, v in jar.json(f).get("animations", {}).items():
                    self.clips[k] = Clip(k, v)
        if self.display.get("animation"):
            ans, apath = _rl(self.display["animation"], dns)
            f = f"{root}assets/{ans}/animations/{apath}.animation.json"
            if src.has(f):
                for k, v in src.json(f).get("animations", {}).items():
                    self.clips[k] = Clip(k, v)  # the gun's own clips replace the defaults by name
        self.ammo, self.bullet_in_barrel = ammo, bullet_in_barrel
        self.text_nodes = set((self.display.get("text_show") or {}).keys())

    # BedrockGunModel functional parts (constructor, cacheShellOriginNodes, allAttachmentRender, setTextShowList)
    def functional(self, name):
        """True when a part draws through a functional renderer instead of its cubes (cubes and children skipped)."""
        if name in ("lefthand_pos", "righthand_pos", "muzzle_flash", "additional_magazine"):
            return True
        if name == "shell" or re.fullmatch(r"shell_\d+", name):
            return True
        if name in self.text_nodes:
            return True
        return any(name == t + "_pos" for t in ATTACHMENT_TYPES if t != "scope")

    def hidden(self, name):
        """Visibility set by the null-returning functional renderers for a gun without attachments."""
        if name in ("bullet_in_mag", "bullet_chain"):
            return not self.ammo
        if name == "bullet_in_barrel":
            return not self.bullet_in_barrel
        if name in ("mount", "sight_folded", "handguard_tactical", "mag_extended_1", "mag_extended_2",
                    "mag_extended_3"):
            return True
        p = self.parts.get(name)
        if p is not None and p.parent == "attachment_adapter":
            return True
        return False

    # ---------------------------------------------------------- pose
    def pose(self, clip=None, t=0.0, idle_t=0.0, sway=True, shoot_t=None):
        """Animated part state at time t: base static_idle, the main clip, shoot (blend), movement idle (blend).

        Returns dict part -> (offset(3), quat(4), scale(3)), the camera quaternion and the set of animated nodes.
        default_state_machine_v2.lua: static_idle on BASE_TRACK (LOOP), draw/reload/inspect/put_away on MAIN_TRACK
        (non-blend, later track overrides), shoot on GUN_KICK_TRACK_LINE (blend), 'idle' on MOVEMENT_TRACK (blend)."""
        state = {n: [np.zeros(3), np.array([0, 0, 0, 1.0]), np.ones(3)] for n in self.parts}
        cam = np.array([0, 0, 0, 1.0])
        touched = set()
        plan = []
        if "static_idle" in self.clips:
            plan.append((self.clips["static_idle"], 0.0, False))
        if clip and clip not in ("idle", "static_idle", "shoot"):
            c = self.clip(clip)
            plan.append((c, min(max(t, 0.0), c.length), False))
        if clip == "shoot" or shoot_t is not None:
            c = self.clips.get("shoot")
            if c:
                plan.append((c, min(t if clip == "shoot" else shoot_t, c.length), True))
        if sway and "idle" in self.clips:
            c = self.clips["idle"]
            plan.append((c, (idle_t % c.length) if c.length else 0.0, True))
        for c, ct, blend in plan:
            for bone, kind, ch in c.channels:
                if bone == "constraint":  # ConstraintObject listener; weight = aiming progress = 0 at the hip
                    continue
                v = ch.value(ct)
                if bone == "camera" and kind == "rotation":  # CameraRotateListener
                    q = quat_euler(v[0], v[1], -v[2])
                    cam = quat_blend(cam, q) if blend else q
                    continue
                if bone not in state:
                    continue
                touched.add(bone)
                s = state[bone]
                if kind == "position":  # ModelTranslateListener: offset (x, -y, z)
                    d = np.array([v[0], -v[1], v[2]])
                    s[0] = s[0] + d if blend else d
                elif kind == "rotation":  # ModelRotateListener
                    e = quat_to_euler(s[1]) + v if blend else v
                    s[1] = quat_euler(*e)
                else:
                    s[2] = s[2] * v if blend else np.array(v, float)
        return state, cam, touched

    def clip(self, name):
        aliases = {"reload": "reload_tactical"}
        n = name if name in self.clips else aliases.get(name, name)
        if n not in self.clips:
            raise KeyError(f"clip {name} not in {sorted(self.clips)}")
        return self.clips[n]

    def local(self, part, st):
        """BedrockPart.translateAndRotateAndScale."""
        off, q, s = st
        p = part
        return (tr(*off) @ tr(p.x / 16, p.y / 16, p.z / 16) @ rz(p.rot[2]) @ ry(p.rot[1]) @ rx(p.rot[0])
                @ quat_matrix(q) @ sc(*s))

    def matrices(self, state):
        """Model matrices (4x4, blocks) of every part relative to the gun model origin. The parts' local matrices are
        kept in ``self.locals`` for triangles() (re-parenting under the additional magazine)."""
        out = {}
        self.locals = {}

        def walk(p, M):
            L = self.local(p, state[p.name])
            self.locals[p.name] = L
            m = M @ L
            out[p.name] = m
            for c in p.children:
                walk(c, m)
        for r in self.roots:
            walk(r, np.eye(4))
        return out

    def idle_view_inverse(self):
        """FirstPersonRenderGunEvent.getPositioningNodeInverse(idle_view path) (rest values only)."""
        if "idle_view" not in self.parts:
            return np.eye(4)
        path, p = [], self.parts["idle_view"]
        while p is not None:
            path.append(p)
            p = self.parts.get(p.parent) if p.parent else None
        path = path[::-1]
        m = np.eye(4)
        for part in reversed(path):
            m = m @ rx(-part.rot[0]) @ ry(-part.rot[1]) @ rz(-part.rot[2])
            if part.parent is not None:
                m = m @ tr(-part.x / 16, -part.y / 16, -part.z / 16)
            else:
                m = m @ tr(-part.x / 16, 1.5 - part.y / 16, -part.z / 16)
        return m

    def view_base(self, cam):
        """Pose stack handed to BedrockGunModel.render in first person, from the camera (view space, blocks).

        BeforeRenderHandEvent camera quaternion; GunItemRendererWrapper.renderFirstPerson: translate(0,1.5,0), ZP 180;
        applyFirstPersonPositioningTransform: translate(0,1.5,0) * idleViewInverse * translate(0,-1.5,0); the
        constraint transform has weight aimingProgress = 0 at the hip."""
        return (quat_matrix(cam) @ tr(0, 1.5, 0) @ rz(math.pi) @ tr(0, 1.5, 0) @ self.idle_view_inverse()
                @ tr(0, -1.5, 0))

    def triangles(self, mats, touched):
        """Drawn gun geometry with ``mats`` (part -> 4x4): triangles (n,3,3), uvs (n,3,2), part name per triangle,
        cube frames (m,4,3: min corner, +x, +y, +z corners), part name per cube and a rigid-group id per cube (one per
        drawn part instance: a magazine drawn both in the gun and in the hand gives two groups). Follows
        BedrockPart.render / FunctionalBedrockPart.render visibility (functional parts draw neither cubes nor
        children)."""
        pos, uvs, names, boxes, box_names, box_groups = [], [], [], [], [], []

        def emit(p, M):
            if len(p.tris):
                pos.append(np.einsum("ij,nkj->nki", M[:3, :3], p.tris / 16.0) + M[:3, 3])
                uvs.append(p.uvs)
                names.extend([p.name] * len(p.tris))
            if len(p.boxes):
                boxes.append(np.einsum("ij,nkj->nki", M[:3, :3], p.boxes / 16.0) + M[:3, 3])
                box_names.extend([p.name] * len(p.boxes))
                box_groups.extend([len(boxes) - 1] * len(p.boxes))

        def walk(p, parent=None):
            """parent: the parent's matrix when re-parented (magazine children), else None (use ``mats``)."""
            m = mats[p.name] if parent is None else parent @ self.locals[p.name]
            under = None if parent is None else m
            if self.functional(p.name):
                if p.name == "additional_magazine" and p.name in touched:  # BedrockGunModel$1
                    emit(p, m)
                    for c in p.children:
                        walk(c, under)
                    mag = self.parts.get("magazine")
                    if mag is not None and not self.hidden("magazine"):
                        emit(mag, m)  # magazine cubes compiled at the additional magazine's pose
                        for c in mag.children:  # and its children below that pose
                            walk(c, m)
                return
            if self.hidden(p.name):
                return
            emit(p, m)
            for c in p.children:
                walk(c, under)
        for r in self.roots:
            walk(r)
        cat = lambda xs, shape: np.concatenate(xs) if xs else np.zeros(shape)
        return (cat(pos, (0, 3, 3)), cat(uvs, (0, 3, 2)), names, cat(boxes, (0, 4, 3)), box_names,
                np.array(box_groups, int))

    def hand_matrix(self, mats, left):
        """LeftHandRender/RightHandRender: the hand part's pose times ZP 180 (model space)."""
        n = "lefthand_pos" if left else "righthand_pos"
        if n not in mats:
            return None
        return mats[n] @ rz(math.pi)
