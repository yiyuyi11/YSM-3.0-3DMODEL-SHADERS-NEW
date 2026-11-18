package com.elfmcys.ysm.natives.render;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.annotation.Borrowed;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.natives.NativeObject;
import com.elfmcys.ysm.util.ExposedShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.lwjgl.system.MemoryUtil;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.function.Function;

/**
 * Owns one mutable native frame publication and its reusable storage.
 *
 * <p>Invariant: valid means that native extraction and Java view/count publication both completed.
 * Invalid state has no consumable publication; zero counts alone do not identify a valid empty result.
 *
 * <p>Execution: the owner serializes extract, view consumption, rendering, invalidate, and close
 * for this instance. Different instances may extract concurrently. Reachability fences do not
 * provide this exclusion.
 *
 * <p>Ownership: pose, index, and locator views borrow this state until its next extract,
 * invalidate, or close. Consumers retain the state and must not modify the borrowed data.
 * Extraction borrows attributes only for the call and retains the baked backing in native state.
 *
 * <p>Seam: implements the
 * [frame publication and borrow contract](docs/architecture/rendering/overview.md#帧状态发布与借用).
 */
public final class NativeModelState extends NativeObject {
    private static final int BONE_INFO_INT_COUNT = 8;
    private static final int CUBE_INFO_HEADER_INT_COUNT = 2;
    private static final int CUBE_INFO_INT_COUNT = 2;

    /** dt, reset, gravity xyz, column-major motion; matches native PhysicsFrame. */
    private static final int PHYSICS_FLOAT_COUNT = 21;
    /** Springs restart from the animated pose after this long without a step (pause, unloaded entity). */
    private static final long MAX_STEP_GAP_NANOS = 500_000_000L;
    /** Motion beyond this many model units in one step is a teleport. */
    private static final double MAX_STEP_MOTION = 8.0;
    /** Set once a native library without nSetSpringModes is met (an older ysm.dll beside this jar). */
    private static volatile boolean springModesMissing;

    private static final ByteBuffer EMPTY_NATIVE_BUFFER = ByteBuffer.allocateDirect(0);
    private static final ShortBuffer EMPTY_SHORT_BUFFER =
        ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asShortBuffer();
    private static final ThreadLocal<int[]> BONE_INFO_BUFFER = ThreadLocal.withInitial(() -> new int[0]);
    private static final ThreadLocal<int[]> CUBE_INFO_BUFFER = ThreadLocal.withInitial(() -> new int[0]);

    private final ExposedShortArrayList locatorBoneIndices = new ExposedShortArrayList(0);
    /** Poses, render indices, render bone and locator counts, total and translucent counts, outline count. */
    private final long[] extractOutput = new long[5];

    private final float[] physicsFrame = new float[PHYSICS_FLOAT_COUNT];
    private final Matrix4d observedWorld = new Matrix4d();
    private final Vector3d observedDown = new Vector3d();
    private final Matrix4d steppedWorld = new Matrix4d();
    private final Matrix4d motion = new Matrix4d();
    private final Vector3d gravity = new Vector3d();
    private int observedSpace = -1;
    private int steppedSpace = -1;
    private long lastStepNanos;
    /** When the latest draw was observed, and when the draw the last step reached was. */
    private long observedNanos;
    private long steppedNanos;

    private BonePoseView bonePoses;
    private ShortBuffer renderBoneIndices;
    private int locatorCount;
    private int totalVertexCount;
    private int translucentVertexCount;
    private int outlineVertexCount;

    private boolean valid;

    private NativeModelState(State state) {
        super(state);
    }

    /**
     * @return a new invalid state, owned and eventually closed by the caller
     * @throws RuntimeException if native state creation fails
     */
    public static NativeModelState create() {
        Function<State, NativeModelState> factory = NativeModelState::new;
        var ptr = nCreate();
        if (ptr == 0) {
            throw new RuntimeException("Failed to create ModelState");
        }
        return adopt(ptr, factory);
    }

    /**
     * <p>Requires: bakedModel is open. boneAttributes contains one complete
     * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT record per baked bone, in the baked bone order.
     * The caller excludes input mutation and release during this synchronous call.
     *
     * <p>Failure: false means native extraction failed. Exceptions during Java result construction
     * propagate. Both outcomes revoke the previous publication and leave this state invalid.
     *
     * @return true after publishing all views and counts, including a valid empty result
     */
    @Borrowed
    public boolean extract(NativeBakedModel bakedModel, float[] boneAttributes) {
        return extract(bakedModel, boneAttributes, false, 0);
    }

    /**
     * Records where the latest draw placed the model, and when. The next {@link #extract} steps spring bones by the
     * motion between the draws observed before consecutive extractions, over the time between those same two draws,
     * so physics follows host motion one frame late but with a matching duration: the step time used to be the time
     * between the extractions (one frame later than the motion), and uneven frame times then made the body velocity
     * estimate, and with it the hair and cloth, jerk.
     *
     * @param modelToWorld model space to a space whose orientation and units are stable across frames
     * @param worldDown down in that space
     * @param space identifies the space; a change restarts the springs
     */
    public void observeDraw(Matrix4dc modelToWorld, Vector3dc worldDown, int space) {
        observedWorld.set(modelToWorld);
        observedDown.set(worldDown);
        observedSpace = space;
        observedNanos = System.nanoTime();
    }

    private float[] nextPhysicsFrame() {
        var now = System.nanoTime();
        var reset = observedSpace < 0 || steppedSpace != observedSpace || lastStepNanos == 0
            || now - lastStepNanos > MAX_STEP_GAP_NANOS;
        motion.identity();
        if (!reset) {
            observedWorld.invert(motion).mul(steppedWorld);
            var moved =
                Math.sqrt(motion.m30() * motion.m30() + motion.m31() * motion.m31() + motion.m32() * motion.m32());
            reset = !motion.isFinite() || moved > MAX_STEP_MOTION;
            if (reset) {
                motion.identity();
            }
        }
        gravity.set(0, -1, 0);
        if (observedSpace >= 0) {
            observedWorld.invert(new Matrix4d()).transformDirection(observedDown, gravity);
            if (!gravity.isFinite() || gravity.lengthSquared() == 0) {
                gravity.set(0, -1, 0);
            }
            gravity.normalize();
        }
        physicsFrame[0] = reset || steppedNanos == 0 ? 0f : (float)(Math.max(observedNanos - steppedNanos, 0L) / 1e9);
        physicsFrame[1] = reset ? 1f : 0f;
        physicsFrame[2] = (float)gravity.x;
        physicsFrame[3] = (float)gravity.y;
        physicsFrame[4] = (float)gravity.z;
        for (var column = 0; column < 4; column++) {
            for (var row = 0; row < 4; row++) {
                physicsFrame[5 + column * 4 + row] = (float)motion.get(column, row);
            }
        }
        steppedWorld.set(observedWorld);
        steppedSpace = observedSpace;
        lastStepNanos = now;
        steppedNanos = observedNanos;
        return physicsFrame;
    }

    /**
     * Uses the extract contract with a local root and the same complete attribute array.
     * External ancestors do not contribute transforms or visibility.
     * Only poses referenced by the resulting render or locator indices may be consumed.
     *
     * @param rootIndex a bone index in the baked order, in [0, baked bone count)
     * @return true after complete local publication; native rejection revokes the result and returns false
     */
    @Borrowed
    public boolean extractSubtree(NativeBakedModel bakedModel, float[] boneAttributes, int rootIndex) {
        return extract(bakedModel, boneAttributes, true, rootIndex);
    }

    private boolean extract(NativeBakedModel bakedModel, float[] boneAttributes, boolean local, int rootIndex) {
        clearResult();
        var statePtr = get();
        try {
            var expectedBoneCount = boneAttributes.length / AnimatedGeoModel.BONE_ATTRIBUTE_COUNT;
            if (locatorBoneIndices.size() < expectedBoneCount) {
                locatorBoneIndices.size(expectedBoneCount);
            }
            var success = local
                ? nExtractSubtree(statePtr, bakedModel.get(), boneAttributes, locatorBoneIndices.getUnderlyingArray(),
                    extractOutput, rootIndex)
                : nExtract(statePtr, bakedModel.get(), boneAttributes, nextPhysicsFrame(),
                    locatorBoneIndices.getUnderlyingArray(), extractOutput);
            if (!success) {
                return false;
            }

            var renderBoneCount = Math.toIntExact(extractOutput[2] & 0xFFFFFFFFL);
            var nextLocatorCount = Math.toIntExact(extractOutput[2] >>> 32);
            var nextVertexCount = Math.toIntExact(extractOutput[3] & 0xFFFFFFFFL);
            var nextTranslucentCount = Math.toIntExact(extractOutput[3] >>> 32);
            var nextOutlineCount = Math.toIntExact(extractOutput[4]);
            if (renderBoneCount > expectedBoneCount || nextLocatorCount > expectedBoneCount
                || nextTranslucentCount > nextVertexCount) {
                throw new IllegalStateException("Invalid native ModelState counts");
            }
            var nextPoses = new BonePoseView(extractOutput[0], expectedBoneCount);
            var nextIndices = borrowShortBuffer(extractOutput[1], renderBoneCount);
            bonePoses = nextPoses;
            renderBoneIndices = nextIndices;
            locatorCount = nextLocatorCount;
            totalVertexCount = nextVertexCount;
            translucentVertexCount = nextTranslucentCount;
            outlineVertexCount = nextOutlineCount;
            valid = true;
            return true;
        } finally {
            if (!valid) {
                clearResult();
                nInvalidate(statePtr);
            }
            Reference.reachabilityFence(this);
            Reference.reachabilityFence(bakedModel);
        }
    }

    /**
     * Sets every spring chain's mode for the following extractions (spring LOD, client.lod.MeshLodRuntime): 0 FULL
     * (simulated), 1 HOLD (keeps the shape it had when it entered, carried by its bones, no integration). Chains past
     * the array are FULL. Mode changes never move a tail.
     *
     * @return false when the native library predates spring modes
     */
    public boolean setSpringModes(byte[] modes) {
        if (springModesMissing) {
            return false;
        }
        try {
            nSetSpringModes(get(), modes);
            return true;
        } catch (UnsatisfiedLinkError e) {
            springModesMissing = true;
            return false;
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** Revokes both publications and all outstanding views; native storage capacity may remain allocated. */
    public void invalidate() {
        clearResult();
        try {
            nInvalidate(get());
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    private void clearResult() {
        bonePoses = null;
        renderBoneIndices = null;
        locatorCount = 0;
        totalVertexCount = 0;
        translucentVertexCount = 0;
        outlineVertexCount = 0;
        valid = false;
    }

    /** @return the borrowed pose view, or null when no publication is valid */
    public BonePoseView getBonePoses() {
        return bonePoses;
    }

    /**
     * @return borrowed direct indices, or null when invalid; shorts encode unsigned baked bone indices
     */
    public ShortBuffer getRenderBoneIndices() {
        return renderBoneIndices;
    }

    /** @return borrowed unsigned bone indices; callers must not mutate this list */
    public ShortList getLocatorBoneIndices() {
        return locatorBoneIndices.subList(0, locatorCount);
    }

    public int getTotalVertexCount() {
        return totalVertexCount;
    }

    public int getTranslucentVertexCount() {
        return translucentVertexCount;
    }

    /**
     * @return vertices of the separate outline pass ({@link NativeRenderer#renderOutline}); not part of
     *     {@link #getTotalVertexCount()}, and zero for models without outline triangles
     */
    public int getOutlineVertexCount() {
        return outlineVertexCount;
    }

    public boolean isValid() {
        return valid;
    }

    /**
     * <p>Requires: this state has a valid publication.
     *
     * <p>Ownership: borrows a thread-local carrier. The next call to this method on the same
     * thread, even for another state, ends the view's lifetime. State invalidation also ends its use.
     *
     * @return ranges in vertex units relative to this publication's final vertex buffer
     * @throws RuntimeException if native range extraction fails
     */
    public BoneInfoView calculateRenderBoneInfo() {
        var requiredSize = checkedArraySize(renderBoneIndices.capacity(), BONE_INFO_INT_COUNT, 0);
        var data = getIntBuffer(BONE_INFO_BUFFER, requiredSize);
        final boolean success;
        try {
            success = nCalculateRenderBoneInfo(get(), data);
        } finally {
            Reference.reachabilityFence(this);
        }
        if (!success) {
            throw new RuntimeException("Failed to get ModelState bone info");
        }
        return new BoneInfoView(data, renderBoneIndices.capacity());
    }

    /**
     * <p>Requires: this state has a valid publication.
     *
     * <p>Ownership: borrows a separate thread-local carrier until this method is called again
     * on the same thread, even for another state. State invalidation also ends its use.
     *
     * @return cube ranges in vertex units relative to this publication's final vertex buffer
     * @throws RuntimeException if native range extraction or result decoding fails
     */
    public CubeInfoView calculateRenderCubeInfo() {
        var headerIntCount = checkedArraySize(renderBoneIndices.capacity(), CUBE_INFO_HEADER_INT_COUNT, 0) + 1;
        var data = CUBE_INFO_BUFFER.get();
        final int[] result;
        try {
            result = nCalculateRenderCubeInfo(get(), data);
        } finally {
            Reference.reachabilityFence(this);
        }
        if (result == null) {
            throw new RuntimeException("Failed to get ModelState cube info");
        }
        if (data != result) {
            CUBE_INFO_BUFFER.set(result);
        }

        return new CubeInfoView(result, renderBoneIndices.capacity(), result[0], headerIntCount);
    }

    private static int checkedArraySize(int count, int stride, int header) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative element count");
        }
        return Math.addExact(header, Math.multiplyExact(count, stride));
    }

    private static int[] getIntBuffer(ThreadLocal<int[]> buffer, int requiredSize) {
        var data = buffer.get();
        if (data.length < requiredSize) {
            data = new int[requiredSize];
            buffer.set(data);
        }
        return data;
    }

    private static void checkIndex(int index, int count, String name) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(name + " index " + index + " out of bounds for " + count);
        }
    }

    private static NativeBuffer borrowNativeBuffer(long ptr, int size) {
        if (size == 0) {
            return NativeBuffer.borrow(EMPTY_NATIVE_BUFFER);
        }
        if (ptr == 0) {
            throw new IllegalStateException("Native buffer pointer is null");
        }
        return NativeBuffer.borrow(MemoryUtil.memByteBuffer(ptr, size));
    }

    private static ShortBuffer borrowShortBuffer(long ptr, int size) {
        if (size == 0) {
            return EMPTY_SHORT_BUFFER;
        }
        if (ptr == 0) {
            throw new IllegalStateException("Native short buffer pointer is null");
        }
        return MemoryUtil.memShortBuffer(ptr, size);
    }

    /** Revokes all views before releasing this state's handle; consumers must already have finished. */
    @Override
    public void close() {
        clearResult();
        super.close();
    }

    private static native long nCreate();

    private static native void nInvalidate(long state);

    private static native boolean nExtract(long state, long bakedModel, float[] boneAttributes, float[] physics,
        short[] locatorBoneIndices, long[] output);

    private static native boolean nExtractSubtree(long state, long bakedModel, float[] boneAttributes,
        short[] locatorBoneIndices, long[] output, int rootIndex);

    private static native boolean nCalculateRenderBoneInfo(long state, int[] dst);

    private static native int[] nCalculateRenderCubeInfo(long state, int[] dst);

    private static native void nSetSpringModes(long state, byte[] modes);

    /** Vertex offset and reserved vertex capacity; these are not byte counts or committed consumer counts. */
    public record VertexRange(long vertexOffset, long expectedVertexCount) {
        public static VertexRange unpack(int[] data, int offset) {
            return new VertexRange(Integer.toUnsignedLong(data[offset]), Integer.toUnsignedLong(data[offset + 1]));
        }
    }

    public static final class BoneInfoView {
        private final int[] data;
        private final int boneCount;

        private BoneInfoView(int[] data, int boneCount) {
            this.data = data;
            this.boneCount = boneCount;
        }

        public int boneCount() {
            return boneCount;
        }

        public VertexRange getCutout(int boneIndex) {
            return get(boneIndex, 0);
        }

        public VertexRange getCutoutNoCulling(int boneIndex) {
            return get(boneIndex, 2);
        }

        public VertexRange getTranslucent(int boneIndex) {
            return get(boneIndex, 4);
        }

        public VertexRange getTranslucentCulling(int boneIndex) {
            return get(boneIndex, 6);
        }

        private VertexRange get(int boneIndex, int partitionOffset) {
            checkIndex(boneIndex, boneCount, "bone");
            return VertexRange.unpack(data, boneIndex * BONE_INFO_INT_COUNT + partitionOffset);
        }
    }

    public static final class CubeInfoView {
        private final int[] data;
        private final int boneCount;
        private final int cubeCount;
        private final int headerIntCount;

        private CubeInfoView(int[] data, int boneCount, int cubeCount, int headerIntCount) {
            this.data = data;
            this.boneCount = boneCount;
            this.cubeCount = cubeCount;
            this.headerIntCount = headerIntCount;

            var parsedCubeCount = 0;
            for (var boneIndex = 0; boneIndex < boneCount; ++boneIndex) {
                parsedCubeCount = Math.addExact(parsedCubeCount, getBoneCubeCountUnchecked(boneIndex));
            }
            if (parsedCubeCount != cubeCount) {
                throw new IllegalStateException("Invalid native cube info");
            }
        }

        public int boneCount() {
            return boneCount;
        }

        public int cubeCount() {
            return cubeCount;
        }

        public int getCutoutCubeCount(int boneIndex) {
            return getPartitionCubeCount(boneIndex, 0);
        }

        public int getCutoutNoCullingCubeCount(int boneIndex) {
            return getPartitionCubeCount(boneIndex, 1);
        }

        public int getTranslucentCubeCount(int boneIndex) {
            return getPartitionCubeCount(boneIndex, 2);
        }

        public int getTranslucentCullingCubeCount(int boneIndex) {
            return getPartitionCubeCount(boneIndex, 3);
        }

        public VertexRange getCutout(int boneIndex, int cubeIndex) {
            return get(boneIndex, 0, cubeIndex);
        }

        public VertexRange getCutoutNoCulling(int boneIndex, int cubeIndex) {
            return get(boneIndex, 1, cubeIndex);
        }

        public VertexRange getTranslucent(int boneIndex, int cubeIndex) {
            return get(boneIndex, 2, cubeIndex);
        }

        public VertexRange getTranslucentCulling(int boneIndex, int cubeIndex) {
            return get(boneIndex, 3, cubeIndex);
        }

        private VertexRange get(int boneIndex, int partition, int cubeIndex) {
            checkIndex(boneIndex, boneCount, "bone");
            var partitionCubeCount = getPartitionCubeCountInternal(boneIndex, partition);
            checkIndex(cubeIndex, partitionCubeCount, "cube");

            var packedCubeIndex = 0;
            for (var index = 0; index < boneIndex; ++index) {
                packedCubeIndex = Math.addExact(packedCubeIndex, getBoneCubeCountUnchecked(index));
            }
            for (var index = 0; index < partition; ++index) {
                packedCubeIndex = Math.addExact(packedCubeIndex, getPartitionCubeCountInternal(boneIndex, index));
            }
            packedCubeIndex = Math.addExact(packedCubeIndex, cubeIndex);
            return VertexRange.unpack(data, headerIntCount + packedCubeIndex * CUBE_INFO_INT_COUNT);
        }

        private int getPartitionCubeCount(int boneIndex, int partition) {
            checkIndex(boneIndex, boneCount, "bone");
            return getPartitionCubeCountInternal(boneIndex, partition);
        }

        private int getPartitionCubeCountInternal(int boneIndex, int partition) {
            var headerOffset = boneIndex * CUBE_INFO_HEADER_INT_COUNT + 1;
            var packedCount = data[headerOffset + partition / 2];
            return partition % 2 == 0 ? packedCount & 0xffff : packedCount >>> 16;
        }

        private int getBoneCubeCountUnchecked(int boneIndex) {
            return Math.addExact(
                Math.addExact(getPartitionCubeCountInternal(boneIndex, 0), getPartitionCubeCountInternal(boneIndex, 1)),
                Math.addExact(getPartitionCubeCountInternal(boneIndex, 2),
                    getPartitionCubeCountInternal(boneIndex, 3)));
        }
    }
}
