package com.elfmcys.ysm.format.parser;

import com.elfmcys.quickbuf.ProtoSource;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SkinnedMeshProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringBonesProto;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Anti-clip harness: runs YSM's humanoid import on a model file and writes what the offline replay needs: the main
 * GeoModel exactly as the game bakes it ({@code geo.pb}), the skeleton, render scale and spring bones
 * ({@code meta.json}) and the rest mesh ({@code mesh.bin}: little-endian float32 positions, normals, weights, then
 * int32 joints and triangles).
 * <p>Usage: AntiClipDump &lt;model file&gt; &lt;out dir&gt;
 */
public final class AntiClipDump {
    public static void main(String[] args) throws Exception {
        com.elfmcys.ysm.natives.NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        com.elfmcys.ysm.natives.NativeRuntime.initialize(
            com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.WARN));
        var model = Path.of(args[0]);
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        var data = Files.readAllBytes(model);
        var buffer = ByteBuffer.allocateDirect(data.length).put(data).flip();
        try (var humanoid = HumanoidImport.load(model.getFileName().toString(), buffer, name -> {
            var file = model.resolveSibling(name);
            return Files.exists(file) ? ByteBuffer.wrap(Files.readAllBytes(file)) : null;
        })) {
            var main = humanoid.main();
            var geo = main.model;
            Files.write(out.resolve("geo.pb"), com.elfmcys.ysm.util.ProtoUtil.serializeToArray(geo));
            var mesh = SkinnedMeshProto.parseFrom(bytes(geo.mesh()));
            var json = new StringBuilder("{\"bones\":[");
            var names = new java.util.HashMap<String, Integer>();
            for (var i = 0; i < geo.bones().size(); i++) {
                names.put(geo.bones().get(i).name(), i);
            }
            for (var i = 0; i < geo.bones().size(); i++) {
                var bone = geo.bones().get(i);
                var p = bone.pivot();
                var r = bone.rotate();
                var parent = bone.parentNullOrEmpty() ? -1 : names.getOrDefault(bone.parentUnsafe(), -1);
                json.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT,
                    "{\"name\":\"%s\",\"parent\":%d,\"pivot\":[%.9g,%.9g,%.9g],\"rotate\":[%.9g,%.9g,%.9g]}",
                    bone.name(), parent, p.getFloat(0), p.getFloat(1), p.getFloat(2), r.getFloat(0), r.getFloat(1),
                    r.getFloat(2)));
            }
            json.append("],\"joints\":[");
            var body = new StringBuilder();
            // Spring details: joint_info [bone, chain, hit radius, stiffness, gravity power, drag, tail xyz],
            // colliders [bone, type, head xyz, tail xyz, radius], chains [[collider indices], center].
            var springDetail = new StringBuilder();
            if (geo.springs().hasRemaining()) {
                var springs = SpringBonesProto.parseFrom(ProtoSource.newInstance(geo.springs().duplicate()));
                springDetail.append(",\"joint_info\":[");
                for (var i = 0; i < springs.joints().size(); i++) {
                    var joint = springs.joints().get(i);
                    json.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT, "[%d,%d,%d,%.9g]", joint.bone(),
                        joint.chain(), joint.backstop(), joint.backstopAllowance()));
                    springDetail.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT,
                        "[%d,%d,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g]", joint.bone(), joint.chain(), joint.hitRadius(),
                        joint.stiffness(), joint.gravityPower(), joint.drag(), joint.tail().getFloat(0),
                        joint.tail().getFloat(1), joint.tail().getFloat(2)));
                }
                springDetail.append("],\"colliders\":[");
                for (var i = 0; i < springs.colliders().size(); i++) {
                    var c = springs.colliders().get(i);
                    springDetail.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT,
                        "[%d,%d,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g]", c.bone(), c.type(), c.head().getFloat(0),
                        c.head().getFloat(1), c.head().getFloat(2), c.tail().getFloat(0), c.tail().getFloat(1),
                        c.tail().getFloat(2), c.radius()));
                }
                springDetail.append("],\"chains\":[");
                for (var i = 0; i < springs.chains().size(); i++) {
                    var c = springs.chains().get(i);
                    springDetail.append(i == 0 ? "[[" : ",[[");
                    for (var k = 0; k < c.colliders().size(); k++) {
                        springDetail.append(k == 0 ? "" : ",").append(c.colliders().getInt(k));
                    }
                    springDetail.append("],").append(c.center()).append(']');
                }
                springDetail.append(']');
                for (var i = 0; i < springs.body().size(); i++) {
                    var c = springs.body().get(i);
                    body.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT,
                        "[%d,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g]", c.bone(), c.head().getFloat(0),
                        c.head().getFloat(1), c.head().getFloat(2), c.tail().getFloat(0), c.tail().getFloat(1),
                        c.tail().getFloat(2), c.radius(), c.tailRadius()));
                }
                if (!springs.pushShare().isEmpty()) {
                    var push = ByteBuffer.allocate(12 * springs.pushShare().size()).order(ByteOrder.LITTLE_ENDIAN);
                    for (var list : new it.unimi.dsi.fastutil.floats.FloatList[] {springs.pushShare(),
                        springs.pushAllowance()}) {
                        for (var i = 0; i < list.size(); i++) {
                            push.putFloat(list.getFloat(i));
                        }
                    }
                    for (var i = 0; i < springs.pushCapsules().size(); i++) {
                        push.putInt(springs.pushCapsules().getInt(i));
                    }
                    Files.write(out.resolve("push.bin"), push.array());
                }
            }
            json.append("],\"body\":[").append(body).append(']').append(springDetail);
            json.append(String.format(Locale.ROOT, ",\"height_scale\":%.9g,\"vertices\":%d,\"triangles\":%d}",
                main.heightScale, mesh.positions().size() / 3, mesh.triangles().size() / 3));
            Files.writeString(out.resolve("meta.json"), json);
            var bin = ByteBuffer.allocate(4 * (mesh.positions().size() + mesh.normals().size()
                + mesh.weights().size() + mesh.joints().size() + mesh.triangles().size())).order(ByteOrder.LITTLE_ENDIAN);
            for (var list : new it.unimi.dsi.fastutil.floats.FloatList[] {mesh.positions(), mesh.normals(),
                mesh.weights()}) {
                for (var i = 0; i < list.size(); i++) {
                    bin.putFloat(list.getFloat(i));
                }
            }
            for (var list : new it.unimi.dsi.fastutil.ints.IntList[] {mesh.joints(), mesh.triangles()}) {
                for (var i = 0; i < list.size(); i++) {
                    bin.putInt(list.getInt(i));
                }
            }
            Files.write(out.resolve("mesh.bin"), bin.array());
            System.out.println("wrote " + out + ": " + geo.bones().size() + " bones, " + mesh.positions().size() / 3
                + " vertices, " + mesh.triangles().size() / 3 + " triangles");
        }
    }

    private static byte[] bytes(ByteBuffer buffer) {
        var copy = buffer.duplicate();
        var result = new byte[copy.remaining()];
        copy.get(result);
        return result;
    }
}
