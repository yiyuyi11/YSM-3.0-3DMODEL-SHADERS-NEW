import sys
import numpy as np
model, name, side = sys.argv[1], sys.argv[2], sys.argv[3]
sys.argv = ['fk.py', model]
exec(open('fk.py').read().replace("if __name__ == '__main__':", "if False:"))
le, re_ = np.array(skel['LeftEye']['pivot']), np.array(skel['RightEye']['pivot'])
eyes, span = (le + re_) / 2, np.linalg.norm(le - re_)
s = 1 if side == 'Right' else -1
anchor = eyes + np.array([s * 1.75 * span, -1.05 * span, 0.25 * span])
clip = clips[name]
for t in map(float, sys.argv[4:] or ['1.0']):
    row = []
    for pitch in range(-60, 91, 5):
        world, point = pose(clip, t, pitch)
        a = (world('Head') @ np.append(anchor, 1))[:3]
        row.append(f'{np.linalg.norm(point(side + "HandLocator") - a):.1f}')
    print(t, ' '.join(row))
