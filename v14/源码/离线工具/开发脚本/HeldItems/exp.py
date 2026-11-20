"""Experiment: evaluate candidate carry offsets across states and items.

Usage in python: from exp import evaluate; evaluate(prefix, carry={state: {bone: (x,y,z)}}, items=[...])
carry maps a state name (or "*") to constant offsets added like a parallel layer.
"""
from collide import check


def layer(offsets):
    return {"loop": True, "bones": {b: {"rotation": list(v)} for b, v in offsets.items()}}


def override(offsets):
    return {"loop": True, "override": True, "bones": {b: {"rotation": list(v)} for b, v in offsets.items()}}


STATES = ["idle", "walk", "walk_back", "run", "sneak", "sneaking"]


def evaluate(prefix, carry, items=("sword", "block", "flat", "bow", "crossbow"), states=STATES, hold=None,
             shield=True, steps=10, left_carry=None):
    out = {}
    print("state      " + " ".join(f"{i:>17}" for i in list(items) + (["shield(L)"] if shield else [])))
    for s in states:
        offsets = carry.get(s, carry.get("*", {}))
        clips = [s]
        row = []
        for i in items:
            extra = []
            if hold and i in hold:
                extra.append(override(hold[i]))
            d, c, t, part = check(prefix, clips + extra + [layer(offsets)], i, False, steps, verbose=False)
            out[(s, i)] = (d, -c)
            row.append(f"in{d:4.1f} {part[:9]:>9}" if d > 0 else f"clear {-c:4.2f}")
        if shield:
            lo = (left_carry or {}).get(s, (left_carry or {}).get("*", {}))
            d, c, t, part = check(prefix, clips + [layer(lo)], "shield", True, steps, verbose=False)
            out[(s, "shield")] = (d, -c)
            row.append(f"in{d:4.1f} {part[:9]:>9}" if d > 0 else f"clear {-c:4.2f}")
        print(f"{s:10} " + " ".join(f"{r:>17}" for r in row), flush=True)
    return out
