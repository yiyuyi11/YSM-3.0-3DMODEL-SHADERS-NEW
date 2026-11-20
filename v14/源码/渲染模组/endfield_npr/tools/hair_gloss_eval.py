#!/usr/bin/env python3
"""Offline evaluator of the hair primary (Kajiya) highlight of npr_character.fsh on Pelica's real hair mesh.

Splats Pelica's head (hair, face, body, collar, brows, iris) orthographically at 1 mm/px with a z-buffer, then evaluates
the shader's hair branch (H2 1667-1696: lutU = sat(sinTH^200 * G), raw = RS(lutU) * lutU * vh, primary =
raw * 0.04 G * _AnisotropyIntensity * 5) per visible hair pixel for 15 camera directions (elevation 0/20/35 deg,
azimuth 0/45/90/135/180) x 12 sun directions (elevation 30/60, azimuth every 60 deg), sunlit (shadow = 1).
Compares the gloss G of the real _MetallicGlossMap (hair_01_p) with constant stand-ins (hairGlossFallback) and,
with --layer N, with G box-downsampled to N px, and with --composited, with G read bilinearly from prop layers that
NprMaps actually built (tools/maps-check.sh writes build/maps-check/<dir>/layer-prop.rgba and size.txt).

usage: tools/hair_gloss_eval.py [--glb GLB] [--gloss PNG] [--rs PNG] [--layer N ...]
         [--composited LABEL=DIR ...] [--hair-rect X Y W H] [--atlas A] [--constants G ...]
Prints, per G source: mean primary energy (sum over hair pixels) and mean peak, also relative to the real map.
"""
import argparse
import io
import json
import os
import struct

import numpy as np
from PIL import Image

PELICA = os.path.expanduser("~/ysm/pelica-full/01-模型/textures/assets/beyond/arts/entity/actor/girl/pelica/textures")
PX = 0.001  # metres per pixel
RES = 520
UP, FW = np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
RT = np.cross(UP, FW)
ANISO = (0.5, 3.0, 1.0, 0.0)  # Pelica _AnisotropyValue, _AnisotropyIntensity, _AnisotropyEdgeFade, _AnisotropyDirX


def unit(v):
    v = np.asarray(v, float)
    return v / np.linalg.norm(v)


def load_glb(path):
    data = open(path, "rb").read()
    length = struct.unpack("<I", data[12:16])[0]
    doc = json.loads(data[20:20 + length])
    blob = data[28 + length:]
    types = {5126: np.float32, 5123: np.uint16, 5125: np.uint32}
    sizes = {"SCALAR": 1, "VEC2": 2, "VEC3": 3}

    def accessor(i):
        a = doc["accessors"][i]
        view = doc["bufferViews"][a["bufferView"]]
        n = sizes[a["type"]]
        arr = np.frombuffer(blob, types[a["componentType"]], a["count"] * n,
                            view.get("byteOffset", 0) + a.get("byteOffset", 0))
        return arr.reshape(a["count"], n) if n > 1 else arr

    meshes = {}
    for mesh in doc["meshes"]:
        for prim in mesh["primitives"]:
            name = doc["materials"][prim["material"]]["name"]
            meshes.setdefault(name, []).append((accessor(prim["attributes"]["POSITION"]).astype(np.float64),
                                                accessor(prim["attributes"]["NORMAL"]).astype(np.float64),
                                                accessor(prim["attributes"]["TEXCOORD_0"]).astype(np.float64),
                                                accessor(prim["indices"]).astype(np.int64).reshape(-1, 3)))
    return meshes


def sample_surfaces(meshes, rng):
    names = ["hair_01", "face_01", "body_01", "cloth_01", "eyebrow_l_01", "eyebrow_r_01", "iris_01"]
    ps, ns, uvs, idx, ids, off = [], [], [], [], [], 0
    for k, name in enumerate(names):
        for p, n, uv, tri in meshes[name]:
            ps.append(p), ns.append(n), uvs.append(uv), idx.append(tri + off), ids.append(np.full(len(tri), k))
            off += len(p)
    p, n, uv, tri, sid = map(np.concatenate, (ps, ns, uvs, idx, ids))
    a, b, c = p[tri[:, 0]], p[tri[:, 1]], p[tri[:, 2]]
    keep = (a[:, 1] > 1.2) | (b[:, 1] > 1.2) | (c[:, 1] > 1.2)
    tri, sid = tri[keep], sid[keep]
    area = 0.5 * np.linalg.norm(np.cross((b - a)[keep], (c - a)[keep]), axis=1)
    count = np.maximum(1, np.ceil(area * 3 / PX ** 2)).astype(np.int64)
    which = np.repeat(np.arange(len(tri)), count).astype(np.int32)
    r1, r2 = rng.random(len(which), dtype=np.float32), rng.random(len(which), dtype=np.float32)
    s = np.sqrt(r1)
    w = np.stack([1 - s, s * (1 - r2), s * r2], 1)

    def interp(attr):
        attr = attr.astype(np.float32)
        return sum(attr[tri[which, i]] * w[:, i:i + 1] for i in range(3))
    normals = interp(n)
    normals /= np.linalg.norm(normals, axis=1, keepdims=True)
    return interp(p), normals, interp(uv), sid[which].astype(np.int8)


def render(points, cam, center=np.array([0, 1.45, 0])):
    c = unit(cam)
    r = unit(np.cross(UP, c))
    u = np.cross(c, r)
    q = points - center
    sx, sy, depth = (q @ r) / PX + RES / 2, (q @ u) / PX + RES / 2, q @ c
    idx = np.where((sx >= 0) & (sx < RES) & (sy >= 0) & (sy < RES))[0]
    pix = sy[idx].astype(int) * RES + sx[idx].astype(int)
    order = np.lexsort((-depth[idx], pix))
    first = np.r_[True, pix[order][1:] != pix[order][:-1]]
    return idx[order[first]], c


def primary(vis, c, light, gloss, normals, sid, rs_row):
    hair = vis[sid[vis] == 0]
    n = normals[hair].astype(np.float64)
    n = np.where((n @ c < 0)[:, None], -n, n)
    d = unit(UP + ANISO[3] * RT)
    tb = -(d - n * (n @ d)[:, None])
    tb /= np.linalg.norm(tb, axis=1, keepdims=True) + 1e-9
    w = RT * (c @ RT) + FW * (c @ FW) + UP * (light @ UP)
    h = unit(unit(2 * w + light) + c)
    nh = np.stack([n @ RT, n @ FW], 1) + 1e-5
    nh /= np.linalg.norm(nh, axis=1, keepdims=True)
    vh = np.clip(nh @ unit([c @ RT, c @ FW]), 1e-5, 1) ** ANISO[2]
    t1 = tb + n * (2 * ANISO[0] - 1)
    t1 /= np.linalg.norm(t1, axis=1, keepdims=True)
    th = t1 @ h
    g = gloss(hair)
    u = np.clip(np.maximum(np.sqrt(np.maximum(1 - th * th, 0)), 1e-4) ** 200 * g, 0, 1)
    x = u * 255
    i0 = np.floor(x).astype(int)
    i1 = np.minimum(i0 + 1, 255)
    rs = rs_row[i0] * (1 - (x - i0))[:, None] + rs_row[i1] * (x - i0)[:, None]
    return (rs * (u * vh)[:, None] * (0.04 * g)[:, None] * ANISO[1] * 5).max(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--glb", default=os.path.expanduser("~/ysm/pelica/01-模型/佩丽卡-真骨架2.glb"))
    ap.add_argument("--gloss", default=os.path.join(PELICA, "t_actor_pelica_hair_01_p.tga.png"))
    ap.add_argument("--rs", default=os.path.expanduser("~/ysm/pelica/07-贴图素材/T_actor_common_hair_01_RS.png"))
    ap.add_argument("--layer", type=int, nargs="*", default=[])
    ap.add_argument("--composited", nargs="*", default=[], help="LABEL=DIR with layer-prop.rgba and size.txt")
    ap.add_argument("--hair-rect", type=float, nargs=4, default=[2.0, 1747.0, 1741.0, 1741.0],
                    help="hair_01's atlas rect (texels), as GenNpr prints it (Pelica)")
    ap.add_argument("--atlas", type=float, default=4096.0)
    ap.add_argument("--constants", type=float, nargs="*", default=[0.5, 0.55, 0.58, 0.6, 0.62, 0.65, 0.7, 0.8, 1.0])
    args = ap.parse_args()
    meshes = load_glb(args.glb)
    points, normals, uvs, sid = sample_surfaces(meshes, np.random.default_rng(1))
    gmap = np.asarray(Image.open(args.gloss).convert("RGBA"))[..., 1].astype(np.float32) / 255
    rs_row = np.asarray(Image.open(args.rs).convert("RGB")).astype(np.float32)[0] / 255

    def nearest(m):
        def f(h):
            hh, ww = m.shape
            x = np.clip((uvs[h, 0] % 1.0) * ww, 0, ww - 1).astype(int)
            y = np.clip((uvs[h, 1] % 1.0) * hh, 0, hh - 1).astype(int)
            return m[y, x].astype(np.float64)
        return f
    sources = {"real map": nearest(gmap)}
    for n in args.layer:
        down = np.asarray(Image.fromarray((gmap * 255).astype(np.uint8)).resize((n, n), Image.BOX), np.float32) / 255
        sources[f"map at {n}px"] = nearest(down)
    for spec in args.composited:
        label, folder = spec.split("=", 1)
        size = int(open(os.path.join(folder, "size.txt")).read().split()[0])
        layer = np.fromfile(os.path.join(folder, "layer-prop.rgba"), np.uint8).reshape(size, size, 4)
        layer = layer[..., 1].astype(np.float32) / 255

        def composited(h, layer=layer, scale=size / args.atlas):
            x0, y0, w, hh = args.hair_rect
            px = (x0 + np.clip(uvs[h, 0], 0, 1) * w) * scale - 0.5  # YSM clamps uv into the region
            py = (y0 + np.clip(uvs[h, 1], 0, 1) * hh) * scale - 0.5
            i = np.clip(np.floor(px).astype(int), 0, size - 2)
            j = np.clip(np.floor(py).astype(int), 0, size - 2)
            fx, fy = px - i, py - j
            return (layer[j, i] * (1 - fx) * (1 - fy) + layer[j, i + 1] * fx * (1 - fy)
                    + layer[j + 1, i] * (1 - fx) * fy + layer[j + 1, i + 1] * fx * fy).astype(np.float64)
        sources[label] = composited
    for value in args.constants:
        sources[f"constant {value}"] = (lambda v: lambda h: np.full(len(h), v))(value)
    cams = [np.array([np.sin(np.radians(az)) * np.cos(np.radians(el)), np.sin(np.radians(el)),
                      np.cos(np.radians(az)) * np.cos(np.radians(el))])
            for el in (0, 20, 35) for az in (0, 45, 90, 135, 180)]
    lights = [unit([np.sin(np.radians(az)) * np.cos(np.radians(el)), np.sin(np.radians(el)),
                    np.cos(np.radians(az)) * np.cos(np.radians(el))]) for el in (30, 60) for az in range(0, 360, 60)]
    result = {k: [] for k in sources}
    for cam in cams:
        vis, c = render(points, cam)
        for light in lights:
            for k, f in sources.items():
                s = primary(vis, c, light, f, normals, sid, rs_row)
                result[k].append((s.sum(), s.max()))
    ref = np.array(result["real map"]).mean(0)
    print(f"{'G source':18s} {'energy':>9s} {'x real':>7s} {'peak':>7s} {'x real':>7s}")
    for k, v in result.items():
        e, p = np.array(v).mean(0)
        print(f"{k:18s} {e:9.2f} {e / ref[0]:7.2f} {p:7.3f} {p / ref[1]:7.2f}")


if __name__ == "__main__":
    main()
