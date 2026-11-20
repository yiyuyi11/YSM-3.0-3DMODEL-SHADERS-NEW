#!/usr/bin/env python3
"""Build a YSM model folder: the VRM plus a ysm.json whose main animation set is YSM's own generated set with the
locomotion clips replaced by retargeted Endfield motion.

Usage: package_motion.py <out_dir> <model.vrm> <generated_main_anim.json> <name> <clip_name>=<clip.json> ...

A ysm.json is needed because a bare VRM folder always takes the generated set. It points the model, texture and
first-person animation at the VRM exactly as YSM's own humanoid manifest does, and only the main animation file at
the merged JSON. For every replaced walk/run, the TACZ legs-only variant is rebuilt from the same clip.
"""

import json
import os
import shutil
import sys

LEG_BONES = {"Root", "DownBody", "LeftLeg", "LeftLowerLeg", "LeftFoot", "RightLeg", "RightLowerLeg", "RightFoot"}
TACZ_VARIANT = {"walk": "tac:walk", "walk_back": "tac:walk_back", "run": "tac:run"}


def main(out_dir, model, generated, name, clips):
    os.makedirs(out_dir, exist_ok=True)
    model_name = os.path.basename(model)
    shutil.copyfile(model, os.path.join(out_dir, model_name))
    animations = json.load(open(generated))
    replaced = []
    for spec in clips:
        clip_name, path = spec.split("=", 1)
        clip = json.load(open(path))
        animations["animations"][clip_name] = clip
        replaced.append(clip_name)
        if clip_name in TACZ_VARIANT:
            legs = dict(clip)
            legs["bones"] = {bone: value for bone, value in clip["bones"].items() if bone in LEG_BONES}
            animations["animations"][TACZ_VARIANT[clip_name]] = legs
            replaced.append(TACZ_VARIANT[clip_name])
    json.dump(animations, open(os.path.join(out_dir, "pelica.animation.json"), "w"), separators=(",", ":"))
    manifest = {
        "spec": 2,
        "metadata": {
            "name": name,
            "tips": "Arknights: Endfield (c) Hypergryph. Local research only, do not distribute.",
            "license": {"type": "All Rights Reserved"},
            "authors": [{"name": "Hypergryph", "role": "model, motion"}],
        },
        "files": {"player": {
            "model": {"main": model_name, "arm": model_name},
            "texture": [{"uv": model_name, "specular": model_name}],
            "animation": {"main": "pelica.animation.json", "fp_arm": model_name},
        }},
    }
    json.dump(manifest, open(os.path.join(out_dir, "ysm.json"), "w"), ensure_ascii=False, indent=1)
    print(f"{out_dir}: replaced {', '.join(replaced)}")


if __name__ == "__main__":
    if len(sys.argv) < 6:
        raise SystemExit(__doc__.splitlines()[3])
    main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5:])
