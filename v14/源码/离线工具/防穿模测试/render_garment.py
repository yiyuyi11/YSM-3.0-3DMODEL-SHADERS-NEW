#!/usr/bin/env python3
"""Close-ups of the hem garment in kept harness frames: the garment as a stretch heat map (posed edge length over rest
length: cloth colour up to 1.1x, yellow at 1.25x, red at 1.5x, magenta beyond), the body grey (where it shows through
the cloth the cloth is clipped), other parts pale, and the body proxy capsules of the trunk and legs as outlines.

Usage: render_garment.py <dump dir> <out.png> <label>=<frames.npz>:<key> ... [--views front,left,back] [--size 420]
       [--no-capsules]
One row per panel spec, one column per view. Keys are harness frame indices kept with --keep, or worst_tear /
worst_gmesh / worst_gproxy.
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

YAW = {"front": 0.0, "back": np.pi, "left": -np.pi / 2, "right": np.pi / 2, "fl": -np.pi / 4, "fr": np.pi / 4,
       "bl": -3 * np.pi / 4, "br": 3 * np.pi / 4}
CLOTH = np.array([0.55, 0.7, 0.95])
STOPS = [(1.1, CLOTH), (1.25, np.array([1.0, 0.9, 0.2])), (1.5, np.array([0.95, 0.15, 0.1])),
         (2.0, np.array([0.9, 0.1, 0.9]))]


def heat(ratio):
    out = np.tile(CLOTH, (len(ratio), 1))
    for (r0, c0), (r1, c1) in zip(STOPS[:-1], STOPS[1:]):
        sel = ratio > r0
        t = np.clip((ratio[sel] - r0) / (r1 - r0), 0, 1)[:, None]
        out[sel] = c0 + (c1 - c0) * t
    return out


def panel(meta, mesh, gar, pos, stretch, poses, view, size, capsules):
    yaw = YAW[view]
    c, s = np.cos(yaw), np.sin(yaw)
    rot = np.array([[c, 0, -s], [0, 1, 0], [s, 0, c]])  # the model faces -Z: the front camera looks along +Z
    p = np.nan_to_num(pos) @ rot.T
    tri = mesh["tri"]
    tri = tri[np.isfinite(pos[tri]).all(2).all(1)]
    g = p[gar["verts"]]
    lo, hi = g.min(0), g.max(0)
    extent = max(hi[1] - lo[1], hi[0] - lo[0]) * 1.5
    cx, cy = 0.5 * (lo[0] + hi[0]), 0.5 * (lo[1] + hi[1]) - 0.1 * extent
    scale = size / extent

    def screen(q):
        return np.stack([(-(q[..., 0] - cx)) * scale + size / 2, -(q[..., 1] - cy) * scale + size / 2], -1)

    xy = screen(p)
    near = (np.abs(xy[tri] - size / 2) < size).all(2).any(1)
    tri = tri[near]
    iw = 1.0 / (10.0 + p[:, 2])
    a, b, cc = p[tri[:, 0]], p[tri[:, 1]], p[tri[:, 2]]
    n = np.cross(b - a, cc - a)
    n /= np.linalg.norm(n, axis=1, keepdims=True) + 1e-12
    shade = 0.4 + 0.6 * np.abs(n[:, 2])
    is_g = np.zeros(len(pos), bool)
    is_g[gar["verts"]] = True
    is_b = np.zeros(len(pos), bool)
    is_b[gar["body"]] = True
    flat = np.tile(np.array([0.88, 0.86, 0.8]), (len(tri), 1))
    flat[is_b[tri].all(1)] = (0.45, 0.45, 0.45)
    gt = is_g[tri].all(1)
    flat[gt] = heat(stretch[tri[gt]].max(1))
    frame = raster.Frame(size, size, (255, 255, 255))
    raster.draw(frame, raster.Textures(), xy[tri], iw[tri], np.zeros((len(tri), 3, 2)),
                np.repeat(shade[:, None], 3, 1), np.full(len(tri), -1), flat)
    img = Image.fromarray(frame.color)
    if capsules and poses is not None:
        draw = ImageDraw.Draw(img)
        for bone, hx, hy, hz, tx, ty, tz, r0, r1 in meta["body"]:
            m = poses[int(bone)].T
            if not np.isfinite(m).all():
                continue
            k = np.linalg.norm(m[:3, 0])
            pa = (m[:3, :3] @ np.array([hx, hy, hz]) + m[:3, 3]) @ rot.T
            pb = (m[:3, :3] @ np.array([tx, ty, tz]) + m[:3, 3]) @ rot.T
            sa, sb = screen(pa), screen(pb)
            if not ((np.abs(sa - size / 2) < size).all() or (np.abs(sb - size / 2) < size).all()):
                continue
            ra, rb = r0 * k * scale, r1 * k * scale
            d = sb - sa
            length = np.linalg.norm(d)
            perp = np.array([-d[1], d[0]]) / length if length > 1e-6 else np.array([1.0, 0.0])
            colour = (0, 140, 60)
            draw.ellipse([sa[0] - ra, sa[1] - ra, sa[0] + ra, sa[1] + ra], outline=colour)
            draw.ellipse([sb[0] - rb, sb[1] - rb, sb[0] + rb, sb[1] + rb], outline=colour)
            for sign in (1, -1):
                draw.line([tuple(sa + sign * perp * ra), tuple(sb + sign * perp * rb)], fill=colour)
    return img


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dump")
    ap.add_argument("out")
    ap.add_argument("panels", nargs="+")
    ap.add_argument("--views", default="front,left,back")
    ap.add_argument("--size", type=int, default=420)
    ap.add_argument("--no-capsules", action="store_true")
    a = ap.parse_args()
    views = a.views.split(",")
    rows = []
    for spec in a.panels:
        label, rest = spec.split("=", 1)
        path, key = rest.rsplit(":", 1)
        dump = a.dump
        if "@" in path:  # <frames.npz>@<dump dir>: a panel from another dump (before / after builds)
            path, dump = path.split("@", 1)
        meta, mesh = harness.load_dump(dump)
        gar = harness.garment(meta, mesh, harness.classify(meta, mesh))
        data = np.load(path)
        pos = data[key].astype(np.float64)
        stretch = data[key + "_stretch"] if key + "_stretch" in data else np.ones(len(pos))
        poses = data[key + "_poses"] if key + "_poses" in data else None
        row = []
        for view in views:
            img = panel(meta, mesh, gar, pos, stretch, poses, view, a.size, not a.no_capsules)
            ImageDraw.Draw(img).text((6, 6), "%s  %s  %s" % (label, key, view), fill=(0, 0, 0))
            row.append(img)
        rows.append(row)
    sheet = Image.new("RGB", (a.size * len(views), a.size * len(rows)), (255, 255, 255))
    for y, row in enumerate(rows):
        for x, img in enumerate(row):
            sheet.paste(img, (x * a.size, y * a.size))
    sheet.save(a.out)
    print("wrote", a.out)


if __name__ == "__main__":
    main()
