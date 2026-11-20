#!/usr/bin/env python3
"""Offline numbers for the iris terms of npr_eyebrow.glsl on Pelica's data.

1. Parallax (HGRP/CharacterNPR_Eye, decompiled Eye_950424.glsl 137-184; ue5ref Stages/Eye.ush 65-80):
     irisUV = uv - normalize(V_tangent).xy * _ParallaxScale * (1, 0.25) * w,  w = smoothstep(sat((0.25 - r^2) * 5))
   with r = fract(uv) - 0.5. Printed as the shift of the iris centre in iris texels and in screen pixels for a 1080p,
   70 deg vertical FOV view at several distances and view angles, using the iris quad size of the mesh (metres per uv).
2. Colour (Eye_950424.glsl 720-733; Eye.ush 185-190), H = sat(_EyeHighLight):
     bright = iris * .96 * ((1 - o*H) + _EyeHighLightColor * o*H) * lerp(1, (1 - a) + _EyeScatteringColor * a, H)
   o = r^2 >= .25 (outside the iris circle), a = base.a * _BaseColor.a. Printed: the mean multiplier and the albedo
   luminance before/after over the iris disc, the outside corners and the alpha-weighted texels.

usage: iris_eval.py [role]   (default pelica; params from ~/ysm/efdata/props/materials/<role>/m_actor_<role>_iris_01)
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

ROLE = sys.argv[1] if len(sys.argv) > 1 else "pelica"
MAT = os.path.expanduser(f"~/ysm/efdata/props/materials/{ROLE}/m_actor_{ROLE}_iris_01.json")
TEX = os.path.expanduser("~/ysm/pelica-full/01-模型/textures/assets/beyond/arts/entity/actor/girl/pelica/textures/"
                         "t_actor_pelica_iris_01_d.tga.png")
EYE_UV_METRES = (0.0253, 0.0269)  # Pelica iris_01: |dP/du|, |dP/dv| per eye (fix_pelica glb, left eye)
LUM = np.array([0.212673, 0.715152, 0.072175])


def params():
    d = json.load(open(MAT))

    def items(x):
        return x.items() if isinstance(x, dict) else [(e["Key"], e["Value"]) for e in x]
    f = dict(items(d["m_Floats"]))
    c = {k: np.array([v["R"], v["G"], v["B"], v["A"]]) for k, v in items(d["m_Colors"])}
    return f, c


def main():
    f, c = params()
    scale = f["_ParallaxScale"]
    H = min(max(f.get("_EyeHighLight", 0.0), 0.0), 1.0)
    HL = c["_EyeHighLightColor"][:3]
    SC = c["_EyeScatteringColor"][:3]
    base_a = c["_BaseColor"][3]
    print(f"{ROLE} iris: _ParallaxScale {scale}, _EyeHighLight {H}, _EyeHighLightColor {HL.round(3)}, "
          f"_EyeScatteringColor {SC.round(3)}, _BaseColor.a {base_a}, _AlphaPremultiply {f.get('_AlphaPremultiply')}")

    # 1. parallax shift of the iris centre (w = 1 there); V_t.xy = sin(angle) along the tilt
    print("\nparallax: centre shift (w = 1; the shift fades to 0 at r^2 = .25, the iris rim)")
    tex_px = 512
    for angle in (10, 30, 45, 60):
        s = math.sin(math.radians(angle))
        du, dv = s * scale, s * scale * 0.25
        print(f"  view {angle:2d} deg off the eye axis: uv shift horizontal {du:.4f} ({du * tex_px:.1f} iris texels),"
              f" vertical {dv:.4f} ({dv * tex_px:.1f} texels)")
    print("  on screen (1080p, 70 deg vertical FOV), horizontal tilt 30 / 60 deg:")
    for dist in (0.5, 1.0, 2.0, 4.0):
        ppm = 1080 / (2 * dist * math.tan(math.radians(35)))
        eye_px = EYE_UV_METRES[0] * ppm
        print(f"    {dist:.1f} blocks: iris quad {eye_px:5.1f} px wide, shift "
              f"{math.sin(math.radians(30)) * scale * eye_px:.2f} / {math.sin(math.radians(60)) * scale * eye_px:.2f} px")

    # 2. colour terms over Pelica's iris texture
    img = np.asarray(Image.open(TEX).convert("RGBA"), dtype=np.float64) / 255.0
    rgb, alpha = img[..., :3], img[..., 3] * base_a
    n = img.shape[0]
    v, u = np.mgrid[0:n, 0:n]
    cu, cv = (u + 0.5) / n - 0.5, (v + 0.5) / n - 0.5
    r2 = cu * cu + cv * cv
    o = (r2 >= 0.25).astype(np.float64)
    hl = (1 - o * H)[..., None] + HL * (o * H)[..., None]
    sc = 1 + H * ((1 - alpha)[..., None] + SC * alpha[..., None] - 1)
    mult = hl * sc
    lin = rgb ** 2.2  # the game works on linear albedo; the multiplier applies to it
    before = (lin * 0.96) @ LUM
    after = (lin * 0.96 * mult) @ LUM
    disc = r2 < 0.25
    print("\ncolour (linear luminance of bright = albedo * .96 * multiplier):")
    for name, sel in (("iris disc (r^2 < .25)", disc), ("outside corners", ~disc),
                      ("alpha > .25 texels", alpha > 0.25), ("all texels", np.ones_like(disc))):
        if not sel.any():
            continue
        w = sel.sum()
        print(f"  {name:22s} {w:6d} texels: multiplier mean {mult[sel].mean(0).round(3)}, luminance "
              f"{before[sel].mean():.4f} -> {after[sel].mean():.4f} (x{after[sel].mean() / max(before[sel].mean(), 1e-6):.2f})")
    print(f"  iris alpha: mean {alpha.mean():.3f}, max {alpha.max():.3f}, texels > .25: {(alpha > 0.25).mean() * 100:.1f}%;"
          f" without the baseAlpha map a = 0: the scattering factor is 1 everywhere")


if __name__ == "__main__":
    main()
