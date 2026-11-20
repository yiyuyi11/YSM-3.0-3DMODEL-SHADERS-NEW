#!/usr/bin/env python3
"""Offline check of the brows-through-bangs pass (npr_eyebrow.glsl, EyeBrowPass.java) on Pelica's mesh.

Software z-buffer of the head (face_01, eyebrow_l/r_01, iris_01, hair_01 in the rest pose of
~/ysm/pelica/01-模型/佩丽卡-真骨架2.glb) from several views. Per pixel: the nearest hair, face and eye-class (brow/iris)
surface, and the _HairBrowMask texel (efdata t_actor_pelica_hair_01_sw_m) of the nearest hair.

  game     brow/iris visible against everything but hair (its PreZ stencil, written before the hair) and the hair in
           front clipped there by mask.r < _HairBrowMaskThreshold (EndfieldCharacterPass.usf:472-477, Hair.ush:858)
  ours     the front surface is hair with mask.r < threshold (receiver code), and an eye-class surface lies within
           `depth` behind it (the pass pulls eye-class vertices toward the camera by `depth`, depth test LEQUAL)

Prints, per view, the eye-class pixels hidden by hair, how many of them the mask opens, how many our pass reveals,
the false reveals (ours, not the game's) and the misses, and the hair-to-brow gap. Optional occluders test that a hat
brim or a hand in front of the bangs keeps the brows hidden (front surface not hair -> no reveal).

usage: eyebrow_eval.py [--depth 0.03] [--size 700] [--out DIR]
"""
import argparse
import math
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.expanduser("~/ysm/pelica-fix-eyes"))
import fix_pelica as fp  # noqa: E402  (load_glb / read_accessor)

GLB = os.path.expanduser("~/ysm/pelica/01-模型/佩丽卡-真骨架2.glb")
MASK = os.path.expanduser("~/ysm/efdata/props/textures/1995991273578056555/t_actor_pelica_hair_01_sw_m.tga.png")
THRESHOLD = 0.5  # m_actor_pelica_hair_01 _HairBrowMaskThreshold (_DrawUnderBrow = 1)
HEAD = np.array([0.0, 1.53, 0.0])  # between the brows, a little behind: the orbit centre


def meshes():
    g, b = fp.load_glb(GLB)
    out = {}
    for m in g["meshes"]:
        if m["name"] not in ("face_01", "eyebrow_l_01", "eyebrow_r_01", "hair_01", "iris_01"):
            continue
        p = m["primitives"][0]
        pos = fp.read_accessor(g, b, p["attributes"]["POSITION"]).astype(np.float64)
        uv = fp.read_accessor(g, b, p["attributes"]["TEXCOORD_0"]).astype(np.float64)
        tri = fp.read_accessor(g, b, p["indices"]).reshape(-1, 3).astype(np.int64)
        out[m["name"]] = (pos, uv, tri)
    return out


def raster(pos, uv, tri, view, size, half, zbuf, uvbuf):
    """Perspective z-buffer (camera at the origin looking down -z of `view` space); keeps the nearest uv."""
    p = pos @ view[:3, :3].T + view[:3, 3]
    z = -p[:, 2]
    f = size / (2.0 * half)
    sx = p[:, 0] / z * f + size / 2.0
    sy = -p[:, 1] / z * f + size / 2.0
    for a, b, c in tri:
        xs = np.array([sx[a], sx[b], sx[c]])
        ys = np.array([sy[a], sy[b], sy[c]])
        x0, x1 = max(int(math.floor(xs.min())), 0), min(int(math.ceil(xs.max())), size - 1)
        y0, y1 = max(int(math.floor(ys.min())), 0), min(int(math.ceil(ys.max())), size - 1)
        if x0 > x1 or y0 > y1:
            continue
        det = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
        if abs(det) < 1e-12:
            continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        w0 = ((ys[1] - ys[2]) * (gx - xs[2]) + (xs[2] - xs[1]) * (gy - ys[2])) / det
        w1 = ((ys[2] - ys[0]) * (gx - xs[2]) + (xs[0] - xs[2]) * (gy - ys[2])) / det
        w2 = 1.0 - w0 - w1
        inside = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
        if not inside.any():
            continue
        # perspective-correct depth / uv
        iz = w0 / z[a] + w1 / z[b] + w2 / z[c]
        depth = 1.0 / iz
        u = (w0 * uv[a, 0] / z[a] + w1 * uv[b, 0] / z[b] + w2 * uv[c, 0] / z[c]) * depth
        v = (w0 * uv[a, 1] / z[a] + w1 * uv[b, 1] / z[b] + w2 * uv[c, 1] / z[c]) * depth
        sub = zbuf[y0:y1 + 1, x0:x1 + 1]
        nearer = inside & (depth < sub)
        sub[nearer] = depth[nearer]
        uvbuf[y0:y1 + 1, x0:x1 + 1, 0][nearer] = u[nearer]
        uvbuf[y0:y1 + 1, x0:x1 + 1, 1][nearer] = v[nearer]


def look(yaw, pitch, dist):
    """World -> view for a camera orbiting HEAD (yaw 0 = in front, +z), looking at it."""
    cy, sy, cp, sp = math.cos(yaw), math.sin(yaw), math.cos(pitch), math.sin(pitch)
    eye = HEAD + dist * np.array([sy * cp, sp, cy * cp])
    fwd = (HEAD - eye) / np.linalg.norm(HEAD - eye)
    right = np.cross(fwd, [0.0, 1.0, 0.0])
    right /= np.linalg.norm(right)
    up = np.cross(right, fwd)
    m = np.eye(4)
    m[0, :3], m[1, :3], m[2, :3] = right, up, -fwd
    m[:3, 3] = -m[:3, :3] @ eye
    return m


def occluder(kind):
    """A hat brim (horizontal disc at the bangs' tips) or a hand (vertical quad 4 cm in front of the brows)."""
    if kind == "hat":
        n = 48
        ring = [np.array([0.2 * math.cos(t), 1.545, 0.2 * math.sin(t) + 0.02]) for t in np.linspace(0, 2 * math.pi, n,
                                                                                                    endpoint=False)]
        pos = np.array([[0.0, 1.545, 0.02]] + ring)
        tri = np.array([[0, 1 + i, 1 + (i + 1) % n] for i in range(n)])
        # tilt the brim down in front so it hides the brows from the front
        pos[:, 1] -= np.clip(pos[:, 2], 0, None) * 0.25
    else:
        pos = np.array([[-0.09, 1.47, 0.16], [0.09, 1.47, 0.16], [0.09, 1.56, 0.16], [-0.09, 1.56, 0.16]])
        tri = np.array([[0, 1, 2], [0, 2, 3]])
    return pos, np.zeros((len(pos), 2)), tri


def evaluate(data, mask, yaw, pitch, depth, size, extra=None, save=None):
    dist = 0.9
    view = look(math.radians(yaw), math.radians(pitch), dist)
    half = 0.16 / dist  # tan(half fov): about 0.32 m across at the head
    bufs = {}
    for cls, names in (("hair", ["hair_01"]), ("face", ["face_01"]), ("eye", ["eyebrow_l_01", "eyebrow_r_01",
                                                                              "iris_01"])):
        z = np.full((size, size), np.inf)
        uvb = np.zeros((size, size, 2))
        for n in names:
            raster(*data[n], view, size, half, z, uvb)
        bufs[cls] = (z, uvb)
    occ = np.full((size, size), np.inf)
    if extra is not None:
        raster(*extra, view, size, half, occ, np.zeros((size, size, 2)))
    hz, huv = bufs["hair"]
    fz, _ = bufs["face"]
    ez, _ = bufs["eye"]
    mh, mw = mask.shape
    mu = np.clip((huv[..., 0] % 1.0) * mw, 0, mw - 1).astype(int)
    mv = np.clip((huv[..., 1] % 1.0) * mh, 0, mh - 1).astype(int)
    m = mask[mv, mu]
    eps = 2e-4  # the brows sit 0.2-2 mm on the face
    front = np.minimum(np.minimum(hz, fz), np.minimum(ez, occ))
    hair_front = np.isfinite(hz) & (hz <= front)
    eye_vs_rest = np.isfinite(ez) & (ez <= fz + eps) & (ez <= occ)  # visible ignoring hair (the game's PreZ stencil)
    hidden = eye_vs_rest & (hz < ez)  # ... but behind hair
    masked = hair_front & (m < THRESHOLD)
    game = hidden & masked
    # ours: front = masked hair (receiver code), an eye-class surface within depth behind it; the pulled eye fragment
    # passes LEQUAL against the front depth. The eye surface may be behind the face there (not checked: the error).
    ours = masked & np.isfinite(ez) & (ez - hz <= depth)
    gap = (ez - hz)[game]
    res = {
        "eye_visible_no_hair": int(eye_vs_rest.sum()),
        "eye_hidden_by_hair": int(hidden.sum()),
        "game_reveals": int(game.sum()),
        "ours_reveals": int(ours.sum()),
        "ours_and_game": int((ours & game).sum()),
        "false_reveals": int((ours & ~game).sum()),
        "misses": int((game & ~ours).sum()),
        "gap_mm": (np.percentile(gap, [0, 50, 95, 100]) * 1000).round(1).tolist() if gap.size else [],
    }
    if save:
        img = np.zeros((size, size, 3), np.uint8)
        img[np.isfinite(fz)] = (200, 170, 160)
        img[eye_vs_rest] = (60, 40, 40)
        img[hair_front] = (230, 225, 215)
        img[masked] = (180, 200, 255)
        img[np.isfinite(occ) & (occ <= front)] = (90, 140, 90)
        img[ours & game] = (220, 30, 30)
        img[ours & ~game] = (255, 160, 0)
        img[game & ~ours] = (160, 0, 200)
        Image.fromarray(img).save(save)
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--depth", type=float, default=0.03, help="pull toward the camera, metres (= blocks at YSM's 1:1)")
    ap.add_argument("--size", type=int, default=600)
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    data = meshes()
    mask = np.asarray(Image.open(MASK).convert("RGB"), dtype=np.float64)[..., 0] / 255.0
    print(f"mask {MASK}: {mask.shape[1]}x{mask.shape[0]}, black (r < {THRESHOLD}) {np.mean(mask < THRESHOLD):.4f}")
    # hair triangles with any vertex on a black mask texel: where they are on the head
    pos, uv, tri = data["hair_01"]
    mh, mw = mask.shape
    tm = mask[np.clip((uv[:, 1] % 1) * mh, 0, mh - 1).astype(int), np.clip((uv[:, 0] % 1) * mw, 0, mw - 1).astype(int)]
    black = tm < THRESHOLD
    print(f"hair vertices on black texels: {black.sum()} of {len(tm)}, centroid {pos[black].mean(0).round(3)}, "
          f"y {pos[black, 1].min():.3f}..{pos[black, 1].max():.3f}, z {pos[black, 2].min():.3f}..{pos[black, 2].max():.3f}"
          f" (brows y 1.492..1.534, z 0.047..0.079)")
    views = [("front", 0, 0), ("front_up20", 0, 20), ("front_down20", 0, -20), ("yaw30", 30, 0), ("yaw60", 60, 0),
             ("yaw90", 90, 0), ("back", 180, 0), ("back_up30", 180, 30)]
    for name, yaw, pitch in views:
        out = os.path.join(a.out, f"eyebrow_{name}.png") if a.out else None
        print(name, evaluate(data, mask, yaw, pitch, a.depth, a.size, save=out))
    for kind in ("hat", "hand"):
        out = os.path.join(a.out, f"eyebrow_front_{kind}.png") if a.out else None
        print(f"front+{kind}", evaluate(data, mask, 0, 0, a.depth, a.size, extra=occluder(kind), save=out))


if __name__ == "__main__":
    main()
