"""Test VRMs for the spring import (GenSpringCheck), derived from a VRM 1.0 model.

<model>_vrm0.vrm: the same model as VRM 0.x (VRM extension, secondaryAnimation): every 1.0 collider becomes a 0.x
sphere (capsules become two spheres, at offset and tail) with z negated as 0.x stores it, every spring a bone group
rooted at its first joint; the first group names the hips as its center.
<model>_ext.vrm: the 1.0 model with VRMC_springBone_extended_collider and VRMC_springBone_limit: collider 0 turns into
an inside sphere (radius 0.5), a plane collider on the hips (normal +Z) joins collider group 0, spring 0 gets the hips
as center, and the first joints of springs 1, 2 and 3 get a cone, a hinge and a spherical limit.

Usage: make_spring_variants.py <vrm 1.0 model> <output dir>
"""
import copy
import json
import os
import struct
import sys


def read_glb(path):
    data = open(path, "rb").read()
    length = struct.unpack_from("<I", data, 12)[0]
    document = json.loads(data[20:20 + length])
    return document, data[20 + length:]


def write_glb(path, document, rest):
    text = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    text += b" " * (-len(text) % 4)
    body = struct.pack("<II", len(text), 0x4E4F534A) + text + rest
    with open(path, "wb") as out:
        out.write(struct.pack("<4sII", b"glTF", 2, 12 + len(body)) + body)


def vrm0(document):
    document = copy.deepcopy(document)
    extensions = document["extensions"]
    vrm = extensions.pop("VRMC_vrm")
    spring = extensions.pop("VRMC_springBone")
    hips = vrm["humanoid"]["humanBones"]["hips"]["node"]
    groups = []  # one 0.x group per 1.0 collider sphere
    by_collider = []
    for collider in spring.get("colliders", []):
        shape = collider["shape"]
        body = shape.get("sphere") or shape.get("capsule")
        points = [body.get("offset", [0, 0, 0])] + ([body["tail"]] if "capsule" in shape else [])
        groups.append({"node": collider["node"], "colliders": [
            {"offset": {"x": p[0], "y": p[1], "z": -p[2]}, "radius": body.get("radius", 0)} for p in points]})
        by_collider.append(len(groups) - 1)
    bone_groups = []
    for index, chain in enumerate(spring.get("springs", [])):
        first = chain["joints"][0]
        group_indices = sorted({by_collider[c] for g in chain.get("colliderGroups", [])
                                for c in spring["colliderGroups"][g]["colliders"]})
        bone_groups.append({
            "comment": chain.get("name", ""), "stiffiness": first.get("stiffness", 1.0),
            "gravityPower": first.get("gravityPower", 0.0),
            "gravityDir": dict(zip("xyz", first.get("gravityDir", [0, -1, 0]))),
            "dragForce": first.get("dragForce", 0.4), "center": hips if index == 0 else -1,
            "hitRadius": first.get("hitRadius", 0.0), "bones": [first["node"]], "colliderGroups": group_indices})
    extensions["VRM"] = {
        "humanoid": {"humanBones": [{"bone": name, "node": bone["node"]}
                                    for name, bone in vrm["humanoid"]["humanBones"].items()]},
        "meta": {"title": vrm["meta"].get("name", ""), "author": ", ".join(vrm["meta"].get("authors", []))},
        "secondaryAnimation": {"colliderGroups": groups, "boneGroups": bone_groups}}
    used = document.get("extensionsUsed", [])
    document["extensionsUsed"] = [e for e in used if e not in ("VRMC_vrm", "VRMC_springBone")] + ["VRM"]
    return document


def extended(document):
    document = copy.deepcopy(document)
    spring = document["extensions"]["VRMC_springBone"]
    hips = document["extensions"]["VRMC_vrm"]["humanoid"]["humanBones"]["hips"]["node"]
    spring["colliders"][0]["extensions"] = {"VRMC_springBone_extended_collider": {
        "specVersion": "1.0", "shape": {"sphere": {"offset": [0, 0, 0], "radius": 0.5, "inside": True}}}}
    spring["colliders"].append({"node": hips, "shape": {"sphere": {"offset": [0, 0, -1000], "radius": 1000}},
                                "extensions": {"VRMC_springBone_extended_collider": {
                                    "specVersion": "1.0",
                                    "shape": {"plane": {"offset": [0, 0, 0], "normal": [0, 0, 1]}}}}})
    spring["colliderGroups"][0]["colliders"].append(len(spring["colliders"]) - 1)
    spring["springs"][0]["center"] = hips
    limits = [{"cone": {"angle": 0.5}},
              {"hinge": {"angle": 0.8, "rotation": [0, 0.7071068, 0, 0.7071068]}},
              {"spherical": {"pitch": 0.3, "yaw": 0.2}}]
    for chain, limit in zip(spring["springs"][1:4], limits):
        chain["joints"][0]["extensions"] = {"VRMC_springBone_limit": {"specVersion": "1.0", "limit": limit}}
    document["extensionsUsed"] = document.get("extensionsUsed", []) + [
        "VRMC_springBone_extended_collider", "VRMC_springBone_limit"]
    return document


def main():
    source, out_dir = sys.argv[1], sys.argv[2]
    document, rest = read_glb(source)
    stem = os.path.splitext(os.path.basename(source))[0]
    os.makedirs(out_dir, exist_ok=True)
    write_glb(os.path.join(out_dir, stem + "_vrm0.vrm"), vrm0(document), rest)
    write_glb(os.path.join(out_dir, stem + "_ext.vrm"), extended(document), rest)
    print("wrote", stem + "_vrm0.vrm", stem + "_ext.vrm")


main()
