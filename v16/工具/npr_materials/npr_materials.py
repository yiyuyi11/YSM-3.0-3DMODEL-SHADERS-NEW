#!/usr/bin/env python3
"""Write the ENDFIELD_npr `materials` block (per-material maps and Unity parameters) into a VRM from the user's export.

Contract: ~/ysm/handoff/npr-materials-schema.md. For every glTF material that triangles use:
  ysm-materials.json[role][name].material (the Unity material, case-insensitive) -> bindings.json rows and
  materials/<role>/<material>.json. Each binding with status "found" and use_flag != 0 becomes maps[kind]
  ({"image": i, "scale", "offset"} with the Unity _ST tiling, omitted when 1,1 / 0,0); params holds every m_Floats and
  m_Colors entry verbatim. Iris/eye materials also get maps.baseAlpha: the alpha channel of their own embedded base
  colour image, as a grey PNG (YSM's atlas is opaque, so it is lost otherwise). Materials with nothing to add are left
  out. A Unity material the role's export lacks is borrowed from another role of the export that has it (bounda's
  body_01 is M_actor_chen_body_01).

Images are new glTF images (bufferViews appended to the GLB BIN chunk), one per (texture, size), downscaled to --cap
per side with an area filter applied per channel (no premultiplied alpha: colours under alpha 0 survive), PNG
encoded and checked pixel-exact after decoding. Everything this tool adds carries extras.endfield_npr_materials;
running it again removes those images and views and the previous block first, so the result does not grow and is
byte-identical for the same input. All other JSON (key order included) and BIN bytes stay as they were.

--fix-format1 also checks the format-1 keys against the export (iris matcap, skinLut, clothRS, overlay textures),
replaces what the export can correct (clothRS from the --ramps folder), and logs every finding.

Usage:
  npr_materials.py IN.vrm OUT.vrm [--role ROLE] [--export DIR] [--cap 1024] [--filter box|lanczos]
                   [--fix-format1 [--ramps DIR]] [--oxipng LEVEL] [--report R.json]
  npr_materials.py --batch MODELS_DIR OUT_DIR [--only ROLE ...] [same options]   (MODELS_DIR/<role>/<role>.vrm)
"""

import argparse
import io
import json
import os
import re
import shutil
import struct
import sys

import numpy as np
from PIL import Image

EXTENSION = "ENDFIELD_npr"
MARKER = "endfield_npr_materials"
DEFAULT_EXPORT = os.path.expanduser("~/ysm/efdata/props")
DEFAULT_CAP = 1024
DEFAULT_CAP_HAIR_PROP = 0  # hair (_AnisotropyIntensity) prop maps: 0 = original size
DEFAULT_RAMPS = os.path.expanduser("~/ysm/pelica/07-贴图素材")
# Ramp path ids -> file in --ramps, for --fix-format1. Ramps are not exported; the user's 24 models name them: every
# model whose enabled cloth spec ramps are only 4523718382697154697 embeds t_actor_common_cloth_04_rs as clothRS (9 of
# 9; Pelica's cloth_02_RS is a hand-tuned choice), pixel-identical to T_actor_common_cloth_04_RS.png; whiten (only
# 2174236443485968244) embeds t_actor_common_cloth_02_rs.
RAMP_FILES = {4523718382697154697: "T_actor_common_cloth_04_RS.png",
              2174236443485968244: "T_actor_common_cloth_02_RS.png"}
# Unity slot -> kind, in the order of each material's maps. line and parallax are tiled (_ST up to 30x30, offsets),
# the rest atlas-aligned; all of them are capped at --cap per side (tiled maps are at most 2048, i.e. 1024 by default).
SLOTS = {
    "_MetallicGlossMap": "prop",
    "_EmissionMap": "emission",
    "_LineMap": "line",
    "_BumpMap": "normal",
    "_SplitNormalMap": "splitNormal",
    "_OutlineMask": "outlineMask",
    "_HairBrowMask": "browMask",
    "_ParallaxTex": "parallax",
}
BASE_ALPHA = "baseAlpha"
EYE_MATERIAL = re.compile(r"^(iris|eye)_\d+$", re.IGNORECASE)  # not eyebrow_* / eyeshadow_* (cards, face texture)
FILTERS = {"box": Image.Resampling.BOX, "lanczos": Image.Resampling.LANCZOS}
JSON_CHUNK, BIN_CHUNK = 0x4E4F534A, 0x004E4942


# ---------------------------------------------------------------------------------------------------------- GLB I/O

def read_glb(data):
    """(document, blob, other chunks) of a GLB; blob is the BIN chunk cut to buffers[0].byteLength."""
    magic, version, length = struct.unpack_from("<4sII", data, 0)
    if magic != b"glTF" or version != 2:
        raise ValueError("not a glTF 2.0 binary")
    offset, document, blob, others = 12, None, b"", []
    while offset < length:
        size, kind = struct.unpack_from("<II", data, offset)
        chunk = data[offset + 8:offset + 8 + size]
        if kind == JSON_CHUNK:
            document = json.loads(chunk.decode("utf-8"))
        elif kind == BIN_CHUNK and not blob:
            blob = chunk
        else:
            others.append((kind, chunk))
        offset += 8 + size
    if others:
        raise ValueError("unexpected extra GLB chunks")
    buffers = document.get("buffers", [])
    if buffers and "uri" not in buffers[0]:
        blob = blob[:buffers[0]["byteLength"]]
    return document, bytearray(blob)


def write_glb(document, blob):
    """GLB bytes, written like fix_pelica.write_glb (compact JSON, space/zero padding)."""
    text = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    text += b" " * (-len(text) % 4)
    blob = bytes(blob) + b"\0" * (-len(blob) % 4)
    total = 12 + 8 + len(text) + 8 + len(blob)
    return (struct.pack("<4sII", b"glTF", 2, total) + struct.pack("<II", len(text), JSON_CHUNK) + text
            + struct.pack("<II", len(blob), BIN_CHUNK) + blob)


def image_bytes(document, blob, index):
    view = document["bufferViews"][document["images"][index]["bufferView"]]
    start = view.get("byteOffset", 0)
    return bytes(blob[start:start + view["byteLength"]])


# ------------------------------------------------------------------------------------------------------------ export

class Export:
    """The user's export directory (bindings.json, textures/<path_id>/, materials/<role>/, ysm-materials.json)."""

    def __init__(self, root=DEFAULT_EXPORT):
        self.root = os.path.expanduser(root)
        with open(os.path.join(self.root, "bindings.json"), encoding="utf-8") as f:
            self.bindings = json.load(f)
        with open(os.path.join(self.root, "ysm-materials.json"), encoding="utf-8") as f:
            self.ysm = json.load(f)
        self.rows = {}
        for row in self.bindings:
            self.rows.setdefault((row["role"], row["material"].lower()), []).append(row)
        self.files = {}
        materials = os.path.join(self.root, "materials")
        for role in sorted(os.listdir(materials)):
            for name in sorted(os.listdir(os.path.join(materials, role))):
                if name.endswith(".json"):
                    self.files[(role, name[:-5].lower())] = os.path.join(materials, role, name)
        self._json = {}

    def unity_name(self, role, gltf_name):
        table = self.ysm.get(role, {})
        entry = table.get(gltf_name)
        if entry is None:
            entry = next((v for k, v in table.items() if k.lower() == gltf_name.lower()), None)
        return entry and entry.get("material")

    def material(self, role, unity):
        """(role it came from, material JSON, binding rows) of a Unity material, borrowing from another role."""
        key = unity.lower()
        for source in [role] + sorted({r for r, _ in self.files} - {role}):
            if (source, key) in self.files:
                path = self.files[(source, key)]
                if path not in self._json:
                    with open(path, encoding="utf-8") as f:
                        self._json[path] = json.load(f)
                return source, self._json[path], self.rows.get((source, key), [])
        return None, None, []

    def texture(self, path_id):
        folder = os.path.join(self.root, "textures", str(path_id))
        names = sorted(os.listdir(folder)) if os.path.isdir(folder) else []
        if len(names) != 1:
            raise FileNotFoundError(f"export texture {path_id}: expected one file in {folder}, found {names}")
        return os.path.join(folder, names[0])


# ------------------------------------------------------------------------------------------------------------ images

def downscale(image, cap, resample):
    """Image scaled to fit cap x cap (aspect kept), each channel on its own: Pillow's RGBA resize premultiplies."""
    w, h = image.size
    scale = min(1.0, cap / w, cap / h)
    if scale >= 1.0:
        return image
    size = (max(1, round(w * scale)), max(1, round(h * scale)))
    return Image.merge(image.mode, [band.resize(size, resample) for band in image.split()])


def encode_png(image, oxipng_level=0):
    """PNG of image, verified to decode to exactly the same texels. oxipng_level 1-6 recompresses it losslessly with
    oxipng (pip install pyoxipng), which may also store fewer channels (RGB for an opaque RGBA map, grey for equal
    RGB): the renderer and YSM decode every PNG to RGBA, so that check compares RGBA; without oxipng the mode must
    match too."""
    out = io.BytesIO()
    image.save(out, "PNG", compress_level=9)
    payload = out.getvalue()
    if oxipng_level:
        payload = oxipng_module().optimize_from_memory(payload, level=oxipng_level)
    back = Image.open(io.BytesIO(payload))
    back.load()
    if oxipng_level:
        same = np.array_equal(np.asarray(back.convert("RGBA")), np.asarray(image.convert("RGBA")))
    else:
        same = back.mode == image.mode and np.array_equal(np.asarray(back), np.asarray(image))
    if not same:
        raise RuntimeError("PNG round trip changed the pixels")
    return payload


def oxipng_module():
    try:
        import oxipng
    except ImportError:
        raise SystemExit("--oxipng needs the pyoxipng package (pip install pyoxipng)") from None
    return oxipng


def load_rgba(path_or_bytes):
    image = Image.open(path_or_bytes if isinstance(path_or_bytes, str) else io.BytesIO(path_or_bytes))
    image.load()
    if image.mode not in ("RGBA", "RGB", "LA", "L"):
        image = image.convert("RGBA")  # palette / 16-bit sources: expand without touching colour under alpha
    return image


# ------------------------------------------------------------------------------------------------------- the update

class Patch:
    def __init__(self, document, blob, log, oxipng_level=0):
        self.document, self.blob, self.log = document, blob, log
        self.oxipng_level = oxipng_level
        self.cache = {}

    def encode(self, image):
        """PNG of an image this run adds (encode_png with the run's --oxipng level)."""
        return encode_png(image, self.oxipng_level)

    def strip(self):
        """Removes everything a previous run added: the materials block, marked images and views; format-1 keys that
        --fix-format1 pointed at an added image get their old image back ("replaces") or are deleted ("added")."""
        doc = self.document
        package = doc.get("extensions", {}).get(EXTENSION)
        if package is not None:
            package.pop("materials", None)
        images = doc.get("images", [])
        removed = {i for i, im in enumerate(images) if MARKER in im.get("extras", {})}
        views = doc.get("bufferViews", [])
        dead_views = {i for i, v in enumerate(views) if MARKER in v.get("extras", {})}
        if not removed and not dead_views:
            return 0
        markers = {i: images[i]["extras"][MARKER] for i in removed}
        restore = {i: m.get("replaces") if isinstance(m, dict) else None for i, m in markers.items()}
        added = {i for i, m in markers.items() if isinstance(m, dict) and m.get("added")}
        image_map, n = {}, 0
        for i in range(len(images)):
            if i not in removed:
                image_map[i] = n
                n += 1

        def remap_image(i, where):
            while i in removed:
                if restore.get(i) is None:
                    raise ValueError(f"{where} still refers to image {i} that a previous run added")
                i = restore[i]
            return image_map[i]

        for t in doc.get("textures", []):
            if "source" in t:
                t["source"] = remap_image(t["source"], "a texture")
        meta = doc.get("extensions", {}).get("VRMC_vrm", {}).get("meta", {})
        if "thumbnailImage" in meta:
            meta["thumbnailImage"] = remap_image(meta["thumbnailImage"], "VRMC_vrm.meta.thumbnailImage")

        def walk(value):
            if isinstance(value, dict):
                for key in [k for k, v in value.items() if isinstance(v, dict) and v.get("image") in added]:
                    del value[key]
                if isinstance(value.get("image"), int):
                    value["image"] = remap_image(value["image"], EXTENSION)
                for item in value.values():
                    walk(item)
            elif isinstance(value, list):
                for item in value:
                    walk(item)

        if package is not None:
            walk(package)
        doc["images"] = [im for i, im in enumerate(images) if i not in removed]

        # Views: they were appended last; anything else keeps its index and bytes.
        keep = [i for i in range(len(views)) if i not in dead_views]
        if dead_views and min(dead_views) < max(keep, default=-1):
            raise ValueError("buffer views added by a previous run are not the last ones; refusing to renumber")
        if dead_views:
            start = min(views[i].get("byteOffset", 0) for i in dead_views)
            if any(views[i].get("byteOffset", 0) + views[i]["byteLength"] > start for i in keep):
                raise ValueError("buffer data added by a previous run is not at the end of the buffer")
            # Back to the length before the first added view (its alignment padding included).
            marks = [views[i]["extras"][MARKER] for i in dead_views]
            del self.blob[min([m["after"] for m in marks if isinstance(m, dict)] + [start]):]
        doc["bufferViews"] = [views[i] for i in keep]
        doc["buffers"][0]["byteLength"] = len(self.blob)
        self.log(f"removed {len(removed)} images and {len(dead_views)} buffer views of a previous run")
        return len(removed)

    def add_image(self, key, name, make, replaces=None, added=False):
        """glTF image index of a PNG made by make() (bytes), created once per key. replaces/added: see strip()."""
        if key in self.cache:
            return self.cache[key]
        payload = make()
        after = len(self.blob)
        while len(self.blob) % 4:
            self.blob.append(0)
        views = self.document.setdefault("bufferViews", [])
        views.append({"buffer": 0, "byteOffset": len(self.blob), "byteLength": len(payload),
                      "extras": {MARKER: {"after": after}}})
        self.blob += payload
        self.document["buffers"][0]["byteLength"] = len(self.blob)
        marker = {"replaces": replaces} if replaces is not None else {"added": True} if added else True
        images = self.document.setdefault("images", [])
        images.append({"name": name, "mimeType": "image/png", "bufferView": len(views) - 1,
                       "extras": {MARKER: marker}})
        self.cache[key] = len(images) - 1
        return len(images) - 1


def used_materials(document):
    """Indices of materials that triangles of node-referenced meshes use, in material order."""
    meshes = {n["mesh"] for n in document.get("nodes", []) if "mesh" in n}
    used = set()
    for m in meshes:
        for p in document["meshes"][m]["primitives"]:
            if "material" in p and p.get("mode", 4) in (4, 5, 6):
                used.add(p["material"])
    return sorted(used)


def unity_params(material):
    params = {}
    for entry in material.get("m_Floats", []):
        params[entry["Key"]] = entry["Value"]
    for entry in material.get("m_Colors", []):
        v = entry["Value"]
        params[entry["Key"]] = [v["R"], v["G"], v["B"], v["A"]]
    return params


def tiling(row):
    out = {}
    scale, offset = [row["scale"]["X"], row["scale"]["Y"]], [row["offset"]["X"], row["offset"]["Y"]]
    if scale != [1, 1]:
        out["scale"] = scale
    if offset != [0, 0]:
        out["offset"] = offset
    return out


def apply(document, blob, role, export, cap=DEFAULT_CAP, resample="box", fix_format1=False, ramps=DEFAULT_RAMPS,
          log=print, cap_hair_prop=DEFAULT_CAP_HAIR_PROP, oxipng_level=0):
    """Writes the materials block into (document, blob) in place; returns (blob, report)."""
    if isinstance(export, str):
        export = Export(export)
    if role not in export.ysm:
        raise ValueError(f"role '{role}' is not in the export's ysm-materials.json (pass --role)")
    filt = FILTERS[resample]
    if not document.get("buffers") or "uri" in document["buffers"][0]:
        if document.get("buffers"):
            raise ValueError("the first buffer is external; only GLB-embedded buffers are supported")
        document["buffers"] = [{"byteLength": 0}]
    patch = Patch(document, bytearray(blob), log, oxipng_level)
    patch.strip()
    extensions = document.setdefault("extensions", {})
    package = extensions.get(EXTENSION)
    if package is None:
        package = extensions[EXTENSION] = {}
        document.setdefault("extensionsUsed", []).append(EXTENSION)
        log(f"warning: the VRM had no {EXTENSION} (format 1); writing materials only")

    report = {"role": role, "materials": {}, "skipped": {}, "maps_by_kind": {}, "unmatched_bindings": [],
              "borrowed": {}, "format1": []}

    def export_png(path_id, limit):
        def make():
            image = load_rgba(export.texture(path_id))
            return patch.encode(downscale(image, limit, filt) if limit else image)
        return make

    def map_rows(rows, params):
        """(kind, binding row, size cap) of every map a material gets."""
        for slot, kind in SLOTS.items():
            row = next((r for r in rows if r["slot"] == slot), None)
            if row is None or row["status"] != "found" or row["use_flag"] == 0:
                continue
            # Hair gloss maps stay at full size: the renderer's gloss-preserving power mean needs the original
            # texels (a box pre-pass loses 10-15 % of the highlight energy).
            yield kind, row, cap_hair_prop if kind == "prop" and "_AnisotropyIntensity" in params else cap

    mats = document.get("materials", [])
    lookups = {}
    for index in used_materials(document):
        name = mats[index].get("name")
        if name is not None and name not in lookups:
            unity = export.unity_name(role, name)
            lookups[name] = (index, unity) + (export.material(role, unity) if unity else (None, None, []))
    # One image per texture, at the largest size any of its users needs (0 = original size); the renderer's
    # compositor downsamples per use.
    largest = {}
    for _, _, _, material, rows in lookups.values():
        if material is not None:
            for _, row, limit in map_rows(rows, unity_params(material)):
                old = largest.get(row["path_id"])
                largest[row["path_id"]] = limit if old is None else 0 if not (old and limit) else max(old, limit)

    reached = set()
    block = {}
    for name, (index, unity, source, material, rows) in lookups.items():
        entry = {"material": name}
        maps, params = {}, None
        why = None
        if unity is None:
            why = "not in ysm-materials.json"
        elif material is None:
            why = f"Unity material {unity} has no JSON in the export"
        else:
            reached.add((source, unity.lower()))
            if source != role:
                report["borrowed"][name] = f"{source}/{unity.lower()}"
            entry["source"] = unity.lower()
            params = unity_params(material)
            for kind, row, _ in map_rows(rows, params):
                stem = os.path.splitext(os.path.splitext(os.path.basename(row["archive_path"] or "x"))[0])[0]
                image = patch.add_image(("export", row["path_id"]), f"npr_{stem}",
                                        export_png(row["path_id"], largest[row["path_id"]]))
                maps[kind] = {"image": image, **tiling(row)}
        # baseAlpha: iris scattering (a = base.a * _BaseColor.a) and the cloth parallax mask (Gens2_14753404 1597,
        # 2061-2069) read the base map's alpha, which YSM's opaque atlas drops.
        if params is not None and (EYE_MATERIAL.match(name) or params.get("_UseParallax") == 1):
            alpha = base_alpha(patch, document, mats[index], name, cap, filt)
            if alpha is not None:
                maps[BASE_ALPHA] = {"image": alpha}
            else:
                log(f"{name}: base colour image has no alpha channel, no {BASE_ALPHA}")
        if params is None:
            report["skipped"][name] = why
            continue
        if maps:
            entry["maps"] = maps
        entry["params"] = params
        block[name] = entry
        for kind in maps:
            report["maps_by_kind"][kind] = report["maps_by_kind"].get(kind, 0) + 1
        report["materials"][name] = {"source": entry["source"], "maps": list(maps), "params": len(params)}
    for (r, unity), rows in sorted(export.rows.items()):
        if r == role and (role, unity) not in reached:
            useful = sorted({SLOTS[x["slot"]] for x in rows
                             if x["slot"] in SLOTS and x["status"] == "found" and x["use_flag"] != 0})
            report["unmatched_bindings"].append({"material": unity, "would_add": useful})
    if fix_format1:
        report["format1"] = fix_format1_keys(patch, document, package, role, export, ramps, log)
    if block:
        package["materials"] = block
    report["images_added"] = len(patch.cache)
    return patch.blob, report


def base_alpha(patch, document, material, name, cap, filt):
    texture = material.get("pbrMetallicRoughness", {}).get("baseColorTexture")
    if texture is None:
        return None
    source = document["textures"][texture["index"]]["source"]

    image = load_rgba(image_bytes(document, patch.blob, source))
    if "A" not in image.getbands():
        return None

    def make():
        return patch.encode(downscale(image.getchannel("A"), cap, filt))
    return patch.add_image(("baseAlpha", source), f"npr_{name}_base_alpha", make)


# ------------------------------------------------------------------------------------------------------ format 1 fix

def fix_format1_keys(patch, document, package, role, export, ramps_dir, log):
    """Checks format-1 keys against the export; replaces what it can. Returns the log lines."""
    lines = []

    def note(text):
        lines.append(text)
        log(f"fix-format1 {role}: {text}")

    images = document.get("images", [])

    def name_of(ref):
        return images[ref["image"]].get("name", "") if isinstance(ref, dict) and "image" in ref else None

    def material_of(gltf_name):
        unity = export.unity_name(role, gltf_name)
        return (unity,) + export.material(role, unity) if unity else (None, None, None, [])

    def flag(material, key):
        return next((e["Value"] for e in material.get("m_Floats", []) if e["Key"] == key), None)

    def slot_id(material, slot):
        return next((t["path_id"] for t in material.get("textures", []) if t["slot"] == slot), 0)

    used = [document["materials"][i].get("name") for i in used_materials(document)]

    # Iris matcap: the export carries _MatcapTex; replace the format-1 image when the pixels differ.
    iris = package.get("iris", {})
    if iris.get("material"):
        unity, source, material, rows = material_of(iris["material"])
        row = next((r for r in rows if r["slot"] == "_MatcapTex" and r["status"] == "found"), None)
        current = iris.get("matcap")
        if row is None:
            note(f"iris.matcap: {unity} has no exported _MatcapTex; kept {name_of(current)}")
        else:
            path = export.texture(row["path_id"])
            fresh = load_rgba(path)
            old = load_rgba(image_bytes(document, patch.blob, current["image"])) if current else None
            same = old is not None and old.mode == fresh.mode and np.array_equal(np.asarray(old), np.asarray(fresh))
            rgb = np.asarray(fresh.convert("RGBA"))
            if same:
                extra = ""
                if rgb[..., :3].max() == 0:
                    extra = (f"; its RGB is 0 in the game texture itself (alpha max {rgb[..., 3].max()}), "
                             f"use_flag {row['use_flag']}: nothing to fix")
                note(f"iris.matcap {name_of(current)} equals the export's {os.path.basename(path)}{extra}")
            else:
                stem = os.path.splitext(os.path.splitext(os.path.basename(path))[0])[0]
                index = patch.add_image(("matcap", row["path_id"]), f"npr_{stem}", lambda: patch.encode(fresh),
                                        replaces=current["image"] if current else None)
                iris["matcap"] = {"image": index}
                note(f"iris.matcap: replaced {name_of(current)} by the export's {os.path.basename(path)} "
                     f"({unity} _MatcapTex)")

    # Skin LUT: the materials say whether the game samples one (_UseShadowLutTex).
    skin = [n for n in used if n and re.match(r"^(body|face)_\d+$", n)]
    flags = {}
    for n in skin:
        unity, source, material, rows = material_of(n)
        if material is not None:
            flags[n] = (flag(material, "_UseShadowLutTex"), slot_id(material, "_ShadowLutTex"))
    if "skinLut" not in package:
        if flags and all(f == 0 for f, _ in flags.values()):
            note(f"skinLut absent; consistent with the materials: _UseShadowLutTex = 0 on {sorted(flags)}")
        else:
            note(f"skinLut absent but the materials use one: {flags}; LUTs are not in the export, not fixable")
    elif flags and all(f == 0 for f, _ in flags.values()):
        note(f"skinLut {name_of(package['skinLut'])} present though _UseShadowLutTex = 0 on {sorted(flags)}; kept")

    # Cloth specular ramp.
    ramps = package.get("ramps", {})
    if "clothRS" not in ramps:
        users = {}
        for n in used:
            if n and n.startswith("cloth"):
                unity, source, material, rows = material_of(n)
                if material is not None:
                    users[n] = (flag(material, "_UseSpecRampMap"), slot_id(material, "_SpecRampMap"))
        on = {n: pid for n, (f, pid) in users.items() if f}
        known = [RAMP_FILES[pid] for pid in on.values() if pid in RAMP_FILES]
        unknown = sorted({pid for pid in on.values() if pid not in RAMP_FILES})
        if not on:
            note("ramps.clothRS absent; no used cloth material enables _UseSpecRampMap: consistent")
        elif not known:
            note(f"ramps.clothRS absent; _UseSpecRampMap = 1 on {sorted(on)} with _SpecRampMap path_id(s) {unknown}: "
                 f"ramps are not exported and the ids are unnamed, not fixable")
        else:
            file = max(sorted(set(known)), key=known.count)
            path = os.path.join(ramps_dir, file)
            users_of = sorted(n for n, pid in on.items() if RAMP_FILES.get(pid) == file)
            if not os.path.isfile(path):
                note(f"ramps.clothRS absent; {users_of} use {file}, but {path} does not exist (see --ramps)")
            else:
                ramp = load_rgba(path)
                stem = os.path.splitext(file)[0]
                ramps["clothRS"] = {"image": patch.add_image(("ramp", file), f"npr_{stem}",
                                                             lambda: patch.encode(ramp), added=True)}
                package["ramps"] = ramps
                rest = f"; {sorted(n for n in on if n not in users_of)} use unnamed id(s) {unknown}" if unknown else ""
                note(f"ramps.clothRS added: {file} from {ramps_dir}, the _SpecRampMap of {users_of}{rest}")

    # Overlay textures.
    for i, layer in enumerate(package.get("overlays", [])):
        name = name_of(layer.get("texture"))
        if name and "unity_default" in name:
            card = layer.get("card", {}).get("material")
            note(f"overlays[{i}].texture is {name} (card {card}, Unity material {export.unity_name(role, card)}); "
                 f"OverlayShadow materials are not in the export, not fixable")
    return lines


# --------------------------------------------------------------------------------------------------------- front end

def patch_file(source, target, role, export=DEFAULT_EXPORT, cap=DEFAULT_CAP, resample="box", fix_format1=False,
               ramps=DEFAULT_RAMPS, log=print, cap_hair_prop=DEFAULT_CAP_HAIR_PROP, oxipng_level=0):
    with open(source, "rb") as f:
        data = f.read()
    document, blob = read_glb(data)
    blob, report = apply(document, blob, role, export, cap, resample, fix_format1, ramps, log, cap_hair_prop,
                         oxipng_level)
    out = write_glb(document, blob)
    tmp = target + ".tmp"
    with open(tmp, "wb") as f:
        f.write(out)
    os.replace(tmp, target)
    report["size_before"], report["size_after"] = len(data), len(out)
    return report


def summary_line(report):
    kinds = ", ".join(f"{k} {v}" for k, v in sorted(report["maps_by_kind"].items()))
    return (f"{report['role']}: {len(report['materials'])} materials, {report['images_added']} images ({kinds}); "
            f"{report['size_before'] / 1e6:.2f} -> {report['size_after'] / 1e6:.2f} MB")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("input", help="VRM, or the models directory with --batch")
    parser.add_argument("output", help="VRM (may equal input), or the output directory with --batch")
    parser.add_argument("--role", help="export role (default: the input file name without extension)")
    parser.add_argument("--export", default=os.environ.get("NPR_EXPORT", DEFAULT_EXPORT),
                        help="export directory (env NPR_EXPORT)")
    parser.add_argument("--cap", type=int, default=DEFAULT_CAP, help="max texels per side of added images")
    parser.add_argument("--cap-hair-prop", type=int, default=DEFAULT_CAP_HAIR_PROP,
                        help="max side of hair prop maps (materials with _AnisotropyIntensity); 0 = original size")
    parser.add_argument("--oxipng", type=int, default=0, choices=range(7), metavar="LEVEL",
                        help="recompress added PNGs losslessly with oxipng at this level (1-6, needs pyoxipng; "
                        "2: about 8 %% smaller); 0 = off")
    parser.add_argument("--filter", choices=sorted(FILTERS), default="box", help="downscale filter (per channel)")
    parser.add_argument("--fix-format1", action="store_true", help="check/fix format-1 keys against the export")
    parser.add_argument("--ramps", default=os.environ.get("NPR_RAMPS", DEFAULT_RAMPS),
                        help="ramp PNGs (T_actor_common_*_RS.png) for --fix-format1 (env NPR_RAMPS)")
    parser.add_argument("--batch", action="store_true", help="patch every <role>/<role>.vrm under input")
    parser.add_argument("--only", nargs="+", metavar="ROLE", help="with --batch: just these roles")
    parser.add_argument("--report", help="write the report(s) as JSON here")
    args = parser.parse_args(argv)
    export = Export(args.export)
    reports = []
    if args.batch:
        for role in sorted(os.listdir(args.input)):
            folder = os.path.join(args.input, role)
            vrm = os.path.join(folder, role + ".vrm")
            if not os.path.isfile(vrm) or (args.only and role not in args.only):
                continue
            target = os.path.join(args.output, role)
            os.makedirs(target, exist_ok=True)
            for name in sorted(os.listdir(folder)):
                if name != role + ".vrm" and os.path.isfile(os.path.join(folder, name)):
                    shutil.copy2(os.path.join(folder, name), os.path.join(target, name))
            report = patch_file(vrm, os.path.join(target, role + ".vrm"), role, export, args.cap, args.filter,
                                args.fix_format1, args.ramps, cap_hair_prop=args.cap_hair_prop,
                                oxipng_level=args.oxipng)
            print(summary_line(report), flush=True)
            reports.append(report)
    else:
        role = args.role or os.path.splitext(os.path.basename(args.input))[0]
        report = patch_file(args.input, args.output, role, export, args.cap, args.filter, args.fix_format1,
                            args.ramps, cap_hair_prop=args.cap_hair_prop, oxipng_level=args.oxipng)
        print(summary_line(report))
        reports.append(report)
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            json.dump(reports if args.batch else reports[0], f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    sys.exit(main())
