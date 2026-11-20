"""Prototype of the held-item arm solver (ported to HeldItems.java once tuned).

For each key of a locomotion clip: the free pose's wrist (relative to the shoulder, in the chest's frame) is pulled
toward the item class's hold point with a reduced swing, the arm is solved by two-bone IK, the hand takes a fixed grip
relative to the forearm, and the wrist goal is pushed out of the posed body's per-height cross-section until the item
and hand clear it.
"""
import math
import re

import numpy as np

from collide import Body, descends, item_points, item_transform, t4
from fk import Model, rx, ry, rz

TOOL_POINTS = None


def euler_zyx(m):
    x = math.atan2(m[2, 1], m[2, 2])
    y = -math.asin(max(-1, min(1, m[2, 0])))
    z = math.atan2(m[1, 0], m[0, 0])
    return np.array([x, y, z])


def local_matrix(rest, key):
    k = np.radians(key)
    e = np.array(rest) + np.array([-k[0], -k[1], k[2]])
    return rz(e[2]) @ ry(e[1]) @ rx(e[0])


def key_of(rest, m, previous=None):
    e = euler_zyx(m)
    if previous is not None:
        e = unwrap(e, previous)
    k = e - np.array(rest)
    return np.array([-math.degrees(k[0]), -math.degrees(k[1]), math.degrees(k[2])]), e


def unwrap(e, prev):
    best, bd = e, 1e9
    twin = np.array([e[0] + math.pi, math.pi - e[1], e[2] + math.pi])
    for base in (e, twin):
        c = np.array([v + 2 * math.pi * round((p - v) / (2 * math.pi)) for v, p in zip(base, prev)])
        d = np.abs(c - prev).sum()
        if d < bd:
            best, bd = c, d
    return best


def frame(hinge, along):
    y = -along / np.linalg.norm(along)
    x = hinge - y * hinge.dot(y)
    x /= np.linalg.norm(x)
    z = np.cross(x, y)
    return np.stack([x, y, z], 1)


class Profile:
    """Per-height (1 px slabs) cross-section of the posed body: max radius per angle bin around the slab centre."""

    BINS = 48

    def __init__(self, points):
        self.lo = math.floor(points[:, 1].min())
        n = int(math.ceil(points[:, 1].max())) - self.lo + 1
        self.centre = np.zeros((n, 2))
        self.radius = np.zeros((n, self.BINS))
        self.valid = np.zeros(n, bool)
        idx = np.floor(points[:, 1] - self.lo).astype(int)
        for s in range(n):
            p = points[idx == s]
            if len(p) < 8:
                continue
            c = np.array([0.0, np.median(p[:, 2])])
            d = p[:, [0, 2]] - c
            ang = ((np.arctan2(d[:, 1], d[:, 0]) + math.pi) / (2 * math.pi) * self.BINS).astype(int) % self.BINS
            r = np.linalg.norm(d, axis=1)
            rad = np.zeros(self.BINS)
            np.maximum.at(rad, ang, r)
            # close gaps between neighbouring bins
            rad = np.maximum(rad, np.maximum(np.roll(rad, 1), np.roll(rad, -1)) * 0.97)
            self.centre[s], self.radius[s], self.valid[s] = c, rad, True

    def push_many(self, pts, margin):
        """Largest outward xz displacement over pts (see push)."""
        best = np.zeros(3)
        for dy in (-1, 0, 1):
            s = np.floor(pts[:, 1] - self.lo).astype(int) + dy
            ok = (s >= 0) & (s < len(self.valid))
            ok[ok] &= self.valid[s[ok]]
            if not ok.any():
                continue
            p, s = pts[ok], s[ok]
            d = p[:, [0, 2]] - self.centre[s]
            r = np.linalg.norm(d, axis=1)
            a = (((np.arctan2(d[:, 1], d[:, 0]) + math.pi) / (2 * math.pi)) * self.BINS).astype(int) % self.BINS
            need = self.radius[s, a] + margin - r
            k = np.argmax(need)
            if need[k] > np.linalg.norm(best):
                u = d[k] / max(r[k], 1e-6)
                best = np.array([u[0] * need[k], 0, u[1] * need[k]])
        return best

    def push(self, p, margin):
        """Outward xz displacement that moves p out of the cross-section of its slab and the slabs beside it."""
        best = np.zeros(3)
        for dy in (-1, 0, 1):
            s = int(math.floor(p[1] - self.lo)) + dy
            if s < 0 or s >= len(self.valid) or not self.valid[s]:
                continue
            d = np.array([p[0], p[2]]) - self.centre[s]
            r = np.linalg.norm(d)
            a = int((math.atan2(d[1], d[0]) + math.pi) / (2 * math.pi) * self.BINS) % self.BINS
            need = self.radius[s, a] + margin - r
            if need > 0:
                u = d / max(r, 1e-6)
                v = np.array([u[0] * need, 0, u[1] * need])
                if np.linalg.norm(v) > np.linalg.norm(best):
                    best = v
        return best


def tool_points():
    t = np.linspace(0, 1, 12)
    diag = np.stack([2 / 16 + t * 12.5 / 16, 2 / 16 + t * 12.5 / 16, np.full_like(t, 0.5)], 1)
    head = np.array([[x / 16, y / 16, 0.5] for x, y in [(15, 10), (10, 15), (15, 15), (13, 15), (15, 13), (8, 15),
                                                        (15, 8)]])
    return diag, head


class Solver:
    def __init__(self, prefix, sub=3):
        self.model = Model(prefix)
        self.body = Body(prefix, self.model)
        names = self.body.names
        strongest = self.body.joints[np.arange(len(self.body.joints)), self.body.weights.argmax(1)]
        skip = np.array([descends(self.model, n, "RightArm") or descends(self.model, n, "LeftArm")
                         or descends(self.model, n, "Head") or bool(re.search(r"(?i)hair", n)) for n in names])
        self.keep = np.nonzero(~skip[strongest])[0][::sub]
        b = self.model.bones
        self.upper = np.linalg.norm(np.subtract(b["RightForeArm"]["pivot"], b["RightArm"]["pivot"]))
        self.lower = np.linalg.norm(np.subtract(b["RightHand"]["pivot"], b["RightForeArm"]["pivot"]))

    def posed_body(self, rot, pos):
        # skin only kept vertices
        cache = {}
        mats = np.stack([self.model.world(n, rot, pos, None, cache) for n in self.body.names])
        p = self.body.pos[self.keep]
        h = np.c_[p, np.ones(len(p))]
        out = np.zeros_like(p)
        for k in range(4):
            w = self.body.weights[self.keep, k]
            m = mats[self.body.joints[self.keep, k]]
            out += w[:, None] * np.einsum("nij,nj->ni", m, h)[:, :3]
        return out, cache


def solve_arm(model, rot, pos, side, wrist_goal, cache):
    """Two-bone IK: keys for side Arm and ForeArm putting the Hand pivot at wrist_goal (world)."""
    b = model.bones
    parent = model.world(b[side + "Arm"]["parent"], rot, pos, None, cache)
    pr = parent[:3, :3]
    shoulder = (model.world(side + "Arm", rot, pos, None, cache) @ np.append(b[side + "Arm"]["pivot"], 1))[:3]
    pu, pl, pe = (np.array(b[side + n]["pivot"]) for n in ("Arm", "ForeArm", "Hand"))
    lu, ll = np.linalg.norm(pl - pu), np.linalg.norm(pe - pl)
    reach = wrist_goal - shoulder
    dist = min(np.linalg.norm(reach), (lu + ll) * 0.9995)
    u = reach / np.linalg.norm(reach)
    outward = -1.0 if side == "Left" else 1.0
    # elbow toward the back and a little out, carried with the reach like EndfieldLocomotion's swingPole
    pole = np.array([0.25 * outward, 0, 1.0])
    pole = pole - u * pole.dot(u)
    v = pole / np.linalg.norm(pole)
    a = (lu * lu - ll * ll + dist * dist) / (2 * dist)
    r = math.sqrt(max(0, lu * lu - a * a))
    middle = shoulder + u * a + v * r
    reached = shoulder + u * dist
    au, al = middle - shoulder, reached - middle
    hinge = np.cross(au, al)
    if np.linalg.norm(hinge) < 1e-4 * lu * ll:
        hinge = np.cross(pole, au)
    hinge /= np.linalg.norm(hinge)
    rest_hinge = np.array([1.0, 0, 0])
    wu = frame(hinge, au) @ frame(rest_hinge, pl - pu).T
    wl = frame(hinge, al) @ frame(rest_hinge, pe - pl).T
    return pr.T @ wu, wu.T @ wl, wl, shoulder


CLASSES = {
    # wrist goal relative to the shoulder in the chest frame, as shares of the arm length (x out, y up, z back);
    # grip: hand key (deg) relative to the forearm; swing: share of the free swing kept; items checked for clearance
    "tool": dict(hold=(0.22, -0.90, -0.20), grip=(40, 15, 0), swing=0.55, items=("tool",)),
    "generic": dict(hold=(0.20, -0.62, -0.50), grip=(0, 0, 0), swing=0.35, items=("block", "flat", "bow", "crossbow")),
    "shield": dict(hold=(0.22, -0.80, -0.30), grip=(0, 0, 0), swing=0.35, items=("shield",)),
}


def class_points(kind, left):
    """Item sample points in the locator frame (pixels), before the locator's own scale."""
    pts = []
    for item in CLASSES[kind]["items"]:
        if item == "tool":
            p = item_points("sword")
            m = item_transform("sword", left)
        else:
            p = item_points(item)
            m = item_transform(item, left)
        pts.append((m @ np.c_[p, np.ones(len(p))].T).T[:, :3])
    return np.concatenate(pts)


def build(solver, clip, side, kind, margin=0.8, item_scale=0.7, iterations=8, verbose=False):
    model = solver.model
    anim = model.anims[clip]
    left = side == "Left"
    times = sorted(float(t) for t in anim["bones"][side + "Arm"]["rotation"]) \
        if isinstance(anim["bones"][side + "Arm"]["rotation"], dict) else [0.0]
    spec = CLASSES[kind]
    outward = -1.0 if left else 1.0
    length = solver.upper + solver.lower
    hold = np.array(spec["hold"]) * length * np.array([outward, 1, 1])
    grip = np.array(spec["grip"], float) * np.array([1, outward, outward])
    item = class_points(kind, left)
    b = model.bones
    # pass 1: free wrists in the chest frame
    free = []
    for t in times:
        rot, pos, _ = model.pose([clip], t, False)
        cache = {}
        chest = model.world("UpBody", rot, pos, None, cache)[:3, :3]
        shoulder = (model.world(side + "Arm", rot, pos, None, cache) @ np.append(b[side + "Arm"]["pivot"], 1))[:3]
        wrist = (model.world(side + "Hand", rot, pos, None, cache) @ np.append(b[side + "Hand"]["pivot"], 1))[:3]
        free.append(chest.T @ (wrist - shoulder))
    mean = np.mean(free, 0)
    keys = {side + "Arm": {}, side + "ForeArm": {}, side + "Hand": {}}
    prev = {}
    worst = 0
    for t, w in zip(times, free):
        rot, pos, _ = model.pose([clip], t, False)
        body, cache = solver.posed_body(rot, pos)
        profile = Profile(body)
        chest = model.world("UpBody", rot, pos, None, cache)[:3, :3]
        goal_local = hold + spec["swing"] * (w - mean)
        shoulder = (model.world(side + "Arm", rot, pos, None, cache) @ np.append(b[side + "Arm"]["pivot"], 1))[:3]
        goal = shoulder + chest @ goal_local
        for it in range(iterations):
            arm, fore, wl, _ = solve_arm(model, rot, pos, side, goal, cache)
            r = dict(rot)
            r[side + "Arm"], _ = key_of(b[side + "Arm"]["rotate"], arm)
            r[side + "ForeArm"], _ = key_of(b[side + "ForeArm"]["rotate"], fore)
            r[side + "Hand"] = grip
            c2 = {}
            loc = model.world(side + "HandLocator", r, pos, None, c2)
            pivot = np.array(b[side + "HandLocator"]["pivot"])
            m = loc @ t4(t=pivot) @ t4(np.eye(3) * item_scale)
            pts = (m @ np.c_[item, np.ones(len(item))].T).T[:, :3]
            hand = (model.world(side + "Hand", r, pos, None, c2) @ np.append(b[side + "Hand"]["pivot"], 1))[:3]
            pts = np.r_[pts, [hand, (loc @ np.append(pivot, 1))[:3]]]
            push = profile.push_many(pts, margin)
            if np.linalg.norm(push) < 0.05:
                break
            goal = goal + push * 1.1
        worst = max(worst, np.linalg.norm(push))
        for name, mat in ((side + "Arm", arm), (side + "ForeArm", fore)):
            k, e = key_of(b[name]["rotate"], mat, prev.get(name))
            prev[name] = e
            keys[name][f"{t:.4f}"] = [round(float(v), 2) for v in k]
        keys[side + "Hand"][f"{t:.4f}"] = [round(float(v), 2) for v in grip]
    if verbose:
        print(f"{clip} {side} {kind}: residual push {worst:.2f}px")
    return keys


def held_clip(solver, clip, kinds):
    """The clip with its arm channels replaced by the held variant for (side, kind) pairs."""
    anim = solver.model.anims[clip]
    out = {"loop": True, "bones": {k: dict(v) for k, v in anim["bones"].items()}}
    if "animation_length" in anim:
        out["animation_length"] = anim["animation_length"]
    for side, kind in kinds:
        for bone, ks in build(solver, clip, side, kind, verbose=True).items():
            out["bones"][bone] = {"rotation": ks if len(ks) > 1 else list(ks.values())[0]}
    return out
