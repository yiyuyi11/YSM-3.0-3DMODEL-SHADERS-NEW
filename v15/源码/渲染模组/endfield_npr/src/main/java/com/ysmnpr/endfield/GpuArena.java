package com.ysmnpr.endfield;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

/**
 * Vertex storage of every NPR character, shared so that one pass can draw all characters of a page with a single
 * multi-draw.
 *
 * <p>Pages hold {@value #CELLS_PER_PAGE} fixed-size cells; a cell is one character and holds two copies of its skinned
 * mesh (double buffering: the copy being written was last drawn at least one update earlier). All cells of a page
 * share one VAO (NEW_ENTITY attributes at offset 0, the shared QUADS index buffer, the per-draw index stream);
 * {@code baseVertex} selects the copy.
 *
 * <p>Persistent mode ({@code gpuPersistentBuffers}, needs ARB_buffer_storage / GL 4.4): each page is immutable storage
 * mapped once with MAP_PERSISTENT (+ MAP_COHERENT, or MAP_FLUSH_EXPLICIT with {@code gpuCoherentMapping=false}). YSM's
 * native skinning writes straight into the mapped copy (zero copy, no glBufferData). One fence per frame after the NPR
 * passes; before a copy is overwritten, the fence of the last frame that drew it must have signalled
 * (glClientWaitSync). At most {@value #FENCES} frames are tracked: when a ring entry is recycled its fence is waited
 * for, so every older frame is known complete. Fallback mode (no extension, or the key off): pages are plain
 * glBufferData(null) storage and each capture is copied in with one glBufferSubData from a CPU staging buffer.
 *
 * <p>Buffers and VAOs are created and edited by name with GL 4.5 / ARB_direct_state_access, else through
 * GL_COPY_WRITE_BUFFER (which vanilla does not track) and a raw VAO bind (see {@link GlObjects}), so capture-time work
 * inside YSM's draw does not disturb Minecraft's GL state cache. Render thread only.
 */
final class GpuArena {
    static final int STRIDE = 36; // NEW_ENTITY: pos 3f, color 4ub, uv0 2f, uv1 2s, uv2 2s, normal 3b, pad
    static final int CELLS_PER_PAGE = 4;
    private static final int FENCES = 8;
    private static final long WAIT_NANOS = 2_000_000_000L;
    /** VAO binding point of the per-draw index stream under DSA (clear of the NEW_ENTITY attributes' bindings 0-5). */
    private static final int DRAW_INDEX_BINDING = 15;
    /** VAO binding point of the rain anchor buffer under DSA. */
    private static final int ANCHOR_BINDING = 14;
    /**
     * Attribute locations of RainAnchorP / RainAnchorN in the character program (14 / 15 with
     * ARB_explicit_attrib_location), -1 = anchoring off (not resolved yet, or a clash with a draw-index location).
     */
    private static int anchorPosLoc = -1;
    private static int anchorNormalLoc = -1;
    /** CPU staging of {@link Cell#writeAnchors}, grown to the largest skin. */
    @Nullable
    private static ByteBuffer anchorStaging;

    private static final ArrayList<Page> PAGES = new ArrayList<>();
    private static final long[] FENCE = new long[FENCES];
    private static final long[] FENCE_FRAME = new long[FENCES];
    private static long completedFrame = -1;
    private static int pageIds;
    private static boolean persistent;
    private static boolean coherent;
    private static boolean modeKnown;
    @Nullable
    private static ByteBuffer staging;
    static long fenceWaitNanos;
    /** Shared QUADS index buffer (0,1,2, 2,3,0 per quad), bound into every page VAO; grown in place. */
    private static int quadBuffer;
    private static int quadCapacity;

    /**
     * The character program's rain anchor locations (NprRenderer, after each program (re)load). Anchoring needs both,
     * outside the NEW_ENTITY locations 0-5 and clear of every program's per-draw index location: all of them share the
     * page VAOs.
     */
    static void anchorLocations(int pos, int normal, int[] drawIndexLocs) {
        var ok = pos > 5 && normal > 5 && pos != normal;
        for (var loc : drawIndexLocs) {
            ok &= loc != pos && loc != normal;
        }
        if (!ok && pos >= 0 && normal >= 0) {
            EndfieldNpr.LOG.warn("Endfield NPR: rain anchor locations {}/{} clash with {}; rain patterns use the rigid "
                + "body frame", pos, normal, java.util.Arrays.toString(drawIndexLocs));
        }
        anchorPosLoc = ok ? pos : -1;
        anchorNormalLoc = ok ? normal : -1;
    }

    /** Whether draws can read rain anchors (see {@link #anchorLocations}). */
    static boolean anchorsUsable() {
        return anchorPosLoc > 5;
    }

    private GpuArena() {}

    static void ensureConfigured() {
        if (!modeKnown) {
            configure();
        }
    }

    /**
     * Applies {@code gpuPersistentBuffers}/{@code gpuCoherentMapping}; on a change every page is released and the
     * characters are re-skinned. Called once per frame before any capture of that frame.
     */
    static void configure() {
        var caps = GL.getCapabilities();
        var wantPersistent = NprConfig.bool("gpuPersistentBuffers") && caps.glBufferStorage != 0L;
        var wantCoherent = NprConfig.bool("gpuCoherentMapping");
        if (modeKnown && wantPersistent == persistent && (!persistent || wantCoherent == coherent)) {
            return;
        }
        if (modeKnown) {
            releaseAll();
        }
        persistent = wantPersistent;
        coherent = wantCoherent;
        modeKnown = true;
        EndfieldNpr.LOG.info("Endfield NPR vertex storage: {}", persistent
            ? "persistent mapped (ARB_buffer_storage, " + (coherent ? "coherent" : "explicit flush") + ", fenced)"
            : "glBufferSubData" + (NprConfig.bool("gpuPersistentBuffers") ? " (ARB_buffer_storage missing)" : ""));
    }

    private static void releaseAll() {
        // GL keeps deleted buffers alive until in-flight commands are done, and nothing writes to them after this
        for (var page : PAGES) {
            page.delete();
        }
        PAGES.clear();
        NprLod.dropSlots();
        FpArms.dropSlots();
    }

    /** A cell with room for {@code vertices} per copy (plus 1/8 headroom), from a page whose cells are big enough. */
    static Cell allocate(int vertices) {
        var copyVertices = vertices + (vertices >> 3) + 4;
        for (var page : PAGES) {
            if (page.copyVertices >= copyVertices && page.copyVertices <= copyVertices * 2) {
                for (var i = 0; i < CELLS_PER_PAGE; i++) {
                    if (page.cells[i] == null) {
                        return page.cells[i] = new Cell(page, i);
                    }
                }
            }
        }
        var page = new Page(copyVertices);
        PAGES.add(page);
        ensureQuads(copyVertices / 4);
        return page.cells[0] = new Cell(page, 0);
    }

    /** Grows the shared quad index buffer to at least {@code quads} (by name or through COPY_WRITE: no VAO disturbed). */
    private static void ensureQuads(int quads) {
        if (quadBuffer == 0) {
            quadBuffer = GlObjects.buffer();
        }
        if (quads <= quadCapacity) {
            return;
        }
        var size = Math.max(quads, quadCapacity * 2);
        var indices = MemoryUtil.memAllocInt(size * 6);
        try {
            for (var q = 0; q < size; q++) {
                var b = q * 4;
                indices.put(b).put(b + 1).put(b + 2).put(b + 2).put(b + 3).put(b);
            }
            GlObjects.bufferData(quadBuffer, (long)size * 6 * 4, MemoryUtil.memAddress0(indices), GL15.GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(indices);
        }
        quadCapacity = size;
    }

    /**
     * Starts writing {@code copy} of a cell: waits until the GPU finished the last frame that drew it, then returns
     * the destination (the mapped copy itself, or the CPU staging buffer in fallback mode), position 0.
     */
    static ByteBuffer beginWrite(Cell cell, int copy) {
        var bytes = cell.page.copyBytes();
        if (persistent) {
            waitFor(cell.useFrame(copy));
            return cell.views[copy];
        }
        if (staging == null || staging.capacity() < bytes) {
            if (staging != null) {
                MemoryUtil.memFree(staging);
            }
            staging = MemoryUtil.memAlloc((int)bytes).order(ByteOrder.nativeOrder());
        }
        return staging;
    }

    /**
     * Publishes {@code vertices} written into {@code copy}: flush (explicit mode) or upload (fallback). With
     * {@code cpu} != null the vertices were written there instead (FULL tier: the overlay card runs and the
     * self-shadow fit read them on the CPU) and are copied into the mapped copy or uploaded from it; {@code boxFrame}
     * != null also measures the self-shadow box during that copy ({@link CpuMesh#publish}).
     */
    static void endWrite(Cell cell, int copy, int vertices, @Nullable CpuMesh cpu,
        @Nullable org.joml.Matrix4f boxFrame) {
        if (vertices <= 0) {
            return;
        }
        var bytes = (long)vertices * STRIDE;
        if (persistent) {
            if (cpu != null) {
                cpu.publish(cell.page.mapped + cell.copyOffset(copy), boxFrame);
            }
            if (coherent) {
                return; // coherent mapping: the writes are visible to every command issued after them
            }
        } else if (cpu != null) {
            cpu.publish(0L, boxFrame);
        }
        if (persistent) {
            GlObjects.flushMapped(cell.page.buffer, cell.copyOffset(copy), bytes);
        } else {
            GlObjects.bufferSubData(cell.page.buffer, cell.copyOffset(copy), bytes,
                MemoryUtil.memAddress(cpu != null ? cpu.data : staging));
        }
    }

    /**
     * After this frame's NPR passes: one fence covering every copy drawn in {@code frame}. A second fence of the same
     * frame (the first-person arms of vanilla's hand pass carry the next frame's number, which renderAll fences again)
     * replaces the first: it signals after every command before it.
     */
    static void fence(long frame) {
        if (!persistent) {
            return;
        }
        var i = (int)(frame % FENCES);
        if (FENCE[i] != 0L && FENCE_FRAME[i] == frame) {
            GL32.glDeleteSync(FENCE[i]); // deleted once signalled; nothing waits on it
            FENCE[i] = 0L;
        } else if (FENCE[i] != 0L) {
            waitSync(i); // recycling: the frame FENCES ago must be complete
        }
        FENCE[i] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        FENCE_FRAME[i] = frame;
        // retire signalled fences without blocking, oldest first: fences signal in submission order, so the first
        // pending one ends the scan (one or two polls a frame instead of one per ring entry)
        while (true) {
            var oldest = -1;
            for (var k = 0; k < FENCES; k++) {
                if (FENCE[k] != 0L && k != i && (oldest < 0 || FENCE_FRAME[k] < FENCE_FRAME[oldest])) {
                    oldest = k;
                }
            }
            if (oldest < 0 || GL32.glClientWaitSync(FENCE[oldest], 0, 0L) == GL32.GL_TIMEOUT_EXPIRED) {
                break;
            }
            completed(oldest);
        }
    }

    private static void waitFor(long frame) {
        if (frame < 0 || frame <= completedFrame) {
            return;
        }
        for (var i = 0; i < FENCES; i++) {
            if (FENCE[i] != 0L && FENCE_FRAME[i] == frame) {
                waitSync(i);
                return;
            }
        }
        // frame not fenced yet (drawn this frame, fence comes after the passes) or already recycled: finish GL work
        var start = System.nanoTime();
        GL11.glFinish();
        fenceWaitNanos += System.nanoTime() - start;
        completedFrame = Math.max(completedFrame, frame);
    }

    private static void waitSync(int i) {
        var start = System.nanoTime();
        var result = GL32.glClientWaitSync(FENCE[i], GL32.GL_SYNC_FLUSH_COMMANDS_BIT, WAIT_NANOS);
        while (result == GL32.GL_TIMEOUT_EXPIRED) {
            result = GL32.glClientWaitSync(FENCE[i], GL32.GL_SYNC_FLUSH_COMMANDS_BIT, WAIT_NANOS);
        }
        fenceWaitNanos += System.nanoTime() - start;
        completed(i);
    }

    private static void completed(int i) {
        GL32.glDeleteSync(FENCE[i]);
        FENCE[i] = 0L;
        completedFrame = Math.max(completedFrame, FENCE_FRAME[i]);
    }

    /** One character's storage: two copies of up to {@link Page#copyVertices} vertices. */
    static final class Cell {
        final Page page;
        final int index;
        /** Direct views of the two mapped copies (persistent mode), handed to YSM's native write. */
        final ByteBuffer[] views = new ByteBuffer[2];

        Cell(Page page, int index) {
            this.page = page;
            this.index = index;
            if (page.mapped != 0L) {
                for (var copy = 0; copy < 2; copy++) {
                    views[copy] = MemoryUtil.memByteBuffer(page.mapped + copyOffset(copy), (int)page.copyBytes())
                        .order(ByteOrder.nativeOrder());
                }
            }
        }

        long copyOffset(int copy) {
            return (2L * index + copy) * page.copyBytes();
        }

        /** baseVertex of a copy in its page's VAO. */
        int baseVertex(int copy) {
            return (2 * index + copy) * page.copyVertices;
        }

        int capacity() {
            return page.copyVertices;
        }

        /** Last frame this copy was drawn (fence frame), -1 = never; kept by the page across cell reuse. */
        long useFrame(int copy) {
            return page.useFrame[2 * index + copy];
        }

        void used(int copy, long frame) {
            page.useFrame[2 * index + copy] = frame;
        }

        /**
         * Builds the rain anchors of a fresh skin ({@link RainAnchors#build}) and uploads them into both copies' ranges
         * of the page's anchor buffer (created on first use): a draw of either copy reads them through its baseVertex.
         * Rare (once per slot and vertex count), so plain glBufferSubData: GL orders it after earlier draws.
         */
        void writeAnchors(ByteBuffer skin, int count, org.joml.Matrix4fc modelView, org.joml.Vector3fc right,
            org.joml.Vector3fc up, org.joml.Vector3fc forward, org.joml.Vector3fc origin) {
            var bytes = (long)count * RainAnchors.STRIDE;
            if (anchorStaging == null || anchorStaging.capacity() < bytes) {
                if (anchorStaging != null) {
                    MemoryUtil.memFree(anchorStaging);
                }
                anchorStaging = MemoryUtil.memAlloc((int)bytes).order(ByteOrder.nativeOrder());
            }
            RainAnchors.build(skin, count, modelView, right, up, forward, origin, anchorStaging);
            var address = MemoryUtil.memAddress0(anchorStaging);
            var buffer = page.anchorBuffer();
            for (var copy = 0; copy < 2; copy++) {
                GlObjects.bufferSubData(buffer, (long)baseVertex(copy) * RainAnchors.STRIDE, bytes, address);
            }
        }

        void free() {
            page.cells[index] = null;
            if (page.isEmpty() && PAGES.size() > 1) {
                // GL defers the deletion until the fenced draws that read it are done; nothing writes to it again
                page.delete();
                PAGES.remove(page);
            }
        }
    }

    /** One buffer + VAO holding {@value #CELLS_PER_PAGE} cells. */
    static final class Page {
        final int id = pageIds++;
        final int copyVertices;
        final Cell[] cells = new Cell[CELLS_PER_PAGE];
        final long[] useFrame = new long[2 * CELLS_PER_PAGE];
        final int buffer;
        final long mapped;
        int vao;
        /** Attribute locations of the per-draw index stream bound in the VAO (MDI mode), or null. */
        @Nullable
        int[] drawIndexLocs;
        boolean vaoMultiDraw;
        /** Rain anchors of every vertex slot of the page (RainAnchors.STRIDE each), 0 until a cell first needs them. */
        private int anchorBuffer;
        /** Anchor locations set up in the VAO, -1 = none. */
        private int anchorPosBound = -1;
        private int anchorNormalBound = -1;

        Page(int copyVertices) {
            this.copyVertices = copyVertices;
            java.util.Arrays.fill(useFrame, -1L);
            var size = copyBytes() * 2L * CELLS_PER_PAGE;
            buffer = GlObjects.buffer();
            if (persistent) {
                var access = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT
                    | (coherent ? GL44.GL_MAP_COHERENT_BIT : GL30.GL_MAP_FLUSH_EXPLICIT_BIT);
                mapped = GlObjects.storageMapped(buffer, size,
                    GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | (coherent ? GL44.GL_MAP_COHERENT_BIT : 0),
                    access);
                if (mapped == 0L) {
                    throw new IllegalStateException("glMapBufferRange failed for a persistent NPR page");
                }
            } else {
                GlObjects.bufferData(buffer, size, 0L, GL15.GL_DYNAMIC_DRAW);
                mapped = 0L;
            }
            EndfieldNpr.LOG.info("Endfield NPR: page {} with {} cells x 2 copies x {} vertices ({} MB, {})", id,
                CELLS_PER_PAGE, copyVertices, size >> 20, persistent ? "persistent" : "buffer data");
        }

        long copyBytes() {
            return (long)copyVertices * STRIDE;
        }

        boolean isEmpty() {
            for (var c : cells) {
                if (c != null) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Binds this page's VAO through GlStateManager (ShaderInstance passes), creating it on first use;
         * {@code drawIndexBuffer} != 0 binds the per-draw index stream (divisor 1, read through baseInstance) at the
         * given locations, 0 leaves those attributes disabled so glVertexAttrib1f supplies the index.
         */
        void bindVao(int drawIndexBuffer, int[] locs) {
            var multiDraw = drawIndexBuffer != 0;
            if (vao == 0) {
                createVao();
            }
            GlStateManager._glBindVertexArray(vao);
            if (multiDraw != vaoMultiDraw || !java.util.Arrays.equals(locs, drawIndexLocs)) {
                var dsa = GlObjects.dsa();
                if (drawIndexLocs != null) {
                    for (var loc : drawIndexLocs) {
                        if (loc > 5) {
                            if (dsa) {
                                GL45.glDisableVertexArrayAttrib(vao, loc);
                            } else {
                                GL20.glDisableVertexAttribArray(loc);
                            }
                        }
                    }
                }
                if (multiDraw && dsa) {
                    // its own binding point (never one of the NEW_ENTITY attributes' implicit bindings 0-5)
                    GL45.glVertexArrayVertexBuffer(vao, DRAW_INDEX_BINDING, drawIndexBuffer, 0L, 4);
                    GL45.glVertexArrayBindingDivisor(vao, DRAW_INDEX_BINDING, 1);
                    for (var loc : locs) {
                        if (loc > 5) {
                            GL45.glVertexArrayAttribFormat(vao, loc, 1, GL11.GL_FLOAT, false, 0);
                            GL45.glVertexArrayAttribBinding(vao, loc, DRAW_INDEX_BINDING);
                            GL45.glEnableVertexArrayAttrib(vao, loc);
                        }
                    }
                } else if (multiDraw) {
                    GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, drawIndexBuffer);
                    for (var loc : locs) {
                        if (loc > 5) { // 0-5 are the NEW_ENTITY attributes; -1 = not active in that program
                            GL20.glEnableVertexAttribArray(loc);
                            GL20.glVertexAttribPointer(loc, 1, GL11.GL_FLOAT, false, 4, 0L);
                            GL33.glVertexAttribDivisor(loc, 1);
                        }
                    }
                }
                drawIndexLocs = locs.clone();
                vaoMultiDraw = multiDraw;
            }
            bindAnchors();
        }

        /** The page's anchor buffer, created on first use (same vertex slots as the page: 2 copies x 4 cells). */
        int anchorBuffer() {
            if (anchorBuffer == 0) {
                anchorBuffer = GlObjects.buffer();
                var size = (long)copyVertices * 2L * CELLS_PER_PAGE * RainAnchors.STRIDE;
                GlObjects.bufferData(anchorBuffer, size, 0L, GL15.GL_STATIC_DRAW);
                EndfieldNpr.LOG.info("Endfield NPR: rain anchors for page {} ({} KB)", id, size >> 10);
            }
            return anchorBuffer;
        }

        /** Points the anchor locations at the anchor buffer (once per buffer and program locations; VAO bound). */
        private void bindAnchors() {
            if (anchorBuffer == 0 || anchorPosBound == anchorPosLoc && anchorNormalBound == anchorNormalLoc) {
                return;
            }
            var dsa = GlObjects.dsa();
            for (var loc : new int[] {anchorPosBound, anchorNormalBound}) {
                if (loc > 5) {
                    if (dsa) {
                        GL45.glDisableVertexArrayAttrib(vao, loc);
                    } else {
                        GL20.glDisableVertexAttribArray(loc);
                    }
                }
            }
            if (anchorPosLoc > 5) {
                if (dsa) {
                    GL45.glVertexArrayVertexBuffer(vao, ANCHOR_BINDING, anchorBuffer, 0L, RainAnchors.STRIDE);
                    GL45.glVertexArrayAttribFormat(vao, anchorPosLoc, 3, GL11.GL_FLOAT, false, 0);
                    GL45.glVertexArrayAttribFormat(vao, anchorNormalLoc, 3, GL11.GL_BYTE, true, 12);
                    GL45.glVertexArrayAttribBinding(vao, anchorPosLoc, ANCHOR_BINDING);
                    GL45.glVertexArrayAttribBinding(vao, anchorNormalLoc, ANCHOR_BINDING);
                    GL45.glEnableVertexArrayAttrib(vao, anchorPosLoc);
                    GL45.glEnableVertexArrayAttrib(vao, anchorNormalLoc);
                } else {
                    GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, anchorBuffer);
                    GL20.glEnableVertexAttribArray(anchorPosLoc);
                    GL20.glVertexAttribPointer(anchorPosLoc, 3, GL11.GL_FLOAT, false, RainAnchors.STRIDE, 0L);
                    GL33.glVertexAttribDivisor(anchorPosLoc, 0);
                    GL20.glEnableVertexAttribArray(anchorNormalLoc);
                    GL20.glVertexAttribPointer(anchorNormalLoc, 3, GL11.GL_BYTE, true, RainAnchors.STRIDE, 12L);
                    GL33.glVertexAttribDivisor(anchorNormalLoc, 0);
                }
            }
            anchorPosBound = anchorPosLoc;
            anchorNormalBound = anchorNormalLoc;
        }

        /**
         * Binds this page's VAO with raw GL inside a {@link RawGl} save/restore bracket (depth prepass, self-shadow
         * caster), creating it on first use. Only Position (0) and UV0 (2) are read there.
         */
        void bindRaw() {
            if (vao == 0) {
                createVao();
            }
            GL30.glBindVertexArray(vao);
        }

        /**
         * The page's VAO: by name under DSA (nothing bound), else raw GL with the VAO bound: callers either restore the
         * bindings (RawGl) or rebind through GlStateManager right after.
         */
        private void createVao() {
            if (GlObjects.dsa()) {
                vao = GL45.glCreateVertexArrays();
                GL45.glVertexArrayVertexBuffer(vao, 0, buffer, 0L, STRIDE);
                format(vao, 0, 3, GL11.GL_FLOAT, true, false, 0); // Position
                format(vao, 1, 4, GL11.GL_UNSIGNED_BYTE, true, true, 12); // Color
                format(vao, 2, 2, GL11.GL_FLOAT, true, false, 16); // UV0
                format(vao, 3, 2, GL11.GL_SHORT, false, false, 24); // UV1 (overlay), integer
                format(vao, 4, 2, GL11.GL_SHORT, false, false, 28); // UV2 (light), integer
                format(vao, 5, 3, GL11.GL_BYTE, true, true, 32); // Normal
                GL45.glVertexArrayElementBuffer(vao, quadBuffer); // growth keeps the name
                return;
            }
            vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            setupAttributes();
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, quadBuffer); // VAO state; growth keeps the name
        }

        /** One NEW_ENTITY attribute on binding 0 (DSA); {@code asFloat} false = integer attribute. */
        private static void format(int vao, int loc, int size, int type, boolean asFloat, boolean normalized,
            int offset) {
            if (asFloat) {
                GL45.glVertexArrayAttribFormat(vao, loc, size, type, normalized, offset);
            } else {
                GL45.glVertexArrayAttribIFormat(vao, loc, size, type, offset);
            }
            GL45.glVertexArrayAttribBinding(vao, loc, 0);
            GL45.glEnableVertexArrayAttrib(vao, loc);
        }

        /**
         * NEW_ENTITY attribute pointers, written out instead of {@code VertexFormat#setupBufferState}: with a shader
         * pack Oculus redirects that call for NEW_ENTITY to its extended entity layout (larger stride), which does not
         * match the 36-byte vertices YSM writes here. Locations follow the format's element order, as ShaderInstance
         * binds them.
         */
        private static void setupAttributes() {
            GL20.glEnableVertexAttribArray(0); // Position
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE, 0L);
            GL20.glEnableVertexAttribArray(1); // Color
            GL20.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE, 12L);
            GL20.glEnableVertexAttribArray(2); // UV0
            GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, STRIDE, 16L);
            GL20.glEnableVertexAttribArray(3); // UV1 (overlay)
            GL30.glVertexAttribIPointer(3, 2, GL11.GL_SHORT, STRIDE, 24L);
            GL20.glEnableVertexAttribArray(4); // UV2 (light)
            GL30.glVertexAttribIPointer(4, 2, GL11.GL_SHORT, STRIDE, 28L);
            GL20.glEnableVertexAttribArray(5); // Normal
            GL20.glVertexAttribPointer(5, 3, GL11.GL_BYTE, true, STRIDE, 32L);
        }

        void delete() {
            if (vao != 0) {
                GlStateManager._glDeleteVertexArrays(vao);
            }
            if (mapped != 0L) {
                GlObjects.unmap(buffer);
            }
            // not GlStateManager._glDeleteBuffers: on Linux it first calls glBufferData(0), which is
            // GL_INVALID_OPERATION on immutable (glBufferStorage) buffers
            GL15.glDeleteBuffers(buffer);
            if (anchorBuffer != 0) {
                GL15.glDeleteBuffers(anchorBuffer);
            }
        }
    }
}
