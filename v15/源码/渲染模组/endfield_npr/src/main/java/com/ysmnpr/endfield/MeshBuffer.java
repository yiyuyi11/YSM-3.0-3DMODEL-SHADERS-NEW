package com.ysmnpr.endfield;

import com.elfmcys.ysm.accessor.VertexBufferAccessor;
import com.elfmcys.ysm.buffer.BorrowedNativeBuffer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import net.minecraft.util.Mth;

/**
 * The consumer YSM's {@code NativeRenderer.render} writes one character into. It implements YSM's
 * {@link VertexBufferAccessor} with the NEW_ENTITY layout, so YSM takes its <em>direct</em> path:
 * {@code probeVertexConsumer} sees the accessor (VertexFormatType.VANILLA), {@code ysm$reserve} hands the native
 * skinning a borrowed slice of {@link #target}, the native library writes the finished vertices straight into it and
 * {@code ysm$advance} commits them. The target is the character's copy inside a persistently mapped
 * {@link GpuArena} page (zero copy) or the arena's staging buffer (one glBufferSubData afterwards). The
 * {@link VertexConsumer} methods only serve YSM's fallback path (UseCompatibilityRenderer=true) and write the same
 * bytes. One instance serves every capture (writes are synchronous). Render thread only.
 */
final class MeshBuffer implements VertexConsumer, VertexBufferAccessor {
    static final VertexFormat FORMAT = DefaultVertexFormat.NEW_ENTITY;
    private static final int STRIDE = GpuArena.STRIDE;

    private ByteBuffer target;
    private int capacity; // vertices
    int count;
    /** A write did not fit into the target; the capture must be discarded. */
    boolean overflow;

    // generic VertexConsumer state (fallback path only)
    private float x, y, z, u, v, nx, ny, nz;
    private int color = -1, overlay, light;
    private boolean fixedColor;
    private int fixedColorValue;

    /** Starts a capture into {@code buffer} (position 0, at least {@code vertices * STRIDE} bytes). */
    void begin(ByteBuffer buffer, int vertices) {
        target = buffer;
        capacity = vertices;
        count = 0;
        overflow = false;
    }

    // ---- YSM direct output -------------------------------------------------------------------------------------

    @Override
    public boolean ysm$ok() {
        return target != null;
    }

    @Override
    public VertexFormat ysm$vertexFormat() {
        return FORMAT;
    }

    @Override
    public BorrowedNativeBuffer ysm$reserve(int maxVertexCount) {
        if (maxVertexCount < 0) {
            throw new IllegalArgumentException("Negative vertex count");
        }
        if (count + maxVertexCount > capacity) {
            // the cell was sized from the published vertex count, so this is a contract change in YSM: write
            // nowhere useful and drop the capture (YSM then draws the character itself next frame)
            overflow = true;
            count = 0;
            return BorrowedNativeBuffer.at(this, target, 0, Math.min(maxVertexCount, capacity) * STRIDE);
        }
        return BorrowedNativeBuffer.at(this, target, count * STRIDE, maxVertexCount * STRIDE);
    }

    @Override
    public void ysm$advance(int actualVertexCount) {
        if (!overflow) {
            count += actualVertexCount;
        }
    }

    // ---- fallback output (YSM's FallbackVertexWriter uses the bulk vertex call) -------------------------------

    @Override
    public void vertex(float px, float py, float pz, float r, float g, float b, float a, float pu, float pv, int ov,
        int lt, float qx, float qy, float qz) {
        put(px, py, pz, (int)(a * 255f) << 24 | (int)(b * 255f) << 16 | (int)(g * 255f) << 8 | (int)(r * 255f), pu,
            pv, ov, lt, qx, qy, qz);
    }

    private void put(float px, float py, float pz, int abgr, float pu, float pv, int ov, int lt, float qx, float qy,
        float qz) {
        if (overflow || count >= capacity) {
            overflow = true;
            return;
        }
        var data = target;
        var o = count * STRIDE;
        data.putFloat(o, px).putFloat(o + 4, py).putFloat(o + 8, pz).putInt(o + 12, abgr);
        data.putFloat(o + 16, pu).putFloat(o + 20, pv).putInt(o + 24, ov).putInt(o + 28, lt);
        data.put(o + 32, normalByte(qx)).put(o + 33, normalByte(qy)).put(o + 34, normalByte(qz)).put(o + 35, (byte)0);
        count++;
    }

    private static byte normalByte(float n) { // as BufferVertexConsumer.normalIntValue
        return (byte)((int)(Mth.clamp(n, -1f, 1f) * 127f) & 0xFF);
    }

    @Override
    public VertexConsumer vertex(double px, double py, double pz) {
        x = (float)px;
        y = (float)py;
        z = (float)pz;
        return this;
    }

    @Override
    public VertexConsumer color(int r, int g, int b, int a) {
        color = a << 24 | b << 16 | g << 8 | r;
        return this;
    }

    @Override
    public VertexConsumer uv(float pu, float pv) {
        u = pu;
        v = pv;
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int pu, int pv) {
        overlay = pv << 16 | pu & 0xFFFF;
        return this;
    }

    @Override
    public VertexConsumer uv2(int pu, int pv) {
        light = pv << 16 | pu & 0xFFFF;
        return this;
    }

    @Override
    public VertexConsumer normal(float qx, float qy, float qz) {
        nx = qx;
        ny = qy;
        nz = qz;
        return this;
    }

    @Override
    public void endVertex() {
        put(x, y, z, fixedColor ? fixedColorValue : color, u, v, overlay, light, nx, ny, nz);
    }

    @Override
    public void defaultColor(int r, int g, int b, int a) {
        fixedColor = true;
        fixedColorValue = a << 24 | b << 16 | g << 8 | r;
    }

    @Override
    public void unsetDefaultColor() {
        fixedColor = false;
    }
}
