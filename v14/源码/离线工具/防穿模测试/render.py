#!/usr/bin/env python3
"""Renders kept harness frames side by side (orthographic, flat shaded): body grey, hair blue, skirt orange,
accessories green, soft vertices more than 5 mm inside the body red.

Usage: render.py <dump dir> <out.png> <label>=<frames.npz>:<key> ... [--view front|back|left|right] [--size 420]
Each panel is one (npz, key); keys are the harness frame indices kept with --keep (and "bind" / "rest").
"""
import argparse
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "fpbench"))
sys.path.insert(0, HERE)
from fpb import raster  # noqa: E402
import harness  # noqa: E402

COLORS = {"": (0.78, 0.78, 0.78), "hair": (0.35, 0.5, 0.95), "skirt": (0.95, 0.6, 0.25),
          "accessory": (0.4, 0.8, 0.4)}
VIEWS = {"front": (0.0, 1.0), "back": (np.pi, 1.0), "left": (-np.pi / 2, 1.0), "right": (np.pi / 2, 1.0)}


def panel(mesh, cls, pos, depth, view, size):
    yaw = VIEWS[view][0]
    c, s = np.cos(yaw), np.sin(yaw)
    # The model faces -Z: the front camera looks along +Z.
    rot = np.array([[c, 0, -s], [0, 1, 0], [s, 0, c]])
    p = np.nan_to_num(pos) @ rot.T
    tri = mesh["tri"]
    ok = np.all(np.isfinite(pos[tri]).all(2), 1)
    tri = tri[ok]
    lo, hi = np.nanmin(p, 0), np.nanmax(p, 0)
    extent = max(hi[1] - lo[1], hi[0] - lo[0]) * 1.05
    cx, cy = 0.5 * (lo[0] + hi[0]), 0.5 * (lo[1] + hi[1])
    scale = size / extent
    xy = np.stack([(-(p[:, 0] - cx)) * scale + size / 2, -(p[:, 1] - cy) * scale + size / 2], 1)
    iw = 1.0 / (10.0 + p[:, 2])  # camera on -Z side looking +Z: smaller z is closer
    a, b, cc = p[tri[:, 0]], p[tri[:, 1]], p[tri[:, 2]]
    n = np.cross(b - a, cc - a)
    n /= np.linalg.norm(n, axis=1, keepdims=True) + 1e-12
    shade = 0.35 + 0.65 * np.abs(n[:, 2])
    part = cls["part"][tri].astype(str)
    flat = np.array([[COLORS.get(x, COLORS[""]) for x in row] for row in part[:, :1]])[:, 0]
    if depth is not None:
        red = (depth[tri] > 0.005 / 1.0).any(1)
        flat[red] = (0.95, 0.1, 0.1)
    frame = raster.Frame(size, size, (255, 255, 255))
    raster.draw(frame, raster.Textures(), xy[tri], iw[tri], np.zeros((len(tri), 3, 2)),
                np.repeat(shade[:, None], 3, 1), np.full(len(tri), -1), flat)
    return Image.fromarray(frame.color)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dump")
    ap.add_argument("out")
    ap.add_argument("panels", nargs="+")
    ap.add_argument("--view", default="front")
    ap.add_argument("--size", type=int, default=420)
    a = ap.parse_args()
    meta, mesh = harness.load_dump(a.dump)
    cls = harness.classify(meta, mesh)
    scale = meta["height_scale"]
    images = []
    for spec in a.panels:
        label, rest = spec.split("=", 1)
        path, key = rest.rsplit(":", 1)
        data = np.load(path)
        depth = data[key + "_depth"] * scale if key + "_depth" in data else None
        img = panel(mesh, cls, data[key].astype(np.float64), depth, a.view, a.size)
        ImageDraw.Draw(img).text((6, 6), label, fill=(0, 0, 0))
        images.append(img)
    sheet = Image.new("RGB", (a.size * len(images), a.size), (255, 255, 255))
    for i, img in enumerate(images):
        sheet.paste(img, (i * a.size, 0))
    sheet.save(a.out)
    print("wrote", a.out)


if __name__ == "__main__":
    main()
