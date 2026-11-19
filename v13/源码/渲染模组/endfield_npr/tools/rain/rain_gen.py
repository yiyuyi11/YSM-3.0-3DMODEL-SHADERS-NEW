#!/usr/bin/env python3
"""Generates shaders/include/npr_rain_asm.glsl: the game's rain/wet (and snow) blocks of the character programs,
translated from the disassembly one GLSL statement per asm line, each citing its line.

Input: the D3D disassembly of the CharacterNPR programs (/tmp/dxdis/out, see ~/ysm/tools/dxdis/README.md).
  python3 tools/rain/rain_gen.py [asm dir]          writes the include
  python3 tools/rain/rain_check.py [asm dir]        runs every snippet against dxasm.py (the reference interpreter)

Translation: the program's temp registers are the global vec4 rainR[] (the caller fills the live-ins and reads the
live-outs, see npr_rain.glsl); v registers and constant-buffer vectors become parameters (vN, cbB_I). Comparison
results (0xFFFFFFFF masks in D3D) are carried as 1.0/0.0, so `and mask, 1.0f` is the mask itself and `and mask, x` a
product; `movc` selects on != 0. Texture fetches go through rainTex(layer, uv) with Unity's uv (v up).
"""
import os
import re
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dxasm import SW, split_args, reads_writes  # noqa: E402

ASM = sys.argv[1] if len(sys.argv) > 1 else '/tmp/dxdis/out'
OUT = os.path.join(os.path.dirname(__file__), '..', '..',
                   'src/main/resources/assets/endfield_npr/shaders/include/npr_rain_asm.glsl')

# texture layers of the RainTex array (npr_rain.glsl); the slot of each program comes from the serialized bind tables
# (pelica-full/03-渲染管线/角色NPR原始字节码/*_s1.dxbc: cloth {RainEffect, RainStreak, SnowEffect}, hair {SnowEffect}),
# in declaration order RainEffect < RainStreak < FaceDrip < FaceDroplet < SnowEffect
LAYER = {'effect': 'RAIN_LAYER_EFFECT', 'streak': 'RAIN_LAYER_STREAK', 'drip': 'RAIN_LAYER_FACE_DRIP',
         'droplet': 'RAIN_LAYER_FACE_DROPLET', 'snow': 'RAIN_LAYER_SNOW'}
FACE = 'HGRP_CharacterNPR_Skin_lod0_p0_s1_10029588'
SKIN = 'HGRP_CharacterNPR_Skin_lod0_p0_s1_1018472'
HAIR = 'HGRP_CharacterNPR_Hair_lod0_p0_s1_1000084'
CLOTH = 'HGRP_CharacterNPR_lod0_p0_s1_10131804'
FACE_TEX = {12: 'drip', 13: 'droplet', 14: 'snow'}
CLOTH_TEX = {15: 'effect', 16: 'streak', 17: 'snow'}
ONE_TEX = {12: 'snow'}

# name, program, first, last, slot -> texture, snow (compiled only with NPR_RAIN_SNOW), what it is
SNIPPETS = [
    ('rainFaceDrip', FACE, 389, 452, FACE_TEX, False, 'face drips: scrolled FaceDrip + FaceDroplet mask (block 1)'),
    ('rainFaceMatcap', FACE, 770, 789, FACE_TEX, False, 'face wet matcap (FaceDroplet A at the view normal)'),
    ('rainSkinDrops', SKIN, 378, 952, ONE_TEX, False, 'body skin procedural drops + wet darkening (block 1)'),
    ('rainSkinSheen', SKIN, 1168, 1184, ONE_TEX, False, 'body skin GGX sheen r25 (pre-drop normal, material roughness)'),
    ('rainSkinGlint', SKIN, 1185, 1214, ONE_TEX, False, 'body skin drop glint and spec/brightness weights'),
    ('rainHairDrops', HAIR, 411, 1010, ONE_TEX, False, 'hair procedural drops + wet darkening (block 1)'),
    ('rainHairGlint', HAIR, 1273, 1304, ONE_TEX, False, 'hair drop glint and spec/brightness weights'),
    ('rainClothWet', CLOTH, 473, 1032, CLOTH_TEX, False, 'cloth wet patches, streaks, procedural drops (block 1)'),
    ('rainClothEnv', CLOTH, 1782, 1783, CLOTH_TEX, False, 'cloth env-reflection roughness of the wet surface'),
    ('rainFaceSnow', FACE, 453, 541, FACE_TEX, True, 'face snow (block 2)'),
    ('rainSkinSnow', SKIN, 953, 1039, ONE_TEX, True, 'body skin snow (block 2)'),
    ('rainHairSnow', HAIR, 1011, 1087, ONE_TEX, True, 'hair snow (block 2)'),
    ('rainClothSnow', CLOTH, 1033, 1131, CLOTH_TEX, True, 'cloth snow (block 2)'),
]

F32 = np.float32
MASK_OPS = ('lt', 'ge', 'eq', 'ne')


def load(prog):
    return open(os.path.join(ASM, prog + '.dxbc.asm')).read().split('\n')


def lit(vals, comps):
    out = []
    for c in comps:
        x = vals[SW[c]] if len(vals) > 1 else vals[0]
        if re.fullmatch(r'-?\d+', x):
            v = int(x)
            out.append('1.0' if v == 1065353216 else '%d.0' % v)
            continue
        f = F32(float(x))
        r = repr(float(f))
        for p in range(6, 10):
            t = '%.*g' % (p, f)
            if F32(float(t)) == f:
                r = t
                break
        if 'e' not in r and '.' not in r:
            r += '.0'
        out.append(r)
    return out[0] if len(out) == 1 else 'vec%d(%s)' % (len(out), ', '.join(out))


class Ctx:
    def __init__(self, lines, texmap):
        self.lines = lines
        self.texmap = texmap
        self.v = set()
        self.cb = set()
        self.regs = set()
        self.mask = {}  # (reg, comp) -> True when it holds a comparison mask (or a product of masks)


def operand(ctx, text, comps):
    t = text.strip()
    neg = t.startswith('-')
    if neg:
        t = t[1:].strip()
    absf = t.startswith('|')
    if absf:
        t = t.strip('|')
    if t.startswith('l('):
        e = lit([x.strip() for x in t[2:-1].split(',')], comps)
    else:
        m = re.fullmatch(r'([a-z]+)(\d*)(\[(.*)\])?(?:\.([xyzw]+))?', t)
        kind, num, _, idx, sw = m.groups()
        sw = sw or 'xyzw'
        sw = (sw * 4)[:4] if len(sw) < 4 else sw
        sel = ''.join(sw[SW[c]] for c in comps)
        if kind == 'r':
            ctx.regs.add(int(num))
            name = 'rainR[%s]' % num
        elif kind == 'v':
            name = 'v' + num
            ctx.v.add(int(num))
        elif kind == 'cb':
            if not re.fullmatch(r'\d+', idx):
                raise ValueError('indexed constant buffer ' + t)
            name = 'cb%s_%s' % (num, idx)
            ctx.cb.add((int(num), int(idx)))
        else:
            raise ValueError(t)
        e = '%s.%s' % (name, sel)
    if absf:
        e = 'abs(%s)' % e
    if neg:
        e = '-' + e if not e.startswith('-') else '-(%s)' % e
    return e


def is_mask(ctx, text, comps):
    """Every selected component of the operand is a 0/1 mask (comparison result, front-face flag)."""
    t = text.strip().lstrip('-').strip('|')
    if t.startswith('l('):
        vals = [x.strip() for x in t[2:-1].split(',')]
        return all(v in ('0', '1065353216', '0.00000000e+00') for v in vals)
    m = re.fullmatch(r'([a-z]+)(\d*)(?:\[[^\]]*\])?(?:\.([xyzw]+))?', t)
    kind, num, sw = m.groups()
    sw = sw or 'xyzw'
    sw = (sw * 4)[:4] if len(sw) < 4 else sw
    if kind == 'v':
        return True  # is_front_face (v10/v11), the only v register used as a mask
    if kind != 'r':
        return False
    return all(ctx.mask.get(('r' + num, sw[SW[c]]), False) for c in comps)


def translate(ctx, a, b, indent='    '):
    out, depth = [], 0
    for ln in range(a, b + 1):
        s = re.sub(r'^\d+:', '', ctx.lines[ln - 1]).strip()
        if not s:
            continue
        op, _, rest = s.partition(' ')
        pad = indent * (depth + 1)
        cite = ' // %d' % ln
        if op in ('if_nz', 'if_z'):
            e = operand(ctx, rest, 'x')
            out.append('%sif (%s %s 0.0) {%s' % (pad, e, '!=' if op == 'if_nz' else '==', cite))
            depth += 1
            continue
        if op == 'else':
            out.append('%s} else {%s' % (indent * depth, cite))
            continue
        if op == 'endif':
            depth -= 1
            out.append('%s}%s' % (indent * (depth + 1), cite))
            continue
        sat = op.endswith('_sat')
        base = op[:-4] if sat else op
        if base.startswith('sample'):
            args = split_args(rest.split(')', 1)[1] if rest.startswith('(') else rest)
            d, uvs, tex = args[0], args[1], args[2]
            dm = re.fullmatch(r'r(\d+)\.([xyzw]+)', d)
            ctx.regs.add(int(dm.group(1)))
            comps = dm.group(2)
            uv = operand(ctx, uvs, 'xy')
            tm = re.fullmatch(r't(\d+)\.([xyzw]+)', tex)
            sel = ''.join(tm.group(2)[SW[c]] for c in comps)
            layer = LAYER[ctx.texmap[int(tm.group(1))]]
            out.append('%srainR[%s].%s = rainTex(%s, %s).%s;%s' % (pad, dm.group(1), comps, layer, uv, sel, cite))
            for c in comps:
                ctx.mask[('r' + dm.group(1), c)] = False
            continue
        args = split_args(rest)
        dm = re.fullmatch(r'r(\d+)(?:\.([xyzw]+))?', args[0])
        reg = 'r' + dm.group(1)
        ctx.regs.add(int(dm.group(1)))
        comps = dm.group(2) or 'xyzw'
        n = len(comps)
        dst = 'rainR[%s].%s' % (dm.group(1), comps)
        T = 'float' if n == 1 else 'vec%d' % n
        result_mask = False
        if base in ('dp2', 'dp3', 'dp4'):
            k = int(base[2])
            e = 'dot(%s, %s)' % (operand(ctx, args[1], 'xyzw'[:k]), operand(ctx, args[2], 'xyzw'[:k]))
            if n > 1:
                e = '%s(%s)' % (T, e)
        else:
            S = [operand(ctx, x, comps) for x in args[1:]]
            if base == 'mov':
                e = S[0]
                result_mask = is_mask(ctx, args[1], comps) and not sat
            elif base == 'add':
                e = '%s + %s' % (S[0], S[1])
            elif base == 'mul':
                e = '%s * %s' % (S[0], S[1])
            elif base == 'mad':
                e = '%s * %s + %s' % (S[0], S[1], S[2])
            elif base == 'div':
                e = '%s / %s' % (S[0], S[1])
            elif base in ('min', 'max'):
                e = '%s(%s, %s)' % (base, S[0], S[1])
            elif base == 'frc':
                e = 'fract(%s)' % S[0]
            elif base == 'sqrt':
                e = 'sqrt(%s)' % S[0]
            elif base == 'rsq':
                e = 'inversesqrt(%s)' % S[0]
            elif base == 'round_ni':
                e = 'floor(%s)' % S[0]
            elif base == 'exp':
                e = 'exp2(%s)' % S[0]
            elif base == 'log':
                e = 'log2(%s)' % S[0]
            elif base in MASK_OPS:
                fn = {'lt': 'lessThan', 'ge': 'greaterThanEqual', 'eq': 'equal', 'ne': 'notEqual'}[base]
                opx = {'lt': '<', 'ge': '>=', 'eq': '==', 'ne': '!='}[base]
                e = ('(%s %s %s) ? 1.0 : 0.0' % (S[0], opx, S[1]) if n == 1
                     else '%s(%s(%s, %s))' % (T, fn, S[0], S[1]))
                result_mask = True
            elif base == 'and':
                m1, m2 = is_mask(ctx, args[1], comps), is_mask(ctx, args[2], comps)
                if not (m1 or m2):
                    raise ValueError('%d: and without a mask operand: %s' % (ln, s))
                if args[2].strip().startswith('l(1065353216'):
                    e = S[0]
                elif args[1].strip().startswith('l(1065353216'):
                    e = S[1]
                else:
                    e = '%s * %s' % (S[0], S[1])
                result_mask = m1 and m2
            elif base == 'movc':
                if not is_mask(ctx, args[1], comps) and not re.match(r'r3\.[xy]', args[1].strip()):
                    raise ValueError('%d: movc on a non-mask condition: %s' % (ln, s))
                e = 'rainSel(%s, %s, %s)' % (S[0], S[1], S[2])
                result_mask = is_mask(ctx, args[2], comps) and is_mask(ctx, args[3], comps)
            else:
                raise NotImplementedError('%d: %s' % (ln, s))
        if sat:
            e = 'clamp(%s, 0.0, 1.0)' % e
        out.append('%s%s = %s;%s' % (pad, dst, e, cite))
        for c in comps:
            ctx.mask[(reg, c)] = result_mask
    if depth != 0:
        raise ValueError('unbalanced if/endif in %d-%d' % (a, b))
    return out


def snippet(name, prog, a, b, texmap, doc):
    lines = load(prog)
    ctx = Ctx(lines, texmap)
    body = translate(ctx, a, b)
    params = ['vec4 v%d' % v for v in sorted(ctx.v)] + ['vec4 cb%d_%d' % c for c in sorted(ctx.cb)]
    head = ['// %s: %s lines %d-%d' % (doc, prog, a, b),
            'void %s(%s) {' % (name, ', '.join(params))]
    return head + body + ['}', ''], ctx


def generate():
    out = ['// GENERATED by tools/rain/rain_gen.py from the D3D disassembly of the character programs; do not edit.',
           '// One statement per asm line, cited (// N). rainR = the program temps; vN = its inputs; cbB_I = cbB[I].',
           '// Comparison masks are 1.0/0.0 (D3D: 0xFFFFFFFF/0). Checked against the asm by tools/rain/rain_check.py.',
           '']
    nreg = 0
    funcs = []
    for name, prog, a, b, texmap, snow, doc in SNIPPETS:
        text, ctx = snippet(name, prog, a, b, texmap, doc)
        nreg = max(nreg, max(ctx.regs) + 1)
        if snow:
            text = ['#ifdef NPR_RAIN_SNOW'] + text[:-1] + ['#endif', '']
        funcs += text
    out += ['vec4 rainR[%d];' % nreg, '',
            'float rainSel(float c, float a, float b) { return c != 0.0 ? a : b; }',
            'vec2 rainSel(vec2 c, vec2 a, vec2 b) { return mix(b, a, notEqual(c, vec2(0.0))); }',
            'vec3 rainSel(vec3 c, vec3 a, vec3 b) { return mix(b, a, notEqual(c, vec3(0.0))); }',
            'vec4 rainSel(vec4 c, vec4 a, vec4 b) { return mix(b, a, notEqual(c, vec4(0.0))); }', '']
    return '\n'.join(out + funcs), nreg


if __name__ == '__main__':
    text, nreg = generate()
    with open(OUT, 'w') as f:
        f.write(text)
    print('wrote', os.path.normpath(OUT), len(text.split('\n')), 'lines,', nreg, 'registers')
