package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

/**
 * Runs YSM's humanoid import on each model, parses the generated animation files with YSM's own AnimationFile adapter
 * and compiles every Molang string with YSM's front end (keyframe values as expressions, timeline entries as
 * instructions). Prints failures, mixed number/string vectors and the generated clip names.
 */
public final class GenCheck {
    private static final MolangFrontend FRONTEND = new MolangFrontend();
    private static int expressions;
    private static int failures;
    private static int mixed;

    public static void main(String[] args) throws Exception {
        com.elfmcys.ysm.natives.NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        com.elfmcys.ysm.natives.NativeRuntime.initialize(
            com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.WARN));
        for (var arg : args) {
            var model = Path.of(arg);
            var data = Files.readAllBytes(model);
            var buffer = ByteBuffer.allocateDirect(data.length).put(data).flip();
            try (var humanoid = HumanoidImport.load(model.getFileName().toString(), buffer, name -> {
                var file = model.resolveSibling(name);
                return Files.exists(file) ? ByteBuffer.wrap(Files.readAllBytes(file)) : null;
            })) {
                expressions = failures = mixed = 0;
                for (var text : new String[] {humanoid.animation(), humanoid.armAnimation()}) {
                    var parsed = AnimationFile.createGson(false).fromJson(text, AnimationFile.class);
                    var root = JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("animations");
                    for (var entry : root.entrySet()) {
                        walk(entry.getKey(), entry.getValue(), false);
                    }
                    System.out.printf("%s: %d clips parsed by AnimationFile%n", model.getFileName(),
                        parsed.animations.size());
                }
                var names = new TreeSet<>(JsonParser.parseString(humanoid.animation()).getAsJsonObject()
                    .getAsJsonObject("animations").keySet());
                names.removeIf(n -> n.startsWith("tac:") || n.startsWith("bfm:"));
                System.out.printf("%s: %d Molang strings, %d compile failures, %d mixed vectors%n  clips: %s%n",
                    model.getFileName(), expressions, failures, mixed, names);
                var geo = humanoid.main().model;
                if (geo.springs().hasRemaining()) {
                    var springs = com.elfmcys.ysm.proto.mixel.asset.model.data.SpringBonesProto
                        .parseFrom(com.elfmcys.quickbuf.ProtoSource.newInstance(geo.springs().duplicate()));
                    System.out.printf("%s: springs %d chains, %d joints, %d colliders%n", model.getFileName(),
                        springs.chains().size(), springs.joints().size(), springs.colliders().size());
                } else {
                    System.out.printf("%s: no springs%n", model.getFileName());
                }
            }
        }
    }

    private static void walk(String clip, JsonElement element, boolean timeline) {
        if (element instanceof JsonObject object) {
            for (var entry : object.entrySet()) {
                walk(clip, entry.getValue(), timeline || entry.getKey().equals("timeline"));
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
            if (array.size() == 3 && strings > 0 && numbers > 0) {
                mixed++;
            }
            for (var value : array) {
                walk(clip, value, timeline);
            }
        } else if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            var source = element.getAsString();
            if (!timeline && isEnumWord(source)) {
                return;
            }
            expressions++;
            try {
                FRONTEND.compile(source, timeline ? MolangFrontend.SourceMode.INSTRUCTION
                    : MolangFrontend.SourceMode.EXPRESSION, new MolangFrontend.SourceLocation(clip));
            } catch (Exception failure) {
                failures++;
                if (failures <= 10) {
                    System.out.printf("  FAIL %s: %s%n    %s%n", clip, failure.getMessage(),
                        source.length() > 200 ? source.substring(0, 200) + "..." : source);
                }
            }
        }
    }

    /** Non-Molang string fields: loop modes, easing and lerp names. */
    private static boolean isEnumWord(String source) {
        return source.matches("[a-z_]+") && !source.contains(".") && (source.equals("hold_on_last_frame")
            || source.equals("linear") || source.equals("catmullrom") || source.equals("step")
            || source.startsWith("ease") || source.equals("true") || source.equals("false"));
    }
}
