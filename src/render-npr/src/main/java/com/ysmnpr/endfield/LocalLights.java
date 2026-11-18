package com.ysmnpr.endfield;

import com.ysmnpr.endfield.CharacterCapture.Captured;
import java.util.IdentityHashMap;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

/**
 * Local lights of a FULL character from the world: Minecraft has no light entities, so the light-emitting blocks near
 * her (torches, lanterns, glowstone, lava, ...: {@link BlockState#getLightEmission()} &gt; 0) stand in for the game's
 * NPR punctual lights (Type0 Default, shaded in npr_lights.glsl). Per character (keyed by its slot) the nearest
 * {@link #MAX} emitters within {@code localLightRadius} blocks of her eyes that still light her eye block (MC level =
 * emission - Manhattan distance &gt; 0) and that she can see (no full opaque block on the line between) are cached and
 * refreshed every {@code localLightRefresh} ticks, or at once when her eyes moved more than a block. A scan reads only
 * the chunk sections of the box whose palette may hold an emitter ({@code LevelChunkSection.maybeHas}), at most
 * {@link #SCANS_PER_FRAME} scans per frame. Per frame the cached blocks only go into view space (uniforms LocalParams,
 * LocalPos, LocalTint). Render thread only.
 */
final class LocalLights {
    static final int MAX = 4;
    private static final int CANDIDATES = 8;
    private static final int SCANS_PER_FRAME = 2;
    private static final Predicate<BlockState> EMITS = s -> s.getLightEmission() > 0;
    private static final IdentityHashMap<NprRenderer.Slot, Entry> CACHE = new IdentityHashMap<>();
    private static final float[] POS = new float[16];
    private static final float[] TINT = new float[16];
    private static final Vector3f EYE = new Vector3f();
    private static final Vector3f VIEW = new Vector3f();
    private static final BlockPos.MutableBlockPos CURSOR = new BlockPos.MutableBlockPos();
    // scan candidates (best first): block position, emission, level at the eye block
    private static final int[] CX = new int[CANDIDATES];
    private static final int[] CY = new int[CANDIDATES];
    private static final int[] CZ = new int[CANDIDATES];
    private static final int[] CE = new int[CANDIDATES];
    private static final int[] CL = new int[CANDIDATES];
    private static int candidates;
    private static long budgetFrame = -1L;
    private static int budget;
    private static int bestLevel; // strongest found light's MC level at her eye block (lights())
    private static long sweepFrame;
    // debugTiming: scan cost
    private static long scanNanos;
    private static long scanBlocks;
    private static int scanCount;

    private LocalLights() {}

    private static final class Entry {
        long scanTick = Long.MIN_VALUE;
        int radius = -1;
        int ex, ey, ez; // eye block at the scan
        int count;
        final int[] x = new int[MAX];
        final int[] y = new int[MAX];
        final int[] z = new int[MAX];
        final int[] emission = new int[MAX];
        final Block[] block = new Block[MAX];
        long seen;
    }

    /**
     * Sets LocalParams / LocalPos / LocalTint for one group: the character's lights when {@code full} and
     * {@code localLights} is on, else none (LocalParams.x = 0: the shader keeps the ambient-only look exactly).
     */
    static void apply(ShaderInstance shader, Captured c, boolean full) {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        var n = 0;
        if (full && level != null && NprConfig.bool("localLights") && NprConfig.f("localLightIntensity") > 0f) {
            n = lights(level, c, Mth.clamp((int)NprConfig.f("localLightCount"), 0, MAX));
        }
        // y: the re-aimed share of her lightmap block light, times the part of her block level the lights explain (MC
        // block light is the strongest source's level: brightness of the best light's level at her eye block over
        // that of the lightmap's block level, LightTexture.getBrightness; a source beyond the scan keeps the rest)
        var explained = n > 0 ? Math.min(brightness(bestLevel) / Math.max(brightness(LightTexture.block(c.light)),
            1e-4f), 1f) : 0f;
        shader.safeGetUniform("LocalParams").set((float)n,
            Mth.clamp(NprConfig.f("localLightShare"), 0f, 1f) * explained, NprConfig.f("localLightIntensity"),
            NprConfig.f("localLightSpecular"));
        if (n > 0) {
            shader.safeGetUniform("LocalPos").set(POS);
            shader.safeGetUniform("LocalTint").set(TINT);
        }
    }

    /** Vanilla LightTexture.getBrightness of the overworld: f / (4 - 3f), f = level / 15. */
    private static float brightness(int level) {
        var f = Mth.clamp(level / 15f, 0f, 1f);
        return f / (4f - 3f * f);
    }

    /** Fills POS / TINT with up to {@code max} lights of {@code c}; returns their count. */
    private static int lights(ClientLevel level, Captured c, int max) {
        var frame = NprLod.frame;
        if (frame - sweepFrame > 600L) { // characters gone for ~10 s drop their cache
            sweepFrame = frame;
            CACHE.values().removeIf(e -> frame - e.seen > 600L);
        }
        if (max == 0) {
            return 0;
        }
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        // her eyes (the light probe of her lightmap value) in camera-relative world space
        NprRenderer.WORLD_TO_VIEW.transformTranspose(c.headCenter, EYE);
        var eyeX = cam.x + EYE.x;
        var eyeY = cam.y + EYE.y;
        var eyeZ = cam.z + EYE.z;
        var bx = Mth.floor(eyeX);
        var by = Mth.floor(eyeY);
        var bz = Mth.floor(eyeZ);
        var e = CACHE.computeIfAbsent(c.slot, k -> new Entry());
        e.seen = frame;
        var radius = Mth.clamp((int)NprConfig.f("localLightRadius"), 1, 15);
        var tick = level.getGameTime();
        var stale = tick - e.scanTick >= Math.max((long)NprConfig.f("localLightRefresh"), 1L) || tick < e.scanTick
            || Math.max(Math.abs(bx - e.ex), Math.max(Math.abs(by - e.ey), Math.abs(bz - e.ez))) > 1
            || radius != e.radius;
        if (budgetFrame != frame) {
            budgetFrame = frame;
            budget = SCANS_PER_FRAME;
        }
        if (stale && (budget > 0 || e.radius < 0)) {
            budget--;
            scan(level, e, bx, by, bz, eyeX, eyeY, eyeZ, radius);
            e.scanTick = tick;
        }
        var n = 0;
        bestLevel = 0;
        for (var i = 0; i < e.count && n < max; i++) {
            // level at her eyes, continuous (the shader's falloff is relative to it): emission - Manhattan distance
            var cx = e.x[i] + 0.5;
            var cy = e.y[i] + 0.5;
            var cz = e.z[i] + 0.5;
            var atEyes = e.emission[i] - (float)(Math.abs(cx - eyeX) + Math.abs(cy - eyeY) + Math.abs(cz - eyeZ));
            if (atEyes <= 0f) {
                continue;
            }
            NprRenderer.WORLD_TO_VIEW.transform((float)(cx - cam.x), (float)(cy - cam.y), (float)(cz - cam.z), VIEW);
            var o = n * 4;
            POS[o] = VIEW.x;
            POS[o + 1] = VIEW.y;
            POS[o + 2] = VIEW.z;
            POS[o + 3] = e.emission[i];
            tint(e.block[i], o);
            TINT[o + 3] = atEyes;
            bestLevel = Math.max(bestLevel, e.emission[i] - Math.abs(e.x[i] - bx) - Math.abs(e.y[i] - by)
                - Math.abs(e.z[i] - bz));
            n++;
        }
        return n;
    }

    /**
     * Light tint relative to the lightmap's own (warm) block-light colour, luminance 1: soul fire blue, redstone red,
     * the rest neutral (vanilla lights every emitter with the same colour).
     */
    private static void tint(Block b, int o) {
        float r = 1f;
        float g = 1f;
        float bl = 1f;
        if (b == Blocks.SOUL_TORCH || b == Blocks.SOUL_WALL_TORCH || b == Blocks.SOUL_LANTERN
            || b == Blocks.SOUL_CAMPFIRE || b == Blocks.SOUL_FIRE) {
            r = 0.45f;
            g = 1.0f;
            bl = 1.9f;
        } else if (b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_WALL_TORCH || b == Blocks.REDSTONE_ORE
            || b == Blocks.DEEPSLATE_REDSTONE_ORE || b == Blocks.REDSTONE_LAMP) {
            r = 2.2f;
            g = 0.75f;
            bl = 0.6f;
        }
        var l = 0.212673f * r + 0.715152f * g + 0.072175f * bl;
        TINT[o] = r / l;
        TINT[o + 1] = g / l;
        TINT[o + 2] = bl / l;
    }

    /**
     * Finds the emitters within {@code radius} (Chebyshev) of the eye block that light it (level = emission - Manhattan
     * distance &gt; 0), keeps the {@link #CANDIDATES} strongest, then the first {@link #MAX} of them in sight of her eyes.
     */
    private static void scan(ClientLevel level, Entry e, int bx, int by, int bz, double eyeX, double eyeY, double eyeZ,
        int radius) {
        var start = NprTiming.on ? System.nanoTime() : 0L;
        var blocks = 0L;
        e.ex = bx;
        e.ey = by;
        e.ez = bz;
        e.radius = radius;
        candidates = 0;
        var minY = Math.max(by - radius, level.getMinBuildHeight());
        var maxY = Math.min(by + radius, level.getMaxBuildHeight() - 1);
        var source = level.getChunkSource();
        for (var sx = (bx - radius) >> 4; sx <= (bx + radius) >> 4; sx++) {
            for (var sz = (bz - radius) >> 4; sz <= (bz + radius) >> 4; sz++) {
                var chunk = source.getChunk(sx, sz, false);
                if (chunk == null) {
                    continue;
                }
                var sections = chunk.getSections();
                for (var sy = minY >> 4; sy <= maxY >> 4; sy++) {
                    var index = chunk.getSectionIndexFromSectionY(sy);
                    if (index < 0 || index >= sections.length) {
                        continue;
                    }
                    var section = sections[index];
                    if (section.hasOnlyAir() || !section.maybeHas(EMITS)) { // palette check: most sections have none
                        continue;
                    }
                    var x0 = Math.max(bx - radius, sx << 4);
                    var x1 = Math.min(bx + radius, (sx << 4) + 15);
                    var y0 = Math.max(minY, sy << 4);
                    var y1 = Math.min(maxY, (sy << 4) + 15);
                    var z0 = Math.max(bz - radius, sz << 4);
                    var z1 = Math.min(bz + radius, (sz << 4) + 15);
                    blocks += (long)(x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
                    for (var y = y0; y <= y1; y++) {
                        for (var z = z0; z <= z1; z++) {
                            for (var x = x0; x <= x1; x++) {
                                var emission = section.getBlockState(x & 15, y & 15, z & 15).getLightEmission();
                                if (emission > 0) {
                                    offer(x, y, z, emission,
                                        emission - Math.abs(x - bx) - Math.abs(y - by) - Math.abs(z - bz));
                                }
                            }
                        }
                    }
                }
            }
        }
        e.count = 0;
        for (var i = 0; i < candidates && e.count < MAX; i++) {
            if (!visible(level, eyeX, eyeY, eyeZ, CX[i], CY[i], CZ[i])) {
                continue; // behind a wall: its light reaches her around it and stays in the ambient
            }
            var k = e.count++;
            e.x[k] = CX[i];
            e.y[k] = CY[i];
            e.z[k] = CZ[i];
            e.emission[k] = CE[i];
            e.block[k] = level.getBlockState(CURSOR.set(CX[i], CY[i], CZ[i])).getBlock();
        }
        if (NprTiming.on) {
            scanNanos += System.nanoTime() - start;
            scanBlocks += blocks;
            if (++scanCount == 200) {
                EndfieldNpr.LOG.info("Endfield NPR local lights: {} scans, {} us and {} blocks read per scan",
                    scanCount, String.format("%.1f", scanNanos / 1e3 / scanCount), scanBlocks / scanCount);
                scanNanos = 0L;
                scanBlocks = 0L;
                scanCount = 0;
            }
        }
    }

    /** Insertion into the candidate list, strongest level at the eye block first. */
    private static void offer(int x, int y, int z, int emission, int levelAtEye) {
        if (levelAtEye <= 0 || candidates == CANDIDATES && CL[CANDIDATES - 1] >= levelAtEye) {
            return;
        }
        var i = Math.min(candidates, CANDIDATES - 1);
        while (i > 0 && CL[i - 1] < levelAtEye) {
            CX[i] = CX[i - 1];
            CY[i] = CY[i - 1];
            CZ[i] = CZ[i - 1];
            CE[i] = CE[i - 1];
            CL[i] = CL[i - 1];
            i--;
        }
        CX[i] = x;
        CY[i] = y;
        CZ[i] = z;
        CE[i] = emission;
        CL[i] = levelAtEye;
        candidates = Math.min(candidates + 1, CANDIDATES);
    }

    /** No full opaque block between her eyes and the light block's centre (voxel walk, both end blocks excluded). */
    private static boolean visible(ClientLevel level, double ox, double oy, double oz, int lx, int ly, int lz) {
        var dx = lx + 0.5 - ox;
        var dy = ly + 0.5 - oy;
        var dz = lz + 0.5 - oz;
        int x = Mth.floor(ox);
        int y = Mth.floor(oy);
        int z = Mth.floor(oz);
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        var tdx = dx != 0 ? Math.abs(1.0 / dx) : Double.MAX_VALUE;
        var tdy = dy != 0 ? Math.abs(1.0 / dy) : Double.MAX_VALUE;
        var tdz = dz != 0 ? Math.abs(1.0 / dz) : Double.MAX_VALUE;
        var tx = dx != 0 ? (stepX > 0 ? x + 1 - ox : ox - x) * tdx : Double.MAX_VALUE;
        var ty = dy != 0 ? (stepY > 0 ? y + 1 - oy : oy - y) * tdy : Double.MAX_VALUE;
        var tz = dz != 0 ? (stepZ > 0 ? z + 1 - oz : oz - z) * tdz : Double.MAX_VALUE;
        for (var steps = 0; steps < 48; steps++) {
            if (tx <= ty && tx <= tz) {
                x += stepX;
                tx += tdx;
            } else if (ty <= tz) {
                y += stepY;
                ty += tdy;
            } else {
                z += stepZ;
                tz += tdz;
            }
            if (x == lx && y == ly && z == lz) {
                return true;
            }
            CURSOR.set(x, y, z);
            if (level.getBlockState(CURSOR).isSolidRender(level, CURSOR)) {
                return false;
            }
        }
        return true;
    }
}
