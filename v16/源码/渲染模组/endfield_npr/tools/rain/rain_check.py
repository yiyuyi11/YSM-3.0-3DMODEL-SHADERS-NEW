#!/usr/bin/env python3
"""Numeric cross-check of npr_rain_asm.glsl against the original asm.

Every snippet of rain_gen.SNIPPETS runs on the same random inputs twice: the asm lines through dxasm.py (float32
D3D semantics, the reference) and the generated GLSL as a compute shader on Mesa llvmpipe (EGL surfaceless, no
window, no GPU needed). All temp registers are compared afterwards: a D3D mask (0xFFFFFFFF) must come out as 1.0, any
other component within |d| <= ATOL + RTOL |ref|. Texture fetches use the same procedural stub on both sides. The
drop hooks (npr_rain_drops.glsl, lines cited `// hook`) run as shipped at their neutral values (rainDropDensity 1,
rainDropJitter 0, rainDropSpeedJitter 0, rainDropSlide 0), where they must leave the game's math unchanged;
RAIN_DROPS=d,j,s,slide runs them at other values instead (expect differences in the drop blocks then).

  python3 tools/rain/rain_check.py [asm dir] [cases per snippet]
Exit status 1 when a snippet's mismatching cases exceed MAX_BAD (float contraction at fract()/floor()/step
discontinuities can flip a rare case; the report lists them).
"""
import ctypes
import os
import re
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import dxasm  # noqa: E402
import rain_gen  # noqa: E402

CASES = int(sys.argv[2]) if len(sys.argv) > 2 else 600
ATOL, RTOL, MAX_BAD = 2e-3, 2e-3, 0.01
LAYERS = {'effect': 0, 'streak': 1, 'drip': 2, 'droplet': 3, 'snow': 4}
F = np.float32


def tri(x):
    x = F(x)
    return F(abs(F(x - np.floor(x)) - F(0.5)) * F(2.0))


def tex_stub(layer, uv):
    u, v = F(uv[0]), F(uv[1])
    L = F(layer)
    return np.array([tri(u * F(1.37) + v * F(0.71) + L * F(0.13)), tri(u * F(0.53) - v * F(1.91) + F(0.3)),
                     tri(u * F(2.11) + v * F(0.37) + F(0.7)), tri(v * F(1.29) - u * F(0.83) + F(0.1))], dtype=F)


GLSL_STUB = '''
float triW(float x) { return abs(fract(x) - 0.5) * 2.0; }
vec4 rainTex(int layer, vec2 uv) {
    float L = float(layer);
    return vec4(triW(uv.x * 1.37 + uv.y * 0.71 + L * 0.13), triW(uv.x * 0.53 - uv.y * 1.91 + 0.3),
                triW(uv.x * 2.11 + uv.y * 0.37 + 0.7), triW(uv.y * 1.29 - uv.x * 0.83 + 0.1));
}
const int RAIN_LAYER_EFFECT = 0;
const int RAIN_LAYER_STREAK = 1;
const int RAIN_LAYER_FACE_DRIP = 2;
const int RAIN_LAYER_FACE_DROPLET = 3;
const int RAIN_LAYER_SNOW = 4;
#define NPR_RAIN_SNOW
'''
# a uniform with an initializer: the compiler cannot fold the hooks away, as in the game
DROPS = os.environ.get('RAIN_DROPS', '1,0,0,0')
GLSL_STUB += open(os.path.join(HERE, '..', '..', 'src/main/resources/assets/endfield_npr/shaders/include',
                               'npr_rain_drops.glsl')).read().replace(
    'uniform vec4 RainDrops;', 'uniform vec4 RainDrops = vec4(%s);' % ', '.join('%r' % float(x) for x in DROPS.split(',')))


# ---------------------------------------------------------------- llvmpipe compute
class GL:
    def __init__(self):
        os.environ.setdefault('EGL_PLATFORM', 'surfaceless')
        os.environ['LIBGL_ALWAYS_SOFTWARE'] = '1'
        os.environ['GALLIUM_DRIVER'] = 'llvmpipe'
        egl = ctypes.CDLL('libEGL.so.1')
        egl.eglGetProcAddress.restype = ctypes.c_void_p
        egl.eglGetProcAddress.argtypes = [ctypes.c_char_p]
        get_display = ctypes.CFUNCTYPE(ctypes.c_void_p, ctypes.c_uint, ctypes.c_void_p, ctypes.c_void_p)(
            egl.eglGetProcAddress(b'eglGetPlatformDisplayEXT'))
        dpy = get_display(0x31DD, None, None)  # EGL_PLATFORM_SURFACELESS_MESA
        egl.eglInitialize.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
        if not egl.eglInitialize(dpy, None, None):
            raise RuntimeError('eglInitialize failed')
        egl.eglBindAPI(0x30A2)  # EGL_OPENGL_API
        egl.eglCreateContext.restype = ctypes.c_void_p
        egl.eglCreateContext.argtypes = [ctypes.c_void_p] * 4
        attrs = (ctypes.c_int * 7)(0x3098, 4, 0x30FB, 5, 0x30FD, 1, 0x3038)  # 4.5 core
        ctx = egl.eglCreateContext(dpy, None, None, attrs)
        egl.eglMakeCurrent.argtypes = [ctypes.c_void_p] * 4
        if not ctx or not egl.eglMakeCurrent(dpy, None, None, ctx):
            raise RuntimeError('no EGL context')
        self.egl = egl

    def fn(self, name, res, *args):
        return ctypes.CFUNCTYPE(res, *args)(self.egl.eglGetProcAddress(name.encode()))

    def run(self, source, data_in, n_out_floats, groups):
        u, i, p, z = ctypes.c_uint, ctypes.c_int, ctypes.c_void_p, ctypes.c_ssize_t
        create_shader = self.fn('glCreateShader', u, u)
        shader_source = self.fn('glShaderSource', None, u, i, ctypes.POINTER(ctypes.c_char_p), p)
        compile_shader = self.fn('glCompileShader', None, u)
        get_shader = self.fn('glGetShaderiv', None, u, u, ctypes.POINTER(i))
        shader_log = self.fn('glGetShaderInfoLog', None, u, i, p, ctypes.c_char_p)
        create_program = self.fn('glCreateProgram', u)
        attach = self.fn('glAttachShader', None, u, u)
        link = self.fn('glLinkProgram', None, u)
        get_program = self.fn('glGetProgramiv', None, u, u, ctypes.POINTER(i))
        program_log = self.fn('glGetProgramInfoLog', None, u, i, p, ctypes.c_char_p)
        use = self.fn('glUseProgram', None, u)
        gen_buffers = self.fn('glGenBuffers', None, i, ctypes.POINTER(u))
        bind_buffer = self.fn('glBindBuffer', None, u, u)
        bind_base = self.fn('glBindBufferBase', None, u, u, u)
        buffer_data = self.fn('glBufferData', None, u, z, p, u)
        dispatch = self.fn('glDispatchCompute', None, u, u, u)
        barrier = self.fn('glMemoryBarrier', None, u)
        get_sub = self.fn('glGetBufferSubData', None, u, z, z, p)
        finish = self.fn('glFinish', None)
        sh = create_shader(0x91B9)
        src = ctypes.c_char_p(source.encode())
        shader_source(sh, 1, ctypes.byref(src), None)
        compile_shader(sh)
        ok = i()
        get_shader(sh, 0x8B81, ctypes.byref(ok))
        if not ok.value:
            log = ctypes.create_string_buffer(65536)
            shader_log(sh, 65536, None, log)
            raise RuntimeError('compile failed:\n' + log.value.decode())
        prog = create_program()
        attach(prog, sh)
        link(prog)
        get_program(prog, 0x8B82, ctypes.byref(ok))
        if not ok.value:
            log = ctypes.create_string_buffer(65536)
            program_log(prog, 65536, None, log)
            raise RuntimeError('link failed:\n' + log.value.decode())
        use(prog)
        bufs = (u * 2)()
        gen_buffers(2, bufs)
        SSBO = 0x90D2
        bind_buffer(SSBO, bufs[0])
        buffer_data(SSBO, data_in.nbytes, data_in.ctypes.data, 0x88E4)
        bind_base(SSBO, 0, bufs[0])
        bind_buffer(SSBO, bufs[1])
        buffer_data(SSBO, n_out_floats * 4, None, 0x88E4)
        bind_base(SSBO, 1, bufs[1])
        dispatch(groups, 1, 1)
        barrier(0x2000)  # GL_SHADER_STORAGE_BARRIER_BIT
        finish()
        out = np.zeros(n_out_floats, dtype=np.float32)
        bind_buffer(SSBO, bufs[1])
        get_sub(SSBO, 0, out.nbytes, out.ctypes.data)
        return out


# ---------------------------------------------------------------- inputs
def unit(rng):
    v = rng.normal(size=3)
    return v / np.linalg.norm(v)


def make_case(rng, name, nreg, vs, cbs):
    """Register file + v + cb values. Plausible ranges: colours/weights in [0, 1], the raw bytes 0..255 (often 0 or
    255), wetness bytes /255, unit normals, object positions on a 2-block figure, time 0..600 s."""
    R = rng.uniform(0.0, 1.0, size=(nreg, 4)).astype(F)
    raw = rng.choice([0.0, 1.0, 2.0, 128.0, 255.0, rng.integers(0, 256)], size=4).astype(F)
    regs = {'face': ('r14', 'r15', 'r11.x'), 'skin': ('r12', 'r13', 'r2.w'), 'hair': ('r20', 'r21', 'r3.x'),
            'cloth': ('r14', 'r15', 'r8.w')}
    fam = 'face' if 'Face' in name else 'skin' if 'Skin' in name else 'hair' if 'Hair' in name else 'cloth'
    raw_r, norm_r, wet = regs[fam]
    k_raw, k_norm = int(raw_r[1:]), int(norm_r[1:])
    R[k_raw] = raw
    R[k_norm] = raw * F(1 / 255)
    wk, wc = int(wet[1:].split('.')[0]), 'xyzw'.index(wet[-1])
    R[wk, wc] = F(rng.choice([0.0, 1.0, rng.uniform()]))
    # the Z-up mesh flag (and 16 of a per-draw word): never set for our meshes
    for zr, zc in (('3', 'x'), ('3', 'y')):
        if (fam in ('face', 'skin') and zc == 'x') or (fam in ('hair', 'cloth') and zc == 'y'):
            R[int(zr), 'xyzw'.index(zc)] = F(0)
    # normals the blocks take from earlier registers: unit vectors
    normal_regs = {'face': [12, 20], 'skin': [10], 'hair': [15, 11], 'cloth': [10]}[fam]
    for k in normal_regs:
        R[k, :3] = unit(rng)
    if 'Glint' in name or 'Sheen' in name:
        R[21 if fam == 'skin' else 4, :3] = unit(rng)
    V = {}
    for v in vs:
        if v in (10, 11):
            V[v] = np.array([rng.integers(0, 2)] * 4, dtype=F)  # front face (mask) -> 0/1 here, 0/~0 in D3D
        elif v == 1:
            V[v] = rng.uniform(0, 1, 4).astype(F)
        elif v == 8 and fam in ('face', 'cloth') or v == 9:
            V[v] = np.array([rng.uniform(-0.4, 0.4), rng.uniform(0, 2), rng.uniform(-0.3, 0.3), 1], dtype=F)
        else:
            V[v] = np.append(unit(rng), rng.choice([-1.0, 1.0])).astype(F)
    C = {}
    for c in cbs:
        if c == (1, 20):
            C[c] = np.array([rng.uniform(0, 600), 0, 0, 0], dtype=F)
        elif c == (1, 120):
            C[c] = np.array([0, 0, 2.25, 0], dtype=F)
        elif c == (6, 5):
            C[c] = np.array([rng.choice([0.0, 0.0, 1.0]), 0, 0, 0], dtype=F)
        elif c == (0, 9):
            C[c] = np.array([0, rng.uniform(1, 3), 0, 0], dtype=F)
        else:
            C[c] = rng.uniform(0, 1, 4).astype(F)
    return R, V, C


# ---------------------------------------------------------------- reference
def run_asm(lines, a, b, texmap, R, V, C, executed):
    st = dxasm.State()
    for k in range(R.shape[0]):
        st.r['r%d' % k] = R[k].view(np.uint32).copy()
    for v, val in V.items():
        if v in (10, 11):
            st.v['v%d' % v] = np.full(4, 0xFFFFFFFF if val[0] else 0, dtype=np.uint32).view(F)
        else:
            st.v['v%d' % v] = val.copy()
    for (bb, ii), val in C.items():
        st.cb[(bb, ii)] = val.copy()
    st.tex = lambda slot, uv: tex_stub(LAYERS[texmap[slot]], uv)
    dxasm.run(lines, a, b, st, executed)
    return np.array([st.r['r%d' % k] for k in range(R.shape[0])], dtype=np.uint32)


def main():
    gl = GL()
    rng = np.random.default_rng(20261118)
    generated, nreg = rain_gen.generate()
    failed = False
    for name, prog, a, b, texmap, snow, doc in rain_gen.SNIPPETS:
        lines = rain_gen.load(prog)
        ctx = rain_gen.Ctx(lines, texmap)
        rain_gen.translate(ctx, a, b)
        vs, cbs = sorted(ctx.v), sorted(ctx.cb)
        stride = nreg + len(vs) + len(cbs)
        cases = [make_case(rng, name, nreg, vs, cbs) for _ in range(CASES)]
        data = np.zeros((CASES, stride, 4), dtype=F)
        for i, (R, V, C) in enumerate(cases):
            data[i, :nreg] = R
            for j, v in enumerate(vs):
                data[i, nreg + j] = V[v]
            for j, c in enumerate(cbs):
                data[i, nreg + len(vs) + j] = C[c]
        args = ['inp[base + %d]' % (nreg + j) for j in range(len(vs) + len(cbs))]
        src = '#version 430\nlayout(local_size_x = 64) in;\n' \
              'layout(std430, binding = 0) readonly buffer In { vec4 inp[]; };\n' \
              'layout(std430, binding = 1) writeonly buffer Out { vec4 outp[]; };\n' + GLSL_STUB + generated + '''
void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= %du) return;
    uint base = i * %du;
    for (int k = 0; k < %d; k++) rainR[k] = inp[base + uint(k)];
    %s(%s);
    for (int k = 0; k < %d; k++) outp[i * %du + uint(k)] = rainR[k];
}
''' % (CASES, stride, nreg, name, ', '.join(args), nreg, nreg)
        out = gl.run(src, data, CASES * nreg * 4, (CASES + 63) // 64).reshape(CASES, nreg, 4)
        bad, worst = 0, 0.0
        examples = []
        seen = set()  # asm lines executed in any case (branch coverage)
        for i, (R, V, C) in enumerate(cases):
            executed = set()
            ref = run_asm(lines, a, b, texmap, R, V, C, executed)
            seen |= executed
            refm = ref == 0xFFFFFFFF
            reff = ref.view(F)
            got = out[i]
            with np.errstate(invalid='ignore'):
                err = np.where(refm, np.abs(got - 1.0),
                               np.where(np.isnan(reff) & np.isnan(got), 0.0, np.abs(got - reff)))
                tol = ATOL + RTOL * np.where(refm, 1.0, np.abs(np.nan_to_num(reff)))
            over = ~(err <= tol)
            if over.any():
                bad += 1
                k = np.argwhere(over)[0]
                examples.append('r%d.%s ref %g got %g' % (k[0], 'xyzw'[k[1]], reff[k[0], k[1]], got[k[0], k[1]]))
            else:
                worst = max(worst, float(np.nanmax(err)))
        frac = bad / CASES
        status = 'ok  ' if frac <= MAX_BAD else 'FAIL'
        failed |= frac > MAX_BAD
        code = [ln for ln in range(a, b + 1)
                if lines[ln - 1].strip().split(' ')[0] not in ('', 'else', 'endif', 'if_nz', 'if_z')]
        covered = sum(ln in seen for ln in code) / len(code)
        print('%s %-15s %4d-%-4d %3d lines, %3.0f%% executed  %d/%d cases differ (%.2f%%), max |d| %.2e  %s'
              % (status, name, a, b, b - a + 1, 100 * covered, bad, CASES, 100 * frac, worst,
                 '; '.join(examples[:2])))
        sys.stdout.flush()
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
