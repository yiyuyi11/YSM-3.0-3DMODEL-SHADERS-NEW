import sys
sys.argv = ['fk.py', sys.argv[1]]
exec(open('fk.py').read().replace("if __name__ == '__main__':", "if False:"))
for name in ['use_mainhand:bow', 'use_offhand:bow', 'hold_mainhand:charged_crossbow', 'hold_mainhand:crossbow']:
    keys = clips[name]['bones']['RightArm']['rotation']
    times = [0.0] if isinstance(keys, list) else sorted(float(t) for t in keys)
    scan(name, times)
