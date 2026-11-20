"""Contact metrics of a Frame, in model pixels (1 px = 1/16 block in view space, the unit of both the gun model and
the arm model). The gun is the union of its drawn cubes (oriented boxes) and of the convex hulls of its compact parts
(``solid_parts``: a handguard drawn as a hollow tube of thin plates is solid to a hand); the hand is the skinned arm
mesh's vertices.
"""
import math

import numpy as np

from . import ysm_arm

PX = 16.0
FINGERS = ysm_arm.FINGERS
# Anatomical wrist range (deg), adult active ROM: flexion 80, extension 70, radial 20, ulnar 30; hand twist about the
# forearm axis is mostly forearm pronation/supination, allowed +-90 here.
WRIST_LIMITS = {"flexion": (-70.0, 80.0), "deviation": (-30.0, 20.0), "twist": (-90.0, 90.0)}
# A drawn part instance counts as solid (its convex hull, approximated by a k-DOP, joins the gun) when at least
# SOLID_MIN of its hull is either inside its cubes or enclosed by them (from a point, at least ENCLOSED_RAYS of 14 rays
# along the part's principal axes and their diagonals hit the part): tubes and plated shells such as a handguard drawn
# as thin plates are solid, sparse groups of pins and open shapes (a carry handle, a trigger guard) are not. Trigger
# parts always stay open (the trigger finger goes through the guard).
SOLID_MIN = 0.6
ENCLOSED_RAYS = 10
_DIRS = np.array([[1, 0, 0], [0, 1, 0], [0, 0, 1], [1, 1, 1], [1, 1, -1], [1, -1, 1], [-1, 1, 1], [1, 1, 0], [1, -1, 0],
                  [1, 0, 1], [1, 0, -1], [0, 1, 1], [0, 1, -1]], float)
_DIRS /= np.linalg.norm(_DIRS, axis=1, keepdims=True)
_CORNERS = np.array([[i, j, k] for i in (0, 1) for j in (0, 1) for k in (0, 1)], float)


class Boxes:
    """Oriented boxes from cube frames (m,4,3: min corner, +x, +y, +z corners), view space."""

    def __init__(self, frames, names):
        c0 = frames[:, 0]
        e = frames[:, 1:] - c0[:, None]  # (m,3,3) rows = edge vectors
        L = np.linalg.norm(e, axis=2)
        U = e / np.maximum(L[..., None], 1e-12)
        for k in range(3):  # degenerate (flat) cubes: complete the frame
            bad = L[:, k] < 1e-9
            if bad.any():
                a, b = U[bad, (k + 1) % 3], U[bad, (k + 2) % 3]
                n = np.cross(a, b)
                U[bad, k] = n / np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
        self.U, self.h = U, L / 2
        self.c = c0 + e.sum(1) / 2
        self.r = np.linalg.norm(self.h, axis=1)
        self.names = np.array(names, dtype=object)
        self.corners = c0[:, None] + np.einsum("ck,bkj->bcj", _CORNERS, e)  # (m,8,3)
        self.hull_of = np.full(len(c0), -1)  # box -> hull index
        self.hulls = []  # (normals (k,3), offsets (k,), centre, radius, member boxes)

    def dop(self, idx):
        """k-DOP of boxes ``idx``: planes along their own axes and 13 fixed directions (both signs)."""
        d = np.concatenate([_DIRS, self.U[idx].reshape(-1, 3)])
        d = d * np.where(d[np.arange(len(d)), np.abs(d).argmax(1)] < 0, -1.0, 1.0)[:, None]  # one sign per axis
        n = np.unique(np.round(d, 3), axis=0)
        n = n[np.linalg.norm(n, axis=1) > 0.5]
        n /= np.linalg.norm(n, axis=1, keepdims=True)
        n = np.concatenate([n, -n])
        pts = self.corners[idx].reshape(-1, 3)
        off = (pts @ n.T).max(0)
        c = pts.mean(0)
        return n, off, c, float(np.sqrt(((pts - c) ** 2).sum(1).max()))

    def add_hull(self, idx):
        n, off, c, r = self.dop(idx)
        self.hull_of[idx] = len(self.hulls)
        self.hulls.append((n, off, c, r, np.asarray(idx)))

    def solidity(self, idx, samples=1200, seed=0):
        """Fraction of the k-DOP of boxes ``idx`` that is inside them or enclosed by them (Monte Carlo)."""
        idx = np.asarray(idx)
        n, off, c, r = self.dop(idx)
        pts = self.corners[idx].reshape(-1, 3)
        lo, hi = pts.min(0), pts.max(0)
        q = lo + np.random.default_rng(seed).random((samples * 4, 3)) * (hi - lo)
        q = q[(q @ n.T - off).max(1) < 0][:samples]
        if len(q) == 0:
            return 1.0
        sd, _ = self._box_sdf(q, idx)
        hollow = q[sd >= 0]
        _, axes = np.linalg.eigh(np.cov((pts - pts.mean(0)).T))
        rays = np.array([[1, 0, 0], [0, 1, 0], [0, 0, 1]] + [[i, j, 1] for i in (-1, 1) for j in (-1, 1)], float)
        rays /= np.linalg.norm(rays, axis=1, keepdims=True)
        rays = np.concatenate([rays, -rays]) @ axes.T  # 14 directions in view space
        U, h, bc = self.U[idx], self.h[idx], self.c[idx]
        dl = np.einsum("bij,rj->rbi", U, rays)  # ray directions in box frames (r,b,3)
        dl = np.where(np.abs(dl) < 1e-9, 1e-9, dl)
        enclosed = 0
        for s in range(0, len(hollow), 128):
            o = np.einsum("bij,sbj->sbi", U, hollow[s:s + 128, None] - bc[None])  # (s,b,3)
            t1 = (-h[None, None] - o[:, None]) / dl[None]
            t2 = (h[None, None] - o[:, None]) / dl[None]  # (s,r,b,3)
            tmin = np.minimum(t1, t2).max(3)
            tmax = np.maximum(t1, t2).min(3)
            hit = ((tmax >= np.maximum(tmin, 0)).any(2)).sum(1)  # rays hitting any box, per sample
            enclosed += int((hit >= ENCLOSED_RAYS).sum())
        return (len(q) - len(hollow) + enclosed) / len(q)

    def near(self, center, radius):
        return np.nonzero(np.linalg.norm(self.c - center, axis=1) < radius + self.r)[0]

    def sdf(self, pts, idx=None, chunk=4096):
        """Signed distance (blocks) of points to the solid gun (boxes ``idx`` and the hulls they belong to), and the
        nearest box index (a member box for a hull).

        Boxes that cannot be nearest to any point of the set are culled first (lower bound |c - centre| - r - R vs
        the best upper bound |c - centre| + R), so the set's minimum and every negative (inside) value are exact;
        positive values of points far from the set's closest box may be overestimated. A hull's distance is the
        largest plane distance: exact inside, a lower bound outside near its edges."""
        idx = np.arange(len(self.c)) if idx is None else np.asarray(idx)
        out, arg = self._box_sdf(pts, idx, chunk)
        if self.hulls and len(idx) and len(pts):
            for h in np.unique(self.hull_of[idx]):
                if h < 0:
                    continue
                n, off, c, r, members = self.hulls[h]
                hd = (pts @ n.T - off).max(1)
                better = hd < out
                out[better] = hd[better]
                arg[better] = members[0]
        return out, arg

    def _box_sdf(self, pts, idx, chunk=4096):
        if len(idx) == 0 or len(pts) == 0:
            return np.full(len(pts), np.inf), np.full(len(pts), -1)
        sc = pts.mean(0)
        R = np.sqrt(((pts - sc) ** 2).sum(1).max())
        dc = np.sqrt(((self.c[idx] - sc) ** 2).sum(1))
        idx = idx[dc - self.r[idx] - R <= (dc + R).min()]
        out = np.empty(len(pts))
        arg = np.empty(len(pts), int)
        U, h, c = self.U[idx], self.h[idx], self.c[idx]
        UT = U.reshape(-1, 3).T  # (3, 3b): columns = box axes
        cU = np.einsum("bij,bj->bi", U, c).reshape(-1)
        for s in range(0, len(pts), chunk):
            p = pts[s:s + chunk]
            q = (p @ UT - cU).reshape(len(p), len(idx), 3)
            d = np.abs(q) - h[None]
            sd = np.sqrt((np.maximum(d, 0) ** 2).sum(2)) + np.minimum(d.max(2), 0)
            a = sd.argmin(1)
            out[s:s + chunk] = sd[np.arange(len(p)), a]
            arg[s:s + chunk] = idx[a]
        return out, arg


def solid_parts(gun, boxes, groups):
    """Part names whose drawn instances count as solid (convex hull), decided once per gun from the fill ratio."""
    cache = gun.__dict__.setdefault("_solid_parts", {})
    for g in np.unique(groups):
        idx = np.nonzero(groups == g)[0]
        name = boxes.names[idx[0]]
        if name not in cache:
            cache[name] = (len(idx) > 1 and "trigger" not in name
                           and boxes.solidity(idx) >= SOLID_MIN)
    return {k for k, v in cache.items() if v}


def gun_boxes(fr, hulls=True):
    """The gun as a solid: its cubes, plus the hulls of its solid parts (``hulls=False``: cubes only)."""
    b = Boxes(fr.gun_boxes, fr.gun_box_names)
    if hulls and len(fr.gun_box_groups):
        solid = solid_parts(fr.gun, b, fr.gun_box_groups)
        for g in np.unique(fr.gun_box_groups):
            idx = np.nonzero(fr.gun_box_groups == g)[0]
            if b.names[idx[0]] in solid:
                b.add_hull(idx)
    return b


def _hand_vertices(arm, side_i):
    """Vertex sets of one hand: fingers and palm (wrist and below), forearm (rest of the arm)."""
    sets = {}
    m = arm.vert_side == side_i
    for f in FINGERS:
        sets[f] = np.nonzero(m & (arm.vert_part == f))[0]
    sets["palm"] = np.nonzero(m & (arm.vert_part == "palm"))[0]
    sets["forearm"] = np.nonzero(m & (arm.vert_part == "arm"))[0]
    return sets


def fingertips(arm, side):
    """Per finger the vertex farthest along the last segment (bind pose)."""
    out = {}
    for f in FINGERS:
        ch = arm.chain(side, f)
        if not ch:
            continue
        last = ch[-1]
        prev = ch[-2] if len(ch) > 1 else arm.index[side + "Hand"]
        d = arm.pivot[last] - arm.pivot[prev]
        d /= np.linalg.norm(d)
        vs = np.nonzero(arm.vert_bone == last)[0]
        if len(vs):
            out[f] = vs[np.argmax((arm.positions[vs] * 16 - arm.pivot[last]) @ d)]
    return out


def grip_region(fr, boxes, palm, radius_px=2.5, parts=None):
    """Gun boxes forming the grip under a hand: named parts, or boxes within radius of the palm centre."""
    if parts:
        idx = np.nonzero(np.isin(boxes.names, list(parts)))[0]
    else:
        idx = boxes.near(palm, radius_px / PX + 0.5)
        if len(idx):
            sd, _ = boxes.sdf(palm[None], idx)
            # boxes whose own surface is within radius of the palm
            d = np.array([boxes.sdf(palm[None], np.array([i]))[0][0] for i in idx])
            idx = idx[d < radius_px / PX]
    return idx


def _axis_of(boxes, idx):
    """Principal axis of the region (corners of its boxes, volume-ish weighted) and its centroid."""
    pts = []
    for i in idx:
        U, h, c = boxes.U[i], boxes.h[i], boxes.c[i]
        for sx in (-1, 1):
            for sy in (-1, 1):
                for sz in (-1, 1):
                    pts.append(c + U.T @ (h * (sx, sy, sz)))
    pts = np.array(pts)
    cen = pts.mean(0)
    w, v = np.linalg.eigh(np.cov((pts - cen).T))
    return cen, v[:, -1], w


def wrist_angles(fr, side):
    """Hand relative to forearm in the arm model: flexion (+ toward the palm), deviation (+ radial, toward the thumb),
    twist about the forearm axis. Rest frame: fingers -Y, palm normal +X (left) / -X (right), thumb -Z."""
    arm = fr.arm
    Wf = fr.bone_world[arm.index[side + "ForeArm"]][:3, :3]
    Wh = fr.bone_world[arm.index[side + "Hand"]][:3, :3]
    R = Wf.T @ Wh
    down = np.array([0, -1.0, 0])
    n = np.array([1.0 if side == "Left" else -1.0, 0, 0])
    thumb = np.array([0, 0, -1.0])
    d = R @ down
    flex = math.degrees(math.atan2(d @ n, d @ down))
    dev = math.degrees(math.atan2(d @ thumb, d @ down))
    axis = np.cross(down, d)
    s = np.linalg.norm(axis)
    if s > 1e-9:
        S = ysm_arm.axis_angle(axis / s, math.degrees(math.atan2(s, down @ d)))
    else:
        S = np.eye(3)
    v = S.T @ R @ n
    twist = math.degrees(math.atan2(v @ np.cross(down, n), v @ n))
    out = {"flexion_deg": flex, "deviation_deg": dev, "twist_deg": twist}
    viol = {}
    for k, (lo, hi) in WRIST_LIMITS.items():
        val = out[k + "_deg"]
        if val < lo or val > hi:
            viol[k] = round(val - (hi if val > hi else lo), 2)
    out["limit_violations_deg"] = viol
    return out


def _min_dist(a, b, chunk=4096):
    if len(a) == 0 or len(b) == 0:
        return math.inf
    bb = (b ** 2).sum(1)
    best = math.inf
    for s in range(0, len(a), chunk):
        x = a[s:s + chunk]
        d2 = (x ** 2).sum(1)[:, None] + bb[None] - 2 * x @ b.T
        best = min(best, float(d2.min()))
    return math.sqrt(max(best, 0.0))


def measure(fr, grip_parts=None, boxes=None):
    """All metrics of a frame (dict, JSON-ready)."""
    arm = fr.arm
    boxes = boxes or gun_boxes(fr)
    res = {"gun": fr.gun.id, "clip": fr.clip or "idle", "t": fr.t, "grip_fitted": fr.grip is not None,
           "units": "model px (1/16 block, view space)", "hands": {}}
    hand_pts = {}
    for side, side_i in (("Left", 1), ("Right", 2)):
        if side not in fr.hand:
            continue
        sets = _hand_vertices(arm, side_i)
        palm = fr.bone_view(side + "HandLocator")
        allv = np.concatenate([sets[k] for k in sets])
        center = fr.arm_pos[allv].mean(0)
        rad = np.linalg.norm(fr.arm_pos[allv] - center, axis=1).max()
        near = boxes.near(center, rad + 0.2)
        h = {"palm_center_view_px": (palm * PX).round(3).tolist(), "fingers": {}}
        tips = fingertips(arm, side)
        for part, vs in sets.items():
            if len(vs) == 0:
                continue
            sd, nb = boxes.sdf(fr.arm_pos[vs], near)
            inside = sd < 0
            entry = {"penetration_px": round(float(max(0.0, -sd.min())) * PX, 3),
                     "inside_vertices": int(inside.sum()), "vertices": int(len(vs)),
                     "gap_px": round(float(max(0.0, sd.min())) * PX, 3),
                     "nearest_part": str(boxes.names[nb[sd.argmin()]]) if nb[sd.argmin()] >= 0 else None}
            if part in tips:
                td, _ = boxes.sdf(fr.arm_pos[tips[part]][None], near)
                entry["tip_gap_px"] = round(float(td[0]) * PX, 3)
            if part in FINGERS:
                h["fingers"][part] = entry
            else:
                h[part] = entry
        # grip under the palm
        gi = grip_region(fr, boxes, palm, parts=grip_parts)
        if len(gi) >= 1:
            cen, axis, ev = _axis_of(boxes, gi)
            rel = palm - cen
            dist = np.linalg.norm(rel - axis * (rel @ axis))
            knuckles = fr.bone_view(side + "Little1") - fr.bone_view(side + "Index1")
            fingers_dir = fr.bone_view(side + "Middle1") - fr.bone_view(side + "Hand")
            ang = lambda a, b: math.degrees(math.acos(abs(a @ b) / (np.linalg.norm(a) * np.linalg.norm(b))))
            g = {"parts": sorted(set(boxes.names[gi].tolist())), "boxes": int(len(gi)),
                 "palm_to_axis_px": round(float(dist) * PX, 3),
                 "knuckle_line_vs_axis_deg": round(ang(knuckles, axis), 2),
                 "finger_dir_vs_axis_deg": round(ang(fingers_dir, axis), 2),
                 "palm_surface_gap_px": round(float(boxes.sdf(palm[None], gi)[0][0]) * PX, 3)}
            if "Thumb" in tips and "Middle" in tips:
                tt, mt = fr.arm_pos[tips["Thumb"]] - cen, fr.arm_pos[tips["Middle"]] - cen
                tt, mt = tt - axis * (tt @ axis), mt - axis * (mt @ axis)
                g["thumb_opposition_deg"] = round(math.degrees(math.acos(np.clip(
                    tt @ mt / max(np.linalg.norm(tt) * np.linalg.norm(mt), 1e-12), -1, 1))), 2)
            h["grip"] = g
        if "Thumb" in tips:
            th = fr.arm_pos[tips["Thumb"]]
            h["thumb"] = {"tip_gap_px": h["fingers"].get("Thumb", {}).get("tip_gap_px"),
                          "tip_to_index_tip_px": round(float(np.linalg.norm(th - fr.arm_pos[tips["Index"]])) * PX, 3)
                          if "Index" in tips else None,
                          "tip_to_palm_px": round(float(np.linalg.norm(th - palm)) * PX, 3)}
        h["wrist"] = {k: (round(v, 2) if isinstance(v, float) else v) for k, v in wrist_angles(fr, side).items()}
        res["hands"][side] = h
        hand_pts[side] = fr.arm_pos[np.concatenate([sets[f] for f in FINGERS] + [sets["palm"]])]
        res["hands"][side]["_arm_pts"] = fr.arm_pos[sets["forearm"]]
    col = {}
    if "Left" in hand_pts and "Right" in hand_pts:
        sub = lambda a: a[:: max(1, len(a) // 3000)]
        col["hand_hand_min_px"] = round(_min_dist(sub(hand_pts["Left"]), sub(hand_pts["Right"])) * PX, 3)
        la, ra = res["hands"]["Left"]["_arm_pts"], res["hands"]["Right"]["_arm_pts"]
        col["arm_arm_min_px"] = round(_min_dist(sub(la), sub(ra)) * PX, 3)
    for side in res["hands"]:
        res["hands"][side].pop("_arm_pts", None)
    pen = {s: sum(e["inside_vertices"] for e in h["fingers"].values()) + h.get("palm", {}).get("inside_vertices", 0)
           for s, h in res["hands"].items()}
    col["hand_gun_inside_vertices"] = pen
    col["forearm_gun_inside_vertices"] = {s: h.get("forearm", {}).get("inside_vertices", 0)
                                          for s, h in res["hands"].items()}
    res["collisions"] = col
    return res


def penetrating_vertices(fr, boxes=None):
    """Bool per arm vertex: inside the gun (for red highlights)."""
    boxes = boxes or gun_boxes(fr)
    out = np.zeros(len(fr.arm_pos), bool)
    vis = np.nonzero(fr.arm_visible & (fr.arm.vert_part != "arm"))[0]
    if len(vis):
        c = fr.arm_pos[vis].mean(0)
        r = np.linalg.norm(fr.arm_pos[vis] - c, axis=1).max()
        sd, _ = boxes.sdf(fr.arm_pos[vis], boxes.near(c, r))
        out[vis] = sd < 0
    return out


def summary(m):
    """Short text summary."""
    lines = [f"{m['gun']} {m['clip']} t={m['t']:.3f}s fitted={m['grip_fitted']} (px = 1/16 block)"]
    for side, h in m["hands"].items():
        fl = []
        for f, e in h["fingers"].items():
            fl.append(f"{f[:3]} pen {e['penetration_px']:.2f}({e['inside_vertices']}) gap {e['gap_px']:.2f}")
        lines.append(f" {side}: " + "; ".join(fl))
        p = h.get("palm", {})
        g = h.get("grip")
        w = h["wrist"]
        s = f"   palm pen {p.get('penetration_px', 0):.2f}({p.get('inside_vertices', 0)}) gap {p.get('gap_px', 0):.2f}"
        if g:
            s += (f"; grip {','.join(g['parts'][:3])}: palm-axis {g['palm_to_axis_px']:.2f} px, knuckles/axis "
                  f"{g['knuckle_line_vs_axis_deg']:.0f} deg, thumb opp {g.get('thumb_opposition_deg', float('nan')):.0f} deg")
        lines.append(s)
        lines.append(f"   wrist flex {w['flexion_deg']:.1f} dev {w['deviation_deg']:.1f} twist {w['twist_deg']:.1f}"
                     + (f" OUT OF RANGE {w['limit_violations_deg']}" if w["limit_violations_deg"] else ""))
    c = m["collisions"]
    lines.append(f" hand-hand min {c.get('hand_hand_min_px', float('nan')):.2f} px, arm-arm min "
                 f"{c.get('arm_arm_min_px', float('nan')):.2f} px, hand-in-gun vertices {c['hand_gun_inside_vertices']},"
                 f" forearm-in-gun {c['forearm_gun_inside_vertices']}")
    return "\n".join(lines)
