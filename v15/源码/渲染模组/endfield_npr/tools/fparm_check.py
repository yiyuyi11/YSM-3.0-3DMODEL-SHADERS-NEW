#!/usr/bin/env python3
"""Offline check of the first-person arms' transform chain (FpArms, NprRenderer.drawArms) on Mesa llvmpipe (EGL
surfaceless, no window, no Minecraft):

1. The hand pass's matrices as Minecraft 1.20.1 and Oculus 1.7 build them: GameRenderer.getProjectionMatrix =
   JOML setPerspective(fov, aspect, 0.05, far) with fov = getFov(camera, pt, false) (70, or TACZ's item model FOV),
   Oculus HandRenderer.setupGlState = scale(1, 1, 0.125) x that; model-view identity. Synthetic arm vertices go through
   a pose chain like vanilla's renderHandsWithItems + renderPlayerArm and YSM's CustomFirstPersonArmRenderer
   (translate(-0.25, 1.8, 0), scale(-1, -1, 1)); NativeRenderer writes pose x vertex (camera-relative hand view space).
2. npr_character.vsh as shipped (imports inlined), through transform feedback, with what drawArms sets (ProjMat and
   ModelViewMat = the captured hand matrices, record model = identity): gl_Position must equal ProjMat x ModelViewMat x
   position, the clip position YSM's own draw of the same vertices gets (same pixel, same depth).
3. clothDepthBias under the scaled projection: same pixel, nearer.
4. npr_outline.vsh: the shell's screen shift against the analytic formula and NprRenderer.armsRect's margin; its depth
   is that of the point 6 cm (+ offsetZ x 0.1) farther along the ray under both projections.
5. Window depths: arm vs held item vs world.

  python3 tools/fparm_check.py
"""
import ctypes
import math
import os
import re
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, 'rain'))
from rain_check import GL  # noqa: E402

ROOT = os.path.join(HERE, '..', 'src/main/resources/assets/endfield_npr/shaders')
W, H = 1920, 1080
FAR = 12 * 16 * 4.0  # getDepthFar at 12 chunks
NEAR = 0.05
failures = []


def check(ok, what):
    print(('ok    ' if ok else 'FAIL  ') + what)
    if not ok:
        failures.append(what)


# ---------------------------------------------------------------- matrices (math convention: M @ column vector)
def perspective(fov_deg, aspect, near=NEAR, far=FAR):
    """JOML Matrix4f.setPerspective (zZeroToOne false), as GameRenderer.getProjectionMatrix uses it."""
    h = math.tan(math.radians(fov_deg) * 0.5)
    m = np.zeros((4, 4))
    m[0, 0] = 1.0 / (h * aspect)
    m[1, 1] = 1.0 / h
    m[2, 2] = (far + near) / (near - far)
    m[2, 3] = 2.0 * far * near / (near - far)
    m[3, 2] = -1.0
    return m


def scale(x, y, z):
    return np.diag([x, y, z, 1.0])


def translate(x, y, z):
    m = np.eye(4)
    m[:3, 3] = (x, y, z)
    return m


def rot(axis, deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    m = np.eye(4)
    i, j = {'x': (1, 2), 'y': (2, 0), 'z': (0, 1)}[axis]
    m[i, i], m[i, j], m[j, i], m[j, j] = c, -s, s, c
    return m


def hand_pose(right, swing=0.0, equip=0.0, bob=0.3):
    """PoseStack of one arm in the hand pass (post-multiplied like PoseStack.translate / mulPose / scale)."""
    p = np.eye(4)  # renderItemInHand: setIdentity
    # bobView (walk phase 0.3, bob 0.6): translate, Z then X tilt
    f1, f2 = bob, 0.6 * 0.4
    p = p @ translate(math.sin(f1 * math.pi) * f2 * 0.5, -abs(math.cos(f1 * math.pi) * f2), 0)
    p = p @ rot('z', math.sin(f1 * math.pi) * f2 * 3.0) @ rot('x', abs(math.cos(f1 * math.pi - 0.2) * f2) * 5.0)
    p = p @ rot('x', 1.5) @ rot('y', -2.0)  # renderHandsWithItems: the hand's lag behind a turning camera
    f = 1.0 if right else -1.0  # ItemInHandRenderer.renderPlayerArm
    s1 = math.sqrt(swing)
    p = p @ translate(f * (-0.3 * math.sin(s1 * math.pi) + 0.64000005), 0.4 * math.sin(s1 * 2 * math.pi) - 0.6
                      - 0.6 * equip, -0.4 * math.sin(swing * math.pi) - 0.71999997)
    p = p @ rot('y', f * 45.0) @ rot('y', f * math.sin(s1 * math.pi) * 70.0)
    p = p @ rot('z', f * math.sin(swing * swing * math.pi) * -20.0)
    p = p @ translate(f * -1.0, 3.6, 3.5) @ rot('z', f * 120.0) @ rot('x', 200.0) @ rot('y', f * -135.0)
    p = p @ translate(f * 5.6, 0.0, 0.0)
    # YSM CustomFirstPersonArmRenderer: the arm model in its own space (feet at 0, blocks)
    p = p @ translate(0.25 if not right else -0.25, 1.8, 0.0) @ scale(-1, -1, 1)
    return p


def arm_points(right):
    """
    A synthetic arm in YSM model space (blocks) that lands where vanilla's arm cube lies in the arm frame of
    renderPlayerArm (PlayerModel rightArm: pivot (-5, 2, 0) px, box (-3, -2, -2) + (4, 12, 4) px; leftArm mirrored),
    mapped back through YSM's translate(-+0.25, 1.8, 0) scale(-1, -1, 1): frame f = (t - m.x, 1.8 - m.y, m.z).
    """
    t = -0.25 if right else 0.25
    xs = (-0.5, -0.25) if right else (0.25, 0.5)
    pts = []
    for fy in np.linspace(0.0, 0.75, 9):
        for fx in xs:
            for fz in (-0.125, 0.125):
                pts.append((t - fx, 1.8 - fy, fz))
    return np.array(pts)


def to_view(pose, pts):
    hom = np.c_[pts, np.ones(len(pts))]
    return (pose @ hom.T).T[:, :3]


def window(clip):
    ndc = clip[:, :3] / clip[:, 3:4]
    return np.c_[(ndc[:, 0] * 0.5 + 0.5) * W, (ndc[:, 1] * 0.5 + 0.5) * H, ndc[:, 2] * 0.5 + 0.5]


# ---------------------------------------------------------------- GL
def inline(path):
    seen = set()

    def expand(p):
        out = []
        for line in open(p):
            m = re.match(r'\s*#moj_import\s*<endfield_npr:([^>]+)>', line)
            if not m:
                out.append(line)
                continue
            if m.group(1) not in seen:
                seen.add(m.group(1))
                out.extend(expand(os.path.join(ROOT, 'include', m.group(1))))
        return out
    return ''.join(expand(path))


class Runner:
    def __init__(self):
        self.gl = GL()
        g, u, i, p, z, f = self.gl, ctypes.c_uint, ctypes.c_int, ctypes.c_void_p, ctypes.c_ssize_t, ctypes.c_float
        fn = g.fn
        self.create_shader = fn('glCreateShader', u, u)
        self.shader_source = fn('glShaderSource', None, u, i, ctypes.POINTER(ctypes.c_char_p), p)
        self.compile = fn('glCompileShader', None, u)
        self.shader_iv = fn('glGetShaderiv', None, u, u, ctypes.POINTER(i))
        self.shader_log = fn('glGetShaderInfoLog', None, u, i, p, ctypes.c_char_p)
        self.create_program = fn('glCreateProgram', u)
        self.attach = fn('glAttachShader', None, u, u)
        self.bind_attrib = fn('glBindAttribLocation', None, u, u, ctypes.c_char_p)
        self.tf_varyings = fn('glTransformFeedbackVaryings', None, u, i, ctypes.POINTER(ctypes.c_char_p), u)
        self.link = fn('glLinkProgram', None, u)
        self.program_iv = fn('glGetProgramiv', None, u, u, ctypes.POINTER(i))
        self.program_log = fn('glGetProgramInfoLog', None, u, i, p, ctypes.c_char_p)
        self.use = fn('glUseProgram', None, u)
        self.uloc = fn('glGetUniformLocation', i, u, ctypes.c_char_p)
        self.umat4 = fn('glUniformMatrix4fv', None, i, i, ctypes.c_ubyte, p)
        self.u4f = fn('glUniform4f', None, i, f, f, f, f)
        self.u2f = fn('glUniform2f', None, i, f, f)
        self.u1f = fn('glUniform1f', None, i, f)
        self.u1i = fn('glUniform1i', None, i, i)
        self.block_index = fn('glGetUniformBlockIndex', u, u, ctypes.c_char_p)
        self.block_binding = fn('glUniformBlockBinding', None, u, u, u)
        self.gen_buffers = fn('glGenBuffers', None, i, ctypes.POINTER(u))
        self.bind_buffer = fn('glBindBuffer', None, u, u)
        self.buffer_data = fn('glBufferData', None, u, z, p, u)
        self.bind_base = fn('glBindBufferBase', None, u, u, u)
        self.get_sub = fn('glGetBufferSubData', None, u, z, z, p)
        self.gen_vao = fn('glGenVertexArrays', None, i, ctypes.POINTER(u))
        self.bind_vao = fn('glBindVertexArray', None, u)
        self.enable_attrib = fn('glEnableVertexAttribArray', None, u)
        self.attrib_ptr = fn('glVertexAttribPointer', None, u, i, u, ctypes.c_ubyte, i, p)
        self.attrib1f = fn('glVertexAttrib1f', None, u, f)
        self.attrib4f = fn('glVertexAttrib4f', None, u, f, f, f, f)
        self.attribI2i = fn('glVertexAttribI2i', None, u, i, i)
        self.attrib3f = fn('glVertexAttrib3f', None, u, f, f, f)
        self.enable = fn('glEnable', None, u)
        self.begin_tf = fn('glBeginTransformFeedback', None, u)
        self.end_tf = fn('glEndTransformFeedback', None)
        self.draw_arrays = fn('glDrawArrays', None, u, i, i)
        self.finish = fn('glFinish', None)
        self.gen_textures = fn('glGenTextures', None, i, ctypes.POINTER(u))
        self.bind_texture = fn('glBindTexture', None, u, u)
        # surfaceless EGL has no default framebuffer: draws need a complete one even with rasterizer discard
        fbo, rbo = u(), u()
        fn('glGenFramebuffers', None, i, ctypes.POINTER(u))(1, ctypes.byref(fbo))
        fn('glGenRenderbuffers', None, i, ctypes.POINTER(u))(1, ctypes.byref(rbo))
        fn('glBindRenderbuffer', None, u, u)(0x8D41, rbo)
        fn('glRenderbufferStorage', None, u, u, i, i)(0x8D41, 0x8058, 4, 4)  # RGBA8
        fn('glBindFramebuffer', None, u, u)(0x8D40, fbo)
        fn('glFramebufferRenderbuffer', None, u, u, u, u)(0x8D40, 0x8CE0, 0x8D41, rbo)
        self.tex_image = fn('glTexImage2D', None, u, i, i, i, i, i, u, u, p)
        self.tex_param = fn('glTexParameteri', None, u, u, i)
        self.active_texture = fn('glActiveTexture', None, u)
        vao = u()
        self.gen_vao(1, ctypes.byref(vao))
        self.bind_vao(vao)
        bufs = (u * 4)()
        self.gen_buffers(4, bufs)
        self.vbo, self.tfo, self.ubo_draw, self.ubo_rain = bufs
        # class map texel for clothDepthBias / the outline classes: 1x1, blue = 10 (cloth)
        tex = u()
        self.gen_textures(1, ctypes.byref(tex))
        self.active_texture(0x84C0 + 3)
        self.bind_texture(0x0DE1, tex)
        texel = (ctypes.c_ubyte * 4)(200, 200, 10, 255)
        self.tex_image(0x0DE1, 0, 0x8058, 1, 1, 0, 0x1908, 0x1401, texel)  # RGBA8
        self.tex_param(0x0DE1, 0x2801, 0x2600)  # GL_NEAREST min
        self.tex_param(0x0DE1, 0x2800, 0x2600)
        self.active_texture(0x84C0)
        self.programs = {}

    def program(self, vsh):
        if vsh in self.programs:
            return self.programs[vsh]
        i = ctypes.c_int
        sh = self.create_shader(0x8B31)
        src = ctypes.c_char_p(inline(os.path.join(ROOT, 'core', vsh)).encode())
        self.shader_source(sh, 1, ctypes.byref(src), None)
        self.compile(sh)
        ok = i()
        self.shader_iv(sh, 0x8B81, ctypes.byref(ok))
        if not ok.value:
            log = ctypes.create_string_buffer(65536)
            self.shader_log(sh, 65536, None, log)
            raise RuntimeError(vsh + ': ' + log.value.decode())
        prog = self.create_program()
        self.attach(prog, sh)
        for k, a in enumerate(('Position', 'Color', 'UV0', 'UV1', 'UV2', 'Normal', 'NprDrawIndex')):
            self.bind_attrib(prog, k, a.encode())
        names = (ctypes.c_char_p * 2)(b'gl_Position', b'viewPos')
        self.tf_varyings(prog, 2, names, 0x8C8C)  # GL_INTERLEAVED_ATTRIBS
        self.link(prog)
        self.program_iv(prog, 0x8B82, ctypes.byref(ok))
        if not ok.value:
            log = ctypes.create_string_buffer(65536)
            self.program_log(prog, 65536, None, log)
            raise RuntimeError(vsh + ' link: ' + log.value.decode())
        for block, binding in ((b'NprDrawBlock', 13), (b'NprRainBlock', 14)):
            idx = self.block_index(prog, block)
            if idx != 0xFFFFFFFF:
                self.block_binding(prog, idx, binding)
        self.programs[vsh] = prog
        return prog

    def run(self, vsh, pos, normals, proj, model_view, uniforms):
        prog = self.program(vsh)
        self.use(prog)
        for name, m in (('ProjMat', proj), ('ModelViewMat', model_view)):
            data = np.ascontiguousarray(m.T, dtype=np.float32)  # GLSL column-major
            self.umat4(self.uloc(prog, name.encode()), 1, 0, data.ctypes.data)
        self.u1i(self.uloc(prog, b'Sampler3'), 3)
        for name, v in uniforms.items():
            loc = self.uloc(prog, name.encode())
            if loc < 0:
                continue
            v = np.atleast_1d(np.asarray(v, dtype=np.float64))
            if len(v) == 4:
                self.u4f(loc, *map(float, v))
            elif len(v) == 2:
                self.u2f(loc, *map(float, v))
            else:
                self.u1f(loc, float(v[0]))
        # NprDrawBlock record 0: model identity, head basis, centre (w = exposure); NprRainBlock record 0: zeros
        record = np.zeros(128 * 32, dtype=np.float32)
        record[0:16] = np.eye(4, dtype=np.float32).T.flatten()
        record[16:32] = (1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1.3)
        rain = np.zeros(128 * 20, dtype=np.float32)
        rain[12:16] = (0, 0, 0, -1000.0)
        for buf, data, binding in ((self.ubo_draw, record, 13), (self.ubo_rain, rain, 14)):
            self.bind_buffer(0x8A11, buf)
            self.buffer_data(0x8A11, data.nbytes, data.ctypes.data, 0x88E4)
            self.bind_base(0x8A11, binding, buf)
        n = len(pos)
        verts = np.zeros((n, 8), dtype=np.float32)
        verts[:, 0:3] = pos
        verts[:, 3:6] = normals
        verts[:, 6:8] = (0.5, 0.5)
        self.bind_buffer(0x8892, self.vbo)
        self.buffer_data(0x8892, verts.nbytes, verts.ctypes.data, 0x88E4)
        for loc, size, off in ((0, 3, 0), (5, 3, 12), (2, 2, 24)):
            self.enable_attrib(loc)
            self.attrib_ptr(loc, size, 0x1406, 0, 32, ctypes.c_void_p(off))
        self.attrib4f(1, 1.0, 1.0, 1.0, 1.0)
        self.attribI2i(3, 0, 10)
        self.attribI2i(4, 240, 240)
        self.attrib1f(6, 0.0)
        self.attrib3f(14, 0.0, 0.0, 0.0)
        self.attrib3f(15, 0.0, 1.0, 0.0)
        out = np.zeros((n, 7), dtype=np.float32)
        self.bind_buffer(0x8C8E, self.tfo)
        self.buffer_data(0x8C8E, out.nbytes, None, 0x88E5)
        self.bind_base(0x8C8E, 0, self.tfo)
        self.enable(0x8C89)  # GL_RASTERIZER_DISCARD
        self.begin_tf(0)  # GL_POINTS
        self.draw_arrays(0, 0, n)
        self.end_tf()
        self.finish()
        self.get_sub(0x8C8E, 0, out.nbytes, out.ctypes.data)
        return out[:, 0:4].astype(np.float64), out[:, 4:7].astype(np.float64)


def main():
    gl = Runner()
    aspect = W / H
    cases = [('vanilla, 70 deg', perspective(70, aspect)),
             ('vanilla, TACZ model fov 45 deg', perspective(45, aspect)),
             ('Oculus, 70 deg', scale(1, 1, 0.125) @ perspective(70, aspect)),
             ('Oculus, TACZ model fov 45 deg', scale(1, 1, 0.125) @ perspective(45, aspect))]
    model_view = np.eye(4)  # RenderSystem.getModelViewMatrix() in both hand passes
    base = {'ClothDepthBias': 0.0, 'BrowRevealPull': 0.0, 'HasClassMap': 0.0, 'FaceRect': (0, 0, 0, 0)}

    print('-- 1/2: arm vertices, NativeRenderer write (pose x vertex) -> npr_character.vsh vs YSM\'s own draw')
    views = []
    for right in (True, False):
        pose = hand_pose(right)
        view = to_view(pose, arm_points(right))
        views.append(view)
        print('   %s arm: view x %.3f..%.3f  y %.3f..%.3f  z %.3f..%.3f (blocks)' % (
            'right' if right else 'left', view[:, 0].min(), view[:, 0].max(), view[:, 1].min(), view[:, 1].max(),
            view[:, 2].min(), view[:, 2].max()))
    view = np.vstack(views)
    normals = np.tile((0.0, 0.0, 1.0), (len(view), 1))
    for name, proj in cases:
        expected = (proj @ model_view @ np.c_[view, np.ones(len(view))].T).T  # ProjMat x ModelViewMat x Position
        clip, vpos = gl.run('npr_character.vsh', view, normals, proj, model_view, base)
        px = np.abs(window(clip)[:, :2] - window(expected)[:, :2]).max()
        dz = np.abs(window(clip)[:, 2] - window(expected)[:, 2]).max()
        inside = np.all(np.abs(expected[:, :3] / expected[:, 3:4]) <= 1.0, axis=1)
        check(px < 1e-3 and dz < 1e-6 and np.abs(vpos - view).max() < 1e-6,
              '%s: gl_Position = YSM\'s clip position (max %.2g px, depth %.2g); viewPos = the written vertex; '
              '%d/%d vertices on screen' % (name, px, dz, inside.sum(), len(view)))

    print('-- 3: clothDepthBias 0.01 on cloth texels (class map blue 10) under each projection')
    for name, proj in cases:
        plain, _ = gl.run('npr_character.vsh', view, normals, proj, model_view, base)
        biased, _ = gl.run('npr_character.vsh', view, normals, proj, model_view,
                           dict(base, ClothDepthBias=0.01, HasClassMap=1.0))
        a, b = window(plain), window(biased)
        # expected: the vertex 1 cm nearer along its ray
        d = np.linalg.norm(view, axis=1, keepdims=True)
        near = view * (1.0 - 0.01 / d)
        expect = window((proj @ np.c_[near, np.ones(len(near))].T).T)
        check(np.abs(a[:, :2] - b[:, :2]).max() < 1e-2 and np.all(b[:, 2] < a[:, 2])
              and np.abs(b[:, 2] - expect[:, 2]).max() < 1e-6,
              '%s: same pixel (max %.2g px), depth nearer by %.2e..%.2e = 1 cm along the ray' % (
                  name, np.abs(a[:, :2] - b[:, :2]).max(), (a[:, 2] - b[:, 2]).min(), (a[:, 2] - b[:, 2]).max()))

    print('-- 4: outline shell (npr_outline.vsh): screen shift and depth push')
    width, offset_z = 0.6, 0.0  # GENERIC cloth width; per-material widths scale the same way
    rng = np.random.default_rng(7)
    nrm = rng.normal(size=(len(view), 3))
    nrm[:, 2] = np.abs(nrm[:, 2]) * 0.3  # silhouette normals, mostly across the view
    nrm /= np.linalg.norm(nrm, axis=1, keepdims=True)
    uni = {'OutlineWidth': (width, width, width, width), 'OutlineOffsetZ': (offset_z,) * 4, 'HasClassMap': 0.0,
           'OutlineScale': 1.0, 'ScreenSize': (W, H)}
    margin = 2.0 + max(width * 0.39269908 * 0.005 * 4.5837 * H * 0.5, 1.0)  # NprRenderer.armsRect
    for name, proj in cases:
        body, _ = gl.run('npr_character.vsh', view, nrm, proj, model_view, base)
        shell, _ = gl.run('npr_outline.vsh', view, nrm, proj, model_view, uni)
        a, b = window(body), window(shell)
        shift = np.abs(b[:, :2] - a[:, :2]).max(axis=1)
        # analytic (npr_outline.vsh): per axis max(|raw|, min) with raw ~ width-based plateau near the camera
        half = math.atan(1.0 / proj[1, 1])
        depth = body[:, 3]
        raw = width * 0.39269908 / half * np.clip(depth * half * 114.591568 * 0.04, 0, 1) * 0.005 / depth  # NDC
        expect_max = np.maximum(raw * H * 0.5, 1.0)  # pixels along y (x is aspect-corrected to the same pixels)
        sd = depth + offset_z * 0.1 + 0.06
        pushed = view * (sd / depth)[:, None]
        expect_z = window((proj @ np.c_[pushed, np.ones(len(pushed))].T).T)[:, 2]
        check(np.all(shift <= expect_max + 0.05) and shift.max() <= margin and np.abs(b[:, 2] - expect_z).max() < 2e-6,
              '%s: shell shift %.2f..%.2f px (analytic max %.2f, armsRect margin %.2f), depth = the point 6 cm '
              'farther on the ray (max err %.1e)' % (name, shift.min(), shift.max(), expect_max.max(), margin,
                                                    np.abs(b[:, 2] - expect_z).max()))

    print('-- 5: window depths (glDepthRange 0..1)')
    world = perspective(90, aspect)  # the level pass: options fov 90 (a typical setting)
    for name, proj in cases[::2]:
        arm = window((proj @ np.c_[view, np.ones(len(view))].T).T)[:, 2]
        item = view * (1 + 0.02 / np.linalg.norm(view, axis=1, keepdims=True))  # a gun surface 2 cm behind the hand
        item_z = window((proj @ np.c_[item, np.ones(len(item))].T).T)[:, 2]
        check(np.all(arm < item_z), '%s: arm %.4f..%.4f, held item 2 cm behind it always farther' % (
            name, arm.min(), arm.max()))
        if name.startswith('Oculus'):
            dists = np.array([0.06, 0.08, 0.1, 0.12, 0.2, 1.0, 10.0])
            wz = window((world @ np.c_[np.zeros((len(dists), 2)), -dists, np.ones(len(dists))].T).T)[:, 2]
            hidden = dists[wz < arm.max()]
            print('      world (fov 90) at %s blocks -> depth %s' % (
                ', '.join('%g' % d for d in dists), ', '.join('%.4f' % z for z in wz)))
            check(hidden.size == 0 or hidden.max() < 0.12,
                  'Oculus: only world geometry nearer than %.2f blocks could cover the arms (as Oculus\'s own hand)'
                  % (hidden.max() if hidden.size else 0.0))
    print('vanilla: GameRenderer clears the depth before the hand pass, so the world never covers the arms')
    if failures:
        print('%d FAILED' % len(failures))
        sys.exit(1)
    print('all checks passed')


if __name__ == '__main__':
    main()
