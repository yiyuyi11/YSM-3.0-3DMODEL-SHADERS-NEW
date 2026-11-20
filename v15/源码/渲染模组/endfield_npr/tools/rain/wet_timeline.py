#!/usr/bin/env python3
"""Before/after timeline of the wet look in and out of the water (waterDrip, waterDripTime, waterLineHold).

1. State: tools' WetTimeline.java (src/test) steps RainWetState per game tick through wading, swimming and rain
   scenarios and prints the per-draw values every 0.5 s; it runs against the BEFORE tree's RainWetState.java (default
   ~/ysm/npr/work-merged, master), this tree's, and this tree's with "off" (waterDrip 0, waterLineHold false), which
   must print exactly what BEFORE prints.
2. Look: each tree's npr_rain.glsl (as surface_smoke.py runs it, Mesa llvmpipe, real rain textures) on small patches
   at the sample times with that tree's values: bare skin and dark cloth at the thigh (0.55 blocks above the feet),
   the waist (1.0, just above the 0.9 wading line) and the chest (1.25), hair at the chest, the face; a stand-in
   shading (npr_character.fsh's terms: toon diffuse, skin/cloth GGX, cloth environment reflection with rainEnvRough,
   a hair strand highlight with rainHairSpec, then rainColor and rainWetFilm), sunlit, seen from 2 blocks (0.3-block
   patch = 120 px, about 1080p at FOV 70). Per patch, against the same patch dry:
     vis   share of pixels visibly changed (any channel > 8/255)
     dL    mean luminance change, % (negative = darker)
     drop  share of pixels on a drop or drip (normal tilted > 10 degrees)
     mov   share of pixels that change by > 8/255 within 0.25 s (running drops and drips)
   Patches under the water surface at that moment are marked `~` (the water hides them in game).
3. All normals: the same shading on a sunlit sphere (radius 0.15, 120 px, 2 blocks: highlights and grazing angles
   included) of skin, dark cloth, light cloth (thigh height) and hair, at a few moments of the scenarios, with hiL =
   luminance change of the dry sphere's brightest 5 % (the highlight: the gloss of wet cloth shows there).

  python3 tools/rain/wet_timeline.py [--before TREE] [--out DIR]   (default out: ~/ysm/handoff/wetwater)
Writes DIR/timeline.md (tables), DIR/<scenario>_strip.png (patches over time, before | after) and
DIR/swim_drip_closeup.gif (0.1-block close-ups 2 s out of the water, before | after, 0.25 s per frame).
"""
import argparse
import ctypes
import csv
import io
import os
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, HERE)
from rain_check import GL  # noqa: E402
import surface_smoke  # noqa: E402

JAVA = 'src/main/java/com/ysmnpr/endfield/RainWetState.java'
DRIVER = os.path.join(ROOT, 'src/test/java/com/ysmnpr/endfield/WetTimeline.java')
PX = 120             # patch pixels per side
SIZE = 0.3           # patch side in blocks
TILING = 5.0         # rainTiling default
LOD_OBJ = np.log2(1024 * TILING * SIZE / PX)   # texels per pixel of the body textures
LOD_FACE = np.log2(1024 / (PX * 0.24 / SIZE))  # the face rect (~0.24 blocks) at the same scale
THRESH = 8 / 255.0
# (label, material, height above the feet); material % 10 = the family as npr_rain.glsl (1 face, 2 skin, 3 hair,
# 4 cloth); 4 = dark cloth (navy, lum 0.25), 14 = light cloth (lum 0.76: the game darkens bright cloth when wet)
PATCHES = [('skin thigh', 2, 0.55), ('dark cloth thigh', 4, 0.55), ('light cloth thigh', 14, 0.55),
           ('light cloth waist', 14, 1.0), ('skin chest', 2, 1.25), ('light cloth chest', 14, 1.25), ('hair', 3, 1.25),
           ('face', 1, 1.55)]
SAMPLES = {
    'wade': [1, 3, 8, 15, 16.5, 18, 22, 26, 30, 34, 40, 60, 120, 200],
    'swim': [1, 8, 15, 16.5, 18, 22, 26, 30, 34, 40, 60, 120, 200],
    'tread': [1, 6, 9.5, 14, 20, 30, 40, 41.5, 45, 50, 60, 90, 200],
    'rain-wade': [30, 61, 68, 73, 74.5, 80, 100, 134, 160, 200],
    'rain-swim': [20, 35, 44.5, 46, 50, 60, 74.5, 76, 100, 200]}
# water surface above the feet per scenario and time (as WetTimeline's segments), to mark hidden patches
SEGS = {
    'wade': [(2, 0, 0), (2, 0, 0.9), (10, 0.9, 0.9), (2, 0.9, 0), (184, 0, 0)],
    'swim': [(2, 0, 0), (1, 0, 2.5), (10, 2.5, 2.5), (3, 2.5, 0), (184, 0, 0)],
    'tread': [(2, 0, 0), (1, 0, 2.5), (5, 2.5, 2.5), (1, 2.5, 0.9), (30, 0.9, 0.9), (2, 0.9, 0), (159, 0, 0)],
    'rain-wade': [(60, 0, 0), (2, 0, 0.9), (10, 0.9, 0.9), (2, 0.9, 0), (60, 0, 0), (66, 0, 0)],
    'rain-swim': [(30, 0, 0), (1, 0, 2.5), (10, 2.5, 2.5), (3, 2.5, 0), (30, 0, 0), (126, 0, 0)]}


def water_at(scenario, t):
    t0 = 0.0
    for dur, h0, h1 in SEGS[scenario]:
        if t <= t0 + dur:
            return h0 + (h1 - h0) * (t - t0) / dur
        t0 += dur
    return 0.0


def timelines(before):
    """{tree: {(scenario, t): (b0, b1, b2, line or None, drip)}} for 'before', 'after', 'off'."""
    out = {}
    with tempfile.TemporaryDirectory() as tmp:
        for name, tree, arg in (('before', before, None), ('after', ROOT, None), ('off', ROOT, 'off')):
            d = os.path.join(tmp, name)
            subprocess.run(['javac', '-J-Xmx256m', '--release', '17', '-nowarn', '-d', d, os.path.join(tree, JAVA),
                            DRIVER], check=True)
            text = subprocess.run(['java', '-Xmx128m', '-cp', d, 'com.ysmnpr.endfield.WetTimeline'] + ([arg] if arg else []),
                                  check=True, capture_output=True, text=True).stdout
            rows = {}
            for r in csv.DictReader(io.StringIO(text)):
                line = float(r['line'])
                rows[(r['scenario'], float(r['t']))] = (int(r['b0']), int(r['b1']), int(r['b2']),
                                                         None if np.isnan(line) else line, float(r['drip']))
            out[name] = rows
    return out


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
const vec4 NprTweak = vec4(0.5, 1.0, 1.0, 3.0); // skinRampTint, rainGlint, rainMatcap, rainDropSpeed defaults
const vec4 Globals2 = vec4(1.0);
const vec3 WorldUp = vec3(0.0, 1.0, 0.0);
vec3 nprEnvBRDF(vec3 f0, float noV, float roughness) { // npr_character.fsh
    float n = noV, r = roughness * roughness, n2 = n * n, n3 = n2 * n;
    float a = ((0.0365463011 + 3.327069998 * n) + (9.063199997 - 9.047559738 * n) * r)
        / max((1.0 + 3.596849918 * n2 - 1.367720008 * n3) + (9.044010163 - 16.31739998 * n2 + 9.22949028 * n3) * r
            + (5.565889835 + 19.78860092 * n2 - 20.21229935 * n3) * r * r * r, 1e-8);
    float b = ((0.990440011 - 1.285140038 * n) + (1.29677999 - 0.755907 * n) * r)
        / max((1.0 + 2.923379898 * n + 59.41880035 * n3) + (20.32250023 - 27.03019905 * n + 222.5919952 * n3) * r
            + (121.5630035 + 626.1300049 * n + 316.6270142 * n3) * r * r * r, 1e-8);
    vec3 c = f0 * a + b;
    return c + f0 * ((1.0 - (a + b)) / max(a + b, 1e-8)) * c;
}
%s
void main() {
    uint i = gl_GlobalInvocationID.x;
    vec4 a = inp[i * 4u], b = inp[i * 4u + 1u], c = inp[i * 4u + 2u], d = inp[i * 4u + 3u];
    int fam = int(a.w) %% 10;
    bool light = int(a.w) >= 10;
    RainParams = vec4(1.0, b.w, %r, 1.7);
    RainBytes = c;
    RainLine = d.x;
    RainDrip = d.y;
    rainLodObject = %r;
    rainLodFace = %r;
    vec3 p = a.xyz;
    vec3 N0 = normalize(b.xyz);
    vec3 N = N0;
    vec3 T = normalize(cross(vec3(0.0, 1.0, 0.0), N));
    mat3 frame = mat3(T, cross(N, T), N);
    RainObjP = vec3(p.x, p.y, -p.z);  // the rigid body frame: right x, up y, forward -z, feet at 0
    RainObjN = vec3(N.x, N.y, -N.z);
    rainObjT = vec3(T.x, T.y, -T.z);
    vec3 V = normalize(vec3(0.0, p.y, 2.0) - p);
    vec3 A = fam == 4 ? (light ? vec3(0.78, 0.76, 0.72) : vec3(0.22, 0.26, 0.36)) : fam == 3 ? vec3(0.62, 0.58, 0.6)
           : fam == 1 ? vec3(0.96, 0.84, 0.78) : vec3(0.93, 0.76, 0.68);
    vec3 S = fam == 4 ? A * 0.6 : A * vec3(0.75, 0.6, 0.65);
    float smoothness = fam == 4 ? 0.3 : fam == 3 ? 0.4 : 0.45, specLevel = 0.5, metal = 0.0, receiver = 1.0;
    rainFamily = RAIN_NONE;
    if (rainActive()) {
        rainSurface(fam, p, N, frame, true, V, d.zw, d.z, 0.0, A, S, N, smoothness, specLevel, metal, receiver);
    }
    vec3 L = normalize(vec3(-0.45, 0.75, 0.6));
    vec3 fill = normalize(vec3(0.0, 0.0, 1.0) + WorldUp * L.y);
    float NoV = clamp(dot(N, V), 0.0, 1.0);
    float lit = smoothstep(-0.1, 0.3, dot(N, L));
    if (fam == 1) {
        lit = min(lit, receiver);
    }
    vec3 diffuse = mix(S, A, lit);
    vec3 spec;
    float rough = 1.0 - smoothness;
    if (fam == 3) { // hair: a strand highlight (strands along y) x the drops' primary multiplier
        vec3 Hh = normalize(L + V);
        vec3 Tb = normalize(vec3(0.0, 1.0, 0.0) - N * N.y);
        float th = dot(Tb, Hh);
        spec = vec3(pow(sqrt(max(1.0 - th * th, 0.0)), 80.0) * 0.35 * rainHairSpec());
    } else {
        vec3 Hs = normalize(L + 2.0 * fill + V * 3.0);
        float ag = max(rough * rough, 0.0078125);
        float NoH = dot(N, Hs);
        float dd = NoH * NoH * (ag * ag - 1.0) + 1.0;
        float Dg = ag * ag / max(dd * dd, 1e-7);
        float Vis = 0.5 / (2.0 * NoV + ag + 1e-4);
        vec3 F0 = vec3(0.04 * specLevel);
        spec = clamp(Dg * Vis - 6.1e-5, 0.0, 20.0) * F0;
        if (fam == 4) { // cloth with property maps: the environment reflection on the wet roughness
            vec3 Rv = reflect(-V, N);
            vec3 envRad = vec3(mix(0.45, 1.0, smoothstep(-0.6, 0.6, Rv.y)));
            spec += envRad * nprEnvBRDF(F0, NoV, rainEnvRough(rough));
        }
    }
    vec3 col = diffuse + spec;
    col = rainColor(col, diffuse, spec, vec3(1.0), L, V, fill, 1.0);
    col += rainWetFilm(N, V, vec3(1.0));
    outp[i * 2u] = vec4(clamp(col, 0.0, 1.0), acos(clamp(dot(N, N0), -1.0, 1.0)));
    outp[i * 2u + 1u] = vec4(A, smoothness);
}
'''


class Runner:
    """One compiled program, buffers reused across dispatches (GL.run compiles and allocates per call)."""

    def __init__(self, gl, source):
        u, i, p, z = ctypes.c_uint, ctypes.c_int, ctypes.c_void_p, ctypes.c_ssize_t
        self.f = {k: gl.fn(*v) for k, v in {
            'create_shader': ('glCreateShader', u, u),
            'source': ('glShaderSource', None, u, i, ctypes.POINTER(ctypes.c_char_p), p),
            'compile': ('glCompileShader', None, u), 'shaderiv': ('glGetShaderiv', None, u, u, ctypes.POINTER(i)),
            'shader_log': ('glGetShaderInfoLog', None, u, i, p, ctypes.c_char_p),
            'create_program': ('glCreateProgram', u), 'attach': ('glAttachShader', None, u, u),
            'link': ('glLinkProgram', None, u), 'programiv': ('glGetProgramiv', None, u, u, ctypes.POINTER(i)),
            'use': ('glUseProgram', None, u), 'gen': ('glGenBuffers', None, i, ctypes.POINTER(u)),
            'bind': ('glBindBuffer', None, u, u), 'base': ('glBindBufferBase', None, u, u, u),
            'data': ('glBufferData', None, u, z, p, u), 'dispatch': ('glDispatchCompute', None, u, u, u),
            'barrier': ('glMemoryBarrier', None, u), 'get': ('glGetBufferSubData', None, u, z, z, p),
            'finish': ('glFinish', None)}.items()}
        f = self.f
        sh = f['create_shader'](0x91B9)
        src = ctypes.c_char_p(source.encode())
        f['source'](sh, 1, ctypes.byref(src), None)
        f['compile'](sh)
        ok = ctypes.c_int()
        f['shaderiv'](sh, 0x8B81, ctypes.byref(ok))
        if not ok.value:
            log = ctypes.create_string_buffer(65536)
            f['shader_log'](sh, 65536, None, log)
            raise RuntimeError('compile failed:\n' + log.value.decode())
        self.prog = f['create_program']()
        f['attach'](self.prog, sh)
        f['link'](self.prog)
        f['programiv'](self.prog, 0x8B82, ctypes.byref(ok))
        if not ok.value:
            raise RuntimeError('link failed')
        self.bufs = (ctypes.c_uint * 2)()
        f['gen'](2, self.bufs)

    def run(self, data):
        """data: (n, 4, 4) float32, n a multiple of 64 -> (n, 2, 4)"""
        f, SSBO = self.f, 0x90D2
        n = data.shape[0]
        f['use'](self.prog)
        f['bind'](SSBO, self.bufs[0])
        f['data'](SSBO, data.nbytes, data.ctypes.data, 0x88E4)
        f['base'](SSBO, 0, self.bufs[0])
        f['bind'](SSBO, self.bufs[1])
        f['data'](SSBO, n * 32, None, 0x88E4)
        f['base'](SSBO, 1, self.bufs[1])
        f['dispatch'](n // 64, 1, 1)
        f['barrier'](0x2000)
        f['finish']()
        out = np.zeros(n * 8, np.float32)
        f['bind'](SSBO, self.bufs[1])
        f['get'](SSBO, 0, out.nbytes, out.ctypes.data)
        return out.reshape(n, 2, 4)


def program(gl, tree, lod_obj=LOD_OBJ, lod_face=LOD_FACE):
    surface_smoke.SHADERS = os.path.join(tree, 'src/main/resources/assets/endfield_npr/shaders')
    inc = surface_smoke.include('npr_rain.glsl', set())
    if 'RainDrip' not in inc:
        inc = 'float RainDrip;\n' + inc  # a tree without the drip: the value is ignored
    return Runner(gl, SRC % (inc, TILING, float(lod_obj), float(lod_face)))


def geometry(patches=PATCHES, px=PX, size=SIZE):
    """Per patch: positions, normals, face uv (px*px each); the face on a 0.12-radius sphere (all of the face rect),
    else a size x size square of a 0.18-radius cylinder."""
    s = (np.arange(px) + 0.5) / px
    out = []
    for _, fam, h in patches:
        if fam == 1:
            u, v = np.meshgrid(s, s)  # face rect uv, v down
            x = (u - 0.5) * 0.24
            y = h - (v - 0.5) * 0.24
            z = np.sqrt(np.maximum(0.12 ** 2 - x ** 2 - (y - h) ** 2, 0.0)) + 0.02
            nrm = np.stack([x, y - h, z - 0.02], -1)
            pos = np.stack([x, y, z], -1)
            uv = np.stack([u, v], -1)
        else:
            ang = (s - 0.5) * size / 0.18
            A, Y = np.meshgrid(ang, h + 0.5 * size - s * size)
            pos = np.stack([0.18 * np.sin(A), Y, 0.18 * np.cos(A)], -1)
            nrm = np.stack([np.sin(A), np.zeros_like(A), np.cos(A)], -1)
            uv = np.stack([A / (2 * np.pi) + 0.5, np.zeros_like(A)], -1)
        nrm = nrm / np.linalg.norm(nrm, axis=-1, keepdims=True)
        out.append((fam, pos.reshape(-1, 3), nrm.reshape(-1, 3), uv.reshape(-1, 2)))
    return out


def evaluate(runner, geo, state, t, px=PX, steps=(0.0, 0.25)):
    """state (b0, b1, b2, line, drip) at time t -> per patch (shaded (px, px, 3), tilt (px, px)) at t + each step."""
    b0, b1, b2, line, drip = state
    n = px * px
    data = np.zeros((len(geo) * len(steps) * n, 4, 4), np.float32)
    k = 0
    for fam, pos, nrm, uv in geo:
        for dt in steps:
            sl = slice(k, k + n)
            data[sl, 0, :3] = pos
            data[sl, 0, 3] = fam
            data[sl, 1, :3] = nrm
            data[sl, 1, 3] = 1000.0 + t + dt  # game time in s
            data[sl, 2] = (b0, b1, b2, 0)
            data[sl, 3, 0] = -1000.0 if line is None else line
            data[sl, 3, 1] = drip
            data[sl, 3, 2:] = uv
            k += n
    out = runner.run(data).reshape(len(geo), len(steps), px, px, 2, 4)
    return out[:, :, :, :, 0, :3], np.degrees(out[:, :, :, :, 0, 3])


def lum(c):
    return c[..., 0] * 0.2126 + c[..., 1] * 0.7152 + c[..., 2] * 0.0722


def metrics(col, tilt, dry):
    """col (2, PX, PX, 3) at t and t + 0.25, tilt (2, PX, PX), dry (PX, PX, 3)"""
    vis = (np.abs(col[0] - dry).max(-1) > THRESH).mean() * 100
    dl = (lum(col[0]).mean() / lum(dry).mean() - 1) * 100
    drop = (tilt[0] > 10).mean() * 100
    mov = (np.abs(col[1] - col[0]).max(-1) > THRESH).mean() * 100
    return vis, dl, drop, mov


def fmt_state(s):
    b0, b1, b2, line, drip = s
    txt = '%d/%d/%d' % (b0, b1, b2)
    txt += ' L%s' % ('-' if line is None else ('%.2f' % line if line < 10 else '16'))
    if drip >= 0:
        txt += ' d%.2f' % drip
    return txt


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--before', default=os.path.expanduser('~/ysm/npr/work-merged'))
    ap.add_argument('--out', default=os.path.expanduser('~/ysm/handoff/wetwater'))
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    tl = timelines(args.before)
    diff = [k for k in tl['before'] if tl['before'][k][:4] != tl['off'][k][:4] or tl['off'][k][4] != -1.0]
    print('state: "off" (waterDrip 0, waterLineHold false) == before: %s (%d samples, %d differ)'
          % (not diff, len(tl['before']), len(diff)))
    gl = GL()
    surface_smoke.rain_textures(gl)
    runners = {'before': program(gl, args.before), 'after': program(gl, ROOT)}
    geo = geometry()
    dry = {k: evaluate(r, geo, (0, 0, 0, None, -1.0), 0.0)[0][:, 0] for k, r in runners.items()}
    md = ['# Wet from water: before / after timeline (tools/rain/wet_timeline.py)', '',
          'State: b0/b1/b2 = instant rain / water-line wetness / wetness bytes, L = water line above the feet (16 = '
          'all of her), d = drip (after only). Per patch `vis dL drop mov` (see the tool\'s header): visibly changed %%, '
          'luminance change %%, drop/drip pixels %%, pixels moving within 0.25 s %%; `~` = under the water surface '
          '(hidden in game). "off" state == before: %s.' % (not diff), '']
    for sc, times in SAMPLES.items():
        md += ['## %s' % sc, '',
               '| t s | water | state before | state after | ' + ' | '.join(p[0] for p in PATCHES) + ' |',
               '|' + '---|' * (4 + len(PATCHES))]
        strip = {k: [] for k in runners}
        for t in times:
            water = water_at(sc, t)
            cells = {}
            for k, r in runners.items():
                s = tl[k][(sc, float(t))]
                col, tilt = evaluate(r, geo, s, t)
                cells[k] = [metrics(col[j], tilt[j], dry[k][j]) for j in range(len(PATCHES))]
                strip[k].append(col[:, 0])
            row = ['%g' % t, '%.2f' % water, fmt_state(tl['before'][(sc, float(t))]),
                   fmt_state(tl['after'][(sc, float(t))])]
            for j, (_, fam, h) in enumerate(PATCHES):
                hidden = '~' if h < water else ''
                row.append(hidden + ' -> '.join('%.0f %+.0f %.0f %.0f' % cells[k][j] for k in ('before', 'after')))
            md.append('| ' + ' | '.join(row) + ' |')
            print(sc, t, 'done', flush=True)
        md.append('')
        sheet(strip, times, os.path.join(args.out, '%s_strip.png' % sc), sc)
    md += spheres(gl, runners, tl)
    path = os.path.join(args.out, 'timeline.md')
    with open(path, 'w') as f:
        f.write('\n'.join(md) + '\n')
    print('wrote', path)
    closeup(gl, args, tl)


def spheres(gl, runners, tl, px=120, radius=0.15):
    """Table 3 of the header: per moment, per material 'vis dL hiL drop mov' before -> after."""
    s = (np.arange(px) + 0.5) / px
    x, y = np.meshgrid((s - 0.5) * 2 * radius, (0.5 - s) * 2 * radius)
    inside = (x ** 2 + y ** 2 < 0.98 * radius ** 2).reshape(-1)
    z = np.sqrt(np.maximum(radius ** 2 - x ** 2 - y ** 2, 0.0))
    mats = [('skin', 2, 0.55), ('dark cloth', 4, 0.55), ('light cloth', 14, 0.55), ('hair', 3, 1.25)]
    geo = []
    for _, code, h in mats:
        nrm = np.stack([x, y, z], -1)
        nrm = nrm / np.maximum(np.linalg.norm(nrm, axis=-1, keepdims=True), 1e-6)
        uv = np.stack([np.arctan2(x, z) / (2 * np.pi) + 0.5, np.zeros_like(x)], -1)
        geo.append((code, np.stack([x, h + y, z], -1).reshape(-1, 3), nrm.reshape(-1, 3), uv.reshape(-1, 2)))
    moments = [('full rain (rain-wade 30 s)', 'rain-wade', 30.0), ('wade: 2 s out (18 s)', 'wade', 18.0),
               ('swim: 2 s out (18 s)', 'swim', 18.0), ('swim: 60 s out (76 s)', 'swim', 76.0)]
    md = ['## all normals (sunlit sphere, 2 blocks)', '',
          'Per material `vis dL hiL drop mov` before -> after (hiL: the dry highlight, brightest 5 %).', '',
          '| moment | state before | state after | ' + ' | '.join(m[0] for m in mats) + ' |',
          '|' + '---|' * (3 + len(mats))]
    dry = {k: evaluate(r, geo, (0, 0, 0, None, -1.0), 0.0, px)[0][:, 0] for k, r in runners.items()}
    for name, sc, t in moments:
        cells = {}
        for k, r in runners.items():
            col, tilt = evaluate(r, geo, tl[k][(sc, t)], t, px)
            cells[k] = []
            for j in range(len(mats)):
                c0, c1 = (col[j, i].reshape(-1, 3)[inside] for i in (0, 1))
                d0 = dry[k][j].reshape(-1, 3)[inside]
                hi = lum(d0) >= np.quantile(lum(d0), 0.95)
                cells[k].append('%.0f %+.0f %+.0f %.1f %.1f' % (
                    (np.abs(c0 - d0).max(-1) > THRESH).mean() * 100, (lum(c0).mean() / lum(d0).mean() - 1) * 100,
                    (lum(c0[hi]).mean() / lum(d0[hi]).mean() - 1) * 100,
                    (tilt[j, 0].reshape(-1)[inside] > 10).mean() * 100,
                    (np.abs(c1 - c0).max(-1) > THRESH).mean() * 100))
        md.append('| %s | %s | %s | ' % (name, fmt_state(tl['before'][(sc, t)]), fmt_state(tl['after'][(sc, t)]))
                  + ' | '.join('%s -> %s' % (cells['before'][j], cells['after'][j]) for j in range(len(mats))) + ' |')
    return md + ['']


def closeup(gl, args, tl, px=200, size=0.1, t=18.0):
    """2 s after she walked out of the water ('swim', t = 18 s): 0.1-block close-ups of skin, light cloth and hair and
    the whole face, before | after, 8 frames 0.25 s apart -> swim_drip_closeup.gif"""
    patches = [('skin', 2, 0.55), ('light cloth', 14, 0.55), ('hair', 3, 1.25), ('face', 1, 1.55)]
    geo = geometry(patches, px, size)
    steps = tuple(0.25 * j for j in range(8))
    tiles = {}
    for k, tree in (('before', args.before), ('after', ROOT)):
        r = program(gl, tree, np.log2(1024 * TILING * size / px), np.log2(1024 / px))
        tiles[k] = evaluate(r, geo, tl[k][('swim', t)], t, px, steps)[0]  # (patch, step, px, px, 3)
    frames = []
    for j in range(len(steps)):
        img = Image.new('RGB', (90 + len(patches) * (px + 4), 16 + 2 * (px + 4)), (30, 30, 30))
        d = ImageDraw.Draw(img)
        d.text((4, 2), 'swim: 2 s out of the water, t+%.2f s' % steps[j], fill=(255, 255, 255))
        for r, k in enumerate(('before', 'after')):
            y = 16 + r * (px + 4)
            d.text((4, y + px // 2 - 6), k, fill=(255, 255, 255))
            for c, (name, _, _) in enumerate(patches):
                im = (np.clip(tiles[k][c, j], 0, 1) * 255 + 0.5).astype(np.uint8)
                img.paste(Image.fromarray(im), (90 + c * (px + 4), y))
                if r == 0:
                    d.text((90 + c * (px + 4) + 2, y + 2), name, fill=(0, 0, 0))
        frames.append(img)
    path = os.path.join(args.out, 'swim_drip_closeup.gif')
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=250, loop=0)
    print('wrote', path)


def sheet(strip, times, path, title):
    """rows: per tree the skin, dark and light cloth at the thigh, the hair and the face; columns: the sample times"""
    rows = [(k, j) for k in ('before', 'after') for j in (0, 1, 2, 6, 7)]
    sc = PX
    img = Image.new('RGB', (110 + len(times) * (sc + 4), 18 + len(rows) * (sc + 4)), (30, 30, 30))
    d = ImageDraw.Draw(img)
    d.text((4, 2), title, fill=(255, 255, 255))
    for c, t in enumerate(times):
        d.text((110 + c * (sc + 4), 4), '%g s' % t, fill=(255, 255, 255))
    for r, (k, j) in enumerate(rows):
        y = 18 + r * (sc + 4)
        d.text((4, y + sc // 2 - 6), '%s %s' % (k, PATCHES[j][0]), fill=(255, 255, 255))
        for c in range(len(times)):
            im = (np.clip(strip[k][c][j], 0, 1) * 255 + 0.5).astype(np.uint8)
            img.paste(Image.fromarray(im), (110 + c * (sc + 4), y))
    img.save(path)


if __name__ == '__main__':
    main()
