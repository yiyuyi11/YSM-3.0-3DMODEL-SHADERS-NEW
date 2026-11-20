#!/usr/bin/env python3
"""Offline reproduction of the dotted mouth ring (0.6.2) and its fix (0.6.3): software rasterization of pelica.vrm's
face_01 the way the NPR passes draw it under a shader pack with the depth prepass.

  1. prepass: nearest depth at pixel centres, rounded to 24 bits (the pack's depth);
  2. copy into the NPR target: exact (0.6.2) or pushed back by its slope (0.6.3, NprDepth.PUSHED in float32);
  3. body pass per sample (1 or 4 samples, GL's standard 4x pattern), LEQUAL with depth writes, with glPolygonOffset
     (-1, -2) semantics (0.6.2: factor x max|dz/dx|,|dz/dy| of the triangle + units x 2^(e-23) for a float depth
     buffer) or none (0.6.3).

A sample is wrong when the surface drawn there lies more than 0.5 mm behind the true nearest one (or nothing passed).
Writes before/after crops textured with the face texture (nearest, unlit) and prints the wrong-pixel counts.

Usage: seam_depth_sim.py <pelica.vrm> <fix_pelica.py dir> <out dir>
"""

import io
import math
import os
import sys

import numpy as np
from PIL import Image

f32 = np.float32
SAMPLES = {1: [(0.5, 0.5)], 4: [(0.375, 0.125), (0.875, 0.375), (0.125, 0.625), (0.625, 0.875)]}
NEAR, FAR, FOVY, SCREEN_H = 0.05, 1024.0, 70.0, 1080


def load(vrm):
    from fix_pelica import load_glb, read_accessor
    g, b = load_glb(vrm)
    mesh = [m for m in g["meshes"] if m["name"] == "face_01"][0]["primitives"][0]
    pos = read_accessor(g, b, mesh["attributes"]["POSITION"]).astype(np.float64)
    uv = read_accessor(g, b, mesh["attributes"]["TEXCOORD_0"]).astype(np.float64)
    idx = read_accessor(g, b, mesh["indices"]).reshape(-1, 3).astype(np.int64)
    tex = g["textures"][g["materials"][mesh["material"]]["pbrMetallicRoughness"]["baseColorTexture"]["index"]]
    view = g["bufferViews"][g["images"][tex["source"]]["bufferView"]]
    start = view.get("byteOffset", 0)
    image = Image.open(io.BytesIO(b[start:start + view["byteLength"]])).convert("RGB")
    return pos, uv, idx, np.asarray(image).astype(float)


def project(pos, mm_per_px, elev, w, h, centre=(0.0, 1.462, 0.075)):
    pixel_angle = 2 * math.tan(math.radians(FOVY / 2)) / SCREEN_H
    dist = mm_per_px / 1000 / pixel_angle
    e = math.radians(elev)
    c = np.array(centre)
    eye = c + dist * np.array([0.0, math.sin(e), math.cos(e)])
    fwd = (c - eye) / np.linalg.norm(c - eye)
    right = np.cross(fwd, [0, 1, 0])
    right /= np.linalg.norm(right)
    up = np.cross(right, fwd)
    view = np.eye(4)
    view[0, :3], view[1, :3], view[2, :3] = right, up, -fwd
    view[:3, 3] = -view[:3, :3] @ eye
    t = 1 / math.tan(math.radians(FOVY / 2))
    proj = np.array([[t, 0, 0, 0], [0, t, 0, 0], [0, 0, -(FAR + NEAR) / (FAR - NEAR), -2 * FAR * NEAR / (FAR - NEAR)],
                     [0, 0, -1, 0]])
    clip = (proj @ view @ np.c_[pos, np.ones(len(pos))].T).T
    ndc = clip[:, :3] / clip[:, 3:4]
    xs = (ndc[:, 0] * 0.5 + 0.5) * SCREEN_H - (SCREEN_H / 2 - w / 2)
    ys = (0.5 - ndc[:, 1] * 0.5) * SCREEN_H - (SCREEN_H / 2 - h / 2)
    return xs, ys, ndc[:, 2] * 0.5 + 0.5, dist


def pushed(d):
    """NprDepth.PUSHED in float32 (camera projection of this script, SCREEN_H source texels)."""
    d = d.astype(f32)
    p22 = f32(-(FAR + NEAR) / (FAR - NEAR))
    p32 = f32(-2 * FAR * NEAR / (FAR - NEAR))
    texel = f32(2.0 / (1 / math.tan(math.radians(FOVY / 2)) * SCREEN_H))
    pad = np.pad(d, 1, mode="edge")
    left, right, below, above = pad[1:-1, :-2], pad[1:-1, 2:], pad[:-2, 1:-1], pad[2:, 1:-1]
    slope = np.maximum(np.abs(d - left), np.abs(right - d)) + np.maximum(np.abs(d - below), np.abs(above - d))
    ndc = d * f32(2) - f32(1)
    z = -p32 / (ndc + p22)
    cap_ndc = -p22 - p32 / (z - (f32(0.04) + f32(2) * texel * -z))
    out = np.minimum(d + np.minimum(slope, f32(0.5) * (cap_ndc - ndc)) + f32(2.0 / 16777216.0), f32(1))
    return np.where(d >= 1, d, out).astype(np.float64)


def simulate(pos, idx, mm_per_px, elev, legacy, ns, w=110, h=90):
    xs, ys, zw, dist = project(pos, mm_per_px, elev, w, h)
    offsets = SAMPLES[ns]
    n = w * h
    centre = np.full(n, 1.0)
    truth_z = np.full(n * ns, np.inf)
    truth = np.full(n * ns, -1)
    covered = []
    for ti, t in enumerate(idx):
        x, y, z = xs[t], ys[t], zw[t]
        x0, x1 = max(int(math.floor(x.min())) - 1, 0), min(int(math.ceil(x.max())) + 1, w - 1)
        y0, y1 = max(int(math.floor(y.min())) - 1, 0), min(int(math.ceil(y.max())) + 1, h - 1)
        den = (y[1] - y[2]) * (x[0] - x[2]) + (x[2] - x[1]) * (y[0] - y[2])
        if x0 > x1 or y0 > y1 or abs(den) < 1e-14:
            covered.append(None)
            continue
        grad = np.linalg.solve([[x[1] - x[0], y[1] - y[0]], [x[2] - x[0], y[2] - y[0]]], [z[1] - z[0], z[2] - z[0]])
        entry = {"grad": grad}
        for key, offs in (("centre", [(0.5, 0.5)]), ("samples", offsets)):
            hits = []
            for si, (ox, oy) in enumerate(offs):
                gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + ox, np.arange(y0, y1 + 1) + oy)
                w0 = ((y[1] - y[2]) * (gx - x[2]) + (x[2] - x[1]) * (gy - y[2])) / den
                w1 = ((y[2] - y[0]) * (gx - x[2]) + (x[0] - x[2]) * (gy - y[2])) / den
                w2 = 1 - w0 - w1
                m = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
                if m.any():
                    px = gy[m].astype(int) * w + gx[m].astype(int)
                    hits.append((px * len(offs) + si, (w0 * z[0] + w1 * z[1] + w2 * z[2])[m]))
            entry[key] = hits
        covered.append(entry)
        for px, zz in entry["centre"]:
            centre[px] = np.minimum(centre[px], zz)
        for sp, zz in entry["samples"]:
            nearer = zz < truth_z[sp]
            truth_z[sp[nearer]] = zz[nearer]
            truth[sp[nearer]] = ti
    prepass = (np.round(centre * (2 ** 24 - 1)) / (2 ** 24 - 1)).reshape(h, w)
    copy = prepass if legacy else pushed(prepass)
    depth = np.repeat(copy.ravel(), ns)
    win = np.full(n * ns, -1)
    win_z = np.full(n * ns, np.inf)
    for ti, (t, entry) in enumerate(zip(idx, covered)):
        if entry is None:
            continue
        offset = 0.0
        if legacy:
            r = 2.0 ** (math.floor(math.log2(zw[t].max())) - 23)
            offset = -max(abs(entry["grad"][0]), abs(entry["grad"][1])) - 2 * r
        for sp, zz in entry["samples"]:
            zo = (zz + offset).astype(f32).astype(np.float64)
            ok = zo <= depth[sp]
            depth[sp[ok]] = zo[ok]
            win[sp[ok]] = ti
            win_z[sp[ok]] = zz[ok]
    with np.errstate(invalid="ignore"):
        behind = (truth >= 0) & (win >= 0) & ((win_z - truth_z) * dist ** 2 / NEAR > 0.0005)
    failed = (truth >= 0) & (win < 0)
    return xs, ys, win.reshape(h, w, ns), behind.reshape(n, ns).any(1).sum(), failed.reshape(n, ns).any(1).sum()


def colour(xs, ys, uv, idx, tex, win, ns):
    h, w, _ = win.shape
    out = np.zeros((h, w, 3))
    for yy in range(h):
        for xx in range(w):
            acc = np.zeros(3)
            for si, (ox, oy) in enumerate(SAMPLES[ns]):
                ti = win[yy, xx, si]
                if ti < 0:
                    acc += (60, 70, 60)
                    continue
                t = idx[ti]
                x, y = xs[t], ys[t]
                px, py = xx + ox, yy + oy
                den = (y[1] - y[2]) * (x[0] - x[2]) + (x[2] - x[1]) * (y[0] - y[2])
                w0 = ((y[1] - y[2]) * (px - x[2]) + (x[2] - x[1]) * (py - y[2])) / den
                w1 = ((y[2] - y[0]) * (px - x[2]) + (x[0] - x[2]) * (py - y[2])) / den
                u = w0 * uv[t[0]] + w1 * uv[t[1]] + (1 - w0 - w1) * uv[t[2]]
                acc += tex[min(max(int(u[1] * tex.shape[0]), 0), tex.shape[0] - 1),
                           min(max(int(u[0] * tex.shape[1]), 0), tex.shape[1] - 1)]
            out[yy, xx] = acc / ns
    return out.astype(np.uint8)


def main():
    vrm, fix_dir, out_dir = sys.argv[1:4]
    sys.path.insert(0, fix_dir)
    pos, uv, idx, tex = load(vrm)
    os.makedirs(out_dir, exist_ok=True)
    rows = []
    for mm in (1.2, 2.0):
        for ns in (1, 4):
            tiles = []
            for legacy in (True, False):
                xs, ys, win, behind, failed = simulate(pos, idx, mm, 15, legacy, ns)
                print(f"{mm} mm/px, {ns} sample(s), {'0.6.2 offset' if legacy else '0.6.3 pushed copy'}: "
                      f"{behind} pixels show a hidden surface, {failed} pixels with a failed sample")
                tiles.append(colour(xs, ys, uv, idx, tex, win, ns))
            rows.append(np.concatenate([tiles[0], np.full((tiles[0].shape[0], 4, 3), 255, np.uint8), tiles[1]], 1))
    sheet = np.concatenate([np.concatenate([r, np.full((4, r.shape[1], 3), 255, np.uint8)], 0) for r in rows], 0)
    Image.fromarray(sheet).save(os.path.join(out_dir, "seam_before_after.png"))
    Image.fromarray(sheet).resize((sheet.shape[1] * 4, sheet.shape[0] * 4), Image.NEAREST).save(
        os.path.join(out_dir, "seam_before_after_x4.png"))


if __name__ == "__main__":
    main()
