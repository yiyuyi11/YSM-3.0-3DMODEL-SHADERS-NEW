package com.elfmcys.ysm.format.parser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaczRetargetTest {
    /**
     * Fitted gun clips keep both hands on the gun: the support hand sits from the grip as far as the default model's
     * does, scaled with the held item, and the gun keeps the default's orientation, whatever this body's arms.
     */
    @Test
    void fittedGunClipsKeepBothHandsOnTheGun() throws Exception {
        var defaults = TaczRetarget.defaultSkeleton();
        JsonObject clips;
        try (var reader = new InputStreamReader(
            TaczRetarget.class.getResourceAsStream("/assets/ysm/builtin/default/animations/tac.animation.json"),
            StandardCharsets.UTF_8)) {
            clips = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animations");
        }
        try (var importer = HumanoidImport.load("probe.pmx", HumanoidImportTest.syntheticPmx(), path -> null)) {
            var ours = importer.skeleton();
            var fitted = JsonParser.parseString(importer.animation()).getAsJsonObject().getAsJsonObject("animations");
            for (var name : new String[] {"tac:hold:rifle", "tac:aim:pistol"}) {
                var source = clips.getAsJsonObject(name).getAsJsonObject("bones");
                var result = fitted.getAsJsonObject(name);
                assertNotNull(result, name);
                var bones = result.getAsJsonObject("bones");

                var defaultGrip = point(defaults, source, "RightHandLocator", 0f);
                var defaultSupport = point(defaults, source, "LeftHandLocator", 0f);
                var grip = point(ours, bones, "RightHandLocator", 0f);
                var support = point(ours, bones, "LeftHandLocator", 0f);
                assertEquals(defaultSupport.distance(defaultGrip) * HumanoidSource.DEFAULT_ITEM_SCALE,
                    support.distance(grip), 0.05f, name);

                var defaultGun = world(defaults, source, "RightHandLocator", 0f).get3x3(new Matrix3f());
                var gun = world(ours, bones, "RightHandLocator", 0f).get3x3(new Matrix3f());
                for (var column = 0; column < 3; column++) {
                    for (var row = 0; row < 3; row++) {
                        assertEquals(defaultGun.get(column, row), gun.get(column, row), 1e-3f, name);
                    }
                }
            }
        }
    }

    /**
     * Elbows are hinges: in every fitted gun clip, at every keyframe, each forearm points straight on from its upper
     * arm or bends forward of it, never behind it (a reversed, hyperextended elbow).
     */
    @Test
    void fittedElbowsNeverBendBackward() throws Exception {
        try (var importer = HumanoidImport.load("probe.pmx", HumanoidImportTest.syntheticPmx(), path -> null)) {
            var ours = importer.skeleton();
            var fitted = JsonParser.parseString(importer.animation()).getAsJsonObject().getAsJsonObject("animations");
            var checked = 0;
            for (var clip : fitted.entrySet()) {
                if (!clip.getKey().startsWith("tac:")) {
                    continue;
                }
                var bones = clip.getValue().getAsJsonObject().getAsJsonObject("bones");
                for (var side : new String[] {"Left", "Right"}) {
                    var channels = bones.getAsJsonObject(side + "ForeArm");
                    if (channels == null || !channels.has("rotation") || !channels.get("rotation").isJsonObject()) {
                        continue;
                    }
                    var bind = new Vector3f(ours.get(side + "Hand").pivot()).sub(ours.get(side + "ForeArm").pivot())
                        .normalize();
                    for (var time : channels.getAsJsonObject("rotation").keySet()) {
                        var at = Float.parseFloat(time);
                        var upper = world(ours, bones, side + "Arm", at).get3x3(new Matrix3f());
                        var fore = world(ours, bones, side + "ForeArm", at).get3x3(new Matrix3f());
                        var direction = upper.transpose(new Matrix3f()).transform(fore.transform(new Vector3f(bind)));
                        // The upper arm's frame is the bind pose: the arm hangs along -Y and forward is -Z.
                        assertTrue(direction.z < 0.02f, clip.getKey() + " " + side + " at " + time + ": " + direction);
                        checked++;
                    }
                }
            }
            assertTrue(checked > 20, "checked " + checked);
        }
    }

    private static Vector3f point(Map<String, TaczRetarget.Bone> skeleton, JsonObject bones, String name, float time) {
        return world(skeleton, bones, name, time).transformPosition(new Vector3f(skeleton.get(name).pivot()));
    }

    /** The bone's world transform at the given time, as the native renderer composes it. */
    private static Matrix4f world(Map<String, TaczRetarget.Bone> skeleton, JsonObject bones, String name, float time) {
        var bone = skeleton.get(name);
        var parent = bone.parent() == null ? new Matrix4f() : world(skeleton, bones, bone.parent(), time);
        var channels = bones.getAsJsonObject(name);
        var keyframe = new TaczRetarget.Channel(channels == null ? null : channels.get("rotation")).at(time);
        var offset = new TaczRetarget.Channel(channels == null ? null : channels.get("position")).at(time);
        var rotation = new Vector3f(bone.rest()).add((float)Math.toRadians(-keyframe.x),
            (float)Math.toRadians(-keyframe.y), (float)Math.toRadians(keyframe.z));
        var pivot = bone.pivot();
        return new Matrix4f(parent).translate(pivot.x - offset.x, pivot.y + offset.y, pivot.z + offset.z)
            .rotateZYX(rotation.z, rotation.y, rotation.x).translate(-pivot.x, -pivot.y, -pivot.z);
    }
}
