#!/usr/bin/env python3
"""Offline numbers of the 0.8.8 sky-less dimension key (Nether, End) and the fill light (no game needed).

Mirrors NprRenderer.frameLight/dimLight (key colour, luminance), adapt/keyGate/lightLevel/fill (adapted level, gain)
and exposure, vanilla LightTexture.updateLightTexture (sky 0 in the Nether/End, forceBrightLightmap in the End,
ambientLight 0.1 in the Nether, gamma 0.5) and npr_character.fsh nprMainLight + localLights (fill slot) for a lit and a
shadow-side skin texel, through light_eval's display transform. Compares with the overworld noon daylight key.

Usage: python3 tools/dim_light_eval.py
"""
import math

import numpy as np

from light_eval import DEFAULTS, display, lightmap_sky15, lum, not_gamma, sky_darken, srgb_decode, time_of_day

GAMMA = 0.5
NETHER_HUE = 0.6
NETHER_LIGHT = 1.0  # netherLight default (key = strength x local lightmap ambient max3)
END_LIGHT = 1.0  # endLight default
END_COLOR = np.array([0.72, 0.62, 1.0])  # endLightColor default
FILL_LIGHT, FILL_TARGET, FILL_COLOR = 0.6, 0.5, np.array([0.92, 0.96, 1.0])
# biome fog colours (vanilla 1.20.1 data: nether biomes "effects.fog_color")
NETHER_FOG = {'nether_wastes': 0x330808, 'crimson_forest': 0x330303, 'warped_forest': 0x1A051A,
              'soul_sand_valley': 0x1B4745, 'basalt_deltas': 0x685F70}


def rgb24(c):
    return np.array([(c >> 16) & 255, (c >> 8) & 255, c & 255]) / 255.0


def brightness(ambient, level):
    f = level / 15.0
    f1 = f / (4.0 - 3.0 * f)
    return f1 + ambient * (1.0 - f1)  # Mth.lerp(ambientLight, f1, 1)


def lightmap(dim, sky, block, darken):
    """LightTexture pixel (no flicker: blockLightRedFlicker 0 -> f4 = 1.5, no effects)."""
    ambient = 0.1 if dim == 'nether' else 0.0
    f1 = darken * 0.95 + 0.05
    base = np.array([darken, darken, 1.0]) + (1.0 - np.array([darken, darken, 1.0])) * 0.35
    f5 = brightness(ambient, sky) * f1
    f6 = brightness(ambient, block) * 1.5
    c = np.array([f6, f6 * ((f6 * 0.6 + 0.4) * 0.6 + 0.4), f6 * (f6 * f6 * 0.6 + 0.4)])
    if dim == 'end':  # forceBrightLightmap
        c = np.clip(c + (np.array([0.99, 1.12, 1.0]) - c) * 0.25, 0, 1)
    else:
        c = c + base * f5
        c = np.clip(c + (0.75 - c) * 0.04, 0, 1)
    c = c + (np.array([not_gamma(v) for v in c]) - c) * GAMMA
    return np.clip(c + (0.75 - c) * 0.04, 0, 1)


def nether_key(fog):
    h = fog / max(fog.max(), 1e-4)
    c = h * NETHER_HUE + (1 - NETHER_HUE)
    return c * (NETHER_LIGHT / lum(c))


def level(s, key_lum, amb):
    return max(s * (key_lum + 0.35 * amb) + (1 - s) * amb, 1e-3)


def fill(lvl):
    return 0.0 if lvl >= FILL_TARGET else FILL_LIGHT * min(FILL_TARGET - lvl, FILL_TARGET)


def gain(target, day):
    lo, hi = DEFAULTS['adaptRange']
    mx = hi + (min(DEFAULTS['adaptNightMax'], hi) - hi) * (1 - day)
    return min(max((DEFAULTS['adaptReference'] / target) ** 0.9, lo), mx)  # adaptStrength 0.9


def shade(lm, key, shadow, sky_x, fill_rgb, albedo):
    """(lit side, shadow side) linear display Y of a skin texel: nprMainLight (ramp lit / shadow) + the fill slot.

    Lit: litMask 1 (ambient hue -> white), N.L fill 1 (Type0 lerp(back, 1, sat(N.L + 0.5)) = 1);
    shadow side: the ramp's shadow (litMask 0, shadow gate applied), fill seen at back 0.5 (skin) x sat(-1 + 0.5) = 0.
    """
    amb_lum = max(lm.max(), 1e-4)
    amb_hue = lm / amb_lum
    k_shadow = min(amb_lum * 0.35 + 0.65, 1.5)
    shadow_amb = amb_hue * k_shadow * amb_lum  # mapX = 1: the sceneShadowAmbient term is 1
    lit_light = shadow_amb + ((np.ones(3) * min(amb_lum, 1.5) * 0.35 + key) - shadow_amb) * shadow
    dark_light = shadow_amb  # the far side of the ramp: indirect only (shadow albedo grading left out)
    lit = lit_light * albedo + fill_rgb * albedo
    dark = dark_light * albedo + fill_rgb * albedo * 0.5  # Type0 back contrast 0.5 (skin), N.L <= -0.5
    return lit, dark


def disp(c, exposure):
    return lum(srgb_decode(display(c, exposure)))


def main():
    albedo = np.array([0.93, 0.80, 0.74])  # light skin, display space
    # overworld noon daylight reference
    tod = time_of_day(6000)
    noon_key = np.array([1.0, 0.985, 0.97])
    noon_lm = lightmap_sky15(sky_darken(tod, 0, 0))
    noon_t = level(1.0, lum(noon_key), 1.0)
    noon_g = gain(noon_t, 1.0)
    noon_lit, noon_dark = shade(noon_lm, noon_key, 1.0, 1.0, np.zeros(3), albedo)
    print(f'overworld noon: key lum {lum(noon_key):.3f} rgb {np.round(noon_key, 3)}, level {noon_t:.3f}, '
          f'gain {noon_g:.2f}, face lit/shadow display Y {disp(noon_lit, 1.3 * noon_g):.3f} / '
          f'{disp(noon_dark, 1.3 * noon_g):.3f}; fill {fill(noon_t):.3f} (0 = identical)')

    print('\nsky-less dimensions (viewer and character at block light 0 / 11; 0.8.7 = netherLight/endLight 0):')
    print(f'{"place":24s} {"key rgb at the spot (lum)":>32s} {"amb":>6s} {"level 087/088":>14s} '
          f'{"gain 087/088":>13s} {"lit Y 087/088":>14s} {"shadow Y 087/088":>17s}')
    rows = [(f'nether {n}', 'nether', rgb24(c)) for n, c in NETHER_FOG.items()] + [('end', 'end', None)]
    for name, dim, fog in rows:
        hue = nether_key(fog) if dim == 'nether' else END_COLOR * (END_LIGHT / lum(END_COLOR))
        darken = 0.2 if dim == 'nether' else 1.0
        for block in (0, 11):
            lm = lightmap(dim, 0, block, darken)
            # 0.8.7: no key reaches (sky 0), adapt amb = block, day 0 (Nether, time 18000) / 1 (End, 6000)
            day_old = 0.0 if dim == 'nether' else 1.0
            old_t = level(0.0, 0.0, block / 15.0)
            old_g = gain(old_t, day_old)
            old_lit, old_dark = shade(lm, np.zeros(3), 0.0, 0.0, np.zeros(3), albedo)
            # 0.8.8: gate open (dimGate 1), key = LightColor x lightmap max3 (keyColor), adapt amb = that max3, day 0.5
            key = hue * lm.max()
            new_t = level(1.0, lum(key), lm.max())
            new_g = gain(new_t, 0.5)
            new_lit, new_dark = shade(lm, key, 1.0, 0.0, np.zeros(3), albedo)
            print(f'{name + " b" + str(block):24s} {str(np.round(key, 3)):>24s} ({lum(key):.2f}) {lm.max():6.3f} '
                  f'{old_t:6.3f} {new_t:6.3f} {old_g:5.2f} {new_g:6.2f} '
                  f'{disp(old_lit, 1.3 * old_g):6.3f} {disp(new_lit, 1.3 * new_g):6.3f} '
                  f'{disp(old_dark, 1.3 * old_g):8.3f} {disp(new_dark, 1.3 * new_g):7.3f}')
        print(f'{"":24s} key hue {np.round(hue, 3)}; key / ambient {lum(hue):.2f} (overworld noon '
              f'{lum(noon_key) / noon_lm.max():.2f}); vs overworld noon key luminance: block 0 '
              f'{lum(hue) * lightmap(dim, 0, 0, darken).max() / lum(noon_key):.2f}, block 11 '
              f'{lum(hue) * lightmap(dim, 0, 11, darken).max() / lum(noon_key):.2f}')

    print(f'\nfill light (fillLight {FILL_LIGHT}, fillTarget {FILL_TARGET}, colour {FILL_COLOR}):')
    tod = time_of_day(18000)
    darken = sky_darken(tod, 0, 0)
    sun_y = math.cos(tod * 2 * math.pi)
    moon = np.array([0.22, 0.26, 0.36]) * min(max((-sun_y + 0.05) / 0.3, 0), 1)
    fc = FILL_COLOR / lum(FILL_COLOR)
    cases = [('overworld noon', noon_lm, noon_key, 1.0, 1.0, 1.0, 1.0),
             ('overworld night, open sky', lightmap_sky15(darken), moon, 1.0, darken, 0.0, 1.0),
             ('cave, block 0, by day', lightmap('overworld', 0, 0, 1.0), noon_key, 0.0, 0.0, 1.0, 0.0),
             ('cave, block 7, by day', lightmap('overworld', 0, 7, 1.0), noon_key, 0.0, 7 / 15, 1.0, 0.0)]
    for name, lm, key, s, amb, day, sky_x in cases:
        t = level(s, lum(key), amb)
        f = fill(t)
        g0 = gain(t, day)
        g1 = gain(t + f, day)
        l0, d0 = shade(lm, key, s, sky_x, np.zeros(3), albedo)
        l1, d1 = shade(lm, key, s, sky_x, fc * f, albedo)
        print(f'  {name:28s} level {t:.3f} fill {f:.3f}  gain {g0:.2f} -> {g1:.2f}  face lit Y '
              f'{disp(l0, 1.3 * g0):.3f} -> {disp(l1, 1.3 * g1):.3f}  shadow side Y {disp(d0, 1.3 * g0):.3f} -> '
              f'{disp(d1, 1.3 * g1):.3f}')


if __name__ == '__main__':
    main()
