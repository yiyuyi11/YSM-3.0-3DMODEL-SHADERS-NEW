#!/usr/bin/env python3
"""Skirt jitter under uneven frame times: stock YSM against the spring timing fix (NativeModelState: step time matched
to the observed motion) and the native substep patch (spring-substep-interpolation.diff), on the generated walk/run.

Usage: jitter.py <GenAnim dir with main.json and skel.json> <model.vrm>

Jitter = how far the drawn skirt vertices wander from their own smooth path: per vertex, the model-space position
minus its centred moving average over 0.1 s of game time (time-weighted, so uneven frame spacing alone does not count),
RMS over vertices and frames, in mm. The same run at a perfectly even frame rate gives the motion's own residual,
which is subtracted in quadrature ("excess")."""
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim  # noqa: E402

WINDOW = 0.1


def jitter(res):
    times = np.array([r[0] for r in res])
    verts = np.stack([r[2] for r in res])  # frames x vertices x 3
    residual = []
    for i, t in enumerate(times):
        m = (times >= t - WINDOW / 2) & (times <= t + WINDOW / 2)
        if times[0] > t - WINDOW / 2 or times[-1] < t + WINDOW / 2:
            continue
        # time-weighted mean of the piecewise-linear path over the window
        ts = times[m]
        vs = verts[m]
        w = np.gradient(ts) if len(ts) > 1 else np.ones(1)
        mean = (vs * w[:, None, None]).sum(0) / w.sum()
        residual.append(np.linalg.norm(verts[i] - mean, axis=1))
    return float(np.sqrt(np.mean(np.square(residual)))) * 1000.0


def main(gen, vrm):
    anims = json.load(open(os.path.join(gen, "main.json")))["animations"]
    skel = json.load(open(os.path.join(gen, "skel.json")))
    hip_y = next(b["pivot"][1] for b in skel if b["name"] == "LeftLeg")
    s = sim.Sim(vrm, skel, None)
    s.unit = hip_y / s.legs["L"][1][1]
    clips = {"walk": (sim.Clip(anims["walk"], skel), 4.3), "run": (sim.Clip(anims["run"], skel), 5.6)}
    rng = np.random.default_rng(7)
    patterns = {
        "40-70 fps random": 1.0 / rng.uniform(40, 70, 400),
        "60 fps, every 7th frame 2x": np.array([1 / 60] * 6 + [2 / 60]),
        "30 fps +-25%": 1.0 / 30 * rng.uniform(0.75, 1.25, 400),
    }
    even = {"40-70 fps random": 55, "60 fps, every 7th frame 2x": 60, "30 fps +-25%": 30}
    variants = [("stock YSM", False, "stock"), ("timing fix", False, "matched"),
                ("timing fix + native substeps", True, "matched")]
    for clip_name, (clip, speed) in clips.items():
        for name, frames in patterns.items():
            base = jitter(s.run(clip, even[name], speed, seconds=2.6, record_from=1.0, timing="matched"))
            row = []
            for label, interp, timing in variants:
                j = jitter(s.run(clip, even[name], speed, seconds=2.6, record_from=1.0, interp=interp, frames=frames,
                                 timing=timing))
                row.append(f"{label}: {j:5.1f} mm (excess {np.sqrt(max(j * j - base * base, 0)):5.1f})")
            print(f"{clip_name} {name:28s} | even {base:5.1f} mm | " + " | ".join(row), flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2])
