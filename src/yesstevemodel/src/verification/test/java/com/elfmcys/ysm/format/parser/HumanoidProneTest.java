package com.elfmcys.ysm.format.parser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanoidProneTest {
    /**
     * Lying prone puts the eyes where Battlefield Movement puts the prone camera, 0.6 blocks ahead of the feet and 0.4
     * above the ground, so the first-person view sits in the head: the hips' front rests on the ground and the elbows
     * do not sink into it.
     */
    @Test
    void proneEyesSitAtTheProneCamera() throws Exception {
        try (var importer = HumanoidImport.load("probe.pmx", HumanoidImportTest.syntheticPmx(), path -> null)) {
            var skeleton = importer.skeleton();
            var body = importer.proneBody();
            var perBlock = 16f / importer.main().heightScale;
            var bones = clip(importer, "climbing");
            var time = 0f;

            var eye = world(skeleton, bones, "Head", time).transformPosition(new Vector3f(body.eye()));
            assertEquals(HumanoidMotion.PRONE_EYE_HEIGHT * perBlock, eye.y, 0.05f, "eye height " + eye);
            assertEquals(-HumanoidMotion.PRONE_EYE_FORWARD * perBlock, eye.z, 0.05f, "eye ahead " + eye);

            var hips = new Vector3f(body.hips().x, body.hips().y, body.hipFront());
            assertEquals(0f, world(skeleton, bones, "AllBody", time).transformPosition(hips).y, 0.05f, "hips");
            for (var side : List.of("Left", "Right")) {
                var elbow = world(skeleton, bones, side + "ForeArm", time)
                    .transformPosition(new Vector3f(skeleton.get(side + "ForeArm").pivot()));
                assertTrue(elbow.y > -0.05f, side + " elbow below the ground: " + elbow);
            }
        }
    }

    /**
     * Going down starts standing and ends exactly in the lying pose; getting up starts there and ends standing, so the
     * clips before and after never jump.
     */
    @Test
    void proneTransitionsJoinStandingAndLying() throws Exception {
        try (var importer = HumanoidImport.load("probe.pmx", HumanoidImportTest.syntheticPmx(), path -> null)) {
            var skeleton = importer.skeleton();
            var lying = clip(importer, "climbing");
            var enter = clip(importer, "bfm:prone_enter");
            var exit = clip(importer, "bfm:prone_exit");
            var down = length(importer, "bfm:prone_enter");
            var up = length(importer, "bfm:prone_exit");
            for (var name : List.of("Head", "LeftHandLocator", "RightFoot")) {
                var pivot = new Vector3f(skeleton.get(name).pivot());
                var rest = world(skeleton, new JsonObject(), name, 0f).transformPosition(new Vector3f(pivot));
                var lies = world(skeleton, lying, name, 0f).transformPosition(new Vector3f(pivot));
                assertTrue(
                    rest.distance(world(skeleton, enter, name, 0f).transformPosition(new Vector3f(pivot))) < 0.01f,
                    name + " enter start");
                assertTrue(
                    lies.distance(world(skeleton, enter, name, down).transformPosition(new Vector3f(pivot))) < 0.01f,
                    name + " enter end");
                assertTrue(
                    lies.distance(world(skeleton, exit, name, 0f).transformPosition(new Vector3f(pivot))) < 0.01f,
                    name + " exit start");
                assertTrue(
                    rest.distance(world(skeleton, exit, name, up).transformPosition(new Vector3f(pivot))) < 0.01f,
                    name + " exit end");
            }
        }
    }

    private static JsonObject clip(HumanoidImport importer, String name) {
        return JsonParser.parseString(importer.animation()).getAsJsonObject().getAsJsonObject("animations")
            .getAsJsonObject(name).getAsJsonObject("bones");
    }

    private static float length(HumanoidImport importer, String name) {
        return JsonParser.parseString(importer.animation()).getAsJsonObject().getAsJsonObject("animations")
            .getAsJsonObject(name).get("animation_length").getAsFloat();
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
