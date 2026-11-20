#!/usr/bin/env python3
"""Hem diagnosis (scratch tool, SkirtCloth2): replays the harness stream and attributes the garment's edge stretch.

Usage: YSM_TEST=<ysm-test> diag.py <dump dir> <main.animation.json> <out.json>   (also writes <out>.npz)

Per measured frame: the garment's linear-blend-skinned positions from the replay's bone poses (no push-out) next to
the drawn positions, so stretch the bones make (springs, weights) and stretch the push-out adds are told apart; the
push-out per vertex and its jump across mesh edges; the gaps between neighbouring hem chains (joint pivots per level,
chains ordered around the body) as a share of their rest gaps.
"""
import json
import os
import subprocess
import sys
import tempfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import harness  # noqa: E402


def main():
    dump, animation, out_json = sys.argv[1], sys.argv[2], sys.argv[3]
    ytest = os.environ.get("YSM_TEST", harness.YSM_TEST)
    meta, mesh = harness.load_dump(dump)
    cls = harness.classify(meta, mesh)
    gar = harness.garment(meta, mesh, cls)
    names, parent, piv = cls["names"], cls["parent"], cls["pivot"]
    rest_rot = np.array([b["rotate"] for b in meta["bones"]])
    anim = harness.Anim(animation, names, rest_rot)
    frames, labels = harness.build_stream(meta, anim, 60)
    measured = [labels[i] for i, f in enumerate(frames) if f[0]]
    nb, nv = len(meta["bones"]), meta["vertices"]
    record = nb * 16 + nv * 3 + 2
    gv, ge, grest = gar["verts"], gar["edges"], gar["rest"]
    W = mesh["w"][gv].astype(np.float64)
    J = np.clip(mesh["j"][gv], 0, nb - 1)
    P = mesh["pos"][gv].astype(np.float64)
    gindex = -np.ones(nv, int)
    gindex[gv] = np.arange(len(gv))
    ea, eb = gindex[ge[:, 0]], gindex[ge[:, 1]]
    cm = meta["height_scale"] * 100
    kids = {}
    for b in range(nb):
        if parent[b] >= 0:
            kids.setdefault(parent[b], []).append(b)
    chain_list = sorted(gar["hem"].values(), key=lambda h: np.arctan2(piv[h["root"]][0], -piv[h["root"]][2]))
    # Gap points per chain: the first joint's pivot, then every joint's tail (level k tail = gap level k + 1).
    jtail = {int(j[0]): np.array(j[6:9], float) for j in meta.get("joint_info", [])}
    levels = []
    for h in chain_list:
        members = set(h["bones"])
        seq, b = [], h["root"]
        while b is not None:
            seq.append(b)
            nxt = [k for k in kids.get(b, []) if k in members and (not jtail or k in jtail)]
            b = nxt[0] if nxt else None
        levels.append(seq)
    print("chains around", [[names[b] for b in s] for s in levels])
    rows = []
    with tempfile.TemporaryDirectory() as tmp:
        poses_path, outp = os.path.join(tmp, "poses.bin"), os.path.join(tmp, "out.fifo")
        harness.write_stream(poses_path, frames, nb)
        os.mkfifo(outp)
        env = dict(os.environ, YSM_ANTICLIP_MODEL=os.path.join(dump, "geo.pb"), YSM_ANTICLIP_POSES=poses_path,
                   YSM_ANTICLIP_OUT=outp)
        proc = subprocess.Popen([ytest, "--gtest_filter=AntiClipTest.Replay"], env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT)
        with open(outp, "rb") as f:
            for label in measured:
                buf = f.read(record * 4)
                if len(buf) < record * 4:
                    break
                data = np.frombuffer(buf, "<f4")
                M = np.transpose(data[:nb * 16].reshape(nb, 4, 4).astype(np.float64), (0, 2, 1))  # row-major
                pos = data[nb * 16:nb * 16 + nv * 3].reshape(nv, 3).astype(np.float64)
                lbs = np.zeros_like(P)
                for k in range(4):
                    Mk = M[J[:, k]]
                    lbs += W[:, k:k + 1] * (np.einsum("vij,vj->vi", Mk[:, :3, :3], P) + Mk[:, :3, 3])
                drawn = pos[gv]
                push = drawn - lbs
                pts = []
                for s in levels:
                    row = [M[s[0]][:3, :3] @ piv[s[0]] + M[s[0]][:3, 3]]
                    for b in s:
                        if b in jtail:
                            row.append(M[b][:3, :3] @ jtail[b] + M[b][:3, 3])
                        else:
                            ch = kids.get(b, [])
                            if ch:
                                row.append(M[ch[0]][:3, :3] @ piv[ch[0]] + M[ch[0]][:3, 3])
                    pts.append(row)
                rows.append(dict(label=label,
                                 rl=(np.linalg.norm(lbs[ea] - lbs[eb], axis=1) / grest).astype(np.float32),
                                 rd=(np.linalg.norm(drawn[ea] - drawn[eb], axis=1) / grest).astype(np.float32),
                                 push=np.linalg.norm(push, axis=1).astype(np.float32),
                                 jump=np.linalg.norm(push[ea] - push[eb], axis=1).astype(np.float32), pts=pts))
        proc.wait()

    def gaps(r):
        n = len(levels)
        return np.array([[np.linalg.norm(np.array(r["pts"][i][k]) - np.array(r["pts"][(i + 1) % n][k]))
                          for k in range(min(len(r["pts"][i]), len(r["pts"][(i + 1) % n])))] for i in range(n)])

    g0 = gaps(rows[1])
    print("rest gaps (cm) per neighbour pair / level\n", np.round(g0 * cm, 1))
    meas = rows[2:]
    labs = [r["label"] for r in meas]
    RL = np.stack([r["rl"] for r in meas])
    RD = np.stack([r["rd"] for r in meas])
    PU = np.stack([r["push"] for r in meas]) * cm
    JU = np.stack([r["jump"] for r in meas]) * cm
    GR = np.stack([gaps(r) / np.maximum(g0, 1e-6) for r in meas])

    def q(x, p):
        return float(np.quantile(x, p))

    summary = {
        "tear_drawn": {"p99": q(RD, .99), "over125": float((RD > 1.25).mean()), "over15": float((RD > 1.5).mean())},
        "tear_lbs": {"p99": q(RL, .99), "over125": float((RL > 1.25).mean()), "over15": float((RL > 1.5).mean())},
        "push_cm": {"share_pushed>1mm": float((PU > 0.1).mean()), "p99": q(PU, .99), "max": float(PU.max())},
        "push_jump_cm": {"p99": q(JU, .99), "p999": q(JU, .999), "max": float(JU.max())},
        "edges_over125_due_to_push": float(((RD > 1.25) & (RL <= 1.25)).mean()),
        "edges_over125_lbs_but_not_drawn": float(((RL > 1.25) & (RD <= 1.25)).mean()),
        "rest_gaps_cm": np.round(g0 * cm, 2).tolist(),
        "gap_ratio_levels": {"p99": [q(GR[:, :, k], .99) for k in range(GR.shape[2])],
                             "max": [float(GR[:, :, k].max()) for k in range(GR.shape[2])],
                             "min": [float(GR[:, :, k].min()) for k in range(GR.shape[2])]},
    }
    per = {}
    for lab in dict.fromkeys(labs):
        idx = [i for i, x in enumerate(labs) if x == lab]
        per[lab] = dict(drawn125=round(float((RD[idx] > 1.25).mean()) * 100, 2),
                        lbs125=round(float((RL[idx] > 1.25).mean()) * 100, 2), push_p99=round(q(PU[idx], .99), 2),
                        jump_p99=round(q(JU[idx], .99), 2),
                        gapmax=[round(float(GR[idx][:, :, k].max()), 2) for k in range(GR.shape[2])],
                        gap99=[round(q(GR[idx][:, :, k], .99), 2) for k in range(GR.shape[2])],
                        gapmin=[round(float(GR[idx][:, :, k].min()), 2) for k in range(GR.shape[2])])
    summary["per_clip"] = per
    print(json.dumps(summary, indent=1))
    json.dump(summary, open(out_json, "w"), indent=1)
    # Per-frame edge ratios are large (frames x edges); only with DIAG_FULL=1.
    full = dict(RL=RL.astype(np.float16), RD=RD.astype(np.float16)) if os.environ.get("DIAG_FULL") else {}
    np.savez_compressed(out_json.replace(".json", ".npz"), GR=GR.astype(np.float32), labs=np.array(labs), **full)


if __name__ == "__main__":
    main()
