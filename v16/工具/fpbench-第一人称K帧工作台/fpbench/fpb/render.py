"""Images of a Frame: the first-person view, orthographic hand close-ups and timeline contact sheets."""
import math

import numpy as np
from PIL import Image, ImageDraw

from . import raster, scene

LIGHT = np.array([0.2, 0.9, 0.5])
LIGHT /= np.linalg.norm(LIGHT)
SKY = (120, 167, 255)


def _shade(normals):
    """Simple two-sided Lambert, 0.55 ambient (not Minecraft's exact item lighting)."""
    return 0.55 + 0.45 * np.abs(normals @ LIGHT)


def _face_normals(tris):
    n = np.cross(tris[:, 1] - tris[:, 0], tris[:, 2] - tris[:, 0])
    return n / np.maximum(np.linalg.norm(n, axis=-1, keepdims=True), 1e-12)


class Layers:
    """Triangle soup of a frame with ids: [0, ng) gun, then left arm, then right arm."""

    def __init__(self, fr, highlight=None):
        self.fr = fr
        arm = fr.arm
        tri = arm.triangles
        side = arm.vert_side[tri[:, 0]]
        self.ng = len(fr.gun_tris)
        self.left = tri[side == 1]
        self.right = tri[side == 2]
        self.nl, self.nr = len(self.left), len(self.right)
        self.arm_tris = np.concatenate([self.left, self.right])
        self.highlight = highlight  # per arm-vertex bool: tint red (penetrating)

    def draw(self, frame, to_screen, textures):
        """to_screen(points (...,3)) -> (xy, iw)."""
        fr = self.fr
        gtex = textures.add(fr.gun.texture)
        atex = textures.add(fr.arm.atlas)
        gxy, giw = to_screen(fr.gun_tris)
        gshade = np.repeat(_shade(_face_normals(fr.gun_tris))[:, None], 3, 1)
        raster.draw(frame, textures, gxy, giw, fr.gun_uvs, gshade, np.full(self.ng, gtex), idbase=0)
        t = self.arm_tris
        p = fr.arm_pos[t]
        xy, iw = to_screen(p)
        sh = _shade(fr.arm_nrm[t].reshape(-1, 3)).reshape(-1, 3)
        uv = fr.arm.uvs[t]
        tex = np.full(len(t), atex)
        flat = None
        if self.highlight is not None:
            hot = self.highlight[t].any(1)
            tex[hot] = -1
            flat = np.zeros((len(t), 3))
            flat[hot] = (1.0, 0.15, 0.1)
        raster.draw(frame, textures, xy, iw, uv, sh, tex, flat=flat, idbase=self.ng)

    def kind(self, ids):
        """Pixel ids -> 0 none, 1 gun, 2 left arm, 3 right arm."""
        k = np.zeros(ids.shape, np.uint8)
        k[(ids >= 0) & (ids < self.ng)] = 1
        k[(ids >= self.ng) & (ids < self.ng + self.nl)] = 2
        k[ids >= self.ng + self.nl] = 3
        return k


def first_person(fr, width=960, height=540, fov=None, background=SKY, highlight=None):
    """The game camera's first-person image (hand projection: the gun's model_fov unless ``fov`` is given)."""
    fov = fr.gun.model_fov if fov is None else fov
    P = scene.projection(fov, width, height)
    layers = Layers(fr, highlight)
    frame = raster.Frame(width, height, background)
    layers.draw(frame, lambda pts: scene.project(pts, P, width, height), raster.Textures())
    return frame, layers, P


def orthographic(fr, center, right, up, half, size=400, background=(40, 40, 46), highlight=None):
    """Orthographic view of radius ``half`` (blocks) around ``center``, looking along -(right x up)."""
    right = right / np.linalg.norm(right)
    up = up - right * np.dot(up, right)
    up /= np.linalg.norm(up)
    fwd = np.cross(right, up)  # towards the viewer

    def to_screen(pts):
        d = pts - center
        x = d @ right
        y = d @ up
        z = d @ fwd
        xy = np.stack([(x / half + 1) * 0.5 * size, (1 - y / half) * 0.5 * size], -1)
        return xy, 1.0 / np.maximum(10 * half - z, 1e-6)  # nearer = larger
    layers = Layers(fr, highlight)
    frame = raster.Frame(size, size, background)
    layers.draw(frame, to_screen, raster.Textures())
    return frame, to_screen


def hand_closeups(fr, metrics=None, size=360, half_px=5.0, highlight=None):
    """Per hand: view from the camera and from the side (90 deg about view up), orthographic, centred on the palm."""
    tiles = []
    for side in ("Left", "Right"):
        if side not in fr.hand:
            continue
        c = fr.bone_view(side + "HandLocator")
        view_dir = -c / np.linalg.norm(c)  # from the hand towards the camera
        up = np.array([0, 1.0, 0])
        right = np.cross(up, view_dir)
        right /= np.linalg.norm(right)
        for label, (r, u) in (("camera", (right, up)), ("side", (-view_dir if side == "Right" else view_dir, up))):
            frame, _ = orthographic(fr, c, r, u, half_px / 16, size, highlight=highlight)
            im = Image.fromarray(frame.color)
            d = ImageDraw.Draw(im)
            d.text((6, 4), f"{side} hand, {label} view, {2 * half_px:.0f} px wide", fill=(255, 255, 255))
            if metrics:
                y = 18
                for fname, fm in metrics["hands"].get(side, {}).get("fingers", {}).items():
                    col = (255, 90, 80) if fm["inside_vertices"] else (200, 230, 200)
                    d.text((6, y), f"{fname[:6]:6s} pen {fm['penetration_px']:.2f} gap {fm['gap_px']:.2f}", fill=col)
                    y += 12
            tiles.append(im)
    if not tiles:
        return Image.new("RGB", (size, size))
    cols = 2
    rows = (len(tiles) + 1) // 2
    sheet = Image.new("RGB", (cols * size, rows * size), (20, 20, 20))
    for i, im in enumerate(tiles):
        sheet.paste(im, ((i % cols) * size, (i // cols) * size))
    return sheet


def contact_sheet(images, labels, cols=4, title=None):
    w, h = images[0].size
    rows = (len(images) + cols - 1) // cols
    top = 22 if title else 0
    sheet = Image.new("RGB", (cols * w, rows * h + top), (16, 16, 16))
    d = ImageDraw.Draw(sheet)
    if title:
        d.text((6, 5), title, fill=(255, 255, 255))
    for i, (im, lab) in enumerate(zip(images, labels)):
        x, y = (i % cols) * w, (i // cols) * h + top
        sheet.paste(im, (x, y))
        dd = ImageDraw.Draw(sheet)
        dd.rectangle([x, y, x + w - 1, y + 14], fill=(0, 0, 0))
        dd.text((x + 4, y + 2), lab, fill=(255, 255, 0))
    return sheet
