package com.elfmcys.ysm.client.compat.tacz.fp;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * A profile's arm-bone rotations for one first-person frame, written over the arm's animated bone attributes after
 * YSM's own arm animation (the importer's {@code parallel2} finger and wrist layer) and before the native extract.
 *
 * <p>{@code idle.bones} replace the animated rotation of their bones while the gun is held. During a profile clip that
 * TACZ is playing, the clip's bones are blended over that (idle or animated) rotation with a weight that ramps over
 * {@link #BLEND_S} at the clip's start and end, times TACZ's own fade when the clip is being replaced. A bone's
 * rotation is {@code rest + (-rx, -ry, rz)} in radians: the importer's keyframe convention (HumanoidImport, keyframes
 * store X and Y negated) as the native bone transform applies it.
 */
public final class TaczFpPose {
    /** Blend in at a clip's start and out at its end, seconds. */
    static final float BLEND_S = 0.1f;
    /** A clip whose TACZ length differs from {@code clipLengths} by more than this (seconds) is disabled. */
    static final float LENGTH_TOLERANCE_S = 0.005f;
    /** Floats per bone in the attribute array (AnimatedGeoModel.BONE_ATTRIBUTE_COUNT), rotation at offset 0. */
    static final int STRIDE = 14;

    /** Clips TACZ is playing on the held gun this frame (reused; filled by TaczFpTacz or a test). */
    public static final class ActiveClips {
        int count;
        String[] names = new String[8];
        float[] times = new float[8];
        float[] lengths = new float[8];
        float[] fades = new float[8];

        void clear() {
            count = 0;
        }

        /** A clip at {@code time} seconds of {@code length}, faded by TACZ's transition ({@code fade} 1: none). */
        void add(String name, float time, float length, float fade) {
            if (count == names.length) {
                names = java.util.Arrays.copyOf(names, count * 2);
                times = java.util.Arrays.copyOf(times, count * 2);
                lengths = java.util.Arrays.copyOf(lengths, count * 2);
                fades = java.util.Arrays.copyOf(fades, count * 2);
            }
            names[count] = name;
            times[count] = time;
            lengths[count] = length;
            fades[count] = fade;
            count++;
        }
    }

    /** The selected overrides of a frame (reused). */
    static final class Frame {
        TaczFpProfile.Track[] idle;
        @Nullable
        TaczFpProfile.Track[] clip;
        float clipTime;
        float weight;
        private final float[] key = new float[3];

        /** Writes the rotations over {@code attributes}; {@code rest} holds the bones' initial attributes. */
        void apply(Object2IntMap<String> boneIndex, float[] rest, float[] attributes) {
            for (var track : idle) {
                var bone = boneIndex.getInt(track.bone());
                if (bone >= 0) {
                    track.sample(0f, key);
                    write(bone, key, rest, attributes, 1f);
                }
            }
            if (clip != null && weight > 0f) {
                for (var track : clip) {
                    var bone = boneIndex.getInt(track.bone());
                    if (bone >= 0) {
                        track.sample(clipTime, key);
                        write(bone, key, rest, attributes, weight);
                    }
                }
            }
        }

        private static void write(int bone, float[] key, float[] rest, float[] attributes, float weight) {
            var o = bone * STRIDE;
            var x = rest[o] + (float)Math.toRadians(-key[0]);
            var y = rest[o + 1] + (float)Math.toRadians(-key[1]);
            var z = rest[o + 2] + (float)Math.toRadians(key[2]);
            attributes[o] += (x - attributes[o]) * weight;
            attributes[o + 1] += (y - attributes[o + 1]) * weight;
            attributes[o + 2] += (z - attributes[o + 2]) * weight;
        }
    }

    /** The clip's own blend weight at {@code t} of {@code length} seconds: 0 outside, ramps over BLEND_S. */
    static float clipWeight(float t, float length) {
        if (!(t >= 0f) || !(t < length)) {
            return 0f;
        }
        return Math.min(1f, Math.min(t, length - t) / BLEND_S);
    }

    /** Clips of one profile whose TACZ length didn't match, reported once each until the profiles reload. */
    static final class LengthCheck {
        private final Set<String> reported = new HashSet<>();
        private final Consumer<String> log;

        LengthCheck(Consumer<String> log) {
            this.log = log;
        }

        boolean matches(TaczFpProfile profile, String clip, float length) {
            var authored = profile.clipLengths.get(clip);
            if (authored == null || Math.abs(authored - length) <= LENGTH_TOLERANCE_S) {
                return true;
            }
            if (reported.add(profile.gun + " " + clip)) {
                log.accept(String.format("%s clip %s is %.3f s in TACZ but %.3f s in the profile: its overrides are "
                    + "disabled", profile.gun, clip, length, authored));
            }
            return false;
        }
    }

    /**
     * Selects the frame's overrides: the model's idle bones and, of the clips TACZ is playing that the profile has,
     * the one with the largest weight. Returns false when nothing applies.
     */
    static boolean select(TaczFpProfile.Resolved data, TaczFpProfile profile, ActiveClips active, LengthCheck lengths,
        Frame out) {
        out.idle = data.idle();
        out.clip = null;
        out.weight = 0f;
        for (var i = 0; i < active.count; i++) {
            var tracks = data.clips().get(active.names[i]);
            if (tracks == null || !lengths.matches(profile, active.names[i], active.lengths[i])) {
                continue;
            }
            var weight = clipWeight(active.times[i], active.lengths[i]) * active.fades[i];
            if (weight > out.weight) {
                out.clip = tracks;
                out.clipTime = active.times[i];
                out.weight = weight;
            }
        }
        return out.idle.length > 0 || out.clip != null;
    }

    private TaczFpPose() {
    }
}
