"""Forward kinematics over generated YSM clips: where the hand locator and a held item go.

fk.py <prefix> clip[+layer...] [--carry] ; prefix = gen/<name> (reads .skel.json and .main.json)
Conventions as EndfieldLocomotion: local = rotationZYX(rest + (-kx, -ky, kz)) about the pivot, Root position (-x, y, z).
"""
import json
import math
import sys

import numpy as np


def rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


ENV = {"tool": False, "shield": False, "crossbow": False, "stance": True}


def _top(s, ch):
    depth = 0
    for i, c in enumerate(s):
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == ch and depth == 0:
            return i
    return -1


def _strip(s):
    s = s.strip()
    while s.startswith("(") and s.endswith(")"):
        depth = 0
        for i, c in enumerate(s):
            depth += c == "("
            depth -= c == ")"
            if depth == 0 and i < len(s) - 1:
                return s
        s = s[1:-1].strip()
    return s


def _cond(c, holding):
    if "#minecraft:tools" in c:
        return holding and ENV["tool"]
    if "':shield'" in c:
        return holding and ENV["shield"]
    if "':crossbow'" in c:
        return holding and ENV["crossbow"]
    if c.startswith("(ysm.has_") or c.startswith("ysm.has_") and "&&" in c:
        return holding and not ENV["stance"]  # carrying(): rests in the stance clips
    if c.startswith("ysm.has_"):
        return holding
    raise ValueError(c)


def molang(value, holding):
    if isinstance(value, (int, float)):
        return float(value)
    v = _strip(value)
    try:
        return float(v)
    except ValueError:
        pass
    q = _top(v, "?")
    if q >= 0:
        rest = v[q + 1:]
        c = _top(rest, ":")
        return molang(rest[:c], holding) if _cond(v[:q].strip(), holding) else molang(rest[c + 1:], holding)
    for op in (" + ", " - "):
        i = _top(v, op.strip()) if op.strip() in v else -1
        if i > 0:
            a, b = molang(v[:i], holding), molang(v[i + 1:], holding)
            return a + b if op == " + " else a - b
    raise ValueError(v)


def sample(channel, t, holding):
    if isinstance(channel, list):
        return np.array([molang(x, holding) for x in channel])
    keys = sorted((float(k), v) for k, v in channel.items())
    if t <= keys[0][0]:
        return np.array([molang(x, holding) for x in keys[0][1]])
    for (t0, v0), (t1, v1) in zip(keys, keys[1:]):
        if t <= t1:
            f = (t - t0) / (t1 - t0) if t1 > t0 else 0
            a = np.array([molang(x, holding) for x in v0])
            b = np.array([molang(x, holding) for x in v1])
            return a + (b - a) * f
    return np.array([molang(x, holding) for x in keys[-1][1]])


class Model:
    def __init__(self, prefix):
        self.bones = {b["name"]: b for b in json.load(open(prefix + ".skel.json"))}
        self.anims = json.load(open(prefix + ".main.json"))["animations"]

    def pose(self, clips, t, holding=True):
        rot = {}
        pos = np.zeros(3)
        scale = {}
        for name in clips:
            clip = self.anims[name] if isinstance(name, str) else name
            if not isinstance(name, str) and clip.get("override"):
                for bone in clip["bones"]:
                    rot.pop(bone, None)
            length = clip.get("animation_length", 0) or 1
            tt = t % length if clip.get("loop") else min(t, length)
            for bone, ch in clip["bones"].items():
                if "rotation" in ch:
                    rot[bone] = rot.get(bone, np.zeros(3)) + sample(ch["rotation"], tt, holding)
                if "position" in ch and bone == "Root":
                    pos = pos + sample(ch["position"], tt, holding)
                if "scale" in ch:
                    scale[bone] = sample(ch["scale"], tt, holding)
        return rot, pos, scale

    def world(self, name, rot, pos, scale=None, cache=None):
        """4x4 transform of a bone (turning about its pivot), parents first."""
        cache = {} if cache is None else cache
        if name in cache:
            return cache[name]
        b = self.bones[name]
        parent = np.eye(4)
        if b["parent"]:
            parent = self.world(b["parent"], rot, pos, scale, cache)
        else:
            parent = np.eye(4)
            parent[:3, 3] = [-pos[0], pos[1], pos[2]]
        rest = np.array(b["rotate"])
        k = np.radians(rot.get(name, np.zeros(3)))
        e = rest + np.array([-k[0], -k[1], k[2]])
        r = rz(e[2]) @ ry(e[1]) @ rx(e[0])
        p = np.array(b["pivot"])
        m = np.eye(4)
        m[:3, :3] = r
        if scale and name in scale:
            m[:3, :3] = r * scale[name][0]
        m[:3, 3] = p - m[:3, :3] @ p
        out = parent @ m
        cache[name] = out
        return out

    def point(self, name, rot, pos, scale=None, cache=None):
        m = self.world(name, rot, pos, scale, cache)
        return (m @ np.append(self.bones[name]["pivot"], 1))[:3]


def table(model, clips, holding, steps=12):
    length = max((model.anims[c].get("animation_length", 1) for c in clips if model.anims[c].get("loop")), default=1)
    for i in range(steps):
        t = length * i / steps
        rot, pos, scale = model.pose(clips, t, holding)
        cache = {}
        hand = model.point("RightHandLocator", rot, pos, scale, cache)
        hip = model.point("RightLeg", rot, pos, scale, cache)
        knee = model.point("RightLowerLeg", rot, pos, scale, cache)
        m = model.world("RightHandLocator", rot, pos, scale, cache)
        fwd = m[:3, :3] @ np.array([0, 0, -1.0])
        print(f"t={t:5.2f} hand={np.round(hand, 2)} hip={np.round(hip, 2)} knee={np.round(knee, 2)} "
              f"item_up={np.round(fwd / np.linalg.norm(fwd), 2)}")


if __name__ == "__main__":
    model = Model(sys.argv[1])
    clips = sys.argv[2].split("+")
    table(model, clips, "--empty" not in sys.argv)
