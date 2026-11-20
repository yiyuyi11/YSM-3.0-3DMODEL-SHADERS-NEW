#!/usr/bin/env python3
"""Offline check of a skinned glTF/VRM: pose named bones, skin on the CPU (linear blend), rasterize with textures.

Usage: render_pose.py <model.glb|vrm> <out.png> [rest|test] [front|side|back]

The 'test' pose turns bones about world axes through their pivots, the way an animation would: arms lowered from the
A-pose, the left leg lifted sideways, the head turned. A skeleton whose joints do not sit among their vertices shows it
immediately: limbs swing about the wrong point and leave the body.
"""

import io
import math
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from fix_pelica import load_glb, local_matrix, read_accessor, world_matrices  # noqa: E402

# bone -> (world axis, degrees); signs chosen for a model facing +Z with its left at +X.
TEST_POSE = {
    "Bip001_L_UpperArm": ("z", -55), "Bip001_R_UpperArm": ("z", 55),
    "Bip001_L_Forearm": ("x", -35), "Bip001_R_Forearm": ("x", -35),
    "Bip001_L_Thigh": ("z", 35), "Bip001_R_Thigh": ("x", -30),
    "Bip001_R_Calf": ("x", 45), "Bip001_Head": ("y", 30),
}


def rotation(axis, degrees):
    c, s = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
    r = {"x": [[1, 0, 0], [0, c, -s], [0, s, c]], "y": [[c, 0, s], [0, 1, 0], [-s, 0, c]],
         "z": [[c, -s, 0], [s, c, 0], [0, 0, 1]]}[axis]
    m = np.eye(4)
    m[:3, :3] = r
    return m


def posed_world(nodes, pose):
    rest = world_matrices(nodes)
    parent = {c: i for i, n in enumerate(nodes) for c in n.get("children", [])}
    names = [n.get("name") for n in nodes]
    out = {}

    def get(i):
        if i in out:
            return out[i]
        local = local_matrix(nodes[i])
        if names[i] in pose:
            axis, degrees = pose[names[i]]
            frame = rest[i].copy()
            frame[:3, 3] = 0
            local = local @ np.linalg.inv(frame) @ rotation(axis, degrees) @ frame
        out[i] = get(parent[i]) @ local if i in parent else local
        return out[i]

    return [get(i) for i in range(len(nodes))]


def skinned_triangles(gltf, blob, pose):
    nodes = gltf["nodes"]
    world = posed_world(nodes, pose)
    skin = gltf["skins"][0]
    ibm = read_accessor(gltf, blob, skin["inverseBindMatrices"]).reshape(-1, 4, 4).transpose(0, 2, 1)
    matrices = np.stack([world[j] @ ibm[k] for k, j in enumerate(skin["joints"])])
    images = {}
    for node in nodes:
        if "mesh" not in node:
            continue
        for primitive in gltf["meshes"][node["mesh"]]["primitives"]:
            a = primitive["attributes"]
            position = read_accessor(gltf, blob, a["POSITION"]).astype(np.float64)
            joints = read_accessor(gltf, blob, a["JOINTS_0"]).astype(int)
            weights = read_accessor(gltf, blob, a["WEIGHTS_0"]).astype(np.float64)
            weights /= weights.sum(axis=1, keepdims=True)
            homogeneous = np.c_[position, np.ones(len(position))]
            skinned = np.zeros((len(position), 4))
            for k in range(4):
                skinned += weights[:, k:k + 1] * np.einsum("nij,nj->ni", matrices[joints[:, k]], homogeneous)
            uv = read_accessor(gltf, blob, a["TEXCOORD_0"]).astype(np.float64)
            triangles = read_accessor(gltf, blob, primitive["indices"]).reshape(-1, 3)
            material = gltf["materials"][primitive["material"]]
            image_index = gltf["textures"][material["pbrMetallicRoughness"]["baseColorTexture"]["index"]]["source"]
            if image_index not in images:
                view = gltf["bufferViews"][gltf["images"][image_index]["bufferView"]]
                data = blob[view.get("byteOffset", 0):view.get("byteOffset", 0) + view["byteLength"]]
                images[image_index] = np.asarray(Image.open(io.BytesIO(data)).convert("RGB"), dtype=np.float64) / 255
            yield skinned[:, :3], uv, triangles, images[image_index]


def render(path, out, pose_name="test", view="front", size=(520, 900)):
    gltf, blob = load_glb(path)
    pose = TEST_POSE if pose_name == "test" else {}
    width, height = size
    color = np.full((height, width, 3), 0.92)
    depth = np.full((height, width), -np.inf)
    turn = {"front": 0, "side": 90, "back": 180}[view]
    c, s = math.cos(math.radians(turn)), math.sin(math.radians(turn))
    light = np.array([0.35, 0.6, 0.72])
    light /= np.linalg.norm(light)
    scale = height / 1.9
    for position, uv, triangles, texture in skinned_triangles(gltf, blob, pose):
        # View from +Z for 'front' (the model faces +Z); rotate the model about Y for the other views.
        x = position[:, 0] * c + position[:, 2] * s
        z = -position[:, 0] * s + position[:, 2] * c
        px = width / 2 + x * scale
        py = height - 20 - position[:, 1] * scale
        th, tw, _ = texture.shape
        for tri in triangles:
            xs, ys, zs = px[tri], py[tri], z[tri]
            x0, x1 = int(max(xs.min(), 0)), int(min(xs.max() + 1, width))
            y0, y1 = int(max(ys.min(), 0)), int(min(ys.max() + 1, height))
            if x0 >= x1 or y0 >= y1:
                continue
            det = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
            if abs(det) < 1e-9:
                continue
            gx, gy = np.meshgrid(np.arange(x0, x1) + 0.5, np.arange(y0, y1) + 0.5)
            l0 = ((ys[1] - ys[2]) * (gx - xs[2]) + (xs[2] - xs[1]) * (gy - ys[2])) / det
            l1 = ((ys[2] - ys[0]) * (gx - xs[2]) + (xs[0] - xs[2]) * (gy - ys[2])) / det
            l2 = 1 - l0 - l1
            inside = (l0 >= 0) & (l1 >= 0) & (l2 >= 0)
            if not inside.any():
                continue
            zz = l0 * zs[0] + l1 * zs[1] + l2 * zs[2]
            region = depth[y0:y1, x0:x1]
            visible = inside & (zz > region)
            if not visible.any():
                continue
            u = l0 * uv[tri[0], 0] + l1 * uv[tri[1], 0] + l2 * uv[tri[2], 0]
            v = l0 * uv[tri[0], 1] + l1 * uv[tri[1], 1] + l2 * uv[tri[2], 1]
            texel = texture[np.clip((v * th).astype(int), 0, th - 1), np.clip((u * tw).astype(int), 0, tw - 1)]
            p = position[tri]
            normal = np.cross(p[1] - p[0], p[2] - p[0])
            n = np.array([normal[0] * c + normal[2] * s, normal[1], -normal[0] * s + normal[2] * c])
            n /= np.linalg.norm(n) + 1e-12
            shade = 0.45 + 0.55 * abs(float(n @ light))
            region[visible] = zz[visible]
            color[y0:y1, x0:x1][visible] = texel[visible] * shade
    Image.fromarray((np.clip(color, 0, 1) * 255).astype(np.uint8)).save(out)
    print(f"wrote {out}")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        raise SystemExit(__doc__.splitlines()[2])
    render(sys.argv[1], sys.argv[2], *(sys.argv[3:5]))
