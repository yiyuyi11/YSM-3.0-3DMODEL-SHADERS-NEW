#!/usr/bin/env python3
"""Builds endfield_locomotion.json, the locomotion data YSM's EndfieldLocomotion retargets onto every imported humanoid
in place of the procedural idle, walk and run, from anchor motion (format: README.md).

Usage: make_resource.py <anchors.json> <out.json>

The input is already in YSM model space, so the mod only reads numbers. This checks it, measures each moving loop's
stride (how far the body travels per cycle, from the planted feet's backward slide) unless the clip gives one, and turns
the idle stance into a breathing loop matching YSM's procedural idle: the chest pitches 0.8 degrees and rises 0.1 px
over 3 s; the head and hand anchors stay put, so the head keeps its gaze and the arms follow the shoulders.
"""

import json
import math
import sys

import numpy as np

ANCHORS = ("head", "leftFoot", "rightFoot", "leftHand", "rightHand", "chest")
SOURCE_POINTS = ("upBody", "chest", "leftShoulder", "rightShoulder")
SOURCE_LENGTHS = ("leg", "arm", "palm")
REQUIRED_CLIPS = ("jog", "sprint", "idle")  # walk and walk_back play jog, run plays sprint; idle is the reference pose
IDLE_SECONDS = 3.0
IDLE_FRAMES = 24
BREATH_DEGREES = 0.8
BREATH_RISE_PX = 0.1
PLANTED_PX = 0.02 * 20.0 / 0.946  # a foot within 2 cm (at 20 px for 0.946 m of hip height) of its lowest point


def quat_mul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return [aw * bx + ax * bw + ay * bz - az * by, aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw, aw * bw - ax * bx - ay * by - az * bz]


def check(document):
    source = document.get("source", {})
    for key in SOURCE_POINTS:
        if len(source.get(key, ())) != 3:
            raise SystemExit(f"source.{key}: expected [x, y, z]")
    for key in SOURCE_LENGTHS:
        if not isinstance(source.get(key), (int, float)) or source[key] <= 0:
            raise SystemExit(f"source.{key}: expected a positive length in pixels")
    clips = document.get("clips", {})
    for name in REQUIRED_CLIPS:
        if name not in clips:
            raise SystemExit(f"clips.{name}: missing")
    for name, clip in clips.items():
        frames = clip.get("frames", [])
        if not frames:
            raise SystemExit(f"clips.{name}: no frames")
        for index, frame in enumerate(frames):
            for key in ANCHORS:
                values = frame.get(key)
                if values is None or len(values) != 7:
                    raise SystemExit(f"clips.{name}.frames[{index}].{key}: expected [x, y, z, qx, qy, qz, qw]")
                if abs(math.sqrt(sum(v * v for v in values[3:])) - 1) > 1e-3:
                    raise SystemExit(f"clips.{name}.frames[{index}].{key}: quaternion is not unit length")


def breathing(stance):
    """IDLE_FRAMES frames of the stance with the chest pitching and rising on a sine."""
    frames = []
    for k in range(IDLE_FRAMES):
        breath = np.sin(2 * np.pi * k / IDLE_FRAMES)
        half = np.radians(BREATH_DEGREES * breath) / 2
        frame = {key: list(values) for key, values in stance.items()}
        chest = frame["chest"]
        chest[1] += BREATH_RISE_PX * breath
        chest[3:] = quat_mul([np.sin(half), 0.0, 0.0, np.cos(half)], chest[3:])
        frame["chest"] = [round(float(v), 5) for v in chest]
        frames.append(frame)
    return frames


def stride(frames):
    """Pixels the body travels per cycle: the planted feet slide backward (+Z) at the ground speed."""
    per_frame = []
    for key in ("leftFoot", "rightFoot"):
        height = np.array([frame[key][1] for frame in frames])
        z = np.array([frame[key][2] for frame in frames])
        planted = height < height.min() + PLANTED_PX
        step = np.diff(np.r_[z, z[:1]])
        both = planted & np.roll(planted, -1)
        per_frame.append(step[both].mean())
    return float(np.mean(per_frame) * len(frames))


def main(path, out):
    document = json.load(open(path, encoding="utf-8"))
    check(document)
    clips = {}
    stance = None
    for name, clip in document["clips"].items():
        if name == "idle":
            stance = clip["frames"][0]
            clips[name] = {"stride": 0.0, "seconds": clip.get("seconds", IDLE_SECONDS), "frames": breathing(stance)}
        else:
            measured = clip["stride"] if "stride" in clip else round(stride(clip["frames"]), 4)
            clips[name] = {"stride": measured, "frames": clip["frames"]}
    resource = {"about": document["about"]} if "about" in document else {}
    resource.update(source=document["source"], idle=stance, clips=clips)
    json.dump(resource, open(out, "w"), separators=(",", ":"))
    print(out, {k: (v["stride"], len(v["frames"])) for k, v in clips.items()})


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__.splitlines()[3])
    main(sys.argv[1], sys.argv[2])
