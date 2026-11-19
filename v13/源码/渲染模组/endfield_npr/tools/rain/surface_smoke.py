#!/usr/bin/env python3
"""Smoke test of npr_rain.glsl's register mapping (rainSurface / rainColor) with the real rain textures, on Mesa
llvmpipe: a figure of random surface points (positions on a 1.8-block body, unit normals, mid-grey albedo) per
program family, dry and in full rain, in light rain and after rain (static drops). Checks that dry leaves the surface
untouched, that the results are finite with unit normals, and prints how much each family changes (share of pixels
with a visible change, mean albedo factor, mean smoothness).

  python3 tools/rain/surface_smoke.py
"""
import ctypes
import os
import re
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from rain_check import GL  # noqa: E402

SHADERS = os.path.join(HERE, '..', '..', 'src/main/resources/assets/endfield_npr/shaders')
TEXTURES = os.path.join(HERE, '..', '..', 'src/main/resources/assets/endfield_npr/textures/rain')
N = 4096


def include(name, seen):
    out = []
    for line in open(os.path.join(SHADERS, 'include', name)):
        m = re.match(r'\s*#moj_import\s*<endfield_npr:([^>]+)>', line)
        if m:
            if m.group(1) not in seen:
                seen.add(m.group(1))
                out.append(include(m.group(1), seen))
            continue
        # per-invocation globals here; no screen derivatives in a compute shader (the lods are set directly)
        out.append(line.replace('flat in ', '').replace('in vec3 RainObj', 'vec3 RainObj')
                   .replace('uniform vec4 RainParams', 'vec4 RainParams')
                   .replace('uniform vec2 RainSpec;', 'const vec2 RainSpec = vec2(1.0);'))
    return ''.join(out)


SRC = '''#version 430
layout(local_size_x = 64) in;
layout(std430, binding = 0) readonly buffer In { vec4 inp[]; };
layout(std430, binding = 1) writeonly buffer Out { vec4 outp[]; };
#define dFdx(x) ((x) * 0.0)
#define dFdy(x) ((x) * 0.0)
uniform mat3 ViewToPlayer;
vec3 srgbEncode(vec3 c) {
    c = max(c, 0.0);
    return mix(1.055 * pow(c, vec3(0.416667)) - 0.055, c * 12.92, vec3(lessThanEqual(c, vec3(0.003131))));
}
vec3 srgbDecode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92, vec3(lessThanEqual(c, vec3(0.04045))));
}
mat3 nprCotangentFrame(vec3 N, vec3 p, vec2 uv) { return mat3(0.0); } // rainObjT is set directly below
const vec4 NprTweak = vec4(0.5, 1.0, 1.0, 1.0); // skinRampTint, rainGlint, rainMatcap, rainDropSpeed defaults
const vec4 Globals2 = vec4(1.0);  // specScale
const vec3 WorldUp = vec3(0.0, 1.0, 0.0);
vec3 nprEnvBRDF(vec3 f0, float noV, float roughness) { return f0; } // stand-in (npr_character.fsh has the real one)
%s
void main() {
    uint i = gl_GlobalInvocationID.x;
    vec4 a = inp[i * 4u], b = inp[i * 4u + 1u], c = inp[i * 4u + 2u], d = inp[i * 4u + 3u];
    RainParams = d;
    RainBytes = c;
    RainLine = 0.8;                     // water line 0.8 blocks above the camera-relative origin
    // object space as 0.8.2's rigid body frame right (1,0,0), up (0,1,0), forward (0,0,-1), origin 0
    RainObjP = vec3(a.x, a.y, -a.z);
    rainLodObject = 1.0;
    rainLodFace = 0.0;
    vec3 p = a.xyz;
    vec3 N = normalize(b.xyz);
    vec3 up = abs(N.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 T = normalize(cross(up, N));
    mat3 frame = mat3(T, cross(N, T), N);
    RainObjN = vec3(N.x, N.y, -N.z);
    rainObjT = vec3(T.x, T.y, -T.z);
    vec3 V = normalize(vec3(0.0, 1.0, 3.0) - p);
    vec3 A = vec3(0.5, 0.45, 0.4), S = vec3(0.3, 0.25, 0.25);
    float smoothness = 0.4, spec = 0.5, metal = 0.0, receiver = 1.0;
    rainSurface(int(a.w), p, N, frame, true, V, b.xy * 0.5 + 0.5, b.z, 0.0, A, S, N, smoothness, spec, metal, receiver);
    vec3 col = rainColor(vec3(0.5), vec3(0.4), vec3(0.1), vec3(1.0), normalize(vec3(0.3, 1.0, 0.5)), V,
        vec3(0.0, 0.5, 1.0), 1.0);
    col += rainWetFilm(N, V, vec3(1.0));
    outp[i * 4u] = vec4(A, smoothness);
    outp[i * 4u + 1u] = vec4(N, spec);
    outp[i * 4u + 2u] = vec4(S, receiver);
    outp[i * 4u + 3u] = vec4(col, 0.0);
}
'''


def main():
    gl = GL()
    src = SRC % include('npr_rain.glsl', set())
    # the RainTex array on unit 0
    u, i, p = ctypes.c_uint, ctypes.c_int, ctypes.c_void_p
    gen = gl.fn('glGenTextures', None, i, ctypes.POINTER(u))
    bind = gl.fn('glBindTexture', None, u, u)
    tex3d = gl.fn('glTexImage3D', None, u, i, i, i, i, i, i, u, u, p)
    param = gl.fn('glTexParameteri', None, u, u, i)
    mip = gl.fn('glGenerateMipmap', None, u)
    tid = u()
    gen(1, ctypes.byref(tid))
    bind(0x8C1A, tid)
    layers = np.stack([np.asarray(Image.open(os.path.join(TEXTURES, 't_actor_common_rain_0%d_m.png' % k)).convert('RGBA'))
                       for k in range(1, 5)])
    layers = np.ascontiguousarray(layers)
    tex3d(0x8C1A, 0, 0x8058, 1024, 1024, 4, 0, 0x1908, 0x1401, layers.ctypes.data)
    mip(0x8C1A)
    param(0x8C1A, 0x2801, 0x2703)
    families = {1: 'face', 2: 'skin', 3: 'hair', 4: 'cloth'}
    rng = np.random.default_rng(7)
    cases = {'dry': (0, 0, 0, 0), 'full rain, drenched': (255, 0, 255, 0), 'light rain, damp': (60, 0, 50, 0),
             'after rain (static drops)': (0, 0, 200, 0), 'wading only': (0, 255, 0, 0)}
    bad = False
    for case, bytes_ in cases.items():
        data = np.zeros((N, 4, 4), np.float32)
        h = rng.uniform(0.0, 1.8, N)
        ang = rng.uniform(0, 2 * np.pi, N)
        data[:, 0, 0] = 0.18 * np.cos(ang)
        data[:, 0, 1] = h
        data[:, 0, 2] = 0.18 * np.sin(ang)
        data[:, 0, 3] = np.repeat(list(families), N // 4)
        nrm = np.stack([np.cos(ang), rng.normal(0, 0.4, N), np.sin(ang)], 1)
        data[:, 1, :3] = nrm / np.linalg.norm(nrm, axis=1, keepdims=True)
        data[:, 2] = bytes_
        data[:, 3] = (1.0, rng.uniform(0, 600), 2.25, 1.7)
        src_full = src.replace('uniform mat3 ViewToPlayer;', 'const mat3 ViewToPlayer = mat3(1.0);')
        out = gl.run(src_full, data, N * 16, N // 64).reshape(N, 4, 4)
        print('== %s (bytes %s)' % (case, bytes_))
        for fam, name in families.items():
            o = out[data[:, 0, 3] == fam]
            alb = o[:, 0, :3]
            nrm_out = o[:, 1, :3]
            finite = np.isfinite(o).all()
            unit = np.abs(np.linalg.norm(nrm_out, axis=1) - 1).max()
            factor = (alb.mean(1) / np.array([0.5, 0.45, 0.4]).mean())
            changed = (np.abs(alb - [0.5, 0.45, 0.4]).max(1) > 2 / 255) | (np.abs(o[:, 0, 3] - 0.4) > 2 / 255)
            ndev = np.degrees(np.arccos(np.clip((nrm_out * data[data[:, 0, 3] == fam, 1, :3]).sum(1), -1, 1)))
            print('  %-5s finite %s  |N|-1 max %.1e  changed %5.1f%%  albedo x%.3f  smooth %.3f  normal moved %.1f deg '
                  '(max %.0f)  colour changed %4.1f%% (max %+.3f)'
              % (name, finite, unit, 100 * changed.mean(), factor.mean(), o[:, 0, 3].mean(), ndev.mean(), ndev.max(),
                 100 * (np.abs(o[:, 3, :3] - 0.5).max(1) > 1e-3).mean(), (o[:, 3, :3] - 0.5).max()))
            if not finite or unit > 1e-3 or (case == 'dry' and changed.any()):
                bad = True
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
