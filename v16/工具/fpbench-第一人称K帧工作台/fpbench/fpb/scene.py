"""One first-person frame: the TACZ gun and YSM's first-person arms in view space (blocks, camera looking down -Z).

Arms follow CustomFirstPersonArmRenderer.render (YesSteveModel src client/renderer/CustomFirstPersonArmRenderer.java
:48-62, identical in the deployed jar): optional TaczGrips.place (fitted guns), translate(+-0.25, 1.8, 0),
scale(-1, -1, 1), then the native skinned draw of that arm with the other hidden (FirstPersonModelState.render).
"""
import copy
import json
import math

import numpy as np

from . import tacz_gun, ysm_arm


def palm_shift(hand, left, wrist, palm):
    """TaczGrips.palmShift (client/compat/tacz/TaczGrips.java:87-98)."""
    side = 4.0 if left else -4.0
    w = np.array([side - wrist[0], 28.8 - wrist[1], wrist[2]])
    p = np.array([side - palm[0], 28.8 - palm[1], palm[2]])
    turn = (ysm_arm.rot_z(math.radians(hand[5])) @ ysm_arm.rot_y(math.radians(hand[4]))
            @ ysm_arm.rot_x(math.radians(hand[3])))
    return np.array(hand[:3]) - (turn @ (p - w) + w)


def place_on_gun(H, hand, left, wrist, palm):
    """TaczGrips.place (TaczGrips.java:73-84): undo the stretch along the block arm, slide so the turned palm lands."""
    across = np.linalg.norm(H[:3, 0])
    along = np.linalg.norm(H[:3, 1])
    if along > 1e-6:
        H = H @ tacz_gun.sc(1, across / along, 1)
    s = palm_shift(hand, left, wrist, palm)
    return H @ tacz_gun.tr(*(s / 16))


class Pose:
    """Overrides: arm keyframes (bone -> deg), finger curls, gun part keyframes and the TaczGrips table."""

    def __init__(self, data=None):
        self.bones, self.curls, self.gun, self.grips = {}, {}, {}, None
        if data:
            self.merge(data)

    @classmethod
    def load(cls, path):
        return cls(json.load(open(path)) if path else None)

    def merge(self, d):
        if "animations" in d:  # a Bedrock/YSM animation file (e.g. fpbench ik output): its parallel2 arm bones
            anim = d["animations"].get("parallel2") or next(iter(d["animations"].values()))
            for b, ch in (anim.get("bones") or {}).items():
                if "rotation" in ch:
                    self.bones[b] = [float(v) for v in ch["rotation"]]
        for b, v in (d.get("bones") or {}).items():
            self.bones[b] = [float(x) for x in (v["rotation"] if isinstance(v, dict) else v)]
        for f, v in (d.get("curl") or {}).items():
            self.curls[f] = [float(x) for x in v]
        for p, v in (d.get("gun") or {}).items():
            self.gun[p] = v
        if d.get("grips") is not None:
            self.grips = dict(ysm_arm.TACZ_GRIPS if self.grips is None else self.grips)
            for gid, g in d["grips"].items():
                self.grips[gid] = None if g is None else (tuple(g["left"]), tuple(g["right"]))
            self.grips = {k: v for k, v in self.grips.items() if v is not None}
        return self

    def to_json(self):
        out = {}
        if self.bones:
            out["bones"] = {b: [round(x, 3) for x in v] for b, v in self.bones.items()}
        if self.curls:
            out["curl"] = self.curls
        if self.gun:
            out["gun"] = self.gun
        if self.grips is not None:
            out["grips"] = {k: {"left": list(v[0]), "right": list(v[1])} for k, v in self.grips.items()}
        return out


class Frame:
    """Everything evaluated at time t: matrices, gun geometry, posed arm vertices (view space)."""

    def __init__(self, arm, gun, clip=None, t=0.0, pose=None, idle_t=0.0, sway=True, bob_view=True):
        """bob_view: the 'View Bobbing' option; TACZ applies the camera animation to the hands only when it is on
        (CameraSetupEvent.applyItemInHandCameraAnimation checks options.bobView(), decompiled CameraSetupEvent.java:80;
        vanilla default on, the ~/mc harness forces it off)."""
        self.arm, self.gun, self.clip, self.t = arm, gun, clip, t
        pose = pose or Pose()
        self.pose = pose
        st, cam, touched = gun.pose(clip, t, idle_t=idle_t, sway=sway)
        for part, ch in pose.gun.items():  # gun keyframe overrides: a last non-blend layer
            if part in st:
                if "position" in ch:
                    v = np.array(ch["position"], float) / 16
                    st[part][0] = np.array([v[0], -v[1], v[2]])
                if "rotation" in ch:
                    st[part][1] = tacz_gun.quat_euler(*np.radians(ch["rotation"]))
                if "scale" in ch:
                    st[part][2] = np.array(ch["scale"], float)
                touched.add(part)
        self.cam = cam if bob_view else np.array([0, 0, 0, 1.0])
        self.mats = gun.matrices(st)
        self.view = gun.view_base(self.cam)
        tri, uv, names, boxes, box_names, box_groups = gun.triangles(self.mats, touched)
        self.gun_tris = np.einsum("ij,nkj->nki", self.view[:3, :3], tri) + self.view[:3, 3]
        self.gun_uvs = uv
        self.gun_names = names
        self.gun_boxes = np.einsum("ij,nkj->nki", self.view[:3, :3], boxes) + self.view[:3, 3]
        self.gun_box_names = box_names
        self.gun_box_groups = box_groups
        grips = ysm_arm.TACZ_GRIPS if pose.grips is None else pose.grips
        self.grip = grips.get(gun.id)
        env = ysm_arm.fp_env(gun.id, grips)
        keys = arm.keyframes(env)
        for f, curls in pose.curls.items():  # "RightMiddle": [c1, c2, c3]
            side = "Left" if f.startswith("Left") else "Right"
            finger = f[len(side):]
            axis = arm.curl_axis(side, finger)
            for b, c in zip(arm.chain(side, finger), curls):
                keys[arm.names[b]] = arm.curl_keyframe(b, axis, c)
        for b, v in pose.bones.items():
            keys[b] = np.array(v, float)
        self.keys = keys
        self.bone_world = arm.world(keys)  # arm model space (blocks)
        self.hand = {}
        for left in (True, False):
            side = "Left" if left else "Right"
            H = gun.hand_matrix(self.mats, left)
            if H is None:
                continue
            H = self.view @ H
            if self.grip is not None:
                wrist = arm.pivot[arm.index[side + "Hand"]]
                palm = arm.pivot[arm.index[side + "HandLocator"]]
                H = place_on_gun(H, self.grip[0 if left else 1], left, wrist, palm)
            H = H @ tacz_gun.tr(0.25 if left else -0.25, 1.8, 0) @ tacz_gun.sc(-1, -1, 1)
            self.hand[side] = H
        pos, nrm = arm.skin(self.bone_world)
        self.arm_pos = np.zeros_like(pos)
        self.arm_nrm = np.zeros_like(nrm)
        self.arm_visible = np.zeros(len(pos), bool)
        for side, H in self.hand.items():
            m = arm.vert_side == (1 if side == "Left" else 2)
            self.arm_pos[m] = pos[m] @ H[:3, :3].T + H[:3, 3]
            n = nrm[m] @ np.linalg.inv(H[:3, :3])
            self.arm_nrm[m] = n / np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-9)
            self.arm_visible |= m

    def bone_view(self, name):
        """A bone's pivot in view space."""
        i = self.arm.index[name]
        side = "Left" if self.arm.bone_side[i] == 1 else "Right"
        p = self.bone_world[i] @ np.r_[self.arm.pivot[i] / 16, 1]
        H = self.hand[side]
        return H[:3, :3] @ p[:3] + H[:3, 3]

    def bone_view_matrix(self, name):
        i = self.arm.index[name]
        side = "Left" if self.arm.bone_side[i] == 1 else "Right"
        return self.hand[side] @ self.bone_world[i]


def projection(fov_deg, width, height, near=0.05, far=1024.0):
    """GameRenderer.getProjectionMatrix: Matrix4f.setPerspective(fov, w/h, 0.05, depthFar)."""
    f = 1 / math.tan(math.radians(fov_deg) / 2)
    a = width / height
    P = np.zeros((4, 4))
    P[0, 0], P[1, 1] = f / a, f
    P[2, 2], P[2, 3] = (far + near) / (near - far), 2 * far * near / (near - far)
    P[3, 2] = -1
    return P


def project(points, P, width, height):
    """View-space points (..., 3) -> pixel xy (..., 2), 1/w (...)."""
    h = points @ P[:3, :3].T + P[:3, 3]
    w = points @ P[3, :3] + P[3, 3]
    ndc = h[..., :2] / w[..., None]
    xy = np.stack([(ndc[..., 0] + 1) * 0.5 * width, (1 - ndc[..., 1]) * 0.5 * height], -1)
    return xy, 1.0 / w
