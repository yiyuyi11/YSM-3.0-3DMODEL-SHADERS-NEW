package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.frontend.MolangFrontendException;
import com.elfmcys.ysm.format.parser.pojo.animation.Animation;
import com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile;
import com.elfmcys.ysm.format.parser.pojo.animation.keyframe.BoneKeyFrameList;
import com.elfmcys.ysm.format.parser.pojo.animation.value.UnionValue;
import com.elfmcys.ysm.format.parser.pojo.animation.value.Vector3v;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * User animations layered over an imported humanoid model's animation sets (the animkit Blockbench plugin writes them).
 * A model whose main model is a humanoid file (.vrm, .pmx, ...) may ship, beside that file,
 * {@code animations/main.animation.json} and {@code animations/fp_arm.animation.json}; humanoid.json's
 * {@code "animations": {"main": "...", "fp_arm": "..."}} names other paths. A clip of an overlay replaces the clip of the
 * same name in its set (generated or from the model's own animation file); other clips are added. Overlay files are
 * read through the model source, so they are part of the model's identity and of its capture like its other files.
 *
 * <p>YSM itself compiles broken Molang to a constant 0 without a word; an overlay is compiled strictly first, so a typo
 * fails the import with the file, clip and expression instead of freezing a bone in game.
 */
final class AnimationOverlay {
    /** Animation sets an overlay may extend: the third-person main set and the first-person arm set. */
    static final List<String> SETS = List.of("main", "fp_arm");
    /** humanoid.json key whose object maps a set to its overlay path (relative to the model file's directory). */
    static final String KEY = "animations";

    private AnimationOverlay() {
    }

    /** Overlay path of a set relative to the model file's directory when humanoid.json names none. */
    static String defaultPath(String set) {
        return "animations/" + set + ".animation.json";
    }

    /** One overlay file: the set it extends, its source path and whether humanoid.json named it. */
    record File(String set, String path, boolean explicit) {
    }

    /**
     * The overlay files of a model whose main model file is {@code modelKey}: humanoid.json's paths, else the default
     * ones that exist. Reads humanoid.json (as the importer does, so the read adds nothing new to the model's
     * identity), never the overlays themselves. A path humanoid.json names must exist.
     */
    static List<File> find(RawModelSource source, String modelKey) throws IOException {
        var slash = modelKey.lastIndexOf('/');
        var directory = slash >= 0 ? modelKey.substring(0, slash) : "";
        var humanoidJson = source.findFile(join(directory, HumanoidMapping.OVERRIDE_FILE));
        var named = humanoidJson.isPresent()
            ? paths(source.readFile(humanoidJson.get(), "texture", false).map(NativeBuffer::nio).orElse(null))
            : Map.<String, String>of();
        var result = new ArrayList<File>();
        for (var set : SETS) {
            var relative = named.get(set);
            var found = findFile(source, join(directory, relative != null ? relative : defaultPath(set)));
            if (found.isPresent()) {
                result.add(new File(set, found.get(), relative != null));
            } else if (relative != null) {
                throw new IOException(HumanoidMapping.OVERRIDE_FILE + ": " + KEY + "." + set + ": file '" + relative
                    + "' not found beside the model");
            }
        }
        return result;
    }

    /** humanoid.json's overlay paths by set; empty without the key. */
    static Map<String, String> paths(@Nullable ByteBuffer humanoidJson) throws IOException {
        if (humanoidJson == null) {
            return Map.of();
        }
        final JsonObject object;
        try {
            object = JsonParser.parseString(StandardCharsets.UTF_8.decode(humanoidJson.duplicate()).toString())
                .getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IOException(HumanoidMapping.OVERRIDE_FILE + " is not a JSON object", invalid);
        }
        var value = object.get(KEY);
        if (value == null) {
            return Map.of();
        }
        if (!value.isJsonObject()) {
            throw new IOException(HumanoidMapping.OVERRIDE_FILE + ": " + KEY
                + " must be an object like {\"main\": \"animations/main.animation.json\"}");
        }
        var result = new LinkedHashMap<String, String>();
        for (var entry : value.getAsJsonObject().entrySet()) {
            if (!SETS.contains(entry.getKey())) {
                throw new IOException(HumanoidMapping.OVERRIDE_FILE + ": " + KEY + ": unknown animation set '"
                    + entry.getKey() + "' (expected " + String.join(" or ", SETS) + ")");
            }
            var path = entry.getValue();
            if (!path.isJsonPrimitive() || !path.getAsJsonPrimitive().isString() || path.getAsString().isBlank()) {
                throw new IOException(
                    HumanoidMapping.OVERRIDE_FILE + ": " + KEY + "." + entry.getKey() + " must be a file path string");
            }
            result.put(entry.getKey(), path.getAsString());
        }
        return result;
    }

    /** A parsed overlay: its clips in YSM's model and the raw JSON of each, by clip name in file order. */
    record Clips(AnimationFile file, Map<String, JsonObject> json) {
        Set<String> names() {
            return json.keySet();
        }
    }

    /**
     * Parses an overlay file with YSM's own animation reader, clip by clip, and compiles every Molang string it holds
     * the way {@link AnimationBuilder} will, strictly. Errors name the file and the clip.
     */
    static Clips parse(String file, String text, MolangFrontend frontend, boolean mergeInstructionLines)
        throws IOException {
        final JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IOException(file + ": not a valid animation JSON object: " + message(invalid), invalid);
        }
        var animations = root.get("animations");
        if (animations == null || !animations.isJsonObject()) {
            throw new IOException(file + ": missing the \"animations\" object");
        }
        var gson = AnimationFile.createGson(mergeInstructionLines);
        var parsed = new AnimationFile();
        var json = new LinkedHashMap<String, JsonObject>();
        for (var entry : animations.getAsJsonObject().entrySet()) {
            var name = entry.getKey();
            if (name.isBlank()) {
                throw new IOException(file + ": a clip has an empty name");
            }
            if (!entry.getValue().isJsonObject()) {
                throw new IOException(file + ": clip '" + name + "' is not an object");
            }
            var single = new JsonObject();
            var wrapper = new JsonObject();
            wrapper.add(name, entry.getValue());
            single.add("animations", wrapper);
            final Animation animation;
            try {
                var one = gson.fromJson(single, AnimationFile.class);
                if (one.animations.size() != 1) {
                    throw new IllegalStateException("unreadable clip");
                }
                animation = one.animations.get(0);
            } catch (RuntimeException invalid) {
                throw new IOException(file + ": clip '" + name + "': " + message(invalid), invalid);
            }
            validate(file, animation, frontend);
            parsed.animations.add(animation);
            json.put(name, entry.getValue().getAsJsonObject());
        }
        return new Clips(parsed, json);
    }

    /** Compiles what {@link AnimationBuilder} compiles: blend weight, keyframe strings and timeline instructions. */
    private static void validate(String file, Animation animation, MolangFrontend frontend) throws IOException {
        var clip = animation.animationName;
        if (animation.blendWeight != null) {
            expression(file, clip, "blend_weight", animation.blendWeight, frontend);
        }
        for (var bone : animation.boneAnimations) {
            keyFrames(file, clip, bone.boneName + " rotation", bone.rotationKeyFrames, frontend);
            keyFrames(file, clip, bone.boneName + " position", bone.positionKeyFrames, frontend);
            keyFrames(file, clip, bone.boneName + " scale", bone.scaleKeyFrames, frontend);
        }
        for (var instruction : animation.customInstructionKeyframes) {
            if (instruction.eventData.isEmpty()) {
                continue;
            }
            // Joined like FrontendPrograms.compileStatements.
            var combined = new StringBuilder();
            for (var line : instruction.eventData) {
                var statement = line == null ? "" : line.stripTrailing();
                combined.append(statement);
                if (!statement.endsWith(";")) {
                    combined.append(';');
                }
                combined.append('\n');
            }
            compile(file, clip, "timeline at tick " + instruction.startTick, combined.toString(),
                MolangFrontend.SourceMode.INSTRUCTION, frontend);
        }
    }

    private static void keyFrames(String file, String clip, String channel, @Nullable BoneKeyFrameList list,
        MolangFrontend frontend) throws IOException {
        if (list == null) {
            return;
        }
        for (var keyFrame : list.keyFrames) {
            var where = channel + " at tick " + keyFrame.startTick;
            for (var value : new Vector3v[] {keyFrame.preValue, keyFrame.postValue}) {
                if (value == null) {
                    continue;
                }
                for (var component : value.components) {
                    expression(file, clip, where, component, frontend);
                }
            }
        }
    }

    private static void expression(String file, String clip, String where, UnionValue value, MolangFrontend frontend)
        throws IOException {
        if (value.type == UnionValue.ValueType.STRING) {
            compile(file, clip, where, value.stringValue, MolangFrontend.SourceMode.EXPRESSION, frontend);
        }
    }

    private static void compile(String file, String clip, String where, String source, MolangFrontend.SourceMode mode,
        MolangFrontend frontend) throws IOException {
        try {
            frontend.compile(source, mode, new MolangFrontend.SourceLocation(location(file, clip)));
        } catch (MolangFrontendException | RuntimeException failure) {
            var shown = source.length() > 200 ? source.substring(0, 200) + "..." : source;
            throw new IOException(file + ": clip '" + clip + "', " + where + ": Molang does not compile: "
                + message(failure) + " in `" + shown.strip() + "`", failure);
        }
    }

    /** A frontend source location (1..1024 UTF-8 bytes). */
    private static String location(String file, String clip) {
        var location = file + "/animation/" + clip;
        return location.getBytes(StandardCharsets.UTF_8).length <= 1024 ? location : file;
    }

    private static String message(Throwable error) {
        return Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName());
    }

    /**
     * The set with the overlay's clips in place of the base clips of the same names, ordered by name as
     * {@link AnimationBuilder} orders a file.
     */
    static AnimationFileProto merge(@Nullable AnimationFileProto base, AnimationFileProto overlay) {
        var names = new java.util.HashSet<String>();
        overlay.animations().forEach(animation -> names.add(animation.name()));
        var merged = new ArrayList<AnimationProto>();
        if (base != null) {
            base.animations().forEach(animation -> {
                if (!names.contains(animation.name())) {
                    merged.add(animation);
                }
            });
        }
        overlay.animations().forEach(merged::add);
        merged.sort(Comparator.comparing(AnimationProto::name));
        var result = AnimationFileProto.newBuilder();
        merged.forEach(result::addAnimations);
        return result.build();
    }

    /** The same merge on Bedrock animation JSON (for the rig export): base clips replaced or added by name. */
    static JsonObject merge(JsonObject base, Clips overlay) {
        var result = base.deepCopy();
        var animations = result.getAsJsonObject("animations");
        if (animations == null) {
            animations = new JsonObject();
            result.add("animations", animations);
        }
        for (Map.Entry<String, JsonObject> clip : overlay.json().entrySet()) {
            animations.add(clip.getKey(), clip.getValue().deepCopy());
        }
        return result;
    }

    /**
     * {@link RawModelSource#findFile}, absent when a directory on the path is missing: a model folder read as a
     * {@link com.elfmcys.ysm.format.vfs.Directory} fails the lookup of a file below a directory that does not exist
     * (most models have no animations folder).
     */
    private static Optional<String> findFile(RawModelSource source, String path) {
        try {
            return source.findFile(path);
        } catch (UncheckedIOException error) {
            if (error.getCause() instanceof NoSuchFileException || error.getCause() instanceof NotDirectoryException) {
                return Optional.empty();
            }
            throw error;
        }
    }

    private static String join(String directory, String name) {
        return directory.isEmpty() ? name : directory + "/" + name;
    }
}
