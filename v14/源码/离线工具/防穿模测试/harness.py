#!/usr/bin/env python3
"""Anti-clip harness: how far hair, skirts and accessories on spring bones go into the body over a scripted sequence of
the model's own main animations, through YSM's native extraction (springs) and render path.

Usage: harness.py <dump dir (tools/anticlip/dump.sh)> <main.animation.json> [--hz 60] [--json out.json]
       [--positions frames.npz --keep idx,...]

The pose stream (idle, walk, run, run with fast yaw turns, idle with yaw snaps, sneak, sneak walk, sit, prone enter and
exit) is evaluated here from the Bedrock keyframes (Molang through tools/fpbench/fpb/molang.py, ctrl.* flags of the
playing state set, everything else 0), cross-faded 0.2 s between clips, with Minecraft's walk / sprint / sneak speeds as
body travel. The native side is the env-driven gtest AntiClipTest.Replay in ysm-test (YSM_TEST, default the
native-release build).

Soft vertices: at least half their weight on spring-simulated bones (a spring joint's bone or below). Part of a soft
vertex: hair when its chain hangs below the neck, skirt when its rest position is below the hips' height plus a
quarter thigh, accessory otherwise. Body vertices: under 5% spring weight.

Metrics per part, over measured frames (every frame at 20 / 30 Hz, every second at 60 Hz) and soft vertices:
- proxy: depth inside a capsule fitted here to the body vertices of each segment (head, neck, five torso bands as
  horizontal capsules, upper arms, forearms, thighs, calves) beyond the vertex's rest depth; capsules the vertex is
  more than half a radius inside at rest (hair roots in the head) are skipped for it.
- mesh: depth behind the nearest body vertices (k=4, all four tangent planes agree, within 8 cm) beyond the rest
  depth, the posed body mesh's vertex normals as the sides.
Reported: share of soft vertex-frames more than 5 mm inside, p99 and max depth (cm, real size), and per clip the
worst frame.

Garment (skirts and hems): every vertex that carries at least half its weight on a hem chain's bones, the chain's
unsimulated bones between the trunk and its first joint (the waistband) included, so cloth that follows the pelvis or
spine rigidly is measured too. A hem chain hangs from the trunk, starts around the hips and reaches below the hip
joints. For it the same proxy and mesh depths are reported against the trunk and legs, plus a tear metric: the
garment's mesh edges (vertices welded by position), posed length over rest length: p99, max, the share above 1.25x and
1.5x, per clip maxima and the bone pairs the worst edges span.
"""
import argparse
import json
import math
import os
import struct
import subprocess
import sys
import tempfile
import time

import numpy as np
from scipy.spatial import cKDTree

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "fpbench"))
from fpb import molang  # noqa: E402

YSM_TEST = os.environ.get("YSM_TEST", os.path.expanduser(
    "~/ysm/nativebuild/src/YesSteveModel-Native/build/native-release/modules/test/ysm-test"))
MAGIC = 0x53504341
WALK, SPRINT, SNEAK = 4.317, 5.612, 1.295  # m/s
FADE = 0.2
INSIDE_CM = 0.5
# Hem chains reach at most this many thighs sideways from the hips (AntiClip.HEM_SIDE).
HEM_SIDE = float(os.environ.get("HEM_SIDE", "1.2"))

# (clip, seconds, speed m/s, yaw deg/s or ("snap", deg, every s), ctrl flags)
SCRIPT = [
    ("idle", 2.0, 0.0, 0.0, ("idle",)),
    ("walk", 3.0, WALK, 0.0, ("walk",)),
    ("run", 3.0, SPRINT, 0.0, ("run",)),
    ("run", 3.0, SPRINT, ("zigzag", 400.0, 0.5), ("run",)),
    ("idle", 2.5, 0.0, ("snap", 180.0, 0.6), ("idle",)),
    ("sneak", 1.5, 0.0, 0.0, ("sneak",)),
    ("sneaking", 2.0, SNEAK, 0.0, ("sneaking",)),
    ("idle", 1.0, 0.0, 0.0, ("idle",)),
    ("sit", 2.5, 0.0, 0.0, ("sit",)),
    ("idle", 1.0, 0.0, 0.0, ("idle",)),
    ("bfm:prone_enter", 1.2, 0.0, 0.0, ("prone",)),
    ("bfm:prone_exit", 1.0, 0.0, 0.0, ()),
    ("idle", 1.0, 0.0, 0.0, ("idle",)),
]


def load_dump(path):
    meta = json.load(open(os.path.join(path, "meta.json")))
    v, t = meta["vertices"], meta["triangles"]
    raw = open(os.path.join(path, "mesh.bin"), "rb").read()
    f = np.frombuffer(raw, "<f4", count=v * 10)
    i = np.frombuffer(raw, "<i4", offset=v * 40)
    mesh = dict(pos=f[:v * 3].reshape(v, 3), nrm=f[v * 3:v * 6].reshape(v, 3), w=f[v * 6:].reshape(v, 4),
                j=i[:v * 4].reshape(v, 4), tri=i[v * 4:v * 4 + t * 3].reshape(t, 3))
    return meta, mesh


# ---------------------------------------------------------------- vertex classes and the metric proxy
def classify(meta, mesh):
    bones = meta["bones"]
    n = len(bones)
    parent = np.array([b["parent"] for b in bones])
    names = [b["name"] for b in bones]
    index = {name: i for i, name in enumerate(names)}
    pivot = np.array([b["pivot"] for b in bones]) / 16.0
    jointed = {j[0] for j in meta["joints"]}
    chain_of = {j[0]: j[1] for j in meta["joints"]}

    def ancestors(i):
        while i >= 0:
            yield i
            i = parent[i]

    spring = np.zeros(n, bool)
    chain = np.full(n, -1)
    for b in range(n):
        for a in ancestors(b):
            if a in jointed:
                spring[b] = True
                chain[b] = chain_of[a]  # nearest joint's chain
                break
    w, j = mesh["w"], mesh["j"]
    jj = np.clip(j, 0, n - 1)
    sw = (w * spring[jj]).sum(1)
    soft = sw >= 0.5
    body = sw < 0.05
    dom = jj[np.arange(len(j)), np.argmax(w, 1)]
    # Spring weight's dominant spring bone -> chain -> part.
    ws = np.where(spring[jj], w, 0)
    sdom = jj[np.arange(len(j)), np.argmax(ws, 1)]
    head_set = set(a for a in range(n) if any(names[x] == "AllHead" for x in ancestors(a)))
    hips_y = pivot[index["AllBody"]][1]
    thigh = abs(pivot[index["LeftLeg"]][1] - pivot[index["LeftLowerLeg"]][1])
    part = np.full(len(j), "", object)
    chain_part = {}
    for c in set(chain[sdom[soft]]):
        sel = soft & (chain[sdom] == c)
        root_bones = [b for b in jointed if chain_of[b] == c]
        root = min(root_bones, key=lambda b: len(list(ancestors(b))))
        attach = parent[root]
        if attach in head_set or (attach >= 0 and names[attach] in ("AllHead", "Head")):
            label = "hair"
        elif np.median(mesh["pos"][sel, 1]) < hips_y + 0.25 * thigh:
            label = "skirt"
        else:
            label = "accessory"
        part[sel] = label
        chain_part[int(c)] = label
    return dict(soft=soft, body=body, dom=dom, part=part, names=names, index=index, pivot=pivot, parent=parent,
                spring=spring, hips_y=hips_y, thigh=thigh, chain_part=chain_part)


def segment_of(cls):
    """Body segment per bone: the nearest humanoid ancestor's segment, torso for the trunk."""
    names, parent = cls["names"], cls["parent"]
    seg_names = {"Head": "head", "AllHead": "neck", "LeftArm": "upperarm_l", "RightArm": "upperarm_r",
                 "LeftForeArm": "forearm_l", "RightForeArm": "forearm_r", "LeftHand": "hand", "RightHand": "hand",
                 "LeftLeg": "thigh_l", "RightLeg": "thigh_r", "LeftLowerLeg": "calf_l", "RightLowerLeg": "calf_r",
                 "LeftFoot": "foot", "RightFoot": "foot", "AllBody": "torso", "UpBody": "torso", "DownBody": "torso"}
    out = []
    for b in range(len(names)):
        a, seg = b, None
        while a >= 0 and seg is None:
            seg = seg_names.get(names[a])
            a = parent[a]
        out.append(seg or "other")
    return np.array(out, object)


def garment(meta, mesh, cls, side_limit=None):
    """Hem chains, their bones, the garment vertices, welded mesh edges and rest lengths (None without a hem).

    Chains that share vertices (a vertex weighted to bones of both) carry one piece of cloth; a hem is such a group
    with at least three chains that hang from the trunk around the hips and reach below the hip joints (side_limit:
    how far sideways from the hips in thighs, default HEM_SIDE)."""
    side_limit = HEM_SIDE if side_limit is None else side_limit
    names, parent, pivot = cls["names"], cls["parent"], cls["pivot"]
    n = len(names)
    seg_named = {"Head", "AllHead", "LeftArm", "RightArm", "LeftForeArm", "RightForeArm", "LeftHand", "RightHand",
                 "LeftLeg", "RightLeg", "LeftLowerLeg", "RightLowerLeg", "LeftFoot", "RightFoot", "AllBody", "UpBody",
                 "DownBody", "MAllBody", "UpperBody", "Arm", "MHead"}
    seg = segment_of(cls)
    idx = cls["index"]
    thigh = cls["thigh"]
    hip = 0.5 * (pivot[idx["LeftLeg"]] + pivot[idx["RightLeg"]])
    chains = {}
    for j in meta["joints"]:
        chains.setdefault(j[1], []).append(j[0])
    children = {}
    carries_body = np.zeros(n, bool)  # a humanoid bone in the subtree
    for b in range(n):
        if parent[b] >= 0:
            children.setdefault(parent[b], []).append(b)
        if names[b] in seg_named:
            a = b
            while a >= 0:
                carries_body[a] = True
                a = parent[a]
    info = {}
    owner = np.full(n, -1)
    for c, bones in chains.items():
        members = set(bones)
        root = next((b for b in bones if parent[b] not in members), None)
        if root is None:
            continue
        # Unsimulated bones between the trunk and the first joint (the waistband).
        band = []
        a = parent[root]
        while a >= 0 and not carries_body[a] and not cls["spring"][a]:
            band.append(a)
            a = parent[a]
        tips = [pivot[k] for b in bones for k in children.get(b, [])] + [pivot[b] for b in bones]
        low = min(p[1] for p in tips)
        side = max(math.hypot(p[0] - hip[0], p[2] - hip[2]) for p in tips)
        ry = pivot[root][1]
        hem_like = (a >= 0 and seg[a] == "torso" and hip[1] - 0.3 * thigh <= ry <= hip[1] + 0.9 * thigh
                    and low <= hip[1] - 0.1 * thigh and side <= side_limit * thigh)
        info[c] = dict(root=root, bones=bones, band=band, name=names[root], hem_like=hem_like)
        for b in range(n):
            a = b
            while a >= 0:
                if a in members or a in band:
                    owner[b] = c
                    break
                a = parent[a]
    w, j = mesh["w"], np.clip(mesh["j"], 0, n - 1)
    vo = np.where(w > 0.05, owner[j], -1)
    link = {}
    for a in range(4):
        for b in range(a + 1, 4):
            sel = (vo[:, a] >= 0) & (vo[:, b] >= 0) & (vo[:, a] != vo[:, b])
            pairs, counts = np.unique(np.sort(np.stack([vo[sel, a], vo[sel, b]], 1), 1), axis=0, return_counts=True)
            for (x, y), k in zip(pairs, counts):
                link[(int(x), int(y))] = link.get((int(x), int(y)), 0) + int(k)
    group = {c: c for c in info}

    def find(c):
        while group[c] != c:
            c = group[c]
        return c

    for (x, y), k in link.items():
        if k >= 10 and x in group and y in group:
            group[find(x)] = find(y)
    hem = {}
    listed = set(x for x in os.environ.get("HEM_CHAINS", "").split(",") if x)
    if listed:  # humanoid.json anti_clip_hem_chains: chains with a joint on a listed bone
        for c in info:
            if any(names[b] in listed for b in chains[c]):
                hem[c] = info[c]
    else:
        for g in set(find(c) for c in info):
            members = [c for c in info if find(c) == g and info[c]["hem_like"]]
            if len(members) >= 3:
                for c in members:
                    hem[c] = info[c]
    if not hem:
        return None
    hem_owner = np.isin(owner, list(hem))
    gw = (w * hem_owner[j]).sum(1)
    verts = np.where(gw >= 0.5)[0]
    if len(verts) < 20:
        return None
    wo = np.where(hem_owner[j], w, 0)
    gdom = j[np.arange(len(j)), np.argmax(wo, 1)]
    # Edges between garment vertices, welded by position.
    key = np.round(mesh["pos"] * 2e4).astype(np.int64)
    _, weld = np.unique(key, axis=0, return_inverse=True)
    weld = weld.ravel()
    is_g = np.zeros(len(j), bool)
    is_g[verts] = True
    rep = np.full(weld.max() + 1, -1)
    rep[weld[verts]] = verts  # one garment vertex per welded position
    tri = mesh["tri"]
    e = np.concatenate([tri[:, [0, 1]], tri[:, [1, 2]], tri[:, [2, 0]]])
    e = e[is_g[e].all(1)]
    e = rep[weld[e]]
    e = np.unique(np.sort(e, 1), axis=0)
    e = e[e[:, 0] != e[:, 1]]
    rest = np.linalg.norm(mesh["pos"][e[:, 0]] - mesh["pos"][e[:, 1]], axis=1)
    keep = rest > 0.002  # shorter edges (under 2 mm) are sliver triangles: any offset multiplies their length
    e, rest = e[keep], rest[keep]
    # Body the garment is measured against: trunk and legs, no spring or garment weight.
    body = np.where((gw < 0.05) & cls["body"] & np.isin(seg[cls["dom"]], ["torso", "thigh_l", "thigh_r", "calf_l",
                                                                         "calf_r"]))[0]
    return dict(hem=hem, owner=owner, verts=verts, gdom=gdom, edges=e, rest=rest, body=body, gw=gw)


def fit_proxy(cls, mesh):
    """Capsules (bone, head, tail, r_head, r_tail) in rest model units fitted to body vertices."""
    seg = segment_of(cls)
    pos, body, dom = mesh["pos"], cls["body"], cls["dom"]
    vseg = seg[dom]
    piv, idx = cls["pivot"], cls["index"]
    caps = []

    def radial(points, a, b):
        ab = b - a
        t = np.clip((points - a) @ ab / max(ab @ ab, 1e-12), 0, 1)
        return t, np.linalg.norm(points - (a + t[:, None] * ab), axis=1)

    def limb(name, bone, a, b, q=0.75):
        sel = body & (vseg == name)
        p = pos[sel]
        if len(p) < 30:
            return
        ab = b - a
        t = (p - a) @ ab / (ab @ ab)
        p = p[(t > 0.0) & (t < 1.0)]
        if len(p) < 30:
            return
        t, r = radial(p, a, b)
        r0 = np.quantile(r[t < 0.5], q) if (t < 0.5).sum() > 10 else np.quantile(r, q)
        r1 = np.quantile(r[t >= 0.5], q) if (t >= 0.5).sum() > 10 else np.quantile(r, q)
        caps.append((name, idx[bone], a, b, r0, r1))

    for side, s in (("l", "Left"), ("r", "Right")):
        limb("upperarm_" + side, s + "Arm", piv[idx[s + "Arm"]], piv[idx[s + "ForeArm"]])
        limb("forearm_" + side, s + "ForeArm", piv[idx[s + "ForeArm"]], piv[idx[s + "Hand"]])
        limb("thigh_" + side, s + "Leg", piv[idx[s + "Leg"]], piv[idx[s + "LowerLeg"]])
        limb("calf_" + side, s + "LowerLeg", piv[idx[s + "LowerLeg"]], piv[idx[s + "Foot"]])
    # Neck: AllHead pivot to Head pivot.
    limb("neck", "AllHead", piv[idx["AllHead"]], piv[idx["Head"]], q=0.6)
    # Head: vertical capsule through the middle of the head vertices.
    p = pos[body & (vseg == "head")]
    if len(p) > 30:
        lo, hi = np.quantile(p[:, 1], 0.02), np.quantile(p[:, 1], 0.98)
        c = np.median(p, 0)
        r = 0.5 * min(np.quantile(p[:, 0], 0.98) - np.quantile(p[:, 0], 0.02),
                      np.quantile(p[:, 2], 0.98) - np.quantile(p[:, 2], 0.02))
        mid, half = 0.5 * (lo + hi), max(0.5 * (hi - lo) - r, 0.0)
        a, b = np.array([c[0], mid - half, c[2]]), np.array([c[0], mid + half, c[2]])
        _, rr = radial(p, a, b)
        rq = np.quantile(rr, 0.6)
        caps.append(("head", idx["Head"], a, b, rq, rq))
    # Torso bands: horizontal capsules from the crotch to the shoulders.
    p = pos[body & (vseg == "torso")]
    y0 = piv[idx["LeftLeg"]][1] - 0.1 * cls["thigh"]
    y1 = piv[idx["LeftArm"]][1]
    bands = 5
    edges = np.linspace(y0, y1, bands + 1)
    for k in range(bands):
        sel = (p[:, 1] >= edges[k]) & (p[:, 1] < edges[k + 1])
        q = p[sel]
        if len(q) < 30:
            continue
        cy = 0.5 * (edges[k] + edges[k + 1])
        cx = np.median(q[:, 0])
        z0, z1 = np.quantile(q[:, 2], 0.05), np.quantile(q[:, 2], 0.95)
        cz = 0.5 * (z0 + z1)
        rz = 0.5 * (z1 - z0)
        hw = 0.5 * (np.quantile(q[:, 0], 0.95) - np.quantile(q[:, 0], 0.05))
        half = max(hw - rz, 0.0)
        a, b = np.array([cx - half, cy, cz]), np.array([cx + half, cy, cz])
        _, rr = radial(q, a, b)
        rq = np.quantile(rr, 0.7)
        bone = idx["UpBody"] if cy > piv[idx["UpBody"]][1] else idx["AllBody"]
        # The bone most of the band's vertices follow.
        vals, counts = np.unique(dom[body & (vseg == "torso")][sel], return_counts=True)
        caps.append(("torso%d" % k, int(vals[np.argmax(counts)]) if len(vals) else bone, a, b, rq, rq))
    return caps


def capsule_depth(points, a, b, r0, r1):
    ab = b - a
    t = np.clip((points - a) @ ab / max(ab @ ab, 1e-12), 0, 1)
    d = np.linalg.norm(points - (a + t[:, None] * ab), axis=1)
    return (r0 + (r1 - r0) * t) - d


# ---------------------------------------------------------------- animation
class Anim:
    def __init__(self, path, names, rest_rotate):
        self.clips = json.load(open(path))["animations"]
        self.index = {n: i for i, n in enumerate(names)}
        self.rest = rest_rotate

    def channel(self, value, t, env, length):
        if isinstance(value, (list, str, int, float)):
            return self.vec(value, env)
        keys = sorted(value, key=float)
        ts = [float(k) for k in keys]
        if t <= ts[0]:
            return self.vec(self.post(value[keys[0]]), env)
        if t >= ts[-1]:
            return self.vec(self.post(value[keys[-1]]), env)
        k = max(i for i in range(len(ts)) if ts[i] <= t)
        a, b = self.vec(self.post(value[keys[k]]), env), self.vec(self.pre(value[keys[k + 1]]), env)
        s = (t - ts[k]) / max(ts[k + 1] - ts[k], 1e-9)
        return a + (b - a) * s

    @staticmethod
    def post(v):
        return v.get("post", v.get("pre")) if isinstance(v, dict) else v

    @staticmethod
    def pre(v):
        return v.get("pre", v.get("post")) if isinstance(v, dict) else v

    @staticmethod
    def vec(v, env):
        if isinstance(v, list):
            out = [molang.evaluate(x, env) for x in v]
            return np.array(out if len(out) == 3 else out * 3, float)
        x = molang.evaluate(v, env)
        return np.array([x, x, x], float)

    def attributes(self, clip, t, flags):
        """(bones, 9): rotation (radians, rest included), position (px), scale."""
        n = len(self.rest)
        out = np.zeros((n, 9))
        out[:, :3] = self.rest
        out[:, 6:] = 1.0
        c = self.clips.get(clip)
        if c is None:
            return out
        length = c.get("animation_length") or 0.0
        loop = c.get("loop")
        if length > 0:
            t = (t % length) if loop is True else min(t, length)
        env = {"query.anim_time": t, "q.anim_time": t}
        for f in flags:
            env["ctrl." + f] = 1.0
        for bone, chans in c.get("bones", {}).items():
            i = self.index.get(bone)
            if i is None:
                continue
            if "rotation" in chans:
                r = np.radians(self.channel(chans["rotation"], t, env, length))
                out[i, :3] += (-r[0], -r[1], r[2])
            if "position" in chans:
                out[i, 3:6] = self.channel(chans["position"], t, env, length)
            if "scale" in chans:
                out[i, 6:] = self.channel(chans["scale"], t, env, length)
        return out


def build_stream(meta, anim, hz):
    """Frames: (measure, physics 21 floats, attributes); labels per frame."""
    scale = meta["height_scale"]
    dt = 1.0 / hz
    frames, labels = [], []
    n = len(meta["bones"])
    rest = np.zeros((n, 9))
    rest[:, :3] = anim.rest
    rest[:, 6:] = 1.0
    zero = np.zeros((n, 9))
    zero[:, 6:] = 1.0

    def physics(delta, reset, yaw, travel):
        m = np.eye(4)
        c, s = math.cos(-yaw), math.sin(-yaw)
        m[:3, :3] = [[c, 0, s], [0, 1, 0], [-s, 0, c]]
        m[:3, 3] = m[:3, :3] @ np.array([0.0, 0.0, travel / scale])
        return [delta, 1.0 if reset else 0.0, 0.0, -1.0, 0.0] + list(m.T.reshape(-1))

    # Rest checks: zero-rotation bind frame and the default (rest-rotation) pose, springs reset.
    frames.append((1.0, physics(0.0, True, 0.0, 0.0), zero))
    labels.append("bind")
    frames.append((1.0, physics(0.0, True, 0.0, 0.0), rest))
    labels.append("rest")
    previous = None
    step = 0
    for clip, seconds, speed, yaw, flags in SCRIPT:
        count = int(round(seconds * hz))
        for k in range(count):
            t = k * dt
            attrs = anim.attributes(clip, t, flags)
            if previous is not None and t < FADE:
                attrs = previous + (attrs - previous) * (t / FADE)
            if isinstance(yaw, tuple):
                kind, deg, every = yaw
                if kind == "zigzag":
                    rate = math.radians(deg) * (1 if int(t / every) % 2 == 0 else -1)
                    turn = rate * dt
                else:  # snap: turn deg over 0.15 s, every `every` s
                    phase = t % every
                    turn = math.radians(deg) / 0.15 * dt if phase < 0.15 else 0.0
            else:
                turn = math.radians(yaw) * dt
            measure = 1.0 if (hz <= 30 or step % 2 == 0) else 0.0
            frames.append((measure, physics(dt, False, turn, speed * dt), attrs))
            labels.append(clip + ("+" + yaw[0] if isinstance(yaw, tuple) else ""))
            step += 1
        previous = anim.attributes(clip, (count - 1) * dt, flags)
    return frames, labels


def write_stream(path, frames, bones):
    with open(path, "wb") as f:
        f.write(struct.pack("<3i", MAGIC, bones, len(frames)))
        for measure, phys, attrs in frames:
            f.write(np.asarray([measure] + phys, "<f4").tobytes())
            f.write(np.asarray(attrs, "<f4").tobytes())


# ---------------------------------------------------------------- run + measure
def swing_setup(meta, cls, gar):
    """Joints whose swing is measured: bone, rest pivot and tail (model units), the bone of the chain's attachment
    (the first unsimulated ancestor of its root) and the part (hair / skirt / accessory, hem for the garment chains)."""
    info = meta.get("joint_info")
    if not info:
        return None
    parent, spring, names = cls["parent"], cls["spring"], cls["names"]
    hem = set(gar["hem"]) if gar is not None else set()
    bones, tails, attach, parts = [], [], [], []
    for j in info:
        b, c = int(j[0]), int(j[1])
        a = parent[b]
        while a >= 0 and spring[a]:
            a = parent[a]
        part = "hem" if c in hem else cls["chain_part"].get(c)
        if a < 0 or part is None:
            continue
        bones.append(b)
        tails.append(j[6:9])
        attach.append(a)
        parts.append(part)
    if not bones:
        return None
    bones = np.array(bones)
    return dict(bones=bones, offset=np.array(tails, float) - cls["pivot"][bones], attach=np.array(attach),
                part=np.array(parts, object))


def swing_directions(sw, poses_m):
    """Each measured joint's bone direction in its attachment bone's frame (unit vectors)."""
    m = np.transpose(poses_m[sw["bones"], :3, :3], (0, 2, 1))  # row-major
    d = np.einsum("jik,jk->ji", m, sw["offset"])
    r = np.transpose(poses_m[sw["attach"], :3, :3], (0, 2, 1))
    local = np.einsum("jki,jk->ji", r, d)  # r^T d
    return local / (np.linalg.norm(local, axis=1, keepdims=True) + 1e-12)


def run(dump, animation, hz, keep=(), garment_only=False):
    meta, mesh = load_dump(dump)
    cls = classify(meta, mesh)
    caps = fit_proxy(cls, mesh)
    rest_rot = np.array([b["rotate"] for b in meta["bones"]])
    anim = Anim(animation, cls["names"], rest_rot)
    frames, labels = build_stream(meta, anim, hz)
    measured = [labels[i] for i, f in enumerate(frames) if f[0]]
    nb, nv = len(meta["bones"]), meta["vertices"]
    record = nb * 16 + nv * 3 + 2
    with tempfile.TemporaryDirectory() as tmp:
        poses = os.path.join(tmp, "poses.bin")
        out = os.path.join(tmp, "out.fifo")
        write_stream(poses, frames, nb)
        os.mkfifo(out)
        env = dict(os.environ, YSM_ANTICLIP_MODEL=os.path.join(dump, "geo.pb"), YSM_ANTICLIP_POSES=poses,
                   YSM_ANTICLIP_OUT=out)
        proc = subprocess.Popen([YSM_TEST, "--gtest_filter=AntiClipTest.Replay"], env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT)
        soft = np.where(cls["soft"])[0][:1 if garment_only else None]  # garment only: the soft parts are not measured
        body = np.where(cls["body"])[0][:16 if garment_only else None]
        part = cls["part"][soft] if not garment_only else np.array([""], object)
        rows = []
        rest_mesh = rest_proxy = None
        kept = {}
        timing = []
        gar = garment(meta, mesh, cls)
        grows = []
        worst = {}
        sw = swing_setup(meta, cls, gar)
        swings = []
        if gar is not None:
            gv, ge = gar["verts"], gar["edges"]
            gcaps = [k for k, c in enumerate(caps) if c[0].startswith(("torso", "thigh", "calf"))]
            gcap_radius = np.array([max(caps[k][4], caps[k][5]) for k in gcaps])
            grest_mesh = grest_proxy = None
            cm = meta["height_scale"] * 100.0

        def keep_frame(key, pos, poses_m, gmover=None, ratio=None):
            kept[key] = pos.copy()
            kept[key + "_poses"] = poses_m.copy()
            if gar is not None and ratio is not None:
                depth = np.zeros(nv)
                depth[gv] = gmover
                stretch = np.ones(nv)
                np.maximum.at(stretch, ge[:, 0], ratio)
                np.maximum.at(stretch, ge[:, 1], ratio)
                kept[key + "_gdepth"] = depth
                kept[key + "_stretch"] = stretch
        with open(out, "rb") as f:
            for label in measured:
                buf = f.read(record * 4)
                if len(buf) < record * 4:
                    break
                data = np.frombuffer(buf, "<f4")
                poses_m = data[:nb * 16].reshape(nb, 4, 4)  # column-major: [col][row]
                pos = data[nb * 16:nb * 16 + nv * 3].reshape(nv, 3).astype(np.float64)
                timing.append(data[-2:].copy())
                proxy_d = np.nan_to_num(proxy_depths(caps, poses_m, pos[soft]), nan=-1.0)
                mesh_d = np.nan_to_num(mesh_depths(mesh, cls, poses_m, pos, soft, body), nan=0.0)
                if label in ("bind", "rest"):
                    if label == "rest":
                        rest_mesh, rest_proxy = mesh_d, proxy_d
                    kept[label] = pos.copy()
                    if gar is not None and label == "rest":
                        grest_proxy = np.nan_to_num(proxy_depths([caps[k] for k in gcaps], poses_m, pos[gv]), nan=-1.0)
                        grest_mesh = np.nan_to_num(mesh_depths(mesh, cls, poses_m, pos, gv, gar["body"]), nan=0.0)
                    continue
                if sw is not None:
                    swings.append((label, swing_directions(sw, poses_m).astype(np.float32)))
                skip = rest_proxy > 0.5 * np.array([max(c[4], c[5]) for c in caps])[None, :]
                over = np.where(skip, 0.0, proxy_d - np.maximum(rest_proxy, 0.0)).max(1)
                mover = np.nan_to_num(mesh_d - np.maximum(rest_mesh, 0.0), nan=0.0)
                index = len(rows)
                gmover = ratio = None
                if gar is not None:
                    gp = np.nan_to_num(proxy_depths([caps[k] for k in gcaps], poses_m, pos[gv]), nan=-1.0)
                    gskip = grest_proxy > 0.5 * gcap_radius[None, :]
                    gover = np.maximum(np.where(gskip, 0.0, gp - np.maximum(grest_proxy, 0.0)).max(1), 0.0)
                    gm = np.nan_to_num(mesh_depths(mesh, cls, poses_m, pos, gv, gar["body"]), nan=0.0)
                    gmover = np.maximum(gm - np.maximum(grest_mesh, 0.0), 0.0)
                    ratio = np.nan_to_num(np.linalg.norm(pos[ge[:, 0]] - pos[ge[:, 1]], axis=1) / gar["rest"], nan=1.0)
                    grows.append((label, gover.astype(np.float32), gmover.astype(np.float32),
                                  ratio.astype(np.float32)))
                    scores = {"tear": float((ratio > 1.25).mean()), "gmesh": float((gmover * cm > INSIDE_CM).mean()),
                              "gproxy": float((gover * cm > INSIDE_CM).mean())}
                    for name, score in scores.items():
                        if score > worst.get(name, (0.0, -1))[0]:
                            worst[name] = (score, index)
                            keep_frame("worst_" + name, pos, poses_m, gmover, ratio)
                if index in keep:
                    keep_frame(str(index), pos, poses_m, gmover, ratio)
                    depth = np.zeros(nv)
                    depth[soft] = np.maximum(mover, 0.0)
                    kept[str(index) + "_depth"] = depth
                rows.append((label, np.maximum(over, 0.0), np.maximum(mover, 0.0)))
        proc.wait()
        log = proc.stdout.read().decode(errors="replace")
        if proc.returncode != 0:
            raise RuntimeError(log[-3000:])
    return dict(meta=meta, cls=cls, caps=caps, rows=rows, part=part, kept=kept, timing=np.array(timing),
                soft=soft, garment=gar, grows=grows, worst=worst, swing=sw, swings=swings)


def proxy_depths(caps, poses_m, points):
    out = np.zeros((len(points), len(caps)))
    for k, (_, bone, a, b, r0, r1) in enumerate(caps):
        m = poses_m[bone].T  # row-major
        if not np.all(np.isfinite(m)):
            out[:, k] = -1
            continue
        s = np.linalg.norm(m[:3, 0])
        pa, pb = m[:3, :3] @ a + m[:3, 3], m[:3, :3] @ b + m[:3, 3]
        out[:, k] = capsule_depth(points, pa, pb, r0 * s, r1 * s)
    return out


def mesh_depths(mesh, cls, poses_m, pos, soft, body):
    bp = pos[body]
    ok = np.all(np.isfinite(bp), 1)
    bi = body[ok]
    rot = np.transpose(poses_m[:, :3, :3], (0, 2, 1))  # row-major 3x3 per bone
    n = np.einsum("vij,vj->vi", rot[cls["dom"][bi]], mesh["nrm"][bi])
    n /= np.linalg.norm(n, axis=1, keepdims=True) + 1e-12
    tree = cKDTree(pos[bi])
    sp = pos[soft]
    good = np.all(np.isfinite(sp), 1)
    dist, near = tree.query(np.where(good[:, None], sp, 0.0), k=4)
    off = sp[:, None, :] - pos[bi][near]
    signed = np.einsum("vkj,vkj->vk", off, n[near])
    inside = np.all(signed < 0, 1) & (dist[:, 0] < 0.08) & good
    return np.where(inside, -signed.max(1), 0.0)


def summarize(result, scale):
    cm = scale * 100.0
    rows, part = result["rows"], result["part"]
    out = {}
    for name in ("hair", "skirt", "accessory"):
        sel = part == name
        if not sel.any():
            continue
        entry = {"vertices": int(sel.sum())}
        for metric, col in (("proxy", 1), ("mesh", 2)):
            d = np.concatenate([r[col][sel] for r in rows]) * cm
            per_frame = np.array([r[col][sel].max() for r in rows]) * cm
            clips = {}
            for label in dict.fromkeys(r[0] for r in rows):
                idx = [i for i, r in enumerate(rows) if r[0] == label]
                clips[label] = round(float(per_frame[idx].max()), 2)
            worst = int(np.argmax(per_frame))
            entry[metric] = {"inside_share": float((d > INSIDE_CM).mean()), "p99": float(np.quantile(d, 0.99)),
                             "p999": float(np.quantile(d, 0.999)), "max": float(d.max()), "worst_frame": worst,
                             "worst_clip": rows[worst][0], "clip_max": clips,
                             "frame_max": [round(float(x), 2) for x in per_frame]}
        out[name] = entry
    gar, grows = result.get("garment"), result.get("grows")
    if gar is not None and grows:
        names = result["cls"]["names"]
        entry = {"vertices": int(len(gar["verts"])), "edges": int(len(gar["edges"])),
                 "chains": sorted(h["name"] for h in gar["hem"].values())}
        labels = [r[0] for r in grows]
        for metric, col in (("proxy", 1), ("mesh", 2)):
            d = np.concatenate([r[col] for r in grows]) * cm
            per_frame = np.array([(r[col] * cm > INSIDE_CM).mean() for r in grows])
            clips = {}
            for label in dict.fromkeys(labels):
                idx = [i for i, x in enumerate(labels) if x == label]
                both = np.concatenate([grows[i][col] for i in idx]) * cm
                clips[label] = [round(float((both > INSIDE_CM).mean() * 100), 2), round(float(both.max()), 2)]
            entry[metric] = {"inside_share": float((d > INSIDE_CM).mean()), "p99": float(np.quantile(d, 0.99)),
                             "p999": float(np.quantile(d, 0.999)), "max": float(d.max()),
                             "worst_frame": int(np.argmax(per_frame)), "clip_share_max": clips,
                             "frame_max": [round(float(r[col].max() * cm), 2) for r in grows]}
        ratio = np.stack([r[3] for r in grows])
        per_frame = (ratio > 1.25).mean(1)
        clips = {}
        for label in dict.fromkeys(labels):
            idx = [i for i, x in enumerate(labels) if x == label]
            clips[label] = [round(float((ratio[idx] > 1.25).mean() * 100), 3), round(float(ratio[idx].max()), 2)]
        # The bone pairs (dominant garment bone of each end) of the edges that stretch past 1.5x most often.
        over = (ratio > 1.5).sum(0)
        pairs = {}
        gdom = gar["gdom"]
        for e in np.where(over > 0)[0]:
            a, b = sorted((names[gdom[gar["edges"][e, 0]]], names[gdom[gar["edges"][e, 1]]]))
            pairs[a + " | " + b] = pairs.get(a + " | " + b, 0) + int(over[e])
        top = sorted(pairs.items(), key=lambda kv: -kv[1])[:12]
        entry["tear"] = {"p99": float(np.quantile(ratio, 0.99)), "p999": float(np.quantile(ratio, 0.999)),
                         "max": float(ratio.max()), "over_1.25": float((ratio > 1.25).mean()),
                         "over_1.5": float((ratio > 1.5).mean()), "compressed_0.5": float((ratio < 0.5).mean()),
                         "worst_frame": int(np.argmax(per_frame)), "clip_share_max": clips,
                         "worst_pairs": [[k, v] for k, v in top],
                         "frame_over_1.25": [round(float(x) * 100, 3) for x in per_frame]}
        entry["worst_kept"] = {k: [round(v[0], 4), v[1]] for k, v in result["worst"].items()}
        out["garment"] = entry
    sw, swings = result.get("swing"), result.get("swings")
    if sw is not None and swings:
        # Swing: per joint the RMS angle (deg) of its bone direction, in its attachment bone's frame, about the
        # clip's mean direction; averaged over a part's joints. How lively the springs are relative to the body.
        labels = [s[0] for s in swings]
        dirs = np.stack([s[1] for s in swings])  # frames, joints, 3
        entry = {}
        for name in dict.fromkeys(sw["part"]):
            sel = sw["part"] == name
            clips = {}
            for label in dict.fromkeys(labels):
                d = dirs[[i for i, x in enumerate(labels) if x == label]][:, sel]
                mean = d.mean(0)
                mean /= np.linalg.norm(mean, axis=1, keepdims=True) + 1e-12
                angle = np.degrees(np.arccos(np.clip(np.einsum("fjk,jk->fj", d, mean), -1.0, 1.0)))
                clips[label] = round(float(np.sqrt((angle ** 2).mean(0)).mean()), 2)
            entry[name] = {"joints": int(sel.sum()), "rms_deg": clips}
        out["swing"] = entry
    t = result["timing"]
    out["timing_ms"] = {"extract": float(np.median(t[:, 0])), "render": float(np.median(t[:, 1]))}
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dump")
    ap.add_argument("animation")
    ap.add_argument("--hz", type=float, default=60)
    ap.add_argument("--json")
    ap.add_argument("--positions")
    ap.add_argument("--keep", default="")
    ap.add_argument("--garment-only", action="store_true", help="measure only the hem garment (faster)")
    a = ap.parse_args()
    keep = tuple(int(x) for x in a.keep.split(",") if x)
    start = time.time()
    result = run(a.dump, a.animation, a.hz, keep, a.garment_only)
    summary = summarize(result, result["meta"]["height_scale"])
    summary["hz"] = a.hz
    summary["frames"] = len(result["rows"])
    summary["capsules"] = [c[0] for c in result["caps"]]
    summary["seconds"] = round(time.time() - start, 1)
    text = json.dumps(summary, indent=1)
    print(text)
    if a.json:
        open(a.json, "w").write(text)
    if a.positions:
        np.savez_compressed(a.positions, **{str(k): v.astype(np.float32) for k, v in result["kept"].items()})


if __name__ == "__main__":
    main()
