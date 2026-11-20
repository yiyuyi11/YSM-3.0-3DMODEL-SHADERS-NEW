#!/usr/bin/env python3
"""Offline run of nprClothParallax (npr_eyebrow.glsl) on a real _ParallaxTex with that material's params.

The march of Gens2_14753404.glsl 586-636 / 1597-1607 (n = min(_ParallaxMarchNum, 20) layers, step
-V_t.xy / max(V_t.z, .001) * _ParallaxScale / n, first layer with depth < h, n + 1 tries, linear interpolation of the
last two, a final sample), evaluated over a grid of surface points for several view angles. Prints the mean / max of
the returned h, how far the final sample moved (tiled uv), and the colour it adds (h * _ParallaxColor, alpha 1).

usage: cloth_parallax_eval.py [role material]   (default lastrite m_actor_lastrite_cloth_05)
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

PROPS = os.path.expanduser("~/ysm/efdata/props")
ROLE, MAT = (sys.argv[1], sys.argv[2]) if len(sys.argv) > 2 else ("lastrite", "m_actor_lastrite_cloth_05")


def load():
    rows = [r for r in json.load(open(os.path.join(PROPS, "bindings.json")))
            if r["role"] == ROLE and r["material"] == MAT and r["slot"] == "_ParallaxTex"]
    row = rows[0]
    tex = np.asarray(Image.open(os.path.join(PROPS, row["archive_path"])).convert("RGBA"), dtype=np.float64) / 255.0
    d = json.load(open(os.path.join(PROPS, "materials", ROLE, MAT + ".json")))

    def items(x):
        return x.items() if isinstance(x, dict) else [(e["Key"], e["Value"]) for e in x]
    f = dict(items(d["m_Floats"]))
    c = {k: np.array([v["R"], v["G"], v["B"], v["A"]]) for k, v in items(d["m_Colors"])}
    return row, tex[..., 0], f, c


def sample(h, uv):
    """Bilinear repeat sample of the height (Unity uv, v up -> image row from the bottom)."""
    hh, ww = h.shape
    x = (uv[..., 0] % 1.0) * ww - 0.5
    y = (1.0 - uv[..., 1] % 1.0) * hh - 0.5
    x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
    fx, fy = x - x0, y - y0
    g = lambda yy, xx: h[yy % hh, xx % ww]  # noqa: E731
    return (g(y0, x0) * (1 - fx) * (1 - fy) + g(y0, x0 + 1) * fx * (1 - fy) + g(y0 + 1, x0) * (1 - fx) * fy
            + g(y0 + 1, x0 + 1) * fx * fy)


def march(h, tuv, vt, scale, layers):
    n = max(min(layers, 20.0), 1.0)
    step1 = 1.0 / n
    delta = vt[:2] / max(vt[2], 0.001) * -scale * step1
    offset = np.tile(delta, (tuv.shape[0], 1))
    prev_offset = np.zeros_like(offset)
    depth = np.full(tuv.shape[0], 1.0 - step1)
    prev_h = np.zeros(tuv.shape[0])
    prev_depth = np.ones(tuv.shape[0])
    hit_h = np.zeros(tuv.shape[0])
    done = np.zeros(tuv.shape[0], bool)
    for i in range(21):
        if i >= n + 1:
            break
        s = sample(h, tuv + offset)
        hit = ~done & (depth < s)
        hit_h[hit] = s[hit]
        done |= hit
        go = ~done
        prev_offset[go] = offset[go]
        offset[go] += delta
        prev_h[go] = s[go]
        prev_depth[go] = depth[go]
        depth[go] -= step1
    hit_h[~done] = prev_h[~done]
    before = prev_h - prev_depth
    k = before / np.minimum(before + depth - hit_h, -1e-6)
    final = tuv + prev_offset + delta * k[:, None]
    return sample(h, final), np.linalg.norm(final - tuv, axis=1)


def main():
    row, h, f, c = load()
    scale, layers = f["_ParallaxScale"], f["_ParallaxMarchNum"]
    st = np.array([row["scale"]["X"], row["scale"]["Y"]])
    col = c["_ParallaxColor"][:3]
    print(f"{ROLE} {MAT}: {row['archive_path']} {h.shape[1]}x{h.shape[0]}, _ST scale {st}, _UseParallax "
          f"{f.get('_UseParallax')}, _ParallaxScale {scale}, _ParallaxMarchNum {layers}, _ParallaxColor {col.round(3)}")
    rng = np.random.default_rng(1)
    tuv = rng.random((4000, 2)) * st
    print(f"  plain h (no parallax): mean {sample(h, tuv).mean():.3f}")
    for angle in (0, 30, 60, 80):
        a = math.radians(angle)
        vt = np.array([math.sin(a), 0.0, math.cos(a)])
        hv, moved = march(h, tuv, vt, scale, layers)
        add = hv[:, None] * col
        print(f"  view {angle:2d} deg: h mean {hv.mean():.3f} max {hv.max():.3f}; final sample moved {moved.mean():.3f} "
              f"(max {moved.max():.3f}) tiled uv; added linear colour mean {add.mean(0).round(3)} (x baseAlpha)")


if __name__ == "__main__":
    main()
