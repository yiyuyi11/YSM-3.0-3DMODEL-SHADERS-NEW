package com.ysmnpr.endfield;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.jetbrains.annotations.Nullable;

/**
 * One model's NPR package on the GPU: its lookup textures, the face/iris/overlay-card rectangles in YSM's atlas (atlas
 * uv) and its material parameters. YSM packs every material texture affinely into one atlas (atlasUV = (region.xy +
 * uv * region.wh) / atlasSize, no rotation), so the face SDF/mask/highlight, the iris matcap and the OverlayShadow
 * cards are addressed through those rectangles, which the importer computed (ModelPackageData). {@link #generic} is
 * the look of a model without a package: procedural ramps, generic material values, no LUT/SDF/highlight/matcap or
 * overlay layers.
 */
final class ModelPackage {
    /**
     *
     * @param cardUv the card's own 0..1 uv rectangle in atlas uv (x, y, w, h)
     * @param texture _BaseMap GL texture
     * @param baseColor _BaseColor rgba
     * @param useGrayAsAlpha _UseGrayAsAlpha (alpha = lerp(texture alpha, texture red, this))
     * @param stencilRef _ShadowOverIris, the stencil reference compared under read mask 20 (4: skin/face/hair
     *     receivers, 20: iris/brow receivers)
     * @param disableDrawUnderHair _DisableDrawUnderHair; 0 suppresses the layer where hair is the receiver
     * @param angleRange _ShadowAngleRange (Unity metres, applied as blocks)
     */
    record Overlay(String card, float[] cardUv, int texture, float[] baseColor, float useGrayAsAlpha, int stencilRef,
        boolean disableDrawUnderHair, float angleRange) {
    }

    /**
     * carries the model's own): per surface class [smoothness, specular, shadowColorBrightness,
     * shadowColorSaturation], edge tint, hair anisotropy, outline per class (skin, face, hair, cloth).
     */
    private static final Map<String, float[]> GENERIC = new LinkedHashMap<>();

    static {
        GENERIC.put("skin", new float[] {0.25f, 0.5f, 0.55f, 1.4f});
        GENERIC.put("face", new float[] {1f, 0f, 0.55f, 1.4f});
        GENERIC.put("hair", new float[] {0.7f, 0f, 0.6f, 1f});
        GENERIC.put("cloth", new float[] {0.4f, 0.5f, 0.5f, 1f});
        GENERIC.put("eye", new float[] {0f, 0f, 0.55f, 1.4f});
        GENERIC.put("skinRimOffScale", new float[] {1f});
        GENERIC.put("faceSkinRimOffScale", new float[] {1f});
        GENERIC.put("faceRimOffScale", new float[] {1f});
        GENERIC.put("sdfRimColor", new float[] {0.65f, 0.4f, 0.42f});
        GENERIC.put("hairAniso", new float[] {0.5f, 3f, 1f, 0f});
        GENERIC.put("hairSpecMask", new float[] {0.5f, 1f});
        GENERIC.put("hairAniso2", new float[] {-0.02f, 0.5f});
        GENERIC.put("hairAnisoColor2", new float[] {0.2f, 0.2f, 0.1f});
        GENERIC.put("outlineWidth", new float[] {0.6f, 0.5f, 0.5f, 0.6f});
        GENERIC.put("outlineOffsetZ", new float[] {0f, 0.75f, 0.01f, 0.02f});
        GENERIC.put("outlineColorSkinCloth", new float[] {0.5f, 1.5f, 0.35f, 1.5f});
        GENERIC.put("outlineColorHairEye", new float[] {0.8f, 1f, 0.5f, 1.5f});
    }

    /** Decoded off the render thread; {@link #upload} turns it into a package on the render thread. */
    record Decoded(ModelPackageData.Parsed parsed, NativeImage ramps, Map<String, NativeImage> images,
        @Nullable NprMaps.Composite maps) {
        void close() {
            ramps.close();
            images.values().forEach(NativeImage::close);
            if (maps != null) {
                maps.close();
            }
        }
    }

    final String label;
    @Nullable
    final float[] faceUv;
    @Nullable
    final float[] irisUv;
    final float[] highlightVector;
    final float[] matcapColor;
    final float matcapNormalScale;
    final List<Overlay> overlays;
    // shading (uniform order as NprRenderer sets them)
    final float[] skin, face, hair, cloth, eye, sdfRimColor, hairAniso, hairSpecMask, hairAniso2, hairAnisoColor2,
        outlineWidth, outlineOffsetZ, outlineColorSkinCloth, outlineColorHairEye;
    final float skinRimOffScale, faceSkinRimOffScale, faceRimOffScale;
    // GL textures, 0 = absent
    final int ramps, lut, sdf, sdfMask, highlight, matcap;
    /** The per-material maps and table (NprMaps), null without {@code materials}. */
    @Nullable
    final NprMaps maps;
    /** The package sets hairSpecMask: its x is the gloss stand-in instead of the hairGlossFallback config. */
    final boolean hairGlossSet;
    /** NormalOutline: the largest per-material _OutlineWidth of {@code materials} (0 without), for the scissor margin. */
    final float maxMaterialOutlineWidth;
    private final Map<String, ModelPackageData.Material> materials;
    private final float[] atlas;
    private final List<DynamicTexture> owned;

    private ModelPackage(String label, ModelPackageData.Parsed p, Map<String, Integer> files, int ramps,
        List<DynamicTexture> owned, @Nullable NprMaps maps) {
        this.label = label;
        this.owned = owned;
        this.ramps = ramps;
        this.maps = maps;
        hairGlossSet = p.shading().containsKey("hairSpecMask");
        materials = p.materials();
        atlas = p.atlas();
        maxMaterialOutlineWidth = (float)materials.values().stream().map(m -> m.params().get("_OutlineWidth"))
            .filter(v -> v != null && v.length > 0).mapToDouble(v -> v[0]).max().orElse(0.0);

        lut = texture(p, files, "skinLut");
        sdf = texture(p, files, "face.sdf");
        sdfMask = texture(p, files, "face.sdfMask");
        highlight = texture(p, files, "face.highlight");
        matcap = texture(p, files, "iris.matcap");
        faceUv = p.faceRect() != null ? uv(p.faceRect(), p.atlas()) : null;
        irisUv = p.irisRect() != null ? uv(p.irisRect(), p.atlas()) : null;
        highlightVector = p.highlightVector() != null ? p.highlightVector() : new float[] {0f, 0f, 1f};
        matcapColor = p.matcapColor() != null ? p.matcapColor() : new float[] {1f, 1f, 1f, 1f};
        matcapNormalScale = p.matcapNormalScale();
        var layers = new ArrayList<Overlay>();
        for (var o : p.overlays()) {
            layers.add(new Overlay(o.card(), uv(o.cardRect(), p.atlas()), files.getOrDefault(o.texture(), 0),
                o.baseColor(), o.useGrayAsAlpha(), o.stencilRef(), o.disableDrawUnderHair(), o.angleRange()));
        }
        overlays = List.copyOf(layers);
        skin = shading(p, "skin", 4);
        face = shading(p, "face", 4);
        hair = shading(p, "hair", 4);
        cloth = shading(p, "cloth", 4);
        eye = shading(p, "eye", 4);
        sdfRimColor = shading(p, "sdfRimColor", 3);
        hairAniso = shading(p, "hairAniso", 4);
        hairSpecMask = shading(p, "hairSpecMask", 2);
        hairAniso2 = shading(p, "hairAniso2", 2);
        hairAnisoColor2 = shading(p, "hairAnisoColor2", 3);
        outlineWidth = shading(p, "outlineWidth", 4);
        outlineOffsetZ = shading(p, "outlineOffsetZ", 4);
        outlineColorSkinCloth = shading(p, "outlineColorSkinCloth", 4);
        outlineColorHairEye = shading(p, "outlineColorHairEye", 4);
        skinRimOffScale = shading(p, "skinRimOffScale", 1)[0];
        faceSkinRimOffScale = shading(p, "faceSkinRimOffScale", 1)[0];
        faceRimOffScale = shading(p, "faceRimOffScale", 1)[0];
    }

    boolean hasSdf() {
        return faceUv != null && sdf != 0 && sdfMask != 0;
    }

    boolean hasHighlight() {
        return faceUv != null && highlight != 0;
    }

    boolean hasMatcap() {
        return irisUv != null && matcap != 0;
    }

    /**
     * Images of a parsed package decoded, and its ramp atlas built (any thread). {@code packageZip} (the package as
     * stored) keys the per-material map cache ({@link NprMapCache}); {@code label} names the model in the log.
     */
    static Decoded decode(ModelPackageData.Parsed parsed, byte[] packageZip, String label) throws IOException {
        var images = new HashMap<String, NativeImage>();
        try {
            for (var file : new java.util.TreeSet<>(parsed.textures().values())) {
                images.put(file, image(parsed.files().get(file)));
            }
            for (var overlay : parsed.overlays()) {
                if (!images.containsKey(overlay.texture())) {
                    images.put(overlay.texture(), image(parsed.files().get(overlay.texture())));
                }
            }
            var ramps = new HashMap<String, NativeImage>();
            for (var name : ModelPackageData.RAMPS) {
                var file = parsed.textures().get("ramp." + name);
                if (file != null) {
                    ramps.put(name, images.get(file));
                }
            }
            var cloth = parsed.shading().getOrDefault("cloth", GENERIC.get("cloth"));
            var rampAtlas = NprTextures.rampAtlas(ramps, cloth[0]);
            NprMaps.Composite maps = null;
            if (!parsed.materials().isEmpty()) {
                try {
                    var cacheMB = Math.max(0f, NprConfig.f("mapCacheMB"));
                    maps = NprMapCache.build(parsed, packageZip, Math.max(16, (int)NprConfig.f("mapResolution")),
                        (long)(Math.max(1f, NprConfig.f("mapBudgetMB")) * (1 << 20)), NprMapCache.directory(),
                        (long)(cacheMB * (1 << 20)), label);
                } catch (IOException | RuntimeException failure) {
                    rampAtlas.close();
                    throw failure;
                }
            }
            return new Decoded(parsed, rampAtlas, images, maps);
        } catch (IOException | RuntimeException failure) {
            images.values().forEach(NativeImage::close);
            throw failure;
        }
    }

    /**
     * Decodes a PNG from a malloc'd buffer. NativeImage.read(byte[]) copies into LWJGL's thread stack (64 KB), which a
     * face SDF (~270 KB) overflows ("Out of stack space").
     */
    static NativeImage image(byte[] png) throws IOException {
        var buffer = org.lwjgl.system.MemoryUtil.memAlloc(png.length);
        try {
            buffer.put(png).flip();
            return NativeImage.read(buffer);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(buffer);
        }
    }

    /** GL textures of a decoded package (render thread); takes ownership of its images. */
    static ModelPackage upload(String label, Decoded decoded) {
        var owned = new ArrayList<DynamicTexture>();
        var ramps = NprTextures.upload(decoded.ramps());
        owned.add(ramps);
        var files = new HashMap<String, Integer>();
        // ramp images were read into the atlas; textures are made of the files other roles and the overlays use
        var used = new java.util.HashSet<String>();
        decoded.parsed().textures().forEach((role, file) -> {
            if (!role.startsWith("ramp.")) {
                used.add(file);
            }
        });
        decoded.parsed().overlays().forEach(o -> used.add(o.texture()));
        for (var e : decoded.images().entrySet()) {
            if (!used.contains(e.getKey())) {
                e.getValue().close();
                continue;
            }
            var texture = NprTextures.upload(e.getValue());
            owned.add(texture);
            files.put(e.getKey(), texture.getId());
        }
        var maps = decoded.maps() != null ? NprMaps.upload(decoded.maps()) : null;
        return new ModelPackage(label, decoded.parsed(), files, ramps.getId(), List.copyOf(owned), maps);
    }

    /** The look of a model without a package (render thread). */
    static ModelPackage generic() {
        var atlas = NprTextures.upload(NprTextures.rampAtlas(Map.of(), GENERIC.get("cloth")[0]));
        return new ModelPackage("generic", ModelPackageData.EMPTY, Map.of(), atlas.getId(), List.of(atlas), null);
    }

    void close() {
        owned.forEach(DynamicTexture::close);
        if (maps != null) {
            maps.close();
        }
    }

    /** Every {@code materials} entry by glTF material name, in profile order. */
    Map<String, ModelPackageData.Material> materials() {
        return materials;
    }

    /** A {@code materials} entry by glTF material name, or null. */
    @Nullable
    ModelPackageData.Material material(String name) {
        return materials.get(name);
    }

    /** A number of a material's Unity parameters (first component), or {@code fallback}. */
    float param(String material, String name, float fallback) {
        var m = materials.get(material);
        var v = m != null ? m.params().get(name) : null;
        return v != null && v.length > 0 ? v[0] : fallback;
    }

    /** A colour of a material's Unity parameters ([r, g, b, a] as stored, gamma space), or {@code fallback}. */
    float[] color(String material, String name, float[] fallback) {
        var m = materials.get(material);
        var v = m != null ? m.params().get(name) : null;
        return v != null && v.length >= 4 ? v : fallback;
    }

    /** A material's atlas rectangle in atlas uv (x, y, w, h), or null. */
    @Nullable
    float[] materialUv(String material) {
        var m = materials.get(material);
        return m != null ? uv(m.rect(), atlas) : null;
    }

    private static int texture(ModelPackageData.Parsed p, Map<String, Integer> files, String role) {
        var file = p.textures().get(role);
        return file != null ? files.getOrDefault(file, 0) : 0;
    }

    private static float[] shading(ModelPackageData.Parsed p, String name, int n) {
        var value = p.shading().get(name);
        var fallback = GENERIC.get(name);
        var out = new float[Math.max(n, 4)];
        for (var i = 0; i < n; i++) {
            out[i] = value != null && i < value.length ? value[i] : fallback[i];
        }
        return out;
    }

    private static float[] uv(float[] r, float[] atlas) {
        return new float[] {r[0] / atlas[0], r[1] / atlas[1], r[2] / atlas[0], r[3] / atlas[1]};
    }
}
