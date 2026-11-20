"""ToolSwings design workbench: FK on dumped meshes, tool segments, clearance, clip I/O with ToolSwings.java.

exec(open(...).read()) in the eval kernel. Mesh dumps come from tools/MeshDump (/tmp/ts/<model>.mesh.json).
"""
import copy, json, math, re, textwrap
import numpy as np
from PIL import Image, ImageDraw

JAVA = '/home/user/ysm/pelica-fix/work-ToolSwings/mod/src/com/elfmcys/ysm/format/parser/ToolSwings.java'
BONES = ['UpBody', 'Head', 'RightArm', 'RightForeArm', 'RightHand', 'LeftArm', 'LeftForeArm', 'LeftHand']


def Rx(a):
    c, s = math.cos(a), math.sin(a); return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def Ry(a):
    c, s = math.cos(a), math.sin(a); return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def Rz(a):
    c, s = math.cos(a), math.sin(a); return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def desc(M, name, anc):
    while name:
        if name == anc: return True
        name = M.bones[name]['parent']
    return False


class Model:
    def __init__(s, n, path=None):
        d = json.load(open(path or f'/tmp/ts/{n}.mesh.json'))
        s.bones = {b['name']: b for b in d['bones']}
        s.order = [b['name'] for b in d['bones']]
        v = np.array(d['vertices']); s.v = v[:, :3]; s.vb = v[:, 3].astype(int); s.n = n
        s.region = np.array(['head' if (desc(s, s.order[i], 'AllHead') or desc(s, s.order[i], 'MHead')) else
                             'larm' if desc(s, s.order[i], 'LeftArm') else 'leg' if desc(s, s.order[i], 'DownBody') else
                             'rarm' if desc(s, s.order[i], 'RightArm') else 'trunk' for i in s.vb])

    def local(s, name, key):
        b = s.bones[name]; p = np.array(b['pivot']); r = np.array(b['rotate'])
        kx, ky, kz = key
        R = Rz(r[2] + math.radians(kz)) @ Ry(r[1] + math.radians(-ky)) @ Rx(r[0] + math.radians(-kx))
        M = np.eye(4); M[:3, :3] = R; T = np.eye(4); T[:3, 3] = p; Ti = np.eye(4); Ti[:3, 3] = -p
        return T @ M @ Ti

    def worlds(s, keys):
        W = {}
        def w(name):
            if name in W: return W[name]
            b = s.bones[name]; P_ = w(b['parent']) if b['parent'] else np.eye(4)
            W[name] = P_ @ s.local(name, keys.get(name, (0, 0, 0))); return W[name]
        for n in s.order: w(n)
        return W

    def pose(s, keys):
        W = s.worlds(keys); out = np.empty_like(s.v)
        for i, name in enumerate(s.order):
            m = s.vb == i
            if m.any():
                M = W[name]; out[m] = (M[:3, :3] @ s.v[m].T).T + M[:3, 3]
        return out, W


def sub(M, step):
    S = copy.copy(M); idx = np.arange(0, len(M.v), step)
    S.v = M.v[idx]; S.vb = M.vb[idx]; S.region = M.region[idx]; return S


# Tool drawn as YSM draws it: grip at the hand locator, handle forward of the hand, 10 degrees toward the fingers.
TOOLS = {'pick': dict(elev=-10, L=6.0, butt=2.0, head=4.2), 'axe': dict(elev=-10, L=6.0, butt=2.0, head=3.0, headdir=0),
         'shovel': dict(elev=-10, L=8.5, butt=2.0), 'sword': dict(elev=-10, L=9.7, butt=2.1)}


def tool_segs(W, M, side='Right', elev=-10, L=8.5, butt=1.7, head=0.0, headdir=1):
    loc = W[side + 'HandLocator']; p = np.array(M.bones[side + 'HandLocator']['pivot'])
    R = loc[:3, :3]; g = R @ p + loc[:3, 3]
    a = math.radians(elev); d = R @ np.array([0, math.sin(a), -math.cos(a)]); n = R @ np.array([0, math.cos(a), math.sin(a)])
    tip = g + d * L; segs = [(g - d * butt, tip)]
    if head: segs.append((tip + n * head * headdir, tip - n * head))
    return segs, g, tip


def clearance(M, keys, tool, skip=('rarm',)):
    pts, W = M.pose(keys); segs, _, _ = tool_segs(W, M, **TOOLS[tool])
    m = ~np.isin(M.region, skip); body = pts[m]; reg = M.region[m]; best = (1e9, None)
    for a, b in segs:
        for t in np.linspace(0, 1, 12):
            q = a + (b - a) * t; dd = np.linalg.norm(body - q, axis=1); i = dd.argmin()
            if dd[i] < best[0]: best = (dd[i], reg[i])
    return best


COL = {'head': (120, 120, 200), 'larm': (60, 160, 60), 'rarm': (220, 120, 40), 'leg': (150, 150, 150), 'trunk': (110, 110, 110)}


def view(M, keys, tools=(), title='', S=6, ylo=8, yhi=42, zr=20):
    pts, W = M.pose(keys)
    w, h = int(2 * zr * S), int((yhi - ylo) * S)
    img = Image.new('RGB', (w * 2, h), 'white'); d = ImageDraw.Draw(img)
    def P2(p, side):
        if side == 0: return (w / 2 + (-p[2]) * S, h - (p[1] - ylo) * S)
        return (w + w / 2 + (-p[0]) * S, h - (p[1] - ylo) * S)
    for side in (0, 1):
        o = np.argsort(pts[:, 0]) if side == 0 else np.argsort(-pts[:, 2])
        for i in o[::2]:
            x, y = P2(pts[i], side); d.point((x, y), fill=COL[M.region[i]])
        for (a, b) in tools: d.line([P2(a, side), P2(b, side)], fill=(255, 0, 0), width=3)
    d.line([(w, 0), (w, h)], fill='black'); d.text((5, 5), title, fill='black')
    return img


# ---- Molang -----------------------------------------------------------------------------------------------------
TOK = re.compile(r"\s*(?:(\d+\.?\d*)|('[^']*')|([A-Za-z_][\w.]*)|(\|\||&&|==|!=|<=|>=|[-+*/()?:!<>,;=]))")


def tokenize(s):
    out = []; i = 0
    while i < len(s):
        m = TOK.match(s, i)
        if not m or m.end() == i:
            if s[i:].strip() == '': break
            raise ValueError(s[i:i + 30])
        out.append(next(g for g in m.groups() if g is not None)); i = m.end()
    return out


class Molang:
    PREC = {'?': 1, '||': 2, '&&': 3, '==': 4, '!=': 4, '<': 5, '>': 5, '<=': 5, '>=': 5, '+': 6, '-': 6, '*': 7, '/': 7}
    OPS = {'||': lambda a, b: float(bool(a) or bool(b)), '&&': lambda a, b: float(bool(a) and bool(b)),
           '==': lambda a, b: float(a == b), '!=': lambda a, b: float(a != b), '<': lambda a, b: float(a < b),
           '>': lambda a, b: float(a > b), '<=': lambda a, b: float(a <= b), '>=': lambda a, b: float(a >= b),
           '+': lambda a, b: a + b, '-': lambda a, b: a - b, '*': lambda a, b: a * b, '/': lambda a, b: a / b}

    def __init__(s, env): s.env = env

    def run(s, src): s.t = tokenize(src); s.i = 0; return s.expr(0)

    def peek(s): return s.t[s.i] if s.i < len(s.t) else None

    def nxt(s): v = s.t[s.i]; s.i += 1; return v

    def expr(s, minp):
        left = s.unary()
        while True:
            op = s.peek()
            if op not in s.PREC or s.PREC[op] < minp: return left
            s.nxt()
            if op == '?':
                a = s.expr(0); assert s.nxt() == ':'; b = s.expr(1); left = a if left else b; continue
            left = s.OPS[op](left, s.expr(s.PREC[op] + 1))

    def unary(s):
        tk = s.nxt()
        if tk == '-': return -s.unary()
        if tk == '!': return float(not s.unary())
        if tk == '(':
            v = s.expr(0); assert s.nxt() == ')'; return v
        if tk[0].isdigit(): return float(tk)
        if tk[0] == "'": return tk[1:-1]
        if s.peek() == '(':
            s.nxt(); args = []
            while s.peek() != ')':
                args.append(s.expr(0))
                if s.peek() == ',': s.nxt()
            s.nxt(); return s.env[tk](*args)
        return s.env.get(tk, 0.0)


def env_for(LD=0.0, VAR=True, tool=True, state='idle', offhand=False):
    return {'ysm.has_mainhand': 1.0, 'ysm.has_offhand': 1.0 if offhand else 0.0, 'v.tool_slash': 1.0 if VAR else 0.0,
            'ysm.head_pitch': -LD, 'math.clamp': lambda x, a, b: min(max(x, a), b), 'math.mod': lambda a, b: a % b,
            'math.floor': math.floor, 'ctrl.hold': lambda slot, f: 1.0 if (tool and f == '#minecraft:tools') else 0.0,
            'ctrl.use': lambda *a: 0.0, 'ctrl.' + state: 1.0}


def evm(x, env): return float(x) if not isinstance(x, str) else float(Molang(env).run(x))


def kv(v): return v.get('post', v.get('pre')) if isinstance(v, dict) else v


def clip_at(AN, name, t, env):
    out = {}
    for b, ch in AN[name]['bones'].items():
        rot = ch.get('rotation')
        if rot is None: continue
        if isinstance(rot, list): out[b] = np.array([evm(v, env) for v in rot]); continue
        keys = sorted(rot.items(), key=lambda p: float(p[0])); ts = [float(k) for k, _ in keys]
        i = max([j for j in range(len(ts)) if ts[j] <= t + 1e-9] or [0]); j = min(i + 1, len(ts) - 1)
        s = 0 if j == i else min(1, (t - ts[i]) / (ts[j] - ts[i]))
        a = np.array([evm(v, env) for v in kv(keys[i][1])]); c = np.array([evm(v, env) for v in kv(keys[j][1])])
        out[b] = a + (c - a) * s
    return out


def swing_pose(AN, M, name, t, env, base='idle'):
    k = clip_at(AN, base, 0, env); k.update(clip_at(AN, name, t, env))
    for b, ch in AN['parallel4']['bones'].items():
        r = ch.get('rotation')
        if r is not None and b in k: k[b] = k[b] + np.array([evm(v, env) for v in r])
    return {b: tuple(v) for b, v in k.items() if b in M.bones}


def sweep(AN, M, name, tool, env, T, n=17):
    r = []
    for t in np.linspace(0, T, n):
        c = clearance(M, swing_pose(AN, M, name, t, env), tool); r.append((round(c[0], 2), round(t, 3), c[1]))
    return min(r), r


# ---- design keys ------------------------------------------------------------------------------------------------
class K:
    """t, weight w (1 action, 0 hold); v: bone -> 6 (right/trunk absolute, left relative to its hold)."""
    def __init__(s, t, w, vals):
        s.t = t; s.w = w; s.v = {}
        for b in BONES:
            v = np.zeros(6); x = vals.get(b)
            if x is not None: v[:len(x)] = x
            s.v[b] = v


def herm(ts, vals, t):
    n = len(ts)
    if t <= ts[0]: return vals[0]
    if t >= ts[-1]: return vals[-1]
    i = max(j for j in range(n - 1) if ts[j] <= t)
    def sl(j):
        if j == 0 or j == n - 1: return 0 * vals[0]
        return (vals[j + 1] - vals[j - 1]) / (ts[j + 1] - ts[j - 1])
    h = ts[i + 1] - ts[i]; s = (t - ts[i]) / h; s2 = s * s; s3 = s2 * s
    return (2 * s3 - 3 * s2 + 1) * vals[i] + (s3 - 2 * s2 + s) * h * sl(i) + (-2 * s3 + 3 * s2) * vals[i + 1] + (s3 - s2) * h * sl(i + 1)


HOLDSIM = {b: np.zeros(3) for b in BONES}


def final_pose(keys, t, p=0.0):
    ts = [k.t for k in keys]; w = herm(ts, [np.array([k.w]) for k in keys], t)[0]; out = {}
    for b in BONES:
        v = herm(ts, [k.v[b] for k in keys], t); share = 1.0 if b.startswith('Left') else (1 - w)
        out[b] = tuple(v[:3] + v[3:] * p + share * HOLDSIM[b])
    return out


def psweep(M, keys, tool, p=0.0, n=25):
    T = keys[-1].t; r = []
    for t in np.linspace(0, T, n):
        c = clearance(M, final_pose(keys, t, p), tool); r.append((round(c[0], 2), round(t, 3), c[1]))
    return min(r), r


def parse_java(path=JAVA):
    src = open(path).read(); motions = {}
    for m in re.finditer(r'private static Motion (\w+)\(\) \{\s*return new Motion\(([\d.]+)f\)(.*?);\n    \}', src, re.S):
        keys = []
        for km in re.finditer(r'\.key\(([\d.]+)f, ([\d.]+)f(.*?)\)(?=\s*(?:\.key|$))', m.group(3), re.S):
            vals = {}
            for bm in re.finditer(r'"(\w+)", ([-\d., ]+?)(?=,\s*"|$)', km.group(3).strip(', \n'), re.S):
                vals[bm.group(1)] = [float(x) for x in bm.group(2).split(',') if x.strip()]
            keys.append(K(float(km.group(1)), float(km.group(2)), vals))
        motions[m.group(1)] = (float(m.group(2)), keys)
    return motions


def fmt(v, dec=1):
    v = round(float(v), dec)
    if v == 0: return '0'
    return f'{v:.{dec}f}'.rstrip('0').rstrip('.')


def emit(name, keys, length, doc):
    wrapped = textwrap.wrap(doc, 112)
    lines = [f'    /** {doc} */'] if len(wrapped) == 1 else ['    /**'] + [f'     * {l}' for l in wrapped] + ['     */']
    lines += [f'    private static Motion {name}() {{', f'        return new Motion({fmt(length, 3)}f)']
    for k in keys:
        if k.w == 0 and not any(np.any(np.abs(k.v[b]) > 1e-6) for b in BONES):
            lines.append(f'            .key({fmt(k.t, 3)}f, 0f)'); continue
        parts = []
        for b in BONES:
            v = k.v[b]
            if not np.any(np.abs(v) > 1e-6): continue
            nums = [fmt(x) for x in v[:3]]
            if np.any(np.abs(v[3:]) > 1e-6): nums += [fmt(x, 3) for x in v[3:]]
            parts.append(f'"{b}", ' + ', '.join(nums))
        body = (',\n                ').join(parts)
        lines.append(f'            .key({fmt(k.t, 3)}f, {fmt(k.w, 2)}f,\n                {body})')
    lines[-1] += ';'; lines.append('    }')
    return '\n'.join(lines)


def write_java(clips, docs, path=JAVA):
    code = '\n\n'.join(emit(n, c, L_, docs[n]) for n, c, L_ in clips)
    s = open(path).read()
    s = re.sub(r'(    // POSES-BEGIN[^\n]*\n).*?(    // POSES-END)', lambda m: m.group(1) + code + '\n' + m.group(2), s, flags=re.S)
    open(path, 'w').write(s)


def docs_from_java(path=JAVA):
    src = open(path).read(); out = {}
    for m in re.finditer(r'    /\*\*(.*?)\*/\n    private static Motion (\w+)\(\)', src, re.S):
        out[m.group(2)] = ' '.join(l.strip().lstrip('*').strip() for l in m.group(1).splitlines()).strip()
    return out
