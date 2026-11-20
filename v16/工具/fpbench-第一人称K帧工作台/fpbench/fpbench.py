#!/usr/bin/env python3
"""fpbench: offline first-person TACZ gun + YSM arm workbench (render, sheet, metrics, ik, compare).

See README.md. Everything runs without Minecraft; the arm model comes from YSM's own importer (FpDump, cached).
"""
import argparse
import json
import os
import sys
import time

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fpb import compare, ik, metrics, render, scene, tacz_gun, ysm_arm  # noqa: E402

DEFAULT_MODEL = os.path.expanduser("~/ysm/pelica-fix/pelica_springs/pelica.vrm")


def _size(s):
    w, _, h = s.lower().partition("x")
    return int(w), int(h)


def common(p):
    p.add_argument("--model", default=DEFAULT_MODEL, help="YSM-importable model (.vrm/.zip/.ysm folder)")
    p.add_argument("--gun", default="tacz:ak47", help="gun id, e.g. tacz:ak47 (see `fpbench guns`)")
    p.add_argument("--pack", action="append", default=[], help="extra gun pack zip/folder (searched before the jar)")
    p.add_argument("--clip", default=None, help="gun clip: idle (default), draw, reload (=reload_tactical), "
                   "reload_empty, inspect, shoot, put_away, any clip name")
    p.add_argument("--t", type=float, default=0.0, help="time in the clip (s)")
    p.add_argument("--idle-t", type=float, default=0.0, help="phase of the looping movement 'idle' sway (s)")
    p.add_argument("--no-sway", action="store_true", help="leave out the movement 'idle' sway clip")
    p.add_argument("--no-bob", action="store_true", help="View Bobbing off: no camera animation on the hands")
    p.add_argument("--empty", action="store_true", help="no ammo in the magazine / barrel (hides bullet parts)")
    p.add_argument("--pose", action="append", default=[], help="override JSON (fpbench pose patch or an ik output "
                   "animation file); repeatable, later wins")
    p.add_argument("--fov", type=float, default=None, help="hand FOV (default: the gun display's model_fov or 70)")
    p.add_argument("--size", type=_size, default=(960, 540), help="WxH (default 960x540)")
    p.add_argument("--ysm-jar", default=None, help="YSM jar whose importer converts the model (default: the harness jar)")


class Bench:
    def __init__(self, a):
        self.a = a
        self.arm = ysm_arm.load(a.model, jar=a.ysm_jar, log=lambda m: print(m, file=sys.stderr))
        self.gun = tacz_gun.Gun(a.gun, a.pack, ammo=not a.empty, bullet_in_barrel=not a.empty)
        self.pose = scene.Pose()
        for f in a.pose:
            self.pose.merge(json.load(open(f)))

    def frame(self, t=None, idle_t=None, pose=None):
        a = self.a
        return scene.Frame(self.arm, self.gun, a.clip, a.t if t is None else t, pose=pose or self.pose,
                           idle_t=a.idle_t if idle_t is None else idle_t, sway=not a.no_sway, bob_view=not a.no_bob)

    def clip_length(self):
        if not self.a.clip or self.a.clip in ("idle", "static_idle"):
            c = self.gun.clips.get("idle")
        else:
            c = self.gun.clip(self.a.clip)
        return c.length if c else 0.0


def _write_json(path, data):
    if path == "-":
        json.dump(data, sys.stdout, indent=1)
        print()
    else:
        with open(path, "w") as fh:
            json.dump(data, fh, indent=1)


def _fp_image(b, fr, highlight=False):
    a = b.a
    hl = metrics.penetrating_vertices(fr) if highlight else None
    frame, _, _ = render.first_person(fr, *a.size, fov=a.fov, highlight=hl)
    return Image.fromarray(frame.color)


def cmd_render(a):
    b = Bench(a)
    t0 = time.time()
    fr = b.frame()
    im = _fp_image(b, fr, a.highlight)
    dt = time.time() - t0
    im.save(a.out)
    print(f"{a.out} ({dt:.2f} s)")
    if a.closeups:
        m = metrics.measure(fr)
        base, ext = os.path.splitext(a.out)
        hl = metrics.penetrating_vertices(fr) if a.highlight else None
        render.hand_closeups(fr, m, half_px=a.closeup_px, highlight=hl).save(base + "_hands" + ext)
        print(base + "_hands" + ext)


def _times(a, length):
    if a.times:
        return [float(x) for x in a.times.split(",")]
    t1 = length if a.t1 is None else a.t1
    return list(np.linspace(a.t0, t1, a.frames))


def cmd_sheet(a):
    b = Bench(a)
    ts = _times(a, b.clip_length())
    ims, labels = [], []
    t0 = time.time()
    w, h = a.size
    for t in ts:
        fr = b.frame(t=t) if a.clip not in (None, "idle") else b.frame(idle_t=t)
        im = _fp_image(b, fr, a.highlight)
        if a.crop:
            x0, y0, x1, y1 = [int(v) for v in a.crop.split(",")]
            im = im.crop((x0, y0, x1, y1))
        if a.thumb:
            im = im.resize((a.thumb, round(a.thumb * im.size[1] / im.size[0])), Image.LANCZOS)
        label = f"t={t:.3f}s"
        if a.metrics:
            m = metrics.measure(fr)
            c = m["collisions"]["hand_gun_inside_vertices"]
            label += " in-gun " + " ".join(f"{k[0]}{v}" for k, v in c.items())
        ims.append(im)
        labels.append(label)
    dt = (time.time() - t0) / max(1, len(ts))
    title = f"{a.gun} {a.clip or 'idle'}  {len(ts)} frames  {w}x{h} fov {a.fov or b.gun.model_fov:g}  model {os.path.basename(a.model)}"
    render.contact_sheet(ims, labels, cols=a.cols, title=title).save(a.out)
    print(f"{a.out} ({len(ts)} frames, {dt:.2f} s/frame)")


def cmd_metrics(a):
    b = Bench(a)
    if a.frames > 1 or a.times:
        out = []
        for t in _times(a, b.clip_length()):
            fr = b.frame(t=t) if a.clip not in (None, "idle") else b.frame(idle_t=t)
            m = metrics.measure(fr, a.grip_parts)
            out.append(m)
            print(metrics.summary(m))
    else:
        out = metrics.measure(b.frame(), a.grip_parts)
        print(metrics.summary(out))
    if a.json:
        _write_json(a.json, out)


def cmd_ik(a):
    b = Bench(a)
    fr = b.frame()
    before = metrics.measure(fr)
    fingers = None
    if a.fingers:
        fingers = {}
        for item in a.fingers.split(","):
            side, f = ("Left", item[4:]) if item.startswith("Left") else ("Right", item[5:])
            fingers.setdefault(side, []).append(f)
    t0 = time.time()
    sol = ik.solve(fr, sides=tuple(a.sides.split(",")), fingers=fingers, clearance_px=a.clearance)
    dt = time.time() - t0
    anim = ik.to_animation(sol, fr, a.clearance)
    patched = scene.Pose().merge(b.pose.to_json()).merge(anim)
    fr2 = b.frame(pose=patched)
    after = metrics.measure(fr2)
    anim["fpbench"]["metrics_before"] = before
    anim["fpbench"]["metrics_after"] = after
    _write_json(a.out, anim)
    print("BEFORE\n" + metrics.summary(before))
    print("AFTER\n" + metrics.summary(after))
    for side, fs in sol.items():
        for f, r in fs.items():
            note = " (kept current pose)" if r["kept_current"] else ""
            note += f" (inside gun when open: links {r['blocked_open_links']})" if r["blocked_open_links"] else ""
            print(f"{side}{f}: curl {r['curl_deg']} link sdf px {r['link_sdf_px']}{note}")
    print(f"{a.out} (ik {dt:.2f} s)")
    if a.render:
        base, ext = os.path.splitext(a.render)
        for tag, f in (("before", fr), ("after", fr2)):
            hl = metrics.penetrating_vertices(f)
            m = before if tag == "before" else after
            render.hand_closeups(f, m, highlight=hl).save(f"{base}_{tag}{ext}")
            print(f"{base}_{tag}{ext}")


def cmd_compare(a):
    b = Bench(a)
    p = {"t": a.t, "idle_t": a.idle_t}
    if a.fit:
        ranges = {}
        if a.clip not in (None, "idle"):
            ranges["t"] = (0.0, b.clip_length(), 0.05, 0.01)
        if not a.no_sway and "idle" in b.gun.clips:
            ranges["idle_t"] = (0.0, b.gun.clips["idle"].length, 0.25, 0.03)
        fitted, cost = compare.fit(lambda t=a.t, idle_t=a.idle_t: b.frame(t=t, idle_t=idle_t), a.shot, ranges,
                                   fov=a.fov)
        p.update(fitted)
        print(f"fit: {fitted} (outline cost {cost:.2f})")
    res, im = compare.compare(b.frame(t=p["t"], idle_t=p["idle_t"]), a.shot, fov=a.fov)
    res["params"] = p
    im.save(a.out)
    for name, g in res["groups"].items():
        print(f"{name}: offset {g['offset_px']} px, median {g['median_px']} px, p90 {g['p90_px']} px"
              + (f", hand-on-grip residual {g['hand_on_grip_residual_px']} px" if "hand_on_grip_residual_px" in g else ""))
    print(f"silhouette IoU {res['silhouette_iou']}  overlay {a.out}")
    if a.json:
        _write_json(a.json, res)


def _models(a):
    """(model id, file): ``--models`` a folder of YSM model folders (<id>/<id>.vrm|.zip, else the first .vrm/.zip)."""
    out = []
    for d in sorted(os.listdir(a.models)):
        p = os.path.join(a.models, d)
        if not os.path.isdir(p) or (a.only and d not in a.only.split(",")):
            continue
        files = [f for f in sorted(os.listdir(p)) if f.endswith((".vrm", ".zip"))]
        pick = next((f for f in files if os.path.splitext(f)[0] == d), files[0] if files else None)
        if pick:
            out.append((d, os.path.join(p, pick)))
    return out


def _profile_job(job):
    mid, path, gun, a = job
    from fpb import profile
    try:
        entry, stats = profile.solve_model(path, gun, a.pack, a.clips.split(","), a.clearance, a.fps, a.tolerance,
                                           not a.no_eval, a.ysm_jar)
        return mid, gun, entry, stats, None
    except Exception as e:  # report and continue with the other models
        return mid, gun, None, None, f"{type(e).__name__}: {e}"


def cmd_profile(a):
    from multiprocessing import Pool
    from fpb import profile
    models = _models(a)
    guns = a.guns.split(",")
    for _, path in models:  # import serially (FpDump is a JVM each; cached afterwards)
        ysm_arm.ensure_dump(path, jar=a.ysm_jar, log=lambda m: print(m, file=sys.stderr))
    jobs = [(mid, path, g, a) for g in guns for mid, path in models]
    t0 = time.time()
    res = {g: {} for g in guns}
    report = {}
    with Pool(a.workers) as pool:
        for mid, g, entry, stats, err in pool.imap_unordered(_profile_job, jobs):
            if err:
                print(f"{g} {mid}: FAILED {err}")
                continue
            res[g][mid] = entry
            report.setdefault(g, {})[mid] = stats
            print(f"{g} {mid}: {stats['solve_s']} s solve, {stats['total_s']} s total"
                  + (", per-model grip" if stats["per_model_grip"] else ""), flush=True)
    for g in guns:
        if a.fallback in res[g]:
            res[g]["*"] = res[g][a.fallback]
        ns, _, p = g.partition(":")
        os.makedirs(os.path.join(a.out, ns), exist_ok=True)
        f = os.path.join(a.out, ns, p + ".json")
        profile.write(f, profile.gun_file(g, dict(sorted(res[g].items())), a.pack, a.clips.split(",")))
        print(f)
    _write_json(os.path.join(a.out, "report.json"), report)
    if not a.no_eval:
        print("gun model clip: max finger/palm penetration px (inside vertices) before -> after; max gap px after")
        for g, rows in report.items():
            for mid, s in sorted(rows.items()):
                for c in ["idle"] + a.clips.split(","):
                    b, af = s[c]["before"], s[c]["after"]
                    print(f"{g} {mid} {c}: {b['pen_px']:.2f} ({b['inside']}) -> {af['pen_px']:.2f} ({af['inside']});"
                          f" gap {af['gap_px']:.2f}")
    print(f"total {time.time() - t0:.0f} s, {a.workers} workers")


def cmd_guns(a):
    for g in tacz_gun.list_guns(a.pack):
        print(g)


def cmd_clips(a):
    g = tacz_gun.Gun(a.gun, a.pack)
    for n, c in sorted(g.clips.items()):
        print(f"{n}\t{c.length:.3f}s")


def main(argv=None):
    ap = argparse.ArgumentParser(prog="fpbench", description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("render", help="first-person PNG (+ hand close-ups)")
    common(p)
    p.add_argument("--out", required=True)
    p.add_argument("--closeups", action="store_true", help="also <out>_hands.png: orthographic views of each hand")
    p.add_argument("--closeup-px", type=float, default=5.0, help="half width of a close-up in model px")
    p.add_argument("--highlight", action="store_true", help="paint hand vertices inside the gun red")
    p.set_defaults(fn=cmd_render)

    def timeline(p):
        p.add_argument("--frames", type=int, default=1, help="evenly spaced frames over [--t0, --t1]")
        p.add_argument("--t0", type=float, default=0.0)
        p.add_argument("--t1", type=float, default=None, help="default: clip length")
        p.add_argument("--times", default=None, help="explicit comma-separated times (overrides --frames)")

    p = sub.add_parser("sheet", help="timeline contact sheet of a clip")
    common(p)
    timeline(p)
    p.set_defaults(frames=12)
    p.add_argument("--out", required=True)
    p.add_argument("--cols", type=int, default=4)
    p.add_argument("--thumb", type=int, default=480, help="tile width (px); 0 = full size")
    p.add_argument("--crop", default=None, help="x0,y0,x1,y1 crop of each frame before scaling")
    p.add_argument("--metrics", action="store_true", help="label tiles with hand-in-gun vertex counts")
    p.add_argument("--highlight", action="store_true")
    p.set_defaults(fn=cmd_sheet)

    p = sub.add_parser("metrics", help="contact metrics JSON + text summary (one frame or a timeline)")
    common(p)
    timeline(p)
    p.add_argument("--json", default=None, help="write JSON here ('-' = stdout)")
    p.add_argument("--grip-parts", nargs="*", default=None, help="gun part names forming the grip (default: auto)")
    p.set_defaults(fn=cmd_metrics)

    p = sub.add_parser("ik", help="grip IK: finger curls touching the gun; writes a YSM/Bedrock animation JSON")
    common(p)
    p.add_argument("--out", required=True, help="animation JSON (parallel2 finger bones); feed back with --pose")
    p.add_argument("--clearance", type=float, default=0.1, help="finger-to-gun clearance (model px)")
    p.add_argument("--sides", default="Left,Right")
    p.add_argument("--fingers", default=None, help="e.g. LeftIndex,LeftMiddle,RightThumb (default: all but the "
                   "right index on the trigger)")
    p.add_argument("--render", default=None, help="also write <render>_before/_after.png hand close-ups")
    p.set_defaults(fn=cmd_ik)

    p = sub.add_parser("compare", help="residuals vs an in-game screenshot (sky background, HUD on)")
    common(p)
    p.add_argument("--shot", required=True)
    p.add_argument("--fit", action="store_true", help="fit the unknown clip time / sway phase first")
    p.add_argument("--out", required=True, help="overlay PNG (render outline red, game outline green)")
    p.add_argument("--json", default=None)
    p.set_defaults(fn=cmd_compare)

    p = sub.add_parser("profile", help="solve per-gun hand profiles (config/ysm_tacz_fp/<ns>/<path>.json) per model")
    p.add_argument("--guns", default="tacz:m4a1,tacz:ak47")
    p.add_argument("--models", required=True, help="folder of YSM model folders (<id>/<id>.vrm); id = folder name")
    p.add_argument("--only", default=None, help="comma-separated model ids to solve (default: all)")
    p.add_argument("--out", required=True, help="output folder: <out>/<ns>/<path>.json and report.json")
    p.add_argument("--fallback", default="pelica", help="model id whose solution becomes the '*' entry")
    p.add_argument("--clips", default="reload_tactical", help="TACZ clips to keyframe (comma-separated)")
    p.add_argument("--fps", type=float, default=15.0, help="IK sampling rate over a clip")
    p.add_argument("--tolerance", type=float, default=1.0, help="keyframe simplification tolerance (deg)")
    p.add_argument("--clearance", type=float, default=0.1, help="finger-to-gun clearance (model px)")
    p.add_argument("--workers", type=int, default=3, help="parallel model x gun jobs (~0.4 GB each)")
    p.add_argument("--no-eval", action="store_true", help="skip the before/after metrics")
    p.add_argument("--pack", action="append", default=[])
    p.add_argument("--ysm-jar", default=None)
    p.set_defaults(fn=cmd_profile)

    p = sub.add_parser("guns", help="list gun ids")
    p.add_argument("--pack", action="append", default=[])
    p.set_defaults(fn=cmd_guns)

    p = sub.add_parser("clips", help="list a gun's clips and lengths")
    p.add_argument("--gun", default="tacz:ak47")
    p.add_argument("--pack", action="append", default=[])
    p.set_defaults(fn=cmd_clips)

    a = ap.parse_args(argv)
    a.fn(a)


if __name__ == "__main__":
    main()
