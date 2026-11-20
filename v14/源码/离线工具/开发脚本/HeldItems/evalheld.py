"""evalheld.py <prefix> [states...]: build held variants and check every item class against them."""
import json
import sys

from collide import check
from held import Solver, held_clip

prefix = sys.argv[1]
states = sys.argv[2:] or ["idle", "walk", "walk_back", "run", "sneak", "sneaking"]
solver = Solver(prefix)
PLAN = {  # right kind, items checked on the right ; left kind, items checked on the left
    "tool": ("tool", ["sword"], "tool", ["sword"]),
    "generic": ("generic", ["block", "flat", "bow", "crossbow"], "generic", ["block", "flat", "bow"]),
    "shield": (None, [], "shield", ["shield"]),
}
for state in states:
    for name, (rk, ritems, lk, litems) in PLAN.items():
        kinds = ([("Right", rk)] if rk else []) + ([("Left", lk)] if lk else [])
        clip = held_clip(solver, state, kinds)
        json.dump(clip, open(f"{prefix}_held_{state}_{name}.json", "w"))
        line = []
        for item in ritems:
            d, c, t, part = check(prefix, [clip], item, False, 10, verbose=False)
            line.append(f"R.{item}:" + (f"IN {d:.1f} {part[:10]}" if d > 0 else f"{-c:.1f}"))
        for item in litems:
            d, c, t, part = check(prefix, [clip], item, True, 10, verbose=False)
            line.append(f"L.{item}:" + (f"IN {d:.1f} {part[:10]}" if d > 0 else f"{-c:.1f}"))
        print(f"{state:9} {name:8} " + "  ".join(line), flush=True)
