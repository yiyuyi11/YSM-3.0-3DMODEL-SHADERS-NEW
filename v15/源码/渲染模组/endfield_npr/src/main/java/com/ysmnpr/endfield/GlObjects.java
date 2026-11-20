package com.ysmnpr.endfield;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.ByteBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Creation and editing of the mod's own GL objects: framebuffers, textures, renderbuffers, buffers and vertex arrays.
 * With GL 4.5 / ARB_direct_state_access ({@code gpuDirectStateAccess}) they are created and edited by name
 * (glCreate*, glTextureStorage*, glNamedFramebuffer*, glNamedBuffer*, glVertexArray*): nothing is bound to be edited,
 * so no binding Minecraft or Oculus tracks is disturbed and no re-binding follows. Without it the classic path binds
 * to edit: textures through GlStateManager (its unit cache stays right) and unbound afterwards, buffers through
 * GL_COPY_WRITE_BUFFER (tracked by nobody), framebuffers and vertex arrays by the caller, who binds them anyway to draw.
 * Objects owned by others (the pack's and Minecraft's textures) are only attached to the mod's framebuffers, never
 * edited. Textures are immutable under DSA: a size change makes a new one (callers re-attach by name). Render thread
 * only.
 */
final class GlObjects {
    @Nullable
    private static GLCapabilities caps;
    private static boolean available;

    private GlObjects() {}

    /** Whether the DSA path runs (the extension is there and the key is on). */
    static boolean dsa() {
        var c = GL.getCapabilities();
        if (c != caps) {
            caps = c;
            available = c.OpenGL45 || c.GL_ARB_direct_state_access;
        }
        return available && NprConfig.bool("gpuDirectStateAccess");
    }

    // ---- textures -------------------------------------------------------------------------------------------------

    /** A 2D texture of {@code format} (R8, RG8, R32F or DEPTH_COMPONENT32F), one level, nearest filtering, clamped. */
    static int texture(int format, int w, int h) {
        if (dsa()) {
            var t = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(t, 1, format, w, h);
            GL45.glTextureParameteri(t, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL45.glTextureParameteri(t, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL45.glTextureParameteri(t, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL45.glTextureParameteri(t, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            return t;
        }
        // bound on the active unit and put back exactly as found, so GlStateManager's cached binding stays true
        var t = GlStateManager._genTexture();
        var previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        var depth = format == GL30.GL_DEPTH_COMPONENT32F;
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format, w, h, 0, depth ? GL11.GL_DEPTH_COMPONENT
            : format == GL30.GL_RG8 ? GL30.GL_RG : GL11.GL_RED,
            format == GL30.GL_R8 || format == GL30.GL_RG8 ? GL11.GL_UNSIGNED_BYTE : GL11.GL_FLOAT, (ByteBuffer)null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        return t;
    }

    /** A multisampled 2D texture (fixed sample locations, so it mixes with any other multisample attachment). */
    static int textureMultisample(int samples, int format, int w, int h) {
        if (dsa()) {
            var t = GL45.glCreateTextures(GL32.GL_TEXTURE_2D_MULTISAMPLE);
            GL45.glTextureStorage2DMultisample(t, samples, format, w, h, true);
            return t;
        }
        var t = GlStateManager._genTexture();
        GL11.glBindTexture(GL32.GL_TEXTURE_2D_MULTISAMPLE, t); // a target GlStateManager does not track
        GL32.glTexImage2DMultisample(GL32.GL_TEXTURE_2D_MULTISAMPLE, samples, format, w, h, true);
        GL11.glBindTexture(GL32.GL_TEXTURE_2D_MULTISAMPLE, 0);
        return t;
    }

    static void deleteTexture(int t) {
        if (t != 0) {
            GlStateManager._deleteTexture(t); // also clears GlStateManager's cached binding of it
        }
    }

    /** Width and height of level 0 of a 2D texture owned by anyone, into {@code out}; binds nothing under DSA. */
    static void size(int texture, int[] out) {
        if (dsa()) {
            out[0] = GL45.glGetTextureLevelParameteri(texture, 0, GL11.GL_TEXTURE_WIDTH);
            out[1] = GL45.glGetTextureLevelParameteri(texture, 0, GL11.GL_TEXTURE_HEIGHT);
            return;
        }
        var active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        var previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        out[0] = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        out[1] = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        GlStateManager._activeTexture(active);
    }

    /** Internal format of level 0 of a 2D texture. */
    static int internalFormat(int texture) {
        if (dsa()) {
            return GL45.glGetTextureLevelParameteri(texture, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        }
        var active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        var previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        var format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        GlStateManager._activeTexture(active);
        return format;
    }

    /**
     * One texel (x, y) of level 0 of a 2D texture owned by anyone, as RGBA8 packed little-endian (r in the low byte), or
     * -1 when it cannot be read. glGetTextureSubImage (GL 4.5) or a read through a temporary framebuffer; the pack
     * state (pixel pack buffer, row length, skips, alignment) and the read framebuffer are put back. Waits for the GPU:
     * diagnostics only (CharacterCapture.logState).
     */
    static int texel(int texture, int x, int y) {
        var buffer = org.lwjgl.system.MemoryUtil.memAlloc(4);
        var pbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        var rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        var skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        var skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        var alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        var result = -1;
        try {
            if (GL.getCapabilities().glGetTextureSubImage != 0L) {
                GL45.glGetTextureSubImage(texture, 0, x, y, 0, 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
                result = buffer.getInt(0);
            } else {
                var read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
                var fbo = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
                GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                    texture, 0);
                if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE) {
                    GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
                    result = buffer.getInt(0);
                }
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
                GL30.glDeleteFramebuffers(fbo);
            }
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
            org.lwjgl.system.MemoryUtil.memFree(buffer);
        }
        return result;
    }

    // ---- renderbuffers and framebuffers -------------------------------------------------------------------------

    static int renderbuffer(int format, int w, int h) {
        if (dsa()) {
            var r = GL45.glCreateRenderbuffers();
            GL45.glNamedRenderbufferStorage(r, format, w, h);
            return r;
        }
        var r = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, r);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, format, w, h);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
        return r;
    }

    static void deleteRenderbuffer(int r) {
        if (r != 0) {
            GL30.glDeleteRenderbuffers(r);
        }
    }

    static int framebuffer() {
        return dsa() ? GL45.glCreateFramebuffers() : GlStateManager.glGenFramebuffers();
    }

    /**
     * Attaches level 0 of {@code texture} ({@code target} = TEXTURE_2D or TEXTURE_2D_MULTISAMPLE; 0 detaches) at
     * {@code attachment} of {@code fbo}. Without DSA the framebuffer must be bound to GL_FRAMEBUFFER.
     */
    static void attach(int fbo, int attachment, int target, int texture) {
        if (dsa()) {
            GL45.glNamedFramebufferTexture(fbo, attachment, texture, 0);
        } else {
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, attachment, target, texture, 0);
        }
    }

    /** Attaches a renderbuffer; without DSA the framebuffer must be bound to GL_FRAMEBUFFER. */
    static void attachRenderbuffer(int fbo, int attachment, int renderbuffer) {
        if (dsa()) {
            GL45.glNamedFramebufferRenderbuffer(fbo, attachment, GL30.GL_RENDERBUFFER, renderbuffer);
        } else {
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, attachment, GL30.GL_RENDERBUFFER, renderbuffer);
        }
    }

    /** Draw buffers 0..n-1 = COLOR_ATTACHMENT0..n-1, or none ({@code n} = 0, read buffer none too). Bound without DSA. */
    static void drawBuffers(int fbo, int n) {
        if (n == 0) {
            if (dsa()) {
                GL45.glNamedFramebufferDrawBuffer(fbo, GL11.GL_NONE);
                GL45.glNamedFramebufferReadBuffer(fbo, GL11.GL_NONE);
            } else {
                GL11.glDrawBuffer(GL11.GL_NONE);
                GL11.glReadBuffer(GL11.GL_NONE);
            }
            return;
        }
        var buffers = n == 1 ? ONE : TWO;
        if (dsa()) {
            GL45.glNamedFramebufferDrawBuffers(fbo, buffers);
        } else {
            GL20.glDrawBuffers(buffers);
        }
    }

    private static final int[] ONE = {GL30.GL_COLOR_ATTACHMENT0};
    private static final int[] TWO = {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1};

    /** Completeness ("" when complete, else the status in hex); bound to GL_FRAMEBUFFER without DSA. */
    static String incomplete(int fbo) {
        var status = dsa() ? GL45.glCheckNamedFramebufferStatus(fbo, GL30.GL_FRAMEBUFFER)
            : GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        return status == GL30.GL_FRAMEBUFFER_COMPLETE ? "" : "0x" + Integer.toHexString(status);
    }

    // ---- buffers --------------------------------------------------------------------------------------------------

    static int buffer() {
        return dsa() ? GL45.glCreateBuffers() : GlStateManager._glGenBuffers();
    }

    /** glBufferData of {@code size} bytes from {@code address} (0 = undefined contents: orphaning). */
    static void bufferData(int buffer, long size, long address, int usage) {
        if (dsa()) {
            GL45.nglNamedBufferData(buffer, size, address, usage);
            return;
        }
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
        GL15.nglBufferData(GL31.GL_COPY_WRITE_BUFFER, size, address, usage);
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    static void bufferSubData(int buffer, long offset, long size, long address) {
        if (dsa()) {
            GL45.nglNamedBufferSubData(buffer, offset, size, address);
            return;
        }
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
        GL15.nglBufferSubData(GL31.GL_COPY_WRITE_BUFFER, offset, size, address);
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    /** Immutable storage mapped once (ARB_buffer_storage); returns the mapping's address, 0 on failure. */
    static long storageMapped(int buffer, long size, int storageFlags, int accessFlags) {
        if (dsa()) {
            GL45.glNamedBufferStorage(buffer, size, storageFlags);
            return GL45.nglMapNamedBufferRange(buffer, 0L, size, accessFlags);
        }
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
        GL44.glBufferStorage(GL31.GL_COPY_WRITE_BUFFER, size, storageFlags);
        var mapped = GL30.nglMapBufferRange(GL31.GL_COPY_WRITE_BUFFER, 0L, size, accessFlags);
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        return mapped;
    }

    static void flushMapped(int buffer, long offset, long size) {
        if (dsa()) {
            GL45.glFlushMappedNamedBufferRange(buffer, offset, size);
            return;
        }
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
        GL30.glFlushMappedBufferRange(GL31.GL_COPY_WRITE_BUFFER, offset, size);
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    static void unmap(int buffer) {
        if (dsa()) {
            GL45.glUnmapNamedBuffer(buffer);
            return;
        }
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
        GL15.glUnmapBuffer(GL31.GL_COPY_WRITE_BUFFER);
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    // ---- vertex arrays --------------------------------------------------------------------------------------------

    static int vertexArray() {
        return dsa() ? GL45.glCreateVertexArrays() : GL30.glGenVertexArrays();
    }
}
