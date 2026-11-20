"""Per-gun first-person hand profiles (config/ysm_tacz_fp/<ns>/<path>.json, format 1; contract:
~/ysm/handoff/tacz-fp-profile-contract.md): the grip placement, idle finger pose and keyframed finger overrides during
TACZ clips, solved per YSM model.

Per model and gun:
1. grip: the gun's placement (GRIPS, else none). If the open hand's palm or finger roots sit deeper than
   ``clearance`` in the gun for this model, the palm is slid out (least-norm shift, Newton on the worst clearance) and
   the model gets its own grip.
2. idle: grip IK (ik.solve) on the idle pose: five support fingers, gun hand all but the trigger index.
3. clips: the same IK per frame (``fps``) against the whole gun, moving magazine included. A finger whose links stay
   farther than NO_CONTACT_PX from the gun relaxes (RELAXED curls) instead of closing to a fist. Curls are median
   filtered, rate limited (MAX_STEP_DEG per frame) and the keyframes simplified within ``tolerance`` degrees.
"""
import json
import math
import time

import numpy as np

from . import ik, metrics, scene, tacz_gun, ysm_arm

# Gun-level grip placements, TaczGrips convention (palm x, y, z, wrist x, y, z per hand).
# tacz:m4a1: TaczGrips' fitted entry, right wrist turned 9.6 deg radially to bring its ulnar deviation inside the
# -30 deg limit (-39.1 before), then both palms slid out of the solid gun by the median least shift over the 24
# models (left 0.7 px down out of the handguard tube, right 0.35 px onto the grip), fpbench 2026-11-18.
# tacz:ak47 (no TaczGrips entry): TACZ's own hand placement re-expressed as a grip (wrists unturned), both palms slid
# out of the solid gun by pelica's least shift (left 1.3 px off the handguard, right 1.5 px off the pistol grip).
GRIPS = {"tacz:m4a1": ((5.344, 18.038, 1.715, 80.64, -63.68, -49.49), (-6.747, 13.889, -1.264, 29.176, 0.0, 9.0)),
         "tacz:ak47": ((6.625, 15.381, -0.645, 0.0, 0.0, 0.0), (-6.771, 15.399, -0.15, 0.0, 0.0, 0.0))}
RELAXED = {"Thumb": (5.0, 10.0, 10.0), "default": (15.0, 25.0, 15.0)}  # HumanoidImport RELAXED / RELAXED_THUMB
RELAXED_EXTRA = {"Ring": 5.0, "Little": 10.0}
NO_CONTACT_PX = 1.0
MAX_STEP_DEG = 12.0
CLIPS = ("reload_tactical",)


def _pose(gun_id, grip, bones=None):
    p = scene.Pose()
    p.grips = {gun_id: grip} if grip else {}
    if bones:
        p.bones.update({b: list(v) for b, v in bones.items()})
    return p


def _open(arm, sides):
    return {arm.names[b]: list(arm.curl_keyframe(b, arm.curl_axis(s, f), 0.0))
            for s in sides for f in ysm_arm.FINGERS for b in arm.chain(s, f)}


def open_clearance(arm, gun, grip, side):
    """Min signed distance (px) of the open hand's palm and finger roots to the solid gun."""
    fr = scene.Frame(arm, gun, pose=_pose(gun.id, grip, _open(arm, (side,))))
    sid = 1 if side == "Left" else 2
    sel = (arm.vert_side == sid) & (arm.vert_part == "palm")
    for f in ysm_arm.FINGERS:
        ch = arm.chain(side, f)
        if ch:
            sel |= (arm.vert_side == sid) & (arm.vert_bone == ch[0])
    sd, _ = metrics.gun_boxes(fr).sdf(fr.arm_pos[np.nonzero(sel)[0]])
    return float(sd.min()) * 16


def shift_grip(arm, gun, grip, side, d_px):
    """Grip with this hand's palm moved by d_px (view space px): TaczGrips.place translates in the locator frame."""
    fr = scene.Frame(arm, gun, pose=_pose(gun.id, grip))
    A = fr.hand[side][:3, :3] @ np.diag([-1.0, -1.0, 1.0])
    i = 0 if side == "Left" else 1
    hand = list(grip[i])
    hand[:3] = (np.array(hand[:3]) + 16 * np.linalg.solve(A, np.asarray(d_px) / 16)).round(3).tolist()
    out = [tuple(grip[0]), tuple(grip[1])]
    out[i] = tuple(float(x) for x in hand)
    return tuple(out)


def refine_grip(arm, gun, grip, clearance=0.1, tol=0.05, h=0.15, iters=6):
    """Per-model grip when the gun-level one leaves this model's open palm/finger roots inside the gun."""
    for side in ("Left", "Right"):
        f0 = open_clearance(arm, gun, grip, side)
        if f0 >= clearance - tol:
            continue
        d = np.zeros(3)
        for _ in range(iters):
            f = open_clearance(arm, gun, shift_grip(arm, gun, grip, side, d), side)
            if abs(f - clearance) < 0.02:
                break
            g = np.array([(open_clearance(arm, gun, shift_grip(arm, gun, grip, side, d + h * e), side) - f) / h
                          for e in np.eye(3)])
            d = d + g * (clearance - f) / max(g @ g, 1e-6)
        grip = shift_grip(arm, gun, grip, side, d)
    return grip


def _relaxed(finger):
    r = RELAXED.get(finger, RELAXED["default"])
    return [c + RELAXED_EXTRA.get(finger, 0.0) for c in r]


def solve_frame(fr, clearance):
    """Finger curls {side+finger: [c1..c3]} on one frame; no-contact fingers relax."""
    out = {}
    for side, fs in ik.solve(fr, clearance_px=clearance).items():
        for f, r in fs.items():
            free = min(r["link_sdf_px"]) > NO_CONTACT_PX
            out[side + f] = _relaxed(f)[:len(r["curl_deg"])] if free else list(r["curl_deg"])
    return out


def curls_to_bones(arm, curls):
    bones = {}
    for k, cs in curls.items():
        side = "Left" if k.startswith("Left") else "Right"
        f = k[len(side):]
        axis = arm.curl_axis(side, f)
        for b, c in zip(arm.chain(side, f), cs):
            bones[arm.names[b]] = [round(float(v), 2) for v in arm.curl_keyframe(b, axis, c)]
    return bones


def smooth(series, max_step=MAX_STEP_DEG):
    """(n,) curl series: 3-tap median (no single-frame pops), then a per-frame rate limit (both directions)."""
    x = np.asarray(series, float)
    if len(x) >= 3:
        m = x.copy()
        m[1:-1] = np.median(np.stack([x[:-2], x[1:-1], x[2:]]), 0)
        x = m
    for _ in range(len(x)):
        d = np.diff(x)
        bad = np.abs(d) > max_step + 1e-9
        if not bad.any():
            break
        i = np.nonzero(bad)[0][0]
        mid = (x[i] + x[i + 1]) / 2
        x[i], x[i + 1] = mid - math.copysign(max_step / 2, d[i]), mid + math.copysign(max_step / 2, d[i])
    return x


def simplify(ts, vals, tol):
    """Keyframe indices whose linear interpolation stays within tol (deg, every component) of vals (n,3)."""
    keep = [0, len(ts) - 1]

    def rec(a, b):
        if b - a < 2:
            return
        w = (np.asarray(ts[a + 1:b]) - ts[a]) / (ts[b] - ts[a])
        lin = vals[a] + w[:, None] * (vals[b] - vals[a])
        err = np.abs(vals[a + 1:b] - lin).max(1)
        k = int(err.argmax())
        if err[k] > tol:
            keep.append(a + 1 + k)
            rec(a, a + 1 + k)
            rec(a + 1 + k, b)
    rec(0, len(ts) - 1)
    return sorted(set(keep))


def solve_clip(arm, gun, clip, grip, clearance=0.1, fps=15.0, tolerance=1.0):
    length = gun.clip(clip).length
    ts = list(np.linspace(0.0, length, max(2, int(round(length * fps)) + 1)))
    frames = [solve_frame(scene.Frame(arm, gun, clip, t, pose=_pose(gun.id, grip)), clearance) for t in ts]
    keys = frames[0].keys()
    curls = {k: np.stack([smooth([fr[k][i] for fr in frames]) for i in range(len(frames[0][k]))], 1) for k in keys}
    per_t = [curls_to_bones(arm, {k: curls[k][j] for k in keys}) for j in range(len(ts))]
    bones = {}
    for b in per_t[0]:
        vals = np.array([p[b] for p in per_t])
        idx = simplify(ts, vals, tolerance)
        bones[b] = {"rotation": {f"{ts[i]:.4g}": vals[i].tolist() for i in idx}}
    return ts, bones


def sample_bones(bones, t):
    """Linear interpolation of clip bones at time t."""
    out = {}
    for b, ch in bones.items():
        ks = sorted(ch["rotation"], key=float)
        tk = np.array([float(k) for k in ks])
        v = np.array([ch["rotation"][k] for k in ks])
        out[b] = [float(np.interp(t, tk, v[:, c])) for c in range(3)]
    return out


def finger_stats(fr, sides_fingers):
    """Max finger penetration (px), inside vertices and max gap over the solved fingers."""
    m = metrics.measure(fr)
    pen, ins, gap = 0.0, 0, 0.0
    for side, fl in sides_fingers.items():
        h = m["hands"].get(side)
        if not h:
            continue
        for f in fl:
            e = h["fingers"].get(f)
            if e:
                pen, ins, gap = max(pen, e["penetration_px"]), ins + e["inside_vertices"], max(gap, e["gap_px"])
        pen = max(pen, h.get("palm", {}).get("penetration_px", 0.0))
    return {"pen_px": round(pen, 3), "inside": ins, "gap_px": round(gap, 3)}


SOLVED = {"Left": list(ysm_arm.FINGERS), "Right": ["Thumb", "Middle", "Ring", "Little"]}


def solve_model(model_path, gun_id, packs=None, clips=CLIPS, clearance=0.1, fps=15.0, tolerance=1.0, evaluate=True,
                jar=None):
    """One model x gun: the profile entry and before/after metrics."""
    t0 = time.time()
    arm = ysm_arm.load(model_path, jar=jar, log=lambda m: None)
    gun = tacz_gun.Gun(gun_id, packs)
    base = GRIPS.get(gun_id)
    grip = refine_grip(arm, gun, base, clearance) if base else None
    idle_fr = scene.Frame(arm, gun, pose=_pose(gun_id, grip))
    idle = curls_to_bones(arm, solve_frame(idle_fr, clearance))
    entry = {}
    if grip:
        entry["grip"] = {"left": [round(x, 3) for x in grip[0]], "right": [round(x, 3) for x in grip[1]]}
    entry["idle"] = {"bones": idle}
    entry["clips"] = {}
    stats = {}
    clip_ts = {}
    for c in clips:
        ts, bones = solve_clip(arm, gun, c, grip, clearance, fps, tolerance)
        entry["clips"][c] = {"bones": bones}
        clip_ts[c] = ts
    stats["solve_s"] = round(time.time() - t0, 1)
    stats["per_model_grip"] = bool(grip and base and tuple(map(tuple, grip)) != tuple(map(tuple, base)))
    if evaluate:
        today = scene.Pose()  # today's game: TaczGrips table + importer finger layer
        stats["idle"] = {"before": finger_stats(scene.Frame(arm, gun, pose=today), SOLVED),
                         "after": finger_stats(scene.Frame(arm, gun, pose=_pose(gun_id, grip, idle)), SOLVED)}
        for c, ts in clip_ts.items():
            bones = entry["clips"][c]["bones"]
            b4 = [finger_stats(scene.Frame(arm, gun, c, t, pose=today), SOLVED) for t in ts]
            af = [finger_stats(scene.Frame(arm, gun, c, t, pose=_pose(gun_id, grip, {**idle, **sample_bones(bones, t)})),
                               SOLVED) for t in ts]
            agg = lambda xs: {"pen_px": max(x["pen_px"] for x in xs), "inside": max(x["inside"] for x in xs),
                              "gap_px": max(x["gap_px"] for x in xs)}
            stats[c] = {"before": agg(b4), "after": agg(af), "frames": len(ts)}
    stats["total_s"] = round(time.time() - t0, 1)
    return entry, stats


def gun_file(gun_id, entries, packs=None, clips=CLIPS):
    gun = tacz_gun.Gun(gun_id, packs)
    lengths = {"idle": round(gun.clips["idle"].length, 3)} if "idle" in gun.clips else {}
    for c in clips:
        lengths[c] = round(gun.clip(c).length, 3)
    return {"format": 1, "gun": gun_id, "clipLengths": lengths, "models": entries}


def write(path, data):
    with open(path, "w") as fh:
        json.dump(data, fh, indent=1)
