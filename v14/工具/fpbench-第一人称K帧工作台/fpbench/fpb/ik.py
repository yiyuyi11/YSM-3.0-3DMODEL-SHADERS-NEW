"""Grip IK: close each finger about its importer curl axis until its links touch the gun with a small clearance.

Parametrisation = the importer's finger layer (HumanoidImport.Layout.emitAnimations, HumanoidImport.java:1215-1283):
one curl angle per segment about the finger's axis (direction x (+-1,0,0)); keyframe = rot(axis, curl) - rest, X/Y
negated. So the result is both a keyframe set (Bedrock animation JSON, numeric) and per-finger curl arrays that can be
pasted into FITTED_GUN_HAND / FITTED_SUPPORT_HAND style tables.

Closing rule (GraspIt-style autograsp): all joints of a finger close together at fixed rates; when a link's vertices
come within ``clearance`` of the gun (signed distance to the union of the gun's cubes), that joint and every joint
proximal to it stop; distal joints keep closing; joints also stop at their limits. Starts from the open hand (lower
limits) so the result does not depend on the current pose.
"""
import math
import re

import numpy as np

from . import metrics, ysm_arm

PX = 16.0
LIMITS = {"Thumb": ((0, 60), (0, 70), (0, 80)), "default": ((0, 90), (0, 110), (0, 80))}
RATES = {"Thumb": (0.6, 1.0, 1.0), "default": (1.0, 1.0, 0.75)}
TRIGGER_SPREAD = -25.0  # HumanoidImport.java:1191 (fitted gun hand's index)


class FingerRig:
    """Fast re-posing of one finger inside a Frame (hand matrix and the rest of the arm fixed)."""

    def __init__(self, fr, side, finger, boxes, spread=0.0):
        self.fr, self.side, self.finger, self.boxes = fr, side, finger, boxes
        arm = fr.arm
        self.chain = arm.chain(side, finger)
        self.axis = arm.curl_axis(side, finger)
        self.spread = spread
        sid = 1 if side == "Left" else 2
        self.links = [np.nonzero((arm.vert_side == sid) & (arm.vert_bone == b))[0] for b in self.chain]
        allv = np.concatenate(self.links)
        c = fr.arm_pos[allv].mean(0)
        r = np.linalg.norm(fr.arm_pos[allv] - c, axis=1).max()
        self.near = boxes.near(c, 2.5 * r + 0.1)
        self.H = fr.hand[side]
        self.keys = dict(fr.keys)

    def keys_for(self, curls):
        k = dict(self.keys)
        for i, (b, c) in enumerate(zip(self.chain, curls)):
            k[self.fr.arm.names[b]] = self.fr.arm.curl_keyframe(b, self.axis, c, self.spread if i == 0 else 0.0)
        return k

    def link_sdf(self, curls):
        """Min signed distance (px) of each link's vertices to the gun."""
        arm = self.fr.arm
        W = arm.world(self.keys_for(curls))
        out = []
        for vs in self.links:
            if len(vs) == 0:
                out.append(math.inf)
                continue
            p, _ = arm.skin(W, vs)
            p = p @ self.H[:3, :3].T + self.H[:3, 3]
            sd, _ = self.boxes.sdf(p, self.near)
            out.append(float(sd.min()) * PX)
        return out


def _penalty(sd, clearance_px):
    return float(sum(max(0.0, clearance_px - s) for s in sd))


def _autograsp(rig, lo, hi, rates, floor, step, fine):
    """Close all joints together; a link may not get nearer than ``floor`` (px, per link); when one would, that
    joint and every proximal joint stop, distal joints keep closing."""
    n = len(lo)
    th = lo.copy()
    active = np.ones(n, bool)
    d = step
    while active.any():
        prop = np.minimum(th + np.where(active, rates * d, 0), hi)
        sd = rig.link_sdf(prop)
        bad = [i for i in range(n) if sd[i] < floor[i]]
        if bad:
            if d > fine:
                d /= 2
                continue
            stop = np.zeros(n, bool)
            stop[:max(bad) + 1] = True
            if not (active & stop).any():  # the bad link is moved by a distal joint (blended skin weights)
                stop[:np.nonzero(active)[0][0] + 1] = True
            active &= ~stop
            d = step
            continue
        th = prop
        active &= th < hi - 1e-9
        d = step
    return th, rig.link_sdf(th)


def close_finger(rig, limits, rates, clearance_px=0.1, step=1.0, fine=0.05):
    """Autograsp from the open hand. Returns (curls, link sdf px, links inside the clearance with the finger open)."""
    n = len(rig.chain)
    lo = np.array([l[0] for l in limits[:n]], float)
    hi = np.array([l[1] for l in limits[:n]], float)
    rates = np.array(rates[:n], float)
    sd0 = rig.link_sdf(lo)
    blocked_open = [i for i, s in enumerate(sd0) if s < clearance_px]
    # A link already inside the clearance with the finger open (the hand itself sits on/in the gun) may stay as deep
    # as it is open but not go deeper; the rest of the finger still closes around the gun.
    floor = np.minimum(clearance_px, np.array(sd0) - 1e-3)
    th, sd = _autograsp(rig, lo, hi, rates, floor, step, fine)
    if blocked_open:
        # Alternative: the least-penetrating pose along the uniform closing path (2 deg steps); taken only when it is
        # clearly better (fixing such fingers needs the hand/wrist placement, not the fingers).
        best = (_penalty(sd, clearance_px), th, sd)
        s = 2.0
        while True:
            prop = np.minimum(lo + rates * s, hi)
            sdp = rig.link_sdf(prop)
            pen = _penalty(sdp, clearance_px)
            if pen < best[0] - 0.05:
                best = (pen, prop, sdp)
            if (prop >= hi - 1e-9).all():
                break
            s += 2.0
        th, sd = best[1], best[2]
    return th, sd, blocked_open


def solve(fr, sides=("Left", "Right"), fingers=None, clearance_px=0.1, limits=None, step=1.0):
    """IK on a Frame; returns {side: {finger: result}}. Default fingers: all five on the support (left) hand, all but
    the trigger index on the gun (right) hand."""
    boxes = metrics.gun_boxes(fr)
    out = {}
    for side in sides:
        if side not in fr.hand:
            continue
        fl = fingers.get(side) if isinstance(fingers, dict) else fingers
        if fl is None:
            fl = list(ysm_arm.FINGERS) if side == "Left" else ["Thumb", "Middle", "Ring", "Little"]
        res = {}
        for f in fl:
            if not fr.arm.chain(side, f):
                continue
            spread = TRIGGER_SPREAD if (side == "Right" and f == "Index" and fr.grip is not None) else 0.0
            rig = FingerRig(fr, side, f, boxes, spread)
            lim = (limits or {}).get(f) or LIMITS.get(f, LIMITS["default"])
            th, sd, blocked = close_finger(rig, lim, RATES.get(f, RATES["default"]), clearance_px, step)
            # never return something worse than the current (importer) pose of this finger
            cur = [fr.arm.keyframe_curl(b, rig.axis, fr.keys[fr.arm.names[b]]) if fr.arm.names[b] in fr.keys else 0.0
                   for b in rig.chain]
            sd_cur = rig.link_sdf(cur)
            kept = _penalty(sd_cur, clearance_px) < _penalty(sd, clearance_px) - 1e-6
            if kept:
                th, sd = np.array(cur), sd_cur
            keys = rig.keys_for(th)
            res[f] = {"curl_deg": [round(float(x), 2) for x in th], "link_sdf_px": [round(s, 3) for s in sd],
                      "blocked_open_links": blocked, "kept_current": bool(kept),
                      "keys": {fr.arm.names[b]: [round(float(v), 3) for v in keys[fr.arm.names[b]]] for b in rig.chain}}
        out[side] = res
    return out


_BRANCH = {True: re.compile(r"(ctrl\.tac_grip\('fitted'\) \? )(-?[\d.]+)( :)"),
           False: re.compile(r"(ctrl\.tac_hold_gun \? )(-?[\d.]+)( :)")}


def to_animation(sol, fr, clearance_px):
    """Bedrock/YSM animation file in the importer's arm animation layout (animations.parallel2.bones.<bone>.rotation,
    degrees with 2 decimals as HumanoidImport emits them). ``fpbench.importer_layer`` holds the importer's own Molang
    rotation strings for those bones with the gun branch (ctrl.tac_grip('fitted') for fitted guns, ctrl.tac_hold_gun
    otherwise) replaced by the solved values: a drop-in replacement for the generated parallel2 entries."""
    bones, curls, layer = {}, {}, {}
    src = fr.arm.animation.get("animations", {}).get("parallel2", {}).get("bones", {})
    pat = _BRANCH[fr.grip is not None]
    for side, fs in sol.items():
        for f, r in fs.items():
            for b, k in r["keys"].items():
                k = [round(v, 2) for v in k]
                bones[b] = {"rotation": k}
                rot = (src.get(b) or {}).get("rotation")
                if isinstance(rot, list) and len(rot) == 3 and all(isinstance(e, str) and pat.search(e) for e in rot):
                    layer[b] = {"rotation": [pat.sub(lambda m, v=v: f"{m.group(1)}{v:.2f}{m.group(3)}", e, count=1)
                                             for e, v in zip(rot, k)]}
            curls[side + f] = r["curl_deg"]
    java = {}
    for side, fs in sol.items():
        name = "FITTED_SUPPORT_HAND" if side == "Left" else "FITTED_GUN_HAND"
        java[side] = name + " = Map.of(" + ", ".join(
            f'"{f}", new float[] {{{", ".join(f"{c:g}f" for c in r["curl_deg"])}}}' for f, r in fs.items()) + ")"
    return {"format_version": "1.8.0",
            "animations": {"parallel2": {"loop": True, "bones": bones}},
            "fpbench": {"gun": fr.gun.id, "clip": fr.clip or "idle", "t": fr.t, "clearance_px": clearance_px,
                        "curl": curls, "importer_tables": java, "importer_layer": {"bones": layer},
                        "note": "rotation = keyframe degrees (X/Y negated, added to the bone's rest) of the finger "
                                "layer; importer_tables = per-segment curls for HumanoidImport FITTED_* tables "
                                "(fitted guns); feed back with --pose"}}
