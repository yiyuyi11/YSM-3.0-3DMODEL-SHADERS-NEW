package com.ysmfit.fitbox.core;

import java.util.HashMap;
import java.util.Map;

/**
 * One animation clip sampled at a single time: per bone the Bedrock-convention rotation (degrees), position offset
 * (pixels) and scale. Missing channels are rest (0, 0, 1).
 */
public final class Clip {
    public final String name;
    /** Where the clip came from, for reports: "model", "builtin" or "none". */
    public final String source;
    private final Map<String, float[]> bones = new HashMap<>();

    public Clip(String name, String source) {
        this.name = name;
        this.source = source;
    }

    public static Clip rest(String name) {
        return new Clip(name, "none");
    }

    /** Channel 0 rotation, 1 position, 2 scale. */
    public void set(String bone, int channel, float x, float y, float z) {
        var v = bones.computeIfAbsent(bone, k -> new float[] {0, 0, 0, 0, 0, 0, 1, 1, 1});
        v[channel * 3] = x;
        v[channel * 3 + 1] = y;
        v[channel * 3 + 2] = z;
    }

    /** rot xyz, pos xyz, scale xyz; null for an untouched bone. */
    public float[] get(String bone) {
        return bones.get(bone);
    }

    public int boneCount() {
        return bones.size();
    }

    /** This clip with another layered on top: every bone the top clip animates takes the top clip's channels. */
    public Clip overlay(Clip top) {
        var out = new Clip(name + "+" + top.name, source + "+" + top.source);
        bones.forEach((k, v) -> out.bones.put(k, v.clone()));
        top.bones.forEach((k, v) -> out.bones.put(k, v.clone()));
        return out;
    }

    /** Looks up clips by name for a model: its own clips first, then the defaults it borrows by name. */
    @FunctionalInterface
    public interface Library {
        /**
         * The clip sampled at a fraction of its length (0 = start, 1 = last keyframe), or null when neither the model
         * nor the defaults have it.
         */
        Clip get(String name, float at);

        default Clip get(String name) {
            return get(name, 0f);
        }

        default Library orElse(Library fallback) {
            return (name, at) -> {
                var c = get(name, at);
                return c != null ? c : fallback.get(name, at);
            };
        }
    }
}
