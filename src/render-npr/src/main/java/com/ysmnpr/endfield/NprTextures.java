package com.ysmnpr.endfield;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Map;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Lookup-texture helpers of the NPR program. A model's ramps come from its package (ModelPackage); a ramp the package
 * lacks, and every ramp of a model without one, is a procedural approximation of the profiles measured in the game's
 * T_actor_common_*_RD/RS textures (a = lit mask, rgb = terminator colour, weighted in the shader by chroma).
 *
 * <p>Ramp atlas rows (256x8): 0 cloth RD, 1 skin RD, 2 face RD, 3 hair RD, 4 eye RD, 5 cloth RS, 6 hair RS
 * ({@link ModelPackageData#RAMPS}).
 */
public final class NprTextures {
    static final int ROWS = 8;

    private NprTextures() {}

    // {u, r, g, b, a} control points, piecewise linear.
    private static final float[][] SKIN_RD = {{0, 0, 0, 0, 0}, {0.25f, .11f, .07f, .06f, .09f},
        {0.375f, .42f, .27f, .21f, .29f}, {0.5f, .72f, .47f, .37f, .49f}, {0.625f, .98f, .68f, .54f, .68f},
        {0.75f, .99f, .89f, .85f, .88f}, {1, 1, 1, 1, 1}};
    private static final float[][] CLOTH_RD = {{0, 0, 0, 0, 0}, {0.55f, 0, 0, 0, 0}, {0.63f, .98f, .58f, .54f, .37f},
        {0.75f, 1, .9f, .88f, .88f}, {1, 1, 1, 1, 1}};
    private static final float[][] HAIR_RD = {{0, 0, 0, 0, 0}, {0.5f, 0, 0, 0, 0}, {0.53f, .27f, .18f, .18f, .06f},
        {0.625f, .8f, .43f, .43f, .25f}, {0.75f, .82f, .74f, .74f, .5f}, {1, 1, 1, 1, 1}};
    private static final float[][] HAIR_RS = {{0, 0, 0, 0, 1}, {0.5f, 0, 0, 0, 1}, {0.625f, .25f, .25f, .33f, 1},
        {0.75f, .47f, .47f, .64f, 1}, {1, .75f, .7f, .9f, 1}};
    private static final float[][] WHITE = {{0, 1, 1, 1, 1}, {1, 1, 1, 1, 1}};
    /** Procedural stand-in per row of {@link ModelPackageData#RAMPS}. */
    private static final float[][][] FALLBACK = {CLOTH_RD, SKIN_RD, SKIN_RD, HAIR_RD, SKIN_RD, WHITE, HAIR_RS};

    /**
     * The 256x8 ramp atlas from a package's ramp images (by row name; missing rows procedural). The images stay owned
     * by the caller. Cloth RS is 2D (u = normalized GGX lobe, Unity v = roughness*(1-metal)); PNG rows are top-down,
     */
    static NativeImage rampAtlas(Map<String, NativeImage> ramps, float clothSmoothness) {
        var atlas = new NativeImage(256, ROWS, false);
        for (var y = 0; y < ModelPackageData.RAMPS.length; y++) {
            var name = ModelPackageData.RAMPS[y];
            row(atlas, y, ramps.get(name), FALLBACK[y], name.equals("clothRS") ? clothSmoothness : 0.5f);
        }
        row(atlas, ROWS - 1, null, WHITE, 0.5f);
        return atlas;
    }

    private static void row(NativeImage atlas, int y, @Nullable NativeImage image, float[][] fallback, float v) {
        for (var x = 0; x < 256; x++) {
            int abgr;
            if (image != null) {
                // the PNG is stored top-down, the game's GPU rows bottom-up: texture v maps to row (1 - v)
                var sy = Math.min(image.getHeight() - 1, Math.max(0, Math.round((1f - v) * (image.getHeight() - 1))));
                var sx = Math.min(image.getWidth() - 1, x * image.getWidth() / 256);
                abgr = image.getPixelRGBA(sx, sy);
            } else {
                abgr = eval(fallback, x / 255f);
            }
            atlas.setPixelRGBA(x, y, abgr);
        }
    }

    private static int eval(float[][] points, float u) {
        var i = 0;
        while (i < points.length - 2 && u > points[i + 1][0]) {
            i++;
        }
        var a = points[i];
        var b = points[i + 1];
        var t = Math.max(0f, Math.min(1f, (u - a[0]) / Math.max(b[0] - a[0], 1e-6f)));
        var r = (int)(255 * (a[1] + (b[1] - a[1]) * t) + 0.5f);
        var g = (int)(255 * (a[2] + (b[2] - a[2]) * t) + 0.5f);
        var bl = (int)(255 * (a[3] + (b[3] - a[3]) * t) + 0.5f);
        var al = (int)(255 * (a[4] + (b[4] - a[4]) * t) + 0.5f);
        return al << 24 | bl << 16 | g << 8 | r;
    }

    /** Uploads (render thread) with linear filtering and clamped edges; the texture owns the image. */
    static DynamicTexture upload(NativeImage image) {
        var texture = new DynamicTexture(image);
        texture.setFilter(true, false);
        RenderSystem.bindTexture(texture.getId());
        RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return texture;
    }
}
