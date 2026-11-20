#!/usr/bin/env python3
"""Skirt collision variants of fix_pelica.py's SPRINGS / LEG_GROUPS / CALF_CAPSULE, each converted and measured with
clip_report.evaluate (walk, sprint, sneak at 60 fps; native substeps + matched timing).

Usage: sweep_skirt.py <input.glb> <GenAnim dir>"""
import contextlib
import io
import os
import sys
import tempfile
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
import clip_report  # noqa: E402
import fix_pelica as fp  # noqa: E402

CLIPS = {"walk": 4.3, "run": 5.6, "sneak": 1.3}
BASE_SPRINGS = list(fp.SPRINGS)
BASE_LEGS = dict(fp.LEG_GROUPS)
BASE_CALF = fp.CALF_CAPSULE


def skirt(springs, **change):
    """SPRINGS with the skirt/hem entries (qunbai*, yibai*) changed: radius / hem / stiff / grav / drag / groups."""
    out = []
    for prefix, first, stiff, grav, drag, radius, hem, groups in springs:
        if prefix.startswith(("qunbai", "yibai")):
            front = groups == ("legs_front",)
            stiff = change.get("stiff", stiff)
            grav = change.get("grav", grav)
            drag = change.get("drag", drag)
            radius = change.get("front_radius" if front else "side_radius", radius)
            hem = change.get("hem", hem)
            groups = change.get("groups", groups)
        out.append((prefix, first, stiff, grav, drag, radius, hem, groups))
    return out


def legs(margin):
    return {g: tuple((top, bottom, radius + margin, shift) for top, bottom, radius, shift in caps)
            for g, caps in BASE_LEGS.items()}


VARIANTS = {
    "current": {},
    "legs +2cm": {"legs": legs(0.02)},
    "legs +3cm, calf +2cm": {"legs": legs(0.03), "calf": (0.0, 0.22, BASE_CALF[2] + 0.02)},
    "hit radii up": {"springs": dict(front_radius=0.09, side_radius=0.03, hem=0.05)},
    "both groups": {"springs": dict(groups=("legs", "legs_front"))},
    "stiffer": {"springs": dict(stiff=2.6, drag=0.75)},
    "legs +2cm + hit radii": {"legs": legs(0.02), "springs": dict(front_radius=0.09, side_radius=0.03, hem=0.05)},
    "legs +3cm + hit radii + both": {"legs": legs(0.03), "calf": (0.0, 0.22, BASE_CALF[2] + 0.02),
                                     "springs": dict(front_radius=0.09, side_radius=0.03, hem=0.05,
                                                     groups=("legs", "legs_front"))},
}


def run(job):
    name, variant, glb, gen = job
    fp.SPRINGS = skirt(BASE_SPRINGS, **variant.get("springs", {}))
    fp.LEG_GROUPS = variant.get("legs", BASE_LEGS)
    fp.CALF_CAPSULE = variant.get("calf", BASE_CALF)
    out = os.path.join(tempfile.mkdtemp(), "v.vrm")
    with contextlib.redirect_stdout(io.StringIO()):
        fp.convert(glb, out, {"textures": fp.NPR_TEXTURE_DIR, "face_set": fp.NPR_FACE_SET_DIR,
                              "overlays": fp.NPR_OVERLAY_DIR})
        summary = clip_report.evaluate(gen, out, 60, CLIPS, verbose=False)
    return name, summary


def main(glb, gen):
    with Pool(4) as pool:
        for name, summary in pool.imap(run, [(n, v, glb, gen) for n, v in VARIANTS.items()]):
            cells = [f"{c}: {100 * b:5.1f}% >1cm, max {m:4.1f}, rms {r:3.1f}" for c, (b, m, r) in summary.items()]
            print(f"{name:32s} | " + " | ".join(cells), flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2])
