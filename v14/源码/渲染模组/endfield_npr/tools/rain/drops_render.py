#!/usr/bin/env python3
"""Offline render of the rain drops on a patch of skin and of cloth (npr_rain.glsl with the real rain textures, Mesa
llvmpipe compute, no window): the game's drops (rainDropDensity 1, rainDropJitter 0, rainDropSpeedJitter 0,
rainDropSlide 0, rainDropSpeed 1), 0.8.6 (0.5, 1, 0.4, slide 0, speed 3) and the 0.8.7 defaults (slide 0.05 b/s), in
full rain.

The patch: a square of a vertical cylinder of radius 0.18 (a torso, front side) at chest height, object space = the
rigid body frame (as surface_smoke.py), rainTiling 5, seen from 3 blocks in front; 2x supersampled.
  <family>_dropmap.png  the drops' normal tilt (white = 25 degrees) on 0.3 and 0.12 blocks: layout and density
  <family>_frames.png   shaded 0.12-block close-ups 0.25 s apart over 1.75 s (0.8.6 row, 0.8.7 row): a stand-in
                        shading (lit albedo, a Blinn highlight and a sky reflection on the normal after the drops, then
                        rainColor's game terms and the wet film) to make the drops readable
  <family>_slide.gif    the 0.8.7 row as an animation (0.25 s per frame)
  <family>_tracks.png   each pixel's strongest drop over 1 s at 0.05 s steps, coloured by time (0.8.6 | 0.8.7)

  python3 tools/rain/drops_render.py [out dir]      (default ~/ysm/handoff/raindrops)
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from rain_check import GL  # noqa: E402
import surface_smoke  # noqa: E402

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser('~/ysm/handoff/raindrops')
PX = 448          # output pixels per side
SS = 2            # supersampling
TILING = 5.0

SRC = '''#version 430
layout(local_size_x = 64) in;
layout(std430, binding = 0) readonly buffer In { vec4 inp[]; };
layout(std430, binding = 1) writeonly buffer Out { vec4 outp[]; };
#define dFdx(x) ((x) * 0.0)
#define dFdy(x) ((x) * 0.0)
const mat3 ViewToPlayer = mat3(1.0);
vec3 srgbEncode(vec3 c) {
    c = max(c, 0.0);
    return mix(1.055 * pow(c, vec3(0.416667)) - 0.055, c * 12.92, vec3(lessThanEqual(c, vec3(0.003131))));
}
vec3 srgbDecode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92, vec3(lessThanEqual(c, vec3(0.04045))));
}
mat3 nprCotangentFrame(vec3 N, vec3 p, vec2 uv) { return mat3(0.0); }
const vec4 NprTweak = vec4(0.5, 1.0, 1.0, @SPEED@);
const vec4 Globals2 = vec4(1.0);
const vec3 WorldUp = vec3(0.0, 1.0, 0.0);
vec3 nprEnvBRDF(vec3 f0, float noV, float roughness) { return f0 + (1.0 - f0) * pow(1.0 - noV, 5.0) * 0.5; }
%s
void main() {
    uint i = gl_GlobalInvocationID.x;
    vec4 a = inp[i * 2u], b = inp[i * 2u + 1u];
    RainParams = vec4(1.0, @TIME@, @TILING@, 1.7);
    RainBytes = vec4(255.0, 0.0, 255.0, 0.0);
    RainLine = -1000.0;
    RainObjP = vec3(a.x, a.y, -a.z);
    rainLodObject = @LOD@;
    rainLodFace = 0.0;
    vec3 p = a.xyz;
    vec3 N0 = normalize(b.xyz);
    vec3 N = N0;
    vec3 T = normalize(cross(vec3(0.0, 1.0, 0.0), N));
    mat3 frame = mat3(T, cross(N, T), N);
    RainObjN = vec3(N.x, N.y, -N.z);
    rainObjT = vec3(T.x, T.y, -T.z);
    vec3 V = normalize(vec3(0.0, 1.1, 3.0) - p);
    bool skin = int(a.w) == RAIN_SKIN;
    vec3 A = skin ? vec3(0.93, 0.76, 0.68) : vec3(0.22, 0.26, 0.36);
    vec3 S = A * vec3(0.75, 0.6, 0.65);
    float smoothness = skin ? 0.45 : 0.3, spec = 0.5, metal = 0.0, receiver = 1.0;
    rainSurface(int(a.w), p, N, frame, true, V, vec2(0.5), 0.5, 0.0, A, S, N, smoothness, spec, metal, receiver);
    vec3 L = normalize(vec3(-0.45, 0.75, 0.6));
    vec3 H = normalize(L + V);
    float shininess = exp2(10.0 * smoothness + 1.0);
    vec3 diffuse = mix(S, A, smoothstep(-0.1, 0.3, dot(N, L)));
    vec3 hl = vec3(pow(max(dot(N, H), 0.0), shininess) * (0.25 + smoothness));
    vec3 col = rainColor(diffuse + hl, diffuse, hl, vec3(1.0), L, V, vec3(0.0, 0.5, 1.0), 1.0);
    col += rainWetFilm(N, V, vec3(1.0));
    // a bright sky above, dark ground below, reflected by the normal after the drops (the drops read as in daylight)
    vec3 R = reflect(-V, N);
    col += vec3(0.9, 0.95, 1.0) * smoothstep(0.1, 0.5, R.y) * 0.35 * smoothness;
    outp[i * 2u] = vec4(col, 1.0);
    outp[i * 2u + 1u] = vec4(acos(clamp(dot(N, N0), -1.0, 1.0)), 0.0, 0.0, 1.0); // the drops' normal tilt
}
'''


def patch(family, size):
    n = PX * SS
    s = (np.arange(n) + 0.5) / n
    ang = (s - 0.5) * size / 0.18           # arc length -> angle on the r = 0.18 cylinder
    y = 1.25 - s * size                       # rows top-down
    A, Y = np.meshgrid(ang, y)
    data = np.zeros((n * n, 2, 4), np.float32)
    data[:, 0, 0] = (0.18 * np.sin(A)).ravel()
    data[:, 0, 1] = Y.ravel()
    data[:, 0, 2] = (0.18 * np.cos(A)).ravel()
    data[:, 0, 3] = family
    data[:, 1, 0] = np.sin(A).ravel()
    data[:, 1, 2] = np.cos(A).ravel()
    return data


def render(gl, family, drops, speed, time, size):
    """-> (shaded image, drop map: the normal tilt of the drops, white at 25 degrees)"""
    src = (SRC % surface_smoke.include('npr_rain.glsl', set())).replace(
        'const vec4 RainDrops = vec4(%s);' % surface_smoke.DROPS, 'const vec4 RainDrops = vec4(%s);' % drops)
    lod = np.log2(1024 * TILING * size / (PX * SS))  # texels per (supersampled) pixel of the cloth textures
    for k, v in (('SPEED', speed), ('TIME', time), ('TILING', TILING), ('LOD', lod)):
        src = src.replace('@%s@' % k, '%r' % float(v))
    n = PX * SS
    out = gl.run(src, patch(family, size), n * n * 8, n * n // 64).reshape(n, n, 2, 4)
    out = out.reshape(PX, SS, PX, SS, 2, 4).mean((1, 3))
    shaded = np.clip(out[:, :, 0, :3], 0, 1)
    tilt = np.clip(out[:, :, 1, 0] / np.radians(25.0), 0, 1)
    return [Image.fromarray((x * 255 + 0.5).astype(np.uint8)).convert('RGB') for x in (shaded, tilt)]


def tracks(gl, family, drops, speed, t0):
    """Each pixel's strongest drop tilt over 1 s (0.05 s steps) on the 0.12-block patch, coloured by when it peaked
    (blue at t0 -> red at t0 + 1 s): sliding drops leave tapered blue-to-red streaks, drops in place dots."""
    peak = np.zeros((PX, PX))
    when = np.zeros((PX, PX))
    for j in range(21):
        m = np.asarray(render(gl, family, drops, speed, t0 + 0.05 * j, 0.12)[1])[:, :, 0] / 255.0
        newer = m > peak
        peak = np.where(newer, m, peak)
        when = np.where(newer, j / 20.0, when)
    rgb = np.stack([when, np.full_like(when, 0.25), 1.0 - when], -1) * peak[:, :, None]
    return Image.fromarray((np.clip(rgb, 0, 1) * 255 + 0.5).astype(np.uint8))


def label(img, text):
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 6 * len(text) + 8, 14), fill=(0, 0, 0))
    d.text((4, 1), text, fill=(255, 255, 255))
    return img


def sheet(rows):
    """rows of (image, label) -> one image, 6 px gaps"""
    w = max(len(r) for r in rows)
    out = Image.new('RGB', (w * PX + 6 * (w - 1), len(rows) * PX + 6 * (len(rows) - 1)), (40, 40, 40))
    for i, r in enumerate(rows):
        for j, (im, text) in enumerate(r):
            out.paste(label(im.copy(), text), (j * (PX + 6), i * (PX + 6)))
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    gl = GL()
    surface_smoke.rain_textures(gl)
    variants = [('game (density 1, jitter 0, slide 0, speed 1)', '1.0, 0.0, 0.0, 0.0', 1.0),
                ('0.8.6 (density 0.5, jitter 1, speed 3 +-40%, slide 0)', '0.5, 1.0, 0.4, 0.0', 3.0),
                ('0.8.7 (slide 0.05 b/s)', surface_smoke.DROPS, 3.0)]
    frames = [100.0 + 0.25 * j for j in range(8)]
    for family, name in ((2, 'skin'), (4, 'cloth')):
        # drop maps: a 0.3-block patch and a 0.12-block close-up, game | 0.8.6 | 0.8.7
        rows = [[(render(gl, family, d, s, frames[0], size)[1], '%s, %.2f blocks' % (t, size)) for t, d, s in variants]
                for size in (0.3, 0.12)]
        sheet(rows).save(os.path.join(OUT, '%s_dropmap.png' % name))
        # shaded close-ups over 1.75 s (0.8.6 row, 0.8.7 row)
        rows = [[(render(gl, family, d, s, f, 0.12)[0], ('%s ' % t if j == 0 else '') + 't+%.2f s' % (f - frames[0]))
                 for j, f in enumerate(frames)] for t, d, s in variants[1:]]
        sheet(rows).save(os.path.join(OUT, '%s_frames.png' % name))
        gif = [label(im.copy(), t) for im, t in rows[1]]
        gif[0].save(os.path.join(OUT, '%s_slide.gif' % name), save_all=True, append_images=gif[1:], duration=250, loop=0)
        rows = [[(tracks(gl, family, d, s, frames[0]), '%s: drop tracks over 1 s (blue -> red)' % t)
                 for t, d, s in variants[1:]]]
        sheet(rows).save(os.path.join(OUT, '%s_tracks.png' % name))
        print('wrote', *(os.path.join(OUT, '%s_%s' % (name, x))
                         for x in ('dropmap.png', 'frames.png', 'slide.gif', 'tracks.png')))


if __name__ == '__main__':
    main()
