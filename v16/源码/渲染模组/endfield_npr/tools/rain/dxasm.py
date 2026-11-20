"""Tiny interpreter for the DXBC asm subset of /tmp/dxdis/out (rain/wet blocks), float32/uint32 exact-ish.

run(lines, a, b, state) executes asm lines a..b (1-based, inclusive) on `state`:
  state.r[name] -> np.uint32[4] bit patterns, state.v[name] -> float32[4], state.cb[(buf, idx)] -> float32[4],
  state.tex(slot, uv[2]) -> float32[4] (texture fetch stub), state.ffront (is_front_face) via v-reg.
"""
import re
import numpy as np

F = np.float32
SW = {'x': 0, 'y': 1, 'z': 2, 'w': 3}


def f2u(a):
    return np.asarray(a, dtype=np.float32).view(np.uint32)


def u2f(a):
    return np.asarray(a, dtype=np.uint32).view(np.float32)


class State:
    def __init__(self):
        self.r = {}
        self.v = {}
        self.cb = {}
        self.tex = None
        self.trace = False

    def reg(self, name):
        if name not in self.r:
            self.r[name] = np.zeros(4, dtype=np.uint32)
        return self.r[name]


OPND = re.compile(r'\s*(-?)(\|?)(-?)([a-z]+\d*(?:\[[^\]]*\])?)(?:\.([xyzw]+))?\|?\s*$')


def split_args(s):
    out, depth, cur = [], 0, ''
    for ch in s:
        if ch in '([':
            depth += 1
        elif ch in ')]':
            depth -= 1
        if ch == ',' and depth == 0:
            out.append(cur.strip())
            cur = ''
        else:
            cur += ch
    if cur.strip():
        out.append(cur.strip())
    return out


class Src:
    def __init__(self, st, text):
        self.st = st
        self.text = text

    def bits(self):
        """uint32[4] after swizzle (no modifiers)."""
        t = self.text.strip()
        neg = absf = False
        if t.startswith('-'):
            neg, t = True, t[1:].strip()
        if t.startswith('|'):
            absf, t = True, t.strip('|')
        self.neg, self.absf = neg, absf
        if t.startswith('l('):
            vals = [x.strip() for x in t[2:-1].split(',')]
            out = []
            for x in vals:
                if re.fullmatch(r'-?\d+', x):
                    out.append(np.uint32(int(x) & 0xffffffff))
                else:
                    out.append(f2u(F(float(x))))
            if len(out) == 1:
                out = out * 4
            return np.array(out, dtype=np.uint32)
        m = re.fullmatch(r'([a-z]+)(\d*)(\[(.*)\])?(?:\.([xyzw]+))?', t)
        if not m:
            raise ValueError('operand ' + self.text)
        kind, num, _, idx, sw = m.groups()
        if kind == 'r':
            base = self.st.reg('r' + num)
        elif kind == 'v':
            base = f2u(self.st.v['v' + num])
        elif kind == 'cb':
            i = idx
            mm = re.fullmatch(r'r(\d+)\.([xyzw])\s*\+\s*(\d+)', i)
            if mm:
                k = int(self.st.reg('r' + mm.group(1))[SW[mm.group(2)]]) + int(mm.group(3))
            else:
                k = int(i)
            base = f2u(self.st.cb[(int(num), k)])
        else:
            raise ValueError('operand kind ' + self.text)
        sw = sw or 'xyzw'
        sw = (sw * 4)[:4] if len(sw) < 4 else sw
        return np.array([base[SW[c]] for c in sw], dtype=np.uint32)

    def f(self):
        b = self.bits()
        x = u2f(b).copy()
        if self.absf:
            x = np.abs(x)
        if self.neg:
            x = -x
        return x.astype(F)

    def i(self):
        b = self.bits().astype(np.int64)
        if self.neg:
            b = (-b) & 0xffffffff
        return b.astype(np.uint32)


def dest(st, text):
    t = text.strip()
    m = re.fullmatch(r'r(\d+)(?:\.([xyzw]+))?', t)
    if not m:
        raise ValueError('dest ' + text)
    return st.reg('r' + m.group(1)), [SW[c] for c in (m.group(2) or 'xyzw')]


def write(st, dtext, val, sat=False, isfloat=True, scalar=False):
    reg, mask = dest(st, dtext)
    for c in mask:
        x = val[0] if scalar else val[c]
        if isfloat:
            x = F(x)
            if sat:
                x = F(min(max(x, F(0)), F(1))) if not np.isnan(x) else F(0)
            reg[c] = f2u(x)
        else:
            reg[c] = np.uint32(int(x) & 0xffffffff)


def run(lines, a, b, st, executed=None):
    """Execute lines[a-1..b-1] with structured if/else/endif."""
    stack = []  # (executing, taken)
    executing = True
    for ln in range(a, b + 1):
        raw = lines[ln - 1]
        s = re.sub(r'^\d+:', '', raw).strip()
        if not s or s.startswith('//'):
            continue
        op, _, rest = s.partition(' ')
        if op in ('if_nz', 'if_z'):
            if executing:
                v = Src(st, rest).bits()[0]
                cond = (v != 0) if op == 'if_nz' else (v == 0)
                stack.append((executing, cond))
                executing = cond
            else:
                stack.append((False, True))
            continue
        if op == 'else':
            outer, cond = stack[-1]
            executing = outer and not cond
            continue
        if op == 'endif':
            outer, cond = stack.pop()
            executing = outer
            continue
        if not executing:
            continue
        exec_op(st, op, rest, ln)
        if executed is not None:
            executed.add(ln)


def exec_op(st, op, rest, ln):
    sat = op.endswith('_sat')
    base = op[:-4] if sat else op
    args = split_args(rest)
    if base.startswith('sample'):
        # sample_b_indexable(texture2d) rD, uv, tN.swz, sM, bias
        m = re.match(r'(\S+)\s+(.*)', rest) if rest.startswith('(') else None
        args = split_args(rest.split(')', 1)[1]) if rest.startswith('(') else args
        d, uvs, tex = args[0], args[1], args[2]
        uv = Src(st, uvs).f()
        tm = re.fullmatch(r't(\d+)\.([xyzw]+)', tex)
        texel = st.tex(int(tm.group(1)), uv)
        sw = tm.group(2)
        val = np.array([texel[SW[c]] for c in sw], dtype=F)
        write(st, d, val, sat)
        return
    d = args[0]
    S = [Src(st, x) for x in args[1:]]
    fl = lambda k: S[k].f()
    if base == 'mov':
        if sat or True:
            b = S[0].bits()
            if S[0].text.strip().startswith(('-', '|')) or sat:
                write(st, d, S[0].f(), sat)
            else:
                write(st, d, b, isfloat=False)
        return
    if base == 'movc':
        c = S[0].bits()
        x, y = S[1].bits(), S[2].bits()
        if S[1].text.strip().startswith(('-', '|')):
            x = f2u(S[1].f())
        if S[2].text.strip().startswith(('-', '|')):
            y = f2u(S[2].f())
        val = np.where(c != 0, x, y).astype(np.uint32)
        if sat:
            write(st, d, u2f(val), sat)
        else:
            write(st, d, val, isfloat=False)
        return
    fops = {
        'add': lambda: fl(0) + fl(1),
        'mul': lambda: fl(0) * fl(1),
        'mad': lambda: F(1) * (fl(0) * fl(1)).astype(F) + fl(2),
        'div': lambda: np.divide(fl(0), fl(1), dtype=F),
        'min': lambda: np.minimum(fl(0), fl(1)),
        'max': lambda: np.maximum(fl(0), fl(1)),
        'frc': lambda: (fl(0) - np.floor(fl(0))).astype(F),
        'sqrt': lambda: np.sqrt(fl(0)).astype(F),
        'rsq': lambda: (F(1) / np.sqrt(fl(0))).astype(F),
        'round_ni': lambda: np.floor(fl(0)).astype(F),
        'round_pi': lambda: np.ceil(fl(0)).astype(F),
        'round_z': lambda: np.trunc(fl(0)).astype(F),
        'round_ne': lambda: np.round(fl(0)).astype(F),
        'exp': lambda: np.exp2(fl(0)).astype(F),
        'log': lambda: np.log2(fl(0)).astype(F),
    }
    with np.errstate(all='ignore'):
        if base in fops:
            write(st, d, fops[base](), sat)
            return
        if base in ('dp2', 'dp3', 'dp4'):
            n = int(base[2])
            a, b = fl(0), fl(1)
            acc = F(0)
            for k in range(n):
                acc = F(acc + F(a[k] * b[k]))
            write(st, d, [acc], sat, scalar=True)
            return
        cmp = {'lt': np.less, 'ge': np.greater_equal, 'eq': np.equal, 'ne': np.not_equal}
        if base in cmp:
            r = cmp[base](fl(0), fl(1))
            write(st, d, np.where(r, 0xffffffff, 0).astype(np.uint32), isfloat=False)
            return
        if base in ('and', 'or', 'xor'):
            a, b = S[0].i(), S[1].i()
            r = {'and': np.bitwise_and, 'or': np.bitwise_or, 'xor': np.bitwise_xor}[base](a, b)
            write(st, d, r.astype(np.uint32), isfloat=False)
            return
        if base == 'not':
            write(st, d, (~S[0].i()).astype(np.uint32), isfloat=False)
            return
        if base == 'ishl':
            write(st, d, (S[0].i().astype(np.uint64) << (S[1].i() & 31)).astype(np.uint64) & 0xffffffff,
                  isfloat=False)
            return
        if base == 'ushr':
            write(st, d, S[0].i() >> (S[1].i() & 31), isfloat=False)
            return
        if base == 'iadd':
            write(st, d, (S[0].i().astype(np.uint64) + S[1].i()) & 0xffffffff, isfloat=False)
            return
        if base == 'ubfe':
            w, o, x = S[0].i() & 31, S[1].i() & 31, S[2].i()
            r = [(int(x[k]) >> int(o[k])) & ((1 << int(w[k])) - 1) if w[k] else 0 for k in range(4)]
            write(st, d, r, isfloat=False)
            return
        if base == 'utof':
            write(st, d, S[0].i().astype(np.float64).astype(F))
            return
        if base == 'itof':
            write(st, d, S[0].i().view(np.int32).astype(F))
            return
        if base == 'ftou':
            write(st, d, np.clip(np.nan_to_num(fl(0)), 0, 4294967295).astype(np.uint32), isfloat=False)
            return
        if base == 'ftoi':
            write(st, d, np.trunc(fl(0)).astype(np.int64).astype(np.uint32), isfloat=False)
            return
    raise NotImplementedError('line %d: %s %s' % (ln, op, rest))


# ---------- dataflow: component-level live-in / defs of a range ----------
def reads_writes(s):
    s = re.sub(r'^\d+:', '', s).strip()
    op, _, rest = s.partition(' ')
    if rest.startswith('('):
        rest = rest.split(')', 1)[1]
    args = split_args(rest)
    if op in ('if_nz', 'if_z'):
        return op, [], [(m.group(1), m.group(2)) for m in re.finditer(r'(r\d+)\.([xyzw])', rest)]
    if op in ('else', 'endif'):
        return op, [], []
    if not args:
        return op, [], []
    d = args[0]
    m = re.fullmatch(r'r(\d+)(?:\.([xyzw]+))?', d.strip())
    w = [('r' + m.group(1), c) for c in (m.group(2) or 'xyzw')] if m else []
    mask = [c for _, c in w]
    srcs = args[1:]
    rd = []
    full4 = op.startswith(('dp',)) or op.startswith('sample')
    for k, x in enumerate(srcs):
        x = x.strip().lstrip('-').strip('|')
        for mm in re.finditer(r'\b([rv]\d+|cb\d+\[[^\]]*\])(?:\.([xyzw]+))?', x):
            nm, sw = mm.group(1), mm.group(2) or 'xyzw'
            sw = (sw * 4)[:4] if len(sw) < 4 else sw
            if op.startswith('sample') and k == 0:
                comps = set(sw[:2])
            elif op.startswith('sample'):
                comps = set()
            elif op.startswith('dp'):
                comps = set(sw[:int(op[2])])
            elif x.startswith(nm) and '[' not in x[:len(nm)] and nm.startswith('cb'):
                comps = set(sw[SW[c]] for c in mask)
            else:
                comps = set(sw[SW[c]] for c in mask) if mask else set(sw)
            if nm.startswith('cb') and '[' in nm:
                for ir in re.finditer(r'(r\d+)\.([xyzw])', nm):
                    rd.append((ir.group(1), ir.group(2)))
            for c in comps:
                rd.append((nm, c))
    return op, w, rd


def live_in(lines, a, b):
    """Components read before written in a..b (conservative across branches: a write inside if does not kill)."""
    written, depth, livein = set(), 0, []
    for ln in range(a, b + 1):
        op, w, rd = reads_writes(lines[ln - 1])
        for r in rd:
            if r not in written and r not in [x for x, _ in livein]:
                livein.append((r, ln))
        if op in ('if_nz', 'if_z'):
            depth += 1
        elif op == 'endif':
            depth -= 1
        for x in w:
            written.add(x)
    return livein


def live_out(lines, a, b, horizon=4000):
    """Components written in a..b that are read after b before a full overwrite (straight-line approx)."""
    defs = set()
    for ln in range(a, b + 1):
        op, w, rd = reads_writes(lines[ln - 1])
        defs.update(w)
    out, killed = [], set()
    for ln in range(b + 1, min(len(lines), b + horizon) + 1):
        if not lines[ln - 1].strip():
            continue
        op, w, rd = reads_writes(lines[ln - 1])
        for r in rd:
            if r in defs and r not in killed and r not in [x for x, _ in out]:
                out.append((r, ln))
        for x in w:
            if op not in ('movc',):
                killed.add(x)
    return out


def defsite(lines, reg, before):
    for ln in range(before - 1, 0, -1):
        op, w, rd = reads_writes(lines[ln - 1])
        if reg in w:
            return ln
    return None
