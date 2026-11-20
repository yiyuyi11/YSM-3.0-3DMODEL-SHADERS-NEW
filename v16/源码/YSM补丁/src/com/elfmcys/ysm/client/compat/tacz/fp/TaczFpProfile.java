package com.elfmcys.ysm.client.compat.tacz.fp;

import com.elfmcys.ysm.client.compat.tacz.TaczGrips;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * One gun's first-person hand profile, format 1 ({@code ~/ysm/handoff/tacz-fp-profile-contract.md}):
 * {@code config/ysm_tacz_fp/<namespace>/<path>.json} with {@code gun}, {@code clipLengths} and per YSM model id (or
 * {@code *}) a {@code grip}, {@code idle.bones} and keyframed {@code clips.<clip>.bones}. Rotations are arm keyframes in
 * degrees, the importer's convention (added to the rest pose, X and Y negated).
 *
 * <p>Validation drops what is malformed and reports it: a file without {@code format: 1} or a gun id is skipped, a
 * malformed grip, bone or clip is left out of an otherwise valid file.
 */
public final class TaczFpProfile {
    /** The fallback model key. */
    static final String ANY_MODEL = "*";

    final String gun;
    final Path source;
    final Map<String, Float> clipLengths;
    final Map<String, Entry> models;
    /** Display path of a YSM model -> its resolved data; filled on first use by any thread. */
    private final Map<String, Resolved> resolved = new ConcurrentHashMap<>();

    private TaczFpProfile(String gun, Path source, Map<String, Float> clipLengths, Map<String, Entry> models) {
        this.gun = gun;
        this.source = source;
        this.clipLengths = clipLengths;
        this.models = models;
    }

    /**
     * A bone's rotation track: keyframe times in seconds (ascending) and {@code [rx, ry, rz]} per key, degrees in the
     * importer's arm-animation convention. Linear between keys, held outside them.
     */
    record Track(String bone, float[] times, float[] values) {
        void sample(float t, float[] out) {
            var n = times.length;
            if (n == 1 || t <= times[0]) {
                System.arraycopy(values, 0, out, 0, 3);
                return;
            }
            if (t >= times[n - 1]) {
                System.arraycopy(values, (n - 1) * 3, out, 0, 3);
                return;
            }
            var hi = 1;
            while (times[hi] < t) {
                hi++;
            }
            var lo = hi - 1;
            var a = (t - times[lo]) / (times[hi] - times[lo]);
            for (var c = 0; c < 3; c++) {
                out[c] = values[lo * 3 + c] + (values[hi * 3 + c] - values[lo * 3 + c]) * a;
            }
        }
    }

    /** One {@code models} entry; null members are absent keys. */
    record Entry(boolean hasGrip, @Nullable TaczGrips.Grip grip, @Nullable Track[] idle, Map<String, Track[]> clips) {
    }

    /**
     * A model's data with the {@code *} entry filling each key (grip, idle, every clip) the model's own entry lacks.
     * {@code hasGrip} false: neither entry has a grip, the built-in table applies.
     */
    record Resolved(boolean hasGrip, @Nullable TaczGrips.Grip grip, Track[] idle, Map<String, Track[]> clips) {
    }

    /** The data for a YSM model, by its display path (e.g. {@code aglina} or {@code aglina/aglina.vrm}). */
    Resolved resolve(@Nullable String modelPath) {
        var key = modelPath == null ? ANY_MODEL : modelPath;
        var found = resolved.get(key);
        if (found == null) {
            found = merge(entryFor(modelPath), models.get(ANY_MODEL));
            resolved.putIfAbsent(key, found);
        }
        return found;
    }

    /**
     * The model's own entry: the display path itself, its last segment, or that without its extension (a model in
     * {@code ysm/custom/aglina/} or a file {@code aglina.vrm} both match {@code aglina}).
     */
    @Nullable
    Entry entryFor(@Nullable String modelPath) {
        if (modelPath == null) {
            return null;
        }
        var entry = models.get(modelPath);
        if (entry != null) {
            return entry;
        }
        var name = modelPath.substring(modelPath.lastIndexOf('/') + 1);
        entry = models.get(name);
        if (entry != null) {
            return entry;
        }
        var dot = name.lastIndexOf('.');
        if (dot > 0) {
            entry = models.get(name.substring(0, dot));
            if (entry != null) {
                return entry;
            }
        }
        // A file inside a folder named after it: aglina/aglina.vrm, matched by the folder name as well.
        var slash = modelPath.lastIndexOf('/');
        if (slash > 0) {
            var folder = modelPath.substring(modelPath.lastIndexOf('/', slash - 1) + 1, slash);
            return models.get(folder);
        }
        return null;
    }

    private static Resolved merge(@Nullable Entry own, @Nullable Entry any) {
        boolean hasGrip;
        TaczGrips.Grip grip;
        if (own != null && own.hasGrip) {
            hasGrip = true;
            grip = own.grip;
        } else if (any != null && any.hasGrip) {
            hasGrip = true;
            grip = any.grip;
        } else {
            hasGrip = false;
            grip = null;
        }
        var idle = own != null && own.idle != null ? own.idle : any != null && any.idle != null ? any.idle
            : new Track[0];
        var clips = new HashMap<String, Track[]>();
        if (any != null) {
            clips.putAll(any.clips);
        }
        if (own != null) {
            clips.putAll(own.clips);
        }
        return new Resolved(hasGrip, grip, idle, Map.copyOf(clips));
    }

    // ------------------------------------------------------------------------------------------------ loading

    /** Every profile under {@code directory}, by gun id; problems and one line per gun go to {@code log}. */
    static Map<String, TaczFpProfile> loadAll(Path directory, List<String> log) {
        var profiles = new LinkedHashMap<String, TaczFpProfile>();
        if (!Files.isDirectory(directory)) {
            return profiles;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(directory)) {
            files = walk.filter(p -> p.getFileName().toString().endsWith(".json") && Files.isRegularFile(p))
                .sorted().toList();
        } catch (IOException | RuntimeException e) {
            log.add("cannot list " + directory + ": " + e);
            return profiles;
        }
        for (var file : files) {
            var name = directory.relativize(file).toString().replace('\\', '/');
            var problems = new ArrayList<String>();
            TaczFpProfile profile;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                profile = parse(JsonParser.parseReader(reader), name, file, problems);
            } catch (IOException | RuntimeException e) {
                profile = null;
                problems.add("unreadable: " + e.getMessage());
            }
            for (var problem : problems) {
                log.add(name + ": " + problem);
            }
            if (profile == null) {
                log.add(name + ": skipped");
                continue;
            }
            var previous = profiles.putIfAbsent(profile.gun, profile);
            if (previous != null) {
                log.add(name + ": skipped, " + profile.gun + " is already defined by "
                    + directory.relativize(previous.source).toString().replace('\\', '/'));
                continue;
            }
            var named = profile.models.keySet().stream().filter(k -> !k.equals(ANY_MODEL)).count();
            var clips = new TreeMap<String, Integer>();
            profile.models.values().forEach(e -> e.clips.keySet().forEach(c -> clips.merge(c, 1, Integer::sum)));
            log.add(String.format("%s: %d model%s%s, clips %s (%s)", profile.gun, named, named == 1 ? "" : "s",
                profile.models.containsKey(ANY_MODEL) ? " + *" : "", clips.isEmpty() ? "none" : clips.keySet(), name));
        }
        return profiles;
    }

    /** A profile from parsed JSON, or null when the file can't be used; {@code where} names it in problems. */
    @Nullable
    static TaczFpProfile parse(JsonElement json, String where, Path source, List<String> problems) {
        if (!json.isJsonObject()) {
            problems.add("not a JSON object");
            return null;
        }
        var root = json.getAsJsonObject();
        var format = root.get("format");
        if (format == null || !format.isJsonPrimitive() || !format.getAsJsonPrimitive().isNumber()
            || format.getAsDouble() != 1) {
            problems.add("format must be 1, found " + format);
            return null;
        }
        var gunElement = root.get("gun");
        String gun;
        if (gunElement != null && gunElement.isJsonPrimitive() && gunElement.getAsJsonPrimitive().isString()) {
            gun = gunElement.getAsString();
        } else {
            gun = gunFromPath(where);
            if (gun == null) {
                problems.add("no gun id (\"gun\" or a <namespace>/<path>.json location)");
                return null;
            }
        }
        if (!gun.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            problems.add("invalid gun id " + gun);
            return null;
        }
        var clipLengths = new HashMap<String, Float>();
        var lengths = object(root, "clipLengths", "clipLengths", problems);
        if (lengths != null) {
            for (var e : lengths.entrySet()) {
                var v = number(e.getValue());
                if (v == null || !(v > 0)) {
                    problems.add("clipLengths." + e.getKey() + ": expected a positive number");
                } else {
                    clipLengths.put(e.getKey(), v);
                }
            }
        }
        var models = new HashMap<String, Entry>();
        var modelsJson = object(root, "models", "models", problems);
        if (modelsJson != null) {
            for (var e : modelsJson.entrySet()) {
                var path = "models." + e.getKey();
                if (!e.getValue().isJsonObject()) {
                    problems.add(path + ": expected an object");
                    continue;
                }
                models.put(e.getKey(), entry(e.getValue().getAsJsonObject(), path, problems));
            }
        }
        return new TaczFpProfile(gun, source, Map.copyOf(clipLengths), Map.copyOf(models));
    }

    /** {@code tacz/m4a1.json} -> {@code tacz:m4a1}. */
    @Nullable
    private static String gunFromPath(String where) {
        var slash = where.indexOf('/');
        if (slash <= 0 || !where.endsWith(".json")) {
            return null;
        }
        return where.substring(0, slash) + ":" + where.substring(slash + 1, where.length() - ".json".length());
    }

    private static Entry entry(JsonObject json, String path, List<String> problems) {
        var hasGrip = false;
        TaczGrips.Grip grip = null;
        var gripElement = json.get("grip");
        if (gripElement != null && !gripElement.isJsonNull()) {
            if (!gripElement.isJsonObject()) {
                problems.add(path + ".grip: expected an object");
            } else {
                var g = gripElement.getAsJsonObject();
                var left = hand(g.get("left"), path + ".grip.left", problems);
                var right = hand(g.get("right"), path + ".grip.right", problems);
                hasGrip = true;
                grip = left == null && right == null ? null : new TaczGrips.Grip(left, right);
            }
        }
        Track[] idle = null;
        var idleJson = object(json, "idle", path + ".idle", problems);
        if (idleJson != null) {
            var bones = object(idleJson, "bones", path + ".idle.bones", problems);
            if (bones != null) {
                var tracks = new ArrayList<Track>();
                for (var e : bones.entrySet()) {
                    var v = vector(e.getValue(), 3);
                    if (v == null) {
                        problems.add(path + ".idle.bones." + e.getKey() + ": expected [rx, ry, rz]");
                    } else {
                        tracks.add(new Track(e.getKey(), new float[] {0f}, v));
                    }
                }
                idle = tracks.toArray(Track[]::new);
            }
        }
        var clips = new HashMap<String, Track[]>();
        var clipsJson = object(json, "clips", path + ".clips", problems);
        if (clipsJson != null) {
            for (var c : clipsJson.entrySet()) {
                var clipPath = path + ".clips." + c.getKey();
                if (!c.getValue().isJsonObject()) {
                    problems.add(clipPath + ": expected an object");
                    continue;
                }
                var bones = object(c.getValue().getAsJsonObject(), "bones", clipPath + ".bones", problems);
                if (bones == null) {
                    continue;
                }
                var tracks = new ArrayList<Track>();
                for (var b : bones.entrySet()) {
                    var track = track(b.getKey(), b.getValue(), clipPath + ".bones." + b.getKey(), problems);
                    if (track != null) {
                        tracks.add(track);
                    }
                }
                if (!tracks.isEmpty()) {
                    clips.put(c.getKey(), tracks.toArray(Track[]::new));
                }
            }
        }
        return new Entry(hasGrip, grip, idle, Map.copyOf(clips));
    }

    @Nullable
    private static TaczGrips.Hand hand(@Nullable JsonElement json, String path, List<String> problems) {
        if (json == null || json.isJsonNull()) {
            return null;
        }
        var v = vector(json, 6);
        if (v == null) {
            problems.add(path + ": expected [x, y, z, rx, ry, rz]");
            return null;
        }
        return new TaczGrips.Hand(v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    /** {@code {"rotation": {"<t>": [rx, ry, rz], ...}}}; a bare {@code [rx, ry, rz]} rotation is constant. */
    @Nullable
    private static Track track(String bone, JsonElement json, String path, List<String> problems) {
        var rotation = json.isJsonObject() ? json.getAsJsonObject().get("rotation") : null;
        if (rotation == null) {
            problems.add(path + ": expected {\"rotation\": {\"<t seconds>\": [rx, ry, rz]}}");
            return null;
        }
        var constant = vector(rotation, 3);
        if (constant != null) {
            return new Track(bone, new float[] {0f}, constant);
        }
        if (!rotation.isJsonObject()) {
            problems.add(path + ".rotation: expected keyframes");
            return null;
        }
        var keys = new TreeMap<Float, float[]>();
        for (var k : rotation.getAsJsonObject().entrySet()) {
            float t;
            try {
                t = Float.parseFloat(k.getKey());
            } catch (NumberFormatException e) {
                t = Float.NaN;
            }
            var v = vector(k.getValue(), 3);
            if (!(t >= 0) || Float.isInfinite(t) || v == null) {
                problems.add(path + ".rotation." + k.getKey() + ": expected \"<t seconds>\": [rx, ry, rz]");
                continue;
            }
            keys.put(t, v);
        }
        if (keys.isEmpty()) {
            problems.add(path + ".rotation: no keyframes");
            return null;
        }
        var times = new float[keys.size()];
        var values = new float[keys.size() * 3];
        var i = 0;
        for (var k : keys.entrySet()) {
            times[i] = k.getKey();
            System.arraycopy(k.getValue(), 0, values, i * 3, 3);
            i++;
        }
        return new Track(bone, times, values);
    }

    @Nullable
    private static JsonObject object(JsonObject parent, String key, String path, List<String> problems) {
        var value = parent.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonObject()) {
            problems.add(path + ": expected an object");
            return null;
        }
        return value.getAsJsonObject();
    }

    @Nullable
    private static Float number(JsonElement json) {
        if (json == null || !json.isJsonPrimitive() || !json.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        var v = json.getAsFloat();
        return Float.isFinite(v) ? v : null;
    }

    @Nullable
    private static float[] vector(JsonElement json, int size) {
        if (json == null || !json.isJsonArray() || json.getAsJsonArray().size() != size) {
            return null;
        }
        var out = new float[size];
        var array = json.getAsJsonArray();
        for (var i = 0; i < size; i++) {
            var v = number(array.get(i));
            if (v == null) {
                return null;
            }
            out[i] = v;
        }
        return out;
    }
}
