#!/usr/bin/env python3
"""Look up SRG names for Mojang-mapped members: srg.py <mojmap.Class> [member ...]"""
import sys, os, re, functools
L = os.path.expanduser('~/mc/client/libraries')
PG = L + '/net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-mappings.txt'
TS = L + '/de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/mcp_config-1.20.1-20230612.114412-mappings-merged.txt'

@functools.lru_cache(None)
def load():
    pg = {}  # moj class -> (obf, {(name, desc_moj): obf})
    cur = None
    for line in open(PG):
        if line.startswith('#'): continue
        if not line.startswith(' '):
            m = re.match(r'(\S+) -> (\S+):', line)
            cur = m.group(1); pg[cur] = (m.group(2), [])
        else:
            m = re.match(r'\s+(?:\d+:\d+:)?(\S+) (\S+?)(\(.*?\))? -> (\S+)', line)
            if m: pg[cur][1].append((m.group(2), m.group(1), m.group(3) or '', m.group(4)))
    ts = {}
    curc = None
    for line in open(TS):
        if line.startswith('tsrg'): continue
        if not line.startswith('\t'):
            a, b = line.split()[:2]; curc = a; ts[a] = {}
        elif not line.startswith('\t\t'):
            p = line.split()
            if len(p) == 2: ts[curc][(p[0], None)] = p[1]
            elif len(p) == 3: ts[curc][(p[0], p[1])] = p[2]
    return pg, ts

PRIM = {'void':'V','boolean':'Z','byte':'B','char':'C','short':'S','int':'I','long':'J','float':'F','double':'D'}
def jd(t, pg):
    dims = t.count('[]'); t = t.replace('[]', '')
    if t in PRIM: d = PRIM[t]
    else: d = 'L' + (pg[t][0] if t in pg else t).replace('.', '/') + ';'
    return '[' * dims + d

def main():
    pg, ts = load()
    cls = sys.argv[1]
    obf, members = pg[cls]
    want = set(sys.argv[2:])
    for name, ret, args, o in members:
        if want and name not in want: continue
        srg = None
        want_desc = None if args == '' else '(' + ''.join(jd(t, pg) for t in args[1:-1].split(',') if t) + ')' + jd(ret, pg)
        for (on, desc), s in ts[obf].items():
            if on == o and desc == want_desc:
                srg = s
        print(f'{name}{args}:{ret} -> {o} -> {srg}')
main()
