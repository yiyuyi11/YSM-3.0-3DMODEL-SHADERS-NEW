package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile;
import com.elfmcys.ysm.natives.render.NativeBakedModel;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.util.ProtoUtil;
import com.ysmfit.fitbox.core.Fk;
import com.ysmfit.fitbox.ysm.YsmGeometry;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Checks the mod's forward kinematics (the server approximation's bone transforms) against YSM's native renderer:
 * bakes the imported model with YSM's native library, feeds the same clip as YSM's Java animation layer would
 * (rotation = rest + keyframe with X/Y negated in radians, raw keyframe position, scale) and compares the native
 * per-bone pose matrices with {@link Fk}.
 *
 * <p>usage: FkCheck &lt;model.vrm&gt; clip...
 */
public final class FkCheck {
    public static void main(String[] args) throws Exception {
        com.elfmcys.ysm.natives.NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        com.elfmcys.ysm.natives.NativeRuntime.initialize(
            com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.WARN));
        var model = Path.of(args[0]);
        var data = Files.readAllBytes(model);
        var buffer = ByteBuffer.allocateDirect(data.length).put(data).flip();
        try (var humanoid = HumanoidImport.load(model.getFileName().toString(), buffer, file -> {
            var f = model.resolveSibling(file);
            return Files.exists(f) ? ByteBuffer.wrap(Files.readAllBytes(f)) : null;
        })) {
            var main = humanoid.main();
            var geo = main.model;
            var rig = YsmGeometry.rig(geo, 1f, 1f);
            var file = AnimationFile.createGson(false).fromJson(humanoid.animation(), AnimationFile.class);
            var clips = YsmGeometry.clips(AnimationBuilder.build(file, new MolangFrontend(), model.toString()), "model")
                .orElse(YsmGeometry.defaults());
            var n = geo.bones().size();
            NativeBakedModel.ModelResult result;
            try (var modelData = ArrayBuffer.allocateWithScope(geo.getSerializedSize());
                var tex = com.elfmcys.ysm.util.ScopeGuard.create(NativeBuffer.allocate(16 * 16 * 4))) {
                geo.writeTo(ProtoUtil.sink(modelData.get()));
                result = NativeBakedModel.bake(modelData.get(), n, tex.get(), 16, 16, 29, false);
            }
            var sorted = result.sortedBoneIndices(); // sorted index -> original bone index
            for (var c = 1; c < args.length; c++) {
                var clip = clips.get(args[c]);
                var attrs = new float[n * 14];
                for (var s = 0; s < n; s++) {
                    var orig = Short.toUnsignedInt(sorted[s]);
                    var ch = clip.get(rig.names[orig]);
                    var o = s * 14;
                    attrs[o] = rig.rest[orig * 3] + (ch == null ? 0 : (float)Math.toRadians(-ch[0]));
                    attrs[o + 1] = rig.rest[orig * 3 + 1] + (ch == null ? 0 : (float)Math.toRadians(-ch[1]));
                    attrs[o + 2] = rig.rest[orig * 3 + 2] + (ch == null ? 0 : (float)Math.toRadians(ch[2]));
                    attrs[o + 3] = ch == null ? 0 : ch[3];
                    attrs[o + 4] = ch == null ? 0 : ch[4];
                    attrs[o + 5] = ch == null ? 0 : ch[5];
                    attrs[o + 6] = ch == null ? 1 : ch[6];
                    attrs[o + 7] = ch == null ? 1 : ch[7];
                    attrs[o + 8] = ch == null ? 1 : ch[8];
                    attrs[o + 12] = 0xFFFFFF;
                    attrs[o + 13] = 0xFFFF;
                }
                var state = NativeModelState.create();
                if (!state.extract(result.bakedModel(), attrs)) {
                    System.out.println(args[c] + ": native extract failed");
                    continue;
                }
                var poses = state.getBonePoses();
                var published = new java.util.HashSet<Integer>();
                var render = state.getRenderBoneIndices();
                for (var i = 0; i < render.limit(); i++) {
                    published.add(Short.toUnsignedInt(render.get(i)));
                }
                var locators = state.getLocatorBoneIndices();
                System.out.println("  render bones " + render.limit() + " locators " + locators.size() + " baked bones "
                    + poses.getBoneCount());
                var fk = Fk.pose(rig, clip);
                var nat = new Matrix4f();
                var worst = 0f;
                var worstBone = "";
                var compared = 0;
                for (var s = 0; s < n; s++) {
                    var orig = Short.toUnsignedInt(sorted[s]);
                    if ((!published.contains(s) && System.getenv("ALL") == null) || rig.loose[orig]) {
                        continue;
                    }
                    poses.getPose(s, nat);
                    // Compare where both put the bone's pivot and a point 0.25 blocks off it on each axis.
                    var px = rig.pivot[orig * 3];
                    var py = rig.pivot[orig * 3 + 1];
                    var pz = rig.pivot[orig * 3 + 2];
                    for (var probe : new float[][] {{0, 0, 0}, {0.25f, 0, 0}, {0, 0.25f, 0}, {0, 0, 0.25f}}) {
                        var a = nat.transformPosition(new Vector3f(px + probe[0], py + probe[1], pz + probe[2]));
                        var b = fk[orig].transformPosition(new Vector3f(px + probe[0], py + probe[1], pz + probe[2]));
                        var e = a.distance(b);
                        if (e > worst) {
                            worst = e;
                            worstBone = rig.names[orig];
                        }
                    }
                    compared++;
                }
                var missing = new StringBuilder();
                for (var name : new String[] {"Head", "UpBody", "AllBody", "LeftArm", "LeftForeArm", "LeftHand",
                    "RightArm", "RightForeArm", "RightHand", "LeftLeg", "LeftLowerLeg", "LeftFoot", "RightLeg",
                    "RightLowerLeg", "RightFoot"}) {
                    var orig = rig.bone(name);
                    var found = false;
                    for (var s = 0; s < n; s++) {
                        if (Short.toUnsignedInt(sorted[s]) == orig && published.contains(s)) {
                            found = true;
                        }
                    }
                    if (!found) {
                        missing.append(' ').append(name);
                    }
                }
                System.out.println("  part root bones without a published pose:" + missing);
                System.out.printf(Locale.ROOT, "%-12s %s: %d %s core bones compared, max deviation %.6f blocks"
                    + " (%s)%n", args[c], clip.source, compared, System.getenv("ALL") == null ? "published" : "(all)",
                    worst, worstBone);
                state.close();
            }
        }
    }
}
