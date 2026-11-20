#!/usr/bin/env python3
"""Turn 佩丽卡-真骨架2.glb into a VRM 1.0 file that YSM's humanoid import loads correctly.

Usage: fix_pelica.py <input.glb> <output.vrm> [--textures DIR] [--face-set DIR] [--overlays DIR] [--npr-export DIR]

Fixes, each checked again on the output before it is written:

1. Skeleton handedness. The joints were read from Unity's left-handed m_BindPose and turned to Y-up with a
   determinant +1 matrix, while the meshes came from an OBJ export that had already been converted to right-handed.
   Every left/right joint therefore sat on the opposite side of the vertices it drives (Bip001_L_Hand at x = -0.40,
   its vertices at x = +0.42). The rest pose still looked right because the inverse bind matrices were computed from
   the same wrong joints, but any rotation pivoted about the wrong side. Mirroring the whole skeleton across X
   (t.x -> -t.x, quaternion (x, y, z, w) -> (x, -y, -z, w), IBM -> S*IBM*S with S = diag(-1, 1, 1, 1)) puts every
   joint back among its vertices and keeps the rest pose exactly: W' * IBM' = S * W * IBM * S = I.
2. Tiled UVs. cloth_01, cloth_02 and cloth_04 address a second, repeat-wrapped tile (u in [1, 2]); YSM clamps UVs to
   [0, 1] when it packs its texture atlas, so those triangles all sampled the right-hand edge of the texture. No
   triangle crosses u = 1, so subtracting the integer part per vertex is lossless.
3. Overlay shadow cards. eyeshadow_01 and hairshadow_01 are Endfield's OverlayShadow cards (eye-white shadow and
   eyeshadow on one, the bangs' shadow on the forehead on the other), which the game multiplies over the finished face.
   Drawn as opaque geometry they hide the eyes. Each card gets a MASK material over its own fully transparent 64x64
   stand-in texture, its UVs moved into the middle half of it (0.25 + 0.5 uv): YSM's own shader and shader packs
   discard every card texel, and no filter tap ever reaches a neighbouring atlas region (a 3x3 untextured tile used
   to bleed into white jagged rims). The endfield_npr render mod finds the cards by their atlas rectangles and
   applies the real overlay textures and tints from the model's NPR package (7.).
4. All other materials stay OPAQUE. The alpha channels of the body and iris textures are masks for the game's shader,
   not coverage; any blended or masked mode would cut holes (the iris samples alpha ~0.01 everywhere).
5. VRMC_vrm 1.0 with the humanoid mapping of the 3ds Max Biped skeleton, the eye bones that carry the iris, and meta.
6. VRMC_springBone for the hair, skirt, jacket hem and front straps (Endfield moves them with Magica Cloth). The legs
   are capsule colliders fitted to the leg mesh (cloth_04): an upper thigh capsule r 0.085 from 5 to 22 cm below the
   hip, a lower thigh capsule r 0.06 down to the knee and a calf capsule r 0.055. YSM only tests each joint's tail
   against the colliders, so the hem joints carry a 3 cm hit radius that keeps the cloth between neighbouring chains
   off the legs too. The skirt hangs from the chest, and the generated walk and run lean the upper body 25-50 degrees
   forward about the waist while the thighs swing from the unleaning hips: the lean tips the skirt's front back onto
   the thighs, where the side-pushing thigh capsules let the front chains slip between the legs. The three front
   chains (and the jacket's front flap) therefore collide with their own thigh capsules, set 6 cm further back with
   the same front surface, so contacts push them forward rather than sideways, and their upper joints keep 7 cm off
   them. That rests the front panels about 3.5 cm further forward at the hip when standing (see SPRINGS, LEG_GROUPS).
7. The endfield_npr package: the glTF extension ENDFIELD_npr (see NPR_* below and endfield_npr's README) with
   Pelica's ramps, skin LUT, face SDF/mask/highlight, iris matcap, the three OverlayShadow textures as extra embedded
   PNG images, her material parameters, and the face/iris/card materials by name. The patched YSM import resolves the
   material names to the atlas rectangles it builds and ships the package to every client with the model.
8. Face seams. face_01 is stitched from pieces whose seam vertices coincide bit-exactly but were skinned differently
   at 14 of them (lip ring, jaw, nose ridge); expressions and jaw motion opened sub-pixel cracks there. Vertices at
   one position now share one skin (SEAM_WELD_MESHES, weld_seam_skin); positions, normals and UVs stay as authored.
9. Per-material maps and Unity parameters: ENDFIELD_npr.materials, written by tools/npr_materials.py from the user's
   export (--npr-export, env NPR_EXPORT, default ~/ysm/efdata/props) exactly as its CLI writes them into any VRM.
"""

import argparse
import json
import os
import struct
import sys
import zlib

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "tools", "npr_materials"))
import npr_materials  # noqa: E402

# Endfield's OverlayShadow cards (multiplied over the face by the render mod, see endfield_npr's README). Their UVs are
# moved into [CARD_INSET, 1 - CARD_INSET] of a fully transparent CARD_TEXTURE_SIZE stand-in: the atlas region around
# them is transparent too, so bilinear filtering never pulls a neighbouring region's texels onto the card.
OVERLAY_CARDS = {"eyeshadow_01", "hairshadow_01"}
CARD_INSET = 0.25
CARD_TEXTURE_SIZE = 64

# endfield_npr package (glTF extension ENDFIELD_npr, format 1). Texture sources: the research dumps (local only).
NPR_EXTENSION = "ENDFIELD_npr"
NPR_TEXTURE_DIR = os.path.expanduser("~/ysm/pelica/07-贴图素材")
NPR_FACE_SET_DIR = os.path.expanduser("~/ysm/sdf")
NPR_OVERLAY_DIR = os.path.expanduser("~/ysm/overlay/贴图")
# (place in the package, source directory option, file). Ramp choices: Pelica's own ramp pathIDs are unmapped.
NPR_IMAGES = [
    (("ramps", "skinRD"), "textures", "T_actor_common_body_01_RD.png"),
    (("ramps", "faceRD"), "textures", "T_actor_common_face_01_RD.png"),
    (("ramps", "eyeRD"), "textures", "T_actor_common_face_01_RD.png"),
    (("ramps", "hairRD"), "textures", "T_actor_common_hair_03_RD.png"),    # cool lavender terminator, closest to refs
    (("ramps", "clothRD"), "textures", "T_actor_common_cloth_05_RD.png"),  # cloth_01/02/07 paint the white cloth pink
    (("ramps", "clothRS"), "textures", "T_actor_common_cloth_02_RS.png"),  # its smoothness-0.42 row is neutral-cool
    (("ramps", "hairRS"), "textures", "T_actor_common_hair_01_RS.png"),
    (("skinLut",), "textures", "T_actor_common_femaleskincolor01_lut_D.png"),
    (("face", "sdf"), "face_set", "T_actor_common_female_face_01_SDF.png"),
    (("face", "sdfMask"), "face_set", "T_actor_common_female_face_01_cm_M.png"),
    (("face", "highlight"), "face_set", "T_actor_common_face_01_hl_M.png"),
    (("iris", "matcap"), "face_set", "T_actor_common_matcap_10_D.png"),
    (("overlays", 0, "texture"), "overlays", "T_actor_common_eyeshadow_01_M.png"),
    (("overlays", 1, "texture"), "overlays", "T_actor_common_eyeshadow_02_M.png"),
    (("overlays", 2, "texture"), "overlays", "T_actor_common_hairshadow_01_M.png"),
]
# Saved Unity material values of m_actor_pelica_* unless noted: [_Smoothness, _Specular, _ShadowColorBrightness,
# _ShadowColorSaturation] per surface class, rim/edge tint, hair anisotropy, outline per class (skin, face, hair, cloth).
NPR_SHADING = {
    "skin": [0.258, 0.454, 0.55, 1.4],
    "face": [1.0, 0.0, 0.55, 1.4],
    "hair": [0.7, 0.0, 0.6, 1.0],
    "cloth": [0.42, 1.0, 0.5, 1.0],  # smoothness = mean alpha of cloth_01_p (the gloss map YSM cannot pass)
    "eye": [0.0, 0.0, 0.55, 1.4],
    "skinRimOffScale": 0.7,
    "faceSkinRimOffScale": 1.0,
    "faceRimOffScale": 1.0,
    "sdfRimColor": [0.651, 0.396, 0.416],
    "hairAniso": [0.5, 3.0, 1.0, 0.0],  # _AnisotropyValue, _AnisotropyIntensity, _AnisotropyEdgeFade, _AnisotropyDirX
    "hairSpecMask": [0.5, 1.0],  # gloss map G and A stand-ins (YSM has no gloss map)
    "hairAniso2": [-0.02, 0.5],  # _AnisotropyRange2, _AnisotropyValue2
    "hairAnisoColor2": [0.2264151, 0.22329417, 0.114061944],
    "outlineWidth": [0.6, 0.5, 0.5, 0.65],
    "outlineOffsetZ": [0.0, 0.75, 0.01, 0.02],
    "outlineColorSkinCloth": [0.5, 1.5, 0.35, 1.5],  # brightness/saturation: skin, cloth
    "outlineColorHairEye": [0.8, 1.0, 0.5, 1.5],  # brightness/saturation: hair, eye
}
CARD_UV = [CARD_INSET, CARD_INSET, 1.0 - 2.0 * CARD_INSET, 1.0 - 2.0 * CARD_INSET]
# Face (face_01, eyebrows share its texture), iris (iris_01) and the OverlayShadow layers in draw order: the
# eyeshadow_01 card's two material slots (M_eyewhiteshadow_common_01, M_eyeshadow_common_03; the textures are named
# crosswise), then M_hairshadow_common_01 on hairshadow_01 (~/ysm/overlay 材质JSON). stencilRef = _ShadowOverIris
# (4: skin/face/hair receivers, 20: iris/brow), angleRange = _ShadowAngleRange.
NPR_PROFILE = {
    "format": 1,
    "shading": NPR_SHADING,
    "face": {"material": "face_01", "highlightVector": [0.03, 0.0, 1.0]},  # _HighlightMapVector
    "iris": {"material": "iris_01", "matcapColor": [0.23948, 0.359814, 0.818868, 0.698039],
             "matcapNormalScale": 0.9},  # tuned
    "overlays": [
        {"card": {"material": "eyeshadow_01", "uv": CARD_UV}, "baseColor": [0.35, 0.330167, 0.32025, 1.0],
         "useGrayAsAlpha": 1.0, "stencilRef": 4, "disableDrawUnderHair": True, "angleRange": 0.0},
        {"card": {"material": "eyeshadow_01", "uv": CARD_UV}, "baseColor": [0.603922, 0.668871, 0.705882, 1.0],
         "useGrayAsAlpha": 1.0, "stencilRef": 20, "disableDrawUnderHair": True, "angleRange": 0.0001},
        {"card": {"material": "hairshadow_01", "uv": CARD_UV}, "baseColor": [0.823529, 0.743235, 0.716471, 1.0],
         "useGrayAsAlpha": 1.0, "stencilRef": 4, "disableDrawUnderHair": False, "angleRange": 0.0},
    ],
}
TILED_MESHES = {"cloth_01", "cloth_02", "cloth_04"}
# Meshes whose coincident (seam) vertices get one shared skin. face_01 is stitched from pieces (the mouth patch with
# lips, cavity, teeth and tongue; the nose ridge) whose seam vertices coincide bit-exactly but carry different
# weights at 12 places (up to 0.45 apart, lipRup1/lipRup2, lipMdn/lipLdn4, jaw, nose): every expression or jaw motion
# moved the two sides apart, and the sub-pixel cracks showed the dark skin outline shell behind the face as a dotted
# ring around the mouth and a tick under the nose. Cloth and hair are left alone: their coincident vertices join
# panels and strands that different spring chains drive on purpose.
SEAM_WELD_MESHES = {"face_01"}
# Seam tolerance (metres): boundary vertices of different pieces this close are one seam point, and a boundary vertex
# this close to another piece's boundary edge is a T-junction. face_01's lip strips sit 0.2-0.3 mm off the mouth patch.
SEAM_SNAP = 0.0005

# VRM 1.0 humanoid bone -> node name. Biped Spine/Spine1/Spine2 become spine/chest/upperChest.
HUMANOID = {
    "hips": "Bip001_Pelvis",
    "spine": "Bip001_Spine",
    "chest": "Bip001_Spine1",
    "upperChest": "Bip001_Spine2",
    "neck": "Bip001_Neck",
    "head": "Bip001_Head",
    "leftEye": "eyeLfJoint",
    "rightEye": "eyeRtJoint",
    "jaw": "jawJoint",
}
for side, s in (("left", "L"), ("right", "R")):
    HUMANOID.update({
        f"{side}Shoulder": f"Bip001_{s}_Clavicle",
        f"{side}UpperArm": f"Bip001_{s}_UpperArm",
        f"{side}LowerArm": f"Bip001_{s}_Forearm",
        f"{side}Hand": f"Bip001_{s}_Hand",
        f"{side}UpperLeg": f"Bip001_{s}_Thigh",
        f"{side}LowerLeg": f"Bip001_{s}_Calf",
        f"{side}Foot": f"Bip001_{s}_Foot",
        f"{side}Toes": f"Bip001_{s}_Toe0",
    })
    # Biped numbers fingers from the thumb: FingerN, FingerN1, FingerN2.
    for n, finger in enumerate(("Thumb", "Index", "Middle", "Ring", "Little")):
        segments = ("Metacarpal", "Proximal", "Distal") if finger == "Thumb" else ("Proximal", "Intermediate", "Distal")
        for k, segment in enumerate(segments):
            HUMANOID[f"{side}{finger}{segment}"] = f"Bip001_{s}_Finger{n}" + ("" if k == 0 else str(k))

# Secondary motion. Endfield simulates hair, skirt, jacket hem and the front straps with Magica Cloth; YSM simulates
# VRMC_springBone chains. Per bone-name prefix (a chain takes the first entry whose prefix its root matches): the first
# joint used (the skirt's and hem's first joint sits above the belt and has to stay with the chest), stiffness,
# gravity, drag, hit radius (m) of the joints above the hem and of the hem joint (the last one, whose tail is the free
# edge), and the collider groups it hits.
SPRINGS = [
    # Hair: heavier than the first pass (1.8 / 0.15 / 0.7 read as weightless): less stiffness and drag let it keep
    # its swing (inertia) and lag the head, more gravity makes it hang and sag like a mass of hair.
    ("hair_base_Back", 0, 1.2, 0.35, 0.5, 0.03, 0.03, ("body",)),
    ("hair_base_L", 0, 1.2, 0.35, 0.5, 0.02, 0.02, ("body",)),
    ("hair_base_R", 0, 1.2, 0.35, 0.5, 0.02, 0.02, ("body",)),
    ("hair_base_Front", 0, 2.2, 0.12, 0.65, 0.015, 0.015, ("head",)),  # bangs: stays off the eyes
    ("qunbai02_01_M_b", 1, 1.6, 0.3, 0.7, 0.07, 0.03, ("legs_front",)),
    ("qunbai02_01_L_c", 1, 1.6, 0.3, 0.7, 0.07, 0.03, ("legs_front",)),
    ("qunbai02_01_R_a", 1, 1.6, 0.3, 0.7, 0.07, 0.03, ("legs_front",)),
    ("qunbai02", 1, 1.6, 0.3, 0.7, 0.01, 0.03, ("legs",)),
    ("qunbai03", 1, 1.6, 0.3, 0.7, 0.01, 0.03, ("legs",)),
    ("yibai02_01_R_a", 1, 1.4, 0.3, 0.7, 0.02, 0.03, ("legs_front",)),
    ("yibai02", 1, 1.4, 0.3, 0.7, 0.02, 0.03, ("legs",)),
    ("clothes_ribbon", 0, 1.5, 0.5, 0.7, 0.015, 0.015, ("legs",)),
]

# Thigh capsules per collider group: start and end (m along the thigh from the hip, None: the knee), radius, and how
# far the axis sits behind the bone. "legs" is fitted to the front of the leg mesh: the thigh reaches 8.4 cm from its
# bone down to 16 cm below the hip, 7.4 cm at 20 cm and 5 cm at the knee. "legs_front" has the same front surface on
# an axis 6 cm further back and starts 8 cm below the hip. Tuned with tools/springsim against the generated clips.
LEG_GROUPS = {
    "legs": ((0.05, 0.22, 0.085, 0.0), (0.22, None, 0.06, 0.0)),
    "legs_front": ((0.08, 0.22, 0.145, 0.06), (0.22, None, 0.12, 0.06)),
}
# Calf capsule shared by both groups (start, end, radius): the calf is 5-8 cm thick over its upper half.
CALF_CAPSULE = (0.0, 0.22, 0.055)

COMPONENT = {5120: "i1", 5121: "u1", 5122: "i2", 5123: "u2", 5125: "u4", 5126: "f4"}
WIDTH = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}
CODE = {np.dtype("<u1"): 5121, np.dtype("<u2"): 5123, np.dtype("<u4"): 5125, np.dtype("<f4"): 5126}
TYPE = {1: "SCALAR", 2: "VEC2", 3: "VEC3", 4: "VEC4", 16: "MAT4"}


def load_glb(path):
    data = open(path, "rb").read()
    magic, version, length = struct.unpack_from("<4sII", data, 0)
    if magic != b"glTF" or version != 2:
        raise SystemExit(f"{path}: not a glTF 2.0 binary")
    offset, gltf, binary = 12, None, b""
    while offset < length:
        size, kind = struct.unpack_from("<II", data, offset)
        chunk = data[offset + 8:offset + 8 + size]
        if kind == 0x4E4F534A:
            gltf = json.loads(chunk.decode("utf-8"))
        elif kind == 0x004E4942:
            binary = chunk
        offset += 8 + size
    return gltf, binary


def read_accessor(gltf, binary, index):
    accessor = gltf["accessors"][index]
    view = gltf["bufferViews"][accessor["bufferView"]]
    dtype = np.dtype("<" + COMPONENT[accessor["componentType"]])
    width = WIDTH[accessor["type"]]
    stride = view.get("byteStride", dtype.itemsize * width)
    start = view.get("byteOffset", 0) + accessor.get("byteOffset", 0)
    array = np.ndarray((accessor["count"], width), dtype=dtype, buffer=binary, offset=start,
                       strides=(stride, dtype.itemsize))
    return array.copy()


def view_bytes(gltf, binary, index):
    view = gltf["bufferViews"][index]
    start = view.get("byteOffset", 0)
    return binary[start:start + view["byteLength"]]


class Writer:
    """Packs accessors and images into one buffer, each view 4-byte aligned."""

    def __init__(self):
        self.blob = bytearray()
        self.views = []
        self.accessors = []

    def view(self, payload, target=None):
        while len(self.blob) % 4:
            self.blob.append(0)
        view = {"buffer": 0, "byteOffset": len(self.blob), "byteLength": len(payload)}
        if target:
            view["target"] = target
        self.blob += payload
        self.views.append(view)
        return len(self.views) - 1

    def accessor(self, array, target=None, bounds=False):
        array = np.ascontiguousarray(array)
        width = array.shape[1] if array.ndim == 2 else 1
        accessor = {"bufferView": self.view(array.tobytes(), target), "componentType": CODE[array.dtype],
                    "count": int(array.shape[0]), "type": TYPE[width]}
        if bounds:
            accessor["min"] = [float(v) for v in array.min(axis=0)]
            accessor["max"] = [float(v) for v in array.max(axis=0)]
        self.accessors.append(accessor)
        return len(self.accessors) - 1


def mirror_node(node):
    """Conjugates a node's local transform by S = diag(-1, 1, 1)."""
    if "matrix" in node:
        raise SystemExit("matrix nodes are not supported")
    node = dict(node)
    if "translation" in node:
        x, y, z = node["translation"]
        node["translation"] = [-x, y, z]
    if "rotation" in node:
        x, y, z, w = node["rotation"]
        node["rotation"] = [x, -y, -z, w]
    if "scale" in node and min(node["scale"]) <= 0:
        raise SystemExit(f"node {node.get('name')} has a non-positive scale")
    return node


def transparent_png(size):
    """A size x size RGBA PNG whose texels are all (0, 0, 0, 0)."""
    def chunk(kind, payload):
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))
    rows = b"".join(b"\0" + bytes(4 * size) for _ in range(size))  # filter byte 0 per row
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))


def local_matrix(node):
    t = np.array(node.get("translation", [0, 0, 0]), dtype=np.float64)
    x, y, z, w = node.get("rotation", [0, 0, 0, 1])
    s = np.array(node.get("scale", [1, 1, 1]), dtype=np.float64)
    r = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                  [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                  [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    m = np.eye(4)
    m[:3, :3] = r * s
    m[:3, 3] = t
    return m


def world_matrices(nodes):
    parent = {c: i for i, n in enumerate(nodes) for c in n.get("children", [])}
    world = {}

    def get(i):
        if i not in world:
            world[i] = get(parent[i]) @ local_matrix(nodes[i]) if i in parent else local_matrix(nodes[i])
        return world[i]

    return [get(i) for i in range(len(nodes))]


def spring_bones(nodes, farthest):
    """Appends a tail node past each chain's last joint (VRM 1.0's last joint is only the end of the one before it, so
    without a tail the hem itself would not swing) and returns the VRMC_springBone extension.

    farthest(node, direction) gives the world position of the vertex the node mostly drives that lies farthest along
    direction, or None."""
    world = world_matrices(nodes)
    index = {n.get("name"): i for i, n in enumerate(nodes)}
    parent = {c: i for i, n in enumerate(nodes) for c in n.get("children", [])}

    def position(i):
        return world[i][:3, 3]

    def local(i, point):
        return [round(float(v), 6) for v in (np.linalg.inv(world[i]) @ np.r_[point, 1.0])[:3]]

    colliders, groups = [], {}

    def collider(names, node, shape):
        for group in names:
            groups.setdefault(group, []).append(len(colliders))
        colliders.append({"node": node, "shape": shape})

    for side in ("L", "R"):
        hip, knee, ankle = (index[f"Bip001_{side}_{bone}"] for bone in ("Thigh", "Calf", "Foot"))
        # Each capsule rides on the bone it measures along.
        down = (position(knee) - position(hip)) / np.linalg.norm(position(knee) - position(hip))
        back = np.array([0.0, 0.0, -1.0]) - down * -down[2]  # the model faces +Z
        back /= np.linalg.norm(back)
        for group, capsules in LEG_GROUPS.items():
            for top, bottom, radius, shift in capsules:
                tip = position(knee) if bottom is None else position(hip) + bottom * down
                collider((group,), hip, {"capsule": {"offset": local(hip, position(hip) + top * down + shift * back),
                                                     "radius": radius, "tail": local(hip, tip + shift * back)}})
        top, bottom, radius = CALF_CAPSULE
        shin = (position(ankle) - position(knee)) / np.linalg.norm(position(ankle) - position(knee))
        collider(tuple(LEG_GROUPS), knee, {"capsule": {"offset": local(knee, position(knee) + top * shin),
                                                       "radius": radius,
                                                       "tail": local(knee, position(knee) + bottom * shin)}})
    spine, neck, head = index["Bip001_Spine1"], index["Bip001_Neck"], index["Bip001_Head"]
    collider(("body",), spine, {"capsule": {"offset": [0.0, 0.0, 0.0], "radius": 0.1,
                                            "tail": local(spine, position(neck))}})
    collider(("head",), head, {"sphere": {"offset": local(head, position(head) + [0.0, 0.08, 0.0]), "radius": 0.075}})
    names = sorted(groups)

    springs, claimed, original = [], set(), len(nodes)
    for prefix, first, stiffness, gravity, drag, radius, hem_radius, collide in SPRINGS:
        roots = [i for i, n in enumerate(nodes[:original]) if n.get("name", "").startswith(prefix) and "mesh" not in n
                 and not nodes[parent[i]].get("name", "").startswith(prefix) and i not in claimed]
        claimed.update(roots)
        for root in roots:
            chain = [root]
            while True:
                children = [c for c in nodes[chain[-1]].get("children", [])
                            if nodes[c].get("name", "").startswith(prefix)]
                if not children:
                    break
                chain.append(children[0])
            if len(chain) <= first:
                continue
            last = chain[-1]
            direction = position(last) - position(chain[-2]) if len(chain) > 1 else np.array([0.0, -1.0, 0.0])
            direction /= np.linalg.norm(direction)
            far = farthest(last, direction)
            length = max(0.03, float((far - position(last)) @ direction)) if far is not None else 0.07
            tail = len(nodes)
            nodes.append({"name": nodes[last]["name"] + "_tail",
                          "translation": local(last, position(last) + direction * length)})
            nodes[last].setdefault("children", []).append(tail)
            joint = {"stiffness": stiffness, "gravityPower": gravity, "gravityDir": [0.0, -1.0, 0.0], "dragForce": drag}
            springs.append({"name": nodes[root]["name"],
                            "joints": [dict(joint, node=i, hitRadius=hem_radius if i == last else radius)
                                       for i in chain[first:] + [tail]],
                            "colliderGroups": [names.index(g) for g in collide]})
    return {"specVersion": "1.0", "colliders": colliders,
            "colliderGroups": [{"name": g, "colliders": groups[g]} for g in names], "springs": springs}


def npr_package(out, images, sources):
    """ENDFIELD_npr: NPR_PROFILE with every NPR_IMAGES file embedded as an extra glTF image ({"image": index})."""
    profile = json.loads(json.dumps(NPR_PROFILE))
    for place, source, name in NPR_IMAGES:
        path = os.path.join(sources[source], name)
        if not os.path.isfile(path):
            raise SystemExit(f"NPR texture not found: {path} (see --{source.replace('_', '-')})")
        payload = open(path, "rb").read()
        if not payload.startswith(b"\x89PNG"):
            raise SystemExit(f"NPR texture is not a PNG: {path}")
        images.append({"name": "npr_" + os.path.splitext(name)[0], "mimeType": "image/png",
                       "bufferView": out.view(payload)})
        target = profile
        for key in place[:-1]:
            target = target.setdefault(key, {}) if isinstance(key, str) else target[key]
        target[place[-1]] = {"image": len(images) - 1}
    return profile


def mesh_pieces(indices, count):
    """Connected pieces of a triangle list by shared vertex index: piece id per vertex."""
    parent = list(range(count))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    for a, b, c in indices.reshape(-1, 3):
        parent[find(int(b))] = find(int(a))
        parent[find(int(c))] = find(int(a))
    return np.array([find(v) for v in range(count)])


def boundary_edges(triangles):
    """Edges (vertex index pairs) used by exactly one triangle."""
    uses = {}
    for t in triangles:
        for k in range(3):
            edge = (min(t[k], t[(k + 1) % 3]), max(t[k], t[(k + 1) % 3]))
            uses[edge] = uses.get(edge, 0) + 1
    return [edge for edge, n in uses.items() if n == 1]


def seam_defects(positions, indices, joints=None, weights=None):
    """Across-piece seam defects within SEAM_SNAP: vertex pairs at different positions (or one position with different
    skins), and boundary vertices lying inside another piece's boundary edge (T-junctions)."""
    p = positions.astype(np.float64)
    triangles = indices.reshape(-1, 3)
    piece = mesh_pieces(indices, len(p))
    edges = boundary_edges(triangles)
    border = sorted({v for e in edges for v in e})
    near = 0
    for i, a in enumerate(border):
        for b in border[i + 1:]:
            if piece[a] == piece[b] or np.linalg.norm(p[a] - p[b]) > SEAM_SNAP:
                continue
            if not np.array_equal(p[a], p[b]) or (joints is not None and not (
                    np.array_equal(joints[a], joints[b]) and np.allclose(weights[a], weights[b]))):
                near += 1
    return near, len(t_junctions(p, piece, edges, border))


def t_junctions(p, piece, edges, border):
    """(edge, t, vertex): a boundary vertex of one piece inside another piece's boundary edge, within SEAM_SNAP."""
    found = []
    for a, b in edges:
        d = p[b] - p[a]
        length2 = float(d @ d)
        if length2 < 1e-14:
            continue
        for v in border:
            if piece[v] == piece[a] or np.array_equal(p[v], p[a]) or np.array_equal(p[v], p[b]):
                continue
            t = float((p[v] - p[a]) @ d) / length2
            if 0.0 < t < 1.0 and np.linalg.norm(p[a] + t * d - p[v]) <= SEAM_SNAP:
                found.append(((a, b), t, v))
    return found


def close_seams(arrays, indices):
    """Closes the seams between the pieces of one stitched mesh (face_01): boundary vertices of different pieces within
    SEAM_SNAP snap to one position (a main-piece vertex's where there is one, so the face silhouette stays), and a
    boundary vertex lying inside another piece's boundary edge splits that edge's triangle at the vertex's position (a
    T-junction opens a crack under rasterization even when it lies exactly on the edge). Normals and UVs stay as
    authored; a new vertex interpolates its edge's normal and UV and takes the T-vertex's skin."""
    arrays = {name: a.copy() for name, a in arrays.items()}
    p = arrays["POSITION"]
    triangles = indices.reshape(-1, 3).astype(np.int64)
    piece = mesh_pieces(indices, len(p))
    main = np.bincount(piece).argmax()
    border = sorted({v for e in boundary_edges(triangles) for v in e})
    # 1. snap: clusters of across-piece boundary vertices within SEAM_SNAP
    parent = {v: v for v in border}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    q = p.astype(np.float64)
    for i, a in enumerate(border):
        for b in border[i + 1:]:
            if piece[a] != piece[b] and np.linalg.norm(q[a] - q[b]) <= SEAM_SNAP:
                parent[find(b)] = find(a)
    clusters = {}
    for v in border:
        clusters.setdefault(find(v), []).append(v)
    snapped = 0
    for members in clusters.values():
        if len(members) < 2:
            continue
        anchor = [v for v in members if piece[v] == main] or members
        target = p[anchor[0]].copy()
        for v in members:
            if not np.array_equal(p[v], target):
                p[v] = target
                snapped += 1
    # 2. T-junctions: split the edge's triangle at each vertex inside it, until none is left
    split = 0
    for _ in range(8):
        q = p.astype(np.float64)
        found = t_junctions(q, piece, boundary_edges(triangles),
                            sorted({v for e in boundary_edges(triangles) for v in e}))
        if not found:
            break
        per_edge = {}
        for edge, t, v in found:
            per_edge.setdefault(edge, {})[v] = t
        keep = np.ones(len(triangles), dtype=bool)
        added_triangles = []
        for (a, b), hits in per_edge.items():
            for ti, tri in enumerate(triangles):
                if not keep[ti]:
                    continue
                ks = [k for k in range(3) if {tri[k], tri[(k + 1) % 3]} == {a, b}]
                if not ks:
                    continue
                k = ks[0]
                e0, e1, c = tri[k], tri[(k + 1) % 3], tri[(k + 2) % 3]
                chain = [e0]
                placed = []
                for v, t in sorted(hits.items(), key=lambda kv: kv[1] if e0 == a else 1.0 - kv[1]):
                    if any(np.array_equal(p[v], p[w]) for w in placed):
                        continue  # one split per position (coincident T-vertices share it)
                    placed.append(v)
                    t0 = t if e0 == a else 1.0 - t  # along e0 -> e1
                    new = len(p)
                    for name, array in arrays.items():
                        if name in ("POSITION", "JOINTS_0", "WEIGHTS_0"):
                            row = array[v]
                        else:
                            row = (array[e0].astype(np.float64) * (1.0 - t0) + array[e1].astype(np.float64) * t0)
                            if name == "NORMAL":
                                row = row / max(np.linalg.norm(row), 1e-12)
                            row = row.astype(array.dtype)
                        arrays[name] = np.vstack([array, row[None, :]])
                    p = arrays["POSITION"]
                    piece = np.append(piece, piece[e0])
                    chain.append(new)
                    split += 1
                chain.append(e1)
                keep[ti] = False
                added_triangles += [(chain[i], chain[i + 1], c) for i in range(len(chain) - 1)]
                break
        triangles = np.vstack([triangles[keep], np.array(added_triangles, dtype=np.int64)])
    print(f"seams closed: {snapped} vertices snapped, {split} T-junction splits")
    return arrays, triangles.reshape(-1).astype(indices.dtype if len(arrays["POSITION"]) < 65536 else np.uint32)


def weld_seam_skin(positions, joints, weights):
    """Gives vertices at bit-identical positions one skin: the mean of their weights per joint, the four strongest
    renormalized. Positions, normals and UVs stay as authored; only the seam's two sides now deform together."""
    groups = {}
    for v in range(len(positions)):
        groups.setdefault(positions[v].tobytes(), []).append(v)
    joints = joints.copy()
    weights = weights.copy()
    changed = 0
    for members in groups.values():
        if len(members) < 2:
            continue
        total = {}
        for v in members:
            for j, w in zip(joints[v], weights[v].astype(np.float64)):
                if w > 0:
                    total[int(j)] = total.get(int(j), 0.0) + w / len(members)
        top = sorted(total.items(), key=lambda kv: -kv[1])[:4]
        norm = sum(w for _, w in top)
        row_j = np.zeros(4, dtype=joints.dtype)
        row_w = np.zeros(4, dtype=np.float64)
        for k, (j, w) in enumerate(top):
            row_j[k] = j
            row_w[k] = w / norm
        for v in members:
            if not (np.array_equal(joints[v], row_j) and np.allclose(weights[v], row_w, atol=1e-6)):
                changed += 1
            joints[v] = row_j
            weights[v] = row_w.astype(weights.dtype)
    print(f"seam skin welded on {changed} coincident vertices")
    return {"JOINTS_0": joints, "WEIGHTS_0": weights}


def convert(source_path, target_path, npr_sources, npr_export=npr_materials.DEFAULT_EXPORT):
    gltf, binary = load_glb(source_path)
    nodes = gltf["nodes"]
    meshes = gltf["meshes"]
    if len(gltf.get("skins", [])) != 1:
        raise SystemExit("expected exactly one skin")

    # Which meshes, materials and textures are written. Every node and mesh is kept; the overlay cards get their own
    # transparent stand-in material, so the base colour textures their source materials borrowed are not counted.
    used_meshes = sorted({n["mesh"] for n in nodes if "mesh" in n})
    if used_meshes != list(range(len(meshes))):
        raise SystemExit("every mesh is expected to be used by exactly the nodes that carry it")
    card_of_material = {p["material"]: meshes[m]["name"] for m in used_meshes
                        if meshes[m].get("name") in OVERLAY_CARDS for p in meshes[m]["primitives"]}
    used_materials = sorted({p["material"] for m in used_meshes for p in meshes[m]["primitives"] if "material" in p})
    material_map = {old: new for new, old in enumerate(used_materials)}
    used_textures = sorted({gltf["materials"][m]["pbrMetallicRoughness"]["baseColorTexture"]["index"]
                            for m in used_materials if m not in card_of_material
                            and "baseColorTexture" in gltf["materials"][m].get("pbrMetallicRoughness", {})})
    texture_map = {old: new for new, old in enumerate(used_textures)}
    used_images = sorted({gltf["textures"][t]["source"] for t in used_textures})
    image_map = {old: new for new, old in enumerate(used_images)}

    out = Writer()

    # 1. Mirrored skeleton (mesh nodes carry identity transforms and stay as they are).
    new_nodes = [mirror_node(node) for node in nodes]

    skin = gltf["skins"][0]
    ibm = read_accessor(gltf, binary, skin["inverseBindMatrices"]).reshape(-1, 4, 4)  # column-major storage
    mirror = np.diag([-1.0, 1.0, 1.0, 1.0])
    ibm_mirrored = np.einsum("ij,njk,kl->nil", mirror, ibm.transpose(0, 2, 1).astype(np.float64), mirror)
    new_skin = {"joints": list(skin["joints"]),
                "inverseBindMatrices": out.accessor(
                    ibm_mirrored.transpose(0, 2, 1).reshape(-1, 16).astype("<f4"))}
    if "skeleton" in skin:
        new_skin["skeleton"] = skin["skeleton"]

    # 2./3. Meshes: tiled UVs folded back into [0, 1], overlay card UVs moved inside their stand-in texture.
    new_meshes = []
    for old in used_meshes:
        mesh = meshes[old]
        primitives = []
        for primitive in mesh["primitives"]:
            attributes = {}
            arrays = {name: read_accessor(gltf, binary, index) for name, index in primitive["attributes"].items()}
            indices = read_accessor(gltf, binary, primitive["indices"]).reshape(-1)
            if mesh.get("name") in SEAM_WELD_MESHES:
                arrays, indices = close_seams(arrays, indices)
                arrays.update(weld_seam_skin(arrays["POSITION"], arrays["JOINTS_0"], arrays["WEIGHTS_0"]))
            for name, array in arrays.items():
                if name == "TEXCOORD_0" and mesh.get("name") in TILED_MESHES:
                    array = fold_tiles(array, indices.reshape(-1, 3))
                if name == "TEXCOORD_0" and mesh.get("name") in OVERLAY_CARDS:
                    array = (CARD_INSET + array.astype(np.float64) * (1.0 - 2.0 * CARD_INSET)).astype("<f4")
                attributes[name] = out.accessor(array, 34962, bounds=(name == "POSITION"))
            new_primitive = {"attributes": attributes,
                             "indices": out.accessor(indices, 34963),
                             "mode": primitive.get("mode", 4)}
            if "material" in primitive:
                new_primitive["material"] = material_map[primitive["material"]]
            primitives.append(new_primitive)
        new_meshes.append({"name": mesh.get("name"), "primitives": primitives})

    # 4. Materials, textures and embedded images. Every material is OPAQUE except the overlay cards: a MASK material
    #    over a fully transparent stand-in texture of their own, which YSM's own shader and shader packs discard.
    new_images = []
    for old in used_images:
        image = gltf["images"][old]
        new_images.append({"name": image.get("name", f"image{old}"), "mimeType": image.get("mimeType", "image/png"),
                           "bufferView": out.view(view_bytes(gltf, binary, image["bufferView"]))})
    new_textures = [{"source": image_map[gltf["textures"][t]["source"]], "sampler": 0} for t in used_textures]
    new_materials = []
    for old in used_materials:
        material = json.loads(json.dumps(gltf["materials"][old]))
        pbr = material.setdefault("pbrMetallicRoughness", {})
        if old in card_of_material:
            # One image per card: YSM's atlas shares a region between materials with the same texture, and the
            # render mod tells the cards apart by their regions.
            card = card_of_material[old]
            new_images.append({"name": f"{card}_standin", "mimeType": "image/png",
                               "bufferView": out.view(transparent_png(CARD_TEXTURE_SIZE))})
            new_textures.append({"source": len(new_images) - 1, "sampler": 0})
            pbr["baseColorTexture"] = {"index": len(new_textures) - 1}
            pbr["baseColorFactor"] = [1.0, 1.0, 1.0, 1.0]
            material["alphaMode"] = "MASK"
            material["alphaCutoff"] = 0.5
        else:
            material["alphaMode"] = "OPAQUE"
            material.pop("alphaCutoff", None)
            if "baseColorTexture" in pbr:
                pbr["baseColorTexture"] = {"index": texture_map[pbr["baseColorTexture"]["index"]]}
        new_materials.append(material)

    # 5. VRM 1.0 humanoid and meta.
    names = {n.get("name"): i for i, n in enumerate(new_nodes)}
    missing = sorted(v for v in HUMANOID.values() if v not in names)
    if missing:
        raise SystemExit("humanoid bones not found: " + ", ".join(missing))
    vrm = {
        "specVersion": "1.0",
        "meta": {
            "name": "Pelica",
            "version": "local-test",
            "authors": ["Hypergryph"],
            "copyrightInformation": "Arknights: Endfield (c) Hypergryph. Local research only, do not distribute.",
            "licenseUrl": "https://vrm.dev/licenses/1.0/",
            "avatarPermission": "onlyAuthor",
            "allowExcessivelyViolentUsage": False,
            "allowExcessivelySexualUsage": False,
            "commercialUsage": "personalNonProfit",
            "allowPoliticalOrReligiousUsage": False,
            "allowAntisocialOrHateUsage": False,
            "creditNotation": "required",
            "allowRedistribution": False,
            "modification": "prohibited",
        },
        "humanoid": {"humanBones": {bone: {"node": names[node]} for bone, node in HUMANOID.items()}},
    }
    # Secondary motion, measured on the finished (mirrored) skeleton and the meshes as they are written (the overlay
    # cards are not part of the silhouette the chains are fitted to).
    rest_positions, rest_owners = [], []
    for old in used_meshes:
        if meshes[old].get("name") in OVERLAY_CARDS:
            continue
        for primitive in meshes[old]["primitives"]:
            a = primitive["attributes"]
            joints = read_accessor(gltf, binary, a["JOINTS_0"]).astype(int)
            weights = read_accessor(gltf, binary, a["WEIGHTS_0"]).astype(float)
            rest_positions.append(read_accessor(gltf, binary, a["POSITION"]).astype(np.float64))
            rest_owners.append(np.array(new_skin["joints"])[joints[np.arange(len(joints)), weights.argmax(axis=1)]])
    rest_positions, rest_owners = np.concatenate(rest_positions), np.concatenate(rest_owners)

    def farthest(node, direction):
        points = rest_positions[rest_owners == node]
        return points[np.argmax(points @ direction)] if len(points) else None

    springs = spring_bones(new_nodes, farthest)
    roots = list(gltf["scenes"][gltf.get("scene", 0)]["nodes"])
    package = npr_package(out, new_images, npr_sources)
    document = {
        "asset": {"version": "2.0", "generator": "fix_pelica.py"},
        "extensionsUsed": ["VRMC_vrm", "VRMC_springBone", NPR_EXTENSION],
        "extensions": {"VRMC_vrm": vrm, "VRMC_springBone": springs, NPR_EXTENSION: package},
        "scene": 0,
        "scenes": [{"nodes": roots}],
        "nodes": new_nodes,
        "meshes": new_meshes,
        "skins": [new_skin],
        "materials": new_materials,
        "textures": new_textures,
        "samplers": [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}],
        "images": new_images,
        "accessors": out.accessors,
        "bufferViews": out.views,
        "buffers": [{"byteLength": len(out.blob)}],
    }
    blob, report = npr_materials.apply(document, bytes(out.blob), "pelica", npr_export)
    print(npr_materials.summary_line(dict(report, size_before=len(out.blob), size_after=len(blob))))
    check(document, bytes(blob))
    write_glb(target_path, document, bytes(blob))


def fold_tiles(uv, triangles):
    """Moves every triangle of a repeat-wrapped tile back into [0, 1] in u; refuses triangles that cross a seam."""
    tile = np.floor(uv[:, 0])
    per_triangle = tile[triangles]
    if (per_triangle.max(axis=1) != per_triangle.min(axis=1)).any():
        raise SystemExit("a triangle crosses an integer u; folding per vertex would tear it")
    if (np.floor(uv[:, 1]) != 0).any():
        raise SystemExit("v outside [0, 1) is not handled")
    folded = uv.copy()
    folded[:, 0] -= tile
    return folded


def check(document, blob):
    """Re-reads the output and asserts the properties the fixes promise."""
    nodes = document["nodes"]
    world = world_matrices(nodes)
    skin = document["skins"][0]
    ibm = read_accessor(document, blob, skin["inverseBindMatrices"]).reshape(-1, 4, 4).transpose(0, 2, 1)
    worst = max(np.abs(world[j] @ ibm[k] - np.eye(4)).max() for k, j in enumerate(skin["joints"]))
    if worst > 1e-4:
        raise SystemExit(f"rest pose changed: |W*IBM - I| = {worst}")

    # Every joint off the midline must sit on the same side as the vertices it mostly drives.
    driven = {}
    for node in nodes:
        if "mesh" not in node:
            continue
        for primitive in document["meshes"][node["mesh"]]["primitives"]:
            a = primitive["attributes"]
            position = read_accessor(document, blob, a["POSITION"])
            joints = read_accessor(document, blob, a["JOINTS_0"]).astype(int)
            weights = read_accessor(document, blob, a["WEIGHTS_0"]).astype(float)
            dominant = joints[np.arange(len(joints)), weights.argmax(axis=1)]
            for k in np.unique(dominant):
                driven.setdefault(int(k), []).append(position[dominant == k])
            uv = read_accessor(document, blob, a["TEXCOORD_0"])
            if uv.min() < 0 or uv.max() > 1:
                raise SystemExit(f"UV outside [0, 1] in {document['meshes'][node['mesh']]['name']}")
    same = opposite = 0
    for k, parts in driven.items():
        points = np.concatenate(parts)
        joint_x = world[skin["joints"][k]][0, 3]
        centre_x = points[:, 0].mean()
        if len(points) >= 20 and abs(joint_x) > 0.03 and abs(centre_x) > 0.03:
            if np.sign(joint_x) == np.sign(centre_x):
                same += 1
            else:
                opposite += 1
    if opposite:
        raise SystemExit(f"{opposite} joints still sit opposite their vertices")

    humanoid = document["extensions"]["VRMC_vrm"]["humanoid"]["humanBones"]
    left = world[humanoid["leftUpperLeg"]["node"]][0, 3]
    right = world[humanoid["rightUpperLeg"]["node"]][0, 3]
    face = [m for m in document["meshes"] if m["name"] == "face_01"][0]
    face_z = read_accessor(document, blob, face["primitives"][0]["attributes"]["POSITION"])[:, 2].max()
    head_z = world[humanoid["head"]["node"]][2, 3]
    print(f"rest pose kept (max |W*IBM - I| = {worst:.2e}); {same} off-midline joints on their vertices' side, "
          f"0 opposite")
    print(f"left thigh x = {left:+.4f}, right thigh x = {right:+.4f}; face reaches z = {face_z:+.3f} "
          f"(head pivot z = {head_z:+.3f}): faces +Z with its left at +X, as glTF/VRM 1.0 expects")
    print(f"meshes: {', '.join(m['name'] for m in document['meshes'])}")
    for mesh in document["meshes"]:
        if mesh["name"] not in OVERLAY_CARDS:
            continue
        primitive = mesh["primitives"][0]
        material = document["materials"][primitive["material"]]
        uv = read_accessor(document, blob, primitive["attributes"]["TEXCOORD_0"])
        if material["alphaMode"] != "MASK" or uv.min() < CARD_INSET or uv.max() > 1 - CARD_INSET:
            raise SystemExit(f"overlay card {mesh['name']} is not a transparent inset card")
        image = document["images"][document["textures"][material["pbrMetallicRoughness"]["baseColorTexture"]
                                                        ["index"]]["source"]]
        print(f"overlay card {mesh['name']}: MASK over {image['name']}, uv {uv.min(axis=0).round(3)} .. "
              f"{uv.max(axis=0).round(3)}")

    # The NPR package: every image it names is an embedded PNG, every material it names is used by a mesh.
    package = document["extensions"][NPR_EXTENSION]
    used = {document["materials"][p["material"]]["name"] for m in document["meshes"] for p in m["primitives"]}
    references = []

    def walk(value):
        if isinstance(value, dict):
            if "material" in value and value["material"] not in used:
                raise SystemExit(f"{NPR_EXTENSION} names material {value['material']}, which no mesh uses")
            if "image" in value:
                image = document["images"][value["image"]]
                view = document["bufferViews"][image["bufferView"]]
                if blob[view["byteOffset"]:view["byteOffset"] + 4] != b"\x89PNG":
                    raise SystemExit(f"{NPR_EXTENSION} image {value['image']} is not a PNG")
                references.append(image["name"])
            for item in value.values():
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)

    walk(package)
    print(f"{NPR_EXTENSION}: {len(references)} images, {len(package['overlays'])} overlay layers, face "
          f"{package['face']['material']}, iris {package['iris']['material']}")


def write_glb(path, document, blob):
    text = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    text += b" " * (-len(text) % 4)
    blob += b"\0" * (-len(blob) % 4)
    total = 12 + 8 + len(text) + 8 + len(blob)
    with open(path, "wb") as f:
        f.write(struct.pack("<4sII", b"glTF", 2, total))
        f.write(struct.pack("<II", len(text), 0x4E4F534A))
        f.write(text)
        f.write(struct.pack("<II", len(blob), 0x004E4942))
        f.write(blob)
    print(f"wrote {path} ({total / 1e6:.1f} MB)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("input")
    parser.add_argument("output")
    parser.add_argument("--textures", default=NPR_TEXTURE_DIR, help="ramps and skin LUT (T_actor_common_*)")
    parser.add_argument("--face-set", default=NPR_FACE_SET_DIR, help="face SDF, SDF mask, highlight map, matcap")
    parser.add_argument("--overlays", default=NPR_OVERLAY_DIR, help="OverlayShadow _BaseMap textures")
    parser.add_argument("--npr-export", default=os.environ.get("NPR_EXPORT", npr_materials.DEFAULT_EXPORT),
                        help="the user's export (bindings.json, textures/, materials/) for ENDFIELD_npr.materials")
    args = parser.parse_args()
    convert(args.input, args.output, {"textures": args.textures, "face_set": args.face_set,
                                      "overlays": args.overlays}, args.npr_export)
