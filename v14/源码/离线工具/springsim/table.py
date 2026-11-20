#!/usr/bin/env python3
"""Skirt-into-leg penetration per frame rate, for several VRMs, stock and patched solver.

Usage: table.py <GenAnim dir with main.json and skel.json> <name>=<model.vrm> ...
GenAnim: java -cp tools/ysmgen/out:<ysm jar>:<client libraries> com.elfmcys.ysm.format.parser.GenAnim
         <model.vrm> main.json arm.json skel.json   (YSM_NATIVE_PATH=<libysm-core .so>)
Counts skirt vertices more than 1 cm inside the leg mesh per drawn frame of the generated walk (4.3 m/s) and run
(5.6 m/s) clips."""
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim  # noqa: E402


def summarize(res):
    cnt = np.array([(x[1] > 0.01).sum() for x in res])
    mx = np.array([x[1].max() for x in res])
    return cnt.mean(), (cnt > 10).mean(), float(np.percentile(mx, 95))


def main(gen, models):
    anims = json.load(open(os.path.join(gen, "main.json")))["animations"]
    skel = json.load(open(os.path.join(gen, "skel.json")))
    hip_y = next(b["pivot"][1] for b in skel if b["name"] == "LeftLeg")
    clips = {"walk": (sim.Clip(anims["walk"], skel), 4.3), "run": (sim.Clip(anims["run"], skel), 5.6)}
    still = sim.Clip({"animation_length": 1.0, "bones": {}}, skel)
    for spec in models:
        name, path = spec.split("=", 1)
        s = sim.Sim(path, skel, None)
        s.unit = hip_y / s.legs["L"][1][1]  # YSM pixels per metre, from the hip height
        r = s.run(still, 30, 0.0, seconds=3.0, record_from=2.9)
        tails = r[-1][4]
        dev = max(np.linalg.norm(tails[ci][-2] - c["pos"][-1]) for ci, c in enumerate(s.chains))
        print(f"{name} standing: max hem displacement from rest {dev * 100:.1f} cm, "
              f"penetrating verts {summarize(r)[0]:.0f}", flush=True)
        for patched in (False, True):
            for fps in (10, 30, 60, 144):
                row = []
                for clip_name, (clip, speed) in clips.items():
                    n, bad, p95 = summarize(s.run(clip, fps, speed, seconds=2.6, record_from=1.2, interp=patched))
                    row.append(f"{clip_name}: {n:5.1f} verts/frame, {bad * 100:3.0f}% frames >10, "
                               f"p95 depth {p95 * 100:4.1f} cm")
                print(f"{name} {'patched' if patched else 'stock  '} {fps:3d} fps | " + " | ".join(row), flush=True)


if __name__ == "__main__":
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2:])
