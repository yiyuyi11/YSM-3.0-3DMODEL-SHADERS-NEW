#!/usr/bin/env python3
"""Checks an overlay import against the same model's import without overlays (two ImportDump reports).

Usage: check_overlay.py <plain.json> <overlay.json> <main overlay file> <fp_arm overlay file>
Passes when every overlay clip is in its set with the overlay's bones (replaced clips differ from the plain import),
new clips were added, and every other clip of every set is byte-identical (same SHA-256) to the plain import.
"""
import json
import sys


def main(plain_path, overlay_path, *overlay_files):
    plain = json.load(open(plain_path))["animationSets"]
    over = json.load(open(overlay_path))
    sets = over["animationSets"]
    failures = []
    for set_name, file in zip(("main", "fp_arm"), overlay_files):
        clips = json.load(open(file))["animations"]
        base, merged = plain.get(set_name, {}), sets.get(set_name, {})
        for name, clip in clips.items():
            got = merged.get(name)
            if got is None:
                failures.append(f"{set_name}: overlay clip {name} missing")
                continue
            if sorted(got["bones"]) != sorted(clip.get("bones", {})):
                failures.append(f"{set_name}: {name} bones {got['bones']} != overlay {sorted(clip['bones'])}")
            kind = "replaced" if name in base else "added"
            if kind == "replaced" and got["sha256"] == base[name]["sha256"]:
                failures.append(f"{set_name}: {name} still the generated clip")
            print(f"{set_name}: {name} {kind} (bones {got['bones']}, length {got['length']})")
        untouched = [c for c in base if c not in clips]
        changed = [c for c in untouched if merged.get(c, {}).get("sha256") != base[c]["sha256"]]
        extra = [c for c in merged if c not in base and c not in clips]
        print(f"{set_name}: {len(untouched)} other clips, {len(untouched) - len(changed)} byte-identical to the plain "
              f"import; {len(merged)} clips total")
        if changed or extra:
            failures.append(f"{set_name}: changed {changed} extra {extra}")
    plain_report = json.load(open(plain_path))
    if over["modelHash"] == plain_report["modelHash"]:
        failures.append("model hash unchanged: overlays are not part of the model identity")
    else:
        print("model hash differs from the plain import (overlay files are part of the identity)")
    for f in failures:
        print("FAIL", f)
    print("PASS" if not failures else "FAILED")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
