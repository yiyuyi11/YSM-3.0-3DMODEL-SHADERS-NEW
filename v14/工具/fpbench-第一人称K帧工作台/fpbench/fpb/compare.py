"""Compare a rendered Frame with an in-game first-person screenshot of the same gun and model.

The in-game shot must have the sky behind the hands (e.g. pitch -45 on a clear day, HUD on: hands are not drawn with
F1). Foreground = not sky. For each group (gun, left arm, right arm) the rendered outline pixels that border the
background are matched to the nearest in-game outline pixel (ICP translation, then residual distances). HUD areas
(hotbar, chat, ammo counter, crosshair, the YSM model preview) are excluded.

Hand-on-grip residual = |offset(hand) - offset(gun)|: how far the hand sits off the gun compared with the game,
independent of a common placement error.
"""
import math

import numpy as np
from PIL import Image, ImageDraw

from . import render

GROUPS = {1: "gun", 2: "Left", 3: "Right"}


def hud_mask(w, h, gui_scale=2):
    """True where the vanilla/TACZ/YSM HUD may cover the image (guiScale 2 layout)."""
    m = np.zeros((h, w), bool)
    g = gui_scale
    m[h - 23 * g:, w // 2 - 92 * g:w // 2 + 92 * g] = True  # hotbar
    m[h - 52 * g:h - 38 * g, :330 * g] = True  # chat line(s) above the hotbar
    m[h - 50 * g:h - 20 * g, w - 125 * g:] = True  # TACZ ammo counter
    m[h // 2 - 6:h // 2 + 6, w // 2 - 6:w // 2 + 6] = True  # crosshair
    m[:100 * g, :60 * g] = True  # YSM paper-doll preview
    return m


def sky_mask(rgb):
    """Background pixels of an in-game shot: blue sky (b well above r)."""
    r, g, b = (rgb[..., i].astype(int) for i in range(3))
    return (b - r > 45) & (b > 170) & (b >= g)


def _outline(fg, inner):
    """Pixels of ``inner`` with a 4-neighbour outside ``fg`` (image border excluded)."""
    bg = ~fg
    nb = np.zeros_like(fg)
    nb[1:] |= bg[:-1]
    nb[:-1] |= bg[1:]
    nb[:, 1:] |= bg[:, :-1]
    nb[:, :-1] |= bg[:, 1:]
    e = inner & nb
    e[0, :] = e[-1, :] = e[:, 0] = e[:, -1] = False
    return e


def _nearest(a, b, chunk=2048):
    """For points a (n,2): vector to the nearest of b (m,2)."""
    out = np.empty_like(a)
    bb = (b ** 2).sum(1)
    for s in range(0, len(a), chunk):
        x = a[s:s + chunk]
        d2 = (x ** 2).sum(1)[:, None] + bb[None] - 2 * x @ b.T
        out[s:s + chunk] = b[d2.argmin(1)] - x
    return out


def _icp_shift(a, b, iters=8):
    """Translation moving rendered outline a onto in-game outline b, and the residual distances after it."""
    t = np.zeros(2)
    for _ in range(iters):
        v = _nearest(a + t, b)
        d = np.linalg.norm(v, axis=1)
        keep = d <= max(3.0, np.percentile(d, 80))  # ignore outline parts the other image lacks (occlusion)
        step = v[keep].mean(0)
        t += step
        if np.linalg.norm(step) < 0.01:
            break
    d0 = np.linalg.norm(_nearest(a, b), axis=1)
    d1 = np.linalg.norm(_nearest(a + t, b), axis=1)
    return t, d0, d1


def compare(fr, shot, fov=None, gui_scale=2):
    """Metrics dict and a diagnostic overlay image (rendered outline red, in-game outline green)."""
    game = np.asarray(Image.open(shot).convert("RGB"))
    h, w = game.shape[:2]
    frame, layers, _ = render.first_person(fr, w, h, fov)
    kind = layers.kind(frame.ids)
    hud = hud_mask(w, h, gui_scale)
    gfg = ~sky_mask(game)
    rfg = kind > 0
    gedge = _outline(gfg, gfg) & ~hud
    gpts = np.argwhere(gedge)[:, ::-1].astype(float)
    valid = ~hud
    inter = (gfg & rfg & valid).sum()
    union = ((gfg | rfg) & valid).sum()
    res = {"shot": str(shot), "size": [w, h], "silhouette_iou": round(float(inter / max(union, 1)), 4), "groups": {}}
    shifts = {}
    for k, name in GROUPS.items():
        e = _outline(rfg, kind == k) & ~hud
        pts = np.argwhere(e)[:, ::-1].astype(float)
        if len(pts) < 20 or len(gpts) == 0:
            continue
        t, d0, d1 = _icp_shift(pts, gpts)
        shifts[name] = t
        res["groups"][name] = {"outline_px": int(len(pts)), "offset_px": [round(float(x), 2) for x in t],
                               "median_px": round(float(np.median(d0)), 2), "p90_px": round(float(np.percentile(d0, 90)), 2),
                               "median_after_offset_px": round(float(np.median(d1)), 2)}
    for side in ("Left", "Right"):
        if side in shifts and "gun" in shifts:
            res["groups"][side]["hand_on_grip_residual_px"] = round(float(np.linalg.norm(shifts[side] - shifts["gun"])), 2)
    over = (0.5 * game + 0.5 * frame.color).astype(np.uint8)
    over[gedge] = (40, 230, 60)
    over[_outline(rfg, rfg) & ~hud] = (255, 40, 40)
    im = Image.fromarray(over)
    d = ImageDraw.Draw(im)
    y = 4
    for name, g in res["groups"].items():
        d.text((130, y), f"{name}: offset {g['offset_px']} median {g['median_px']} px"
               + (f" grip residual {g['hand_on_grip_residual_px']} px" if "hand_on_grip_residual_px" in g else ""),
               fill=(0, 0, 0))
        y += 12
    return res, im


def _outline_cost(make_frame, shot, fov=None):
    """cost(**params): symmetric mean outline distance (px, capped) between a render and the shot."""
    game = np.asarray(Image.open(shot).convert("RGB"))
    h, w = game.shape[:2]
    hud = hud_mask(w, h)
    gfg = ~sky_mask(game)
    gpts = np.argwhere(_outline(gfg, gfg) & ~hud)[:, ::-1].astype(float)
    g = gpts[:: max(1, len(gpts) // 1500)]

    def cost(**p):
        frame, _, _ = render.first_person(make_frame(**p), w, h, fov)
        rfg = frame.ids >= 0
        pts = np.argwhere(_outline(rfg, rfg) & ~hud)[:, ::-1].astype(float)
        if len(pts) < 20 or len(gpts) == 0:
            return math.inf
        pts = pts[:: max(1, len(pts) // 1500)]
        d = np.linalg.norm(_nearest(pts, gpts), axis=1)
        d2 = np.linalg.norm(_nearest(g, pts), axis=1)  # in-game outline the render lacks counts too
        return float(np.mean(np.minimum(d, 50)) + np.mean(np.minimum(d2, 50)))
    return cost


def fit(make_frame, shot, ranges, passes=2, fov=None):
    """Unknown in-game timing: coordinate search of make_frame(**params) against the shot.

    ranges: {name: (lo, hi, coarse_step, fine_step)}, e.g. {"t": (0, 2.5, 0.05, 0.01), "idle_t": (0, 7.27, 0.2, 0.02)}.
    Each pass scans every parameter coarsely (later passes: +-2 coarse steps around the current value), then refines
    with the fine step. Returns (params, cost)."""
    cost = _outline_cost(make_frame, shot, fov)
    p = {k: r[0] for k, r in ranges.items()}
    best = math.inf

    def scan(k, a, b, s):
        return min((cost(**{**p, k: float(v)}), float(v)) for v in np.arange(a, b + 1e-9, s))
    for n in range(passes):
        for k, (lo, hi, step, fine) in ranges.items():
            a, b = (lo, hi) if n == 0 else (max(lo, p[k] - 2 * step), min(hi, p[k] + 2 * step))
            best, p[k] = scan(k, a, b, step if n == 0 else step / 2)
            best, p[k] = scan(k, max(lo, p[k] - step), min(hi, p[k] + step), fine)
    return p, best
