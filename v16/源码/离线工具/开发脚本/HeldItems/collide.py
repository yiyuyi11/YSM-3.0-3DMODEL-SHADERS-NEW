"""Held-item penetration check on the skinned mesh.

collide.py <prefix> <state> [--item sword|block|flat|shield] [--hand right|left] [--steps N] [--png out.png]
state: clip names joined by '+', e.g. idle+parallel4. The item is placed at the hand locator through YSM's
CustomPlayerItemInHandLayer chain and vanilla's third-person display transform. For every sample point of the item
the nearest body vertex (not of the holding arm) and its normal decide whether the point is inside the body shell.
"""
import argparse
import math
import re
import struct

import numpy as np

from fk import Model, rx, ry, rz

# Vanilla third-person right-hand display transforms: translation (px), rotation XYZ (deg), scale.
DISPLAY = {
    "sword": ((0, 4, 0.5), (0, -90, 55), 0.85),  # item/handheld
    "flat": ((0, 3, 1), (0, 0, 0), 0.55),  # item/generated
    "block": ((0, 2.5, 0), (75, 45, 0), 0.375),  # block/block
    "bow": ((-1, -2, 2.5), (-80, 260, -40), 0.9),
    "crossbow": ((2, 0.1, -3), (-90, 0, -60), 0.9),
    "shield": ((10, 6, -4), (0, 90, 0), 1.0),  # builtin/entity shield; lefthand given as (10, 6, 12)
}


TEXTURES = {"sword": ["diamond_sword", "diamond_axe", "diamond_pickaxe", "diamond_shovel", "diamond_hoe"],
            "flat": ["apple", "totem_of_undying"], "bow": ["bow"], "crossbow": ["crossbow_standby"]}


def sprite(names):
    """Opaque pixel centres of item textures in item model space (0..1), z through the middle."""
    from PIL import Image
    import os
    here = os.path.dirname(os.path.abspath(__file__))
    mask = None
    for n in names:
        a = np.array(Image.open(f"{here}/gen/tex/{n}.png").convert("RGBA"))[:16, :16, 3] > 0
        mask = a if mask is None else mask | a
    rows, cols = np.nonzero(mask)
    return np.stack([(cols + 0.5) / 16, (16 - rows - 0.5) / 16, np.full(len(rows), 0.5)], 1)


def item_points(kind):
    """Sample points in item model space (0..1)."""
    if kind in TEXTURES:
        return sprite(TEXTURES[kind])
    if kind == "block":
        g = np.linspace(0, 1, 4)
        return np.array([[x, y, z] for x in g for y in g for z in g])
    if kind == "shield":
        # ShieldModel plate box(-6, -11, -2, 12, 22, 1), drawn by BEWLR under scale(1, -1, -1)
        xs = np.linspace(-6, 6, 5) / 16
        ys = np.linspace(-11, 11, 7) / 16
        return np.array([[x, -y, 1.5 / 16] for x in xs for y in ys])  # T(-0.5) precedes BEWLR
    raise ValueError(kind)


def t4(m3=None, t=(0, 0, 0)):
    m = np.eye(4)
    if m3 is not None:
        m[:3, :3] = m3
    m[:3, 3] = t
    return m


def item_transform(kind, left):
    """Locator-local transform (pixels) of the item model's 0..1 space."""
    (tx, ty, tz), (ax, ay, az), s = DISPLAY[kind]
    if left and kind == "shield":
        tz = 12  # shield.json gives its own left-hand translation
    if left:
        tx, ay, az = -tx, -ay, -az
    m = t4(t=(0, -1, -1.6)) @ t4(rx(math.radians(-90)))
    m = m @ t4(t=(tx, ty, tz)) @ t4(rx(math.radians(ax)) @ ry(math.radians(ay)) @ rz(math.radians(az)))
    m = m @ t4(np.eye(3) * s * 16) @ t4(t=(-0.5, -0.5, -0.5))
    return m


class Body:
    def __init__(self, prefix, model):
        with open(prefix + ".mesh.bin", "rb") as f:
            n = struct.unpack(">i", f.read(4))[0]
            self.pos = np.frombuffer(f.read(12 * n), ">f4").reshape(n, 3).astype(np.float64)
            self.joints = np.frombuffer(f.read(16 * n), ">i4").reshape(n, 4)
            self.weights = np.frombuffer(f.read(16 * n), ">f4").reshape(n, 4).astype(np.float64)
            m = struct.unpack(">i", f.read(4))[0]
            self.tris = np.frombuffer(f.read(4 * m), ">i4").reshape(-1, 3)
        self.model = model
        self.names = [b["name"] for b in json_bones(prefix)]

    def skin(self, rot, pos, scale):
        cache = {}
        mats = np.stack([self.model.world(n, rot, pos, scale, cache) for n in self.names])
        out = np.zeros_like(self.pos)
        h = np.c_[self.pos, np.ones(len(self.pos))]
        for k in range(4):
            w = self.weights[:, k]
            m = mats[self.joints[:, k]]
            out += w[:, None] * np.einsum("nij,nj->ni", m, h)[:, :3]
        return out, cache

    def normals(self, p):
        a, b, c = p[self.tris[:, 0]], p[self.tris[:, 1]], p[self.tris[:, 2]]
        fn = np.cross(b - a, c - a)
        n = np.zeros_like(p)
        for k in range(3):
            np.add.at(n, self.tris[:, k], fn)
        return n / np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-9)


def json_bones(prefix):
    import json
    return json.load(open(prefix + ".skel.json"))


def descends(model, name, root):
    while name:
        if name == root:
            return True
        name = model.bones[name]["parent"]
    return False


def check(prefix, clips, kind, left, steps, holding=True, verbose=True):
    model = Model(prefix)
    body = Body(prefix, model)
    side = "Left" if left else "Right"
    arm = np.array([descends(model, n, side + "Arm") for n in body.names])
    strongest = body.joints[np.arange(len(body.joints)), body.weights.argmax(1)]
    hair = np.array([bool(re.search(r"(?i)hair|maozi|ear_base", n)) or descends(model, n, "Head") for n in body.names])
    keep = ~arm[strongest] & ~hair[strongest]
    length = max((model.anims[c].get("animation_length", 1) for c in clips if isinstance(c, str) and model.anims[c].get("loop")), default=1)
    worst = []
    for i in range(steps):
        t = length * i / steps
        rot, pos, scale = model.pose(clips, t, holding)
        skinned, cache = body.skin(rot, pos, scale)
        normals = body.normals(skinned)
        loc = model.world(side + "HandLocator", rot, pos, scale, cache)
        pivot = np.array(model.bones[side + "HandLocator"]["pivot"])
        # locator frame: translate to its pivot, its rotation (scale included)
        frame = loc @ t4(t=pivot)
        pts = (frame @ item_transform(kind, left) @ np.c_[item_points(kind), np.ones(len(item_points(kind)))].T).T[:, :3]
        lo, hi = pts.min(0) - 3, pts.max(0) + 3
        near = keep & np.all((skinned > lo) & (skinned < hi), 1)
        idx = np.nonzero(near)[0]
        depth, part, clear = 0.0, "-", 99.0
        if len(idx):
            d = np.linalg.norm(skinned[idx][None] - pts[:, None], axis=2)
            j = d.argmin(1)
            v = idx[j]
            dist = d[np.arange(len(pts)), j]
            # k nearest vertices vote: inside when most see the point behind their surface
            kk = min(16, d.shape[1])
            near_k = np.argsort(d, axis=1)[:, :kk]
            vk = idx[near_k]
            signed_k = np.einsum("pkj,pkj->pk", pts[:, None] - skinned[vk], normals[vk])
            inside = (signed_k < -0.05).mean(1) > 0.75
            # far from every vertex the normals vote is unreliable: confirm with the body's cross-sections
            from held import Profile
            profile = Profile(skinned[keep & (np.abs(skinned[:, 1] - pts[:, 1].mean()) < 20)])
            inside &= np.array([np.linalg.norm(profile.push(p, 0.0)) > 0 for p in pts]) if inside.any() else inside
            if inside.any():
                k = np.argmax(np.where(inside, dist, -1))
                depth, part = dist[k], body.names[strongest[v[k]]]
                if verbose:
                    print("   worst point", np.round(pts[k], 1), "vertex", np.round(skinned[v[k]], 1), "normal", np.round(normals[v[k]], 2), "dist", round(float(dist[k]), 2))
            clear = dist[~inside].min() if (~inside).any() else 0.0
        worst.append((depth, -clear, t, part))
        if verbose:
            print(f"t={t:5.2f} item {np.round(pts[0], 1)}..{np.round(pts[-1], 1)} "
                  f"inside={depth:4.2f}px ({part}) clear={clear:4.2f}")
    return max(worst)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("prefix")
    ap.add_argument("state")
    ap.add_argument("--item", default="sword")
    ap.add_argument("--hand", default="right")
    ap.add_argument("--steps", type=int, default=12)
    a = ap.parse_args()
    print("worst", check(a.prefix, a.state.split("+"), a.item, a.hand == "left", a.steps))


def render(prefix, clips, kind, left, t, out, holding=True):
    """Front, right-side and top orthographic point views of the posed body and item."""
    from PIL import Image, ImageDraw
    model = Model(prefix)
    body = Body(prefix, model)
    side = "Left" if left else "Right"
    rot, pos, scale = model.pose(clips, t, holding)
    skinned, cache = body.skin(rot, pos, scale)
    loc = model.world(side + "HandLocator", rot, pos, scale, cache)
    pivot = np.array(model.bones[side + "HandLocator"]["pivot"])
    pts = (loc @ t4(t=pivot) @ item_transform(kind, left) @ np.c_[item_points(kind), np.ones(len(item_points(kind)))].T).T[:, :3]
    s = 14
    img = Image.new("RGB", (3 * 30 * s, 36 * s), "white")
    d = ImageDraw.Draw(img)
    views = [(0, lambda p: (-p[:, 0], p[:, 1])), (1, lambda p: (-p[:, 2], p[:, 1])), (2, lambda p: (-p[:, 0], -p[:, 2] + 16))]
    for k, f in views:
        ox = k * 30 * s + 15 * s
        bx, by = f(skinned)
        order = np.argsort(-skinned[:, 2] if k == 0 else (skinned[:, 0] if k == 1 else skinned[:, 1]))
        for i in order[::3]:
            x, y = ox + bx[i] * s, (34 - by[i]) * s
            g = int(120 + 100 * (i % 7) / 7)
            d.point((x, y), fill=(g, g, g))
        ix, iy = f(pts)
        for x, y in zip(ix, iy):
            d.ellipse((ox + x * s - 3, (34 - y) * s - 3, ox + x * s + 3, (34 - y) * s + 3), fill="red")
        d.text((k * 30 * s + 5, 5), ["front (her right on the left)", "right side (front to the left)", "top"][k], fill="black")
    img.save(out)
