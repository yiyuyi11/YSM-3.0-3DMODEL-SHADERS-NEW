"""Software rasterizer front end: builds raster.c once (cc), falls back to a slow numpy loop without a compiler."""
import ctypes
import hashlib
import os
import subprocess

import numpy as np

_HERE = os.path.dirname(os.path.abspath(__file__))
_LIB = None


def _cache_dir():
    d = os.path.join(os.environ.get("FPBENCH_CACHE", os.path.expanduser("~/.cache/fpbench")))
    os.makedirs(d, exist_ok=True)
    return d


def _lib():
    global _LIB
    if _LIB is not None:
        return _LIB or None
    src = os.path.join(_HERE, "raster.c")
    tag = hashlib.md5(open(src, "rb").read()).hexdigest()[:12]
    so = os.path.join(_cache_dir(), f"raster-{tag}.so")
    if not os.path.exists(so):
        for cc in ("cc", "gcc", "clang"):
            try:
                subprocess.run([cc, "-O3", "-ffast-math", "-shared", "-fPIC", "-o", so + ".tmp", src, "-lm"],
                               check=True, capture_output=True)
                os.replace(so + ".tmp", so)
                break
            except (OSError, subprocess.CalledProcessError):
                continue
    if not os.path.exists(so):
        _LIB = False
        return None
    lib = ctypes.CDLL(so)
    lib.raster.restype = None
    _LIB = lib
    return lib


class Textures:
    """RGBA8 textures packed for the rasterizer; index = order of add()."""

    def __init__(self):
        self.images = []

    def add(self, rgba):
        self.images.append(np.ascontiguousarray(rgba, dtype=np.uint8))
        return len(self.images) - 1

    def packed(self):
        if not self.images:
            return np.zeros(4, np.uint8), np.zeros(1, np.int64), np.ones(1, np.int32), np.ones(1, np.int32)
        offs = np.cumsum([0] + [im.size for im in self.images[:-1]]).astype(np.int64)
        data = np.concatenate([im.reshape(-1) for im in self.images])
        return (data, offs, np.array([im.shape[1] for im in self.images], np.int32),
                np.array([im.shape[0] for im in self.images], np.int32))


class Frame:
    def __init__(self, width, height, background=(0, 0, 0)):
        self.w, self.h = width, height
        self.color = np.empty((height, width, 3), np.uint8)
        self.color[:] = background
        self.depth = np.zeros((height, width), np.float32)
        self.ids = np.full((height, width), -1, np.int32)


def draw(frame, textures, xy, iw, uv, shade, tex, flat=None, idbase=0, cutoff=0.1):
    """Draws triangles: xy (n,3,2) pixels, iw (n,3) 1/view depth, uv (n,3,2), shade (n,3), tex (n,) index or -1."""
    n = len(xy)
    if n == 0:
        return
    xy = np.ascontiguousarray(xy, np.float32)
    iw = np.ascontiguousarray(iw, np.float32)
    uv = np.ascontiguousarray(uv, np.float32)
    shade = np.ascontiguousarray(shade, np.float32)
    tex = np.ascontiguousarray(tex, np.int32)
    flat = np.ascontiguousarray(np.zeros((n, 3)) if flat is None else flat, np.float32)
    data, offs, tw, th = textures.packed()
    lib = _lib()
    if lib is not None:
        p = lambda a: a.ctypes.data_as(ctypes.c_void_p)
        lib.raster(ctypes.c_int(n), p(xy), p(iw), p(uv), p(shade), p(tex), p(flat), p(data), p(offs), p(tw), p(th),
                   ctypes.c_int(frame.w), ctypes.c_int(frame.h), p(frame.color), p(frame.depth), p(frame.ids),
                   ctypes.c_int(idbase), ctypes.c_float(cutoff))
        return
    _draw_numpy(frame, textures, xy, iw, uv, shade, tex, flat, idbase, cutoff)


def _draw_numpy(frame, textures, xy, iw, uv, shade, tex, flat, idbase, cutoff):
    W, H = frame.w, frame.h
    for t in range(len(xy)):
        (x0, y0), (x1, y1), (x2, y2) = xy[t]
        area = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
        if abs(area) < 1e-12 or not (iw[t] > 0).all():
            continue
        xa, xb = max(int(np.floor(min(x0, x1, x2))), 0), min(int(np.ceil(max(x0, x1, x2))), W - 1)
        ya, yb = max(int(np.floor(min(y0, y1, y2))), 0), min(int(np.ceil(max(y0, y1, y2))), H - 1)
        if xa > xb or ya > yb:
            continue
        gx, gy = np.meshgrid(np.arange(xa, xb + 1) + 0.5, np.arange(ya, yb + 1) + 0.5)
        b0 = ((x2 - x1) * (gy - y1) - (y2 - y1) * (gx - x1)) / area
        b1 = ((x0 - x2) * (gy - y2) - (y0 - y2) * (gx - x2)) / area
        b2 = 1 - b0 - b1
        z = b0 * iw[t, 0] + b1 * iw[t, 1] + b2 * iw[t, 2]
        sub = frame.depth[ya:yb + 1, xa:xb + 1]
        m = (b0 >= 0) & (b1 >= 0) & (b2 >= 0) & (z > sub)
        if not m.any():
            continue
        c0, c1, c2 = b0 * iw[t, 0] / z, b1 * iw[t, 1] / z, b2 * iw[t, 2] / z
        light = c0 * shade[t, 0] + c1 * shade[t, 1] + c2 * shade[t, 2]
        if tex[t] >= 0:
            im = textures.images[tex[t]]
            tu = c0 * uv[t, 0, 0] + c1 * uv[t, 1, 0] + c2 * uv[t, 2, 0]
            tv = c0 * uv[t, 0, 1] + c1 * uv[t, 1, 1] + c2 * uv[t, 2, 1]
            ix = np.clip((tu * im.shape[1]).astype(int), 0, im.shape[1] - 1)
            iy = np.clip((tv * im.shape[0]).astype(int), 0, im.shape[0] - 1)
            texel = im[iy, ix].astype(np.float32)
            m &= texel[..., 3] >= cutoff * 255
            rgb = texel[..., :3]
        else:
            rgb = np.broadcast_to(flat[t] * 255, b0.shape + (3,))
        col = np.clip(rgb * light[..., None], 0, 255).astype(np.uint8)
        frame.color[ya:yb + 1, xa:xb + 1][m] = col[m]
        sub[m] = z[m]
        frame.ids[ya:yb + 1, xa:xb + 1][m] = idbase + t
