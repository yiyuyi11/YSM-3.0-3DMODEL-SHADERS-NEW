#!/usr/bin/env python3
"""Offline evaluator for the endfield_npr normal maps and outline mask (npr_normal.glsl / npr_outline.vsh).

Rasterises a VRM's triangles into each material's texture space, builds the per-pixel cotangent frame exactly as
npr_normal.glsl does (Schueler 2013; the flat-triangle derivatives are the triangle's edges; image-up = Unity +V, so
the bitangent is -dP/dv of the glTF uv), perturbs the interpolated mesh normal with the material's _BumpMap and
reports, for random light directions:
  - |N'.L - N.L| over cloth/body/face texels,
  - the change of the diffuse ramp alpha (RD row of the model) and the share of texels that cross the ramp midpoint,
  - the terminator shift: |dN.L| / |grad_surface(N.L)| (metres), in screen pixels at typical view sizes,
plus the hair split-normal angles and the outline mask sampled at the mesh vertices (as npr_outline.vsh does).

usage: normal_eval.py <model.vrm> <efdata/props dir> <role> [resolution]
"""
import io
import json
import os
import struct
import sys

import numpy as np
from PIL import Image

CT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
NC = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4}


def glb(path):
    b = open(path, 'rb').read()
    n = struct.unpack_from('<I', b, 12)[0]
    js = json.loads(b[20:20 + n])
    off = 20 + n
    bl = struct.unpack_from('<I', b, off)[0]
    return js, b[off + 8:off + 8 + bl]


def accessor(js, bin_, i):
    a = js['accessors'][i]
    bv = js['bufferViews'][a['bufferView']]
    dt = np.dtype(CT[a['componentType']])
    n = NC[a['type']]
    off = bv.get('byteOffset', 0) + a.get('byteOffset', 0)
    stride = bv.get('byteStride', 0) or dt.itemsize * n
    raw = np.frombuffer(bin_, dtype=np.uint8, count=stride * (a['count'] - 1) + dt.itemsize * n, offset=off)
    rows = np.lib.stride_tricks.as_strided(raw, shape=(a['count'], dt.itemsize * n), strides=(stride, 1))
    return rows.copy().view(dt).reshape(a['count'], n).astype(np.float64)


def image(js, bin_, i):
    bv = js['bufferViews'][js['images'][i]['bufferView']]
    o = bv.get('byteOffset', 0)
    return Image.open(io.BytesIO(bin_[o:o + bv['byteLength']]))


def rgba(img, size=None):
    img = img.convert('RGBA')
    if size:
        img = img.resize((size, size), Image.BILINEAR)
    return np.asarray(img).astype(np.float64) / 255.0


def raster(tris_uv, res):
    """Yields (triangle index, texel rows, cols, barycentrics) for texel centres inside each triangle (uv: glTF,
    v down = image row)."""
    for t, uv in enumerate(tris_uv):
        p = uv * res
        x0, y0 = np.floor(p.min(0)).astype(int)
        x1, y1 = np.ceil(p.max(0)).astype(int)
        x0, y0 = max(x0, 0), max(y0, 0)
        x1, y1 = min(x1, res - 1), min(y1, res - 1)
        if x1 < x0 or y1 < y0:
            continue
        xs, ys = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        a, b, c = p
        d = (b[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (b[1] - a[1])
        if abs(d) < 1e-12:
            continue
        w1 = ((xs - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (ys - a[1])) / d
        w2 = ((b[0] - a[0]) * (ys - a[1]) - (xs - a[0]) * (b[1] - a[1])) / d
        w0 = 1 - w1 - w2
        m = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
        if not m.any():
            continue
        yield t, (ys[m] - 0.5).astype(int), (xs[m] - 0.5).astype(int), np.stack([w0[m], w1[m], w2[m]], 1)


def norm(v):
    return v / np.maximum(np.linalg.norm(v, axis=-1, keepdims=True), 1e-12)


def cotangent_frame(P, UV, N):
    """npr_normal.glsl nprCotangentFrame for one triangle: e1, e2 stand in for dFdx/dFdy(viewPos) (any two edges, ordered
    so cross(e1, e2).N > 0 like screen x/y on a front face), du/dv for the uv derivatives. N: (k,3) interpolated normals.
    Returns T, B (Unity bitangent, i.e. image-up) per texel, scaled by Schueler's invmax."""
    e1, e2 = P[1] - P[0], P[2] - P[0]
    du1, du2 = UV[1] - UV[0], UV[2] - UV[0]
    flip = np.sign(np.einsum('j,kj->k', np.cross(e1, e2), N))[:, None]
    dp2perp = np.cross(e2, N)
    dp1perp = np.cross(N, e1)
    T = (dp2perp * du1[0] + dp1perp * du2[0]) * flip
    B = (dp2perp * du1[1] + dp1perp * du2[1]) * flip
    inv = 1.0 / np.sqrt(np.maximum(np.maximum((T * T).sum(1), (B * B).sum(1)), 1e-30))[:, None]
    return T * inv, -B * inv  # glTF v grows down the image; Unity's +V (the map's +G) is image-up


def decode_normal(tex, scale):
    """EFL_DecodePackedNormal (EndfieldNormalDecode.ush:37-43, S 121-129): xy = (R*A, G)*2-1, z from the unscaled xy,
    then xy *= scale."""
    xy = np.stack([tex[..., 0] * tex[..., 3], tex[..., 1]], -1) * 2 - 1
    z = np.sqrt(np.clip(1 - (xy * xy).sum(-1), 0, 1))
    return np.concatenate([xy * scale, z[..., None]], -1)


def ramp_alpha(ramp, u):
    a = ramp[0, :, 3]
    x = np.clip(u, 0, 1) * 255
    i = np.clip(np.floor(x).astype(int), 0, 254)
    f = x - i
    return a[i] * (1 - f) + a[i + 1] * f


def pct(x, ps=(50, 90, 99)):
    return ' '.join(f'p{p}={np.percentile(x, p):.3f}' for p in ps)


def main():
    vrm, props, role = sys.argv[1], sys.argv[2], sys.argv[3]
    res = int(sys.argv[4]) if len(sys.argv) > 4 else 1024
    js, bin_ = glb(vrm)
    ext = js['extensions']['ENDFIELD_npr']
    names = [m['name'] for m in js['materials']]
    xmat = json.load(open(os.path.join(props, 'ysm-materials.json')))[role]
    bindings = [b for b in json.load(open(os.path.join(props, 'bindings.json'))) if b['role'] == role]

    def binding(unity, slot):
        for b in bindings:
            if b['material'] == unity.lower() and b['slot'] == slot and b['status'] == 'found':
                return b
        return None

    def params(unity):
        path = os.path.join(props, 'materials', role, unity + '.json')
        if not os.path.exists(path):
            path = os.path.join(props, 'materials', role, unity.lower() + '.json')
        if not os.path.exists(path):
            return None
        m = json.load(open(path))
        return {d['Key']: d['Value'] for d in m['m_Floats']}

    def tex(b, size=None):
        return rgba(Image.open(os.path.join(props, b['archive_path'])), size)

    ramps = {k: rgba(image(js, bin_, v['image'])) for k, v in ext['ramps'].items()}
    geo = {}
    bones = {}  # material -> per-vertex dominant joint name (outline report)
    mesh_skin = {n['mesh']: n.get('skin') for n in js['nodes'] if 'mesh' in n}
    for mi, me in enumerate(js['meshes']):
        for p in me['primitives']:
            a = p['attributes']
            geo.setdefault(names[p['material']], []).append((accessor(js, bin_, a['POSITION']),
                accessor(js, bin_, a['NORMAL']), accessor(js, bin_, a['TEXCOORD_0']),
                accessor(js, bin_, p['indices']).reshape(-1, 3).astype(np.int64)))
            skin = mesh_skin.get(mi)
            if skin is not None and 'JOINTS_0' in a:
                jn = [js['nodes'][j].get('name', str(j)) for j in js['skins'][skin]['joints']]
                J = accessor(js, bin_, a['JOINTS_0']).astype(int)
                W = accessor(js, bin_, a['WEIGHTS_0'])
                bones.setdefault(names[p['material']], []).extend(
                    jn[J[k, np.argmax(W[k])]] for k in range(len(J)))

    rng = np.random.default_rng(1)
    lights = norm(rng.normal(size=(48, 3)))
    # typical views: 1080p, vertical FOV 70 deg, camera 1.5 / 3 / 8 m away -> pixels per metre
    ppm = {d: 540.0 / (np.tan(np.radians(35)) * d) for d in (1.5, 3.0, 8.0)}
    print(f'# {role}: {vrm}, raster {res}^2, {len(lights)} light directions; px/m at 1080p fov70: '
          + ', '.join(f'{d} m={v:.0f}' for d, v in ppm.items()))

    for gname in names:
        if gname not in geo or gname not in xmat:
            continue
        unity = xmat[gname]['material'] if isinstance(xmat[gname], dict) else xmat[gname]
        pr = params(unity)
        kind = 'hair' if 'hair' in gname else 'face' if 'face' in gname else 'skin' if 'body' in gname else 'cloth'
        if kind == 'hair' or pr is None:
            continue
        b = binding(unity, '_BumpMap')
        use = pr.get('_UseBumpMap', 0)
        if b is None or use == 0:
            print(f'{gname:12s} ({unity}): no _BumpMap in use (binding {"found" if b else "absent"}, _UseBumpMap={use})')
            continue
        scale = pr.get('_BumpScale', 1.0) * use
        nmap = tex(b, res)
        ramp = ramps.get(f'{kind}RD', ramps['clothRD'])
        a_ramp = ramp[0, :, 3]
        mid_u = (np.argmax(a_ramp > 0.5) + 0.5) / 256
        # face: the geometric N.L only enters through the SDF mask's G (rampU = mix(SDF response, geometric, G))
        face_g = None
        if kind == 'face' and ext.get('face', {}).get('material') == gname and 'sdfMask' in ext['face']:
            face_g = rgba(image(js, bin_, ext['face']['sdfMask']['image']), res)[..., 1]
        dnl, dal, flips, shift, ang, wts, cnt, flat = [], [], [], [], [], [], 0, [0, 0]
        for P, Nv, UV, I in geo[gname]:
            for t, rows, cols, w in raster(UV[I], res):
                tri = I[t]
                N = norm(w @ Nv[tri])
                T, B = cotangent_frame(P[tri], UV[tri], N)
                ts = decode_normal(nmap[rows, cols], scale)
                Np = norm(T * ts[:, :1] + B * ts[:, 1:2] + N * ts[:, 2:3])
                ang.append(np.degrees(np.arccos(np.clip((Np * N).sum(1), -1, 1))))
                # surface gradient of the geometric N.L over this triangle (from the vertex values)
                e1, e2 = P[tri][1] - P[tri][0], P[tri][2] - P[tri][0]
                G = np.array([[e1 @ e1, e1 @ e2], [e1 @ e2, e2 @ e2]])
                if abs(np.linalg.det(G)) < 1e-20:
                    continue
                Gi = np.linalg.inv(G)
                nl_v = Nv[tri] @ lights.T  # (3, L)
                d = np.stack([nl_v[1] - nl_v[0], nl_v[2] - nl_v[0]])  # (2, L)
                coef = Gi @ d
                grad = np.linalg.norm(np.outer(e1, coef[0]) + np.outer(e2, coef[1]), axis=0)  # (L,)
                nl = N @ lights.T
                nlp = Np @ lights.T
                dnl.append(np.abs(nlp - nl).ravel())
                ug, up = nl * 0.5 + 0.5, nlp * 0.5 + 0.5
                dal.append(np.abs(ramp_alpha(ramp, up) - ramp_alpha(ramp, ug)).ravel())
                flips.append(((ug > mid_u) != (up > mid_u)).ravel())
                wts.append(np.repeat(face_g[rows, cols] if face_g is not None else np.ones(len(rows)), len(lights)))
                near = np.abs(ug - mid_u) < 0.02  # texels on the terminator (ramp alpha 0.5)
                # curved surfaces only (|grad N.L| >= 1/m, radius <= 1 m): on a flat panel the terminator sweeps the
                # whole panel at once and has no position to shift
                curved = near & (grad[None, :] >= 1.0)
                flat[0] += near.sum()
                flat[1] += (near & ~curved).sum()
                if curved.any():
                    shift.append((np.abs(nlp - nl) / grad[None, :])[curved])
                cnt += len(rows)
        if not dnl:
            continue
        dnl, dal, flips, ang, wts = map(np.concatenate, (dnl, dal, flips, ang, wts))
        shift = np.concatenate(shift) if shift else np.zeros(1)
        print(f'{gname:12s} ({unity}) _BumpScale={scale:g}: {cnt} texels; tilt deg {pct(ang)}; |dN.L| mean='
              f'{dnl.mean():.3f} {pct(dnl)}; |d rampA| mean={dal.mean():.3f} {pct(dal)}; crosses midpoint '
              f'{flips.mean() * 100:.1f}%')
        print(f'{"":12s} terminator shift (mm) {pct(shift * 1000)}; px at 1.5/3/8 m (p50, p90): '
              + ', '.join(f'{np.percentile(shift, 50) * v:.1f}/{np.percentile(shift, 90) * v:.1f}' for v in ppm.values())
              + f'; {flat[1] / max(flat[0], 1) * 100:.0f}% of terminator texels on flat parts (excluded)')
        if face_g is not None:
            g = wts
            print(f'{"":12s} face, weighted by the SDF mask G (geometric share of rampU): G>0.5 on '
                  f'{np.mean(g > 0.5) * 100:.1f}% of face texels; ramp-u change mean {(dnl * 0.5 * g).mean():.4f}, '
                  f'p99 {np.percentile(dnl * 0.5 * g, 99):.3f}; |d rampA| x G mean {(dal * g).mean():.4f}, '
                  f'p99 {np.percentile(dal * g, 99):.3f}')

    # hair split normal: RG diffuse (x _BumpScale*_UseBumpMap), BA specular (x _SpecBumpScale*_UseSpecBumpMap)
    for gname in names:
        if 'hair' not in gname or gname not in xmat:
            continue
        unity = xmat[gname]['material'] if isinstance(xmat[gname], dict) else xmat[gname]
        pr = params(unity)
        b = binding(unity, '_SplitNormalMap')
        if b is None or pr is None:
            continue
        s = tex(b, res)
        for label, ch, sc in (('diffuse RG', (0, 1), pr.get('_BumpScale', 1) * pr.get('_UseBumpMap', 0)),
                              ('spec BA', (2, 3), pr.get('_SpecBumpScale', 1) * pr.get('_UseSpecBumpMap', 0))):
            xy = s[..., ch] * 2 - 1
            z = np.sqrt(np.clip(1 - (xy * xy).sum(-1), 0, 1))
            n = norm(np.concatenate([xy * sc, z[..., None]], -1))
            m = s[..., 3] > -1
            tilt = np.degrees(np.arccos(np.clip(n[..., 2][m], -1, 1)))
            print(f'{gname:12s} split {label} scale={sc:g}: tilt deg mean={tilt.mean():.1f} {pct(tilt)}; '
                  f'mean xy=({xy[..., 0].mean():+.3f},{xy[..., 1].mean():+.3f})')

    # outline mask at the vertices (npr_outline.vsh fetches it per vertex): R scales the width, G the z offset
    print('# outline mask (per vertex, as npr_outline.vsh): R = width scale, G = z-offset scale')
    for gname in names:
        if gname not in geo or gname not in xmat:
            continue
        unity = xmat[gname]['material'] if isinstance(xmat[gname], dict) else xmat[gname]
        pr = params(unity)
        b = binding(unity, '_OutlineMask')
        if pr is None:
            continue
        en, enm = pr.get('_EnableOutline', 1), pr.get('_EnableOutlineMask', 0)
        w, oz = pr.get('_OutlineWidth', None), pr.get('_OutlineOffsetZ', None)
        if b is None or enm == 0:
            print(f'{gname:12s} ({unity}): width={w} offsetZ={oz} enable={en} mask {"off" if b else "none"}')
            continue
        m = tex(b)
        h, wd = m.shape[:2]
        offs = np.cumsum([0] + [len(g[0]) for g in geo[gname]])
        P = np.concatenate([g[0] for g in geo[gname]])
        UV = np.concatenate([g[2] for g in geo[gname]])
        I = np.concatenate([g[3] + o for g, o in zip(geo[gname], offs)])
        px = np.clip((UV * [wd, h]).astype(int), 0, [wd - 1, h - 1])
        r, g = m[px[:, 1], px[:, 0], 0], m[px[:, 1], px[:, 0], 1]
        gone = r < 0.05
        thin = (r >= 0.05) & (r < 0.95)
        print(f'{gname:12s} ({unity}): width={w} offsetZ={oz} enable={en}; {len(r)} vertices: R<0.05 (no outline) '
              f'{gone.mean() * 100:.1f}%, 0.05<=R<0.95 {thin.mean() * 100:.1f}%, mean R {r.mean():.3f}; '
              f'G mean {g.mean():.3f}, G<0.05 {np.mean(g < 0.05) * 100:.1f}% (z offset off), '
              f'effective offsetZ*G mean {oz * g.mean() if oz is not None else 0:.3f}')
        # the masked areas: connected components of triangles whose three vertices lose the outline (by position,
        # so split vertices join), largest first
        tri = I[gone[I].all(1)]
        if len(tri):
            key = np.round(P / 1e-4).astype(np.int64)
            _, weld = np.unique(key, axis=0, return_inverse=True)
            weld = weld.ravel()
            parent = np.arange(weld.max() + 1)

            def find(x):
                while parent[x] != x:
                    parent[x] = parent[parent[x]]
                    x = parent[x]
                return x
            for a, b2, c in weld[tri]:
                ra, rb, rc = find(a), find(b2), find(c)
                parent[rb] = ra
                parent[find(rc)] = ra
            roots = np.array([find(x) for x in weld[tri[:, 0]]])
            bn = bones.get(gname)
            for root in sorted(set(roots), key=lambda q: -np.sum(roots == q))[:6]:
                vid = np.unique(tri[roots == root])
                q = P[vid]
                where = ''
                if bn and len(bn) == len(P):
                    names_, counts = np.unique([bn[v] for v in vid], return_counts=True)
                    where = ', bones ' + '/'.join(names_[np.argsort(-counts)][:2])
                print(f'{"":12s} no-outline area: {np.sum(roots == root)} tris, centre ({q[:, 0].mean():+.3f}, '
                      f'{q[:, 1].mean():.3f}, {q[:, 2].mean():+.3f}) m, size {np.ptp(q[:, 0]):.3f} x '
                      f'{np.ptp(q[:, 1]):.3f} x {np.ptp(q[:, 2]):.3f} m{where}')


if __name__ == '__main__':
    main()
