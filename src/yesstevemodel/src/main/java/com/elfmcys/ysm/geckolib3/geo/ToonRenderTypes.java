package com.elfmcys.ysm.geckolib3.geo;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.compat.IrisCompat;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.geckolib3.core.util.Color;
import com.elfmcys.ysm.client.texture.CustomPBRTextureSet;
import com.elfmcys.ysm.client.texture.ToonExtraMaps;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.util.function.Function;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Render types for imported humanoid meshes: hybrid PBR and cel shading (assets/ysm/shaders/core/ysm_toon) and the
 * outline hull. The toon shader reads the model's LabPBR specular map from Sampler3. Under a shader pack the pack's own
 * entity programs must light the model, so the toon shader steps aside and meshes draw with the vanilla cutout type the
 * pack recognizes, reading the same specular map through its PBR loader; the only say left over the pack's lighting is
 * the vertex tint ({@link #tint()}). The outline hull is plain geometry and draws either way.
 */
public final class ToonRenderTypes extends RenderType {
    /** Cloth in LabPBR, red in the low byte: for a mesh without a specular map. */
    private static final int NEUTRAL_SPECULAR = 64 | 10 << 8 | 10 << 16;

    @Nullable
    private static ShaderInstance toonShader;
    @Nullable
    private static ShaderInstance outlineShader;
    @Nullable
    private static DynamicTexture neutralSpecular;
    private static float tintBrightness = 1f;
    private static Color tint = Color.WHITE;

    private static final Function<ResourceLocation, RenderType> TOON = Util.memoize(texture -> create("ysm_toon",
        DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true, false, CompositeState.builder()
            .setShaderState(new ShaderStateShard(() -> toonShader)).setTextureState(new EmptyTextureStateShard(() -> {
                RenderSystem.setShaderTexture(0, texture);
                RenderSystem.setShaderTexture(3, specularId(texture));
                var extra = ToonExtraMaps.of(texture);
                RenderSystem.setShaderTexture(4, extra.ramp().getId());
                RenderSystem.setShaderTexture(5, extra.specRamp().getId());
                RenderSystem.setShaderTexture(6, extra.shadowLut().getId());
                if (toonShader != null) {
                    var slices = toonShader.getUniform("LutSlices");
                    if (slices != null) {
                        slices.set(extra.lutSlices());
                    }
                }
            }, () -> {
            })).setTransparencyState(NO_TRANSPARENCY).setCullState(NO_CULL).setLightmapState(LIGHTMAP)
            .setOverlayState(OVERLAY).createCompositeState(true)));

    private static final Function<ResourceLocation, RenderType> OUTLINE = Util.memoize(texture -> create("ysm_outline",
        DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true, false, CompositeState.builder()
            .setShaderState(new ShaderStateShard(() -> outlineShader)).setTextureState(
                new EmptyTextureStateShard(() -> {
                    RenderSystem.setShaderTexture(0, texture);
                    var extra = ToonExtraMaps.of(texture);
                    RenderSystem.setShaderTexture(4, extra.outline().getId());
                }, () -> {
                })).setTransparencyState(NO_TRANSPARENCY).setCullState(CULL).setLightmapState(LIGHTMAP)
            .setOverlayState(OVERLAY).createCompositeState(false)));

    private ToonRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
        boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
            new ResourceLocation(YesSteveModel.MOD_ID, "ysm_toon"), DefaultVertexFormat.NEW_ENTITY),
            shader -> toonShader = shader);
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
            new ResourceLocation(YesSteveModel.MOD_ID, "ysm_outline"), DefaultVertexFormat.NEW_ENTITY),
            shader -> outlineShader = shader);
    }

    /** The cel-shaded type for an opaque mesh, or null when a shader pack is active or the shader failed to load. */
    @Nullable
    public static RenderType toon(ResourceLocation texture) {
        return toonShader != null && (!IrisCompat.isShaderPackInUse() || ClientConfig.TOON_UNDER_SHADER_PACK.get())
            ? TOON.apply(texture) : null;
    }

    /**
     * Vertex tint of a mesh: white for the toon shader, which keeps its output in range itself; the configured
     * brightness under a shader pack, whose sun on textures painted near white washes them out.
     */
    public static Color tint() {
        if (!IrisCompat.isShaderPackInUse()) {
            return Color.WHITE;
        }
        var brightness = ClientConfig.SHADER_PACK_MESH_BRIGHTNESS.get().floatValue();
        if (brightness != tintBrightness) {
            tintBrightness = brightness;
            tint = Color.ofRGB(brightness, brightness, brightness);
        }
        return tint;
    }

    /** The GL texture of the model's specular map, uploading it on first use, or of a neutral one. */
    private static int specularId(ResourceLocation texture) {
        var host =
            Minecraft.getInstance().getTextureManager().getTexture(texture, MissingTextureAtlasSprite.getTexture());
        if (host instanceof CustomPBRTextureSet set) {
            var specular = set.toonSpecular();
            if (specular != null) {
                return specular.getId();
            }
        }
        var neutral = neutralSpecular;
        if (neutral == null) {
            var pixels = new NativeImage(1, 1, false);
            pixels.setPixelRGBA(0, 0, NEUTRAL_SPECULAR);
            neutral = new DynamicTexture(pixels);
            neutralSpecular = neutral;
        }
        return neutral.getId();
    }

    /**
     * The outline hull's type: vanilla entity cutout, which culls back faces, so only the inside of the inflated hull
     * shows beyond the model's silhouette.
     */
    public static RenderType outline(ResourceLocation texture) {
        var extra = ToonExtraMaps.of(texture);
        if (outlineShader == null || extra.outline() == null) {
            return RenderType.entityCutout(texture);
        }
        return OUTLINE.apply(texture);
    }
}
