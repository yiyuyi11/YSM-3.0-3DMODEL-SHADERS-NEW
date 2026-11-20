package com.ysmnpr.endfield;

import java.util.concurrent.atomic.LongAdder;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * {@code debugTiming=true}: averaged per-frame cost of each stage, logged every {@code debugTimingFrames} frames.
 * capture = NativeRenderer.render into the character's cell (native skinning + direct write), upload = glBufferSubData
 * (fallback) or explicit flush, plus fence waits (persistent), draw = CPU time of the batched passes, gpu =
 * GL_TIME_ELAPSED of those passes (read back a few frames later, never stalling), ysm = debug clones drawn through
 * YSM's path, extract = GeoModelState.extract (native pose + springs, summed over YSM's worker threads; "skipped" =
 * extracts the animation LOD held back), frame = time between two post-level hooks (1000/frame = FPS).
 */
public final class NprTiming {
    private static final int QUERIES = 4;

    public static volatile boolean on;
    private static long capture, upload, draw, gpu, ysm, frameSum, lastFrame;
    private static int frames, gpuSamples, captures, reused, ysmDraws, batches, draws;
    private static final LongAdder EXTRACT_NANOS = new LongAdder();
    private static final LongAdder EXTRACTS = new LongAdder();
    private static final LongAdder SKIPPED = new LongAdder();
    private static final int[] query = new int[QUERIES];
    private static final boolean[] pending = new boolean[QUERIES];
    private static int next;
    private static boolean active;

    private NprTiming() {}

    /** Start of the post-level hook: frame accounting and the periodic log. */
    static void frame() {
        on = NprConfig.bool("debugTiming");
        var now = System.nanoTime();
        if (!on) {
            lastFrame = 0;
            return;
        }
        if (lastFrame != 0) {
            frameSum += now - lastFrame;
            frames++;
        }
        lastFrame = now;
        collectGpu();
        if (frames >= Math.max(1, (int)NprConfig.f("debugTimingFrames"))) {
            var n = (double)frames;
            EndfieldNpr.LOG.info(String.format(
                "Endfield NPR timing over %d frames: frame %.2f ms (%.2f fps) | capture %.3f ms (%.1f skinned, "
                    + "%.1f reused /frame) | upload %.3f ms | draw cpu %.3f ms (%.1f draws in %.1f batches) | "
                    + "draw gpu %.3f ms | ysm clones %.3f ms (%.1f) | extract %.3f ms (%.2f /frame, %.2f skipped)",
                frames, frameSum / n / 1e6, 1e9 / (frameSum / n), capture / n / 1e6, captures / n, reused / n,
                upload / n / 1e6, draw / n / 1e6, draws / n, batches / n,
                gpuSamples > 0 ? gpu / (double)gpuSamples / 1e6 : -1.0, ysm / n / 1e6, ysmDraws / n,
                EXTRACT_NANOS.sumThenReset() / n / 1e6, EXTRACTS.sumThenReset() / n, SKIPPED.sumThenReset() / n));
            capture = upload = draw = gpu = ysm = frameSum = 0;
            frames = gpuSamples = captures = reused = ysmDraws = batches = draws = 0;
        }
    }

    static void capture(long nanos) {
        capture += nanos;
        captures++;
    }

    static void reused() {
        reused++;
    }

    static void upload(long nanos) {
        upload += nanos;
    }

    static void draw(long nanos) {
        draw += nanos;
    }

    static void batches(int groups, int drawn) {
        batches += groups;
        draws += drawn;
    }

    static void ysm(long nanos) {
        ysm += nanos;
        ysmDraws++;
    }

    /** From YSM's animation workers (GeoModelStateMixin). */
    public static void extract(long nanos) {
        EXTRACT_NANOS.add(nanos);
        EXTRACTS.increment();
    }

    public static void extractSkipped() {
        if (on) {
            SKIPPED.increment();
        }
    }

    private static int adaptLogs;

    /** debugTiming: eye-adaptation input (adapted level, this frame's level with full weather dimming) and result. */
    static void adapt(float ownLight, float weatherLight, float exposure) {
        if (on && adaptLogs++ % 16 == 0) {
            EndfieldNpr.LOG.info(String.format("Endfield NPR adapt: own light %.3f (with full weather dimming %.3f) | "
                + "exposure %.3f", ownLight, weatherLight, exposure));
        }
    }

    static void beginGpu() {
        if (query[0] == 0) {
            for (var i = 0; i < QUERIES; i++) {
                query[i] = GL15.glGenQueries();
            }
        }
        active = !pending[next]; // ring full: the oldest result is still in flight, skip this frame's sample
        if (active) {
            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query[next]);
        }
    }

    static void endGpu() {
        if (!active) {
            return;
        }
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        pending[next] = true;
        next = (next + 1) % QUERIES;
    }

    private static void collectGpu() {
        for (var i = 0; i < QUERIES; i++) {
            if (pending[i] && GL15.glGetQueryObjecti(query[i], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                gpu += GL33.glGetQueryObjecti64(query[i], GL15.GL_QUERY_RESULT);
                gpuSamples++;
                pending[i] = false;
            }
        }
    }
}
