package com.ysmfit.fitbox.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Bedrock animation JSON (YSM's builtin default clips) sampled at time 0 with {@link Molang} for string values. */
public final class JsonClips implements Clip.Library {
    private final Map<String, JsonObject> animations = new HashMap<>();
    private final String source;

    public JsonClips(JsonObject root, String source) {
        this.source = source;
        var anims = root.getAsJsonObject("animations");
        if (anims != null) {
            for (var e : anims.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    animations.put(e.getKey(), e.getValue().getAsJsonObject());
                }
            }
        }
    }

    public static JsonClips read(InputStream in, String source) {
        return new JsonClips(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject(),
            source);
    }

    @Override
    public Clip get(String name, float at) {
        var anim = animations.get(name);
        if (anim == null) {
            return null;
        }
        var clip = new Clip(name, source);
        var bones = anim.getAsJsonObject("bones");
        if (bones == null) {
            return clip;
        }
        // Length: animation_length, else the last keyframe time of any channel.
        var length = 0f;
        if (anim.has("animation_length") && anim.get("animation_length").isJsonPrimitive()) {
            length = anim.get("animation_length").getAsFloat();
        } else {
            for (var b : bones.entrySet()) {
                if (b.getValue().isJsonObject()) {
                    for (var ch : b.getValue().getAsJsonObject().entrySet()) {
                        var keys = keys(ch.getValue());
                        if (keys != null && !keys.isEmpty()) {
                            length = Math.max(length, keys.lastKey());
                        }
                    }
                }
            }
        }
        var time = Math.max(0f, Math.min(1f, at)) * length;
        for (var b : bones.entrySet()) {
            if (!b.getValue().isJsonObject()) {
                continue;
            }
            var ch = b.getValue().getAsJsonObject();
            channel(clip, b.getKey(), 0, ch.get("rotation"), 0f, time);
            channel(clip, b.getKey(), 1, ch.get("position"), 0f, time);
            channel(clip, b.getKey(), 2, ch.get("scale"), 1f, time);
        }
        return clip;
    }

    private static void channel(Clip clip, String bone, int channel, JsonElement value, float rest, float time) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        var keys = keys(value);
        float[] out;
        if (keys == null) {
            out = vector(value, rest);
        } else if (keys.isEmpty()) {
            return;
        } else {
            var before = keys.floorEntry(time);
            var after = keys.ceilingEntry(time);
            if (before == null) {
                out = vector(side(after.getValue(), false), rest);
            } else if (after == null || after.getKey().equals(before.getKey())) {
                out = vector(side(before.getValue(), true), rest);
            } else {
                // Linear between the leaving side of one keyframe and the arriving side of the next.
                var a = vector(side(before.getValue(), true), rest);
                var b = vector(side(after.getValue(), false), rest);
                var f = (time - before.getKey()) / (after.getKey() - before.getKey());
                out = new float[] {a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
            }
        }
        clip.set(bone, channel, out[0], out[1], out[2]);
    }

    /** Keyframes by time (seconds), or null for a constant channel. */
    private static TreeMap<Float, JsonElement> keys(JsonElement value) {
        if (!value.isJsonObject()) {
            return null;
        }
        var o = value.getAsJsonObject();
        if (o.has("post") || o.has("pre")) {
            return null;
        }
        var keys = new TreeMap<Float, JsonElement>();
        for (var e : o.entrySet()) {
            try {
                keys.put(Float.parseFloat(e.getKey()), e.getValue());
            } catch (NumberFormatException ignored) {
                // lerp_mode and other non-time keys
            }
        }
        return keys;
    }

    /** A keyframe's value leaving it ("post") or arriving at it ("pre"); plain values are both. */
    private static JsonElement side(JsonElement key, boolean leaving) {
        if (key.isJsonObject()) {
            var o = key.getAsJsonObject();
            if (leaving) {
                return o.has("post") ? o.get("post") : o.get("pre");
            }
            return o.has("pre") ? o.get("pre") : o.get("post");
        }
        return key;
    }

    private static float[] vector(JsonElement v, float rest) {
        if (v == null) {
            return new float[] {rest, rest, rest};
        }
        if (v.isJsonObject()) {
            // A constant written as {"pre"/"post"} or a lone keyframe object.
            return vector(side(v, true), rest);
        }
        if (v.isJsonArray()) {
            JsonArray a = v.getAsJsonArray();
            var out = new float[] {rest, rest, rest};
            for (var i = 0; i < Math.min(3, a.size()); i++) {
                out[i] = scalar(a.get(i), rest);
            }
            return out;
        }
        var s = scalar(v, rest);
        return new float[] {s, s, s};
    }

    private static float scalar(JsonElement e, float rest) {
        if (e == null || !e.isJsonPrimitive()) {
            return rest;
        }
        var p = e.getAsJsonPrimitive();
        if (p.isNumber()) {
            return p.getAsFloat();
        }
        return Molang.eval(p.getAsString(), rest);
    }
}
