package com.ysmnpr.endfield;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * The game's four shared character rain textures as one GL_TEXTURE_2D_ARRAY on npr_character's {@code RainTex}
 * sampler (npr_rain.glsl), layers in the order of HGRainPresettingAsset and the shaders' declarations:
 * 0 T_actor_common_rain_01_M (_CharacterRainEffectTex), 1 _02 (_CharacterRainStreakTex), 2 _03
 * (_CharacterRainFaceDripTex), 3 _04 (_CharacterRainFaceDropletTex). Linear data (RGBA8, not sRGB), repeat, mipmapped.
 * Decoded on a worker the first time rain is drawn, uploaded on the render thread when ready.
 */
final class RainTextures {
    private static final String[] LAYERS = {"t_actor_common_rain_01_m", "t_actor_common_rain_02_m",
        "t_actor_common_rain_03_m", "t_actor_common_rain_04_m"};
    /**
     * RainTex's texture unit, not a json sampler (those take units 0-13 in json order; EyeBrow.MS_UNIT is 14): the last
     * of the 16 units GL 3.2 guarantees.
     */
    static final int UNIT = 15;
    /** Uniform location of RainTex per program object (a resource reload builds new ShaderInstances). */
    private static final Map<ShaderInstance, Integer> LOCATIONS = new java.util.WeakHashMap<>();
    private static CompletableFuture<NativeImage[]> loading;
    private static int array;
    private static boolean failed;

    private RainTextures() {}

    /** True once the array is on the GPU (render thread); starts loading on the first call. */
    static boolean ready() {
        RenderSystem.assertOnRenderThread();
        if (array != 0 || failed) {
            return array != 0;
        }
        if (loading == null) {
            var resources = Minecraft.getInstance().getResourceManager();
            loading = CompletableFuture.supplyAsync(() -> {
                var images = new NativeImage[LAYERS.length];
                try {
                    for (var i = 0; i < LAYERS.length; i++) {
                        var location = new ResourceLocation(EndfieldNpr.MOD_ID, "textures/rain/" + LAYERS[i] + ".png");
                        try (var in = resources.getResourceOrThrow(location).open()) {
                            images[i] = ModelPackage.image(in.readAllBytes());
                        }
                    }
                    return images;
                } catch (IOException | RuntimeException failure) {
                    for (var image : images) {
                        if (image != null) {
                            image.close();
                        }
                    }
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }, Util.backgroundExecutor());
        }
        if (!loading.isDone()) {
            return false;
        }
        try {
            array = upload(loading.join());
        } catch (RuntimeException failure) {
            failed = true;
            EndfieldNpr.LOG.error("Endfield NPR: rain textures could not be loaded; rain/wet shading stays off",
                failure);
        }
        loading = null;
        return array != 0;
    }

    private static int upload(NativeImage[] images) {
        var w = images[0].getWidth();
        var h = images[0].getHeight();
        var previous = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        var id = GL11.glGenTextures();
        var pixels = MemoryUtil.memAlloc(w * h * 4);
        try {
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, id);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8, w, h, images.length, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null);
            for (var layer = 0; layer < images.length; layer++) {
                var image = images[layer];
                if (image.getWidth() != w || image.getHeight() != h) {
                    throw new IllegalStateException("rain texture " + LAYERS[layer] + " is not " + w + "x" + h);
                }
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        pixels.putInt((y * w + x) * 4, image.getPixelRGBA(x, y)); // ABGR int = RGBA bytes
                    }
                }
                GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, w, h, 1, GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, pixels);
            }
            GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);
            return id;
        } catch (RuntimeException failure) {
            GL11.glDeleteTextures(id);
            throw failure;
        } finally {
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previous);
            MemoryUtil.memFree(pixels);
            for (var image : images) {
                image.close();
            }
        }
    }

    /**
     * After {@code shader.apply()} (program bound): RainTex points at {@link #UNIT} (once per program; left at its
     * default 0 it would share Sampler0's unit with another sampler type, an error at every draw) and the array, or
     * NprMaps' empty array while it is not loaded, is bound there.
     */
    static void afterApply(ShaderInstance shader) {
        var program = shader.getId();
        int loc = LOCATIONS.computeIfAbsent(shader, s -> {
            var l = GL20.glGetUniformLocation(program, "RainTex");
            if (l >= 0) {
                GL20.glUniform1i(l, UNIT);
            }
            return l;
        });
        if (loc < 0) {
            return;
        }
        var active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, array != 0 ? array : NprMaps.empty());
        GlStateManager._activeTexture(active);
    }
}
