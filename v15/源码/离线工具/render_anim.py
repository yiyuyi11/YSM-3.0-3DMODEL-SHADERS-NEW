#!/usr/bin/env python3
"""Render frames of a glTF animation clip as a contact strip (CPU skinning, same rasterizer as render_pose.py).

Usage: render_anim.py <model.glb> <clip> <out.png> [frames=6] [front|side|back]
"""

import copy
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import render_pose  # noqa: E402
from fix_pelica import load_glb, read_accessor  # noqa: E402


def sample(times, values, t, path):
    i = int(np.searchsorted(times, t, side="right") - 1)
    i = max(0, min(i, len(times) - 2))
    f = 0.0 if times[i + 1] == times[i] else (t - times[i]) / (times[i + 1] - times[i])
    f = min(max(f, 0.0), 1.0)
    a, b = values[i].astype(np.float64), values[i + 1].astype(np.float64)
    if path != "rotation":
        return a * (1 - f) + b * f
    if np.dot(a, b) < 0:
        b = -b
    q = a * (1 - f) + b * f
    return q / np.linalg.norm(q)


def main(model, clip, out, frames=6, view="side"):
    gltf, blob = load_glb(model)
    animation = [a for a in gltf["animations"] if a.get("name") == clip][0]
    tracks = []
    for channel in animation["channels"]:
        sampler = animation["samplers"][channel["sampler"]]
        tracks.append((channel["target"]["node"], channel["target"]["path"],
                       read_accessor(gltf, blob, sampler["input"])[:, 0], read_accessor(gltf, blob, sampler["output"])))
    end = max(t[2].max() for t in tracks)
    tiles = []
    for k in range(frames):
        t = end * k / frames
        posed = copy.deepcopy(gltf)
        for node, path, times, values in tracks:
            posed["nodes"][node][path] = [float(v) for v in sample(times, values, t, path)]
        tmp = f"/tmp/_anim_{os.getpid()}.glb"
        from fix_pelica import write_glb
        import contextlib
        import io
        with contextlib.redirect_stdout(io.StringIO()):
            write_glb(tmp, posed, blob)
            render_pose.render(tmp, f"{tmp}_{k}.png", "rest", view, size=(360, 640))
        tiles.append(Image.open(f"{tmp}_{k}.png").convert("RGB"))
        os.remove(f"{tmp}_{k}.png")
        os.remove(tmp)
    strip = Image.new("RGB", (sum(t.width for t in tiles), tiles[0].height), "white")
    x = 0
    for tile in tiles:
        strip.paste(tile, (x, 0))
        x += tile.width
    strip.save(out)
    print(f"wrote {out} ({frames} frames of '{clip}', {end:.3f} s)")


if __name__ == "__main__":
    if len(sys.argv) < 4:
        raise SystemExit(__doc__.splitlines()[2])
    main(sys.argv[1], sys.argv[2], sys.argv[3], *(int(sys.argv[4]),) if len(sys.argv) > 4 else (),
         *(sys.argv[5:6]))
