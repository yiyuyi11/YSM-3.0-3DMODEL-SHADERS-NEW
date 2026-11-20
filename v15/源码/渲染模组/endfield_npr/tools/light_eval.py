#!/usr/bin/env python3
"""Offline evaluator of the NPR character brightness against the scene (no game needed).

Replicates, per condition (time of day, rain, thunder), with the viewer and character under open sky (sky light 15,
block light 0):
  - vanilla: DimensionType.timeOfDay, ClientLevel.getSkyDarken, LightTexture.updateLightTexture (sky 15 / block 0);
  - NprRenderer.frameLight (key light colour), adapt (light level), exposure (gain), and the new scene-tracking terms;
  - npr_character.fsh for a lit white-dress texel: lightCol = (0.35 * clamp(ambLum) + LcDesat) * lightScale, times the
    display-space albedo, then displayTransform (pow 2.2 x exposure -> ACES_modified -> sat -> sRGB -> post sat).
The scene side is the measured Bliss frame (grass under open sky in ~/mc/shots/m_*, same harness and pack as the
user's Bliss_v2.1.2_YSM-NPR, only CRLF differences); the dress albedo is calibrated on the noon frame with
adaptation off, and the other "off"/"on" frames validate the model.

Usage: python3 tools/light_eval.py [--shots ~/mc/shots]
"""
import math
import os
import sys

import numpy as np

GAMMA_OPTION = 0.5  # options.txt gamma (Moody 0, Bright 1); the harness and a default install use 0.5


# ---------------------------------------------------------------- vanilla
def frac(x):
    return x - math.floor(x)


def time_of_day(day_time):
    d = frac(day_time / 24000.0 - 0.25)
    e = 0.5 - math.cos(d * math.pi) / 2.0
    return (d * 2.0 + e) / 3.0


def sky_darken(tod, rain, thunder):
    g = 1.0 - (math.cos(tod * 2 * math.pi) * 2.0 + 0.2)
    g = min(max(g, 0.0), 1.0)
    g = 1.0 - g
    g *= 1.0 - rain * 5.0 / 16.0
    g *= 1.0 - thunder * 5.0 / 16.0
    return g * 0.8 + 0.2


def not_gamma(x):
    y = 1.0 - x
    return 1.0 - y * y * y * y


def lightmap_sky15(f):
    """LightTexture pixel for sky 15, block 0 (no flash, no effects)."""
    g = f * 0.95 + 0.05
    base = np.array([f, f, 1.0]) + (np.array([1.0, 1.0, 1.0]) - np.array([f, f, 1.0])) * 0.35
    c = base * (1.0 * g)  # getBrightness(overworld, 15) = 1; block part p = 0 -> (0,0,0)
    c = c + (0.75 - c) * 0.04
    c = np.clip(c, 0, 1)
    ng = np.array([not_gamma(v) for v in c])
    c = c + (ng - c) * max(0.0, GAMMA_OPTION)
    c = c + (0.75 - c) * 0.04
    return np.clip(c, 0, 1)


def lum(c):
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


# ---------------------------------------------------------------- NprRenderer
DEFAULTS = dict(sunScale=1.0, lightScale=1.0, ambientScale=1.0, tonemapExposure=1.3, adaptReference=1.337,
                adaptStrength=1.3, adaptRange=(0.5, 8.0), adaptNightMax=2.5)


def frame_light(tod, rain, thunder, new):
    ang = tod * 2 * math.pi
    sun_y = math.cos(ang)
    day = min(max((sun_y + 0.05) / 0.3, 0), 1)
    col = np.array([1.0, 0.985, 0.97]) * day
    if sun_y < 0:
        col = np.array([0.22, 0.26, 0.36]) * min(max((-sun_y + 0.05) / 0.3, 0), 1)
    warm = 1 - min(max(abs(sun_y) / 0.35, 0), 1)
    col = col * np.array([1, 1 - 0.25 * warm, 1 - 0.45 * warm])
    weather = 1 - 0.6 * rain
    if new:  # thunder darkens the key light like the sky (vanilla getSkyDarken: (1 - 5/16 thunder))
        weather *= 1 - 5.0 / 16.0 * thunder
    col = col * DEFAULTS['sunScale'] * weather
    return col, day, sun_y


def adapt_target(col, f):
    amb = f * 1.0 * DEFAULTS['ambientScale']  # max(skyDarken * sky, block), sky = 1, s = 1 under open sky
    key = lum(col) * DEFAULTS['lightScale']
    return max(key + 0.35 * amb, 1e-3)


def gain_old(t, day):
    lo, hi = DEFAULTS['adaptRange']
    mx = hi + (min(DEFAULTS['adaptNightMax'], hi) - hi) * (1 - day)
    return min(max((DEFAULTS['adaptReference'] / t) ** DEFAULTS['adaptStrength'], lo), mx)


# ---------------------------------------------------------------- npr_character.fsh
SRGB_TO_AP1 = np.array([[0.613191, 0.339511, 0.047368], [0.070208, 0.916335, 0.013450],
                        [0.020616, 0.109566, 0.869612]])
AP1_TO_SRGB = np.array([[1.705052, -0.621791, -0.083259], [-0.130257, 1.140803, -0.010548],
                        [-0.024003, -0.128969, 1.152972]])
AP1_LUM = np.array([0.272229, 0.674082, 0.053689])


def srgb_encode(c):
    c = np.maximum(c, 0)
    return np.where(c <= 0.003131, c * 12.92, 1.055 * np.power(c, 0.416667) - 0.055)


def srgb_decode(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.04045, c / 12.92, np.power((c + 0.055) / 1.055, 2.4))


def aces(x):
    return np.clip(x * (x * 2.785085 + 0.107772) / (x * (x * 2.936045 + 0.887122) + 0.806889), 0, 1)


def display(c, exposure, sat=0.93, post=0.85):
    lin = np.power(np.maximum(c, 0), 2.2) * exposure
    ap1 = SRGB_TO_AP1 @ lin
    t = aces(ap1)
    t = np.dot(t, AP1_LUM) + (t - np.dot(t, AP1_LUM)) * sat
    s = AP1_TO_SRGB @ t
    hl = min(max((np.dot(ap1, AP1_LUM) - 0.5) * 0.6666667, 0), 1)
    s = np.clip(s + (np.clip(s / max(s.max(), 1e-5), 0, 1) - s) * hl, 0, 1)
    s = srgb_encode(s)
    l = 0.2126 * s[0] + 0.7152 * s[1] + 0.0722 * s[2]
    return np.clip(l + (s - l) * post, 0, 1)


def dress_display_Y(albedo, col, lm, exposure, eye_gain=1.0):
    amb_lum = max(lm.max() * DEFAULTS['ambientScale'], 1e-4)
    light = (np.ones(3) * min(amb_lum, 1.5) * 0.35 + col) * DEFAULTS['lightScale']  # lit: ambL -> white, LcDesat = Lc
    c = light * albedo * eye_gain
    return lum(srgb_decode(display(c, exposure)))


# ---------------------------------------------------------------- adaptation, 0.6.2 vs 0.6.3 (NprRenderer.adapt/exposure)
def level_and_gain(day_time, rain, thunder, legacy, strength=0.9, weather=0.0):
    """(key colour, lightmap, adapted level, gain) as NprRenderer computes them under open sky (s = 1)."""
    tod = time_of_day(day_time)
    col, day, _ = frame_light(tod, rain, thunder, False)
    clear_col, _, _ = frame_light(tod, 0.0, 0.0, False)
    darken = sky_darken(tod, rain, thunder)
    lm = lightmap_sky15(darken)
    target = adapt_target(col, darken)
    if legacy:
        strength, weather = 1.3, 1.0
    if weather < 1.0:
        dim = (1 - rain * 5 / 16) * (1 - thunder * 5 / 16)
        amb_clear = min(0.2 + (darken - 0.2) / dim, 1.0) * DEFAULTS['ambientScale']
        clear = max(lum(clear_col) * DEFAULTS['lightScale'] + 0.35 * amb_clear, 1e-3)
        target = math.exp((1 - weather) * math.log(clear) + weather * math.log(target))
    lo, hi = DEFAULTS['adaptRange']
    mx = hi + (min(DEFAULTS['adaptNightMax'], hi) - hi) * (1 - day)
    gain = min(max((DEFAULTS['adaptReference'] / target) ** strength, lo), mx)
    return col, lm, target, gain


CONDITIONS = [  # name, time, rain, thunder, harness frame pair (m_<x>_off/on) or None
    ('noon', 6000, 0, 0, 'noon'), ('late afternoon', 12000, 0, 0, None), ('sunset', 12500, 0, 0, 'dusk'),
    ('dusk', 13000, 0, 0, None), ('night', 18000, 0, 0, 'night'), ('rain noon', 6000, 1, 0, None),
    ('thunder noon', 6000, 1, 1, 'thunder'), ('rain sunset', 12500, 1, 0, None)]
# Bliss on screen, grass luminance relative to clear noon (harness frames; rain/thunder frames carry llvmpipe
# artefacts, so their values are ranges read from the clean grass patches)
SCENE = {'noon': (1.0, '1.00'), 'sunset': (0.455, '0.455'), 'night': (0.43, '0.43'),
         'thunder noon': (0.26, '0.20-0.40'), 'rain noon': (0.19, '0.15-0.45'), 'rain sunset': (0.17, '0.12-0.40')}


def _ylin(path):
    from PIL import Image
    a = np.asarray(Image.open(path).convert('RGB')).astype(float) / 255.0
    a = np.where(a <= 0.04045, a / 12.92, ((a + 0.055) / 1.055) ** 2.4)
    return a @ np.array([0.2126, 0.7152, 0.0722])


def _char_mask(shots):
    """The character's pixels: where the sunset frames with adaptation on and off differ (static scene)."""
    from scipy import ndimage as ndi
    on, off = _ylin(f'{shots}/m_dusk_on.png'), _ylin(f'{shots}/m_dusk_off.png')
    m = ndi.binary_opening(np.abs(on - off) > 0.02, iterations=2)
    lab, n = ndi.label(m)
    m = lab == (np.argmax(ndi.sum(m, lab, range(1, n + 1))) + 1)
    return ndi.binary_erosion(ndi.binary_fill_holes(m), iterations=2)


def main():
    shots = os.path.expanduser(sys.argv[sys.argv.index('--shots') + 1] if '--shots' in sys.argv else '~/mc/shots')
    have_shots = os.path.exists(f'{shots}/m_dusk_on.png')
    # dress albedo (display space) from the noon frame with adaptation off: lit white dress median 0.812
    noon_col, noon_lm, _, _ = level_and_gain(6000, 0, 0, True)
    lo_a, hi_a = 0.05, 1.0
    for _ in range(60):
        mid = (lo_a + hi_a) / 2
        lo_a, hi_a = (mid, hi_a) if dress_display_Y(np.full(3, mid), noon_col, noon_lm, 1.3) < 0.812 else (lo_a, mid)
    albedo = np.full(3, lo_a)
    noon_dress = dress_display_Y(albedo, noon_col, noon_lm, 1.3)

    relight = None
    if have_shots:
        xs = np.exp(np.linspace(math.log(1e-5), math.log(60), 4000))
        table = np.array([lum(srgb_decode(display(np.full(3, x ** (1 / 2.2)), 1.0))) for x in xs])
        mask = _char_mask(shots)

        def relight(name, gain):  # off frame (gain 1) -> character mean with this gain, per pixel through the curve
            y = _ylin(f'{shots}/m_{name}_off.png')[mask]
            return np.interp(np.interp(y, table, xs) * gain, xs, table).mean()
        noon_char = relight('noon', 1.0)
        for name, measured_gain in (('dusk', 3.351), ('thunder', 2.851), ('night', 6.2)):  # validation
            meas = _ylin(f'{shots}/m_{name}_on.png')[mask].mean()
            print(f'validation {name:8s}: frame with gain {measured_gain:.2f} measured {meas:.3f}, '
                  f'model {relight(name, measured_gain):.3f}')

    print(f'\n{"condition":15s} {"level":>12s} {"gain":>11s} {"dress Y":>13s}  {"Bliss grass":>11s}  '
          f'{"char/scene vs noon (dress)":>27s}  {"(whole char, frames)":>20s}')
    print(f'{"":15s} {"0.6.2  0.6.3":>12s} {"0.6.2 0.6.3":>11s} {"0.6.2  0.6.3":>13s}  {"rel. noon":>11s}  '
          f'{"0.6.2  0.6.3":>27s}  {"0.6.2  0.6.3":>20s}')
    for name, t, r, th, frame in CONDITIONS:
        col, lm, lvl_o, g_o = level_and_gain(t, r, th, True)
        _, _, lvl_n, g_n = level_and_gain(t, r, th, False)
        y_o = dress_display_Y(albedo, col, lm, 1.3 * g_o)
        y_n = dress_display_Y(albedo, col, lm, 1.3 * g_n)
        scene = SCENE.get(name)
        rel = (f'{y_o / noon_dress / scene[0]:5.2f}  {y_n / noon_dress / scene[0]:5.2f}' if scene else '    -      -')
        whole = '    -      -'
        if relight and frame:
            whole = f'{relight(frame, g_o) / noon_char / scene[0]:5.2f}  {relight(frame, g_n) / noon_char / scene[0]:5.2f}'
        print(f'{name:15s} {lvl_o:5.3f} {lvl_n:6.3f} {g_o:5.2f} {g_n:5.2f} {y_o:6.3f} {y_n:6.3f}  '
              f'{scene[1] if scene else "-":>11s}  {rel:>27s}  {whole:>20s}')


if __name__ == '__main__':
    main()
