#!/usr/bin/env python3
"""GPU-free renders of the eye region through npr_character.fsh's eye path (EyeSparkle), from a model's VRM.

Front view of one eye: face_01 and the brows (face texture), the iris disc and its catchlight polygons (iris texture),
the OverlayShadow eyeshadow layers; YSM atlas sampling (no mipmaps, nearest, textures resized to their atlas regions);
2 x 2 samples per pixel (the close-up SSAA path). Shading follows the shader for front light: nprMainLight lit / shadow
branches (eyeLit = 1 - eyeShadow on the eyeball), Grade in shade, nprIrisBright, the iris matcap, the highlight
saturation boost, eyeBrightness / eyeNightBrightness, irisBrightness, eyeDayGlow / eyeNightGlow, the catchlight boost,
footprint max, painted minimum-size dots and halo (eyeHighlightMinPx / Halo / HaloPx), the display transform
(tools/light_eval.py), scleraBrightness, and the overlay layers in game order (tone(Y m) / tone(Y)). Not modelled: the
face SDF (front light: lit), ramp tints, rim, back light, AO, self-shadow, local block lights (the fill light is a
frontal key term).

Classes as in game after both fixes: YSM's class map (dumped by GenClassMap, see EyeSparkle-notes.md) in the face
texture, the material table (Eye shader) on the iris.

  python3 tools/eye_sparkle_eval.py sizes  [models...]   # before (0.8.12) / after at iris 3, 6, 12, 24 px, noon
  python3 tools/eye_sparkle_eval.py defaults [models...] # candidate eye-key sets x 4 lights x iris 24 / 12 px
Outputs in ~/ysm/handoff/eyesparkle/ (sizes_<model>.png, sizes.txt; defaults/<model>.png, defaults/numbers.txt).
Class maps: /tmp/eyesparkle/gencls/<model>.spec.npy (G, B of YSM's half-resolution specular atlas) + <model>.txt.
"""
import json
import math
import os
import re
import struct
import sys
from io import BytesIO

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import light_eval as le  # noqa: E402

MODELS = os.path.expanduser("~/ysm/efdata/models-v14")
CLASSES = "/tmp/eyesparkle/gencls"
OUT = os.path.expanduser("~/ysm/handoff/eyesparkle")
LUM = np.array([0.212673, 0.715152, 0.072175])
CLOTH, SKIN, FACE, HAIR, EYE = 0, 1, 2, 3, 4

# ---------------------------------------------------------------- keys
BASE = dict(eyeBrightness=1.0, eyeNightBrightness=0.8, eyeDayGlow=0.05, eyeNightGlow=0.15, eyeShadow=0.0,
            eyeSceneShadow=0.0, eyeHighlightBoost=0.4, eyeHighlightOverShadow=True, scleraBrightness=1.0,
            scleraNightBrightness=1.0, eyeHighlightMinPx=0.0, eyeHighlightHalo=0.0, eyeHighlightHaloPx=1.5,
            irisBrightness=1.0)  # 0.8.12
F = dict(eyeHighlightMinPx=1.5, eyeHighlightHalo=0.5, eyeHighlightHaloPx=1.0, irisBrightness=1.1,
         eyeNightBrightness=1.0, eyeNightGlow=0.3, eyeShadow=0.3, scleraNightBrightness=0.9)
# the 2026-11-20 candidates were A-F (B-E: README "Unreleased: visible catchlights"); F was picked, then split from
# the scene shadow: the final defaults are F + eyeSceneShadow 1
SETS = [
    ("A 0.8.12 defaults", dict()),
    ("F (sun shadow off)", F),
    ("F + eyeSceneShadow 1", dict(F, eyeSceneShadow=1.0)),
]


# ---------------------------------------------------------------- lights (NprRenderer / npr_lights.glsl)
def condition(name):
    """key colour Lc, ambient rgb, skyX, face mapX, exposure, day, fill (lit-side luminance), label."""
    if name == "cave":
        amb = np.full(3, 0.05)
        fill = 0.6 * 0.5
        level = 0.05
        gain = min(max((1.337 / (level + fill)) ** 0.9, 0.5), 8.0)
        return dict(Lc=np.zeros(3), amb=amb, skyX=0.0, mapX=1.0, exposure=1.3 * gain, day=1.0, fill=fill)
    t = {"noon": 6000, "shade": 6000, "night": 18000}[name]
    col, lm, target, gain = le.level_and_gain(t, 0, 0, False)
    fill = 0.6 * min(0.5 - target, 0.5) if target < 0.5 else 0.0
    if fill > 0:
        tod = le.time_of_day(t)
        day = min(max((math.cos(tod * 2 * math.pi) + 0.05) / 0.3, 0), 1)
        hi = 8.0 + (min(2.5, 8.0) - 8.0) * (1 - day)
        gain = min(max((1.337 / (target + fill)) ** 0.9, 0.5), hi)
    _, day, _ = le.frame_light(le.time_of_day(t), 0, 0, False)
    return dict(Lc=col, amb=np.array(lm), skyX=1.0, mapX=0.0 if name == "shade" else 1.0, exposure=1.3 * gain,
                day=day, fill=fill)


def light_col(c, shadow, map_x):
    """nprMainLight for a front-lit surface (litMask 1: ambL white), blended by the per-sample shadow, plus the fill
    light as a frontal key term; map_x = the pack's sun-shadow map term (1 lit), for sceneShadowAmbient."""
    amb_lum = max(c["amb"].max(), 1e-4)
    under = 1.0 + (0.85 - 1.0) * (1.0 - map_x) * c["skyX"]  # sceneShadowAmbient where the pack's map shades
    shadow_amb = np.ones(3) * min(amb_lum * 0.35 + 0.65, 1.5) * amb_lum * under[..., None]
    lit_col = np.ones(3) * min(amb_lum, 1.5) * 0.35 + c["Lc"]
    fill = np.array([0.92, 0.96, 1.0]) / (np.array([0.92, 0.96, 1.0]) @ LUM) * c["fill"] * 0.87
    return shadow_amb + (lit_col - shadow_amb) * shadow[..., None] + fill


# ---------------------------------------------------------------- colour helpers (npr_character.fsh)
def srgb_decode(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def srgb_encode(c):
    c = np.maximum(c, 0)
    return np.where(c <= 0.003131, c * 12.92, 1.055 * np.power(c, 0.416667) - 0.055)


def grade(c, b, s):
    lin = srgb_decode(c)
    l = (lin @ LUM)[..., None]
    return srgb_encode(np.maximum((l + (lin - l) * s) * b, 0))


def display(c, exposure):
    """displayTransform, vectorised over [..., 3]."""
    lin = np.power(np.maximum(c, 0), 2.2) * exposure
    ap1 = lin @ le.SRGB_TO_AP1.T
    t = le.aces(ap1)
    tl = (t @ le.AP1_LUM)[..., None]
    t = tl + (t - tl) * 0.93
    s = t @ le.AP1_TO_SRGB.T
    hl = np.clip(((ap1 @ le.AP1_LUM) - 0.5) * 0.6666667, 0, 1)[..., None]
    s = np.clip(s + (np.clip(s / np.maximum(s.max(-1, keepdims=True), 1e-5), 0, 1) - s) * hl, 0, 1)
    s = srgb_encode(s)
    l = (s @ LUM)[..., None]
    return np.clip(l + (s - l) * 0.85, 0, 1)


def tone(y):
    t = le.aces(y) + (1 - le.aces(y)) * np.clip((y - 0.5) * 0.6666667, 0, 1)
    return np.where(t <= 0.003131, t * 12.92, 1.055 * np.power(np.maximum(t, 0), 0.416667) - 0.055)


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


# ---------------------------------------------------------------- model data
def load_vrm(role):
    with open(f"{MODELS}/{role}/{role}.vrm", "rb") as f:
        f.read(12)
        clen, _ = struct.unpack("<II", f.read(8))
        js = json.loads(f.read(clen))
        blen, _ = struct.unpack("<II", f.read(8))
        bin_ = f.read(blen)
    return js, bin_


def accessor(js, bin_, idx):
    a = js["accessors"][idx]
    bv = js["bufferViews"][a["bufferView"]]
    comp = {5126: np.float32, 5125: np.uint32, 5123: np.uint16, 5121: np.uint8}[a["componentType"]]
    n = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[a["type"]]
    o = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    stride = bv.get("byteStride")
    size = np.dtype(comp).itemsize * n
    if stride and stride != size:
        raw = np.frombuffer(bin_, np.uint8, a["count"] * stride, o).reshape(a["count"], stride)[:, :size]
        return np.frombuffer(raw.tobytes(), comp).reshape(a["count"], n).astype(np.float64)
    return np.frombuffer(bin_, comp, a["count"] * n, o).reshape(a["count"], n).astype(np.float64)


def image(js, bin_, idx):
    bv = js["bufferViews"][js["images"][idx]["bufferView"]]
    o = bv.get("byteOffset", 0)
    return Image.open(BytesIO(bin_[o:o + bv["byteLength"]])).convert("RGBA")


class Model:
    def __init__(self, role):
        js, bin_ = load_vrm(role)
        e = js["extensions"]["ENDFIELD_npr"]
        self.role = role
        names = [m["name"] for m in js["materials"]]
        face_name, iris_name = e["face"]["material"], e["iris"]["material"]
        mats = e["materials"]
        ip = mats[iris_name]["params"]
        self.hl = np.array(ip["_EyeHighLightColor"][:3])
        self.scatter = np.array(ip["_EyeScatteringColor"][:3])
        self.base_a = ip["_BaseColor"][3]
        self.h = min(max(ip.get("_EyeHighLight", 0.0), 0.0), 1.0)
        self.matcap_color = np.array(e["iris"]["matcapColor"])
        self.matcap_scale = e["iris"].get("matcapNormalScale", 1.0)
        self.matcap = np.asarray(image(js, bin_, e["iris"]["matcap"]["image"]), np.float64)[..., :] / 255
        # atlas regions (GenClassMap) for the texel grids; class map of the face texture
        txt = open(f"{CLASSES}/{role}.txt").read()
        aw, ah = map(int, re.search(r"atlas (\d+) (\d+)", txt).groups())
        reg = {names[int(i)]: tuple(map(int, r)) for i, *r in
               re.findall(r"material (\d+) Region\[x=(\d+), y=(\d+), width=(\d+), height=(\d+)\]", txt)}
        spec = np.load(f"{CLASSES}/{role}.spec.npy")  # G, B at half resolution

        def tex_of(mname):
            m = js["materials"][names.index(mname)]
            return m["pbrMetallicRoughness"]["baseColorTexture"]["index"]

        def atlas_texture(mname):
            x, y, w, h = reg[mname]
            # YSM's atlas is opaque (the alpha travels as the package's baseAlpha map): resize the colour alone, an
            # RGBA resize would premultiply and blacken the transparent catchlight corners
            img = image(js, bin_, js["textures"][tex_of(mname)]["source"]).convert("RGB").resize((w, h),
                                                                                                 Image.BILINEAR)
            return np.asarray(img, np.float64) / 255

        self.face_tex = atlas_texture(face_name)
        self.iris_tex = atlas_texture(iris_name)
        mp = mats[iris_name].get("maps", {})
        if "baseAlpha" in mp:
            x, y, w, h = reg[iris_name]
            self.alpha = np.asarray(image(js, bin_, mp["baseAlpha"]["image"]).convert("RGB").resize((w, h),
                                                                                                    Image.BILINEAR),
                                    np.float64)[..., 0] / 255
        else:
            self.alpha = np.zeros(self.iris_tex.shape[:2])
        fx, fy, fw, fh = reg[face_name]
        gx, gy = np.meshgrid((fx + (np.arange(fw) + 0.5)) * spec.shape[1] / aw,
                             (fy + (np.arange(fh) + 0.5)) * spec.shape[0] / ah)
        sb = spec[gy.astype(int), gx.astype(int)]
        blue, green = sb[..., 1].astype(np.int32), sb[..., 0]
        cls = np.full(blue.shape, CLOTH)
        cls[blue >= 140] = SKIN
        cls[blue >= 155] = FACE
        cls[(blue >= 112) & (blue <= 138)] = HAIR
        cls[np.abs(blue - 5) < 2.5] = EYE
        self.face_cls = cls
        # geometry: the eye at +x (the character's left), disc + catchlights, face_01, brows, eyeshadow card
        prims = []
        for mesh in js["meshes"]:
            for prim in mesh["primitives"]:
                nm = names[prim["material"]] if prim.get("material") is not None else ""
                if nm not in (face_name, iris_name, "eyeshadow_01") and not ("brow" in nm and nm in reg):
                    continue
                P = accessor(js, bin_, prim["attributes"]["POSITION"])
                UV = accessor(js, bin_, prim["attributes"]["TEXCOORD_0"])
                I = accessor(js, bin_, prim["indices"]).astype(np.int64).reshape(-1, 3)
                kind = 1 if nm == iris_name else 2 if nm == "eyeshadow_01" else 0
                prims.append((kind, P, UV, I))
        iris = [(P, UV, I) for k, P, UV, I in prims if k == 1]
        P, UV, I = iris[0]
        r2 = ((UV - 0.5) ** 2).sum(1)
        disc = (r2 < 0.27) & (P[:, 0] > 0)
        self.centre = P[disc].mean(0)
        self.disc_w = P[disc, 0].max() - P[disc, 0].min()
        self.prims = prims
        self.overlays = [o for o in e.get("overlays", []) if o["card"]["material"] == "eyeshadow_01"]
        self.overlay_tex = [np.asarray(image(js, bin_, o["texture"]["image"]), np.float64)[..., 0] / 255
                            for o in self.overlays]
        # catchlight polygons of this eye (CPU table of Catchlights.java): area centroid, area radius, uv centroid
        self.catch = []
        tris = [t for t in I if r2[t].mean() > 0.27 and P[t, 0].mean() > 0]
        comp = {}
        for t in tris:
            key = None
            for v in t:
                for k, vs in comp.items():
                    if any(np.allclose(UV[v], UV[w]) for w in vs):
                        key = k
                if key is not None:
                    break
            comp.setdefault(key if key is not None else len(comp), set()).update(t.tolist())
        for vs in comp.values():
            ts = [t for t in tris if set(t.tolist()) <= vs]
            area = 0
            c = np.zeros(3)
            cu = np.zeros(2)
            for t in ts:
                a = 0.5 * np.linalg.norm(np.cross(P[t[1]] - P[t[0]], P[t[2]] - P[t[0]]))
                area += a
                c += a * P[t].mean(0)
                cu += a * UV[t].mean(0)
            if area > 0:
                self.catch.append((c / area, math.sqrt(area / math.pi), cu / area))


# ---------------------------------------------------------------- rasteriser (front view, +z toward the camera)
def rasterise(m, iris_px, ss=2):
    s = iris_px / m.disc_w * ss  # samples per metre
    w = int(round(2.6 * iris_px)) * ss
    h = int(round(2.2 * iris_px)) * ss
    x0 = m.centre[0] - w / s / 2
    y1 = m.centre[1] + 0.25 * m.disc_w + h / s / 2
    depth = np.full((h, w), -1e9)
    kind = np.full((h, w), -1)
    uvb = np.zeros((h, w, 2))
    duv = np.zeros((h, w, 4))  # du/dx, dv/dx, du/dy, dv/dy per final pixel
    tframe = np.zeros((h, w, 4))  # screen directions of +u and +v (unit, image x right / y up)
    over = np.zeros((h, w, 2))
    over_d = np.full((h, w), -1e9)
    for k, P, UV, I in m.prims:
        sx = (P[:, 0] - x0) * s
        sy = (y1 - P[:, 1]) * s
        for t in I:
            xs, ys = sx[t], sy[t]
            if xs.max() < 0 or ys.max() < 0 or xs.min() > w or ys.min() > h:
                continue
            ax, ay = np.floor(max(xs.min(), 0)), np.floor(max(ys.min(), 0))
            bx, by = min(np.ceil(xs.max()), w), min(np.ceil(ys.max()), h)
            if bx <= ax or by <= ay:
                continue
            gx, gy = np.meshgrid(np.arange(ax, bx) + 0.5, np.arange(ay, by) + 0.5)
            d = (xs[1] - xs[0]) * (ys[2] - ys[0]) - (xs[2] - xs[0]) * (ys[1] - ys[0])
            if abs(d) < 1e-12:
                continue
            b1 = ((gx - xs[0]) * (ys[2] - ys[0]) - (xs[2] - xs[0]) * (gy - ys[0])) / d
            b2 = ((xs[1] - xs[0]) * (gy - ys[0]) - (gx - xs[0]) * (ys[1] - ys[0])) / d
            b0 = 1 - b1 - b2
            inside = (b0 >= 0) & (b1 >= 0) & (b2 >= 0)
            if not inside.any():
                continue
            z = b0 * P[t[0], 2] + b1 * P[t[1], 2] + b2 * P[t[2], 2]
            uv = b0[..., None] * UV[t[0]] + b1[..., None] * UV[t[1]] + b2[..., None] * UV[t[2]]
            sl = (slice(int(ay), int(by)), slice(int(ax), int(bx)))
            # uv derivatives per sample step -> per final pixel
            M = np.array([[xs[1] - xs[0], xs[2] - xs[0]], [ys[1] - ys[0], ys[2] - ys[0]]])
            Du = np.array([UV[t[1]] - UV[t[0]], UV[t[2]] - UV[t[0]]])  # rows: e1, e2
            J = np.linalg.solve(M.T, Du)  # rows: d/dx, d/dy of (u, v)
            if k == 2:
                take = inside & (z > over_d[sl])
                over_d[sl][take] = z[take]
                over[sl][take] = uv[take]
                continue
            take = inside & (z > depth[sl])
            depth[sl][take] = z[take]
            kind[sl][take] = k
            uvb[sl][take] = uv[take]
            duv[sl][take] = (J * ss).reshape(4)
            # screen direction of +u, +v (image y down -> up): inverse Jacobian columns
            Ji = np.linalg.inv(J)  # rows: d(x, y)/du, d(x, y)/dv
            tu = Ji[0] / max(np.linalg.norm(Ji[0]), 1e-12)
            tv = Ji[1] / max(np.linalg.norm(Ji[1]), 1e-12)
            tframe[sl][take] = (tu[0], -tu[1], tv[0], -tv[1])
    return dict(depth=depth, kind=kind, uv=uvb, duv=duv, tframe=tframe, over=over, over_d=over_d, s=s, x0=x0, y1=y1,
                w=w, h=h, ss=ss)


def nearest(tex, uv):
    hh, ww = tex.shape[:2]
    x = np.clip((uv[..., 0] * ww).astype(int), 0, ww - 1)
    y = np.clip((uv[..., 1] * hh).astype(int), 0, hh - 1)
    return tex[y, x]


# ---------------------------------------------------------------- shading
def shade(m, R, c, k, after):
    """Display colour per sample and per-sample masks; k = keys, after = EyeSparkle terms on."""
    h, w = R["h"], R["w"]
    kind, uv = R["kind"], R["uv"]
    face_px = kind == 0
    iris_px = kind == 1
    face_t = nearest(m.face_tex, uv)
    iris_t = nearest(m.iris_tex, uv)
    tex = np.where(iris_px[..., None], iris_t, face_t)[..., :3]
    A = tex.copy()
    fcls = nearest(m.face_cls[..., None], uv)[..., 0]
    cls = np.where(iris_px, EYE, np.where(face_px, fcls, -1))
    # classFromMaterials in the face texture: the class map's light eye texels (the eye whites, on the eye bones) take
    # the sclera repair like its cloth texels; dark eye texels (brows, lashes) stay eye
    # the eye opening around the disc centre (nprNearEyeball: 2.6 disc radii across, 1.4 up/down)
    ys_, xs_ = np.mgrid[0:h, 0:w]
    wx = R["x0"] + (xs_ + 0.5) / R["s"] - m.centre[0]
    wy = R["y1"] - (ys_ + 0.5) / R["s"] - m.centre[1]
    r_eye = 0.55 * m.disc_w
    near = (wx / (2.6 * r_eye)) ** 2 + (wy / (1.4 * r_eye)) ** 2 < 1
    sclera = face_px & ((cls == CLOTH) | ((cls == EYE) & ((tex @ LUM) > 0.5) & near))
    cls = np.where(sclera, FACE, cls)
    brow = face_px & (cls == EYE)
    # iris terms
    p = uv * 2 - 1
    r2 = (p ** 2).sum(-1)
    fp = np.sqrt(R["duv"][..., 0] ** 2 + R["duv"][..., 2] ** 2) * 4  # ~ footprint of r2 (|grad r2| ~ 4 |grad uv|)
    iris_in = np.where(iris_px, 1 - smoothstep(1 - 0.5 * np.minimum(fp, 0.5), 1 + 0.5 * np.minimum(fp, 0.5), r2), 0)
    outside = np.where(iris_px, (1 - iris_in) * m.h, 0)
    core = np.zeros((h, w, 3))
    splat = np.zeros((h, w))
    halo = np.zeros((h, w))
    ss = R["ss"]
    if after and k["eyeHighlightMinPx"] > 0:
        # footprint max on the catchlight polygons' own pixels (4 x 4 taps outside the circle)
        sel = iris_px & (r2 > 1.08)  # the catchlight polygons (uv clear of the disc's own rim)
        size = m.iris_tex.shape[0]
        foot = np.maximum(np.hypot(R["duv"][..., 0], R["duv"][..., 1]), np.hypot(R["duv"][..., 2], R["duv"][..., 3]))
        sel &= foot * size > 2
        best = A.max(-1)
        for i in range(4):
            for j in range(4):
                fu = ((i + 0.5) * 0.25 - 0.5)
                fv = ((j + 0.5) * 0.25 - 0.5)
                tuv = uv + R["duv"][..., 0:2] * fu + R["duv"][..., 2:4] * fv
                cc = np.clip(tuv, 0, 1) - 0.5
                ok = sel & ((cc ** 2).sum(-1) >= 0.25)
                t = nearest(m.iris_tex, np.clip(tuv, 0, 1))[..., :3]
                v = t.max(-1)
                better = ok & (v > best)
                A[better] = t[better]
                best = np.where(better, v, best)
    if after and (k["eyeHighlightMinPx"] > 0 or k["eyeHighlightHalo"] > 0):
        ys, xs = np.mgrid[0:h, 0:w]
        fx = (xs + 0.5) / ss
        fy = (ys + 0.5) / ss
        best = np.zeros((h, w))
        r_largest = max((rad for _, rad, _ in m.catch), default=1.0)
        for cpos, rad, cuv in m.catch:
            px = (cpos[0] - R["x0"]) * R["s"] / ss
            py = (R["y1"] - cpos[1]) * R["s"] / ss
            scale = R["s"] / ss  # pixels per metre
            r = rad * scale
            r_min = 0.5 * k["eyeHighlightMinPx"] * rad / r_largest
            reach = np.linalg.norm(cpos - m.centre) + 1.15 * 0.55 * m.disc_w
            edge = max(r, r_min)
            d = np.hypot(fx - px, fy - py)
            cv = np.clip(edge + 0.5 - d, 0, 1) * (1 - smoothstep(r_min, 2 * r_min, r)) if r_min > 0 else 0 * d
            e = np.maximum(d - edge, 0) / max(min(k["eyeHighlightHaloPx"], 0.15 * reach * scale), 1e-3)
            hv = np.exp(-e * e) if k["eyeHighlightHalo"] > 0 else 0 * d
            cv = np.where(iris_px, cv, 0)
            hv = np.where(iris_px, hv, 0)
            stronger = np.maximum(cv, hv) > best
            core[stronger] = nearest(m.iris_tex, np.broadcast_to(cuv, (h, w, 2)))[stronger][..., :3]
            best = np.maximum(best, np.maximum(cv, hv))
            splat = np.maximum(splat, cv)
            halo = np.maximum(halo, hv)
        A = A + (core - A) * splat[..., None]
    catch = np.maximum(outside, splat * m.h) * smoothstep(0.35, 0.6, A.max(-1))
    catch = np.where(iris_px, catch, 0)
    # lighting: eyeball (iris, sclera) lit by eyeLit; face, brows by the scene shadow
    eye_scene_lit = np.where(iris_px | sclera, 1 - k["eyeSceneShadow"], 0.0)  # the pack's sun shadow: eyeSceneShadow
    map_x = c["mapX"] + (1 - c["mapX"]) * eye_scene_lit
    shadow = c["skyX"] * map_x
    L = light_col(c, shadow, map_x)
    diff = A * 0.96
    a = nearest(m.alpha[..., None], uv)[..., 0] * m.base_a
    o = np.maximum(1 - iris_in, splat) * m.h
    bright = ((1 - o)[..., None] + m.hl * o[..., None]) * (1 + m.h * ((1 - a)[..., None] + m.scatter * a[..., None] - 1))
    diff = np.where(iris_px[..., None], diff * bright, diff)
    shade_alb = grade(diff, 1.0, 1.2)
    alb = shade_alb + (diff - shade_alb) * shadow[..., None]
    color = L * alb
    # iris matcap on the uv sphere (eye frame from the triangle's uv directions)
    sxy = p * -m.matcap_scale
    sz = np.sqrt(np.clip(1 - r2, 0, 1))
    tf = R["tframe"]
    n = np.stack([tf[..., 0] * sxy[..., 0] + tf[..., 2] * sxy[..., 1], tf[..., 1] * sxy[..., 0] + tf[..., 3] * sxy[..., 1],
                  sz], -1)
    n /= np.maximum(np.linalg.norm(n, axis=-1, keepdims=True), 1e-6)
    cap = nearest(m.matcap, np.stack([n[..., 0] * 0.5 + 0.5, 0.5 - n[..., 1] * 0.5], -1))
    capc = cap[..., :3] * m.matcap_color[3] + m.matcap_color[:3] * cap[..., 3:4]
    color = color + np.where(iris_px[..., None], capc * iris_in[..., None] * L, 0)
    # highlight saturation boost (linear)
    lc = np.power(np.maximum(color, 0), 2.2)
    cl = (lc @ LUM)[..., None]
    kk = np.clip(cl - 0.5, 0, 0.5)
    color = np.power(np.maximum(cl + (lc - cl) * (1 + kk * kk), 0), 1 / 2.2)
    # eye class: gain, iris gain, glow, boost, halo
    day = c["day"]
    eye_gain = k["eyeBrightness"] * (k["eyeNightBrightness"] + (1 - k["eyeNightBrightness"]) * day)
    eye = iris_px | brow
    color = np.where(eye[..., None], color * eye_gain, color)
    color = np.where(iris_px[..., None], color * (1 + (k["irisBrightness"] - 1) * iris_in)[..., None], color)
    glow = k["eyeNightGlow"] + (k["eyeDayGlow"] - k["eyeNightGlow"]) * day
    if glow > 0:
        g = np.power(np.maximum(A, 0), 2.2) * (glow * iris_in)[..., None]
        color = np.where(iris_px[..., None], np.power(np.power(np.maximum(color, 0), 2.2) + g, 1 / 2.2), color)
    if k["eyeHighlightBoost"] > 0:
        white = A.min(-1)
        litv = smoothstep(0.02, 0.5, color.max(-1))
        hl = np.power(np.maximum(A, 0), 2.2) * m.hl * (k["eyeHighlightBoost"] * catch * white * white * litv)[..., None]
        color = np.where((catch > 0)[..., None], np.power(np.power(np.maximum(color, 0), 2.2) + hl, 1 / 2.2), color)
    if after and k["eyeHighlightHalo"] > 0:
        hc = np.maximum(m.hl, 0)
        tint = 1 + (hc / max(hc.max(), 1e-4) - 1) * 0.5
        lit3 = core * L * hc * 0.96 * eye_gain
        litv = smoothstep(0.02, 0.5, lit3.max(-1))
        add = tint * (k["eyeHighlightHalo"] * k["eyeHighlightBoost"] * (hc @ LUM) * halo * litv)[..., None]
        color = np.where((halo > 0)[..., None], np.power(np.power(np.maximum(color, 0), 2.2) + add, 1 / 2.2), color)
    pre_y = (np.power(np.maximum(color, 0), 2.2) * c["exposure"]) @ LUM
    out = display(color, c["exposure"])
    sg = k["scleraBrightness"] * (k["scleraNightBrightness"] + (1 - k["scleraNightBrightness"]) * day)
    out = np.where(sclera[..., None], out * sg, out)
    # overlay layers (game order), stencil 4 over skin/face, 20 over the iris; catchlights (class 7) skip
    for ov, t in zip(m.overlays, m.overlay_tex):
        cov = R["over_d"] > R["depth"] - 1e-4
        cu = (R["over"] - np.array(ov["card"]["uv"][:2])) / np.array(ov["card"]["uv"][2:])
        g = nearest(t[..., None], np.clip(cu, 0, 1))[..., 0] * ov["baseColor"][3]
        mul = 1 + (np.array(ov["baseColor"][:3]) - 1) * g[..., None]
        if ov["stencilRef"] == 20:
            recv = iris_px & ~((catch > 0.5) & k["eyeHighlightOverShadow"])
        else:
            recv = face_px & ~brow
        y = np.maximum(pre_y, 1e-6)[..., None]
        ratio = tone(y * mul) / np.maximum(tone(y), 1e-5)
        out = np.where((cov & recv)[..., None], out * ratio, out)
    out = np.where((kind >= 0)[..., None], out, 0.12)
    return out, dict(iris=iris_px & (iris_in > 0.5) & (catch < 0.5), dot=iris_px & ((catch > 0.5) | (splat > 0.5)),
                     sclera=sclera & (tex.max(-1) > 0.6), skin=face_px & (cls == FACE) & ~sclera, brow=brow)


def resolve(img, ss):
    h, w = img.shape[0] // ss, img.shape[1] // ss
    lin = srgb_decode(img).reshape(h, ss, w, ss, 3).mean((1, 3))
    return srgb_encode(lin)


def stats(out, masks, ss):
    lin = srgb_decode(out)
    y = lin @ LUM
    res = {}
    for name in ("iris", "dot", "sclera", "skin"):
        msk = masks[name]
        if msk.any():
            mean_lin = lin[msk].mean(0)
            res[name] = float(srgb_encode(mean_lin @ LUM)) if name != "dot" else float(srgb_encode(y[msk].max()))
            if name == "iris":
                mx, mn = mean_lin.max(), mean_lin.min()
                res["iris_sat"] = float((mx - mn) / max(mx, 1e-6))
        else:
            res[name] = float("nan")
    return res


def label(img, text):
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, img.width, 12], fill=(0, 0, 0))
    d.text((2, 0), text, fill=(255, 255, 255))
    return img


def tile(out, ss, scale):
    px = resolve(out, ss)
    im = Image.fromarray((np.clip(px, 0, 1) * 255 + 0.5).astype(np.uint8))
    return im.resize((im.width * scale, im.height * scale), Image.NEAREST)


def run_sizes(roles):
    lines = ["# iris px, model: dot peak display luminance before -> after (noon); iris mean"]
    c = condition("noon")
    after_keys = dict(BASE, eyeHighlightMinPx=1.5, eyeHighlightHalo=0.5, eyeHighlightHaloPx=1.0)
    for role in roles:
        m = Model(role)
        cols = []
        for size in (3, 6, 12, 24):
            R = rasterise(m, size)
            scale = max(1, 192 // (R["w"] // R["ss"]))
            row = []
            vals = []
            for after in (False, True):
                out, masks = shade(m, R, c, after_keys if after else BASE, after)
                st = stats(out, masks, R["ss"])
                px = resolve(out, R["ss"])
                y = srgb_decode(px) @ LUM
                # the brightest final pixel that is mostly iris (disc or catchlight), against the iris mean
                ss = R["ss"]
                cover = (R["kind"] == 1).reshape(R["h"] // ss, ss, R["w"] // ss, ss).mean((1, 3)) >= 0.5
                dot_peak = float(srgb_encode(y[cover].max())) if cover.any() else float("nan")
                vals.append((dot_peak, st["iris"]))
                row.append(label(tile(out, R["ss"], scale), f"{size}px {'after' if after else 'before'}"))
            lines.append(f"{size:3d} px {role:9s}: peak {vals[0][0]:.2f} -> {vals[1][0]:.2f}, iris {vals[0][1]:.2f}"
                         f" -> {vals[1][1]:.2f}")
            w = sum(r.width for r in row) + 4
            hh = max(r.height for r in row)
            pair = Image.new("RGB", (w, hh), (40, 40, 40))
            pair.paste(row[0], (0, 0))
            pair.paste(row[1], (row[0].width + 4, 0))
            cols.append(pair)
        W = max(p.width for p in cols)
        H = sum(p.height + 4 for p in cols)
        sheet = Image.new("RGB", (W, H), (40, 40, 40))
        yy = 0
        for p in cols:
            sheet.paste(p, (0, yy))
            yy += p.height + 4
        sheet.save(f"{OUT}/sizes_{role}.png")
    open(f"{OUT}/sizes.txt", "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))


def run_defaults(roles):
    os.makedirs(f"{OUT}/defaults", exist_ok=True)
    lights = ["noon", "shade", "night", "cave"]
    lines = ["# per set, light, size: display luminance (sRGB-encoded mean of linear) iris / dot peak / sclera / skin,"
             " iris saturation"]
    for role in roles:
        m = Model(role)
        rows = []
        for sname, over in SETS:
            k = dict(BASE, **over)
            row = []
            for size in (24, 12):
                R = rasterise(m, size)
                for light in lights:
                    c = condition(light)
                    out, masks = shade(m, R, c, k, True)
                    st = stats(out, masks, R["ss"])
                    lines.append(f"{role:9s} {sname:18s} {light:6s} {size:2d}px iris {st['iris']:.2f} dot "
                                 f"{st['dot']:.2f} sclera {st['sclera']:.2f} skin {st['skin']:.2f} "
                                 f"sat {st.get('iris_sat', float('nan')):.2f}")
                    row.append(label(tile(out, R["ss"], 4 if size == 24 else 8), f"{light} {size}px"))
            rows.append((sname, row))
        tw = sum(t.width for t in rows[0][1]) + 4 * len(rows[0][1]) + 150
        th = max(t.height for t in rows[0][1])
        sheet = Image.new("RGB", (tw, (th + 4) * len(rows)), (30, 30, 30))
        d = ImageDraw.Draw(sheet)
        for i, (sname, row) in enumerate(rows):
            y = i * (th + 4)
            d.text((4, y + th // 2 - 6), sname, fill=(255, 255, 255))
            x = 150
            for t in row:
                sheet.paste(t, (x, y))
                x += t.width + 4
        sheet.save(f"{OUT}/defaults/{role}.png")
    open(f"{OUT}/defaults/numbers.txt", "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "sizes"
    roles = sys.argv[2:] or ["pelica", "chen", "zhuangfy", "ikut"]
    (run_sizes if mode == "sizes" else run_defaults)(roles)
