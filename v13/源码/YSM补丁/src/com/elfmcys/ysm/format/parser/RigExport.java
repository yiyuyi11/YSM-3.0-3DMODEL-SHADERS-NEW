package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.format.parser.pojo.manifest.ModelManifest;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SkinnedMeshProto;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.function.IntBinaryOperator;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jetbrains.annotations.Nullable;

/**
 * animkit rig export: runs YSM's own import of an imported 3D model (the folder's ysm.json, or the largest humanoid
 * file as the game chooses, with humanoid.json, textures and the user animation overlays) and writes a
 * {@code .ysmrig.json} (format 1, {@code ~/ysm/handoff/ysmrig-format.md}) for the Blockbench plugin: the main and
 * first-person arm skeletons as Bedrock geometry, their skinned meshes, the atlas as PNG and the main and fp_arm
 * animation sets as the game assembles them.
 *
 * <p>Usage: {@code java -cp <ysm jar>:<libraries> com.elfmcys.ysm.format.parser.RigExport <model folder | model file>
 * <out.ysmrig.json>}. The YSM native library comes from the jar itself (META-INF/native) unless YSM_NATIVE_PATH names
 * one. Exit status 0 on success, 1 on an import error (message on stderr), 2 on bad arguments.
 */
public final class RigExport implements com.elfmcys.ysm.util.Closeable {
    static final int FORMAT = 1;
    private static final Gson GSON = new Gson();
    private static final String UNITS = "Blockbench scene space = YSM model space in pixels (16 = 1 block, before"
        + " renderScale), facing -Z, left at -X; geo is Bedrock JSON (pivot x and X/Y rotation negated, as Blockbench's"
        + " Bedrock codec reads it); mesh in the bind pose with every bone at zero rotation";

    private final RawModelSource source;
    private final MolangFrontend frontend = new MolangFrontend();
    private final Map<String, HumanoidImport> humanoids = new HashMap<>();
    /** The "textures" array (one atlas per imported model file) and the index of each import's atlas in it. */
    private final JsonArray textures = new JsonArray();
    private final Map<HumanoidImport, Integer> textureOf = new java.util.IdentityHashMap<>();
    /** The "materials" array, indexed by "texture:region:material". */
    private final JsonArray materials = new JsonArray();
    private final Map<String, Integer> materialIndex = new HashMap<>();

    private RigExport(RawModelSource source) {
        this.source = source;
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: RigExport <model folder | model file (.vrm .pmx .pmd .xps)> <out.ysmrig.json>");
            System.exit(2);
        }
        try {
            loadNative();
            var summary = export(Path.of(args[0]), Path.of(args[1]));
            System.out.println(summary);
        } catch (Throwable error) {
            var message = new StringBuilder(describe(error));
            for (var cause = error.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause()) {
                var text = describe(cause);
                if (message.indexOf(text) < 0) {
                    message.append("\n  caused by: ").append(text);
                }
            }
            System.err.println("RigExport: " + message);
            if (System.getenv("YSM_RIGEXPORT_DEBUG") != null) {
                error.printStackTrace();
            }
            System.exit(1);
        }
    }

    private static String describe(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.getClass().getName();
    }

    /** Loads YSM's native library: YSM_NATIVE_PATH, else the jar's own copy unpacked once per content into tmp. */
    private static void loadNative() throws Exception {
        var override = System.getenv("YSM_NATIVE_PATH");
        Path library;
        if (override != null && !override.isBlank()) {
            library = Path.of(override).toAbsolutePath();
        } else {
            var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            var name = os.contains("win") ? "ysm.dll" : os.contains("linux") ? "libysm.so" : null;
            if (name == null) {
                throw new IOException("no YSM native library for " + os + " in the jar; set YSM_NATIVE_PATH");
            }
            byte[] bytes;
            try (var in = RigExport.class.getResourceAsStream("/META-INF/native/" + name)) {
                if (in == null) {
                    throw new IOException("the YSM jar on the classpath has no META-INF/native/" + name);
                }
                bytes = in.readAllBytes();
            }
            var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            var hex = new StringBuilder();
            for (var i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            var directory = Path.of(System.getProperty("java.io.tmpdir"), "ysm-animkit", hex.toString());
            library = directory.resolve(name);
            if (!Files.isRegularFile(library) || Files.size(library) != bytes.length) {
                Files.createDirectories(directory);
                var temporary = Files.createTempFile(directory, name, ".part");
                Files.write(temporary, bytes);
                Files.move(temporary, library, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        com.elfmcys.ysm.natives.NativeRuntime.load(library.toString());
        com.elfmcys.ysm.natives.NativeRuntime.initialize(
            com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.WARN));
    }

    static String export(Path input, Path output) throws IOException {
        input = input.toAbsolutePath().normalize();
        Path directory;
        String chosen = null;
        if (Files.isDirectory(input)) {
            directory = input;
        } else if (Files.isRegularFile(input)) {
            directory = input.getParent();
            chosen = input.getFileName().toString();
            if (!HumanoidImport.isHumanoidFile(chosen)) {
                throw new IOException(chosen + ": not a model file YSM imports (" + String.join(" ",
                    HumanoidImport.EXTENSIONS) + "); pick the model folder or its model file");
            }
        } else {
            throw new IOException(input + ": no such file or folder");
        }
        try (var vfs = new Directory(directory); var exporter = new RigExport(new RawModelSource(vfs))) {
            var rig = exporter.build(chosen, directory);
            var parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var temporary = output.resolveSibling(output.getFileName() + ".part");
            try (var writer = new OutputStreamWriter(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                GSON.toJson(rig, writer);
            }
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            var rigs = rig.getAsJsonObject("rigs");
            var summary = new StringBuilder("wrote ").append(output).append(" (").append(Files.size(output))
                .append(" bytes): model ").append(rig.get("model").getAsString());
            for (var entry : rigs.entrySet()) {
                var mesh = entry.getValue().getAsJsonObject().getAsJsonObject("mesh");
                summary.append(", ").append(entry.getKey()).append(' ')
                    .append(mesh.getAsJsonArray("boneNames").size()).append(" bones ")
                    .append(mesh.get("vertexCount").getAsInt()).append(" vertices ")
                    .append(mesh.get("triangleCount").getAsInt()).append(" triangles");
            }
            var animations = rig.getAsJsonObject("animations");
            for (var set : AnimationOverlay.SETS) {
                summary.append(", ").append(set).append(' ').append(
                    animations.getAsJsonObject(set).getAsJsonObject("animations").size()).append(" clips");
            }
            var overlay = rig.getAsJsonObject("overlay");
            for (var entry : overlay.entrySet()) {
                summary.append(", overlay ").append(entry.getKey()).append(' ')
                    .append(entry.getValue().getAsJsonObject().getAsJsonArray("clips"));
            }
            return summary.toString();
        }
    }

    /** What the game loads from the folder, as RawModelAssembler reads it. */
    private record Player(String name, String mainModel, @Nullable String armModel, Map<String, String> animations,
        boolean mergeInstructionLines) {
    }

    private Player player(@Nullable String chosen, Path directory) throws IOException {
        var manifest = source.readJson("ysm.json", "manifest", GSON, ModelManifest.class, false).orElse(null);
        if (manifest != null) {
            manifest.normalize();
            var files = manifest.files.player;
            if (files == null || files.model.get("main") == null) {
                throw new IOException("ysm.json: no player main model");
            }
            var main = files.model.get("main");
            if (!HumanoidImport.isHumanoidFile(main)) {
                throw new IOException("ysm.json: the player main model '" + main
                    + "' is a Bedrock model, not an imported 3D model; edit it in Blockbench directly");
            }
            if (chosen != null && !normalize(main).equalsIgnoreCase(normalize(chosen))) {
                System.err.println("RigExport: note: ysm.json uses " + main + " as the main model, not " + chosen
                    + "; exporting what the game loads");
            }
            var animations = new LinkedHashMap<String, String>();
            for (var set : AnimationOverlay.SETS) {
                animations.put(set, files.animation.get(set));
            }
            var name = manifest.metadata != null && manifest.metadata.name != null && !manifest.metadata.name.isBlank()
                ? manifest.metadata.name : directory.getFileName().toString();
            return new Player(name, main, files.model.get("arm"), animations,
                manifest.properties != null && manifest.properties.mergeMultilineExpr);
        }
        if (source.hasFile("main.json")) {
            throw new IOException("main.json: a classic Bedrock model, not an imported 3D model");
        }
        // RawModelAssembler.mainHumanoidFile: the largest root-level model file, ties to the first name.
        var found = new ArrayList<String>();
        for (var extension : HumanoidImport.EXTENSIONS) {
            source.collectFileNames("", extension, false, (name, path) -> found.add(path));
        }
        found.sort(Comparator.naturalOrder());
        String main = null;
        var mainSize = -1L;
        for (var path : found) {
            var size = source.fileSize(path);
            if (size > mainSize) {
                main = path;
                mainSize = size;
            }
        }
        if (main == null) {
            throw new IOException(directory + ": no ysm.json and no model file (" + String.join(" ",
                HumanoidImport.EXTENSIONS) + ")");
        }
        if (chosen != null && !main.equalsIgnoreCase(chosen)) {
            System.err.println("RigExport: note: the game imports the largest model file " + main + ", not " + chosen
                + "; exporting what the game loads");
        }
        var animations = new LinkedHashMap<String, String>();
        animations.put("main", main);
        animations.put("fp_arm", main);
        var name = humanoid(main).metadata().name();
        return new Player(name.isBlank() ? removeExtension(main) : name, main, main, animations, false);
    }

    private JsonObject build(@Nullable String chosen, Path directory) throws IOException {
        var player = player(chosen, directory);
        var main = humanoid(player.mainModel());
        var result = new JsonObject();
        result.addProperty("format", FORMAT);
        result.addProperty("model", player.name());
        var importer = new JsonObject();
        importer.addProperty("revision", HumanoidImport.REVISION);
        importer.addProperty("jar", jarName());
        result.add("importer", importer);
        result.addProperty("units", UNITS);
        var files = new JsonObject();
        files.addProperty("folder", directory.toString());
        files.addProperty("main", player.mainModel());
        if (player.armModel() != null) {
            files.addProperty("arm", player.armModel());
        }
        result.add("files", files);
        var model = source.findFile(normalize(player.mainModel())).orElse(normalize(player.mainModel()));
        var sourceFile = new JsonObject();
        sourceFile.addProperty("path", directory.resolve(model).toString());
        sourceFile.addProperty("name", model.substring(model.lastIndexOf('/') + 1));
        sourceFile.addProperty("format", model.substring(model.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
        result.add("source", sourceFile);

        var rigs = new JsonObject();
        var mainRig = main.main();
        rigs.add("main",
            rig(player.name(), "main", mainRig, mainRig.heightScale, mainRig.widthScale, main, false));
        HumanoidImport arm = null;
        if (player.armModel() != null && HumanoidImport.isHumanoidFile(player.armModel())) {
            arm = humanoid(player.armModel());
            // CustomFirstPersonArmRenderer draws the arm model unscaled.
            rigs.add("arm", rig(player.name(), "arm", arm.arm(), 1f, 1f, arm, true));
        }
        result.add("rigs", rigs);
        result.add("textures", textures);
        result.add("materials", materials);

        var animations = new JsonObject();
        var sources = new JsonObject();
        var overlay = new JsonObject();
        var overlays = new HashMap<String, AnimationOverlay.File>();
        for (var file : AnimationOverlay.find(source, normalize(player.mainModel()))) {
            overlays.put(file.set(), file);
        }
        for (var set : AnimationOverlay.SETS) {
            var path = player.animations().get(set);
            var base = baseAnimation(path, set);
            if (path != null) {
                sources.addProperty(set, path);
            }
            var file = overlays.get(set);
            if (file != null) {
                var text = source.readText(file.path(), "animation", true).orElseThrow();
                var clips = AnimationOverlay.parse(file.path(), text, frontend, player.mergeInstructionLines());
                var replaced = new JsonObject();
                var baseClips = base.getAsJsonObject("animations");
                var names = new JsonArray();
                for (var name : clips.names()) {
                    names.add(name);
                    if (baseClips != null && baseClips.has(name)) {
                        replaced.add(name, baseClips.get(name));
                    }
                }
                var entry = new JsonObject();
                entry.addProperty("file", file.path());
                entry.add("clips", names);
                entry.add("replaced", replaced);
                overlay.add(set, entry);
                base = AnimationOverlay.merge(base, clips);
            }
            animations.add(set, base);
        }
        result.add("animations", animations);
        result.add("animationSources", sources);
        result.add("overlay", overlay);

        if (arm != null) {
            var firstPerson = new JsonObject();
            var palm = new JsonObject();
            palm.add("left", pixels(arm.firstPersonPalm("Left")));
            palm.add("right", pixels(arm.firstPersonPalm("Right")));
            firstPerson.add("palm", palm);
            result.add("firstPerson", firstPerson);
        }
        return result;
    }

    /** The set as the game reads it before overlays: generated by the importer, or the model's own file. */
    private JsonObject baseAnimation(@Nullable String path, String set) throws IOException {
        if (path == null) {
            var empty = new JsonObject();
            empty.addProperty("format_version", "1.8.0");
            empty.add("animations", new JsonObject());
            return empty;
        }
        String text;
        if (HumanoidImport.isHumanoidFile(path)) {
            var humanoid = humanoid(path);
            text = set.equals("fp_arm") ? humanoid.armAnimation() : humanoid.animation();
        } else {
            text = source.readText(path, "animation", true).orElseThrow();
        }
        try {
            var object = JsonParser.parseString(text).getAsJsonObject();
            if (!object.has("animations") || !object.get("animations").isJsonObject()) {
                object.add("animations", new JsonObject());
            }
            return object;
        } catch (RuntimeException invalid) {
            throw new IOException(path + ": not a JSON animation file", invalid);
        }
    }

    /** RawModelAssembler.humanoid: one import per model file, files it references resolved beside it. */
    private HumanoidImport humanoid(String path) throws IOException {
        var key = normalize(path);
        var cached = humanoids.get(key);
        if (cached == null) {
            var found = source.findFile(key).orElseThrow(() -> new IOException(path + ": file not found"));
            var data = source.readFile(found, "model", true).orElseThrow();
            var slash = key.lastIndexOf('/');
            var directory = slash >= 0 ? key.substring(0, slash) : "";
            cached = HumanoidImport.load(key, data.nio(), relative -> {
                var file = source.findFile(directory.isEmpty() ? relative : directory + "/" + relative);
                return file.isEmpty() ? null
                    : source.readFile(file.get(), "texture", false).map(NativeBuffer::nio).orElse(null);
            });
            humanoids.put(key, cached);
        }
        return cached;
    }

    private JsonObject rig(String model, String rigName, GeoBuilder.Result result, float heightScale,
        float widthScale, HumanoidImport humanoid, boolean arm) throws IOException {
        var geo = result.model;
        var rig = new JsonObject();
        rig.add("geo", geometry(model, rigName, geo));
        var scale = new JsonObject();
        scale.addProperty("height", heightScale);
        scale.addProperty("width", widthScale);
        rig.add("renderScale", scale);
        var names = new JsonArray();
        geo.bones().forEach(bone -> names.add(bone.name()));
        rig.add("mesh", mesh(geo, names, humanoid, arm));
        return rig;
    }

    /** Bedrock geometry 1.12.0 with bones only: the inverse of GeoBuilder's bone conversion (x and the X/Y turns flip). */
    private static JsonObject geometry(String model, String rigName, GeoModelProto geo) {
        var description = new JsonObject();
        description.addProperty("identifier", "geometry." + identifier(model) + "." + rigName);
        description.addProperty("texture_width", Math.round(geo.properties().textureWidth()));
        description.addProperty("texture_height", Math.round(geo.properties().textureHeight()));
        var bones = new JsonArray();
        for (var bone : geo.bones()) {
            var object = new JsonObject();
            object.addProperty("name", bone.name());
            if (!bone.parentNullOrEmpty()) {
                object.addProperty("parent", bone.parentUnsafe());
            }
            var pivot = bone.pivot();
            object.add("pivot", vector(-pivot.getFloat(0), pivot.getFloat(1), pivot.getFloat(2)));
            var rotate = bone.rotate();
            object.add("rotation", vector((float)-Math.toDegrees(rotate.getFloat(0)),
                (float)-Math.toDegrees(rotate.getFloat(1)), (float)Math.toDegrees(rotate.getFloat(2))));
            bones.add(object);
        }
        var entry = new JsonObject();
        entry.add("description", description);
        entry.add("bones", bones);
        var geometries = new JsonArray();
        geometries.add(entry);
        var file = new JsonObject();
        file.addProperty("format_version", "1.12.0");
        file.add("minecraft:geometry", geometries);
        return file;
    }

    /**
     * One skinned mesh: vertex streams in scene pixels, and the triangles regrouped into one draw group per atlas
     * material (first corner's material), body groups first, then the outline hull groups.
     */
    private JsonObject mesh(GeoModelProto geo, JsonArray boneNames, HumanoidImport humanoid, boolean arm)
        throws IOException {
        var bytes = new byte[geo.mesh().remaining()];
        geo.mesh().duplicate().get(bytes);
        var mesh = SkinnedMeshProto.parseFrom(bytes);
        var vertices = mesh.positions().size() / 3;
        var positions = buffer(mesh.positions().size() * 4);
        for (var i = 0; i < mesh.positions().size(); i++) {
            positions.putFloat(mesh.positions().getFloat(i) * 16f);
        }
        var normals = buffer(mesh.normals().size() * 4);
        for (var i = 0; i < mesh.normals().size(); i++) {
            normals.putFloat(mesh.normals().getFloat(i));
        }
        var uvs = buffer(mesh.uvs().size() * 4);
        for (var i = 0; i < mesh.uvs().size(); i++) {
            uvs.putFloat(mesh.uvs().getFloat(i));
        }
        var joints = buffer(mesh.joints().size() * 2);
        for (var i = 0; i < mesh.joints().size(); i++) {
            var joint = mesh.joints().getInt(i);
            if (joint < 0 || joint >= boneNames.size()) {
                throw new IOException("joint " + joint + " outside the " + boneNames.size() + " bones");
            }
            joints.putShort((short)joint);
        }
        var weights = buffer(mesh.weights().size() * 4);
        for (var i = 0; i < mesh.weights().size(); i++) {
            weights.putFloat(mesh.weights().getFloat(i));
        }

        var vertexMaterial = arm ? armVertexMaterials(humanoid) : mainVertexMaterials(humanoid, vertices);
        if (vertexMaterial.length != vertices) {
            throw new IOException("material table covers " + vertexMaterial.length + " of " + vertices + " vertices");
        }
        var triangles = mesh.triangles();
        var outline = mesh.outlineTriangles();
        var indices = buffer((triangles.size() + outline.size()) * 4);
        var groups = new JsonArray();
        var start = 0;
        for (var hull : new boolean[] {false, true}) {
            var list = hull ? outline : triangles;
            var byMaterial = new LinkedHashMap<Integer, IntArrayList>();
            for (var i = 0; i < list.size(); i += 3) {
                var bucket = byMaterial.computeIfAbsent(vertexMaterial[list.getInt(i)], ignored -> new IntArrayList());
                bucket.add(list.getInt(i));
                bucket.add(list.getInt(i + 1));
                bucket.add(list.getInt(i + 2));
            }
            for (var entry : byMaterial.entrySet()) {
                var bucket = entry.getValue();
                for (var i = 0; i < bucket.size(); i++) {
                    indices.putInt(bucket.getInt(i));
                }
                var material = entry.getKey();
                var group = new JsonObject();
                group.addProperty("start", start);
                group.addProperty("count", bucket.size());
                group.addProperty("texture", textureIndex(humanoid));
                group.addProperty("material", material < 0 ? -1 : material(humanoid, material, hull));
                group.addProperty("alpha", hull || material >= 0
                    && humanoid.materials().get(material).alphaMode() == HumanoidSource.OPAQUE ? "opaque" : "cutout");
                if (hull) {
                    group.addProperty("outline", true);
                }
                groups.add(group);
                start += bucket.size();
            }
        }

        var morphs = new JsonArray();
        for (var morph : mesh.morphs()) {
            var object = new JsonObject();
            object.addProperty("bone", boneNames.get(morph.bone()).getAsString());
            object.addProperty("driver", morph.driver() == 1 ? "blink" : "position_x");
            var morphVertices = buffer(morph.vertices().size() * 4);
            for (var i = 0; i < morph.vertices().size(); i++) {
                morphVertices.putInt(morph.vertices().getInt(i));
            }
            var deltas = buffer(morph.deltas().size() * 4);
            for (var i = 0; i < morph.deltas().size(); i++) {
                deltas.putFloat(morph.deltas().getFloat(i) * 16f);
            }
            object.addProperty("vertices", base64(morphVertices));
            object.addProperty("deltas", base64(deltas));
            morphs.add(object);
        }
        var result = new JsonObject();
        result.addProperty("bind", "zero");
        result.addProperty("vertexCount", vertices);
        result.addProperty("triangleCount", (triangles.size() + outline.size()) / 3);
        result.addProperty("positions", base64(positions));
        result.addProperty("normals", base64(normals));
        result.addProperty("uvs", base64(uvs));
        result.addProperty("joints", base64(joints));
        result.addProperty("weights", base64(weights));
        result.addProperty("indices", base64(indices));
        result.add("boneNames", boneNames);
        result.add("groups", groups);
        if (!morphs.isEmpty()) {
            result.add("morphs", morphs);
        }
        return result;
    }

    /** Material of every main-mesh vertex; outline hull copies take the material of the vertex they copy. */
    private static int[] mainVertexMaterials(HumanoidImport humanoid, int vertexCount) {
        var result = new int[vertexCount];
        Arrays.fill(result, -1);
        var materials = humanoid.vertexMaterials();
        var hulls = humanoid.outlineHulls();
        for (var v = 0; v < materials.length && v < vertexCount; v++) {
            result[v] = materials[v];
            if (v < hulls.length && hulls[v] >= 0 && hulls[v] < vertexCount) {
                result[hulls[v]] = materials[v];
            }
        }
        return result;
    }

    /** Material of every vertex of the arm model, through the main-mesh vertex it was made from. */
    private static int[] armVertexMaterials(HumanoidImport humanoid) {
        var sources = humanoid.armVertexSources();
        var materials = humanoid.vertexMaterials();
        var result = new int[sources.length];
        for (var i = 0; i < sources.length; i++) {
            result[i] = sources[i] < materials.length ? materials[sources[i]] : -1;
        }
        return result;
    }

    /**
     * Index into "materials" of an atlas material (of its outline colour region for a hull): name and glTF provenance,
     * the atlas rect, and the linear map from the material's own uv (clamped to 0..1) to atlas uv.
     */
    private int material(HumanoidImport humanoid, int material, boolean hull) throws IOException {
        var texture = textureIndex(humanoid);
        var region = hull ? humanoid.outlineMaterial(material) : material;
        if (region < 0) {
            throw new IOException("outline triangles on material " + material + " without an outline colour");
        }
        var key = texture + ":" + region + ":" + material;
        var existing = materialIndex.get(key);
        if (existing != null) {
            return existing;
        }
        var source = humanoid.materials().get(material);
        var atlas = humanoid.atlas();
        var out = new float[4];
        atlas.remap(region, 0f, 0f, out, 0);
        atlas.remap(region, 1f, 1f, out, 2);
        var x = Math.round(out[0] * atlas.width);
        var y = Math.round(out[1] * atlas.height);
        var entry = new JsonObject();
        entry.addProperty("name", source.name());
        entry.addProperty("texture", texture);
        entry.addProperty("gltfMaterial", humanoid.materialGltf(material));
        entry.addProperty("image", humanoid.materialImage(material));
        entry.addProperty("alphaMode", source.alphaMode() == HumanoidSource.MASK ? "MASK"
            : source.alphaMode() == HumanoidSource.BLEND ? "BLEND" : "OPAQUE");
        entry.addProperty("outline", hull);
        var rect = new JsonArray();
        rect.add(x);
        rect.add(y);
        rect.add(Math.round(out[2] * atlas.width) - x);
        rect.add(Math.round(out[3] * atlas.height) - y);
        entry.add("rect", rect);
        var offset = new JsonArray();
        offset.add(out[0]);
        offset.add(out[1]);
        entry.add("uvOffset", offset);
        var scale = new JsonArray();
        scale.add(out[2] - out[0]);
        scale.add(out[3] - out[1]);
        entry.add("uvScale", scale);
        materials.add(entry);
        materialIndex.put(key, materials.size() - 1);
        return materials.size() - 1;
    }

    /** Index into "textures" of a humanoid's atlas, written (with its specular map) on first use. */
    private int textureIndex(HumanoidImport humanoid) throws IOException {
        var existing = textureOf.get(humanoid);
        if (existing != null) {
            return existing;
        }
        var atlas = humanoid.atlas();
        var object = new JsonObject();
        object.addProperty("name", "atlas" + textures.size());
        object.addProperty("width", atlas.width);
        object.addProperty("height", atlas.height);
        object.addProperty("png", Base64.getEncoder().encodeToString(png(atlas.width, atlas.height, atlas::texel)));
        var specular = humanoid.specularTexels();
        object.addProperty("specularWidth", specular.width());
        object.addProperty("specularHeight", specular.height());
        object.addProperty("specularPng", Base64.getEncoder().encodeToString(png(specular.width(), specular.height(),
            (x, y) -> specular.rgba()[y * specular.width() + x])));
        textures.add(object);
        textureOf.put(humanoid, textures.size() - 1);
        return textures.size() - 1;
    }

    /** A minimal PNG of packed RGBA texels (red in the low byte): IHDR (8-bit RGBA), one IDAT of filter-0 rows, IEND. */
    private static byte[] png(int width, int height, IntBinaryOperator texel) throws IOException {
        var rows = new byte[height * (width * 4 + 1)];
        var at = 0;
        for (var y = 0; y < height; y++) {
            rows[at++] = 0;
            for (var x = 0; x < width; x++) {
                var value = texel.applyAsInt(x, y);
                rows[at++] = (byte)value;
                rows[at++] = (byte)(value >>> 8);
                rows[at++] = (byte)(value >>> 16);
                rows[at++] = (byte)(value >>> 24);
            }
        }
        return png(width, height, rows);
    }

    /** A minimal PNG: IHDR (8-bit RGBA), one zlib IDAT of filter-0 rows, IEND. */
    private static byte[] png(int width, int height, byte[] rows) throws IOException {
        var out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte)0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        var header = ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte)8).put((byte)6).put((byte)0)
            .put((byte)0).put((byte)0);
        chunk(out, "IHDR", header.array());
        var compressed = new ByteArrayOutputStream();
        try (var deflate = new DeflaterOutputStream(compressed, new Deflater(6), 1 << 16)) {
            deflate.write(rows);
        }
        chunk(out, "IDAT", compressed.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        var typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int)crc.getValue()).array());
    }

    private static JsonArray pixels(org.joml.Vector3f point) {
        return vector(point.x, point.y, point.z);
    }

    private static JsonArray vector(float x, float y, float z) {
        var array = new JsonArray();
        array.add(clean(x));
        array.add(clean(y));
        array.add(clean(z));
        return array;
    }

    /** -0 written as 0. */
    private static float clean(float value) {
        return value == 0f ? 0f : value;
    }

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static String base64(ByteBuffer buffer) {
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private static String identifier(String model) {
        var cleaned = model.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.]+", "_");
        return cleaned.isEmpty() ? "model" : cleaned;
    }

    private static String normalize(String path) {
        var normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String removeExtension(String path) {
        var slash = path.lastIndexOf('/');
        var name = slash >= 0 ? path.substring(slash + 1) : path;
        var dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String jarName() {
        try {
            var location = RigExport.class.getProtectionDomain().getCodeSource().getLocation();
            return Path.of(location.toURI()).getFileName().toString();
        } catch (Exception | Error unknown) {
            return "unknown";
        }
    }

    @Override
    public void close() {
        humanoids.values().forEach(HumanoidImport::close);
        humanoids.clear();
    }
}
