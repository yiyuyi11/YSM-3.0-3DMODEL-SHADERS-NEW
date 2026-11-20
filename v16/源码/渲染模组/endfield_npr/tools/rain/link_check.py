#!/usr/bin/env python3
"""Compiles and links the core programs on a real GL driver (Mesa llvmpipe, EGL surfaceless; no window, no GPU):
npr_character.vsh + .fsh and npr_outline.vsh + npr_character.fsh, as Minecraft would (#version 150 core,
#moj_import inlined like tools/glsl_check.sh), once as shipped and once with NPR_RAIN_SNOW defined. Catches what
glslangValidator does not: the driver's interface matching, varying/sampler limits, and lists the active samplers and
vertex attributes (NEW_ENTITY bound to 0-5 as ShaderInstance does; the rain anchors must sit at 14/15, clear of
NprDrawIndex, as GpuArena.anchorLocations requires).

  python3 tools/rain/link_check.py
"""
import ctypes
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from rain_check import GL  # noqa: E402

ROOT = os.path.join(HERE, '..', '..', 'src/main/resources/assets/endfield_npr/shaders')


def inline(path, defines=''):
    seen = set()

    def expand(p):
        out = []
        for line in open(p):
            m = re.match(r'\s*#moj_import\s*<endfield_npr:([^>]+)>', line)
            if not m:
                out.append(line)
                continue
            if m.group(1) not in seen:
                seen.add(m.group(1))
                out.extend(expand(os.path.join(ROOT, 'include', m.group(1))))
        return out
    lines = expand(path)
    if defines:  # after #version
        lines.insert(1, defines)
    return ''.join(lines)


def main():
    gl = GL()
    u, i, p = ctypes.c_uint, ctypes.c_int, ctypes.c_void_p
    create_shader = gl.fn('glCreateShader', u, u)
    source = gl.fn('glShaderSource', None, u, i, ctypes.POINTER(ctypes.c_char_p), p)
    compile_ = gl.fn('glCompileShader', None, u)
    shader_iv = gl.fn('glGetShaderiv', None, u, u, ctypes.POINTER(i))
    shader_log = gl.fn('glGetShaderInfoLog', None, u, i, p, ctypes.c_char_p)
    create_program = gl.fn('glCreateProgram', u)
    attach = gl.fn('glAttachShader', None, u, u)
    link = gl.fn('glLinkProgram', None, u)
    program_iv = gl.fn('glGetProgramiv', None, u, u, ctypes.POINTER(i))
    program_log = gl.fn('glGetProgramInfoLog', None, u, i, p, ctypes.c_char_p)
    active_uniform = gl.fn('glGetActiveUniform', None, u, u, i, p, ctypes.POINTER(i), ctypes.POINTER(u),
                           ctypes.c_char_p)
    bind_attrib = gl.fn('glBindAttribLocation', None, u, u, ctypes.c_char_p)
    attrib_loc = gl.fn('glGetAttribLocation', i, u, ctypes.c_char_p)
    draw_locs = {}
    failed = False

    def compile_one(kind, text):
        sh = create_shader(kind)
        src = ctypes.c_char_p(text.encode())
        source(sh, 1, ctypes.byref(src), None)
        compile_(sh)
        ok = i()
        shader_iv(sh, 0x8B81, ctypes.byref(ok))
        log = ctypes.create_string_buffer(65536)
        shader_log(sh, 65536, None, log)
        return sh, ok.value, log.value.decode()

    for vsh in ('npr_character.vsh', 'npr_outline.vsh'):
        for defines in ('', '#define NPR_RAIN_SNOW\n'):
            v, okv, logv = compile_one(0x8B31, inline(os.path.join(ROOT, 'core', vsh)))
            f, okf, logf = compile_one(0x8B30, inline(os.path.join(ROOT, 'core', 'npr_character.fsh'), defines))
            prog = create_program()
            attach(prog, v)
            attach(prog, f)
            for k, a in enumerate(('Position', 'Color', 'UV0', 'UV1', 'UV2', 'Normal')):
                bind_attrib(prog, k, a.encode())
            link(prog)
            ok = i()
            program_iv(prog, 0x8B82, ctypes.byref(ok))
            log = ctypes.create_string_buffer(65536)
            program_log(prog, 65536, None, log)
            name = '%s + npr_character.fsh%s' % (vsh, ' (NPR_RAIN_SNOW)' if defines else '')
            if not (okv and okf and ok.value):
                failed = True
                print('FAIL', name, logv, logf, log.value.decode())
                continue
            count = i()
            program_iv(prog, 0x8B86, ctypes.byref(count))  # GL_ACTIVE_UNIFORMS
            samplers = []
            for k in range(count.value):
                size, typ = i(), u()
                nm = ctypes.create_string_buffer(256)
                active_uniform(prog, k, 256, None, ctypes.byref(size), ctypes.byref(typ), nm)
                if typ.value in (0x8B5E, 0x8DC1, 0x9108):  # sampler2D, sampler2DArray, sampler2DMS
                    samplers.append(nm.value.decode())
            print('ok  ', name, '-', len(samplers), 'active samplers:', ' '.join(sorted(samplers)))
            names = ('Position', 'Color', 'UV0', 'UV1', 'UV2', 'Normal', 'NprDrawIndex', 'RainAnchorP', 'RainAnchorN')
            locs = {a: attrib_loc(prog, a.encode()) for a in names}
            print('     attributes:', ' '.join('%s=%d' % kv for kv in locs.items() if kv[1] >= 0))
            draw_locs[name] = locs['NprDrawIndex']
            if vsh == 'npr_character.vsh' and (locs['RainAnchorP'], locs['RainAnchorN']) != (14, 15):
                failed = True
                print('FAIL', name, 'rain anchors not at 14/15')
    if any(loc in (14, 15) for loc in draw_locs.values()):
        failed = True
        print('FAIL NprDrawIndex clashes with the rain anchors:', draw_locs)
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
