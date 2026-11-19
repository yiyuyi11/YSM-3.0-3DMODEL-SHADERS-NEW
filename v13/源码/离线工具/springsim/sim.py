"""Offline port of YSM's native StepSprings (spring_physics.cc) for Pelica's skirt and jacket hem, driven by the
walk/run clips YSM generates (tools/ysmgen GenAnim: main animation JSON and rest skeleton). Measures how far skirt
vertices sink into the legs at a given frame rate. interp=True models native-patch/spring-substep-interpolation.diff
(the frame's animated pose and body travel spread over its fixed steps)."""
import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
import fix_pelica as fp  # noqa: E402

STEP = 1 / 120
AIR_DRAG = 0.15
FRICTION = 0.4
S = np.diag([-1.0, 1.0, -1.0])  # VRM <-> YSM axes (YSM faces -Z with its left at -X)


def rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def rot_between(f, t):
    f = f / np.linalg.norm(f)
    t = t / np.linalg.norm(t)
    c = float(np.clip(f @ t, -1, 1))
    ax = np.cross(f, t)
    s = np.linalg.norm(ax)
    if s < 1e-9:
        return np.eye(3)
    ax /= s
    k = np.array([[0, -ax[2], ax[1]], [ax[2], 0, -ax[0]], [-ax[1], ax[0], 0]])
    return np.eye(3) + s * k + (1 - c) * k @ k


def closest(p, a, b):
    ab = b - a
    t = np.clip((p - a) @ ab / max(ab @ ab, 1e-12), 0, 1)
    return a + t * ab


class Clip:
    def __init__(self, clip, skel):
        self.length = clip.get("animation_length", 1.0)  # held poses (sneak) carry no length
        self.bones = clip["bones"]
        self.skel = {b["name"]: b for b in skel}

    def channel(self, bone, ch, t):
        kf = self.bones.get(bone, {}).get(ch)
        if not kf:
            return np.zeros(3)
        keys = sorted(kf, key=float)
        ts = np.array([float(k) for k in keys])
        vs = np.array([kf[k] if isinstance(kf[k], list) else kf[k]["post"] for k in keys], float)
        t = t % self.length
        return np.array([np.interp(t, ts, vs[:, i]) for i in range(3)])

    def world(self, name, t, cache):
        """4x4 pose (rest -> posed) of a bone in VRM metres."""
        if name in cache:
            return cache[name]
        b = self.skel[name]
        parent = self.world(b["parent"], t, cache) if b["parent"] else np.eye(4)
        rot = np.radians(self.channel(name, "rotation", t))
        pos = self.channel(name, "position", t)
        pivot = np.array(b["pivot"])
        m = np.eye(4)
        r = rz(rot[2]) @ ry(-rot[1]) @ rx(-rot[0])
        m[:3, :3] = r
        m[:3, 3] = pivot + np.array([-pos[0], pos[1], pos[2]]) - r @ pivot
        cache[name] = parent @ m
        return cache[name]


class Sim:
    def __init__(self, vrm, skel, unit):
        doc, blob = fp.load_glb(vrm)
        nodes = doc["nodes"]
        self.names = [n.get("name", "") for n in nodes]
        world = fp.world_matrices(nodes)
        self.unit = unit  # YSM pixels per metre
        self.skel_names = {b["name"] for b in skel}
        # YSM renames humanoid bones; map VRM node -> YSM bone name.
        humanoid = doc["extensions"]["VRMC_vrm"]["humanoid"]["humanBones"]
        rename = {"leftUpperLeg": "LeftLeg", "rightUpperLeg": "RightLeg", "leftLowerLeg": "LeftLowerLeg",
                  "rightLowerLeg": "RightLowerLeg"}
        self.ysm_name = {i: n for i, n in enumerate(self.names)}
        for bone, ysm in rename.items():
            self.ysm_name[humanoid[bone]["node"]] = ysm
        self.parent = {c: i for i, n in enumerate(nodes) for c in n.get("children", [])}
        spring = doc["extensions"]["VRMC_springBone"]
        self.colliders = []
        for c in spring["colliders"]:
            body = c["shape"].get("capsule") or c["shape"]["sphere"]
            head = (world[c["node"]] @ np.r_[body["offset"], 1])[:3]
            tail = (world[c["node"]] @ np.r_[body.get("tail", body["offset"]), 1])[:3]
            self.colliders.append((self.ysm_name[c["node"]], head, tail, body["radius"]))
        groups = [g["colliders"] for g in spring["colliderGroups"]]
        self.chains = []
        for sp in spring["springs"]:
            if not sp["name"].startswith(("qunbai", "yibai")):
                continue
            js = sp["joints"]
            self.chains.append(dict(
                nodes=[j["node"] for j in js], pos=[world[j["node"]][:3, 3] for j in js],
                stiff=[j.get("stiffness", 1.0) for j in js], drag=[j.get("dragForce", 0.4) for j in js],
                grav=[j.get("gravityPower", 0.0) for j in js], hit=[j.get("hitRadius", 0.0) for j in js],
                gdir=[np.array(j.get("gravityDir", [0, -1.0, 0])) / np.linalg.norm(j.get("gravityDir", [0, -1.0, 0]))
                      for j in js],
                cols=sorted({ci for g in sp["colliderGroups"] for ci in groups[g]}),
                anchor=self.anchor(js[0]["node"])))
        self.used = sorted({i for c in self.chains for i in c["cols"]})
        # Skirt vertices (weighted to any chain joint) and the leg profile.
        skin = np.array(doc["skins"][0]["joints"])
        ps, js_, ws = [], [], []
        for n in nodes:
            if "mesh" not in n:
                continue
            for prim in doc["meshes"][n["mesh"]]["primitives"]:
                a = prim["attributes"]
                ps.append(fp.read_accessor(doc, blob, a["POSITION"]).astype(float))
                js_.append(skin[fp.read_accessor(doc, blob, a["JOINTS_0"]).astype(int)])
                ws.append(fp.read_accessor(doc, blob, a["WEIGHTS_0"]).astype(float))
        p, j, w = np.concatenate(ps), np.concatenate(js_), np.concatenate(ws)
        self.chain_nodes = {n for c in self.chains for n in c["nodes"][:-1]}
        skirt = np.array([self.names[x].startswith("qunbai") for x in j[np.arange(len(j)), w.argmax(1)]])
        self.vp, self.vj, self.vw = p[skirt], j[skirt], w[skirt]
        self.vanchor = np.array([self.anchor(x) for x in self.vj[np.arange(len(self.vj)), self.vw.argmax(1)]])
        self.legs = {}
        self.radius = {}
        for side, s in (("L", "Left"), ("R", "Right")):
            hip = world[humanoid[f"{s.lower()}UpperLeg"]["node"]][:3, 3]
            knee = world[humanoid[f"{s.lower()}LowerLeg"]["node"]][:3, 3]
            self.legs[side] = (f"{s}Leg", hip, knee)
            # Leg surface radius per (distance along the thigh, angle about it), from the rest mesh.
            thigh = [i for i, n in enumerate(self.names) if n in (f"Bip001_{side}_Thigh", f"Bip001_{side}ThighTwist",
                                                                     f"Bip001_{side}ThighTwist1")]
            sel = (np.isin(j, thigh) * w).sum(1) > 0.5
            sel &= ~skirt
            t, ang, r = self.cyl(p[sel], hip, knee)
            tb = np.clip((t / 0.02).astype(int), 0, 19)
            ab = ((ang + np.pi) / (np.pi / 6)).astype(int) % 12
            table = np.zeros((20, 12))
            np.maximum.at(table, (tb, ab), r)
            for _ in range(2):  # fill empty bins from neighbours
                filled = np.maximum.reduce([np.roll(table, 1, 1), np.roll(table, -1, 1), table,
                                            np.roll(table, 1, 0), np.roll(table, -1, 0)])
                table = np.where(table > 0, table, filled)
            self.radius[side] = table

    @staticmethod
    def cyl(points, hip, knee):
        """(t along the thigh, angle about it with 0 = front, radius) in the thigh's rest frame."""
        d = (knee - hip) / np.linalg.norm(knee - hip)
        fwd = np.array([0, 0, 1.0]) - d * d[2]
        fwd /= np.linalg.norm(fwd)
        side = np.cross(d, fwd)
        q = points - hip
        t = q @ d
        return t, np.arctan2(q @ side, q @ fwd), np.linalg.norm(q - np.outer(t, d), axis=1)

    def anchor(self, node):
        """Nearest ancestor (or self) that YSM's skeleton animates: the bone a chain's root rides on."""
        while node is not None:
            name = self.ysm_name[node]
            if name in self.skel_names and not name.startswith(("qunbai", "yibai")):
                return name
            node = self.parent.get(node)
        return "Root"

    def pose(self, clip, t):
        cache = {}
        k = self.unit

        def get(name):
            m = clip.world(name, t, cache)
            c = np.eye(4)
            c[:3, :3] = S * k
            ci = np.eye(4)
            ci[:3, :3] = S / k
            return ci @ m @ c  # VRM metres

        return get

    def run(self, clip, fps, speed, seconds=3.0, record_from=1.5, interp=False, frames=None, timing="ideal"):
        """frames: frame durations to cycle through (default 1/fps each). timing: how YSM's NativeModelState pairs the
        step time with the body motion: "ideal" both from this frame; "stock" (YSM) the motion is the previous frame's
        travel (between the two draws observed before the extraction) but the time is this frame's; "matched" (the fix)
        both are the previous frame's."""
        tails = [[None] * len(c["nodes"]) for c in self.chains]
        vel = np.zeros(3)
        acc = 0.0
        t = 0.0
        out = []
        previous = 1 / fps
        index = 0
        while t < seconds:
            frame = frames[index % len(frames)] if frames is not None else 1 / fps
            index += 1
            t += frame
            travelled = frame if timing == "ideal" else previous
            dt = travelled if timing in ("ideal", "matched") else frame
            previous = frame
            move = np.array([0, 0, -speed * travelled])  # VRM faces +Z: world-fixed points drift back
            get = self.pose(clip, t)
            vel += (move / dt - vel) * min(dt / 0.1, 1)
            if not interp:
                for ct in tails:
                    for i, x in enumerate(ct):
                        if x is not None:
                            ct[i] = (x[0] + move, x[1] + move)
            carry = vel * STEP * (1 - AIR_DRAG)
            acc += min(dt, 0.1)
            steps = int(acc / STEP)
            acc -= steps * STEP
            for st in range(steps):
                g = self.pose(clip, t - frame + (st + 1) / steps * frame) if interp else get
                if interp:  # the patched solver: the frame's body motion and pose arrive spread over its steps
                    for ct in tails:
                        for i, x in enumerate(ct):
                            if x is not None:
                                ct[i] = (x[0] + move / steps, x[1] + move / steps)
                cols = {}
                for idx in self.used:
                    name, a, b, r = self.colliders[idx]
                    m = g(name)
                    cols[idx] = ((m @ np.r_[a, 1])[:3], (m @ np.r_[b, 1])[:3], r)
                transforms = {}
                for ci, c in enumerate(self.chains):
                    base = g(c["anchor"])
                    racc = base[:3, :3].copy()
                    head = (base @ np.r_[c["pos"][0], 1])[:3]
                    for k in range(len(c["nodes"]) - 1):
                        rest = c["pos"][k + 1] - c["pos"][k]
                        length = np.linalg.norm(rest)
                        adir = racc @ rest / length
                        if tails[ci][k] is None:
                            tails[ci][k] = (head + adir * length,) * 2
                        cur, prv = tails[ci][k]
                        keep = (1 - c["drag"][k]) ** (STEP * 60)
                        v = (cur - prv + carry) * keep - carry
                        nxt = cur + v + adir * c["stiff"][k] * STEP + c["gdir"][k] * c["grav"][k] * STEP
                        nxt = head + (nxt - head) / np.linalg.norm(nxt - head) * length
                        nsum = np.zeros(3)
                        contact = False
                        for _ in range(3):
                            pushed = False
                            for idx in c["cols"]:
                                a, b, r = cols[idx]
                                q = closest(nxt, a, b)
                                off = nxt - q
                                d = np.linalg.norm(off)
                                reach = r + c["hit"][k]
                                if d < reach:
                                    n = off / d if d > 1e-9 else adir
                                    nxt = q + n * reach
                                    nsum += n
                                    contact = pushed = True
                            nxt = head + (nxt - head) / np.linalg.norm(nxt - head) * length
                            if not pushed:
                                break
                        prev = cur
                        if contact and np.linalg.norm(nsum) > 1e-9:
                            n = nsum / np.linalg.norm(nsum)
                            slide = nxt - cur
                            slide -= n * (slide @ n)
                            prev = nxt - slide * (1 - FRICTION)
                        tails[ci][k] = (nxt, prev)
                        racc = rot_between(adir, nxt - head) @ racc
                        transforms[c["nodes"][k]] = (racc, c["pos"][k], head.copy())
                        head = nxt.copy()
            if t < record_from:
                continue
            # Draw like the native: aim each joint, in the current animated pose, at its tail interpolated between
            # the last two steps.
            alpha = min(max(acc / STEP, 0.0), 1.0)
            transforms = {}
            for ci, c in enumerate(self.chains):
                base = get(c["anchor"])
                racc = base[:3, :3].copy()
                head = (base @ np.r_[c["pos"][0], 1])[:3]
                for k in range(len(c["nodes"]) - 1):
                    rest = c["pos"][k + 1] - c["pos"][k]
                    length = np.linalg.norm(rest)
                    adir = racc @ rest / length
                    cur, prv = tails[ci][k]
                    target = prv + (cur - prv) * alpha
                    racc = rot_between(adir, target - head) @ racc
                    transforms[c["nodes"][k]] = (racc, c["pos"][k], head.copy())
                    head = head + (target - head) / np.linalg.norm(target - head) * length
            verts = np.zeros_like(self.vp)
            for k in range(self.vj.shape[1]):
                jj, ww = self.vj[:, k], self.vw[:, k]
                for n in np.unique(jj[ww > 0]):
                    m = (jj == n) & (ww > 0)
                    if n in transforms:
                        r, p0, p1 = transforms[n]
                        verts[m] += ww[m, None] * ((self.vp[m] - p0) @ r.T + p1)
                    else:
                        base = get(self.anchor(n))
                        verts[m] += ww[m, None] * ((self.vp[m] @ base[:3, :3].T) + base[:3, 3])
            pen = np.full(len(verts), -1.0)
            segs = []
            for side, (name, hip, knee) in self.legs.items():
                m = get(name)
                a = (m @ np.r_[hip, 1])[:3]
                b = (m @ np.r_[knee, 1])[:3]
                segs.append((a, b))
                local = (verts - m[:3, 3]) @ m[:3, :3]  # posed -> the thigh's rest frame
                tt, ang, dist = self.cyl(local, hip, knee)
                inside = (tt >= 0.06) & (tt < 0.40)  # above 6 cm the waistband is snug against the hip by design
                tb = np.clip((tt / 0.02).astype(int), 0, 19)
                ab = ((ang + np.pi) / (np.pi / 6)).astype(int) % 12
                rr = self.radius[side][tb, ab]
                pen = np.maximum(pen, np.where(inside, rr - dist, -1.0))
            out.append((t, pen, verts, segs, [[x[0].copy() if x is not None else None for x in ct] for ct in tails]))
        return out
