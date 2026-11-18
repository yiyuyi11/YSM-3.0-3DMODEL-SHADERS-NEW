package com.elfmcys.ysm.client.texture;

import com.elfmcys.ysm.util.ResourceDiagnostics;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The optional maps the toon shader reads beside base colour and the LabPBR specular map, found by naming convention
 * next to the model's own texture:
 *
 * <ul>
 * <li>{@code <name>_ramp.png} — light ramp, Endfield's {@code _DiffRampMap}: black through a tinted mid tone to white.
 * The shader takes the mid tone as the shadow's colour cast.</li>
 * <li>{@code <name>_specramp.png} — specular ramp, {@code _SpecRampMap}: the highlight's shape along the half vector.</li>
 * <li>{@code <name>_shadowlut.png} — shadow colour, {@code _ShadowLutTex}. Either a horizontal gradient or a 3D LUT
 * stored as a strip of equal square slices stacked vertically; the shader samples the strip trilinearly when the
 * image's height is a whole multiple of its width.</li>
 * <li>{@code <name>_outline.png} — outline mask, {@code _OutlineMask}: white keeps the outline hull, black cuts it
 * away.</li>
 * </ul>
 *
 * <p>
 * A map that is absent falls back to a one-pixel white texture, so a model without these maps shades exactly as
 * before and the shader needs no branches.
 */
public final class ToonExtraMaps {
    private static final Map<ResourceLocation, Entry> CACHE = new ConcurrentHashMap<>();
    private static DynamicTexture white;

    private ToonExtraMaps() {}

    /** The maps for a model texture, loaded on first use. */
    public static Entry of(ResourceLocation texture) {
        return CACHE.computeIfAbsent(texture, ToonExtraMaps::load);
    }

    private static Entry load(ResourceLocation texture) {
        var ramp = sidecar(texture, "_ramp");
        var specRamp = sidecar(texture, "_specramp");
        var shadowLut = sidecar(texture, "_shadowlut");
        var outline = sidecar(texture, "_outline");
        var slices = 1;
        if (shadowLut != null && shadowLut.width() > 0) {
            slices = Math.max(1, shadowLut.height() / shadowLut.width());
        }
        return new Entry(ramp == null ? white() : ramp.texture(), specRamp == null ? white() : specRamp.texture(),
            shadowLut == null ? white() : shadowLut.texture(), outline == null ? null : outline.texture(), slices);
    }

    /** A PNG beside the model texture with the given suffix, uploaded and owned by the cache, or null. */
    @Nullable
    private static Sidecar sidecar(ResourceLocation texture, String suffix) {
        var path = texture.getPath();
        var dot = path.lastIndexOf('.');
        var base = dot < 0 ? path : path.substring(0, dot);
        var location = new ResourceLocation(texture.getNamespace(), base + suffix + ".png");
        try {
            var manager = Minecraft.getInstance().getResourceManager();
            var resource = manager.getResource(location);
            if (resource.isEmpty()) {
                return null;
            }
            var image = NativeImage.read(resource.get().open());
            var width = image.getWidth();
            var height = image.getHeight();
            var dynamic = new DynamicTexture(image);
            dynamic.setFilter(true, false);
            Minecraft.getInstance().getTextureManager().register(location, dynamic);
            return new Sidecar(dynamic, width, height);
        } catch (IOException | RuntimeException failure) {
            ResourceDiagnostics.log("toon map {} not loaded: {}", location, failure.toString());
            return null;
        }
    }

    /** One pixel that leaves every term unchanged. */
    private static synchronized AbstractTexture white() {
        if (white == null) {
            var image = new NativeImage(1, 1, false);
            image.setPixelRGBA(0, 0, 0xFFFFFFFF);
            white = new DynamicTexture(image);
            white.setFilter(false, false);
        }
        return white;
    }

    private record Sidecar(AbstractTexture texture, int width, int height) {
    }

    /**
     * The maps of one model texture, each a valid GL texture, plus the number of slices in the shadow LUT strip (1 for a
     * plain gradient or no LUT at all).
     */
    public record Entry(AbstractTexture ramp, AbstractTexture specRamp, AbstractTexture shadowLut,
        @Nullable AbstractTexture outline, int lutSlices) {
    }
}
