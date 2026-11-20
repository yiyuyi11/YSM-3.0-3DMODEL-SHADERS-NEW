#!/usr/bin/env python3
"""Converts the girl body type's MPData loops into the anchor motion make_resource.py reads (private: reads the game's
files, so the public export leaves it out together with mpdata.py, retarget.py and package_motion.py).

Usage: from_mpdata.py <dir with A_actor_girl_*_loop_MPData.bin> <out anchors.json>

Positions go to YSM model space in Pelica's pixels (hips at 20 px for her 0.946 m, facing -Z, left at -X: Unity's z is
mirrored), quaternions to the same space ((x, y, z, w) -> (-x, -y, z, w)), the repeated closing frame dropped. The
idle loop contributes its first frame as the stance: the mount data holds a still pose (the head moves 0.01 cm over the
loop), and Pelica's own standing clips decode without the main body bones. Each moving loop's stride is measured on
the raw data and written out, so make_resource.py builds the same resource as before the split.
"""

import json
import sys

import numpy as np

import mpdata

PX_PER_M = 20.0 / 0.946
CHANNELS = {"head": 1, "leftFoot": 4, "rightFoot": 5, "leftHand": 6, "rightHand": 7, "chest": 8}
# Pelica's finished YSM skeleton, which has the source body's proportions: the frame the clips are measured in.
SOURCE = {
    "upBody": [0.0, 21.82, -0.6], "chest": [0.0, 25.21, -0.6],
    "leftShoulder": [-2.21, 28.01, 0.04], "rightShoulder": [2.21, 28.01, 0.04],
    "leg": 16.72, "arm": 9.46, "palm": 1.1,
}


def convert(position, quaternion):
    p = position * np.array([1.0, 1.0, -1.0]) * PX_PER_M
    q = quaternion * np.array([-1.0, -1.0, 1.0, 1.0])
    return [round(float(v), 5) for v in (*p, *q)]


def stride(positions):
    """Pixels the body travels per cycle: the planted feet slide backward (+Z in YSM space) at the ground speed."""
    per_frame = []
    for channel in (CHANNELS["leftFoot"], CHANNELS["rightFoot"]):
        height = positions[:, channel, 1]
        z = -positions[:, channel, 2] * PX_PER_M  # YSM z
        planted = height < height.min() + 0.02
        step = np.diff(np.r_[z, z[:1]])
        both = planted & np.roll(planted, -1)
        per_frame.append(step[both].mean())
    return float(np.mean(per_frame) * len(positions))


def main(source_dir, out):
    clips = {}
    for name, clip in (("jog", "run"), ("sprint", "sprint")):
        positions, quaternions = mpdata.loop(f"{source_dir}/A_actor_girl_{clip}_loop_MPData.bin")
        clips[name] = {
            "stride": round(stride(positions), 4),
            "frames": [{key: convert(positions[f, c], quaternions[f, c]) for key, c in CHANNELS.items()}
                       for f in range(len(positions))],
        }
    idle_p, idle_q = mpdata.loop(f"{source_dir}/A_actor_girl_idle_loop_MPData.bin")
    clips["idle"] = {"frames": [{key: convert(idle_p[0, c], idle_q[0, c]) for key, c in CHANNELS.items()}]}
    document = {
        "about": "Arknights: Endfield (c) Hypergryph: girl body type locomotion mount points, local research only.",
        "source": SOURCE,
        "clips": clips,
    }
    json.dump(document, open(out, "w"), separators=(",", ":"))
    print(out, {k: len(v["frames"]) for k, v in clips.items()})


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__.splitlines()[3])
    main(sys.argv[1], sys.argv[2])
