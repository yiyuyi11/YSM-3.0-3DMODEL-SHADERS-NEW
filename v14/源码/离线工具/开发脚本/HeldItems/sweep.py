"""sweep.py <prefix>: worst penetration per state x item (right hand) and shield (left)."""
import sys
from collide import check
prefix = sys.argv[1]
extra = sys.argv[2:] if len(sys.argv) > 2 else []
states = ["idle", "walk", "walk_back", "run", "sneak", "sneaking"]
items = ["sword", "block", "flat", "bow", "crossbow"]
print("state      " + " ".join(f"{i:>22}" for i in items + ["shield(L)"]))
for s in states:
    clips = [s, "parallel4"] + extra
    row = []
    for i in items:
        d, c, t, part = check(prefix, clips, i, False, 10, verbose=False)
        row.append(f"in{d:4.1f} {part[:10]:>10}" if d > 0 else f"clear {-c:4.2f}")
    d, c, t, part = check(prefix, clips, "shield", True, 10, verbose=False)
    row.append(f"in{d:4.1f} {part[:10]:>10}" if d > 0 else f"clear {-c:4.2f}")
    print(f"{s:10} " + " ".join(f"{r:>22}" for r in row), flush=True)
