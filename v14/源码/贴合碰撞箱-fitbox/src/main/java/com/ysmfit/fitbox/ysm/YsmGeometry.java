package com.ysmfit.fitbox.ysm;

import com.elfmcys.quickbuf.ProtoSource;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CubesProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SkinnedMeshProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringBonesProto;
import com.ysmfit.fitbox.core.Clip;
import com.ysmfit.fitbox.core.JsonClips;
import com.ysmfit.fitbox.core.Rig;
import it.unimi.dsi.fastutil.objects.ObjectList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads YSM's stored model data (the {@code GeoModelProto} of a model's player target, as the server's catalog and the
 * humanoid importer produce it) into a {@link Rig}, and its compiled animation file into clips. No Minecraft classes:
 * the offline tool runs this too.
 */
public final class YsmGeometry {
    /** YSM's builtin default clips; models borrow every clip they lack from here by name. */
    public static final String DEFAULT_CLIPS = "/assets/ysm/builtin/default/animations/main.animation.json";
    private static volatile Clip.Library defaults;

    private YsmGeometry() {}

    public static Rig rig(GeoModelProto geo, float widthScale, float heightScale) throws IOException {
        var bones = geo.bones();
        var n = bones.size();
        var names = new String[n];
        var parent = new int[n];
        var pivot = new float[n * 3];
        var rest = new float[n * 3];
        Map<String, Integer> index = new HashMap<>();
        for (var i = 0; i < n; i++) {
            names[i] = bones.get(i).name();
            index.putIfAbsent(names[i], i);
        }
        for (var i = 0; i < n; i++) {
            var b = bones.get(i);
            var p = b.parent();
            parent[i] = p.isPresent() && index.containsKey(p.get()) ? index.get(p.get()) : -1;
            if (parent[i] == i) {
                parent[i] = -1;
            }
            for (var k = 0; k < 3; k++) {
                pivot[i * 3 + k] = b.pivot().size() > k ? b.pivot().getFloat(k) / 16f : 0f;
                rest[i * 3 + k] = b.rotate().size() > k ? b.rotate().getFloat(k) : 0f;
            }
        }
        var spring = new boolean[n];
        var springs = geo.springs();
        if (springs != null && springs.remaining() > 0) {
            var sb = SpringBonesProto.parseFrom(ProtoSource.newInstance(springs.duplicate()));
            for (var j : sb.joints()) {
                if (j.bone() >= 0 && j.bone() < n) {
                    spring[j.bone()] = true;
                }
            }
        }
        var mesh = geo.mesh();
        if (mesh != null && mesh.remaining() > 0) {
            var m = SkinnedMeshProto.parseFrom(ProtoSource.newInstance(mesh.duplicate()));
            var positions = m.positions();
            var vc = positions.size() / 3;
            var pos = new float[vc * 3];
            for (var i = 0; i < pos.length; i++) {
                pos[i] = positions.getFloat(i);
            }
            var joints = new int[vc * 4];
            var weights = new float[vc * 4];
            var mj = m.joints();
            var mw = m.weights();
            for (var i = 0; i < vc * 4 && i < mj.size() && i < mw.size(); i++) {
                joints[i] = mj.getInt(i);
                weights[i] = mw.getFloat(i);
            }
            var area = new float[vc];
            var tris = m.triangles();
            for (var t = 0; t + 2 < tris.size(); t += 3) {
                int a = tris.getInt(t), b = tris.getInt(t + 1), c = tris.getInt(t + 2);
                if (a >= vc || b >= vc || c >= vc) {
                    continue;
                }
                var s = triangleArea(pos, a, b, c) / 3f;
                area[a] += s;
                area[b] += s;
                area[c] += s;
            }
            return new Rig(names, parent, pivot, rest, spring, pos, joints, weights, area, true, widthScale,
                heightScale);
        }
        // Classic cubes: surface samples carried by their bone (cube rotations are already in the positions).
        var cubesBuf = geo.cubes();
        var pos = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        var joints = new it.unimi.dsi.fastutil.ints.IntArrayList();
        var area = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        if (cubesBuf != null && cubesBuf.remaining() > 0) {
            var cubes = CubesProto.parseFrom(ProtoSource.newInstance(cubesBuf.duplicate())).cubesLegacy();
            var next = 0;
            for (var i = 0; i < n; i++) {
                var cc = bones.get(i).cubeCount();
                var k = cc.isPresent() ? cc.getAsInt() : 0;
                for (var c = 0; c < k && next < cubes.size(); c++, next++) {
                    var cube = cubes.get(next);
                    var p = cube.pos();
                    var idx = cube.posIndices();
                    // Each face is a quad of four position indices: sample it on a 4x4 grid, each sample standing
                    // for a sixteenth of the face, so quantiles see the cube's surface, not just its corners.
                    for (var f = 0; f + 3 < idx.size(); f += 4) {
                        int a = idx.getInt(f), b = idx.getInt(f + 1), c3 = idx.getInt(f + 2), d = idx.getInt(f + 3);
                        if (Math.max(Math.max(a, b), Math.max(c3, d)) * 3 + 2 >= p.size()) {
                            continue;
                        }
                        var faceArea = quadArea(p, a, b, d);
                        for (var su = 0; su < 4; su++) {
                            for (var sv = 0; sv < 4; sv++) {
                                var u = su / 3f;
                                var v = sv / 3f;
                                for (var k3 = 0; k3 < 3; k3++) {
                                    var top = p.getFloat(a * 3 + k3) + (p.getFloat(b * 3 + k3) - p.getFloat(a * 3 + k3)) * u;
                                    var bottom = p.getFloat(d * 3 + k3) + (p.getFloat(c3 * 3 + k3) - p.getFloat(d * 3 + k3)) * u;
                                    pos.add(top + (bottom - top) * v);
                                }
                                joints.add(i);
                                joints.add(0);
                                joints.add(0);
                                joints.add(0);
                                area.add(faceArea / 16f);
                            }
                        }
                    }
                }
            }
        }
        var w = new float[area.size() * 4];
        for (var i = 0; i < area.size(); i++) {
            w[i * 4] = 1;
        }
        return new Rig(names, parent, pivot, rest, spring, pos.toFloatArray(), joints.toIntArray(), w,
            area.toFloatArray(), false, widthScale, heightScale);
    }

    private static float quadArea(it.unimi.dsi.fastutil.floats.FloatList p, int a, int b, int d) {
        float ux = p.getFloat(b * 3) - p.getFloat(a * 3), uy = p.getFloat(b * 3 + 1) - p.getFloat(a * 3 + 1),
            uz = p.getFloat(b * 3 + 2) - p.getFloat(a * 3 + 2);
        float vx = p.getFloat(d * 3) - p.getFloat(a * 3), vy = p.getFloat(d * 3 + 1) - p.getFloat(a * 3 + 1),
            vz = p.getFloat(d * 3 + 2) - p.getFloat(a * 3 + 2);
        float cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
        return (float)Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    private static float triangleArea(float[] p, int a, int b, int c) {
        float ux = p[b * 3] - p[a * 3], uy = p[b * 3 + 1] - p[a * 3 + 1], uz = p[b * 3 + 2] - p[a * 3 + 2];
        float vx = p[c * 3] - p[a * 3], vy = p[c * 3 + 1] - p[a * 3 + 1], vz = p[c * 3 + 2] - p[a * 3 + 2];
        float cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
        return 0.5f * (float)Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /**
     * A model's compiled clips sampled at time 0. Literal keyframe values are read as stored; Molang values (stored
     * compiled) are evaluated by {@link ProgramEval} for a neutral, empty-handed body.
     */
    public static Clip.Library clips(AnimationFileProto file, String source) {
        Map<String, com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto> byName = new HashMap<>();
        if (file != null) {
            for (var a : file.animations()) {
                byName.putIfAbsent(a.name(), a);
            }
        }
        return (name, at) -> {
            var a = byName.get(name);
            if (a == null) {
                return null;
            }
            // Length in the keyframes' own time unit: the last keyframe start of any channel.
            var last = 0f;
            for (var b : a.boneAnimations()) {
                for (var list : List.of(b.rotation(), b.position(), b.scale())) {
                    for (var f : list) {
                        last = Math.max(last, f.startTick());
                    }
                }
            }
            var time = Math.max(0f, Math.min(1f, at)) * last;
            var clip = new Clip(name, source);
            for (var b : a.boneAnimations()) {
                channel(clip, b.boneName(), 0, b.rotation(), 0f, time);
                channel(clip, b.boneName(), 1, b.position(), 0f, time);
                channel(clip, b.boneName(), 2, b.scale(), 1f, time);
            }
            return clip;
        };
    }

    private static void channel(Clip clip, String bone, int channel, ObjectList<BoneKeyFrameProto> frames,
        float rest, float time) {
        if (frames.isEmpty()) {
            return;
        }
        BoneKeyFrameProto before = null;
        BoneKeyFrameProto after = null;
        for (var f : frames) {
            if (f.startTick() <= time && (before == null || f.startTick() >= before.startTick())) {
                before = f;
            }
            if (f.startTick() >= time && (after == null || f.startTick() < after.startTick())) {
                after = f;
            }
        }
        float[] out;
        if (before == null) {
            out = values(after, false, rest);
        } else if (after == null || after.startTick() == before.startTick()) {
            out = values(before, true, rest);
        } else {
            // Linear between the leaving side of one keyframe and the arriving side of the next.
            var a = values(before, true, rest);
            var b = values(after, false, rest);
            var t = (time - before.startTick()) / (after.startTick() - before.startTick());
            out = new float[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
        }
        if (out != null) {
            clip.set(bone, channel, out[0], out[1], out[2]);
        }
    }

    /** A keyframe's value leaving it (post, which is empty when equal to pre) or arriving at it (pre). */
    private static float[] values(BoneKeyFrameProto frame, boolean leaving, float rest) {
        var values = leaving ? (frame.post().isEmpty() ? frame.pre() : frame.post())
            : (frame.pre().isEmpty() ? frame.post() : frame.pre());
        if (values.isEmpty()) {
            return new float[] {rest, rest, rest};
        }
        var out = new float[3];
        for (var i = 0; i < 3; i++) {
            out[i] = value(values.get(Math.min(i, values.size() - 1)), rest);
        }
        return out;
    }

    private static float value(ExpressionValueProto v, float rest) {
        if (v.hasNum()) {
            return v.num();
        }
        return v.hasProgram() ? ProgramEval.eval(v.program(), rest) : rest;
    }

    /** YSM's builtin default clips, read once from the YSM jar. */
    public static Clip.Library defaults() {
        var d = defaults;
        if (d == null) {
            try (var in = GeoModelProto.class.getResourceAsStream(DEFAULT_CLIPS)) {
                d = in == null ? (name, at) -> null : JsonClips.read(in, "builtin");
            } catch (IOException e) {
                d = (name, at) -> null;
            }
            defaults = d;
        }
        return d;
    }

    /** Copies a ByteBuffer view's remaining bytes. */
    static byte[] bytes(ByteBuffer b) {
        var d = b.duplicate();
        var out = new byte[d.remaining()];
        d.get(out);
        return out;
    }
}
