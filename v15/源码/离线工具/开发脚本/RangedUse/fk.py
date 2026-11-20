"""Evaluates generated clips offline: forward kinematics on the dumped skeleton at a time and head pitch/yaw.

usage: python3 fk.py <model> <clip> [times...]   prints palms, elbows, hand axes per pitch.
"""
import json
import math
import re
import sys

import numpy as np

model = sys.argv[1]
skel = {b['name']: b for b in json.load(open(f'{model}.skel.json'))}
clips = json.load(open(f'{model}.main.json'))['animations']


def rot(x, y, z):
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return rz @ ry @ rx


def evaluate(expr, pitch, yaw, speed=0.0, carry=False):
    if isinstance(expr, (int, float)):
        return float(expr)
    e = expr
    # carry conditions: "(<cond> ? v : 0)" with nested parens; replace the whole conditional
    while '? ' in e:
        i = e.index('? ')
        # find start of the condition: the '(' that opens the group containing '?'
        depth = 0
        j = i - 1
        while j >= 0:
            if e[j] == ')':
                depth += 1
            elif e[j] == '(':
                if depth == 0:
                    break
                depth -= 1
            j -= 1
        k = e.index(':', i)
        m = e.index(')', k)
        value = e[i + 2:k].strip()
        e = e[:j] + ('(' + value + ')' if carry else '0') + e[m + 1:]
    e = e.replace('math.max', 'max').replace('math.clamp', 'clamp').replace('ysm.head_pitch', f'({pitch})')
    e = e.replace('ysm.head_yaw', f'({yaw})').replace('query.ground_speed', f'({speed})')
    return eval(e, {'max': max, 'clamp': lambda v, a, b: min(max(v, a), b)})


def channel(clip, bone, time, pitch, yaw):
    c = clip['bones'].get(bone, {}).get('rotation')
    if c is None:
        return [0, 0, 0]
    if isinstance(c, list):
        return [evaluate(v, pitch, yaw) for v in c]
    keys = sorted((float(t), v) for t, v in c.items())
    if time <= keys[0][0]:
        return [evaluate(v, pitch, yaw) for v in keys[0][1]]
    for (t0, v0), (t1, v1) in zip(keys, keys[1:]):
        if t0 <= time <= t1:
            s = (time - t0) / (t1 - t0)
            a = [evaluate(v, pitch, yaw) for v in v0]
            b = [evaluate(v, pitch, yaw) for v in v1]
            return [x + (y - x) * s for x, y in zip(a, b)]
    return [evaluate(v, pitch, yaw) for v in keys[-1][1]]


def pose(clip, time, pitch, yaw=0.0):
    worlds = {}

    def world(name):
        if name in worlds:
            return worlds[name]
        b = skel[name]
        parent = world(b['parent']) if b['parent'] else np.eye(4)
        k = channel(clip, name, time, pitch, yaw)
        rest = b['rotate']
        x = rest[0] + math.radians(-k[0])
        y = rest[1] + math.radians(-k[1])
        z = rest[2] + math.radians(k[2])
        if name == 'Head':
            x += math.radians(pitch)
            y += math.radians(yaw)
        p = np.array(b['pivot'])
        m = np.eye(4)
        m[:3, :3] = rot(x, y, z)
        m[:3, 3] = p - m[:3, :3] @ p
        w = parent @ m
        worlds[name] = w
        return w

    def point(name):
        return (world(name) @ np.append(skel[name]['pivot'], 1))[:3]

    return world, point


if __name__ == '__main__':
    clip = clips[sys.argv[2]]
    times = [float(t) for t in sys.argv[3:]] or [0.0]
    np.set_printoptions(precision=2, suppress=True)
    for time in times:
        for pitch in (-60, -45, -30, 0, 15, 30, 60, 90):
            world, point = pose(clip, time, pitch)
            out = []
            for side in ('Right', 'Left'):
                s, e, p = point(side + 'Arm'), point(side + 'ForeArm'), point(side + 'HandLocator')
                hand = world(side + 'HandLocator')[:3, :3]
                out.append(f"{side[0]} palm {p} elbow {e} down {hand @ [0, -1, 0]} front {hand @ [0, 0, -1]}")
            head = world('Head')[:3, :3] @ [0, 0, -1]
            print(f't={time:.2f} p={pitch:4d} look {head} | ' + ' | '.join(out))


def scan(name, times):
    clip = clips[name]
    worst = 0
    for time in times:
        prev = None
        for pitch in range(-90, 91, 5):
            world, point = pose(clip, time, pitch)
            cur = np.concatenate([point(s + n) for s in ('Right', 'Left') for n in ('ForeArm', 'HandLocator')])
            if prev is not None:
                jump = np.abs(cur - prev).max()
                if jump > 1.2:
                    print(f'  {name} t={time:.2f} jump {jump:.2f} at pitch {pitch}')
                worst = max(worst, jump)
            prev = cur
    print(f'{name}: worst 5-degree step {worst:.2f} px')
