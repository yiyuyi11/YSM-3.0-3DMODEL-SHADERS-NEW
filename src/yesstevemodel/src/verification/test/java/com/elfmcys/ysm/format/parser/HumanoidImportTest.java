package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SkinnedMeshProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringBonesProto;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanoidImportTest {
    private static final float EPSILON = 1e-4f;

    /** Absolute rest positions (VRM 1.0: faces +Z, avatar left is +X) and glTF parents. */
    private static final String[] NAMES = {"mesh", "hips", "spine", "head", "leftUpperArm", "leftLowerArm", "leftHand",
        "rightUpperArm", "rightLowerArm", "rightHand", "leftUpperLeg", "leftLowerLeg", "leftFoot", "rightUpperLeg",
        "rightLowerLeg", "rightFoot", "sleeve"};
    private static final int[] PARENTS = {-1, -1, 1, 2, 2, 4, 5, 2, 7, 8, 1, 10, 11, 1, 13, 14, 2};
    private static final float[][] POSITIONS = {{0, 0, 0}, {0, 1, 0}, {0, 1.1f, 0}, {0, 1.5f, 0}, {0.2f, 1.4f, 0},
        {0.45f, 1.4f, 0}, {0.7f, 1.4f, 0}, {-0.2f, 1.4f, 0}, {-0.45f, 1.4f, 0}, {-0.7f, 1.4f, 0}, {0.1f, 0.9f, 0},
        {0.1f, 0.5f, 0}, {0.1f, 0.1f, 0}, {-0.1f, 0.9f, 0}, {-0.1f, 0.5f, 0}, {-0.1f, 0.1f, 0}, {0.25f, 1.4f, 0}};
    private static final int SLEEVE = 16;

    /**
     * MMD standard bones in an A-pose (arms 45 degrees down), left at +X, plus 左足D copying 左足 with a detail bone 左足EX
     * below it, and the index finger.
     */
    private static final String[] NAMES_PMX = {"全ての親", "センター", "上半身", "首", "頭", "左肩", "左腕", "左ひじ", "左手首", "右肩", "右腕",
        "右ひじ", "右手首", "下半身", "左足", "左ひざ", "左足首", "右足", "右ひざ", "右足首", "左足D", "左人指１", "左人指２", "左人指３", "左足EX"};
    private static final int[] PARENTS_PMX =
        {-1, 0, 1, 2, 3, 2, 5, 6, 7, 2, 9, 10, 11, 1, 13, 14, 15, 13, 17, 18, 13, 8, 21, 22, 20};
    private static final float D = (float)(3 / Math.sqrt(2));
    private static final float[][] POSITIONS_PMX = {{0, 0, 0}, {0, 8, 0}, {0, 10, 0}, {0, 16, 0}, {0, 17, 0},
        {1, 15, 0}, {2, 15, 0}, {2 + D, 15 - D, 0}, {2 + 2 * D, 15 - 2 * D, 0}, {-1, 15, 0}, {-2, 15, 0},
        {-2 - D, 15 - D, 0}, {-2 - 2 * D, 15 - 2 * D, 0}, {0, 10, 0}, {1, 9, 0}, {1, 5, 0}, {1, 1, 0}, {-1, 9, 0},
        {-1, 5, 0}, {-1, 1, 0}, {1, 9, 0}, {2 + 2.1f * D, 15 - 2.1f * D, 0}, {2 + 2.2f * D, 15 - 2.2f * D, 0},
        {2 + 2.3f * D, 15 - 2.3f * D, 0}, {1, 7, 0}};

    @Test
    void retargetsVrm1ToDefaultSkeletonWithArmsDown() throws IOException {
        try (var importer = HumanoidImport.load("probe.vrm", syntheticVrm(), path -> null)) {
            assertEquals("probe", importer.metadata().name());
            var main = importer.main().model;

            // Facing -Z puts the avatar's left at -X; arms are re-posed to hang along -Y (pivots in pixels).
            // The hips at 1 m land at the default model's 20 pixels (1.25 model units per meter); the mesh top at
            // 1.625 model units renders two blocks up by default.
            assertEquals(2f / 1.625f, importer.main().heightScale, EPSILON);
            assertEquals(importer.main().heightScale, importer.main().widthScale, EPSILON);
            assertPivot(main, "LeftArm", -4f, 28f, 0);
            assertPivot(main, "LeftForeArm", -4f, 23f, 0);
            assertPivot(main, "LeftHand", -4f, 18f, 0);
            assertPivot(main, "RightHand", 4f, 18f, 0);
            assertPivot(main, "LeftLeg", -2f, 18f, 0);
            assertEquals((float)Math.toRadians(-15), bone(main, "LeftArm").rotate().getFloat(2), EPSILON);
            // Hip-wide legs (hips and ankles at +-2 px, 16 px long) turn inward so the ankles close to 40% of the hip
            // spacing; the feet turn back to stay level.
            var stance = (float)Math.asin((2 - 0.4 * 2) / 16);
            assertEquals(stance, bone(main, "LeftLeg").rotate().getFloat(2), EPSILON);
            assertEquals(-stance, bone(main, "RightLeg").rotate().getFloat(2), EPSILON);
            assertEquals(-stance, bone(main, "LeftFoot").rotate().getFloat(2), EPSILON);
            // The default skeleton's extra bones carry what default animations move: the whole body, the chest above
            // the waist (sword swings turn it) and the head.
            assertEquals("MAllBody", bone(main, "AllBody").parent().orElseThrow());
            assertEquals("Root", bone(main, "MAllBody").parent().orElseThrow());
            assertEquals("UpBody", bone(main, "UpperBody").parent().orElseThrow());
            assertEquals("MHead", bone(main, "Head").parent().orElseThrow());
            assertTrue(descends(main, "Arm", "UpperBody"), "the arms turn with the chest");
            assertEquals("DownBody", bone(main, "LeftLeg").parent().orElseThrow());
            assertEquals("Arm", bone(main, "LeftArm").parent().orElseThrow());
            // An aim-constrained helper follows the bone that drives its aim target.
            assertEquals("LeftArm", bone(main, "sleeve").parent().orElseThrow());

            var mesh = mesh(main);
            assertVertex(mesh, 0, -0.25f, 1.625f, 0);
            assertVertex(mesh, 1, -0.25f, 1.25f, 0);
            assertVertex(mesh, 2, -0.3125f, 1.625f, 0);
            assertEquals(List.of(0, 1, 2), mesh.triangles());

            // The first-person arm keeps the arm-only triangle, puts the shoulder at the default arm's pivot and scales
            // the 10 pixel reach so the wrist lands on the default arm's hand locator.
            var arm = importer.arm().model;
            assertTrue(bone(arm, "LeftArm").parent().isEmpty());
            assertPivot(arm, "LeftArm", -1.15f, 29.775f, 0);
            // The palm, where held items and gun grips go, lands at the default arm's hand locator 11.45 px below the
            // shoulder. Without fingers the palm lies 17% of the 5 px forearm past the wrist, 1.8 times further once
            // the hand is enlarged, so the arm scales by 11.45 / (10 + 1.53).
            assertPivot(arm, "LeftHandLocator", -1.15f, 18.325f, 0);
            var armScale = 11.45f / (10f + 1.8f * 0.17f * 5f);
            assertPivot(arm, "LeftHand", -1.15f, 29.775f - 10f * armScale, 0);
            // Mesh vertex 1 sat 8 pixels below the shoulder, on the arm's axis, where thickening does not move it.
            assertVertex(mesh(arm), 1, -1.15f / 16f, (29.775f - 8f * armScale) / 16f, 0);
            assertEquals(3, mesh(arm).triangles().size());
            assertEquals(0, arm.springs().remaining());

            // VRMC_springBone: the sleeve swings toward the next joint (the elbow, re-posed with the arm) and collides
            // with a sphere on the head.
            var springs = springs(main);
            assertEquals(1, springs.joints().size());
            var joint = springs.joints().get(0);
            assertEquals(boneIndex(main, "sleeve"), joint.bone());
            assertFloats(joint.tail(), -0.25f, 1.4375f, 0);
            assertFloats(joint.gravityDir(), 0, -1, 0);
            assertEquals(1.25f, joint.stiffness(), EPSILON);
            assertEquals(0.4f, joint.drag(), EPSILON);
            assertEquals(0.025f, joint.hitRadius(), EPSILON);
            var collider = springs.colliders().get(0);
            assertEquals(boneIndex(main, "Head"), collider.bone());
            assertFloats(collider.head(), 0, 2f, 0);
            assertEquals(0.125f, collider.radius(), EPSILON);
            assertEquals(List.of(0), springs.chains().get(joint.chain()).colliders());

            // Expressions: blink is driven by the "Eyelid" bone the default blink animation closes, others by an
            // expression bone's X translation. The upper-arm offset turns with the re-posed arm.
            var morphs = mesh.morphs();
            assertEquals(2, morphs.size());
            var blink = morphs.get(0);
            assertEquals(boneIndex(main, "Eyelid"), blink.bone());
            assertEquals(1, blink.driver());
            assertEquals(List.of(0), blink.vertices());
            assertFloats(blink.deltas(), -0.125f, 0, 0);
            var wink = morphs.get(1);
            assertEquals(boneIndex(main, "expression.wink"), wink.bone());
            assertEquals(0, wink.driver());
            assertFloats(wink.deltas(), -0.0625f, 0, 0);
            assertEquals(0, mesh(arm).morphs().size());
        }
    }

    @Test
    void humanoidJsonSetsTheRenderedHeight() throws IOException {
        HumanoidImport.TextureResolver tall = path -> path.equals(HumanoidMapping.OVERRIDE_FILE)
            ? ByteBuffer.wrap("{\"height\": 1.6}".getBytes(StandardCharsets.UTF_8)) : null;
        try (var importer = HumanoidImport.load("probe.vrm", syntheticVrm(), tall)) {
            assertEquals(1.6f / 1.625f, importer.main().heightScale, EPSILON);
        }
        for (var invalid : List.of("{\"height\": 0}", "{\"height\": \"tall\"}", "{\"height\": 100}")) {
            HumanoidImport.TextureResolver resolver = path -> path.equals(HumanoidMapping.OVERRIDE_FILE)
                ? ByteBuffer.wrap(invalid.getBytes(StandardCharsets.UTF_8)) : null;
            var error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> HumanoidImport.load("probe.vrm", syntheticVrm(), resolver).close());
            assertTrue(error.getMessage().contains("height"), error.getMessage());
        }
    }

    /**
     * Outlines are off unless humanoid.json asks for them: the stylized-PBR look outlines the silhouette with rim
     * light, so a model's PMX edges and MToon outlines add no hull and no outline color by default. With "outline":
     * true an MToon outline becomes an inverted hull: a copy of each vertex pushed out along its normal by the outline
     * width, on the same joints and expressions, wound the other way and sampling the outline color. A half-transparent
     * base color is dithered so the atlas has no partial alpha, which would send the whole model to the translucent
     * pass.
     */
    @Test
    void mtoonOutlineBecomesAnInwardHullAndBlendIsDithered() throws IOException {
        var materials = """
            [{"name": "Body", "alphaMode": "BLEND", "pbrMetallicRoughness": {"baseColorFactor": [1, 1, 1, 0.5]},
              "extensions": {"VRMC_materials_mtoon": {"outlineWidthMode": "worldCoordinates",
                "outlineWidthFactor": 0.004, "outlineColorFactor": [0.2, 0.1, 0.1]}}}]""";
        try (var plain = HumanoidImport.load("probe.vrm", syntheticVrm(materials), path -> null)) {
            var mesh = mesh(plain.main().model);
            assertEquals(0, mesh.outlineTriangles().size());
            assertEquals(3, mesh.positions().size() / 3);
        }
        HumanoidImport.TextureResolver outlined = path -> path.equals(HumanoidMapping.OVERRIDE_FILE)
            ? ByteBuffer.wrap("{\"outline\": true}".getBytes(StandardCharsets.UTF_8)) : null;
        try (var importer = HumanoidImport.load("probe.vrm", syntheticVrm(materials), outlined)) {
            var mesh = mesh(importer.main().model);
            // The body winds counter-clockwise along its normals (Minecraft's front faces); the hull the other way, so
            // back-face culling hides it wherever it faces the viewer.
            assertEquals(3, mesh.outlineTriangles().size());
            assertTrue(windsAlongNormals(mesh, mesh.triangles(), 0));
            assertFalse(windsAlongNormals(mesh, mesh.outlineTriangles(), 0));
            assertEquals(java.util.Set.of(3, 4, 5), java.util.Set.copyOf(mesh.outlineTriangles()));
            for (var vertex = 0; vertex < 3; vertex++) {
                var hull = vertex + 3;
                for (var axis = 0; axis < 3; axis++) {
                    // 4 mm at 1.25 model units per meter.
                    var pushed = mesh.positions().getFloat(vertex * 3 + axis)
                        + mesh.normals().getFloat(vertex * 3 + axis) * 0.005f;
                    assertEquals(pushed, mesh.positions().getFloat(hull * 3 + axis), EPSILON, "hull axis " + axis);
                }
                for (var k = 0; k < 4; k++) {
                    assertEquals(mesh.joints().getInt(vertex * 4 + k), mesh.joints().getInt(hull * 4 + k));
                    assertEquals(mesh.weights().getFloat(vertex * 4 + k), mesh.weights().getFloat(hull * 4 + k));
                }
            }
            // The blink offset on vertex 0 moves its hull copy too, so outlines follow the face.
            var blink = mesh.morphs().get(0);
            assertEquals(List.of(0, 3), blink.vertices());

            final byte[] bytes;
            try (var image = importer.atlasImage()) {
                bytes = new byte[image.data().nio().remaining()];
                image.data().nio().duplicate().get(bytes);
            }
            var atlas = HumanoidTexture.encoded(ByteBuffer.wrap(bytes));
            var texels = atlas.decode();
            var whiteOpaque = 0;
            var outline = 0;
            for (var i = 0; i < atlas.width() * atlas.height(); i++) {
                var texel = texel(texels, i);
                assertTrue(texel.get(3) == 0 || texel.get(3) == 255, "partial alpha at texel " + i + ": " + texel);
                if (texel.equals(List.of(255, 255, 255, 255))) {
                    whiteOpaque++;
                }
                if (texel.equals(List.of(51, 26, 26, 255))) {
                    outline++;
                }
            }
            // The texture-less body region is 4x4 texels plus a 2-texel clamped border, 64 in all; ordered dithering
            // at alpha 0.5 makes exactly half of them opaque. (Lossless encoding may drop the color of transparent
            // texels, so only opaque ones are counted.)
            assertEquals(32, whiteOpaque);
            assertTrue(outline > 0, "outline color region");
        }
    }

    /**
     * The specular map survives encoding: every texel is opaque (LabPBR alpha 255, not emissive), so the lossless
     * encoder keeps its smoothness, reflectance and class code; a fully transparent texel would lose them, and shader
     * packs and the toon shader would read an all-zero map. This model's untextured white body is cloth.
     */
    @Test
    void specularMapKeepsItsMaterialValuesThroughEncoding() throws IOException {
        try (var importer = HumanoidImport.load("probe.pmx", syntheticPmx(), path -> null);
            var image = importer.specularImage()) {
            var bytes = new byte[image.data().nio().remaining()];
            image.data().nio().duplicate().get(bytes);
            var map = HumanoidTexture.encoded(ByteBuffer.wrap(bytes));
            var texels = map.decode();
            var cloth = 0;
            for (var i = 0; i < map.width() * map.height(); i++) {
                var texel = texel(texels, i);
                assertEquals(255, texel.get(3), "emissive or transparent texel " + i + ": " + texel);
                if (texel.equals(List.of(50, 10, 10, 255))) {
                    cloth++;
                }
            }
            assertTrue(cloth > 0, "no cloth texel survived");
        }
    }

    private static boolean windsAlongNormals(SkinnedMeshProto mesh, it.unimi.dsi.fastutil.ints.IntList triangles,
        int triangle) {
        var corners = new org.joml.Vector3f[3];
        var normals = new org.joml.Vector3f();
        for (var c = 0; c < 3; c++) {
            var vertex = triangles.getInt(triangle * 3 + c);
            corners[c] = new org.joml.Vector3f(mesh.positions().getFloat(vertex * 3),
                mesh.positions().getFloat(vertex * 3 + 1), mesh.positions().getFloat(vertex * 3 + 2));
            normals.add(mesh.normals().getFloat(vertex * 3), mesh.normals().getFloat(vertex * 3 + 1),
                mesh.normals().getFloat(vertex * 3 + 2));
        }
        var face =
            new org.joml.Vector3f(corners[1]).sub(corners[0]).cross(new org.joml.Vector3f(corners[2]).sub(corners[0]));
        return face.dot(normals) > 0f;
    }

    @Test
    void turnsMmdRigidBodiesIntoSpringChainsAndColliders() {
        var source = new HumanoidSource();
        source.addNode("root", -1, 0, 0, 0);
        source.addNode("head", 0, 0, 1.5f, 0);
        source.addNode("hair1", 1, 0.1f, 1.5f, 0);
        source.addNode("hair2", 2, 0.1f, 1.2f, 0);
        source.addNode("hair3", 3, 0.1f, 0.9f, 0);
        var none = MmdPhysics.rotation(0, 0, 0);
        var bodies = List.of(
            new MmdPhysics.Body(2, 1, 0xFFFF, MmdPhysics.CAPSULE, new org.joml.Vector3f(0.05f, 0.2f, 0),
                new org.joml.Vector3f(0.1f, 1.35f, 0), none, 0.5f, true),
            new MmdPhysics.Body(3, 1, 0xFFFF, MmdPhysics.SPHERE, new org.joml.Vector3f(0.04f, 0, 0),
                new org.joml.Vector3f(0.1f, 1.05f, 0), none, 0.5f, true),
            // The head capsule lies along MMD +Z (rotated 90 degrees about X); mirrored it runs along -Z. Its mask
            // excludes the hair group.
            new MmdPhysics.Body(1, 0, 0xFFFD, MmdPhysics.CAPSULE, new org.joml.Vector3f(0.1f, 0.4f, 0),
                new org.joml.Vector3f(0, 1.5f, 0), MmdPhysics.rotation((float)(Math.PI / 2), 0, 0), 0.5f, false),
            new MmdPhysics.Body(0, 2, 0xFFFF, MmdPhysics.SPHERE, new org.joml.Vector3f(0.12f, 0, 0),
                new org.joml.Vector3f(0, 1, 0), none, 0.5f, false));
        MmdPhysics.apply(source, bodies, List.of());

        assertEquals(2, source.springJoints.size());
        var first = source.springJoints.get(0);
        assertEquals(2, first.node());
        assertEquals(new org.joml.Vector3f(0.1f, 1.2f, 0), first.tail());
        // hair3 has no body but is hair2's child, so it ends hair2's swing.
        assertEquals(new org.joml.Vector3f(0.1f, 0.9f, 0), source.springJoints.get(1).tail());
        assertEquals(0.05f, first.hitRadius(), EPSILON);
        // hair2's tail rests sqrt(0.02) from the body sphere's center: its hit sphere may not reach past that surface,
        // or the simulation would push the modeled pose away from the body.
        assertEquals((float)Math.sqrt(0.02) - 0.12f, source.springJoints.get(1).hitRadius(), EPSILON);

        var head = source.springColliders.get(0);
        assertEquals(1, head.node());
        assertFloats(head.head(), 0, 1.5f, 0.2f);
        assertFloats(head.tail(), 0, 1.5f, -0.2f);
        assertEquals(0.1f, head.radius(), EPSILON);
        assertTrue(java.util.Arrays.equals(new int[] {1}, source.springChains.get(first.chain())));
    }

    /**
     * MMD simulates bodies, not bones: an ornament whose bone hangs from the right braid but whose joint ties it to the
     * left braid must follow the left one, and a joint limited to a few degrees keeps the part's modeled shape.
     */
    @Test
    void mmdJointsDecideWhatABoneHangsFromAndHowStiffItIs() {
        var source = new HumanoidSource();
        source.addNode("head", -1, 0, 1.5f, 0);
        source.addNode("braidL", 0, -0.1f, 1.5f, 0);
        source.addNode("braidR", 0, 0.1f, 1.5f, 0);
        source.addNode("ornament", 2, -0.1f, 1.2f, 0);
        source.addNode("ornamentTip", 3, -0.1f, 1.1f, 0);
        source.addNode("braidLTip", 1, -0.1f, 1f, 0);
        var none = MmdPhysics.rotation(0, 0, 0);
        var size = new org.joml.Vector3f(0.03f, 0.1f, 0);
        var bodies = List.of(
            new MmdPhysics.Body(0, 0, 0xFFFF, MmdPhysics.SPHERE, size, new org.joml.Vector3f(0, 1.5f, 0), none, 0.5f,
                false),
            new MmdPhysics.Body(1, 1, 0xFFFF, MmdPhysics.CAPSULE, size, new org.joml.Vector3f(-0.1f, 1.3f, 0), none,
                0.5f, true),
            new MmdPhysics.Body(2, 1, 0xFFFF, MmdPhysics.CAPSULE, size, new org.joml.Vector3f(0.1f, 1.3f, 0), none,
                0.5f, true),
            new MmdPhysics.Body(3, 1, 0xFFFF, MmdPhysics.CAPSULE, size, new org.joml.Vector3f(-0.1f, 1.15f, 0), none,
                0.5f, true));
        var free = new org.joml.Vector3f(1, 1, 1);
        var locked = new org.joml.Vector3f();
        var tight = (float)Math.toRadians(5);
        var joints = List.of(new MmdPhysics.Joint(0, 1, free, locked), new MmdPhysics.Joint(0, 2, free, locked),
            new MmdPhysics.Joint(1, 3, new org.joml.Vector3f(-tight, 0, -tight),
                new org.joml.Vector3f(tight, 0, tight)));
        MmdPhysics.apply(source, bodies, joints);

        assertEquals(1, source.skeletonParents.getInt(3), "the ornament follows the braid its joint ties it to");
        var stiffness = new java.util.HashMap<Integer, Float>();
        for (var joint : source.springJoints) {
            stiffness.put(joint.node(), joint.stiffness());
        }
        // The left braid swings toward its own tip, not toward the ornament it no longer owns in the bone tree.
        assertEquals(new org.joml.Vector3f(-0.1f, 1f, 0),
            source.springJoints.stream().filter(j -> j.node() == 1).findFirst().orElseThrow().tail());
        assertTrue(stiffness.get(3) > 2 * stiffness.get(1), "a 5 degree joint holds its shape: " + stiffness);
        assertEquals(stiffness.get(1), stiffness.get(2), EPSILON);
    }

    /**
     * Generated animations carry Molang conditions (carrying, gun holding, moods) that only fail in game: every string
     * channel value must compile and reference only builtin queries.
     */
    @Test
    void everyGeneratedMolangExpressionCompilesAgainstTheBuiltins() throws Exception {
        var expressions = new java.util.ArrayList<String>();
        try (var importer = HumanoidImport.load("probe.vrm", syntheticVrm(), path -> null)) {
            collectChannelStrings(JsonParser.parseString(importer.animation()), false, expressions);
            collectChannelStrings(JsonParser.parseString(importer.armAnimation()), false, expressions);
        }
        var moods = new JsonObject();
        HumanoidMotion.addExpressions(moods,
            List.of("expression.sad", "expression.aa", "expression.ih", "expression.oh", "expression.relaxed"));
        assertEquals(5, moods.getAsJsonObject(HumanoidMotion.EXPRESSION_LAYER).getAsJsonObject("bones").size());
        collectChannelStrings(moods, false, expressions);
        assertTrue(expressions.stream().anyMatch(value -> value.contains("ctrl.tac_hold_gun")));

        var frontend = new com.elfmcys.ysm.expression.frontend.MolangFrontend();
        for (var expression : expressions) {
            var location = new com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceLocation("humanoid");
            var result = frontend.compile(expression,
                com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceMode.EXPRESSION, location);
            com.elfmcys.ysm.expression.host.BuiltinDeclarations.validateDefaultSource(location, result);
        }
    }

    private static void collectChannelStrings(com.google.gson.JsonElement element, boolean inBones, List<String> out) {
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                // Keyframe interpolation modes (catmullrom, linear) are names, not expressions.
                if (!entry.getKey().equals("lerp_mode")) {
                    collectChannelStrings(entry.getValue(), inBones || entry.getKey().equals("bones"), out);
                }
            }
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(value -> collectChannelStrings(value, inBones, out));
        } else if (inBones && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            out.add(element.getAsString());
        }
    }

    private static void assertFloats(it.unimi.dsi.fastutil.floats.FloatList values, float x, float y, float z) {
        assertEquals(3, values.size());
        assertEquals(x, values.getFloat(0), EPSILON);
        assertEquals(y, values.getFloat(1), EPSILON);
        assertEquals(z, values.getFloat(2), EPSILON);
    }

    private static void assertFloats(org.joml.Vector3f values, float x, float y, float z) {
        assertEquals(x, values.x, EPSILON);
        assertEquals(y, values.y, EPSILON);
        assertEquals(z, values.z, EPSILON);
    }

    private static SpringBonesProto springs(GeoModelProto model) throws IOException {
        var bytes = new byte[model.springs().remaining()];
        model.springs().duplicate().get(bytes);
        return SpringBonesProto.parseFrom(bytes);
    }

    @Test
    void retargetsPmxAPoseWithLowerBodyAndAppendBones() throws IOException {
        try (var importer = HumanoidImport.load("probe.pmx", syntheticPmx(), path -> null)) {
            assertEquals("probe", importer.metadata().name());
            var main = importer.main().model;

            // Hips (センター) is the common ancestor of 上半身 and 下半身 but pivots at 下半身, the waist; 下半身
            // becomes DownBody. 0.08 m per unit; the 0.8 m waist lands at 20 pixels (1.5625 units per meter).
            assertPivot(main, "AllBody", 0, 20f, 0);
            assertEquals("AllBody", bone(main, "UpBody").parent().orElseThrow());
            assertEquals("AllBody", bone(main, "DownBody").parent().orElseThrow());
            assertEquals("DownBody", bone(main, "LeftLeg").parent().orElseThrow());
            assertEquals("左肩", bone(main, "LeftArm").parent().orElseThrow());
            assertEquals("Arm", bone(main, "左肩").parent().orElseThrow());
            // Mirrored from left-handed MMD space and turned to face -Z; the A-pose arm is re-posed straight down.
            assertPivot(main, "LeftArm", -4f, 30f, 0);
            assertPivot(main, "LeftForeArm", -4f, 24f, 0);
            assertPivot(main, "LeftHand", -4f, 18f, 0);
            assertEquals(-1, boneIndex(main, "左足D"));

            var mesh = mesh(main);
            assertVertex(mesh, 0, -0.25f, 1.6875f, 0);
            assertVertex(mesh, 1, -0.25f, 1.5f, 0);
            assertVertex(mesh, 2, -0.125f, 0.875f, 0.0625f);
            // The 足D vertex follows 左足, the bone whose rotation 足D copies; its share on 左足EX, a detail bone under
            // 足D, follows 左足 too because 左足EX hangs from 左足 in the YSM skeleton.
            assertEquals(boneIndex(main, "LeftLeg"), mesh.joints().getInt(8));
            assertEquals(0.5f, mesh.weights().getFloat(8), EPSILON);
            assertEquals(boneIndex(main, "左足EX"), mesh.joints().getInt(9));
            assertEquals("LeftLeg", bone(main, "左足EX").parent().orElseThrow());

            // まばたき becomes blink (driven by Eyelid), 笑い a group morph at half weight becomes happy; MMD units,
            // mirrored and turned like positions.
            assertEquals(2, mesh.morphs().size());
            var blink = mesh.morphs().get(0);
            assertEquals(boneIndex(main, "Eyelid"), blink.bone());
            assertEquals(List.of(2), blink.vertices());
            assertFloats(blink.deltas(), -0.125f, 0, 0.125f);
            var happy = mesh.morphs().get(1);
            assertEquals(boneIndex(main, "expression.happy"), happy.bone());
            assertFloats(happy.deltas(), -0.0625f, 0, 0.0625f);

            // The index finger hangs along -Y beside the body; it rests curled toward the palm (+X for the left
            // hand), 15/25/15 degrees about +Z, and the parallel2 animation grips while the off hand holds an item.
            assertEquals("LeftHand", bone(main, "LeftIndex1").parent().orElseThrow());
            assertFloats(bone(main, "LeftIndex1").rotate(), 0, 0, (float)Math.toRadians(15));
            assertFloats(bone(main, "LeftIndex2").rotate(), 0, 0, (float)Math.toRadians(25));
            assertFloats(bone(main, "LeftIndex3").rotate(), 0, 0, (float)Math.toRadians(15));
            var animations =
                JsonParser.parseString(importer.animation()).getAsJsonObject().getAsJsonObject("animations");
            var index = animations.getAsJsonObject("parallel2").getAsJsonObject("bones").getAsJsonObject("LeftIndex1")
                .getAsJsonArray("rotation");
            assertEquals("0", index.get(0).getAsString());
            // Holding a TACZ gun, the left hand cups the handguard at 45 degrees; a held item makes a fist at 80, and
            // sprinting empty-handed a loose one at 55.
            assertEquals("(ctrl.elytra_fly || ctrl.swim) ? -15.00 : (ctrl.tac_hold_gun ? 30.00 : ((ysm.has_offhand "
                + "|| ctrl.ladder_up || ctrl.ladder_down || ctrl.ladder_stillness) ? 65.00 : "
                + "(ctrl.run ? 40.00 : 0)))", index.get(2).getAsString());
            assertTrue(!animations.has("parallel3"));

            // A held item raises the forearm into a carry; first person plays every parallel animation it is given, so
            // its set holds only the finger grip and never the carry.
            var carry = animations.getAsJsonObject("parallel4").getAsJsonObject("bones").getAsJsonObject("RightForeArm")
                .getAsJsonArray("rotation");
            assertTrue(carry.get(0).getAsString().startsWith("(ysm.has_mainhand && !(ctrl.swim"), carry.toString());
            var armAnimations =
                JsonParser.parseString(importer.armAnimation()).getAsJsonObject().getAsJsonObject("animations");
            assertEquals(java.util.Set.of("parallel2"), armAnimations.keySet());
            // In first person a gun with a fitted grip curls the support fingers up the handguard (40 degrees at the
            // root) and turns the wrists by the grip's angles; any other gun closes the hand into the same fist as a
            // held item: TACZ places the arm for a block arm's end, and only a fist stays inside it.
            var armBones = armAnimations.getAsJsonObject("parallel2").getAsJsonObject("bones");
            assertEquals(
                "(ctrl.elytra_fly || ctrl.swim) ? -15.00 : (ctrl.tac_grip('fitted') ? 25.00 : "
                    + "((ctrl.tac_hold_gun || ysm.has_offhand || ctrl.ladder_up || ctrl.ladder_down "
                    + "|| ctrl.ladder_stillness) ? 65.00 : 0))",
                armBones.getAsJsonObject("LeftIndex1").getAsJsonArray("rotation").get(2).getAsString());
            assertEquals("ctrl.tac_grip('right_y')",
                armBones.getAsJsonObject("RightHand").getAsJsonArray("rotation").get(1).getAsString());
            // Swings play once and end at rest; the swinging arm subtracts the carry layer, which parallel layers add
            // on
            // top, so the arm lands on the swing pose with or without an item.
            var swing = animations.getAsJsonObject("swing_hand");
            assertTrue(!swing.has("loop"));
            var end = swing.getAsJsonObject("bones").getAsJsonObject("RightArm").getAsJsonObject("rotation")
                .getAsJsonArray("0.3750");
            var carrying = carry.get(0).getAsString().replace(" ? -40.0 : 0", "");
            assertEquals("0.00 - (" + carrying + " ? -14.0 : 0)", end.get(0).getAsString());
            assertEquals(0f, end.get(1).getAsFloat(), EPSILON);
        }
    }

    /**
     * Walk and run bounce like a runner, not a walker: the hips are lowest while a foot is planted and highest in the
     * flight between steps, when both feet are off the ground. A planted foot rests on the ground, on its ball once the
     * heel lifts, and while it lies flat its ankle moves back at the speed the body moves forward, so the foot does not
     * slide. Sneaking keeps a foot planted at every moment. For this model's 8-pixel thigh and shin.
     */
    @Test
    void runsBounceOnThePlantedLegAndFlyBetweenSteps() throws IOException {
        try (var importer = HumanoidImport.load("probe.pmx", syntheticPmx(), path -> null)) {
            var animations =
                JsonParser.parseString(importer.animation()).getAsJsonObject().getAsJsonObject("animations");
            var blocksPerPixel = importer.main().heightScale / 16f;
            var speeds = java.util.Map.of("walk", 4.317f, "run", 5.612f, "sneak", 1.295f);
            for (var name : List.of("walk", "run", "sneak")) {
                var bones = animations.getAsJsonObject(name).getAsJsonObject("bones");
                var root = bones.getAsJsonObject("Root").getAsJsonObject("position");
                var times = new java.util.ArrayList<>(root.keySet());
                var highest = 0;
                var lowest = 0;
                var flights = 0;
                var heights = new float[times.size()];
                var feet = new float[times.size()];
                for (var i = 0; i < times.size(); i++) {
                    var time = times.get(i);
                    heights[i] = 16f + root.getAsJsonArray(time).get(1).getAsFloat();
                    feet[i] = Math.min(footHeight(bones, "Left", time, heights[i]),
                        footHeight(bones, "Right", time, heights[i]));
                    assertTrue(feet[i] > -0.03f, name + " at " + time + ": foot " + feet[i] + " below ground");
                    highest = heights[i] > heights[highest] ? i : highest;
                    lowest = heights[i] < heights[lowest] ? i : lowest;
                    flights += feet[i] > 0.5f ? 1 : 0;
                }
                if (name.equals("sneak")) {
                    assertEquals(0, flights, "sneak never leaves the ground");
                    continue;
                }
                assertTrue(flights >= 2, name + " flies between steps");
                assertTrue(feet[highest] > 0.5f, name + " is highest in flight, at " + times.get(highest));
                assertTrue(feet[lowest] < 0.03f, name + " is lowest on a planted foot, at " + times.get(lowest));
                // A running bounce of 3 to 5 percent of the body's height (16 px of leg in a 2-block, 32 px body).
                var bounce = heights[highest] - heights[lowest];
                assertTrue(bounce > 0.9f && bounce < 1.6f, name + " bounces " + bounce);

                // The left foot lands at time zero: while it lies flat, the ankle moves back at the body's speed.
                var step = Float.parseFloat(times.get(1));
                var flat = 0;
                for (var i = 0; i + 1 < times.size(); i++) {
                    var from = heights[i] - legDrop(bones, "Left", times.get(i));
                    var to = heights[i + 1] - legDrop(bones, "Left", times.get(i + 1));
                    if (Math.abs(from) > 0.03f || Math.abs(to) > 0.03f) {
                        break;
                    }
                    var speed = (legReach(bones, "Left", times.get(i)) - legReach(bones, "Left", times.get(i + 1)))
                        * blocksPerPixel / step;
                    assertEquals(speeds.get(name), speed, speeds.get(name) * 0.04f, name + " at " + times.get(i));
                    flat++;
                }
                assertTrue(flat >= 3, name + " plants the foot for " + flat + " frames");
            }

            var sneaking = animations.getAsJsonObject("sneaking").getAsJsonObject("bones");
            var left = legDrop(sneaking, "Left", null);
            assertEquals(left, legDrop(sneaking, "Right", null), 0.02f);
            assertEquals(left - 16f, sneaking.getAsJsonObject("Root").getAsJsonArray("position").get(1).getAsFloat(),
                0.02f);
            assertTrue(left < 14f, "sneaking crouches");
            for (var side : List.of("Left", "Right")) {
                // Level feet: the leg's X angles cancel.
                assertEquals(0f, x(sneaking, side + "Leg", null) + x(sneaking, side + "LowerLeg", null)
                    + x(sneaking, side + "Foot", null), 0.02f);
            }
        }
    }

    /**
     * Walking backward is forward walking in reverse: at every keyframe the legs take the pose the forward walk has as
     * far from the end of its cycle, so the planted foot sweeps forward under a body moving back. The trunk leans less.
     * The gun variants keep only the legs: the gun clips lean the trunk and pose the arms themselves, and channels of
     * both would add up into a doubled lean.
     */
    @Test
    void backwardGaitsReverseTheForwardOnes() throws IOException {
        try (var importer = HumanoidImport.load("probe.pmx", syntheticPmx(), path -> null)) {
            var animations =
                JsonParser.parseString(importer.animation()).getAsJsonObject().getAsJsonObject("animations");
            for (var pair : List.of(List.of("walk", "walk_back"), List.of("sneak", "sneak_back"))) {
                var forward = animations.getAsJsonObject(pair.get(0)).getAsJsonObject("bones");
                var backward = animations.getAsJsonObject(pair.get(1)).getAsJsonObject("bones");
                var times =
                    new java.util.ArrayList<>(forward.getAsJsonObject("LeftLeg").getAsJsonObject("rotation").keySet());
                var length = Float.parseFloat(times.get(times.size() - 1));
                for (var time : times) {
                    var mirrored = String.format(java.util.Locale.ROOT, "%.4f", length - Float.parseFloat(time));
                    for (var bone : List.of("LeftLeg", "RightLowerLeg", "LeftFoot")) {
                        assertEquals(x(forward, bone, mirrored), x(backward, bone, time), 0.011f,
                            pair.get(1) + " " + bone + " at " + time);
                    }
                }
                assertTrue(x(backward, "UpBody", times.get(0)) < x(forward, "UpBody", times.get(0)), pair.get(1));
            }
            for (var name : List.of("tac:walk", "tac:walk_back", "tac:run")) {
                var gun = animations.getAsJsonObject(name).getAsJsonObject("bones");
                assertEquals(java.util.Set.of("Root", "DownBody", "LeftLeg", "LeftLowerLeg", "LeftFoot", "RightLeg",
                    "RightLowerLeg", "RightFoot"), gun.keySet(), name);
            }
        }
    }

    /**
     * Both slides sit this model on the ground: the leading heel and the trailing leg's lowest point (the shin lying
     * behind when crouched, the foot when reclined) end exactly at the height the root drops the hips to, and the trunk
     * meets each thigh at an obtuse angle rather than folding over the legs.
     */
    @Test
    void slidesSitOnTheGround() throws IOException {
        try (var importer = HumanoidImport.load("probe.pmx", syntheticPmx(), path -> null)) {
            var animations =
                JsonParser.parseString(importer.animation()).getAsJsonObject().getAsJsonObject("animations");
            for (var name : List.of("bfm:slide_crouch", "bfm:slide")) {
                var bones = animations.getAsJsonObject(name).getAsJsonObject("bones");
                var time = "0.0000";
                var hips =
                    bones.getAsJsonObject("Root").getAsJsonObject("position").getAsJsonArray(time).get(1).getAsFloat()
                        + 16f;
                // The pelvis turns back with the trunk; thigh angles from straight down, forward positive.
                var back = -x(bones, "AllBody", time);
                for (var side : List.of("Left", "Right")) {
                    var thigh = back - x(bones, side + "Leg", time);
                    var knee = x(bones, side + "LowerLeg", time);
                    var kneeDepth = 8f * (float)Math.cos(Math.toRadians(thigh));
                    var ankleDepth = kneeDepth + 8f * (float)Math.cos(Math.toRadians(thigh - knee));
                    assertEquals(hips, Math.max(kneeDepth, ankleDepth), 0.05f, name + " " + side);
                    assertTrue(180f - thigh + back > 100f, name + " " + side + " trunk to thigh");
                }
            }
        }
    }

    /** Hip-to-ankle height of an 8-pixel thigh and shin: hip flexion is the leg's negated X, knee flexion its X. */
    private static float legDrop(JsonObject bones, String side, String time) {
        var hip = -x(bones, side + "Leg", time);
        var knee = x(bones, side + "LowerLeg", time);
        return 8f * (float)(Math.cos(Math.toRadians(hip)) + Math.cos(Math.toRadians(hip - knee)));
    }

    /** How far ahead of the hip the ankle of an 8-pixel thigh and shin lies. */
    private static float legReach(JsonObject bones, String side, String time) {
        var hip = -x(bones, side + "Leg", time);
        var knee = x(bones, side + "LowerLeg", time);
        return 8f * (float)(Math.sin(Math.toRadians(hip)) + Math.sin(Math.toRadians(hip - knee)));
    }

    /**
     * Height of the lowest point of a foot under hips at the given height: the ankle, or the ball of the foot (0.17 of
     * the leg ahead of the ankle) once the foot pitches toes down. The foot's pitch from level is the sum of the leg's
     * X angles.
     */
    private static float footHeight(JsonObject bones, String side, String time, float hips) {
        var pitch = x(bones, side + "Leg", time) + x(bones, side + "LowerLeg", time) + x(bones, side + "Foot", time);
        return hips - legDrop(bones, side, time) - 0.17f * 16f * (float)Math.sin(Math.toRadians(Math.max(pitch, 0f)));
    }

    private static float x(JsonObject bones, String bone, String time) {
        var rotation = bones.getAsJsonObject(bone).get("rotation");
        var value = time == null ? rotation.getAsJsonArray() : rotation.getAsJsonObject().getAsJsonArray(time);
        return value.get(0).getAsFloat();
    }

    /**
     * A real conversion compiles the generated animations through the Molang frontend, and a smaller sibling model (a
     * prop shipped beside the character, here not even a valid PMX) does not stop the directory from being a model.
     */
    @Test
    void humanoidDirectoryConvertsWithGeneratedAnimations(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root,
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path output) throws Exception {
        var bytes = syntheticPmx();
        var array = new byte[bytes.remaining()];
        bytes.get(array);
        java.nio.file.Files.write(root.resolve("probe.pmx"), array);
        java.nio.file.Files.write(root.resolve("武器.pmx"), new byte[] {'P', 'M', 'X', ' '});
        try (var vfs = new com.elfmcys.ysm.format.vfs.Directory(root)) {
            var converted =
                ModelParser.parse(vfs, output, DefaultAnimationFilter.keepAll(), (source, mode, location, result) -> {
                });
            assertTrue(java.nio.file.Files.size(converted) > 0);
        }
    }

    /**
     * The game captures a source before converting it, and the conversion may only read what capture read: the PMX's
     * external texture (in a subfolder, written with a backslash and a non-ASCII name) and the humanoid.json beside it
     * must be read during capture, or conversion fails with "File was not read during capture".
     */
    @Test
    void capturedHumanoidDirectoryConvertsWithExternalTextureAndOverride(
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        var root = java.nio.file.Files.createDirectories(temporary.resolve("model"));
        var bytes = syntheticPmx("tex\\颜.bmp");
        var array = new byte[bytes.remaining()];
        bytes.get(array);
        java.nio.file.Files.write(root.resolve("probe.pmx"), array);
        var bmp = ByteBuffer.allocate(54 + 4).order(ByteOrder.LITTLE_ENDIAN);
        bmp.put((byte)'B').put((byte)'M').putInt(58).putInt(0).putInt(54);
        bmp.putInt(40).putInt(1).putInt(1).putShort((short)1).putShort((short)24).putInt(0).putInt(4).putInt(0)
            .putInt(0).putInt(0).putInt(0);
        bmp.put(new byte[] {(byte)255, 0, 0, 0});
        java.nio.file.Files.createDirectories(root.resolve("tex"));
        java.nio.file.Files.write(root.resolve("tex").resolve("颜.bmp"), bmp.array());
        java.nio.file.Files.writeString(root.resolve("humanoid.json"), "{\"hips\": \"センター\"}");

        var importer = new com.elfmcys.ysm.model.catalog.RawModelImporter(DefaultAnimationFilter.keepAll());
        try (var captured = importer.capture(root)) {
            var result = importer.convert(captured, temporary.resolve("converted"));
            assertTrue(java.nio.file.Files.size(result.stagedContainer()) > 0);
        }
    }

    @Test
    void guessesFingerChainsFromBipedAndMixamoNames() throws IOException {
        var source = new HumanoidSource();
        var hips = source.addNode("hips", -1, 0, 1, 0);
        var spine = source.addNode("spine", hips, 0, 1.1f, 0);
        source.humanoid.put("hips", hips);
        source.humanoid.put("spine", spine);
        source.humanoid.put("leftUpperLeg", source.addNode("leftUpperLeg", hips, 0.1f, 0.9f, 0));
        source.humanoid.put("rightUpperLeg", source.addNode("rightUpperLeg", hips, -0.1f, 0.9f, 0));
        var leftHand = source.addNode("Bip01 L Hand", spine, 0.6f, 1.4f, 0);
        var rightHand = source.addNode("mixamorig:RightHand", spine, -0.6f, 1.4f, 0);
        source.humanoid.put("leftHand", leftHand);
        source.humanoid.put("rightHand", rightHand);
        // Biped counts from the thumb (Finger0); "Nub" end bones are not segments.
        var thumb = source.addNode("Bip01 L Finger0", leftHand, 0.65f, 1.4f, 0.02f);
        var thumb2 = source.addNode("Bip01 L Finger01", thumb, 0.68f, 1.4f, 0.03f);
        var index = source.addNode("Bip01 L Finger1", leftHand, 0.7f, 1.4f, 0);
        source.addNode("Bip01 L Finger1Nub", index, 0.75f, 1.4f, 0);
        var rightThumb = source.addNode("mixamorig:RightHandThumb1", rightHand, -0.65f, 1.4f, 0.02f);
        var rightIndex = source.addNode("mixamorig:RightHandIndex1", rightHand, -0.7f, 1.4f, 0);
        var rightIndex2 = source.addNode("mixamorig:RightHandIndex2", rightIndex, -0.73f, 1.4f, 0);
        var rightPinky = source.addNode("mixamorig:RightHandPinky1", rightHand, -0.7f, 1.4f, -0.02f);

        HumanoidMapping.complete(source);

        assertEquals(thumb, source.humanoid.get("leftThumb1"));
        assertEquals(thumb2, source.humanoid.get("leftThumb2"));
        assertEquals(index, source.humanoid.get("leftIndex1"));
        assertTrue(!source.humanoid.containsKey("leftIndex2"));
        assertEquals(rightThumb, source.humanoid.get("rightThumb1"));
        assertEquals(rightIndex, source.humanoid.get("rightIndex1"));
        assertEquals(rightIndex2, source.humanoid.get("rightIndex2"));
        assertEquals(rightPinky, source.humanoid.get("rightLittle1"));
    }

    @Test
    void decodesBmpTgaAndDxt1TopRowFirst() throws IOException {
        // 1x2 images: top pixel red, bottom pixel blue, both stored bottom-up.
        var bmp = ByteBuffer.allocate(54 + 8).order(ByteOrder.LITTLE_ENDIAN);
        bmp.put((byte)'B').put((byte)'M').putInt(62).putInt(0).putInt(54);
        bmp.putInt(40).putInt(1).putInt(2).putShort((short)1).putShort((short)24).putInt(0).putInt(8).putInt(0)
            .putInt(0).putInt(0).putInt(0);
        bmp.put(new byte[] {(byte)255, 0, 0, 0}).put(new byte[] {0, 0, (byte)255, 0});
        assertTopRedBottomBlue(LegacyImages.decode(bmp.flip(), "a.bmp"));

        var tga = ByteBuffer.allocate(18 + 6).order(ByteOrder.LITTLE_ENDIAN);
        tga.put((byte)0).put((byte)0).put((byte)2).put(new byte[5]).putShort((short)0).putShort((short)0)
            .putShort((short)1).putShort((short)2).put((byte)24).put((byte)0);
        tga.put(new byte[] {(byte)255, 0, 0}).put(new byte[] {0, 0, (byte)255});
        assertTopRedBottomBlue(LegacyImages.decode(tga.flip(), "a.tga"));

        var dds = ByteBuffer.allocate(128 + 8).order(ByteOrder.LITTLE_ENDIAN);
        dds.put((byte)'D').put((byte)'D').put((byte)'S').put((byte)' ');
        dds.putInt(4, 124).putInt(12, 4).putInt(16, 4).putInt(80, 0x4).putInt(84, 0x31545844);
        // c0 = red, c1 = blue; row 0 selects c0, rows 1-3 select c1.
        dds.putShort(128, (short)0xF800).putShort(130, (short)0x001F).putInt(132, 0x55555500);
        var texels = LegacyImages.decode(dds.position(0), "a.dds").decode();
        assertEquals(List.of(255, 0, 0, 255), texel(texels, 0));
        assertEquals(List.of(0, 0, 255, 255), texel(texels, 4));
    }

    @Test
    void retargetsXnaLaraAsciiWithPelvisAndClockwiseFaces() throws IOException {
        var requested = new java.util.ArrayList<String>();
        try (
            var importer = HumanoidImport.load("model/probe.mesh.ascii", xpsAscii(NAMES_XNA, PARENTS_XNA, 1f), path -> {
                requested.add(path);
                return null;
            })) {
            assertEquals("probe", importer.metadata().name());
            var main = importer.main().model;
            // "root hips" is hips; "pelvis" carries both thighs but not the spine, so it becomes DownBody and
            // hips pivots there.
            assertPivot(main, "AllBody", 0, 20, 0);
            assertEquals("AllBody", bone(main, "DownBody").parent().orElseThrow());
            assertEquals("DownBody", bone(main, "LeftLeg").parent().orElseThrow());
            assertEquals("arm left shoulder 1", bone(main, "LeftArm").parent().orElseThrow());
            assertXpsPose(main);
            // Stored clockwise (0 1 2)(2 3 4) and renumbered by first use (the thigh vertex comes second); every face
            // then winds counter-clockwise along its normals, whatever order the file stored.
            var mesh = mesh(main);
            assertEquals(java.util.Set.of(0, 1, 2), java.util.Set.copyOf(mesh.triangles().subList(0, 3)));
            assertEquals(java.util.Set.of(1, 3, 4), java.util.Set.copyOf(mesh.triangles().subList(3, 6)));
            assertTrue(windsAlongNormals(mesh, mesh.triangles(), 0));
            assertTrue(windsAlongNormals(mesh, mesh.triangles(), 1));
            // Textures resolve by file name beside the model, whatever directory the file recorded.
            assertEquals(List.of("Body.TGA", HumanoidMapping.OVERRIDE_FILE), requested);
        }
    }

    @Test
    void readsXpsV3BinaryWithVariableWeightsInCentimeters() throws IOException {
        try (var importer = HumanoidImport.load("probe.xps", xpsBinary(), path -> null)) {
            var main = importer.main().model;
            // Bip01 Pelvis is hips and parents the thighs directly, so DownBody is synthesized at hips. 170 units
            // tall: rescaled to meters.
            assertPivot(main, "AllBody", 0, 20, 0);
            assertPivot(main, "DownBody", 0, 20, 0);
            assertEquals("DownBody", bone(main, "LeftLeg").parent().orElseThrow());
            assertEquals("Bip01 L Clavicle", bone(main, "LeftArm").parent().orElseThrow());
            assertXpsPose(main);
            // Five influences: the strongest four kept and renormalized (0.5 / 0.9 for the thigh).
            var mesh = mesh(main);
            var thigh = boneIndex(main, "LeftLeg");
            var found = false;
            for (var slot = 0; slot < 4; slot++) {
                if (mesh.joints().getInt(4 + slot) == thigh) {
                    assertEquals(0.5f / 0.9f, mesh.weights().getFloat(4 + slot), EPSILON);
                    found = true;
                }
            }
            assertTrue(found);
        }
    }

    @Test
    void guessesMixamoAndUnrealBones() {
        var mixamo = source(
            new String[] {"mixamorig:Hips", "mixamorig:Spine", "mixamorig:Spine1", "mixamorig:Neck", "mixamorig:Head",
                "mixamorig:HeadTop_End", "mixamorig:LeftShoulder", "mixamorig:LeftArm", "mixamorig:LeftForeArm",
                "mixamorig:LeftHand", "mixamorig:LeftHandIndex1", "mixamorig:LeftUpLeg", "mixamorig:LeftLeg",
                "mixamorig:LeftFoot", "mixamorig:LeftToeBase", "mixamorig:RightUpLeg"},
            new int[] {-1, 0, 1, 2, 3, 4, 2, 6, 7, 8, 9, 0, 11, 12, 13, 0});
        HumanoidMapping.guessFromNames(mixamo);
        assertMapped(mixamo, "hips", "mixamorig:Hips", "spine", "mixamorig:Spine", "neck", "mixamorig:Neck", "head",
            "mixamorig:Head", "leftShoulder", "mixamorig:LeftShoulder", "leftUpperArm", "mixamorig:LeftArm",
            "leftLowerArm", "mixamorig:LeftForeArm", "leftHand", "mixamorig:LeftHand", "leftUpperLeg",
            "mixamorig:LeftUpLeg", "leftLowerLeg", "mixamorig:LeftLeg", "leftFoot", "mixamorig:LeftFoot",
            "rightUpperLeg", "mixamorig:RightUpLeg");

        var unreal = source(new String[] {"root", "pelvis", "spine_01", "spine_02", "neck_01", "head", "clavicle_l",
            "upperarm_l", "upperarm_twist_01_l", "lowerarm_l", "hand_l", "thigh_l", "calf_l", "foot_l", "ball_l",
            "ik_hand_l", "thigh_r"}, new int[] {-1, 0, 1, 2, 3, 4, 3, 6, 7, 7, 9, 1, 11, 12, 13, 0, 1});
        HumanoidMapping.guessFromNames(unreal);
        assertMapped(unreal, "hips", "pelvis", "spine", "spine_01", "neck", "neck_01", "head", "head", "leftShoulder",
            "clavicle_l", "leftUpperArm", "upperarm_l", "leftLowerArm", "lowerarm_l", "leftHand", "hand_l",
            "leftUpperLeg", "thigh_l", "leftLowerLeg", "calf_l", "leftFoot", "foot_l", "rightUpperLeg", "thigh_r");
    }

    @Test
    void unknownBoneNamesNeedHumanoidJson() throws IOException {
        var names = new String[NAMES_XNA.length];
        for (var i = 0; i < names.length; i++) {
            names[i] = "b" + i;
        }
        var model = xpsAscii(names, PARENTS_XNA, 1f);
        var error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
            () -> HumanoidImport.load("probe.mesh.ascii", model.duplicate(), path -> null));
        assertTrue(error.getMessage().contains(HumanoidMapping.OVERRIDE_FILE), error.getMessage());

        var json = """
            {"spine": "b3", "neck": "b5", "head": "b6", "leftShoulder": "b7", "leftUpperArm": "b8",
             "leftLowerArm": "b9", "leftHand": "b10", "rightUpperArm": "b12", "rightLowerArm": "b13",
             "rightHand": "b14", "leftUpperLeg": "b15", "leftLowerLeg": "b16", "leftFoot": "b17",
             "rightUpperLeg": "b19", "rightLowerLeg": "b20", "rightFoot": "b21"}""";
        try (var importer = HumanoidImport.load("probe.mesh.ascii", model.duplicate(),
            path -> path.equals(HumanoidMapping.OVERRIDE_FILE) ? StandardCharsets.UTF_8.encode(json) : null)) {
            var main = importer.main().model;
            // Hips derived as the common ancestor of spine and thighs (b1), pelvis b2 as DownBody.
            assertEquals("AllBody", bone(main, "DownBody").parent().orElseThrow());
            assertEquals("b7", bone(main, "LeftArm").parent().orElseThrow());
            assertXpsPose(main);
        }
    }

    /** T-pose arms re-posed down and the +Z-facing model turned to face -Z (left side at -X). */
    private static void assertXpsPose(GeoModelProto main) throws IOException {
        // Hips at 1 m: 1.25 model units per meter.
        assertPivot(main, "LeftArm", -4f, 28f, 0);
        assertPivot(main, "LeftForeArm", -4f, 23f, 0);
        var mesh = mesh(main);
        assertVertex(mesh, 0, -0.25f, 1.625f, 0);
        assertVertex(mesh, 1, -0.125f, 0.875f, 0);
        assertVertex(mesh, 2, -0.25f, 1.375f, 0);
    }

    private static HumanoidSource source(String[] names, int[] parents) {
        var source = new HumanoidSource();
        for (var i = 0; i < names.length; i++) {
            source.addNode(names[i], parents[i], 0, 0, 0);
        }
        return source;
    }

    private static void assertMapped(HumanoidSource source, String... expected) {
        for (var i = 0; i < expected.length; i += 2) {
            var node = source.humanoid.get(expected[i]);
            assertEquals(expected[i + 1], node == null ? null : source.nodeNames.get(node), expected[i]);
        }
    }

    private static void assertTopRedBottomBlue(HumanoidTexture texture) throws IOException {
        assertEquals(1, texture.width());
        assertEquals(2, texture.height());
        var texels = texture.decode();
        assertEquals(List.of(255, 0, 0, 255), texel(texels, 0));
        assertEquals(List.of(0, 0, 255, 255), texel(texels, 1));
    }

    private static List<Integer> texel(ByteBuffer texels, int index) {
        var at = texels.position() + index * 4;
        return List.of(Byte.toUnsignedInt(texels.get(at)), Byte.toUnsignedInt(texels.get(at + 1)),
            Byte.toUnsignedInt(texels.get(at + 2)), Byte.toUnsignedInt(texels.get(at + 3)));
    }

    private static BoneProto bone(GeoModelProto model, String name) {
        for (var bone : model.bones()) {
            if (bone.name().equals(name)) {
                return bone;
            }
        }
        throw new AssertionError("Missing bone " + name);
    }

    /** Whether the named bone has the ancestor somewhere up its parent chain. */
    private static boolean descends(GeoModelProto model, String name, String ancestor) {
        for (var current = bone(model, name).parent(); current.isPresent();
            current = bone(model, current.get()).parent()) {
            if (current.get().equals(ancestor)) {
                return true;
            }
        }
        return false;
    }

    private static int boneIndex(GeoModelProto model, String name) {
        var index = 0;
        for (var bone : model.bones()) {
            if (bone.name().equals(name)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static void assertPivot(GeoModelProto model, String name, float x, float y, float z) {
        var pivot = bone(model, name).pivot();
        assertEquals(x, pivot.getFloat(0), EPSILON, name + " x");
        assertEquals(y, pivot.getFloat(1), EPSILON, name + " y");
        assertEquals(z, pivot.getFloat(2), EPSILON, name + " z");
    }

    private static SkinnedMeshProto mesh(GeoModelProto model) throws IOException {
        var bytes = new byte[model.mesh().remaining()];
        model.mesh().duplicate().get(bytes);
        return SkinnedMeshProto.parseFrom(bytes);
    }

    private static void assertVertex(SkinnedMeshProto mesh, int vertex, float x, float y, float z) {
        assertEquals(x, mesh.positions().getFloat(vertex * 3), EPSILON, "vertex " + vertex + " x");
        assertEquals(y, mesh.positions().getFloat(vertex * 3 + 1), EPSILON, "vertex " + vertex + " y");
        assertEquals(z, mesh.positions().getFloat(vertex * 3 + 2), EPSILON, "vertex " + vertex + " z");
    }

    static ByteBuffer syntheticPmx() {
        return syntheticPmx(null);
    }

    /**
     * One triangle: upper arm, lower arm and the 足D-weighted thigh each own one vertex; the material uses the given
     * external texture path when one is given.
     */
    private static ByteBuffer syntheticPmx(@org.jetbrains.annotations.Nullable String texture) {
        var out = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
        out.put("PMX ".getBytes(StandardCharsets.US_ASCII)).putFloat(2.0f).put((byte)8);
        out.put(new byte[] {1, 0, 4, 4, 4, 4, 4, 4});
        pmxText(out, "probe");
        pmxText(out, "");
        pmxText(out, "");
        pmxText(out, "");
        float[][] vertices = {{2 + D / 2, 15 - D / 2, 0}, {2 + D, 15 - D, 0}, {1, 7, 0.5f}};
        int[] bones = {6, 7, 20};
        out.putInt(3);
        for (var v = 0; v < 3; v++) {
            out.putFloat(vertices[v][0]).putFloat(vertices[v][1]).putFloat(vertices[v][2]);
            out.putFloat(0).putFloat(0).putFloat(-1).putFloat(0).putFloat(0);
            if (v == 2) {
                // BDEF2: half on 左足D, half on the detail bone 左足EX below it; then the edge scale.
                out.put((byte)1).putInt(20).putInt(24).putFloat(0.5f).putFloat(1);
            } else {
                out.put((byte)0).putInt(bones[v]).putFloat(1);
            }
        }
        out.putInt(3).putInt(0).putInt(1).putInt(2);
        if (texture == null) {
            out.putInt(0);
        } else {
            out.putInt(1);
            pmxText(out, texture);
        }
        out.putInt(1);
        pmxText(out, "material");
        pmxText(out, "");
        out.putFloat(1).putFloat(1).putFloat(1).putFloat(1);
        out.put(new byte[12 + 4 + 12]).put((byte)0).put(new byte[16 + 4]);
        out.putInt(texture == null ? -1 : 0).putInt(-1).put((byte)0).put((byte)1).put((byte)0);
        pmxText(out, "");
        out.putInt(3);
        out.putInt(NAMES_PMX.length);
        for (var i = 0; i < NAMES_PMX.length; i++) {
            pmxText(out, NAMES_PMX[i]);
            pmxText(out, "");
            out.putFloat(POSITIONS_PMX[i][0]).putFloat(POSITIONS_PMX[i][1]).putFloat(POSITIONS_PMX[i][2]);
            out.putInt(PARENTS_PMX[i]).putInt(0);
            var append = NAMES_PMX[i].equals("左足D");
            out.putShort((short)(0x001A | (append ? 0x0100 : 0)));
            out.putFloat(0).putFloat(0).putFloat(0);
            if (append) {
                out.putInt(14).putFloat(1f);
            }
        }
        // Morphs: vertex morph まばたき moves the thigh vertex; group morph 笑い applies half of it.
        out.putInt(2);
        pmxText(out, "まばたき");
        pmxText(out, "");
        out.put((byte)1).put((byte)1).putInt(1);
        out.putInt(2).putFloat(1).putFloat(0).putFloat(1);
        pmxText(out, "笑い");
        pmxText(out, "");
        out.put((byte)3).put((byte)0).putInt(1);
        out.putInt(0).putFloat(0.5f);
        out.putInt(0); // display frames
        out.putInt(0); // rigid bodies
        return ByteBuffer.allocateDirect(out.position()).put(out.flip()).flip();
    }

    private static void pmxText(ByteBuffer out, String text) {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        out.putInt(bytes.length).put(bytes);
    }

    /** One triangle on the left arm: upper arm, lower arm and the constrained sleeve each own one vertex. */
    private static ByteBuffer syntheticVrm() {
        return syntheticVrm(null);
    }

    /** @param materials a glTF materials array for the mesh's one primitive (which uses material 0), or null */
    private static ByteBuffer syntheticVrm(@org.jetbrains.annotations.Nullable String materials) {
        var joints = new int[NAMES.length - 1];
        for (var i = 0; i < joints.length; i++) {
            joints[i] = i + 1;
        }
        float[] positions = {0.3f, 1.4f, 0, 0.6f, 1.4f, 0, 0.3f, 1.45f, 0};
        float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1};
        short[] vertexJoints = {3, 0, 0, 0, 4, 0, 0, 0, SLEEVE - 1, 0, 0, 0};
        float[] weights = {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};
        short[] indices = {0, 1, 2, 0};

        var bin = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        var views = new JsonArray();
        var accessors = new JsonArray();
        addFloats(bin, views, accessors, positions, "VEC3", 3);
        addFloats(bin, views, accessors, normals, "VEC3", 3);
        var jointsView = bin.position();
        for (var value : vertexJoints) {
            bin.putShort(value);
        }
        addAccessor(views, accessors, jointsView, bin.position() - jointsView, 5123, 3, "VEC4");
        addFloats(bin, views, accessors, weights, "VEC4", 3);
        var indexView = bin.position();
        for (var value : indices) {
            bin.putShort(value);
        }
        addAccessor(views, accessors, indexView, 6, 5123, 3, "SCALAR");
        var inverseBind = new float[joints.length * 16];
        for (var i = 0; i < joints.length; i++) {
            var p = POSITIONS[joints[i]];
            inverseBind[i * 16] = inverseBind[i * 16 + 5] = inverseBind[i * 16 + 10] = inverseBind[i * 16 + 15] = 1;
            inverseBind[i * 16 + 12] = -p[0];
            inverseBind[i * 16 + 13] = -p[1];
            inverseBind[i * 16 + 14] = -p[2];
        }
        addFloats(bin, views, accessors, inverseBind, "MAT4", joints.length);
        // Morph target 0 (accessor 6) lifts the upper-arm vertex 0.1 m in glTF space.
        addFloats(bin, views, accessors, new float[] {0, 0.1f, 0, 0, 0, 0, 0, 0, 0}, "VEC3", 3);

        var json = new JsonObject();
        var asset = new JsonObject();
        asset.addProperty("version", "2.0");
        json.add("asset", asset);
        var nodes = new JsonArray();
        for (var i = 0; i < NAMES.length; i++) {
            var node = new JsonObject();
            node.addProperty("name", NAMES[i]);
            var translation = new JsonArray();
            for (var axis = 0; axis < 3; axis++) {
                translation.add(POSITIONS[i][axis] - (PARENTS[i] < 0 ? 0 : POSITIONS[PARENTS[i]][axis]));
            }
            node.add("translation", translation);
            var children = new JsonArray();
            for (var child = 0; child < NAMES.length; child++) {
                if (PARENTS[child] == i) {
                    children.add(child);
                }
            }
            if (!children.isEmpty()) {
                node.add("children", children);
            }
            nodes.add(node);
        }
        nodes.get(0).getAsJsonObject().addProperty("mesh", 0);
        nodes.get(0).getAsJsonObject().addProperty("skin", 0);
        var aim = new JsonObject();
        aim.addProperty("source", 5);
        var constraint = new JsonObject();
        constraint.add("aim", aim);
        var constraintExtension = new JsonObject();
        constraintExtension.add("constraint", constraint);
        var nodeExtensions = new JsonObject();
        nodeExtensions.add("VRMC_node_constraint", constraintExtension);
        nodes.get(SLEEVE).getAsJsonObject().add("extensions", nodeExtensions);
        json.add("nodes", nodes);

        var skin = new JsonObject();
        var jointArray = new JsonArray();
        for (var joint : joints) {
            jointArray.add(joint);
        }
        skin.add("joints", jointArray);
        skin.addProperty("inverseBindMatrices", 5);
        var skins = new JsonArray();
        skins.add(skin);
        json.add("skins", skins);

        var attributes = new JsonObject();
        attributes.addProperty("POSITION", 0);
        attributes.addProperty("NORMAL", 1);
        attributes.addProperty("JOINTS_0", 2);
        attributes.addProperty("WEIGHTS_0", 3);
        var primitive = new JsonObject();
        primitive.add("attributes", attributes);
        primitive.addProperty("indices", 4);
        primitive.add("targets", JsonParser.parseString("[{\"POSITION\": 6}]"));
        if (materials != null) {
            primitive.addProperty("material", 0);
            json.add("materials", JsonParser.parseString(materials));
        }
        var primitives = new JsonArray();
        primitives.add(primitive);
        var mesh = new JsonObject();
        mesh.add("primitives", primitives);
        var meshes = new JsonArray();
        meshes.add(mesh);
        json.add("meshes", meshes);
        json.add("accessors", accessors);
        json.add("bufferViews", views);
        var buffer = new JsonObject();
        buffer.addProperty("byteLength", bin.position());
        var buffers = new JsonArray();
        buffers.add(buffer);
        json.add("buffers", buffers);

        var humanBones = new JsonObject();
        for (var i = 1; i < NAMES.length - 1; i++) {
            var reference = new JsonObject();
            reference.addProperty("node", i);
            humanBones.add(NAMES[i], reference);
        }
        var humanoid = new JsonObject();
        humanoid.add("humanBones", humanBones);
        var meta = new JsonObject();
        meta.addProperty("name", "probe");
        var vrm = new JsonObject();
        vrm.add("humanoid", humanoid);
        vrm.add("meta", meta);
        vrm.add("expressions", JsonParser.parseString("""
            {"preset": {"blink": {"morphTargetBinds": [{"node": 0, "index": 0, "weight": 1.0}]}},
             "custom": {"wink": {"morphTargetBinds": [{"node": 0, "index": 0, "weight": 0.5}]}}}
            """));
        var extensions = new JsonObject();
        extensions.add("VRMC_vrm", vrm);
        extensions.add("VRMC_springBone", JsonParser.parseString("""
            {"specVersion": "1.0",
             "colliders": [{"node": 3, "shape": {"sphere": {"offset": [0, 0.1, 0], "radius": 0.1}}}],
             "colliderGroups": [{"colliders": [0]}],
             "springs": [{"joints": [{"node": %d, "hitRadius": 0.02}, {"node": 5}], "colliderGroups": [0]}]}
            """.formatted(SLEEVE)));
        json.add("extensions", extensions);

        var jsonBytes = json.toString().getBytes(StandardCharsets.UTF_8);
        var jsonLength = (jsonBytes.length + 3) & ~3;
        var binLength = (bin.position() + 3) & ~3;
        var glb = ByteBuffer.allocateDirect(12 + 8 + jsonLength + 8 + binLength).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546C67).putInt(2).putInt(glb.capacity());
        glb.putInt(jsonLength).putInt(0x4E4F534A).put(jsonBytes);
        for (var i = jsonBytes.length; i < jsonLength; i++) {
            glb.put((byte)' ');
        }
        glb.putInt(binLength).putInt(0x004E4942).put(bin.array(), 0, binLength);
        return glb.flip();
    }

    private static void addFloats(ByteBuffer bin, JsonArray views, JsonArray accessors, float[] values, String type,
        int count) {
        var offset = bin.position();
        for (var value : values) {
            bin.putFloat(value);
        }
        addAccessor(views, accessors, offset, values.length * 4, 5126, count, type);
    }

    private static void addAccessor(JsonArray views, JsonArray accessors, int offset, int length, int componentType,
        int count, String type) {
        var view = new JsonObject();
        view.addProperty("buffer", 0);
        view.addProperty("byteOffset", offset);
        view.addProperty("byteLength", length);
        views.add(view);
        var accessor = new JsonObject();
        accessor.addProperty("bufferView", views.size() - 1);
        accessor.addProperty("componentType", componentType);
        accessor.addProperty("count", count);
        accessor.addProperty("type", type);
        accessors.add(accessor);
    }

    /** XNALara generic skeleton in a T-pose, meters, facing +Z with the left side at +X. */
    private static final String[] NAMES_XNA = {"root ground", "root hips", "pelvis", "spine lower", "spine upper",
        "head neck lower", "head", "arm left shoulder 1", "arm left shoulder 2", "arm left elbow", "arm left wrist",
        "arm right shoulder 1", "arm right shoulder 2", "arm right elbow", "arm right wrist", "leg left thigh",
        "leg left knee", "leg left ankle", "leg left toes", "leg right thigh", "leg right knee", "leg right ankle",
        "leg right toes"};
    private static final int[] PARENTS_XNA =
        {-1, 0, 1, 1, 3, 4, 5, 4, 7, 8, 9, 4, 11, 12, 13, 2, 15, 16, 17, 2, 19, 20, 21};
    private static final float[][] POSITIONS_XNA = {{0, 0, 0}, {0, 1, 0}, {0, 1, 0}, {0, 1.1f, 0}, {0, 1.3f, 0},
        {0, 1.45f, 0}, {0, 1.55f, 0}, {0.05f, 1.4f, 0}, {0.2f, 1.4f, 0}, {0.45f, 1.4f, 0}, {0.7f, 1.4f, 0},
        {-0.05f, 1.4f, 0}, {-0.2f, 1.4f, 0}, {-0.45f, 1.4f, 0}, {-0.7f, 1.4f, 0}, {0.1f, 0.9f, 0}, {0.1f, 0.5f, 0},
        {0.1f, 0.1f, 0}, {0.1f, 0, 0.1f}, {-0.1f, 0.9f, 0}, {-0.1f, 0.5f, 0}, {-0.1f, 0.1f, 0}, {-0.1f, 0, 0.1f}};

    /** 3ds Max Biped: Pelvis is hips and parents the spine and thighs. */
    private static final String[] NAMES_BIP = {"Bip01", "Bip01 Pelvis", "Bip01 Spine", "Bip01 Spine1", "Bip01 Neck",
        "Bip01 Head", "Bip01 L Clavicle", "Bip01 L UpperArm", "Bip01 L Forearm", "Bip01 L Hand", "Bip01 R Clavicle",
        "Bip01 R UpperArm", "Bip01 R Forearm", "Bip01 R Hand", "Bip01 L Thigh", "Bip01 L Calf", "Bip01 L Foot",
        "Bip01 L Toe0", "Bip01 R Thigh", "Bip01 R Calf", "Bip01 R Foot", "Bip01 R Toe0"};
    private static final int[] PARENTS_BIP =
        {-1, 0, 1, 2, 3, 4, 3, 6, 7, 8, 3, 10, 11, 12, 1, 14, 15, 16, 1, 18, 19, 20};
    private static final float[][] POSITIONS_BIP = {{0, 1, 0}, {0, 1, 0}, {0, 1.1f, 0}, {0, 1.3f, 0}, {0, 1.45f, 0},
        {0, 1.55f, 0}, {0.05f, 1.4f, 0}, {0.2f, 1.4f, 0}, {0.45f, 1.4f, 0}, {0.7f, 1.4f, 0}, {-0.05f, 1.4f, 0},
        {-0.2f, 1.4f, 0}, {-0.45f, 1.4f, 0}, {-0.7f, 1.4f, 0}, {0.1f, 0.9f, 0}, {0.1f, 0.5f, 0}, {0.1f, 0.1f, 0},
        {0.1f, 0, 0.1f}, {-0.1f, 0.9f, 0}, {-0.1f, 0.5f, 0}, {-0.1f, 0.1f, 0}, {-0.1f, 0, 0.1f}};

    /** Upper arm, forearm, thigh, head top and foot; spans 1.7 m so no rescale applies. */
    private static final float[][] XPS_VERTICES =
        {{0.3f, 1.4f, 0}, {0.5f, 1.4f, 0}, {0.1f, 0.7f, 0}, {0, 1.7f, 0}, {0.1f, 0, 0}};

    /** Two triangles, clockwise as XPS stores them, over {@link #XPS_VERTICES}; one bone each. */
    private static ByteBuffer xpsAscii(String[] names, int[] parents, float scale) {
        var text = new StringBuilder().append(names.length).append(" # bones\n");
        for (var i = 0; i < names.length; i++) {
            var p = POSITIONS_XNA[i];
            text.append(names[i]).append('\n').append(parents[i]).append('\n').append(p[0] * scale).append(' ')
                .append(p[1] * scale).append(' ').append(p[2] * scale).append('\n');
        }
        int[] bones = {8, 9, 15, 6, 17};
        text.append("1\n6_body_0.5\n1\n1\ntextures\\Body.TGA\n0\n").append(XPS_VERTICES.length).append('\n');
        for (var v = 0; v < XPS_VERTICES.length; v++) {
            var p = XPS_VERTICES[v];
            text.append(p[0] * scale).append(' ').append(p[1] * scale).append(' ').append(p[2] * scale).append('\n');
            text.append("0 0 1\n255 255 255 255\n0.5 0.5\n");
            text.append(bones[v]).append(" 0 0 0\n1 0 0 0\n");
        }
        text.append("2\n0 1 2\n2 3 4\n");
        return StandardCharsets.UTF_8.encode(text.toString());
    }

    /** XPS 3.15 with a header, Biped names, centimeters and five influences on the thigh vertex. */
    private static ByteBuffer xpsBinary() {
        var out = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(323232).putShort((short)3).putShort((short)15);
        xpsString(out, "XNAaraL");
        out.putInt(2);
        xpsString(out, "machine");
        xpsString(out, "user");
        xpsString(out, "C:\\models\\probe.xps");
        out.putInt(0).putInt(0); // settings: hash, no items
        out.putInt(NAMES_BIP.length);
        for (var i = 0; i < NAMES_BIP.length; i++) {
            xpsString(out, NAMES_BIP[i]);
            out.putShort((short)PARENTS_BIP[i]);
            for (var c : POSITIONS_BIP[i]) {
                out.putFloat(c * 100);
            }
        }
        out.putInt(1);
        xpsString(out, "body");
        out.putInt(1).putInt(0).putInt(XPS_VERTICES.length);
        int[][] bones = {{7}, {8}, {14, 15, 16, 1, 2}, {5}, {16}};
        float[][] weights = {{1}, {1}, {0.5f, 0.2f, 0.1f, 0.1f, 0.1f}, {1}, {1}};
        for (var v = 0; v < XPS_VERTICES.length; v++) {
            for (var c : XPS_VERTICES[v]) {
                out.putFloat(c * 100);
            }
            out.putFloat(0).putFloat(0).putFloat(1).putInt(-1).putFloat(0.5f).putFloat(0.5f);
            out.putShort((short)bones[v].length);
            for (var bone : bones[v]) {
                out.putShort((short)bone);
            }
            for (var weight : weights[v]) {
                out.putFloat(weight);
            }
        }
        out.putInt(2).putInt(0).putInt(1).putInt(2).putInt(2).putInt(3).putInt(4);
        return out.flip();
    }

    private static void xpsString(ByteBuffer out, String text) {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        out.put((byte)bytes.length).put(bytes);
    }
}
