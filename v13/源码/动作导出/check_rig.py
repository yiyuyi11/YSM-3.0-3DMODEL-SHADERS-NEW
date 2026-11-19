#!/usr/bin/env python3
"""Checks a .ysmrig.json (format 1, ~/ysm/handoff/ysmrig-format.md) and optionally renders its rigs.

Usage: check_rig.py <file.ysmrig.json> [--render out_prefix] [--pose key]

Checks: every base64 stream decodes to the length its counts imply, joints index boneNames, weights sum to 1, draw
groups tile the index buffer, every group's atlas uvs lie in its material's rect, atlas and specular PNGs decode,
geo bones match boneNames, animation sets parse. --render writes <prefix>_main.png and <prefix>_arm.png: the meshes
skinned into the rest pose (geo rest rotations, Blockbench's Bedrock reading: pivot x and X/Y rotation negated),
front view, flat shaded with the atlas colour. --pose CLIP additionally samples the first keyframe of a main clip's
plain numeric rotations (Molang strings are skipped) on top of the rest pose.
"""
import argparse
import base64
import io
import json
import math
import sys

import numpy as np
from PIL import Image, ImageDraw


def arr(b64, dtype, width):
    data = np.frombuffer(base64.b64decode(b64), dtype=dtype)
    return data.reshape(-1, width) if width > 1 else data


def euler_zyx(rx, ry, rz):
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    X = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Y = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Z = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Z @ Y @ X


def world_matrices(geo, keys=None):
    """Scene-space bone matrices (pixels): T(p) R(rest + key) T(-p), chained to the parent."""
    bones = geo["minecraft:geometry"][0]["bones"]
    index = {b["name"]: i for i, b in enumerate(bones)}
    W = [None] * len(bones)

    def solve(i):
        if W[i] is not None:
            return W[i]
        b = bones[i]
        p = np.array([-b["pivot"][0], b["pivot"][1], b["pivot"][2]], float)
        r = np.radians([-b["rotation"][0], -b["rotation"][1], b["rotation"][2]])
        k = (keys or {}).get(b["name"])
        if k is not None:
            r = r + np.radians([-k[0], -k[1], k[2]])
        L = np.eye(4)
        L[:3, :3] = euler_zyx(*r)
        L[:3, 3] = p - L[:3, :3] @ p
        W[i] = L if "parent" not in b else solve(index[b["parent"]]) @ L
        return W[i]

    for i in range(len(bones)):
        solve(i)
    return np.array(W), [b["name"] for b in bones]


def check(rig_file):
    rig = json.load(open(rig_file))
    errors = []
    assert rig["format"] == 1, rig["format"]
    textures = []
    for t in rig["textures"]:
        atlas = Image.open(io.BytesIO(base64.b64decode(t["png"])))
        spec = Image.open(io.BytesIO(base64.b64decode(t["specularPng"])))
        if atlas.size != (t["width"], t["height"]) or spec.size != (t["specularWidth"], t["specularHeight"]):
            errors.append(f"texture {t['name']}: size mismatch")
        textures.append(np.asarray(atlas.convert("RGBA")))
    meshes = {}
    for name, r in rig["rigs"].items():
        m = r["mesh"]
        n = m["vertexCount"]
        pos = arr(m["positions"], "<f4", 3)
        nor = arr(m["normals"], "<f4", 3)
        uv = arr(m["uvs"], "<f4", 2)
        jo = arr(m["joints"], "<u2", 4)
        we = arr(m["weights"], "<f4", 4)
        ind = arr(m["indices"], "<u4", 1)
        for label, a in (("positions", pos), ("normals", nor), ("uvs", uv), ("joints", jo), ("weights", we)):
            if len(a) != n:
                errors.append(f"{name}.{label}: {len(a)} rows for {n} vertices")
        if len(ind) != 3 * m["triangleCount"] or ind.max() >= n:
            errors.append(f"{name}.indices: bad length or range")
        bone_names = m["boneNames"]
        geo_names = [b["name"] for b in r["geo"]["minecraft:geometry"][0]["bones"]]
        if bone_names != geo_names:
            errors.append(f"{name}: boneNames differ from geo bones")
        if jo.max() >= len(bone_names):
            errors.append(f"{name}: joint index out of range")
        sums = we.sum(1)
        if np.abs(sums - 1).max() > 1e-3:
            errors.append(f"{name}: weights sum range {sums.min():.4f}..{sums.max():.4f}")
        at = 0
        for g in m["groups"]:
            if g["start"] != at:
                errors.append(f"{name}: groups do not tile the indices at {at}")
            at = g["start"] + g["count"]
            if g["material"] >= 0:
                mat = rig["materials"][g["material"]]
                tex = rig["textures"][mat["texture"]]
                x, y, w, h = mat["rect"]
                gu = uv[ind[g["start"]:at]]
                lo = np.array([x, y]) / [tex["width"], tex["height"]] - 1e-4
                hi = np.array([x + w, y + h]) / [tex["width"], tex["height"]] + 1e-4
                if (gu < lo).any() or (gu > hi).any():
                    errors.append(f"{name}: group {g['material']} uvs leave material rect {mat['name']}")
        if at != len(ind):
            errors.append(f"{name}: groups cover {at} of {len(ind)} indices")
        meshes[name] = dict(pos=pos, nor=nor, uv=uv, jo=jo, we=we, ind=ind, rig=r)
        print(f"{name}: {len(bone_names)} bones, {n} vertices, {m['triangleCount']} triangles, "
              f"{len(m['groups'])} groups, bbox {pos.min(0).round(2)}..{pos.max(0).round(2)} px, "
              f"renderScale {r['renderScale']}, morphs {len(m.get('morphs', []))}")
    for s in ("main", "fp_arm"):
        clips = rig["animations"][s]["animations"]
        print(f"animations.{s}: {len(clips)} clips from {rig['animationSources'].get(s)}"
              + (f", overlay {rig['overlay'][s]['file']} clips {rig['overlay'][s]['clips']} replaced "
                 f"{sorted(rig['overlay'][s]['replaced'])}" if s in rig["overlay"] else ""))
    print(f"materials: {len(rig['materials'])}, source {rig['source']}, firstPerson {rig.get('firstPerson')}")
    for e in errors:
        print("ERROR", e)
    return rig, meshes, textures, errors


def render(prefix, meshes, textures, rig, pose):
    for name, m in meshes.items():
        keys = {}
        if pose and name == "main":
            clip = rig["animations"]["main"]["animations"][pose]
            for bone, ch in clip.get("bones", {}).items():
                rot = ch.get("rotation")
                if isinstance(rot, dict):
                    rot = rot[sorted(rot, key=float)[0]]
                    rot = rot.get("post", rot.get("pre")) if isinstance(rot, dict) else rot
                if isinstance(rot, list) and all(isinstance(v, (int, float)) for v in rot):
                    keys[bone] = rot
        W, _ = world_matrices(m["rig"]["geo"], keys)
        p = np.c_[m["pos"], np.ones(len(m["pos"]))]
        skinned = np.zeros((len(p), 3))
        for k in range(4):
            skinned += m["we"][:, k:k + 1] * np.einsum("vij,vj->vi", W[m["jo"][:, k]], p)[:, :3]
        size = 800
        lo, hi = skinned.min(0), skinned.max(0)
        s = (size - 40) / max(hi[0] - lo[0], hi[1] - lo[1])
        img = Image.new("RGB", (size, size), (40, 40, 48))
        draw = ImageDraw.Draw(img)
        tri = m["ind"].reshape(-1, 3)
        # front view: camera on -Z looking +Z sees the model's face; screen x = -scene x (model's left on screen right)
        depth = skinned[tri].mean(1)[:, 2]
        tex = textures[0]
        for t in tri[np.argsort(-depth)]:
            a, b, c = skinned[t]
            n = np.cross(b - a, c - a)
            if n[2] >= 0:
                continue
            u, v = m["uv"][t].mean(0)
            col = tex[min(int(v * tex.shape[0]), tex.shape[0] - 1), min(int(u * tex.shape[1]), tex.shape[1] - 1)]
            shade = 0.45 + 0.55 * abs(n[2]) / (np.linalg.norm(n) + 1e-9)
            pts = [(size / 2 - (q[0] - (lo[0] + hi[0]) / 2) * s, size - 20 - (q[1] - lo[1]) * s) for q in (a, b, c)]
            draw.polygon(pts, fill=tuple(int(x * shade) for x in col[:3]))
        img.save(f"{prefix}_{name}.png")
        print("wrote", f"{prefix}_{name}.png")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("rig")
    ap.add_argument("--render")
    ap.add_argument("--pose")
    a = ap.parse_args()
    rig, meshes, textures, errors = check(a.rig)
    if a.render:
        render(a.render, meshes, textures, rig, a.pose)
    sys.exit(1 if errors else 0)
