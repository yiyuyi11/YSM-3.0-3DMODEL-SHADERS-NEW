#!/usr/bin/env python3
"""Write moj2srg.tsrg (Mojang names -> SRG members, class names unchanged) for 1.20.1 from the client libraries."""
import sys, os
sys.argv = ['srg.py', 'x']
exec(open(os.path.join(os.path.dirname(__file__), 'srg.py')).read().split('\ndef main():')[0])
pg, ts = load()
IDENT = {}
def jd2(t):
    return jd(t, IDENT)  # mojang descriptor (class names unchanged)
out = []
for moj, (obf, members) in pg.items():
    cls = moj.replace('.', '/')
    out.append(f'{cls} {cls}')
    tm = ts.get(obf, {})
    seen = set()
    for name, ret, args, o in members:
        if args == '':
            s = tm.get((o, None))
            if s and s != name and ('f', name) not in seen:
                seen.add(('f', name)); out.append(f'\t{name} {s}')
        else:
            od = '(' + ''.join(jd(t, pg) for t in args[1:-1].split(',') if t) + ')' + jd(ret, pg)
            s = tm.get((o, od))
            md = '(' + ''.join(jd2(t) for t in args[1:-1].split(',') if t) + ')' + jd2(ret)
            if s and s != name and (name, md) not in seen:
                seen.add((name, md)); out.append(f'\t{name} {md} {s}')
open(os.path.join(os.path.dirname(__file__), 'moj2srg.tsrg'), 'w').write('\n'.join(out) + '\n')
print(len(out))
