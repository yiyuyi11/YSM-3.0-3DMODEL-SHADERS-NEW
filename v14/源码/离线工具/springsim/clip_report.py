#!/usr/bin/env python3
"""Where the skirt and hem go into the legs, per clip, chain and leg part.

Usage: clip_report.py <GenAnim dir with main.json and skel.json> <model.vrm> [fps]

Runs the solver port (tools/springsim/sim.py) as the shipped test jar does: native substeps (interp=True) and the
matched step timing, at a steady frame rate (default 60). Each drawn skirt/hem vertex is measured against the posed leg
mesh surface: the thigh (hip to knee) and the calf (knee to ankle), each as a radius table over (distance along the
bone, angle about it) built from the rest mesh. A vertex is inside when it is nearer the bone axis than the leg surface
in its direction. Reported per clip, per chain (the spring chain whose joint carries most of the vertex's weight) and per
leg part: share of frames with any vertex more than 1 cm inside, the deepest point (cm) and the RMS depth of the
vertices that are inside (cm)."""
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim  # noqa: E402

CLIPS = {"idle": 0.0, "walk": 4.3, "run": 5.6, "sneak": 1.3}
PARTS = ("thigh upper", "thigh lower", "knee", "calf")


def segment_table(points, a, b):
    t, ang, r = sim.Sim.cyl(points, a, b)
    length = np.linalg.norm(b - a)
    tb = np.clip((t / length * 20).astype(int), 0, 19)
    ab = ((ang + np.pi) / (np.pi / 6)).astype(int) % 12
    table = np.zeros((20, 12))
    np.maximum.at(table, (tb, ab), r)
    for _ in range(3):
        filled = np.maximum.reduce([np.roll(table, 1, 1), np.roll(table, -1, 1), table, np.roll(table, 1, 0),
                                    np.roll(table, -1, 0)])
        table = np.where(table > 0, table, filled)
    return table, length


def evaluate(gen, vrm, fps=60, clips=None, verbose=True):
    """Per clip: (share of frames with any vertex > 1 cm inside, deepest cm, RMS cm of inside vertices)."""
    clips = clips or CLIPS
    anims = json.load(open(os.path.join(gen, "main.json")))["animations"]
    skel = json.load(open(os.path.join(gen, "skel.json")))
    hip_y = next(b["pivot"][1] for b in skel if b["name"] == "LeftLeg")
    s = sim.Sim(vrm, skel, None)
    s.unit = hip_y / s.legs["L"][1][1]
    doc, blob = sim.fp.load_glb(vrm)
    nodes = doc["nodes"]
    world = sim.fp.world_matrices(nodes)
    humanoid = doc["extensions"]["VRMC_vrm"]["humanoid"]["humanBones"]
    # rest mesh, non-skirt vertices weighted to thigh / calf joints
    skin = np.array(doc["skins"][0]["joints"])
    ps, js_, ws = [], [], []
    for n in nodes:
        if "mesh" not in n:
            continue
        for prim in doc["meshes"][n["mesh"]]["primitives"]:
            a = prim["attributes"]
            ps.append(sim.fp.read_accessor(doc, blob, a["POSITION"]).astype(float))
            js_.append(skin[sim.fp.read_accessor(doc, blob, a["JOINTS_0"]).astype(int)])
            ws.append(sim.fp.read_accessor(doc, blob, a["WEIGHTS_0"]).astype(float))
    p, j, w = np.concatenate(ps), np.concatenate(js_), np.concatenate(ws)
    names = s.names
    chain_node = s.chain_nodes | {c["nodes"][-1] for c in s.chains}
    cloth = np.array([any(names[x].startswith(pre) for pre in ("qunbai", "yibai", "clothes_ribbon"))
                      for x in j[np.arange(len(j)), w.argmax(1)]])
    segments = {}
    for side, upper in (("L", "Left"), ("R", "Right")):
        hip = world[humanoid[f"{upper.lower()}UpperLeg"]["node"]][:3, 3]
        knee = world[humanoid[f"{upper.lower()}LowerLeg"]["node"]][:3, 3]
        foot = world[humanoid[f"{upper.lower()}Foot"]["node"]][:3, 3]
        thigh_j = [i for i, n in enumerate(names) if n.startswith(f"Bip001_{side}_Thigh") or
                   n.startswith(f"Bip001_{side}Thigh")]
        calf_j = [i for i, n in enumerate(names) if n.startswith(f"Bip001_{side}_Calf") or
                  n.startswith(f"Bip001_{side}Calf")]
        sel_t = ((np.isin(j, thigh_j) * w).sum(1) > 0.5) & ~cloth
        sel_c = ((np.isin(j, calf_j) * w).sum(1) > 0.5) & ~cloth
        segments[side] = [(f"{upper}Leg", hip, knee) + segment_table(p[sel_t], hip, knee),
                          (f"{upper}LowerLeg", knee, foot) + segment_table(p[sel_c], knee, foot)]
    # chain of each skirt vertex: the spring chain owning its heaviest joint
    owner = {}
    for c in s.chains:
        root = names[c["nodes"][0]]
        label = next((pre for pre, *_ in sim.fp.SPRINGS if root.startswith(pre)), root)
        for n in c["nodes"]:
            owner[n] = label
    vlabel = np.array([owner.get(x, "waist") for x in s.vj[np.arange(len(s.vj)), s.vw.argmax(1)]])
    labels = sorted(set(vlabel))
    summary = {}
    for clip_name, speed in clips.items():
        if clip_name not in anims:
            continue
        clip = sim.Clip(anims[clip_name], skel)
        res = s.run(clip, fps, speed, seconds=3.2, record_from=1.2, interp=True, timing="matched")
        stats = {}
        frames_bad = 0
        all_depths = []
        deepest = 0.0
        for t, _, verts, _, _ in res:
            get = s.pose(clip, t)
            depth = np.zeros(len(verts))
            part = np.full(len(verts), -1)
            for side in ("L", "R"):
                for k, (bone, a, b, table, length) in enumerate(segments[side]):
                    m = get(bone)
                    local = (verts - m[:3, 3]) @ m[:3, :3]
                    tt, ang, dist = sim.Sim.cyl(local, a, b)
                    frac = tt / length
                    inside = (frac >= (0.15 if k == 0 else 0.0)) & (frac < 1.0)
                    tb = np.clip((frac * 20).astype(int), 0, 19)
                    abin = ((ang + np.pi) / (np.pi / 6)).astype(int) % 12
                    d = np.where(inside, table[tb, abin] - dist, -1.0)
                    if k == 0:
                        pid = np.where(frac < 0.55, 0, np.where(frac < 0.9, 1, 2))
                    else:
                        pid = np.where(frac < 0.15, 2, 3)
                    better = d > depth
                    depth = np.where(better, d, depth)
                    part = np.where(better, pid, part)
            inside_all = depth[depth > 0.0]
            all_depths.extend(inside_all.tolist())
            if inside_all.size and inside_all.max() > 0.01:
                frames_bad += 1
            deepest = max(deepest, float(inside_all.max()) if inside_all.size else 0.0)
            for label in labels:
                for pi, pname in enumerate(PARTS):
                    sel = (vlabel == label) & (part == pi) & (depth > 0.0)
                    key = (label, pname)
                    st = stats.setdefault(key, [0, 0.0, [], 0])
                    st[3] += 1
                    if sel.any():
                        dd = depth[sel]
                        if dd.max() > 0.01:
                            st[0] += 1
                        st[1] = max(st[1], float(dd.max()))
                        st[2].extend(dd.tolist())
        rms_all = float(np.sqrt(np.mean(np.square(all_depths)))) if all_depths else 0.0
        summary[clip_name] = (frames_bad / len(res), deepest * 100, rms_all * 100)
        if not verbose:
            continue
        print(f"== {clip_name} ({fps} fps, {speed} m/s): frames>1cm {100 * frames_bad / len(res):5.1f}%  max "
              f"{deepest * 100:5.1f} cm  rms {rms_all * 100:4.1f} cm")
        for (label, pname), (bad, mx, ds, n) in sorted(stats.items()):
            if mx <= 0.005:
                continue
            rms = float(np.sqrt(np.mean(np.square(ds)))) if ds else 0.0
            print(f"  {label:18s} {pname:12s} frames>1cm {100 * bad / n:5.1f}%  max {mx * 100:5.1f} cm  "
                  f"rms {rms * 100:4.1f} cm")
    return summary


if __name__ == "__main__":
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    evaluate(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 60)
