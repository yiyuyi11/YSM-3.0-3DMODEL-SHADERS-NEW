#!/usr/bin/env python3
"""Retarget Endfield's baked locomotion mount points onto the skeleton YSM builds for an imported humanoid.

The *_MPData.bin loops store 13 attachment points per frame, not bones. For the 'girl' body type (Pelica's) they sit on
the same skeleton proportions as Pelica: at idle the foot mounts are at ankle height (0.155 m), the head mount at the
head joint (1.47 m) and the chest mount at Bip001_Spine2 (1.19 m). Channels: 0/11 root, 1/2/3/10 head, 4 left ankle,
5 right ankle, 6 left hand, 7 right hand, 8/12 chest (Spine2), 9 right-hand weapon.

Per frame:
- trunk: the chest mount's orientation (relative to idle) turns UpBody; Root moves so Spine2 lands on the chest mount;
- legs: two-bone IK from the hips to the ankle mounts, knees turned with the foot's heading; the foot keeps the
  mount's orientation;
- arms: the hand's displacement from its idle place, measured in the chest's frame, moves the wrist target of a
  two-bone IK whose elbow points back;
- head: the head mount's orientation relative to the trunk.

Rotations become keyframes exactly as YSM's TaczRetarget does: local = parent^-1 * world (model space: pixels, facing
-Z, left at -X), keyframe = degrees of getEulerAnglesZYX(local) - rest, with x and y negated; positions negate x.

Usage: retarget.py <bones.json> <clip.bin> <seconds per cycle> [--reverse] > clip.json
"""

import json
import math
import sys

import numpy as np

import mpdata

PX_PER_M = 20.0 / 0.946  # YSM puts the hips at 20 px; Pelica's hips are 0.946 m up
HEAD, LEFT_FOOT, RIGHT_FOOT, LEFT_HAND, RIGHT_HAND, CHEST = 1, 4, 5, 6, 7, 8
PALM = 1.1  # px from the palm mount up to the wrist pivot


def to_ysm(position, quaternion):
    """Unity (left-handed, faces +Z, left -X) to YSM model space (right-handed, faces -Z, left -X): mirror z."""
    p = position * np.array([1.0, 1.0, -1.0]) * PX_PER_M
    q = quaternion * np.array([-1.0, -1.0, 1.0, 1.0])
    return p, q


def quat_matrix(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def euler_zyx(m):
    """JOML Matrix3f.getEulerAnglesZYX: m = Rz(z) * Ry(y) * Rx(x)."""
    return np.array([math.atan2(m[2, 1], m[2, 2]), math.atan2(-m[2, 0], math.sqrt(max(0.0, 1 - m[2, 0] ** 2))),
                     math.atan2(m[1, 0], m[0, 0])])


def unwrap(euler, previous):
    """Of the ZYX angle sets equal to euler (2*pi shifts and the (x+pi, pi-y, z+pi) twin), the one nearest previous:
    keyframes are interpolated per component, so neighbours must not straddle a wrap."""
    if previous is None:
        return euler
    twin = np.array([euler[0] + math.pi, math.pi - euler[1], euler[2] + math.pi])
    best, distance = euler, float("inf")
    for base in (euler, twin):
        candidate = base + 2 * math.pi * np.round((previous - base) / (2 * math.pi))
        d = np.abs(candidate - previous).sum()
        if d < distance:
            best, distance = candidate, d
    return best


def axis_rotation(axis, angle):
    axis = axis / np.linalg.norm(axis)
    x, y, z = axis
    c, s = math.cos(angle), math.sin(angle)
    return np.array([[c + x * x * (1 - c), x * y * (1 - c) - z * s, x * z * (1 - c) + y * s],
                     [y * x * (1 - c) + z * s, c + y * y * (1 - c), y * z * (1 - c) - x * s],
                     [z * x * (1 - c) - y * s, z * y * (1 - c) + x * s, c + z * z * (1 - c)]])


def yaw_of(m):
    forward = m @ np.array([0.0, 0.0, -1.0])
    return math.atan2(-forward[0], -forward[2])


def swing_pole(rest_reach, reach, rest_pole):
    """rest_pole carried by the shortest rotation from rest_reach to reach: always across the reach, never along it."""
    a = rest_reach / np.linalg.norm(rest_reach)
    b = reach / np.linalg.norm(reach)
    axis = np.cross(a, b)
    if np.linalg.norm(axis) < 1e-9:
        return rest_pole if a @ b > 0 else -rest_pole
    return axis_rotation(axis, math.acos(max(-1.0, min(1.0, a @ b)))) @ rest_pole


def frame(hinge, along):
    """Orthonormal frame: x = hinge, y = -along (the limb points down its -y), z = x * y."""
    y = -along / np.linalg.norm(along)
    x = hinge - y * (hinge @ y)
    x /= np.linalg.norm(x)
    return np.column_stack([x, y, np.cross(x, y)])


def two_bone(root, target, upper, lower, pole):
    """Middle joint of a two-bone chain reaching from root toward target, bending toward pole."""
    reach = target - root
    distance = min(np.linalg.norm(reach), (upper + lower) * 0.9995)
    u = reach / np.linalg.norm(reach)
    a = (upper * upper - lower * lower + distance * distance) / (2 * distance)
    r = math.sqrt(max(0.0, upper * upper - a * a))
    v = pole - u * (pole @ u)
    v /= np.linalg.norm(v)
    return root + a * u + r * v, root + distance * u


class Skeleton:
    def __init__(self, path):
        self.bones = {b["name"]: b for b in json.load(open(path))}
        self.previous = {}

    def pivot(self, name):
        return np.array(self.bones[name]["pivot"], dtype=np.float64)

    def rest(self, name):
        return np.array(self.bones[name]["rotate"], dtype=np.float64)

    def keyframe(self, name, local):
        euler = unwrap(euler_zyx(local), self.previous.get(name))
        self.previous[name] = euler
        euler = euler - self.rest(name)
        degrees = np.degrees(euler)
        return [round(-degrees[0], 2), round(-degrees[1], 2), round(degrees[2], 2)]


def limb(skeleton, upper, lower, end, hinge0, root, target, pole, parent):
    """World rotations of a two-bone limb (relative to the zero frame) and their keyframes."""
    p_upper, p_lower, p_end = skeleton.pivot(upper), skeleton.pivot(lower), skeleton.pivot(end)
    length_upper = np.linalg.norm(p_lower - p_upper)
    length_lower = np.linalg.norm(p_end - p_lower)
    middle, reached = two_bone(root, target, length_upper, length_lower, pole)
    along_upper, along_lower = middle - root, reached - middle
    # The bend's axis; a straight limb takes pole x upper, which has the same sense for knees and elbows.
    hinge = np.cross(along_upper, along_lower)
    if np.linalg.norm(hinge) < 1e-4 * length_upper * length_lower:
        hinge = np.cross(pole, along_upper)
    hinge /= np.linalg.norm(hinge)
    world_upper = frame(hinge, along_upper) @ frame(hinge0, p_lower - p_upper).T
    world_lower = frame(hinge, along_lower) @ frame(hinge0, p_end - p_lower).T
    keys = {upper: skeleton.keyframe(upper, parent.T @ world_upper),
            lower: skeleton.keyframe(lower, world_upper.T @ world_lower)}
    return keys, world_lower


def report(tracks):
    """Largest keyframe-to-keyframe jump per bone (wrapping): big jumps mean Euler flips that interpolate badly."""
    worst = []
    for bone, channels in tracks.items():
        keys = np.array(list(channels.get("rotation", {}).values()) or [[0, 0, 0]])
        jump = np.abs(np.diff(np.vstack([keys, keys[:1]]), axis=0)).max()
        worst.append((jump, bone))
    worst.sort(reverse=True)
    print("largest per-key jumps: " + ", ".join(f"{b} {j:.0f}" for j, b in worst[:5]), file=sys.stderr)


def retarget(bones_path, clip_path, seconds, reverse=False):
    skeleton = Skeleton(bones_path)
    positions, quaternions = mpdata.loop(clip_path)
    idle_positions, idle_quaternions, _ = mpdata.read(clip_path.replace(clip_path.split("_")[-3], "idle"))
    frames = len(positions)
    p = np.empty_like(positions)
    q = np.empty_like(quaternions)
    for f in range(frames):
        for c in range(positions.shape[1]):
            p[f, c], q[f, c] = to_ysm(positions[f, c], quaternions[f, c])
    idle_p = np.array([to_ysm(idle_positions[0, c], idle_quaternions[0, c])[0] for c in range(positions.shape[1])])
    idle_q = np.array([to_ysm(idle_positions[0, c], idle_quaternions[0, c])[1] for c in range(positions.shape[1])])

    def delta(f, c):
        return quat_matrix(q[f, c]) @ quat_matrix(idle_q[c]).T

    up_pivot = skeleton.pivot("UpBody")
    spine2 = skeleton.pivot("Bip001_Spine2")
    down_pivot = skeleton.pivot("DownBody")
    tracks = {}
    order = range(frames - 1, -1, -1) if reverse else range(frames)
    for k, f in enumerate(order):
        time = f"{seconds * k / frames:.4f}"
        chest = delta(f, CHEST)
        trunk_yaw = yaw_of(chest)
        # Pelvis: counter-turns a little against the chest and keeps upright; the legs' IK absorbs the rest.
        pelvis = axis_rotation(np.array([0.0, 1.0, 0.0]), -0.35 * trunk_yaw)
        root = p[f, CHEST] - (up_pivot + chest @ (spine2 - up_pivot))
        hips = {side: down_pivot + pelvis @ (skeleton.pivot(side + "Leg") - down_pivot) for side in ("Left", "Right")}
        # Lower the body if a planted foot could not be reached.
        for side, channel in (("Left", LEFT_FOOT), ("Right", RIGHT_FOOT)):
            reach = (np.linalg.norm(skeleton.pivot(side + "LowerLeg") - skeleton.pivot(side + "Leg"))
                     + np.linalg.norm(skeleton.pivot(side + "Foot") - skeleton.pivot(side + "LowerLeg"))) * 0.995
            gap = p[f, channel] - (root + hips[side])
            if np.linalg.norm(gap) > reach:
                horizontal = math.hypot(gap[0], gap[2])
                root[1] -= -gap[1] - math.sqrt(max(0.0, reach * reach - horizontal * horizontal))
        add = lambda bone, channel, value: tracks.setdefault(bone, {}).setdefault(channel, {}).__setitem__(time, value)
        add("Root", "position", [round(-root[0], 3), round(root[1], 3), round(root[2], 3)])
        add("DownBody", "rotation", skeleton.keyframe("DownBody", pelvis))
        add("UpBody", "rotation", skeleton.keyframe("UpBody", chest))
        for side, foot_channel in (("Left", LEFT_FOOT), ("Right", RIGHT_FOOT)):
            foot = delta(f, foot_channel)
            # Knees bend forward of the leg's swing (the foot's own heading flips mid-swing, so it is not used).
            outward = -1.0 if side == "Left" else 1.0
            hip = root + hips[side]
            pole = pelvis @ swing_pole(np.array([0.0, -1.0, 0.0]), pelvis.T @ (p[f, foot_channel] - hip),
                                       np.array([0.1 * outward, 0.0, -1.0]))
            keys, shin = limb(skeleton, side + "Leg", side + "LowerLeg", side + "Foot", np.array([-1.0, 0, 0]),
                              root + hips[side], p[f, foot_channel], pole, pelvis)
            for bone, value in keys.items():
                add(bone, "rotation", value)
            add(side + "Foot", "rotation", skeleton.keyframe(side + "Foot", shin.T @ foot))
        for side, hand_channel in (("Left", LEFT_HAND), ("Right", RIGHT_HAND)):
            # The mount sits on the palm, PALM px below the wrist along the hand; the source skeleton has Pelica's
            # proportions, so its hand position is used as it is.
            hand = delta(f, hand_channel)
            shoulder = root + up_pivot + chest @ (skeleton.pivot(side + "Arm") - up_pivot)
            wrist = p[f, hand_channel] + hand @ np.array([0.0, PALM, 0.0])
            outward = -1.0 if side == "Left" else 1.0
            # Elbows bend behind the arm's swing: down when it reaches forward, up when it swings back.
            pole = chest @ swing_pole(np.array([0.0, -1.0, 0.0]), chest.T @ (wrist - shoulder),
                                      np.array([0.25 * outward, 0.0, 1.0]))
            keys, forearm = limb(skeleton, side + "Arm", side + "ForeArm", side + "Hand", np.array([1.0, 0, 0]),
                                 shoulder, wrist, pole, chest)
            for bone, value in keys.items():
                add(bone, "rotation", value)
            add(side + "Hand", "rotation", skeleton.keyframe(side + "Hand", forearm.T @ hand))
        add("Head", "rotation", skeleton.keyframe("Head", chest.T @ delta(f, HEAD)))
    report(tracks)
    # Close the loop on the first frame so playback wraps without a jump.
    closing = f"{seconds:.4f}"
    for bone, channels in tracks.items():
        for channel, keys in channels.items():
            keys[closing] = next(iter(keys.values()))
    return {"loop": True, "animation_length": round(seconds, 4), "bones": tracks}


if __name__ == "__main__":
    if len(sys.argv) < 4:
        raise SystemExit(__doc__.splitlines()[-1])
    clip = retarget(sys.argv[1], sys.argv[2], float(sys.argv[3]), "--reverse" in sys.argv)
    json.dump(clip, sys.stdout, separators=(",", ":"))
