package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoPropertiesProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.MorphTargetProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SkinnedMeshProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringBonesProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringChainProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringColliderProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringJointProto;
import com.elfmcys.ysm.util.Closeable;
import com.elfmcys.ysm.util.ProtoUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Converts an imported humanoid (VRM, PMX, PMD, XPS) into YSM geometry: a skinned mesh on a skeleton whose humanoid
 * bones carry the builtin default model's names, so default animations drive it. The mesh is re-posed so the arms hang
 * straight down in the zero-rotation frame; arm bones then carry the default rest rotation. The model faces -Z with its
 * left side at -X. Geometry is scaled so the hips sit at the default model's 20 pixels, and the height/width scale
 * restores the real size: the default animations' pixel translations (sneak, walk bob, sitting) then fit the body.
 * Fingers rest slightly curled; a generated animation grips and opens them, and {@link FaceLayer} animates the face.
 */
final class HumanoidImport implements Closeable {
    static final List<String> EXTENSIONS = List.of(".vrm", ".pmx", ".pmd", ".xps", ".mesh", ".mesh.ascii");
    /** Driver of the blink expression; the default blink animation scales a bone of this name to 0. */
    static final String BLINK_BONE = "Eyelid";
    /**
     * Revision of the generated output (skeleton, animations, physics, expressions, scale). Part of every imported
     * model's identity, so bump it whenever the output changes: otherwise unchanged model files keep the conversion
     * cached by an earlier build of the same mod version.
     */
    static final int REVISION = 24;
    /** Driver bones of other expressions are this prefix and the expression name. */
    static final String EXPRESSION_BONE_PREFIX = "expression.";

    private static final Map<String,
        String> HUMANOID_NAMES = Map.ofEntries(Map.entry("hips", "AllBody"), Map.entry("lowerBody", "DownBody"),
            Map.entry("spine", "UpBody"), Map.entry("neck", "AllHead"), Map.entry("head", "Head"),
            Map.entry("leftUpperArm", "LeftArm"), Map.entry("leftLowerArm", "LeftForeArm"),
            Map.entry("leftHand", "LeftHand"), Map.entry("rightUpperArm", "RightArm"),
            Map.entry("rightLowerArm", "RightForeArm"), Map.entry("rightHand", "RightHand"),
            Map.entry("leftUpperLeg", "LeftLeg"), Map.entry("leftLowerLeg", "LeftLowerLeg"),
            Map.entry("leftFoot", "LeftFoot"), Map.entry("rightUpperLeg", "RightLeg"),
            Map.entry("rightLowerLeg", "RightLowerLeg"), Map.entry("rightFoot", "RightFoot"),
            Map.entry("leftEye", "LeftEye"), Map.entry("rightEye", "RightEye"));

    static final List<String> REQUIRED_HUMANOID =
        List.of("hips", "spine", "head", "leftUpperArm", "leftLowerArm", "leftHand", "rightUpperArm", "rightLowerArm",
            "rightHand", "leftUpperLeg", "leftLowerLeg", "leftFoot", "rightUpperLeg", "rightLowerLeg", "rightFoot");

    /** Default model hip height in pixels; imported geometry is scaled to match it. */
    private static final float DEFAULT_HIPS_PIXELS = 20f;

    /** Default skeleton locators: offset from parent pivot in default pixels (YSM axes), rotation in degrees. */
    private record Locator(String name, String parent, float x, float y, float z, float rx, float ry, float rz) {
    }

    private static final List<Locator> LOCATORS = List.of(new Locator("LeftHandLocator", "LeftHand", 0, 0, 0, 0, 0, 0),
        new Locator("SheathLocator", "LeftHand", 0, 0, 0, 0, 0, 0),
        new Locator("LeftWaistLocator", "LeftHand", 0, 0, 0, 0, 0, 0),
        new Locator("RightHandLocator", "RightHand", 0, 0, 0, 0, 0, 0),
        new Locator("BladeLocator", "RightHand", 0, 0, 0, 0, 0, 0),
        new Locator("RifleLocator", "UpperBody", 0, 1.15f, 2.3f, 0, 0, 45),
        new Locator("PistolLocator", "UpBody", 4, 0.75f, -1.6f, 30, 0, 0),
        new Locator("ElytraLocator", "UpperBody", 0, 7.65f, 2, 0, 0, 0),
        new Locator("RightWaistLocator", "UpBody", 4, -3, 0, 5, 0, 0),
        new Locator("BackpackLocator", "UpperBody", 0, 5, 0, 0, 0, 0),
        new Locator("LeftShoulderLocator", "Head", -3, 9.5f, 0, 0, 0, 0),
        new Locator("RightShoulderLocator", "Head", 3, 9.5f, 0, 0, 0, 0));
    /**
     * Bones of the default skeleton without a humanoid counterpart. Default animations move them: UpperBody turns the
     * chest above the waist (sword swings and most item animations), MAllBody the whole body and MHead the head.
     */
    private static final List<String> DEFAULT_ONLY_BONES = List.of("MAllBody", "UpperBody", "MHead");

    /**
     * Default first-person arm root pivots (YSM axes) in pixels. First-person arms render unscaled, unlike the body, so
     * these are the default arm model's own pivots.
     */
    private static final Vector3f LEFT_ARM_FIRST_PERSON = new Vector3f(-1.15f, 29.775f, 0);
    private static final Vector3f RIGHT_ARM_FIRST_PERSON = new Vector3f(1.15f, 29.775f, 0);
    /** Shoulder pivot to hand locator of the default first-person arm, in pixels: the middle of its fist. */
    private static final float FIRST_PERSON_REACH = 11.45f;
    /**
     * First-person hands grow by this much about the wrist, and arms by this much across their length. Held guns put
     * the default arm's blocky end at their grip; a human hand there is a fraction of it and vanishes into the stock.
     * Grown this much, the hand closed into a fist fills about that end's three pixels, and the forearm stays slimmer
     * than the default arm. Hands only grow evenly: thickened across the arm, a hand whose fingers curl turns that
     * stretch sideways and the fingers look twisted.
     */
    private static final float FIRST_PERSON_HAND_SCALE = 1.8f;
    private static final float FIRST_PERSON_THICKNESS = 1.45f;
    /** Palm center past the wrist for a hand without fingers, as a share of the forearm's length. */
    private static final float PALM_REACH = 0.17f;

    /** Reads a file that a model references by relative path; null when it does not exist. */
    @FunctionalInterface
    interface TextureResolver {
        @Nullable
        ByteBuffer read(String path) throws IOException;
    }

    private static final class BoneDef {
        final String name;
        @Nullable
        String parent;
        final Vector3f pivot;
        final Vector3f rotate = new Vector3f();

        BoneDef(String name, @Nullable String parent, Vector3f pivot) {
            this.name = name;
            this.parent = parent;
            this.pivot = pivot;
        }
    }

    private final List<BoneDef> bones = new ArrayList<>();
    private final Map<String, Integer> boneIndex = new HashMap<>();
    private final FloatArrayList positions = new FloatArrayList();
    private final FloatArrayList normals = new FloatArrayList();
    private final FloatArrayList uvs = new FloatArrayList();
    private final IntArrayList joints = new IntArrayList();
    private final FloatArrayList weights = new FloatArrayList();
    private final IntArrayList triangles = new IntArrayList();
    /** Inverted-hull outline triangles over hull copies of the outlined vertices, wound inward. */
    private final IntArrayList outlineTriangles = new IntArrayList();
    /** Surface class of every triangle (a {@link SpecularAtlas} class), for the specular map. */
    private final ByteArrayList triangleClasses = new ByteArrayList();
    private final TextureAtlas atlas;
    private final HumanoidSource.Metadata metadata;
    @Nullable
    private SpringBonesProto springs;
    private List<MorphTargetProto> morphs = List.of();
    /** Model units per meter (the hips land at the default model's height); real size renders at the inverse. */
    private final float unit;
    /** Rendered height in blocks. */
    private final float height;
    /** Generated animation file for the main set: fingers, face, carrying, locomotion and swings. */
    private String animation = "{\"animations\": {}}";
    /** Generated animation file for the first-person arm set: only the fingers, which play there too. */
    private String armAnimation = "{\"animations\": {}}";
    /** The model's endfield_npr package ({@link EndfieldNprPackage}), null without one. */
    @Nullable
    private byte[] nprPackage;
    /** Material of every main-mesh output vertex (outline hull copies excluded), for {@link MeshLod}. */
    private int[] vertexMaterials = new int[0];
    /** Outline hull copy of every outlined output vertex, -1 elsewhere, for {@link MeshLod}. */
    private int[] outlineHulls = new int[0];
    @Nullable
    private MeshLod.Result meshLod;
    /** Atlas materials: the source's, then the outline colours ({@link #outlineMaterialOf}); for the rig export. */
    private List<HumanoidSource.Material> materials = List.of();
    /** glTF material and base colour image index of each source material (VRM only); for the rig export. */
    private int[] materialGltf = new int[0];
    private int[] materialImage = new int[0];
    /** Outline colour material of each outlined material; for the rig export. */
    private Map<Integer, Integer> outlineMaterialOf = Map.of();
    /** Main-mesh vertex of every vertex of the last {@link #arm()}; for the rig export. */
    private int[] armVertexSources = new int[0];

    private HumanoidImport(TextureAtlas atlas, HumanoidSource.Metadata metadata, float unit, float height) {
        this.atlas = atlas;
        this.metadata = metadata;
        this.unit = unit;
        this.height = height;
    }

    static boolean isHumanoidFile(@Nullable String path) {
        if (path == null) {
            return false;
        }
        var lower = path.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /**
     * Reads a model file, maps its humanoid bones (format conventions, then common rig names, then a humanoid.json
     * beside the model) and converts it.
     */
    static HumanoidImport load(String path, ByteBuffer data, TextureResolver textures) throws IOException {
        var lower = path.toLowerCase(Locale.ROOT);
        var original = data.duplicate();
        try (var source = lower.endsWith(".pmx") ? PmxReader.read(data, textures)
            : lower.endsWith(".pmd") ? PmdReader.read(data, textures)
                : lower.endsWith(".vrm") ? VrmReader.read(data) : XpsReader.read(path, data, textures)) {
            HumanoidMapping.guessFromNames(source);
            var override = textures.read(HumanoidMapping.OVERRIDE_FILE);
            if (override != null) {
                HumanoidMapping.applyOverride(source, override);
            }
            var result = build(source);
            if (lower.endsWith(".vrm")) {
                try {
                    result.nprPackage = EndfieldNprPackage.build(original, source.materials, result.atlas);
                } catch (IOException | RuntimeException error) {
                    result.close();
                    throw error instanceof IOException io ? io
                        : new IOException(EndfieldNprPackage.EXTENSION + ": malformed extension: " + error, error);
                }
            }
            return result;
        }
    }

    /**
     * Reads every file a model references (its textures and the humanoid.json beside it) through the resolver without
     * converting the model: a capture pass records these reads so the conversion may repeat them. Only the format
     * reader runs; a resolver that answers null keeps it from decoding images. VRM embeds its textures.
     */
    static void readReferences(String path, ByteBuffer data, TextureResolver files) throws IOException {
        var lower = path.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".vrm")) {
            try (var ignored = lower.endsWith(".pmx") ? PmxReader.read(data, files)
                : lower.endsWith(".pmd") ? PmdReader.read(data, files) : XpsReader.read(path, data, files)) {
                // Reading is the point; the source itself is discarded.
            }
        }
        files.read(HumanoidMapping.OVERRIDE_FILE);
    }

    static HumanoidImport build(HumanoidSource source) throws IOException {
        HumanoidMapping.complete(source);
        HumanoidMapping.validate(source);
        if (!source.outline) {
            // Rim light separates the figure; the model's own outlines stay off unless humanoid.json asks for them.
            for (var i = 0; i < source.materials.size(); i++) {
                var m = source.materials.get(i);
                if (m.outline() != null) {
                    source.materials.set(i, new HumanoidSource.Material(m.name(), m.texture(), m.r(), m.g(), m.b(),
                        m.a(), m.alphaMode(), m.cutoff(), null));
                }
            }
        }
        PhongRefinement.refine(source);
        var cloth = ClothColliders.apply(source);
        if (!cloth.chains().isEmpty()) {
            // Opt-in (humanoid.json "cloth_colliders" / "cloth_follow": true); one line per model so server owners can
            // spot chains taken for skirts by mistake.
            System.out.println("[YSM] cloth colliders (humanoid.json opt-in): " + cloth.chains().size()
                + " skirt chains " + cloth.chains() + ", " + cloth.colliders()
                + " leg colliders added, hit radii clamped to rest clearance " + cloth.clamped() + ", "
                + cloth.followVertices() + " vertices follow the thighs");
        }
        var layout = new Layout(source);
        var used = new TreeSet<Integer>();
        for (var i = 0; i < source.triangleMaterials.size(); i++) {
            used.add(source.triangleMaterials.getInt(i));
        }
        // Each outlined material gets a solid region in its outline color for the hull to sample.
        var materials = new ArrayList<>(source.materials);
        for (int material : List.copyOf(used)) {
            var outline = source.materials.get(material).outline();
            if (outline != null) {
                layout.outlineMaterials.put(material, materials.size());
                used.add(materials.size());
                materials.add(new HumanoidSource.Material("outline", null, outline.r(), outline.g(), outline.b(), 1f,
                    HumanoidSource.OPAQUE, 0.5f, null));
            }
        }
        var atlas = TextureAtlas.build(materials, used);
        try {
            var result = new HumanoidImport(atlas, source.metadata, layout.unit, source.height);
            result.materials = List.copyOf(materials);
            result.materialGltf = source.materialGltf.toIntArray();
            result.materialImage = source.materialImage.toIntArray();
            result.outlineMaterialOf = Map.copyOf(layout.outlineMaterials);
            layout.emitSkeleton(result);
            var animations = layout.emitAnimations(result);
            result.springs = layout.emitSprings(result);
            layout.emitMesh(result);
            result.morphs = layout.emitMorphs(result);
            // Clips the model ships itself; the generated ones below never replace them.
            var authored = java.util.Set.copyOf(animations.keySet());
            // Locomotion paces the planted foot by the render scale, which the finished mesh sets.
            HumanoidMotion.addTo(animations, result.legs(), source.itemScale);
            // Gun clips fit the arms to the finished skeleton and keep guns clear of the finished chest.
            TaczRetarget.addTo(animations, result.skeleton(), result.chestDepth(), source.itemScale);
            // Idle, walk and run follow Endfield's own motion (retargeted to this skeleton) when its data ships.
            EndfieldLocomotion.addTo(animations, authored, result.skeleton(), result.legs());
            // Stances hold items clear of the finished body: arm channels choose held poses solved against the mesh.
            HeldItems.addTo(animations, authored, result.skeleton(), result.bones.stream().map(bone -> bone.name).toList(),
                result.positions, result.joints, result.weights, source.itemScale);
            // Tool swings start and end on the held pose above, so they follow HeldItems.
            ToolSwings.addTo(animations, authored);
            // Bow draw and crossbow loading, charged aim and shot, aimed by the head's pitch and yaw.
            RangedUse.addTo(animations, authored, result.skeleton(), source.itemScale);
            // Blink, gaze, idle micro-motion and state expressions for bone-rigged and blend-shape faces.
            FaceLayer.addTo(animations, authored, result.skeleton());
            // Prone transitions kneel on the finished legs and end on the builtin lying pose; the dive needs the scale.
            HumanoidMotion.addProne(animations, result.proneBody());
            stringifyMixedVectors(animations);
            result.animation = Layout.animationFile(animations);
            return result;
        } catch (IOException | RuntimeException error) {
            atlas.close();
            throw error;
        }
    }

    HumanoidSource.Metadata metadata() {
        return metadata;
    }

    Image atlasImage() throws IOException {
        return atlas.encode();
    }

    /** The RGBA texture atlas both models sample (for the rig export, which writes it as PNG). */
    TextureAtlas atlas() {
        return atlas;
    }

    /** The LabPBR specular map of {@link #atlasImage()}: per-class smoothness, reflectance, scattering and emission. */
    Image specularImage() throws IOException {
        return SpecularAtlas.encode(atlas, uvs, triangles, triangleClasses);
    }

    /** The endfield_npr package for the player target's data blob, or null. */
    @Nullable
    byte[] nprPackage() {
        return nprPackage;
    }

    /**
     * Geometric LOD levels of the main mesh and the spring chain classes ({@link MeshLod}); built once, after the
     * endfield_npr package so its overlay cards are kept whole.
     */
    MeshLod.Result meshLod() throws IOException {
        if (meshLod == null) {
            var names = new String[bones.size()];
            var parents = new int[bones.size()];
            var pivots = new float[bones.size() * 3];
            for (var i = 0; i < bones.size(); i++) {
                var bone = bones.get(i);
                names[i] = bone.name;
                parents[i] = bone.parent == null ? -1 : boneIndex.getOrDefault(bone.parent, -1);
                pivots[i * 3] = bone.pivot.x;
                pivots[i * 3 + 1] = bone.pivot.y;
                pivots[i * 3 + 2] = bone.pivot.z;
            }
            meshLod = MeshLod.build(new MeshLod.Input(positions.toFloatArray(), normals.toFloatArray(),
                uvs.toFloatArray(), joints.toIntArray(), weights.toFloatArray(), triangles.toIntArray(),
                outlineTriangles.toIntArray(), triangleClasses.toByteArray(), vertexMaterials, outlineHulls, morphs,
                springs, names, parents, pivots, nprPackage));
            var levels = new StringBuilder();
            for (var level : meshLod.levels()) {
                levels.append(' ').append(level.triangles().length / 3).append(" tris @ ")
                    .append(String.format(Locale.ROOT, "%.2f%%", level.error() * 100));
            }
            System.out.println("[YSM] mesh LOD: " + triangles.size() / 3 + " tris ->" + levels + " (" + meshLod.millis()
                + " ms)");
        }
        return meshLod;
    }

    /** Bedrock animation JSON for the main animation set. */
    String animation() {
        return animation;
    }

    /**
     * Bedrock animation JSON for the first-person arm set, which plays every parallel animation it has: only the finger
     * grip, so third-person poses such as carrying never move the first-person arm.
     */
    String armAnimation() {
        return armAnimation;
    }

    /** The main model, scaled so it stands {@link HumanoidSource#height} blocks tall. */
    GeoBuilder.Result main() throws IOException {
        var stats = new GeoBuilder.Stats();
        stats.bones = bones.size();
        stats.faces = triangles.size() / 3;
        var model =
            geoModel(bones, positions.toFloatArray(), normals.toFloatArray(), uvs.toFloatArray(), joints.toIntArray(),
                weights.toFloatArray(), triangles.toIntArray(), outlineTriangles.toIntArray(), morphs, springs);
        var scale = renderScale();
        return new GeoBuilder.Result(model, stats, scale, scale, null);
    }

    private int vertexCount() {
        return positions.size() / 3;
    }

    /** Render scale that brings the top of the mesh (model units above the feet) to the target height in blocks. */
    private float renderScale() {
        var top = 0f;
        for (var i = 1; i < positions.size(); i += 3) {
            top = Math.max(top, positions.getFloat(i));
        }
        return top > 1e-4f ? height / top : 1f / unit;
    }

    /**
     * First-person arms: the LeftArm and RightArm subtrees as roots, with the triangles weighted only to them. Each arm
     * is fitted by {@link ArmFit}: the palm lands where the default arm holds items and the shoulder where the default
     * arm model places it, so the hand and anything it holds land where the default first-person animations and other
     * mods' first-person poses (TACZ puts the fist on a gun's grip) expect them. A human hand at its natural size is a
     * fraction of the default fist and the arm well under half as thick, so both are enlarged.
     */
    GeoBuilder.Result arm() throws IOException {
        var armBones = new ArrayList<BoneDef>();
        var remap = new HashMap<Integer, Integer>();
        var side = new int[bones.size()];
        var inHand = new boolean[bones.size()];
        var fits = new ArmFit[3];
        for (var sideIndex = 1; sideIndex <= 2; sideIndex++) {
            var prefix = sideIndex == 1 ? "Left" : "Right";
            var rootName = prefix + "Arm";
            fits[sideIndex] = ArmFit.of(bone(rootName).pivot, bone(prefix + "Hand").pivot, palm(prefix),
                sideIndex == 1 ? LEFT_ARM_FIRST_PERSON : RIGHT_ARM_FIRST_PERSON);
            for (var i = 0; i < bones.size(); i++) {
                if (!descendsFrom(i, rootName)) {
                    continue;
                }
                inHand[i] = descendsFrom(i, prefix + "Hand");
                var source = bones.get(i);
                var isRoot = source.name.equals(rootName);
                var copy = new BoneDef(source.name, isRoot ? null : source.parent,
                    fits[sideIndex].place(source.pivot, inHand[i] ? 1f : 0f));
                if (!isRoot) {
                    copy.rotate.set(source.rotate);
                }
                remap.put(i, armBones.size());
                side[i] = sideIndex;
                armBones.add(copy);
            }
        }

        var vertexMap = new HashMap<Integer, Integer>();
        var armSources = new IntArrayList();
        var armPositions = new FloatArrayList();
        var armNormals = new FloatArrayList();
        var armUvs = new FloatArrayList();
        var armJoints = new IntArrayList();
        var armWeights = new FloatArrayList();
        var armTriangles = new IntArrayList();
        var point = new Vector3f();
        for (var t = 0; t < triangles.size(); t += 3) {
            var triangleSide = 0;
            var inside = true;
            for (var c = 0; c < 3 && inside; c++) {
                var vertex = triangles.getInt(t + c);
                for (var k = 0; k < 4; k++) {
                    if (weights.getFloat(vertex * 4 + k) <= 0f) {
                        continue;
                    }
                    var jointSide = side[joints.getInt(vertex * 4 + k)];
                    if (jointSide == 0 || (triangleSide != 0 && jointSide != triangleSide)) {
                        inside = false;
                        break;
                    }
                    triangleSide = jointSide;
                }
            }
            if (!inside || triangleSide == 0) {
                continue;
            }
            var fit = fits[triangleSide];
            for (var c = 0; c < 3; c++) {
                var vertex = triangles.getInt(t + c);
                var mapped = vertexMap.get(vertex);
                if (mapped == null) {
                    mapped = armPositions.size() / 3;
                    vertexMap.put(vertex, mapped);
                    armSources.add(vertex);
                    var hand = 0f;
                    for (var k = 0; k < 4; k++) {
                        if (inHand[joints.getInt(vertex * 4 + k)]) {
                            hand += weights.getFloat(vertex * 4 + k);
                        }
                    }
                    // Positions are in blocks, the fit in pixels.
                    point.set(positions.getFloat(vertex * 3), positions.getFloat(vertex * 3 + 1),
                        positions.getFloat(vertex * 3 + 2)).mul(16f);
                    var placed = fit.place(point, Math.min(hand, 1f)).div(16f);
                    armPositions.add(placed.x);
                    armPositions.add(placed.y);
                    armPositions.add(placed.z);
                    var normal = fit.normal(point.set(normals.getFloat(vertex * 3), normals.getFloat(vertex * 3 + 1),
                        normals.getFloat(vertex * 3 + 2)));
                    armNormals.add(normal.x);
                    armNormals.add(normal.y);
                    armNormals.add(normal.z);
                    armUvs.add(uvs.getFloat(vertex * 2));
                    armUvs.add(uvs.getFloat(vertex * 2 + 1));
                    for (var k = 0; k < 4; k++) {
                        var weight = weights.getFloat(vertex * 4 + k);
                        armJoints.add(weight > 0f ? remap.get(joints.getInt(vertex * 4 + k)) : 0);
                        armWeights.add(weight);
                    }
                }
                armTriangles.add(mapped.intValue());
            }
        }

        var stats = new GeoBuilder.Stats();
        stats.bones = armBones.size();
        stats.faces = armTriangles.size() / 3;
        // First-person arms render without expressions or outlines; spring chains hung inside the arms swing there too.
        var model = geoModel(armBones, armPositions.toFloatArray(), armNormals.toFloatArray(), armUvs.toFloatArray(),
            armJoints.toIntArray(), armWeights.toFloatArray(), armTriangles.toIntArray(), new int[0], List.of(),
            armSprings(remap, side, inHand, fits));
        armVertexSources = armSources.toIntArray();
        return new GeoBuilder.Result(model, stats, renderScale(), renderScale(), null);
    }

    /** Atlas materials (source materials, then outline colours), for the rig export. */
    List<HumanoidSource.Material> materials() {
        return materials;
    }

    /** glTF material index of an atlas material, -1 for none (outline colours, non-VRM models). */
    int materialGltf(int material) {
        return material < materialGltf.length ? materialGltf[material] : -1;
    }

    /** glTF image index of an atlas material's base colour texture, -1 for none. */
    int materialImage(int material) {
        return material < materialImage.length ? materialImage[material] : -1;
    }

    /** Outline colour material of an outlined material, or -1. */
    int outlineMaterial(int material) {
        return outlineMaterialOf.getOrDefault(material, -1);
    }

    /** Material of each main-mesh vertex (outline hull copies excluded). */
    int[] vertexMaterials() {
        return vertexMaterials;
    }

    /** Outline hull copy of each outlined main-mesh vertex, -1 elsewhere. */
    int[] outlineHulls() {
        return outlineHulls;
    }

    /** Main-mesh vertex of every vertex of the last {@link #arm()}. */
    int[] armVertexSources() {
        return armVertexSources;
    }

    /** The specular map's texels ({@link #specularImage()}), for the rig export. */
    SpecularAtlas.Texels specularTexels() {
        return SpecularAtlas.texels(atlas, uvs, triangles, triangleClasses);
    }

    /**
     * Palm center of one first-person arm ("Left" or "Right") in the arm model's pixels: where {@link #arm()} places
     * {@link #palm}, the point fitted TACZ grips put on the gun. For the rig export.
     */
    Vector3f firstPersonPalm(String side) {
        var fit = ArmFit.of(bone(side + "Arm").pivot, bone(side + "Hand").pivot, palm(side),
            side.equals("Left") ? LEFT_ARM_FIRST_PERSON : RIGHT_ARM_FIRST_PERSON);
        return fit.place(palm(side), 1f);
    }

    /**
     * The body's spring chains whose joints all lie inside the LeftArm/RightArm subtrees (sleeves, cuffs, bracelets,
     * hand accessories), for the first-person arm model: bones remapped to it, tails and collider ends placed by the
     * arm's {@link ArmFit} like its vertices, lengths that set the response (radii, stiffness and gravity per second)
     * scaled with the fit. Colliders on arm bones come along; colliders elsewhere (the body) do not exist in the arm
     * model and drop out of the chains. Null when no chain lives in the arms.
     */
    @Nullable
    private SpringBonesProto armSprings(Map<Integer, Integer> remap, int[] side, boolean[] inHand, ArmFit[] fits) {
        if (springs == null) {
            return null;
        }
        var chainKept = new HashMap<Integer, Boolean>();
        for (var joint : springs.joints()) {
            chainKept.merge(joint.chain(), remap.containsKey(joint.bone()), Boolean::logicalAnd);
        }
        var out = SpringBonesProto.newBuilder();
        var colliderMap = new HashMap<Integer, Integer>();
        for (var i = 0; i < springs.colliders().size(); i++) {
            var collider = springs.colliders().get(i);
            var mapped = remap.get(collider.bone());
            if (mapped == null) {
                continue;
            }
            var fit = fits[side[collider.bone()]];
            var hand = inHand[collider.bone()] ? 1f : 0f;
            var head = placed(fit, collider.head(), hand);
            var tail = placed(fit, collider.tail(), hand);
            colliderMap.put(i, colliderMap.size());
            out.addColliders(SpringColliderProto.newBuilder().setBone(mapped).addHead(head.x, head.y, head.z)
                .addTail(tail.x, tail.y, tail.z).setRadius(collider.radius() * across(fit, hand))
                .setType(collider.type()).build());
        }
        var chainMap = new HashMap<Integer, Integer>();
        for (var c = 0; c < springs.chains().size(); c++) {
            if (!chainKept.getOrDefault(c, false)) {
                continue;
            }
            // A center outside the arms does not exist in the arm model: the chain falls back to world space.
            var center = springs.chains().get(c).center();
            var centerBone = center > 0 ? remap.get(center - 1) : null;
            var chain = SpringChainProto.newBuilder().setCenter(centerBone != null ? centerBone + 1 : 0);
            for (var k = 0; k < springs.chains().get(c).colliders().size(); k++) {
                var mapped = colliderMap.get(springs.chains().get(c).colliders().getInt(k));
                if (mapped != null) {
                    chain.addColliders(mapped);
                }
            }
            chainMap.put(c, chainMap.size());
            out.addChains(chain.build());
        }
        if (chainMap.isEmpty()) {
            return null;
        }
        for (var joint : springs.joints()) {
            var chain = chainMap.get(joint.chain());
            if (chain == null) {
                continue;
            }
            var fit = fits[side[joint.bone()]];
            var hand = inHand[joint.bone()] ? 1f : 0f;
            var tail = placed(fit, joint.tail(), hand);
            out.addJoints(SpringJointProto.newBuilder().setBone(remap.get(joint.bone())).addTail(tail.x, tail.y, tail.z)
                .setHitRadius(joint.hitRadius() * across(fit, hand)).setStiffness(joint.stiffness() * fit.scale())
                .setDrag(joint.drag()).setGravityPower(joint.gravityPower() * fit.scale())
                .addGravityDir(joint.gravityDir().getFloat(0), joint.gravityDir().getFloat(1),
                    joint.gravityDir().getFloat(2))
                .setChain(chain).setLimit(joint.limit()).addAllLimitRotation(joint.limitRotation())
                .setLimitAngle(joint.limitAngle()).setLimitYaw(joint.limitYaw()).build());
        }
        return out.build();
    }

    /** A point in model units (blocks) placed like the arm's vertices (the fit works in pixels). */
    private static Vector3f placed(ArmFit fit, it.unimi.dsi.fastutil.floats.FloatList point, float hand) {
        return fit.place(new Vector3f(point.getFloat(0), point.getFloat(1), point.getFloat(2)).mul(16f), hand)
            .div(16f);
    }

    /** How much the fit enlarges lengths across the arm: the hand grows evenly, the rest thickens across its axis. */
    private static float across(ArmFit fit, float hand) {
        return fit.scale() * (hand > 0f ? FIRST_PERSON_HAND_SCALE : FIRST_PERSON_THICKNESS);
    }

    /**
     * Fits one arm to the default first-person arm, in pixels: the hand grows evenly about the wrist, the rest of the
     * arm thickens across its shoulder-to-wrist axis, then the whole arm scales about the shoulder so the enlarged palm
     * lies the default reach from it and moves so the shoulder sits on the default arm's pivot.
     */
    private record ArmFit(Vector3f shoulder, Vector3f wrist, Vector3f axis, float scale, Vector3f target) {
        static ArmFit of(Vector3f shoulder, Vector3f wrist, Vector3f palm, Vector3f target) {
            var axis = new Vector3f(wrist).sub(shoulder);
            axis = axis.lengthSquared() > 1e-8f ? axis.normalize() : new Vector3f(0, -1, 0);
            var shaped = new ArmFit(shoulder, wrist, axis, 1f, target);
            var reach = shaped.shape(palm, 1f).distance(shoulder);
            return new ArmFit(shoulder, wrist, axis, reach > 1e-4f ? FIRST_PERSON_REACH / reach : 1f, target);
        }

        /** The point with the hand enlarged and the arm thickened, by its hand weight (0 to 1). */
        Vector3f shape(Vector3f point, float hand) {
            var offset =
                new Vector3f(point).sub(wrist).mul(1f + (FIRST_PERSON_HAND_SCALE - 1f) * hand).add(wrist).sub(shoulder);
            var along = new Vector3f(axis).mul(axis.dot(offset));
            return offset.sub(along).mul(1f + (FIRST_PERSON_THICKNESS - 1f) * (1f - hand)).add(along).add(shoulder);
        }

        Vector3f place(Vector3f point, float hand) {
            return shape(point, hand).sub(shoulder).mul(scale).add(target);
        }

        /** A normal under the thickening: the inverse transpose divides its part across the axis. */
        Vector3f normal(Vector3f normal) {
            var along = new Vector3f(axis).mul(axis.dot(normal));
            var result = new Vector3f(normal).sub(along).div(FIRST_PERSON_THICKNESS).add(along);
            return result.lengthSquared() > 1e-12f ? result.normalize() : new Vector3f(normal);
        }
    }

    private boolean descendsFrom(int index, String ancestor) {
        var current = bones.get(index);
        while (current != null) {
            if (current.name.equals(ancestor)) {
                return true;
            }
            var parent = current.parent == null ? null : boneIndex.get(current.parent);
            current = parent == null ? null : bones.get(parent);
        }
        return false;
    }

    private GeoModelProto geoModel(List<BoneDef> boneList, float[] meshPositions, float[] meshNormals, float[] meshUvs,
        int[] meshJoints, float[] meshWeights, int[] meshTriangles, int[] meshOutlineTriangles,
        List<MorphTargetProto> meshMorphs, @Nullable SpringBonesProto springBones) throws IOException {
        var model = GeoModelProto.newBuilder().setProperties(
            GeoPropertiesProto.newBuilder().setTextureWidth(atlas.width).setTextureHeight(atlas.height).build());
        for (var bone : boneList) {
            var proto = BoneProto.newBuilder().setName(bone.name).addPivot(bone.pivot.x).addPivot(bone.pivot.y)
                .addPivot(bone.pivot.z).addRotate(bone.rotate.x).addRotate(bone.rotate.y).addRotate(bone.rotate.z);
            if (bone.parent != null) {
                proto.setParent(bone.parent);
            }
            model.addBones(proto.build());
        }
        var mesh = SkinnedMeshProto.newBuilder().addPositions(meshPositions).addNormals(meshNormals).addUvs(meshUvs)
            .addJoints(meshJoints).addWeights(meshWeights).addTriangles(meshTriangles)
            .addOutlineTriangles(meshOutlineTriangles);
        meshMorphs.forEach(mesh::addMorphs);
        model.setMesh(ByteBuffer.wrap(ProtoUtil.serializeToArray(mesh.build())));
        if (springBones != null) {
            model.setSprings(ByteBuffer.wrap(ProtoUtil.serializeToArray(springBones)));
        }
        return model.build();
    }

    @Override
    public void close() {
        atlas.close();
    }

    private void addBone(BoneDef bone) {
        boneIndex.put(bone.name, bones.size());
        bones.add(bone);
    }

    private BoneDef bone(String name) {
        return bones.get(Objects.requireNonNull(boneIndex.get(name), name));
    }

    /**
     * Middle of a hand's palm in pixels ("Left" or "Right"): halfway from the wrist to the root of the middle finger
     * (or the nearest finger present), or a little past the wrist along the forearm for a hand without fingers.
     */
    private Vector3f palm(String side) {
        var wrist = bone(side + "Hand").pivot;
        for (var finger : List.of("Middle1", "Ring1", "Index1", "Little1")) {
            var index = boneIndex.get(side + finger);
            if (index != null) {
                return new Vector3f(bones.get(index).pivot).add(wrist).mul(0.5f);
            }
        }
        var elbow = bone(side + "ForeArm").pivot;
        return new Vector3f(wrist).sub(elbow).mul(PALM_REACH).add(wrist);
    }

    /** The finished skeleton for {@link TaczRetarget}: pixels and rest rotations, as the native renderer reads them. */
    Map<String, TaczRetarget.Bone> skeleton() {
        var result = new HashMap<String, TaczRetarget.Bone>();
        for (var bone : bones) {
            result.put(bone.name,
                new TaczRetarget.Bone(bone.name, bone.parent, new Vector3f(bone.pivot), new Vector3f(bone.rotate)));
        }
        return result;
    }

    /**
     * How far the chest reaches in front of the shoulder girdle's pivot, in pixels (the model faces -Z): the most
     * forward vertex between the hips and the shoulders that mostly follows the trunk rather than an arm or the head.
     */
    private float chestDepth() {
        var girdle = bone("Arm").pivot;
        return girdle.z - front(bone("UpBody").pivot.y, girdle.y, "UpBody", girdle.z);
    }

    /**
     * The most forward depth (smallest z, in pixels) of the vertices between two heights that mostly follow root's
     * subtree but not an arm or the head; fallback when there are none.
     */
    private float front(float low, float high, String root, float fallback) {
        var body = new boolean[bones.size()];
        for (var i = 0; i < bones.size(); i++) {
            body[i] = descendsFrom(i, root) && !descendsFrom(i, "LeftArm") && !descendsFrom(i, "RightArm")
                && !descendsFrom(i, "AllHead") && !descendsFrom(i, "MHead");
        }
        var front = fallback;
        for (var vertex = 0; vertex < positions.size() / 3; vertex++) {
            var y = positions.getFloat(vertex * 3 + 1) * 16f;
            if (y < low || y > high) {
                continue;
            }
            var strongest = 0;
            for (var k = 1; k < 4; k++) {
                if (weights.getFloat(vertex * 4 + k) > weights.getFloat(vertex * 4 + strongest)) {
                    strongest = k;
                }
            }
            if (body[joints.getInt(vertex * 4 + strongest)]) {
                front = Math.min(front, positions.getFloat(vertex * 3 + 2) * 16f);
            }
        }
        return front;
    }

    /**
     * The most forward vertex (smallest z, in pixels) between two heights that mostly follows root's subtree, not an
     * arm, the head or cloth; fallback when there is none. Cloth is a bone off the humanoid skeleton whose subtree
     * holds spring joints (a skirt, coat hem, ribbon or hair chain with its anchor): it hangs under gravity in game, so
     * its rest shape does not show where the body touches the ground.
     */
    private Vector3f rigidFront(float low, float high, String root, Vector3f fallback) {
        var draped = draped();
        var body = new boolean[bones.size()];
        for (var i = 0; i < bones.size(); i++) {
            body[i] = !draped[i] && descendsFrom(i, root) && !descendsFrom(i, "LeftArm")
                && !descendsFrom(i, "RightArm") && !descendsFrom(i, "AllHead") && !descendsFrom(i, "MHead");
        }
        var front = new Vector3f(fallback);
        for (var vertex = 0; vertex < positions.size() / 3; vertex++) {
            var y = positions.getFloat(vertex * 3 + 1) * 16f;
            if (y < low || y > high) {
                continue;
            }
            var strongest = 0;
            for (var k = 1; k < 4; k++) {
                if (weights.getFloat(vertex * 4 + k) > weights.getFloat(vertex * 4 + strongest)) {
                    strongest = k;
                }
            }
            var z = positions.getFloat(vertex * 3 + 2) * 16f;
            if (body[joints.getInt(vertex * 4 + strongest)] && z < front.z) {
                front.set(positions.getFloat(vertex * 3) * 16f, y, z);
            }
        }
        return front;
    }

    /**
     * Per bone, whether it is cloth or hangs from cloth: a bone off the humanoid skeleton whose subtree holds spring
     * joints (a skirt, coat hem, ribbon or hair chain with its anchor), and everything below it.
     */
    private boolean[] draped() {
        var cloth = new boolean[bones.size()];
        var skeleton = new boolean[bones.size()];
        for (var end : List.of("LeftHand", "RightHand", "Head", "LeftFoot", "RightFoot")) {
            for (var current = boneIndex.getOrDefault(end, -1); current >= 0;) {
                skeleton[current] = true;
                var parent = bones.get(current).parent;
                current = parent == null ? -1 : boneIndex.getOrDefault(parent, -1);
            }
        }
        if (springs != null) {
            for (var joint : springs.joints()) {
                for (var current = joint.bone(); current >= 0 && !skeleton[current];) {
                    cloth[current] = true;
                    var parent = bones.get(current).parent;
                    current = parent == null ? -1 : boneIndex.getOrDefault(parent, -1);
                }
            }
        }
        var draped = new boolean[bones.size()];
        for (var i = 0; i < bones.size(); i++) {
            for (var current = i; current >= 0 && !draped[i];) {
                draped[i] = cloth[current];
                var parent = bones.get(current).parent;
                current = parent == null ? -1 : boneIndex.getOrDefault(parent, -1);
            }
        }
        return draped;
    }

    /** Every step-th vertex that mostly follows a bone that is not cloth: that bone and the rest position in pixels. */
    private List<HumanoidMotion.Contact> rigidPoints(int step) {
        var draped = draped();
        var result = new ArrayList<HumanoidMotion.Contact>();
        for (var vertex = 0; vertex < positions.size() / 3; vertex += step) {
            var strongest = 0;
            for (var k = 1; k < 4; k++) {
                if (weights.getFloat(vertex * 4 + k) > weights.getFloat(vertex * 4 + strongest)) {
                    strongest = k;
                }
            }
            var joint = joints.getInt(vertex * 4 + strongest);
            if (joint >= 0 && joint < draped.length && !draped[joint]) {
                result.add(new HumanoidMotion.Contact(bones.get(joint).name, new Vector3f(
                    positions.getFloat(vertex * 3), positions.getFloat(vertex * 3 + 1),
                    positions.getFloat(vertex * 3 + 2)).mul(16f)));
            }
        }
        return result;
    }

    /** The finished legs: closeStance turned the left leg inward by +Z. */
    HumanoidMotion.Legs legs() {
        var left = bone("LeftLeg").pivot.distance(bone("LeftLowerLeg").pivot);
        var right = bone("RightLeg").pivot.distance(bone("RightLowerLeg").pivot);
        var leftShin = bone("LeftLowerLeg").pivot.distance(bone("LeftFoot").pivot);
        var rightShin = bone("RightLowerLeg").pivot.distance(bone("RightFoot").pivot);
        return new HumanoidMotion.Legs((left + right) / 2, (leftShin + rightShin) / 2,
            (float)Math.toDegrees(bone("LeftLeg").rotate.z), renderScale() / 16f,
            (bone("LeftLeg").pivot.y + bone("RightLeg").pivot.y) / 2);
    }

    /** The finished body's measures for the prone transitions and the dive ({@link HumanoidMotion#addProne}). */
    HumanoidMotion.ProneBody proneBody() {
        var hips = bone("AllBody").pivot;
        // The pelvis band: a sixth of the thigh above and below the hips' pivot; the knee's band the same.
        var thigh = bone("LeftLeg").pivot.distance(bone("LeftLowerLeg").pivot);
        var band = thigh / 6f;
        var knee = bone("LeftLowerLeg").pivot;
        return new HumanoidMotion.ProneBody(thigh, bone("LeftLowerLeg").pivot.distance(bone("LeftFoot").pivot),
            rigidFront(hips.y - band, hips.y + band, "AllBody", hips).z, new Vector3f(knee),
            rigidFront(knee.y - band, knee.y + band, "LeftLeg", knee).z, 16f / renderScale(), skeleton(),
            rigidPoints(4));
    }

    /** Facing, arm re-pose and bone naming resolved from a source. */
    private static final class Layout {
        final HumanoidSource source;
        final int nodeCount;
        final Map<String, Integer> humanoid;
        final boolean[] isBone;
        /** Rest-space to YSM-space transform per node: arm re-pose after facing. */
        final Matrix4f[] transforms;
        final Matrix3f[] normalTransforms;
        final Matrix4f facing = new Matrix4f();
        final Matrix3f facingNormal;
        final String[] boneName;
        /** Expressions with offsets and the driver bone name of each. */
        final List<HumanoidSource.Expression> expressions = new ArrayList<>();
        final List<String> expressionBones = new ArrayList<>();
        /** Output vertices (one per material copy, plus outline hull copies) of each source vertex. */
        final Int2ObjectOpenHashMap<IntArrayList> vertexCopies = new Int2ObjectOpenHashMap<>();
        /** Atlas material holding the outline color, per outlined source material. */
        final Int2IntOpenHashMap outlineMaterials = new Int2IntOpenHashMap();
        /** Model units per meter: the hips land at the default model's pixel height. */
        final float unit;
        /** Finger segments root to tip per hand and finger, with their YSM names. */
        final List<FingerChain> fingers = new ArrayList<>();

        record FingerChain(boolean left, String finger, List<Integer> nodes) {
        }

        Layout(HumanoidSource source) throws IOException {
            this.source = source;
            this.nodeCount = source.nodeCount();
            this.humanoid = source.humanoid;
            validateMesh();

            if (restPosition(humanoid.get("leftUpperLeg")).x > restPosition(humanoid.get("rightUpperLeg")).x) {
                facing.rotationY((float)Math.PI);
            }
            facingNormal = facing.normal(new Matrix3f());

            isBone = new boolean[nodeCount];
            for (var i = 0; i < source.joints.size(); i++) {
                var joint = source.joints.getInt(i);
                if (joint >= 0 && source.weights.getFloat(i) > 0f) {
                    isBone[joint] = true;
                }
            }
            humanoid.values().forEach(index -> isBone[index] = true);
            // Spring joints must be bones to rotate, colliders and chain centers to follow their node.
            var humanoidNodes = new HashSet<>(humanoid.values());
            for (var joint : source.springJoints) {
                if (!humanoidNodes.contains(joint.node())) {
                    isBone[joint.node()] = true;
                }
            }
            source.springColliders.forEach(collider -> isBone[collider.node()] = true);
            source.springChains.forEach(chain -> {
                if (chain.center() >= 0) {
                    isBone[chain.center()] = true;
                }
            });
            for (var i = 0; i < nodeCount; i++) {
                if (isBone[i]) {
                    for (var p = skeletonParent(i); p >= 0 && !isBone[p]; p = skeletonParent(p)) {
                        isBone[p] = true;
                    }
                }
            }

            var leftArm = armRepose("leftUpperArm", "leftLowerArm");
            var rightArm = armRepose("rightUpperArm", "rightLowerArm");
            transforms = new Matrix4f[nodeCount];
            normalTransforms = new Matrix3f[nodeCount];
            for (var i = 0; i < nodeCount; i++) {
                var repose = descends(i, humanoid.get("leftUpperArm")) ? leftArm
                    : descends(i, humanoid.get("rightUpperArm")) ? rightArm : new Matrix4f();
                transforms[i] = new Matrix4f(repose).mul(facing);
                normalTransforms[i] = transforms[i].normal(new Matrix3f());
            }
            var hipsNode = humanoid.getOrDefault("lowerBody", humanoid.get("hips"));
            var hipsHeight = transforms[hipsNode].transformPosition(restPosition(hipsNode)).y;
            unit = hipsHeight > 0.05f ? DEFAULT_HIPS_PIXELS / (hipsHeight * 16f) : 1f;

            boneName = new String[nodeCount];
            var used = new HashSet<String>(HUMANOID_NAMES.values());
            used.addAll(List.of("Root", "Arm"));
            used.addAll(DEFAULT_ONLY_BONES);
            LOCATORS.forEach(locator -> used.add(locator.name));
            // Expression driver bones claim their names before nodes do.
            for (var expression : source.expressions) {
                if (expression.vertices.isEmpty() || expression.name.isBlank()) {
                    continue;
                }
                var name = expression.name.equals("blink") ? BLINK_BONE : EXPRESSION_BONE_PREFIX + expression.name;
                if (used.add(name)) {
                    expressions.add(expression);
                    expressionBones.add(name);
                }
            }
            humanoid.forEach((bone, node) -> {
                var mapped = HUMANOID_NAMES.get(bone);
                if (mapped != null) {
                    boneName[node] = mapped;
                }
            });
            for (var left : new boolean[] {true, false}) {
                for (var finger : HumanoidMapping.FINGERS) {
                    var prefix = (left ? "left" : "right") + finger;
                    var nodes = new ArrayList<Integer>();
                    humanoid.forEach((bone, node) -> {
                        if (bone.startsWith(prefix) && !nodes.contains(node)) {
                            nodes.add(node);
                        }
                    });
                    if (nodes.isEmpty()) {
                        continue;
                    }
                    nodes.sort(java.util.Comparator.comparingInt(this::depth));
                    for (var i = 0; i < nodes.size(); i++) {
                        var name = (left ? "Left" : "Right") + finger + (i + 1);
                        used.add(name);
                        boneName[nodes.get(i)] = name;
                    }
                    fingers.add(new FingerChain(left, finger, nodes));
                }
            }
            for (var i = 0; i < nodeCount; i++) {
                if (isBone[i] && boneName[i] == null) {
                    var base = source.nodeNames.get(i).isBlank() ? "node" + i : source.nodeNames.get(i);
                    var name = base;
                    for (var suffix = 2; !used.add(name); suffix++) {
                        name = base + "_" + suffix;
                    }
                    boneName[i] = name;
                }
            }
        }

        private void validateMesh() throws IOException {
            var vertices = source.vertexCount();
            if (source.normals.size() != vertices * 3 || source.uvs.size() != vertices * 2
                || source.joints.size() != vertices * 4 || source.weights.size() != vertices * 4
                || source.triangles.size() % 3 != 0 || source.triangleMaterials.size() != source.triangles.size() / 3) {
                throw new IOException("Inconsistent humanoid mesh streams");
            }
            if (source.triangles.isEmpty()) {
                throw new IOException("Model has no triangle geometry");
            }
            for (var i = 0; i < source.triangles.size(); i++) {
                var index = source.triangles.getInt(i);
                if (index < 0 || index >= vertices) {
                    throw new IOException("Triangle index out of range");
                }
            }
            for (var i = 0; i < source.triangleMaterials.size(); i++) {
                var material = source.triangleMaterials.getInt(i);
                if (material < 0 || material >= source.materials.size()) {
                    throw new IOException("Material index out of range");
                }
            }
            for (var i = 0; i < source.joints.size(); i++) {
                var joint = source.joints.getInt(i);
                if (joint < HumanoidSource.ROOT_JOINT || joint >= nodeCount) {
                    throw new IOException("Joint index out of range");
                }
            }
        }

        int skeletonParent(int node) {
            return source.skeletonParents.getInt(node);
        }

        Vector3f restPosition(int node) {
            return new Vector3f(source.nodePositions.getFloat(node * 3), source.nodePositions.getFloat(node * 3 + 1),
                source.nodePositions.getFloat(node * 3 + 2));
        }

        /** Rest position of a node in YSM axes, before re-posing. */
        Vector3f facingPosition(int node) {
            return facing.transformPosition(restPosition(node));
        }

        private Matrix4f armRepose(String upper, String lower) {
            var shoulder = facingPosition(humanoid.get(upper));
            var direction = facingPosition(humanoid.get(lower)).sub(shoulder);
            if (direction.lengthSquared() < 1e-12f) {
                return new Matrix4f();
            }
            var rotation = new Quaternionf().rotationTo(direction.normalize(), new Vector3f(0, -1, 0));
            return new Matrix4f().translation(shoulder).rotate(rotation).translate(-shoulder.x, -shoulder.y,
                -shoulder.z);
        }

        private boolean descends(int node, @Nullable Integer ancestor) {
            if (ancestor == null) {
                return false;
            }
            for (var current = node; current >= 0; current = skeletonParent(current)) {
                if (current == ancestor) {
                    return true;
                }
            }
            return false;
        }

        /** Pivot of a node in YSM pixels. */
        Vector3f pivot(int node) {
            return transforms[node].transformPosition(restPosition(node)).mul(16f * unit);
        }

        int nearestBone(int node) {
            for (var current = node; current >= 0; current = skeletonParent(current)) {
                if (isBone[current]) {
                    return current;
                }
            }
            return -1;
        }

        void emitSkeleton(HumanoidImport importer) {
            var hips = humanoid.get("hips");
            var lowerBody = humanoid.get("lowerBody");
            var head = humanoid.get("head");
            var neck = humanoid.get("neck");
            var leftChain = humanoid.getOrDefault("leftShoulder", humanoid.get("leftUpperArm"));
            var rightChain = humanoid.getOrDefault("rightShoulder", humanoid.get("rightUpperArm"));
            Set<Integer> legs = Set.of(humanoid.get("leftUpperLeg"), humanoid.get("rightUpperLeg"));
            // A pivot only moves the rotation center, not rest geometry. MMD's hips (センター) sits well below the
            // waist; rotate the whole body about the pelvis bone instead, as the default skeleton does.
            var hipsPivot = lowerBody != null ? pivot(lowerBody) : pivot(hips);

            importer.addBone(new BoneDef("Root", null, new Vector3f()));
            for (var i = 0; i < nodeCount; i++) {
                if (boneName[i] == null) {
                    continue;
                }
                String parentName;
                if (i == hips) {
                    parentName = "MAllBody";
                } else if (lowerBody != null && i == lowerBody) {
                    parentName = "AllBody";
                } else if (lowerBody == null && legs.contains(i)) {
                    parentName = "DownBody";
                } else if (i == leftChain || i == rightChain) {
                    parentName = "Arm";
                } else if (i == head && neck == null) {
                    parentName = "AllHead";
                } else {
                    var p = nearestBone(skeletonParent(i));
                    parentName = p < 0 ? "Root" : boneName[p];
                }
                importer.addBone(new BoneDef(boneName[i], parentName, i == hips ? new Vector3f(hipsPivot) : pivot(i)));
            }

            if (lowerBody == null) {
                importer.addBone(new BoneDef("DownBody", "AllBody", pivot(hips)));
            }
            var armParent = nearestBone(skeletonParent(leftChain));
            var armPivot = pivot(humanoid.get("leftUpperArm")).add(pivot(humanoid.get("rightUpperArm"))).mul(0.5f);
            importer.addBone(new BoneDef("Arm", armParent < 0 ? "UpBody" : boneName[armParent], armPivot));
            if (neck == null) {
                var headParent = nearestBone(skeletonParent(head));
                importer.addBone(new BoneDef("AllHead", headParent < 0 ? "UpBody" : boneName[headParent], pivot(head)));
            }

            importer.addBone(new BoneDef("MAllBody", "Root", new Vector3f(hipsPivot)));
            // The chest turns above the waist: everything the spine carries hangs from UpperBody at the spine's pivot.
            var upperBody = new BoneDef("UpperBody", "UpBody", new Vector3f(importer.bone("UpBody").pivot));
            for (var bone : importer.bones) {
                if ("UpBody".equals(bone.parent)) {
                    bone.parent = upperBody.name;
                }
            }
            importer.addBone(upperBody);
            var headBone = importer.bone("Head");
            importer.addBone(new BoneDef("MHead", headBone.parent, new Vector3f(headBone.pivot)));
            headBone.parent = "MHead";

            importer.bone("LeftArm").rotate.set(0, 0, (float)Math.toRadians(-15));
            importer.bone("RightArm").rotate.set(0, 0, (float)Math.toRadians(15));
            closeStance(importer);

            var scale = hipsPivot.y / DEFAULT_HIPS_PIXELS;
            for (var locator : LOCATORS) {
                var parentBone = importer.bone(locator.parent);
                // Hand locators sit in the palm, where a held item's grip goes, not at the wrist pivot.
                var origin = locator.parent.endsWith("Hand")
                    ? importer.palm(locator.parent.substring(0, locator.parent.length() - 4)) : parentBone.pivot;
                var def = new BoneDef(locator.name, locator.parent,
                    new Vector3f(locator.x, locator.y, locator.z).mul(scale).add(origin));
                def.rotate.set((float)Math.toRadians(locator.rx), (float)Math.toRadians(locator.ry),
                    (float)Math.toRadians(locator.rz));
                importer.addBone(def);
            }
            // Driver bones carry no geometry; animations set their attributes to weigh the expression.
            for (var name : expressionBones) {
                importer.addBone(new BoneDef(name, "Root", pivot(head)));
            }
        }

        /**
         * Ankle spacing as a fraction of the hip joint spacing: feet nearly together, like the source games' stance.
         */
        private static final float STANCE = 0.4f;
        /** Largest inward turn of a leg; a wider stance than this needs is left partly open. */
        private static final float MAX_STANCE_DEGREES = 10f;

        /**
         * Brings the ankles in to {@link #STANCE} of the hip joint spacing by turning both legs inward at the hip, the
         * feet turned back so the soles stay level. Models drawn in a hip-wide stance otherwise stand and walk with
         * their feet far apart. Animations add to this rest pose, so every action keeps the closed stance.
         */
        private void closeStance(HumanoidImport importer) {
            var leftHip = pivot(humanoid.get("leftUpperLeg"));
            var rightHip = pivot(humanoid.get("rightUpperLeg"));
            var leftAnkle = pivot(humanoid.get("leftFoot"));
            var rightAnkle = pivot(humanoid.get("rightFoot"));
            var hipHalf = (rightHip.x - leftHip.x) / 2;
            var ankleHalf = (rightAnkle.x - leftAnkle.x) / 2;
            var legLength = (leftHip.distance(leftAnkle) + rightHip.distance(rightAnkle)) / 2;
            var shift = ankleHalf - STANCE * hipHalf;
            if (!(shift > 0f && legLength > 0f)) {
                return;
            }
            var angle = (float)Math.min(Math.asin(Math.min(1f, shift / legLength)), Math.toRadians(MAX_STANCE_DEGREES));
            // Left is -X: turning its leg by +Z swings the ankle toward +X, the body's midline.
            importer.bone("LeftLeg").rotate.set(0, 0, angle);
            importer.bone("RightLeg").rotate.set(0, 0, -angle);
            importer.bone("LeftFoot").rotate.set(0, 0, -angle);
            importer.bone("RightFoot").rotate.set(0, 0, angle);
        }

        /** Curl per segment, root to tip, in degrees: relaxed at rest, gripping an item or a ladder. */
        private static final float[] RELAXED = {15f, 25f, 15f};
        private static final float[] RELAXED_THUMB = {5f, 10f, 10f};
        private static final float[] GRIP = {80f, 95f, 60f};
        private static final float[] GRIP_THUMB = {25f, 30f, 30f};
        /**
         * Holding a TACZ gun in third person: the gun hand wraps the grip with the index finger nearly straight along
         * the trigger guard; the support hand cups the handguard, curled less than a fist.
         */
        private static final float[] GUN_TRIGGER = {10f, 20f, 10f};
        private static final float[] GUN_GRIP = {65f, 75f, 45f};
        private static final float[] GUN_SUPPORT = {45f, 55f, 35f};
        private static final float[] GUN_THUMB = {20f, 25f, 25f};
        /**
         * First person on a gun without a fitted grip: a relaxed wrap round an invisible grip rather than a balled
         * fist. The default block arm ends in a fist because TACZ poses each arm for that shape; an imported human
         * hand reads as clenched fingers through the trigger guard, so it keeps the fingers apart and only slightly
         * curled, index nearly straight along the frame.
         */
        private static final float[] HOLD_INDEX = {18f, 26f, 14f};
        private static final float[] HOLD_MIDDLE = {40f, 50f, 30f};
        private static final float[] HOLD_RING = {46f, 56f, 36f};
        private static final float[] HOLD_LITTLE = {52f, 60f, 40f};
        private static final float[] HOLD_THUMB = {14f, 18f, 16f};
        /** Sprinting empty-handed: loose fists. */
        private static final float[] RUN_FIST = {55f, 70f, 45f};
        private static final float[] RUN_THUMB = {15f, 20f, 20f};
        private static final String RUN = "ctrl.run";
        /** Ring and little fingers rest a little more curled than the index. */
        private static final Map<String, Float> RELAXED_EXTRA = Map.of("Ring", 5f, "Little", 10f);
        private static final String OPEN = "(ctrl.elytra_fly || ctrl.swim)";
        private static final String LADDER = "ctrl.ladder_up || ctrl.ladder_down || ctrl.ladder_stillness";
        /** Without TACZ the query is unset, so false. */
        private static final String GUN = "ctrl.tac_hold_gun";
        /**
         * First person on a gun with a fitted grip (TaczGrips): the gun hand wraps the pistol grip with the trigger
         * finger straight along the frame, swung toward the thumb to lie level; the support hand curls up the
         * handguard's far side, the little finger most. The wrist turns come from the grip.
         */
        private static final String FITTED = "ctrl.tac_grip('fitted')";
        private static final Map<String,
            float[]> FITTED_GUN_HAND = Map.of("Thumb", new float[] {30f, 30f, 25f}, "Index",
                new float[] {16.5f, 22f, 11f}, "Middle", new float[] {88f, 93.5f, 60.5f}, "Ring",
                new float[] {93.5f, 93.5f, 60.5f}, "Little", new float[] {93.5f, 93.5f, 60.5f});
        private static final Map<String,
            float[]> FITTED_SUPPORT_HAND = Map.of("Thumb", new float[] {15f, 20f, 15f}, "Index",
                new float[] {40f, 50f, 35f}, "Middle", new float[] {45f, 55f, 35f}, "Ring", new float[] {50f, 55f, 35f},
                "Little", new float[] {55f, 55f, 35f});
        private static final float TRIGGER_SPREAD = -25f;

        /**
         * Curls each finger toward the palm: a relaxed rest rotation for every action, plus a generated parallel2
         * animation that holds a TACZ gun with a trigger finger and a support hand, grips while the hand holds an item
         * (main hand right, off hand left) or climbs a ladder, makes loose fists while sprinting, and straightens the
         * fingers while gliding or swimming. Palms face the body after the arm re-pose, so a finger curls about its
         * direction crossed with the inward axis. Rest and animation rotations add per Euler component; the offsets
         * are exact at full grip or open.
         *
         * <p>
         * The first-person arm set gets only the hands ({@link HumanoidImport#armAnimation()}). On a gun with a fitted
         * grip the wrists turn and the fingers take that grip; on any other gun the hand keeps a relaxed wrap round an
         * invisible grip ({@link #HOLD_INDEX}) instead of a fist: TACZ places each first-person arm for a block arm
         * whose end meets the gun, and a real hand reads as clenched fingers through the trigger guard, so the fingers
         * stay apart and only slightly curled, the index nearly straight along the frame.
         *
         * @return the animations of the main set; locomotion, gun and prone clips are added once the mesh exists
         */
        JsonObject emitAnimations(HumanoidImport importer) {
            var fingerBones = new JsonObject();
            var armFingerBones = new JsonObject();
            var rotation = new Matrix3f();
            for (var chain : fingers) {
                var nodes = chain.nodes();
                var hand = humanoid.get(chain.left() ? "leftHand" : "rightHand");
                var direction = nodes.size() > 1 ? pivot(nodes.get(1)).sub(pivot(nodes.get(0)))
                    : pivot(nodes.get(0)).sub(pivot(hand));
                var axis = direction.normalize().cross(chain.left() ? 1f : -1f, 0f, 0f);
                if (!(axis.lengthSquared() > 1e-6f)) {
                    continue;
                }
                axis.normalize();
                var thumb = chain.finger().equals("Thumb");
                var relaxed = thumb ? RELAXED_THUMB : RELAXED;
                var grip = thumb ? GRIP_THUMB : GRIP;
                var gun = thumb ? GUN_THUMB
                    : chain.left() ? GUN_SUPPORT : chain.finger().equals("Index") ? GUN_TRIGGER : GUN_GRIP;
                // First person on a gun without a fitted grip: neutral wrap instead of a fist.
                var hold = thumb ? HOLD_THUMB
                    : chain.finger().equals("Index") ? HOLD_INDEX
                        : chain.finger().equals("Middle") ? HOLD_MIDDLE
                            : chain.finger().equals("Ring") ? HOLD_RING : HOLD_LITTLE;
                var run = thumb ? RUN_THUMB : RUN_FIST;
                var fitted = (chain.left() ? FITTED_SUPPORT_HAND : FITTED_GUN_HAND).get(chain.finger());
                var grips = chain.left() ? "ysm.has_offhand || " + LADDER : "ysm.has_mainhand || " + LADDER;
                for (var i = 0; i < nodes.size(); i++) {
                    var segment = Math.min(i, relaxed.length - 1);
                    var rest = euler(rotation, axis, relaxed[segment] + RELAXED_EXTRA.getOrDefault(chain.finger(), 0f));
                    var held = euler(rotation, axis, grip[segment]);
                    var aimed = euler(rotation, axis, gun[segment]);
                    var holding = euler(rotation, axis, hold[segment]);
                    var running = euler(rotation, axis, run[segment]);
                    var fit = fitted == null ? held
                        : !chain.left() && i == 0 && chain.finger().equals("Index")
                            ? rotation.rotation((float)Math.toRadians(TRIGGER_SPREAD), -1f, 0f, 0f)
                                .mul(new Matrix3f().rotation((float)Math.toRadians(fitted[segment]), axis))
                                .getEulerAnglesZYX(new Vector3f())
                            : euler(rotation, axis, fitted[segment]);
                    importer.bone(boneName[nodes.get(i)]).rotate.set(rest);
                    var values = new JsonArray();
                    var armValues = new JsonArray();
                    for (var c = 0; c < 3; c++) {
                        // Keyframes store X and Y negated, in degrees.
                        var sign = c < 2 ? -1f : 1f;
                        var open = sign * (float)Math.toDegrees(-rest.get(c));
                        var closed = sign * (float)Math.toDegrees(held.get(c) - rest.get(c));
                        var armed = sign * (float)Math.toDegrees(aimed.get(c) - rest.get(c));
                        var neutral = sign * (float)Math.toDegrees(holding.get(c) - rest.get(c));
                        var loose = sign * (float)Math.toDegrees(running.get(c) - rest.get(c));
                        var placed = sign * (float)Math.toDegrees(fit.get(c) - rest.get(c));
                        var still = Math.abs(open) < 0.005f && Math.abs(closed) < 0.005f;
                        values.add(still && Math.abs(armed) < 0.005f && Math.abs(loose) < 0.005f ? "0"
                            : String.format(Locale.ROOT, "%s ? %.2f : (%s ? %.2f : ((%s) ? %.2f : (%s ? %.2f : 0)))",
                                OPEN, open, GUN, armed, grips, closed, RUN, loose));
                        armValues.add(still && Math.abs(placed) < 0.005f && Math.abs(neutral) < 0.005f ? "0"
                            : String.format(Locale.ROOT,
                                "%s ? %.2f : (%s ? %.2f : (%s ? %.2f : ((%s) ? %.2f : 0)))", OPEN,
                                open, FITTED, placed, GUN, neutral, grips, closed));
                    }
                    var bone = new JsonObject();
                    bone.add("rotation", values);
                    fingerBones.add(boneName[nodes.get(i)], bone);
                    var armBone = new JsonObject();
                    armBone.add("rotation", armValues);
                    armFingerBones.add(boneName[nodes.get(i)], armBone);
                }
            }
            var animations = new JsonObject();
            if (fingerBones.size() > 0) {
                var animation = new JsonObject();
                animation.addProperty("loop", true);
                animation.add("bones", fingerBones);
                animations.add("parallel2", animation);
            }
            var armAnimations = new JsonObject();
            if (armFingerBones.size() > 0) {
                // The wrists turn onto a fitted grip; TaczGrips slides the arm so the palm lands.
                for (var side : List.of("left", "right")) {
                    var node = humanoid.get(side + "Hand");
                    var values = new JsonArray();
                    for (var axis : List.of("x", "y", "z")) {
                        values.add("ctrl.tac_grip('" + side + "_" + axis + "')");
                    }
                    var wrist = new JsonObject();
                    wrist.add("rotation", values);
                    armFingerBones.add(boneName[node], wrist);
                }
                var grip = new JsonObject();
                grip.addProperty("loop", true);
                grip.add("bones", armFingerBones);
                armAnimations.add("parallel2", grip);
            }
            importer.armAnimation = animationFile(armAnimations);
            return animations;
        }

        private static String animationFile(JsonObject animations) {
            var file = new JsonObject();
            file.addProperty("format_version", "1.8.0");
            file.add("animations", animations);
            return file.toString();
        }

        /** Euler angles (x, y, z) such that Rz(z) Ry(y) Rx(x) rotates about axis by degrees, the native order. */
        private static Vector3f euler(Matrix3f scratch, Vector3f axis, float degrees) {
            return scratch.rotation((float)Math.toRadians(degrees), axis).getEulerAnglesZYX(new Vector3f());
        }

        private int depth(int node) {
            var depth = 0;
            for (var current = skeletonParent(node); current >= 0 && depth <= nodeCount;
                current = skeletonParent(current)) {
                depth++;
            }
            return depth;
        }

        /**
         * One morph target per expression, driven by its bone: "Eyelid" closes by scale like the default blink
         * animation expects, other expressions weigh by X translation. Offsets follow the same blended re-pose as their
         * vertices and apply to every material copy of a vertex.
         */
        List<MorphTargetProto> emitMorphs(HumanoidImport importer) {
            var morphs = new ArrayList<MorphTargetProto>();
            var delta = new Vector3f();
            var moved = new Vector3f();
            var blended = new Vector3f();
            for (var e = 0; e < expressions.size(); e++) {
                var expression = expressions.get(e);
                var name = expressionBones.get(e);
                var morph = MorphTargetProto.newBuilder().setBone(importer.boneIndex.get(name))
                    .setDriver(name.equals(BLINK_BONE) ? 1 : 0);
                for (var i = 0; i < expression.vertices.size(); i++) {
                    var vertex = expression.vertices.getInt(i);
                    var outputs = vertexCopies.get(vertex);
                    if (outputs == null) {
                        continue;
                    }
                    delta.set(expression.deltas.getFloat(i * 3), expression.deltas.getFloat(i * 3 + 1),
                        expression.deltas.getFloat(i * 3 + 2));
                    blended.zero();
                    var total = 0f;
                    for (var k = 0; k < 4; k++) {
                        var weight = source.weights.getFloat(vertex * 4 + k);
                        if (weight <= 0f) {
                            continue;
                        }
                        var joint = source.joints.getInt(vertex * 4 + k);
                        (joint >= 0 ? transforms[joint] : facing).transformDirection(moved.set(delta));
                        blended.fma(weight, moved);
                        total += weight;
                    }
                    if (total > 0f) {
                        blended.div(total);
                    } else {
                        facing.transformDirection(blended.set(delta));
                    }
                    blended.mul(unit);
                    for (var c = 0; c < outputs.size(); c++) {
                        morph.addVertices(outputs.getInt(c)).addDeltas(blended.x, blended.y, blended.z);
                    }
                }
                morphs.add(morph.build());
            }
            return morphs;
        }

        /**
         * Spring joints, colliders and chains in YSM rest space. Joints on humanoid bones are dropped: the default
         * animations own those.
         */
        @Nullable
        SpringBonesProto emitSprings(HumanoidImport importer) {
            var humanoidNodes = new HashSet<>(humanoid.values());
            var springs = SpringBonesProto.newBuilder();
            for (var collider : source.springColliders) {
                var transform = transforms[collider.node()];
                var head = transform.transformPosition(new Vector3f(collider.head())).mul(unit);
                var tail = transform.transformPosition(new Vector3f(collider.tail())).mul(unit);
                springs.addColliders(SpringColliderProto.newBuilder()
                    .setBone(importer.boneIndex.get(boneName[collider.node()])).addHead(head.x, head.y, head.z)
                    .addTail(tail.x, tail.y, tail.z).setRadius(collider.radius() * unit).setType(collider.type())
                    .build());
            }
            for (var chain : source.springChains) {
                var proto = SpringChainProto.newBuilder().addColliders(chain.colliders());
                if (chain.center() >= 0) {
                    proto.setCenter(importer.boneIndex.get(boneName[chain.center()]) + 1);
                }
                springs.addChains(proto.build());
            }
            var used = 0;
            for (var joint : source.springJoints) {
                if (humanoidNodes.contains(joint.node())) {
                    continue;
                }
                var tail = transforms[joint.node()].transformPosition(new Vector3f(joint.tail())).mul(unit);
                // Gravity is a world direction: only the facing turn applies.
                var gravity = facingNormal.transform(new Vector3f(joint.gravity()));
                if (gravity.lengthSquared() > 0f) {
                    gravity.normalize();
                }
                // Stiffness and gravity move the tail in meters per second; model space is scaled by unit.
                var proto = SpringJointProto.newBuilder().setBone(importer.boneIndex.get(boneName[joint.node()]))
                    .addTail(tail.x, tail.y, tail.z).setHitRadius(joint.hitRadius() * unit)
                    .setStiffness(joint.stiffness() * unit).setDrag(joint.drag())
                    .setGravityPower(joint.gravityPower() * unit).addGravityDir(gravity.x, gravity.y, gravity.z)
                    .setChain(joint.chain());
                var limit = joint.limit();
                if (limit != null) {
                    // The limit space turns like the joint's node from rest to YSM space.
                    var frame = transforms[joint.node()].getNormalizedRotation(new Quaternionf()).mul(limit.frame())
                        .normalize();
                    proto.setLimit(limit.type()).addLimitRotation(frame.x, frame.y, frame.z, frame.w)
                        .setLimitAngle(limit.angle()).setLimitYaw(limit.yaw());
                }
                springs.addJoints(proto.build());
                used++;
            }
            return used == 0 ? null : springs.build();
        }

        /**
         * Re-poses rest vertices by blending each joint's re-pose transform, and splits vertices shared by materials so
         * each copy gets its own atlas region. Then evens out face shading and adds the outline hull.
         */
        void emitMesh(HumanoidImport importer) {
            var rootBone = importer.boneIndex.get("Root");
            var copies = new Long2IntOpenHashMap();
            copies.defaultReturnValue(-1);
            var materialCount = Math.max(source.materials.size(), 1);
            var outputMaterials = new IntArrayList();
            var outputSources = new IntArrayList();
            var p = new Vector3f();
            var n = new Vector3f();
            var position = new Vector3f();
            var normal = new Vector3f();
            var uv = new float[2];
            for (var t = 0; t < source.triangles.size(); t++) {
                var vertex = source.triangles.getInt(t);
                var material = source.triangleMaterials.getInt(t / 3);
                var key = (long)vertex * materialCount + material;
                var output = copies.get(key);
                if (output < 0) {
                    output = importer.positions.size() / 3;
                    copies.put(key, output);
                    outputMaterials.add(material);
                    outputSources.add(vertex);
                    vertexCopies.computeIfAbsent(vertex, ignored -> new IntArrayList()).add(output);
                    position.zero();
                    normal.zero();
                    var total = 0f;
                    for (var k = 0; k < 4; k++) {
                        var weight = source.weights.getFloat(vertex * 4 + k);
                        if (weight <= 0f) {
                            continue;
                        }
                        var joint = source.joints.getInt(vertex * 4 + k);
                        restVertex(vertex, p, n);
                        if (joint >= 0) {
                            transforms[joint].transformPosition(p);
                            normalTransforms[joint].transform(n);
                        } else {
                            facing.transformPosition(p);
                            facingNormal.transform(n);
                        }
                        position.fma(weight, p);
                        normal.fma(weight, n);
                        total += weight;
                    }
                    if (total > 0f) {
                        position.div(total);
                    } else {
                        restVertex(vertex, position, normal);
                        facing.transformPosition(position);
                        facingNormal.transform(normal);
                    }
                    if (normal.lengthSquared() > 0f) {
                        normal.normalize();
                    }
                    importer.positions.add(position.x * unit);
                    importer.positions.add(position.y * unit);
                    importer.positions.add(position.z * unit);
                    importer.normals.add(normal.x);
                    importer.normals.add(normal.y);
                    importer.normals.add(normal.z);
                    importer.atlas.remap(material, source.uvs.getFloat(vertex * 2), source.uvs.getFloat(vertex * 2 + 1),
                        uv, 0);
                    importer.uvs.add(uv[0]);
                    importer.uvs.add(uv[1]);
                    var written = 0;
                    for (var k = 0; k < 4; k++) {
                        var weight = source.weights.getFloat(vertex * 4 + k);
                        var joint = source.joints.getInt(vertex * 4 + k);
                        if (weight > 0f && total > 0f) {
                            importer.joints.add(joint >= 0 ? importer.boneIndex.get(boneName[joint]) : rootBone);
                            importer.weights.add(weight / total);
                            written++;
                        }
                    }
                    if (written == 0) {
                        importer.joints.add(rootBone);
                        importer.weights.add(1f);
                        written = 1;
                    }
                    for (; written < 4; written++) {
                        importer.joints.add(0);
                        importer.weights.add(0f);
                    }
                }
                importer.triangles.add(output);
            }
            // Formats disagree on winding (MMD's front faces are clockwise); Minecraft's front faces, and the toon
            // shader's gl_FrontFacing, are counter-clockwise, so every triangle turns to face along its normals.
            for (var t = 0; t < importer.triangles.size(); t += 3) {
                if (!facesNormals(importer, importer.triangles.getInt(t), importer.triangles.getInt(t + 1),
                    importer.triangles.getInt(t + 2))) {
                    var b = importer.triangles.getInt(t + 1);
                    importer.triangles.set(t + 1, importer.triangles.getInt(t + 2));
                    importer.triangles.set(t + 2, b);
                }
            }
            smoothFaces(importer, outputMaterials, outputSources);
            emitOutlines(importer, copies, materialCount);
            classifySurfaces(importer, outputMaterials);
            importer.vertexMaterials = outputMaterials.toIntArray();
        }

        /** Material and bone names of surface classes: Japanese, Chinese and English, as model authors name them. */
        private static final java.util.regex.Pattern HAIR_NAME =
            java.util.regex.Pattern.compile("(?i)髪|发|髮|hair|ヘア|kami|ponytail|twintail|ツイン|もみあげ|bang|fringe|ahoge|アホ毛");
        private static final java.util.regex.Pattern EYE_NAME =
            java.util.regex.Pattern.compile("(?i)瞳|眼球|白目|ハイライト|eye(?!brow|lash|lid|line)|iris|pupil");
        private static final java.util.regex.Pattern METAL_NAME = java.util.regex.Pattern
            .compile("(?i)金属|metal|鉄|鋼|銀|金(?!髪)|steel|iron|silver|gold|chrome|armou?r|鎧|武器|weapon|gun|銃|sword|剣|blade");
        private static final java.util.regex.Pattern SKIN_NAME =
            java.util.regex.Pattern.compile("(?i)肌|皮肤|皮膚|skin|hada");
        private static final java.util.regex.Pattern CLOTH_NAME = java.util.regex.Pattern.compile(
            "(?i)服|衣|cloth|shirt|skirt|スカート|dress|coat|jacket|pants|靴|shoe|boot|sock|glove|手袋|ribbon|リボン|uniform");

        /**
         * Gives every output triangle a surface class for the specular map. The eyes and hair follow their bones as
         * well as their material names, since many models are a single material: triangles mostly weighted to the eye
         * bones are eyes, to hair bones (named as hair, or spring bones on the head, and their children) hair. Other
         * triangles take the class of their material name; unnamed ones mostly on the head are face where skin-toned,
         * and the rest skin or cloth by color.
         */
        private void classifySurfaces(HumanoidImport importer, IntArrayList outputMaterials) {
            var boneCount = importer.bones.size();
            var springBones = new HashSet<String>();
            for (var joint : source.springJoints) {
                if (boneName[joint.node()] != null) {
                    springBones.add(boneName[joint.node()]);
                }
            }
            var hairRoot = new boolean[boneCount];
            var onHead = new boolean[boneCount];
            for (var i = 0; i < boneCount; i++) {
                var name = importer.bones.get(i).name;
                onHead[i] = importer.descendsFrom(i, "Head");
                hairRoot[i] = HAIR_NAME.matcher(name).find() || onHead[i] && springBones.contains(name);
            }
            var eye = new boolean[boneCount];
            var hair = new boolean[boneCount];
            for (var i = 0; i < boneCount; i++) {
                var name = importer.bones.get(i).name;
                eye[i] = name.equals("LeftEye") || name.equals("RightEye") || EYE_NAME.matcher(name).find();
                for (var bone = importer.bones.get(i); bone != null && !hair[i];) {
                    var index = importer.boneIndex.get(bone.name);
                    hair[i] = hairRoot[index];
                    bone = bone.parent == null ? null : importer.bones.get(importer.boneIndex.get(bone.parent));
                }
            }
            importer.triangleClasses.clear();
            for (var t = 0; t < importer.triangles.size(); t += 3) {
                float total = 0, eyeWeight = 0, hairWeight = 0, headWeight = 0;
                for (var c = 0; c < 3; c++) {
                    var vertex = importer.triangles.getInt(t + c);
                    for (var k = 0; k < 4; k++) {
                        var weight = importer.weights.getFloat(vertex * 4 + k);
                        var joint = importer.joints.getInt(vertex * 4 + k);
                        total += weight;
                        if (eye[joint]) {
                            eyeWeight += weight;
                        } else if (hair[joint]) {
                            hairWeight += weight;
                        } else if (onHead[joint]) {
                            headWeight += weight;
                        }
                    }
                }
                var name = source.materials.get(outputMaterials.getInt(importer.triangles.getInt(t))).name();
                byte type;
                if (EYE_NAME.matcher(name).find() || eyeWeight >= 0.5f * total) {
                    type = SpecularAtlas.EYE;
                } else if (HAIR_NAME.matcher(name).find() || hairWeight >= 0.5f * total) {
                    type = SpecularAtlas.HAIR;
                } else if (METAL_NAME.matcher(name).find()) {
                    type = SpecularAtlas.METAL;
                } else if (FACE_SKIN.matcher(name).find()) {
                    type = SpecularAtlas.FACE;
                } else if (SKIN_NAME.matcher(name).find()) {
                    type = SpecularAtlas.SKIN;
                } else if (CLOTH_NAME.matcher(name).find()) {
                    type = SpecularAtlas.CLOTH;
                } else if (headWeight >= 0.7f * total) {
                    type = SpecularAtlas.FACE_TONE;
                } else {
                    type = SpecularAtlas.UNKNOWN;
                }
                importer.triangleClasses.add(type);
            }
        }

        /** Material names of a face's skin, and of the features drawn on or set into it. */
        private static final java.util.regex.Pattern FACE_SKIN =
            java.util.regex.Pattern.compile("(?i)颜|顔|脸|臉|face|kao");
        private static final java.util.regex.Pattern FACE_PART =
            java.util.regex.Pattern.compile("(?i)颜|顔|脸|臉|face|kao|目|眉|睫|瞳|eye|brow|lash|iris|mouth");
        /** How far face normals turn from the geometry toward the fitted ellipsoid. */
        private static final float FACE_NORMAL_BLEND = 0.85f;

        /**
         * Anime faces are lit like a smooth egg; shading from the real geometry puts blotches beside the nose and under
         * the cheeks. Normals of face materials under the head bone bend toward an ellipsoid fitted around the face
         * skin, so light crosses the face in one soft gradient under any lighting, a shader pack's included.
         */
        private void smoothFaces(HumanoidImport importer, IntArrayList materials, IntArrayList sources) {
            var head = humanoid.get("head");
            var min = new Vector3f(Float.POSITIVE_INFINITY);
            var max = new Vector3f(Float.NEGATIVE_INFINITY);
            var face = new IntArrayList();
            var position = new Vector3f();
            for (var output = 0; output < materials.size(); output++) {
                var name = source.materials.get(materials.getInt(output)).name();
                if (head == null || !FACE_PART.matcher(name).find() || !underNode(sources.getInt(output), head)) {
                    continue;
                }
                face.add(output);
                if (FACE_SKIN.matcher(name).find()) {
                    outputPosition(importer, output, position);
                    min.min(position);
                    max.max(position);
                }
            }
            var width = max.x - min.x;
            if (!(width > 0f)) {
                return;
            }
            // The face looks toward -Z: the head's center sits behind the front of the face skin.
            var center = new Vector3f((min.x + max.x) / 2, (min.y + max.y) / 2, min.z + 0.6f * width);
            var radii = new Vector3f(0.55f * width, 0.55f * Math.max(max.y - min.y, width), 0.6f * width);
            var normal = new Vector3f();
            var ellipsoid = new Vector3f();
            for (var i = 0; i < face.size(); i++) {
                var output = face.getInt(i);
                outputPosition(importer, output, position);
                position.sub(center, ellipsoid).div(radii).div(radii);
                if (!(ellipsoid.lengthSquared() > 0f)) {
                    continue;
                }
                normal.set(importer.normals.getFloat(output * 3), importer.normals.getFloat(output * 3 + 1),
                    importer.normals.getFloat(output * 3 + 2));
                normal.lerp(ellipsoid.normalize(), FACE_NORMAL_BLEND).normalize();
                importer.normals.set(output * 3, normal.x);
                importer.normals.set(output * 3 + 1, normal.y);
                importer.normals.set(output * 3 + 2, normal.z);
            }
        }

        /** Whether a source vertex's most weighted joint is the node or hangs below it. */
        private boolean underNode(int vertex, int node) {
            var joint = -1;
            var best = 0f;
            for (var k = 0; k < 4; k++) {
                var weight = source.weights.getFloat(vertex * 4 + k);
                if (weight > best) {
                    best = weight;
                    joint = source.joints.getInt(vertex * 4 + k);
                }
            }
            for (var steps = 0; joint >= 0 && steps <= nodeCount; joint = source.parents.getInt(joint), steps++) {
                if (joint == node) {
                    return true;
                }
            }
            return false;
        }

        private static void outputPosition(HumanoidImport importer, int output, Vector3f out) {
            out.set(importer.positions.getFloat(output * 3), importer.positions.getFloat(output * 3 + 1),
                importer.positions.getFloat(output * 3 + 2));
        }

        /**
         * Adds the inverted hull of every outlined material: each of its vertices copied once and pushed out by the
         * outline width along the normal averaged over all outlined vertices at that position (so hard edges and UV
         * seams do not split the outline), sampling the outline color, with the triangles wound inward. Drawn with
         * back-face culling only the hull's far side shows, as a rim around the silhouette. Copies follow their
         * vertex's joints and expressions.
         */
        private void emitOutlines(HumanoidImport importer, Long2IntOpenHashMap copies, int materialCount) {
            if (outlineMaterials.isEmpty()) {
                return;
            }
            var smoothed = new HashMap<Long, Vector3f>();
            var counted = new java.util.BitSet();
            var position = new Vector3f();
            for (var t = 0; t < source.triangles.size(); t++) {
                var material = source.triangleMaterials.getInt(t / 3);
                if (!outlineMaterials.containsKey(material)) {
                    continue;
                }
                var output = copies.get((long)source.triangles.getInt(t) * materialCount + material);
                if (!counted.get(output)) {
                    counted.set(output);
                    outputPosition(importer, output, position);
                    smoothed.computeIfAbsent(positionKey(position), ignored -> new Vector3f()).add(
                        importer.normals.getFloat(output * 3), importer.normals.getFloat(output * 3 + 1),
                        importer.normals.getFloat(output * 3 + 2));
                }
            }

            var hulls = new Int2IntOpenHashMap();
            hulls.defaultReturnValue(-1);
            var hull = new int[3];
            var outputs = new int[3];
            var uv = new float[2];
            for (var t = 0; t < source.triangles.size(); t += 3) {
                var material = source.triangleMaterials.getInt(t / 3);
                if (!outlineMaterials.containsKey(material)) {
                    continue;
                }
                var width = source.materials.get(material).outline().width() * unit;
                var visible = false;
                for (var c = 0; c < 3; c++) {
                    var vertex = source.triangles.getInt(t + c);
                    var output = copies.get((long)vertex * materialCount + material);
                    outputs[c] = output;
                    var scale = source.edgeScales.isEmpty() ? 1f : Math.max(source.edgeScales.getFloat(vertex), 0f);
                    visible |= scale > 0f;
                    hull[c] = hulls.get(output);
                    if (hull[c] >= 0) {
                        continue;
                    }
                    hull[c] = importer.positions.size() / 3;
                    hulls.put(output, hull[c]);
                    vertexCopies.get(vertex).add(hull[c]);
                    outputPosition(importer, output, position);
                    var normal = new Vector3f(smoothed.get(positionKey(position)));
                    if (normal.lengthSquared() > 0f) {
                        normal.normalize();
                    }
                    position.fma(width * scale, normal);
                    importer.positions.add(position.x);
                    importer.positions.add(position.y);
                    importer.positions.add(position.z);
                    importer.normals.add(normal.x);
                    importer.normals.add(normal.y);
                    importer.normals.add(normal.z);
                    importer.atlas.remap(outlineMaterials.get(material), 0.5f, 0.5f, uv, 0);
                    importer.uvs.add(uv[0]);
                    importer.uvs.add(uv[1]);
                    for (var k = 0; k < 4; k++) {
                        importer.joints.add(importer.joints.getInt(output * 4 + k));
                        importer.weights.add(importer.weights.getFloat(output * 4 + k));
                    }
                }
                if (visible) {
                    // Wound against the surface's normals, whatever the source's convention: culled where it faces
                    // the viewer, drawn where it lies behind the silhouette.
                    var outward = facesNormals(importer, outputs[0], outputs[1], outputs[2]);
                    importer.outlineTriangles.add(hull[0]);
                    importer.outlineTriangles.add(outward ? hull[2] : hull[1]);
                    importer.outlineTriangles.add(outward ? hull[1] : hull[2]);
                }
            }
            var outlineHulls = new int[importer.vertexCount()];
            java.util.Arrays.fill(outlineHulls, -1);
            hulls.int2IntEntrySet().forEach(entry -> outlineHulls[entry.getIntKey()] = entry.getIntValue());
            importer.outlineHulls = outlineHulls;
        }

        /** Whether a triangle winds counter-clockwise when seen from the side its vertex normals point to. */
        private static boolean facesNormals(HumanoidImport importer, int a, int b, int c) {
            var pa = new Vector3f();
            var pb = new Vector3f();
            var pc = new Vector3f();
            outputPosition(importer, a, pa);
            outputPosition(importer, b, pb);
            outputPosition(importer, c, pc);
            var face = pb.sub(pa).cross(pc.sub(pa));
            var normals = new Vector3f();
            for (var vertex : new int[] {a, b, c}) {
                normals.add(importer.normals.getFloat(vertex * 3), importer.normals.getFloat(vertex * 3 + 1),
                    importer.normals.getFloat(vertex * 3 + 2));
            }
            return face.dot(normals) >= 0f;
        }

        /** Positions closer than a quarter millimeter of a model unit share a key. */
        private static long positionKey(Vector3f position) {
            var x = Math.round(position.x * 4096f) & 0x1FFFFFL;
            var y = Math.round(position.y * 4096f) & 0x1FFFFFL;
            var z = Math.round(position.z * 4096f) & 0x1FFFFFL;
            return x << 42 | y << 21 | z;
        }

        private void restVertex(int vertex, Vector3f position, Vector3f normal) {
            position.set(source.positions.getFloat(vertex * 3), source.positions.getFloat(vertex * 3 + 1),
                source.positions.getFloat(vertex * 3 + 2));
            normal.set(source.normals.getFloat(vertex * 3), source.normals.getFloat(vertex * 3 + 1),
                source.normals.getFloat(vertex * 3 + 2));
        }
    }

    /**
     * Writes every keyframe vector that holds a Molang string with all three components as strings. A vector mixing
     * numbers and expressions (e.g. [0, "-72 - ysm.head_yaw", 0]) was seen in game to drop its expression component,
     * while all-number and all-string vectors evaluate; a number written as a string evaluates to the same value.
     */
    static void stringifyMixedVectors(JsonElement element) {
        if (element instanceof JsonObject object) {
            for (var entry : object.entrySet()) {
                stringifyMixedVectors(entry.getValue());
            }
        } else if (element instanceof JsonArray array) {
            var strings = 0;
            var numbers = 0;
            for (var value : array) {
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    strings++;
                } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                    numbers++;
                }
            }
            if (array.size() == 3 && strings > 0 && numbers > 0 && strings + numbers == 3) {
                for (var i = 0; i < 3; i++) {
                    var value = array.get(i).getAsJsonPrimitive();
                    if (value.isNumber()) {
                        array.set(i, new com.google.gson.JsonPrimitive(value.getAsString()));
                    }
                }
            } else {
                for (var value : array) {
                    stringifyMixedVectors(value);
                }
            }
        }
    }
}
