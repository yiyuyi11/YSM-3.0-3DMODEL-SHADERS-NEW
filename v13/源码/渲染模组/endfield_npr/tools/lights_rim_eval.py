#!/usr/bin/env python3
"""Offline evaluator of the local block lights and the hair depth rim (npr_lights.glsl), no game needed.

Part 1, local lights: a torch (emission 14) at 1, 3 and 6 blocks from Pelica's eyes, at night under open sky (sky 15,
moonlight, main light X = 1) and in a cave (sky 0, X = 0). The vanilla lightmap (LightTexture.updateLightTexture, gamma
0.5, no flicker) gives her ambient; nprMainLight / localLightSplit / localLights are replicated from npr_lights.glsl and
evaluated on Pelica's real albedo (median opaque texel of t_actor_pelica_{cloth_01,body_01,hair_01}_d) with her
material shadow grades (_ShadowColorBrightness/_Saturation). Printed per class: the ambient-only colour (0.6.3, = the
new path with localLights off), the new colour with the normal facing the torch (lit) and facing away (dark), lit/dark
contrast, and the mean over 4000 normals on the sphere divided by the ambient-only colour (1.000 = no double
counting). Diffuse only (the additional GGX adds the game's local highlight on top and is not part of the split).

Part 2, hair depth rim: a sphere of hair (radius 0.12 blocks) at 2-16 blocks, 1920x1080, vertical FOV 70, rimWidth 0.5;
the rim mask along the horizontal line through its centre, new (depth gate against a background 4 blocks behind, or
her body 0.03 / 0.08 / 0.15 / 0.3 blocks behind the hair edge) and old (angular smoothstep(0.5, 0.7, 1 - |N.V|)):
pixels with mask > 0.5 and the profile from the silhouette inward. Writes /tmp/lights_rim_eval/rim_profile.png (rows:
2 blocks old, new; 8 blocks old, new; 2x nearest).

Usage: python3 tools/lights_rim_eval.py [--pelica <textures dir>]
"""
import math
import os
import sys

import numpy as np

LUM = np.array([0.212673, 0.715152, 0.072175])
TEX = os.path.expanduser('~/ysm/pelica-full/01-模型/textures/assets/beyond/arts/entity/actor/girl/pelica/textures')
GAMMA_OPTION = 0.5
OUT = '/tmp/lights_rim_eval'


def lum(c):
    return float(np.dot(c, LUM))


def srgb_encode(c):
    c = np.maximum(c, 0)
    return np.where(c <= 0.003131, c * 12.92, 1.055 * np.power(c, 0.416667) - 0.055)


def srgb_decode(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.04045, c / 12.92, np.power((c + 0.055) / 1.055, 2.4))


def grade(c, b, s):
    l = srgb_decode(c)
    return srgb_encode(np.maximum((lum(l) + (l - lum(l)) * s) * b, 0))


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


# ---------------------------------------------------------------- vanilla lightmap (LightTexture 1.20.1)
def brightness(level):
    f = np.clip(level / 15.0, 0, 1)
    return f / (4 - 3 * f)


def not_gamma(x):
    return 1 - (1 - x) ** 4


def lightmap(block, sky, sky_darken):
    f1 = sky_darken * 0.95 + 0.05
    sky_rgb = np.array([sky_darken, sky_darken, 1.0]) * 0.65 + 0.35
    f7 = brightness(sky) * f1
    f8 = brightness(block) * 1.5  # blockLightRedFlicker 0 on average
    c = np.array([f8, f8 * ((f8 * 0.6 + 0.4) * 0.6 + 0.4), f8 * (f8 * f8 * 0.6 + 0.4)])
    c = c + sky_rgb * f7
    c = c + (0.75 - c) * 0.04
    c = np.clip(c, 0, 1)
    c = c + (not_gamma(c) - c) * GAMMA_OPTION
    c = c + (0.75 - c) * 0.04
    return np.clip(c, 0, 1)


# ---------------------------------------------------------------- npr_lights.glsl
def main_light(amb, lit_mask, shadow, sky, light_color, sky_color, env_ao=1.0, map_x=1.0, sky_x=None):
    sky_x = shadow if sky_x is None else sky_x
    amb_lum = max(amb.max(), 1e-4)
    sky_hue = sky_color / max(sky_color.max(), 1e-4)
    amb_hue = amb / amb_lum + (((1 - 0.45) + sky_hue * 0.45) - amb / amb_lum) * min(max(sky, 0), 1)
    amb_l = amb_hue + (1 - amb_hue) * lit_mask  # ambToWhite 1
    k_shadow = min(amb_lum * 0.35 + 0.65, 1.5)
    shadow_amb = amb_l * k_shadow * amb_lum * env_ao * (1 + ((0.85 + (1 - 0.85) * map_x) - 1) * sky_x)
    lc_desat = lum(light_color) + (light_color - lum(light_color)) * lit_mask
    lit = amb_l * min(amb_lum, 1.5) * 0.35 * env_ao + lc_desat
    return shadow_amb + (lit - shadow_amb) * shadow


def split(amb, lit_mask, shadow, sky01, block_level, sky_level, sky_darken, light_color, sky_color, best_at_eyes,
          share):
    full = main_light(amb, lit_mask, shadow, sky01, light_color, sky_color)
    explained = min(max(brightness(best_at_eyes) / max(brightness(block_level), 1e-4), 0), 1)
    block_share = np.maximum(amb - lightmap(0, sky_level, sky_darken), 0)
    rest = main_light(amb - block_share * share * explained, lit_mask, shadow, sky01, light_color, sky_color)
    return rest, np.maximum(full - rest, 0)


def local(gain, albedo, n, l_dir, back, shadow, at_eyes, level_here, intensity=1.0):
    mean_f = 0.5 * (1 + back)
    falloff = min(brightness(level_here) / max(brightness(at_eyes), 1e-4), 2.0)
    c = gain * falloff / mean_f  # one light: its share of the brightness at her eyes is 1
    c = c / max(1.0, c.max() * (0.75 + (0.5 - 0.75) * (1 - shadow)))
    f = back + (1 - back) * np.clip(n @ l_dir + 0.5, 0, 1)
    return np.outer(f, c * albedo) * intensity


def albedo_of(name):
    from PIL import Image
    a = np.asarray(Image.open(f'{TEX}/t_actor_pelica_{name}_d.tga.png').convert('RGBA')).astype(float) / 255
    a = a.reshape(-1, 4)
    a = a[a[:, 3] > 0.5, :3]
    return np.median(a, axis=0)


def sphere_normals(n=4000):
    i = np.arange(n) + 0.5
    phi = np.arccos(1 - 2 * i / n)
    th = math.pi * (1 + 5 ** 0.5) * i
    return np.stack([np.cos(th) * np.sin(phi), np.sin(th) * np.sin(phi), np.cos(phi)], 1)


def part1(share):
    classes = {  # name: texture, grade (brightness, saturation), back contrast
        'cloth': ('cloth_01', (0.5, 1.0), 0.25), 'skin': ('body_01', (0.55, 1.4), 0.5),
        'hair': ('hair_01', (0.6, 1.0), 0.25)}
    normals = sphere_normals()
    l_dir = np.array([1.0, 0.0, 0.0])
    scenes = {  # sky level, sky darken, main light colour, sky colour, X (main light shadow) and lit mask
        'night, open sky': (15, 0.2, np.array([0.22, 0.26, 0.36]), np.array([0.02, 0.03, 0.08]), 1.0, 1.0),
        'cave (sky 0)': (0, 0.2, np.array([0.22, 0.26, 0.36]), np.array([0.02, 0.03, 0.08]), 0.0, 0.0)}
    rows = []
    for scene, (sky_level, darken, lc, sc, shadow, lit_mask) in scenes.items():
        for d in (1, 3, 6):
            block = 14 - d  # torch at d blocks (Manhattan) from her eye block
            amb = lightmap(block, sky_level, darken)
            for cls, (tex, (gb, gs), back) in classes.items():
                a = albedo_of(tex) * 0.96
                # albedoF: lit main light -> diff; X = 0 (cave) -> aoAlb with aoMask 1 = Grade(diff, 1, 1.2)
                alb = a if shadow > 0.5 else grade(a, 1.0, 1.2)
                old = main_light(amb, lit_mask, shadow, sky_level / 15, lc, sc) * alb
                rest, gain = split(amb, lit_mask, shadow, sky_level / 15, block, sky_level, darken, lc, sc,
                                   14 - d, share)
                base = rest * alb
                lit = base + local(gain, alb, l_dir[None], l_dir, back, shadow, block, block)[0]
                dark = base + local(gain, alb, -l_dir[None], l_dir, back, shadow, block, block)[0]
                mean = (base[None] + local(gain, alb, normals, l_dir, back, shadow, block, block)).mean(0)
                feet = min(brightness(block - 1.6) / brightness(block), 2.0)
                rows.append((scene, d, cls, lum(old), lum(lit), lum(dark), lum(lit) / max(lum(dark), 1e-6),
                             lum(mean) / max(lum(old), 1e-6), feet))
    print(f'\n== local lights, localLightShare {share} (luminance of lightCol x albedoF, before exposure) ==')
    print(f'{"scene":16s} {"d":>2s} {"class":5s} {"old":>7s} {"lit":>7s} {"dark":>7s} {"lit/dark":>8s} '
          f'{"mean/old":>8s} {"feet":>5s}')
    for r in rows:
        print(f'{r[0]:16s} {r[1]:2d} {r[2]:5s} {r[3]:7.4f} {r[4]:7.4f} {r[5]:7.4f} {r[6]:8.2f} {r[7]:8.3f} '
              f'{r[8]:5.2f}')
    return rows


# ---------------------------------------------------------------- hair depth rim
W, H, FOV = 1920, 1080, 70.0
RIM_WIDTH = 0.5
RADIUS = 0.12


def rim_line(dist, behind):
    """Rim masks along the horizontal line through a hair sphere's centre at dist blocks; behind: background depth
    offset from the sphere centre (blocks)."""
    t = math.tan(math.radians(FOV) / 2)
    xs = np.arange(W) + 0.5
    ndc = xs / W * 2 - 1
    dirs = np.stack([ndc * t * W / H, np.zeros(W), -np.ones(W)], 1)
    dirs /= np.linalg.norm(dirs, axis=1, keepdims=True)
    c = np.array([0, 0, -dist])
    b = dirs @ c
    disc = b * b - (c @ c - RADIUS ** 2)
    hit = disc > 0
    tt = np.where(hit, b - np.sqrt(np.maximum(disc, 0)), 0.0)
    p = dirs * tt[:, None]
    depth = np.where(hit, -p[:, 2], dist + behind)  # view depth of the depth buffer (her prepass + background)
    n = (p - c) / RADIUS
    v = -dirs
    old = np.where(hit, smoothstep(0.8 - 0.6 * RIM_WIDTH, 0.9 - 0.4 * RIM_WIDTH, 1 - np.abs(np.sum(n * v, 1))), 0)
    off_px = RIM_WIDTH * 0.006 * H  # uv offset dir * (H/W, 1) * w * .006 -> pixels: dir * w * .006 * H
    nx = np.where(hit, n[:, 0] / np.maximum(np.hypot(n[:, 0], n[:, 1]), 1e-9), 0)
    sample = np.clip(np.floor(xs + nx * off_px).astype(int), 0, W - 1)
    new = np.where(hit, smoothstep(0.1, 0.2, depth[sample] - depth), 0)
    return hit, old, new, off_px


def part2():
    print(f'\n== hair depth rim: sphere r {RADIUS} blocks, {W}x{H}, FOV {FOV}, rimWidth {RIM_WIDTH} '
          f'(offset {RIM_WIDTH * 0.006 * H:.2f} px) ==')
    print('px with mask > 0.5 on the right silhouette (old angular | new depth); new profile from the edge inward')
    for dist in (2, 4, 8, 16):
        for behind in (4.0, 0.3, 0.15, 0.08, 0.03):
            hit, old, new, _ = rim_line(dist, behind)
            idx = np.nonzero(hit)[0]
            right = idx[idx >= idx.mean()]
            prof = new[right][::-1][:6]
            print(f'  dist {dist:2d}  behind {behind:4.2f}: old {int((old[right] > 0.5).sum()):3d} px  '
                  f'new {int((new[right] > 0.5).sum()):3d} px  profile {" ".join(f"{x:.2f}" for x in prof)}'
                  f'  (sphere {len(idx)} px wide)')
    try:
        from PIL import Image
        os.makedirs(OUT, exist_ok=True)
        rows = []
        for dist in (2, 8):
            hit, old, new, _ = rim_line(dist, 4.0)
            idx = np.nonzero(hit)[0]
            lo, hi = idx.min() - 4, idx.max() + 5
            for m in (old, new):
                rows.append(np.repeat((m[lo:hi] * 255).astype(np.uint8)[None], 12, 0))
        wmax = max(r.shape[1] for r in rows)
        img = np.vstack([np.pad(r, ((0, 2), (0, wmax - r.shape[1]))) for r in rows])
        Image.fromarray(img).resize((min(wmax * 2, 800), img.shape[0] * 2), Image.NEAREST).save(
            os.path.join(OUT, 'rim_profile.png'))
    except ImportError:
        pass


if __name__ == '__main__':
    if '--pelica' in sys.argv:
        TEX = sys.argv[sys.argv.index('--pelica') + 1]
    for s in (0.5, 0.75, 1.0):
        part1(s)
    part2()
