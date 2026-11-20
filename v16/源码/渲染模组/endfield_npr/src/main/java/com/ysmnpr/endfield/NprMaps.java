package com.ysmnpr.endfield;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * The per-material maps of a model package ({@code materials}, ~/ysm/handoff/npr-materials-schema.md) on the GPU, read
 * by assets/endfield_npr/shaders/include/npr_maps.glsl.
 *
 * <ul>
 * <li><b>Sampler11</b>: a GL_TEXTURE_2D_ARRAY aligned with YSM's colour atlas, one layer per atlas-aligned kind the
 * model has ({@link #ALIGNED}). YSM puts every material's base texture whole into one atlas rectangle with linearly
 * remapped uv (TextureAtlas.remap), so each material's map is drawn (box-filtered) into that rectangle, plus one texel
 * of edge padding; texels no material covers keep the kind's neutral value ({@link #NEUTRAL}). Layer resolution: the
 * atlas scaled down to {@code mapResolution} and until layers + sheet fit {@code mapBudgetMB}.</li>
 * <li><b>Sampler12</b>: a 2D sheet of the tiled maps ({@link #TILED}: _LineMap, _ParallaxTex), each once; the shader
 * tiles them per material (Unity _ST, fract, half-texel guard).</li>
 * <li>A per-material table (uniform arrays, at most {@link #MAX_MATERIALS} entries after merging materials that share
 * one atlas rectangle): atlas rect, map flags, the tiled map's _ST and sheet rectangle, and 4 rows of parameters
 * ({@link #TILED_PARAMS} rows 0-1, {@link #ROWS} rows 2-3).</li>
 * </ul>
 * Emission is baked per texel (map x _EmissionColor x _EmissionBrightness x _UseEmission, linear): rgb = sRGB of the
 * colour normalised by its maximum, a = log2(1 + maximum) / 8 (npr_maps.glsl nprEmission). The CPU work
 * ({@link #build}) is called from the package loader's worker thread and spreads its PNG decodes and layers over a
 * small pool; {@link NprMapCache} keeps its results on disk. {@link #upload} and the binding run on the render thread.
 */
final class NprMaps {
    /** Atlas-aligned kinds, in shader id order (NPR_PROP = 0 ...). */
    static final String[] ALIGNED = {"prop", "emission", "normal", "splitNormal", "outlineMask", "browMask",
        "baseAlpha"};
    /** Tiled kinds; shader ids TILED_BASE + index (NPR_LINE = 8, NPR_PARALLAX = 9). */
    static final String[] TILED = {"line", "parallax"};
    static final int TILED_BASE = 8;
    static final int MAX_MATERIALS = 24;
    private static final int EMISSION = 1;
    /** Mipmap levels of the layer array beyond the base (normal/gloss detail aliases at a distance without them). */
    private static final int MIP_LEVELS = 4;
    /**
     * Power of the mean that reduces hair _MetallicGlossMap G/A into the layer (materials with _AnisotropyIntensity).
     * The primary highlight reads G through the RS ramp, which is black below u ~ 0.52, so a box average of thin
     * bright strands with dark gaps falls under it and the highlight fades. tools/hair_gloss_eval.py on Pelica's
     * composited layers (energy / mean peak vs her full-resolution map): box 0.48x / 0.91x, p 8 0.86x / 0.93x from the
     * patcher's 1024 px map, 0.98x / 0.95x from the 2048 px original (96 MB budget: 0.88x, 0.95x).
     */
    static final float HAIR_GLOSS_POWER = 8f;
    /**
     * Version of {@link #build}'s output for the same package and settings, part of {@link NprMapCache}'s key: bump it
     * with every change to the compositing (filters, packing, neutral texels, table layout), or players keep loading
     * composites of the old code from their disk cache.
     */
    static final int COMPOSITOR_VERSION = 4; // 2: table flag HAIR_SHADER_FLAG; 3: rain disable per entry; 4: class flags
    /**
     * Material table flag (bit of the flags word, beside the kind bits): a material of the region is drawn by the hair
     * shader in the game (its Unity parameters carry _AnisotropyIntensity). Its _MetallicGlossMap is the hair layout
     * (R tangent blend, G gloss, B shadow weight, A secondary), never cloth metal/smoothness, and its texels are
     * shaded as hair whatever YSM's class map (or the colour fallback without one) says (npr_maps.glsl
     * nprMaterialIsHair).
     */
    static final int HAIR_SHADER_FLAG = 10;
    /**
     * Material table flags of the game shader of a region's materials (classFromMaterials, npr_character.fsh
     * nprMaterialClass): Eye (params with _EyeHighLightColor: irises and brows), Skin (_SDFRimColor: body and face), and
     * the profile's face material (face_01, whose texture the brows share: per texel only the class map tells them).
     */
    static final int EYE_SHADER_FLAG = 11;
    static final int SKIN_SHADER_FLAG = 12;
    static final int FACE_FLAG = 13;
    /**
     * Threads that decode a package's map PNGs and composite its layers (one task per file / physical layer), at the
     * package loader's low priority. Only a model's first load does this work; later ones read NprMapCache.
     */
    private static final int THREADS = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 2));
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, r -> {
        var thread = new Thread(r, "endfield_npr maps");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    /**
     * Physical array layer of each aligned kind: the single-channel kinds share one "masks" layer (outlineMask in rg,
     * browMask in b, baseAlpha in a; npr_maps.glsl swizzles them back), so the others keep more resolution.
     */
    private static final int[] PHYSICAL = {0, 1, 2, 3, 4, 4, 4};
    private static final int PHYSICAL_LAYERS = 5;
    private static final int OUTLINE_MASK = 4;
    private static final int BROW_MASK = 5;
    private static final int BASE_ALPHA = 6;
    /** Neutral texel per physical layer (RGBA bytes, red in the low byte): masks = outline 1, brow 0, alpha 0. */
    private static final int[] NEUTRAL = {rgba(0, 0, 255, 0), 0, rgba(128, 128, 0, 255), rgba(128, 128, 128, 128),
        rgba(255, 255, 0, 0)};
    /**
     * Parameters of a material's tiled kind, packed into table rows 0 (xyzw) and 1 (xyzw). Syntax per entry: a Unity
     * property name, optionally {@code .r/.g/.b/.a} (or {@code .x..w}) for a colour component, {@code a*b} for a
     * product, {@code #kind} = 1 when the material has that map, {@code :default} for a missing value (else 0). Over
     * the materials sharing one atlas rectangle, a value comes from the first that has its properties;
     * {@code >expr} takes the maximum instead.
     */
    static final String[][] TILED_PARAMS = {
        {"_LineAmount", "_LineValue", "_LineRange", "_LineIntensity", "_LineSaturation", "_UseLineMap", "", ""},
        {"_ParallaxScale", "_ParallaxMarchNum", "_ParallaxUseNormal", "_AlphaPremultiply", "_ParallaxColor.r",
            "_ParallaxColor.g", "_ParallaxColor.b", "_BaseColor.a"},
    };
    /** Table rows 2 (NPR_ROW_NORMAL) and 3 (NPR_ROW_OUTLINE), same syntax (owner of the region's texture first). */
    static final String[][] ROWS = {
        {"_BumpScale*_UseBumpMap", "_SpecBumpScale*_UseSpecBumpMap", "#normal", "#splitNormal"},
        {">_OutlineWidth:-1", ">_OutlineOffsetZ:-1", ">_EnableOutline:1", ">_EnableOutlineMask:1"},
    };
    private static final float[] SRGB_TO_LINEAR = new float[256];

    static {
        for (var i = 0; i < 256; i++) {
            var c = i / 255f;
            SRGB_TO_LINEAR[i] = c <= 0.04045f ? c / 12.92f : (float)Math.pow((c + 0.055f) / 1.055f, 2.4f);
        }
    }

    /** CPU result of {@link #build}; owns off-heap memory until uploaded or closed. */
    static final class Composite {
        final int width;
        final int height;
        final int[] layerOf = new int[ALIGNED.length];
        int layers;
        @Nullable
        ByteBuffer pixels;
        @Nullable
        NativeImage sheet;
        final float[] table = new float[MAX_MATERIALS * 8 * 4];
        /** _DisableRainEffectOnMaterial per table entry (npr_maps.glsl NprMatRain, the rain blocks' cb6[5].x). */
        final float[] rain = new float[MAX_MATERIALS];
        int count;

        Composite(int width, int height) {
            this.width = width;
            this.height = height;
            Arrays.fill(layerOf, -1);
        }

        void close() {
            if (pixels != null) {
                MemoryUtil.memFree(pixels);
                pixels = null;
            }
            if (sheet != null) {
                sheet.close();
                sheet = null;
            }
        }
    }

    final int array;
    @Nullable
    private final DynamicTexture sheet;
    private final float[] layers = new float[8];
    private final float[] table;
    private final float[] rain;
    private final int count;
    private static int emptyArray;
    /** Uniform locations per program object (a resource reload builds new ShaderInstances). */
    private static final Map<ShaderInstance, int[]> LOCATIONS = new java.util.WeakHashMap<>();
    private static final FloatBuffer UPLOAD = MemoryUtil.memAllocFloat(MAX_MATERIALS * 4 * 4);

    private NprMaps(int array, @Nullable DynamicTexture sheet, Composite c) {
        this.array = array;
        this.sheet = sheet;
        Arrays.fill(layers, -1f);
        for (var k = 0; k < ALIGNED.length; k++) {
            layers[k] = c.layerOf[k];
        }
        table = c.table.clone();
        rain = c.rain.clone();
        count = c.count;
    }

    // ---------------------------------------------------------------- worker thread

    /**
     * Composites the package's per-material maps (any thread). {@code resolution} caps the larger layer side,
     * {@code budget} (bytes) the layers plus the tiled sheet.
     */
    static Composite build(ModelPackageData.Parsed p, int resolution, long budget) throws IOException {
        return build(p, resolution, budget, HAIR_GLOSS_POWER);
    }

    /** {@link #build} with the power of the hair gloss reduction ({@link #HAIR_GLOSS_POWER}; 1 = box filter). */
    static Composite build(ModelPackageData.Parsed p, int resolution, long budget, float hairGlossPower)
        throws IOException {
        var atlasW = Math.max(1, Math.round(p.atlas()[0]));
        var atlasH = Math.max(1, Math.round(p.atlas()[1]));
        var images = new ConcurrentHashMap<String, NativeImage>();
        Composite c = null;
        try {
            // tiled sheet first: its size counts against the budget
            var tiledFiles = new LinkedHashMap<String, float[]>(); // file -> sheet rect (texels), filled by packSheet
            for (var m : p.materials().values()) {
                for (var kind : TILED) {
                    var ref = m.maps().get(kind);
                    if (ref != null) {
                        tiledFiles.putIfAbsent(ref.file(), null);
                    }
                }
            }
            // physical layers: prop, emission, normal, splitNormal, masks (outlineMask rg, browMask b, baseAlpha a)
            var present = new boolean[ALIGNED.length];
            var physical = new boolean[PHYSICAL_LAYERS];
            // materials sharing one atlas rectangle share its texels: the first listed with a kind paints it (a later
            // one, e.g. Pelica's cloth_03 under cloth_02's emission, cannot repaint)
            var painters = new ArrayList<List<ModelPackageData.Material>>();
            var files = new LinkedHashSet<>(tiledFiles.keySet());
            for (var k = 0; k < ALIGNED.length; k++) {
                var painted = new ArrayList<ModelPackageData.Material>();
                for (var m : p.materials().values()) {
                    var ref = m.maps().get(ALIGNED[k]);
                    if (ref != null && painted.stream().noneMatch(o -> Arrays.equals(o.rect(), m.rect()))) {
                        painted.add(m);
                        files.add(ref.file());
                    }
                }
                painters.add(painted);
                present[k] = !painted.isEmpty();
                physical[PHYSICAL[k]] |= present[k];
            }
            // every map PNG once, decoded on the pool (each texel is read later, so the peak is the same either way)
            parallel(files.stream().<Runnable>map(f -> () -> images.put(f, decode(p, f))).toList());
            NativeImage sheet = null;
            if (!tiledFiles.isEmpty()) {
                sheet = packSheet(tiledFiles, images);
            }
            var kinds = 0;
            for (var used : physical) {
                kinds += used ? 1 : 0;
            }
            var scale = Math.min(1f, resolution / (float)Math.max(atlasW, atlasH));
            var sheetBytes = sheet != null ? (long)sheet.getWidth() * sheet.getHeight() * 4 : 0L;
            // x 4/3: the layers carry MIP_LEVELS mipmaps (normal/gloss detail would shimmer at a distance without)
            while (scale > 1f / 64f && (long)kinds * layerSide(atlasW, scale) * layerSide(atlasH, scale) * 16 / 3
                + sheetBytes > budget) {
                scale *= 0.9f;
            }
            c = new Composite(layerSide(atlasW, scale), layerSide(atlasH, scale));
            c.sheet = sheet;
            if (kinds > 0) {
                var layerBytes = c.width * c.height * 4;
                c.pixels = MemoryUtil.memAlloc(layerBytes * kinds).order(ByteOrder.LITTLE_ENDIAN);
                var fx = c.width / (float)atlasW;
                var fy = c.height / (float)atlasH;
                var tasks = new ArrayList<Runnable>();
                for (var l = 0; l < PHYSICAL_LAYERS; l++) {
                    if (!physical[l]) {
                        continue;
                    }
                    var layer = c.layers++;
                    var physicalLayer = l;
                    for (var k = 0; k < ALIGNED.length; k++) {
                        c.layerOf[k] = PHYSICAL[k] == l && present[k] ? layer : c.layerOf[k];
                    }
                    // one task per physical layer: the masks layer's kinds write channels of the same texels in turn
                    var out = c;
                    tasks.add(() -> {
                        var base = layer * layerBytes;
                        for (var i = 0; i < out.width * out.height; i++) {
                            out.pixels.putInt(base + i * 4, NEUTRAL[physicalLayer]);
                        }
                        for (var k = 0; k < ALIGNED.length; k++) {
                            if (PHYSICAL[k] != physicalLayer) {
                                continue;
                            }
                            for (var m : painters.get(k)) {
                                var ref = m.maps().get(ALIGNED[k]);
                                var emission = k == EMISSION ? emissionFactor(m) : null;
                                // hair prop: G and A reduced by a power mean, so thin bright strands keep their gloss
                                var power = k == 0 && m.params().containsKey("_AnisotropyIntensity") ? hairGlossPower
                                    : 1f;
                                blit(out.pixels, base, out.width, out.height, fx, fy, m.rect(), images.get(ref.file()),
                                    ref, emission, k, power);
                            }
                        }
                    });
                }
                parallel(tasks);
            }
            table(p, c, tiledFiles);
            return c;
        } catch (DecodeFailure failure) {
            if (c != null) {
                c.close();
            }
            throw new IOException(failure.getMessage(), failure.getCause());
        } catch (RuntimeException failure) {
            if (c != null) {
                c.close();
            }
            throw failure;
        } finally {
            images.values().forEach(NativeImage::close);
        }
    }

    /**
     * Runs the tasks on {@link #POOL} and returns when all have ended (so none still touches the images or the
     * composite after a failure); rethrows the first failure. On one thread when there is a single task.
     */
    private static void parallel(List<Runnable> tasks) {
        if (tasks.size() <= 1 || THREADS <= 1) {
            tasks.forEach(Runnable::run);
            return;
        }
        RuntimeException failure = null;
        for (var future : tasks.stream().map(t -> CompletableFuture.runAsync(t, POOL)).toList()) {
            try {
                future.join();
            } catch (CompletionException e) {
                var cause = e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
                if (failure == null) {
                    failure = cause;
                } else {
                    failure.addSuppressed(cause);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static int layerSide(int atlas, float scale) {
        return Math.max(1, Math.round(atlas * scale));
    }

    private static final class DecodeFailure extends RuntimeException {
        DecodeFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static NativeImage decode(ModelPackageData.Parsed p, String file) {
        try {
            return ModelPackage.image(p.files().get(file));
        } catch (IOException failure) {
            throw new DecodeFailure("materials map " + file + ": " + failure.getMessage(), failure);
        }
    }

    /** Linear emission colour x brightness x use of a material, or zero. */
    private static float[] emissionFactor(ModelPackageData.Material m) {
        var color = m.params().getOrDefault("_EmissionColor", new float[] {1f, 1f, 1f, 1f});
        var brightness = scalar(m, "_EmissionBrightness", 1f) * scalar(m, "_UseEmission", 1f);
        var out = new float[3];
        for (var i = 0; i < 3; i++) {
            // Unity serialises material colours in gamma space and linearises them for a linear-space project
            var g = i < color.length ? Math.max(0f, color[i]) : 1f;
            out[i] = (g <= 0.04045f ? g / 12.92f : (float)Math.pow((g + 0.055f) / 1.055f, 2.4f)) * brightness;
        }
        return out;
    }

    private static float scalar(ModelPackageData.Material m, String name, float fallback) {
        var v = m.params().get(name);
        return v != null && v.length > 0 ? v[0] : fallback;
    }

    /**
     * Draws one material's map into its atlas rectangle of a layer: each layer texel averages the source texels under
     * its footprint (uv clamped to the material's 0..1, which also pads one texel around the rectangle); with
     * {@code power} > 1, G and A take the power mean (mean(x^p))^(1/p) instead. Unity tiling other than 1,1 / 0,0
     * point-samples the tiled uv instead.
     */
    private static void blit(ByteBuffer dst, int base, int lw, int lh, float fx, float fy, float[] rect,
        NativeImage src, ModelPackageData.MapRef ref, @Nullable float[] emission, int kind, float power) {
        float[] pow = null;
        if (power > 1f) {
            pow = new float[256];
            for (var i = 0; i < 256; i++) {
                pow[i] = (float)Math.pow(i / 255.0, power);
            }
        }
        var sw = src.getWidth();
        var sh = src.getHeight();
        var x0 = Math.max(0, (int)Math.floor(rect[0] * fx) - 1);
        var y0 = Math.max(0, (int)Math.floor(rect[1] * fy) - 1);
        var x1 = Math.min(lw, (int)Math.ceil((rect[0] + rect[2]) * fx) + 1);
        var y1 = Math.min(lh, (int)Math.ceil((rect[1] + rect[3]) * fy) + 1);
        var identity = ref.scale()[0] == 1f && ref.scale()[1] == 1f && ref.offset()[0] == 0f
            && ref.offset()[1] == 0f;
        for (var ty = y0; ty < y1; ty++) {
            var v0 = clamp01((ty / fy - rect[1]) / rect[3]);
            var v1 = clamp01(((ty + 1) / fy - rect[1]) / rect[3]);
            for (var tx = x0; tx < x1; tx++) {
                var u0 = clamp01((tx / fx - rect[0]) / rect[2]);
                var u1 = clamp01(((tx + 1) / fx - rect[0]) / rect[2]);
                int texel;
                if (identity) {
                    texel = average(src, sw, sh, u0, u1, v0, v1, emission, pow, power);
                } else {
                    // Unity uv = (u, 1 - v_glTF); its tiled value maps back to PNG row 1 - fract(t.y)
                    var tu = 0.5f * (u0 + u1) * ref.scale()[0] + ref.offset()[0];
                    var tv = (1f - 0.5f * (v0 + v1)) * ref.scale()[1] + ref.offset()[1];
                    var px = Math.min(sw - 1, (int)((tu - (float)Math.floor(tu)) * sw));
                    var py = Math.min(sh - 1, (int)((1f - (tv - (float)Math.floor(tv))) * sh));
                    var u = px / (float)sw;
                    var v = py / (float)sh;
                    texel = average(src, sw, sh, u, u, v, v, emission, null, 1f);
                }
                var at = base + (ty * lw + tx) * 4;
                dst.putInt(at, switch (kind) { // the masks layer keeps the other kinds' channels
                    case OUTLINE_MASK -> dst.getInt(at) & 0xFFFF0000 | texel & 0x0000FFFF;
                    case BROW_MASK -> dst.getInt(at) & 0xFF00FFFF | (texel & 0xFF) << 16;
                    case BASE_ALPHA -> dst.getInt(at) & 0x00FFFFFF | (texel & 0xFF) << 24;
                    default -> texel;
                });
            }
        }
    }

    private static int average(NativeImage src, int sw, int sh, float u0, float u1, float v0, float v1,
        @Nullable float[] emission, @Nullable float[] pow, float power) {
        var sx0 = Math.min(sw - 1, (int)(u0 * sw));
        var sx1 = Math.max(sx0 + 1, Math.min(sw, (int)Math.ceil(u1 * sw)));
        var sy0 = Math.min(sh - 1, (int)(v0 * sh));
        var sy1 = Math.max(sy0 + 1, Math.min(sh, (int)Math.ceil(v1 * sh)));
        var n = (sx1 - sx0) * (sy1 - sy0);
        if (emission != null) {
            float r = 0;
            float g = 0;
            float b = 0;
            for (var y = sy0; y < sy1; y++) {
                for (var x = sx0; x < sx1; x++) {
                    var p = src.getPixelRGBA(x, y);
                    r += SRGB_TO_LINEAR[p & 0xFF];
                    g += SRGB_TO_LINEAR[p >>> 8 & 0xFF];
                    b += SRGB_TO_LINEAR[p >>> 16 & 0xFF];
                }
            }
            return encodeEmission(r / n * emission[0], g / n * emission[1], b / n * emission[2]);
        }
        long r = 0;
        long g = 0;
        long b = 0;
        long a = 0;
        float gp = 0;
        float ap = 0;
        for (var y = sy0; y < sy1; y++) {
            for (var x = sx0; x < sx1; x++) {
                var p = src.getPixelRGBA(x, y);
                r += p & 0xFF;
                g += p >>> 8 & 0xFF;
                b += p >>> 16 & 0xFF;
                a += p >>> 24;
                if (pow != null) {
                    gp += pow[p >>> 8 & 0xFF];
                    ap += pow[p >>> 24];
                }
            }
        }
        var gOut = (int)((g + n / 2) / n);
        var aOut = (int)((a + n / 2) / n);
        if (pow != null) {
            gOut = Math.round((float)Math.pow(gp / n, 1.0 / power) * 255f);
            aOut = Math.round((float)Math.pow(ap / n, 1.0 / power) * 255f);
        }
        return rgba((int)((r + n / 2) / n), gOut, (int)((b + n / 2) / n), aOut);
    }

    /** rgb = sRGB(colour / max), a = log2(1 + max) / 8 (npr_maps.glsl nprEmission decodes it). */
    static int encodeEmission(float r, float g, float b) {
        var m = Math.max(r, Math.max(g, b));
        if (m <= 1e-5f) {
            return 0;
        }
        var a = Math.min(255, Math.round((float)(Math.log1p(m) / Math.log(2) / 8.0) * 255f));
        return rgba(srgb(r / m), srgb(g / m), srgb(b / m), a);
    }

    private static int srgb(float c) {
        c = clamp01(c);
        var s = c <= 0.0031308f ? c * 12.92f : 1.055f * (float)Math.pow(c, 1 / 2.4) - 0.055f;
        return Math.round(s * 255f);
    }

    /** Shelf-packs the tiled maps (each once, native size) into one sheet; fills each file's rect (texels). */
    private static NativeImage packSheet(Map<String, float[]> files, Map<String, NativeImage> images) {
        var area = 0L;
        var widest = 1;
        for (var file : files.keySet()) {
            var image = images.get(file);
            area += (long)image.getWidth() * image.getHeight();
            widest = Math.max(widest, image.getWidth());
        }
        var width = Math.max(widest, Integer.highestOneBit((int)Math.max(1, Math.ceil(Math.sqrt(area)))));
        var x = 0;
        var y = 0;
        var shelf = 0;
        for (var e : files.entrySet()) {
            var image = images.get(e.getKey());
            if (x + image.getWidth() > width) {
                x = 0;
                y += shelf;
                shelf = 0;
            }
            e.setValue(new float[] {x, y, image.getWidth(), image.getHeight()});
            x += image.getWidth();
            shelf = Math.max(shelf, image.getHeight());
        }
        var sheet = new NativeImage(width, Math.max(1, y + shelf), true);
        for (var e : files.entrySet()) {
            var image = images.get(e.getKey());
            var r = e.getValue();
            for (var sy = 0; sy < image.getHeight(); sy++) {
                for (var sx = 0; sx < image.getWidth(); sx++) {
                    sheet.setPixelRGBA((int)r[0] + sx, (int)r[1] + sy, image.getPixelRGBA(sx, sy));
                }
            }
        }
        return sheet;
    }

    /**
     * The material table: materials sharing one atlas rectangle become one entry (map flags OR'ed; tiled map,
     * tiling and rows 0-1 from the first with a tiled kind; rows 2-3 from the first). Entry layout (vec4 each):
     * 0 rect (atlas uv), 1 info (flags, tiled kind id or -1, tiled map texels w, h), 2 tiled _ST (scale, offset),
     * 3 sheet rect (uv), 4-7 parameter rows 0-3.
     */
    private static void table(ModelPackageData.Parsed p, Composite c, Map<String, float[]> sheetRects) {
        var rects = new ArrayList<float[]>();
        var entries = new ArrayList<List<ModelPackageData.Material>>();
        for (var m : p.materials().values()) {
            var at = -1;
            for (var i = 0; i < rects.size() && at < 0; i++) {
                at = Arrays.equals(rects.get(i), m.rect()) ? i : -1;
            }
            if (at < 0) {
                if (rects.size() == MAX_MATERIALS) {
                    EndfieldNpr.LOG.warn("Endfield NPR: package lists more than {} material regions; {} ignored",
                        MAX_MATERIALS, m.name());
                    continue;
                }
                rects.add(m.rect());
                entries.add(new ArrayList<>());
                at = rects.size() - 1;
            }
            entries.get(at).add(m);
        }
        for (var group : entries) {
            if (group.size() > 1) {
                EndfieldNpr.LOG.info("Endfield NPR: materials {} share one atlas region; merged per value (flags, "
                    + "outline width/offset/enable: max; other values: the region texture's owner, then listed order)",
                    group.stream().map(ModelPackageData.Material::name).toList());
            }
        }
        var aw = p.atlas()[0];
        var ah = p.atlas()[1];
        var sheetW = c.sheet != null ? c.sheet.getWidth() : 1;
        var sheetH = c.sheet != null ? c.sheet.getHeight() : 1;
        for (var i = 0; i < entries.size(); i++) {
            var o = i * 32;
            var r = rects.get(i);
            put(c.table, o, r[0] / aw, r[1] / ah, r[2] / aw, r[3] / ah);
            var flags = 0;
            ModelPackageData.Material tiled = null;
            var tiledKind = -1;
            for (var m : entries.get(i)) {
                for (var k = 0; k < ALIGNED.length; k++) {
                    flags |= m.maps().containsKey(ALIGNED[k]) ? 1 << k : 0;
                }
                flags |= m.params().containsKey("_AnisotropyIntensity") ? 1 << HAIR_SHADER_FLAG : 0;
                flags |= m.params().containsKey("_EyeHighLightColor") ? 1 << EYE_SHADER_FLAG : 0;
                flags |= m.params().containsKey("_SDFRimColor") ? 1 << SKIN_SHADER_FLAG : 0;
                flags |= p.faceRect() != null && Arrays.equals(m.rect(), p.faceRect()) ? 1 << FACE_FLAG : 0;
                for (var t = 0; t < TILED.length; t++) {
                    // the hair line exists without a map too (procedural stripes, _UseLineMap = 0 path, H2 1711-1718)
                    var has = m.maps().containsKey(TILED[t]) || t == 0 && m.params().containsKey("_LineAmount");
                    if (has) {
                        flags |= 1 << (TILED_BASE + t);
                        if (tiled == null) {
                            tiled = m;
                            tiledKind = t;
                        }
                    }
                }
            }
            // rows 2-3, per value: the first material of the region that has the property, the texture's owner (most
            // atlas-aligned maps: face_01 before the brows sharing its texture) first, then in listed order
            var ordered = new ArrayList<>(entries.get(i));
            ordered.sort(java.util.Comparator.comparingInt(NprMaps::alignedCount).reversed());
            put(c.table, o + 4, flags, tiledKind >= 0 ? TILED_BASE + tiledKind : -1, 0, 0);
            put(c.table, o + 8, 1, 1, 0, 0);
            if (tiled != null) {
                var ref = tiled.maps().get(TILED[tiledKind]);
                if (ref != null) {
                    var s = sheetRects.get(ref.file());
                    put(c.table, o + 4, flags, TILED_BASE + tiledKind, s[2], s[3]);
                    put(c.table, o + 8, ref.scale()[0], ref.scale()[1], ref.offset()[0], ref.offset()[1]);
                    put(c.table, o + 12, s[0] / sheetW, s[1] / sheetH, s[2] / sheetW, s[3] / sheetH);
                }
                var names = TILED_PARAMS[tiledKind];
                for (var j = 0; j < 8; j++) {
                    c.table[o + 16 + j] = value(List.of(tiled), names[j]);
                }
            }
            for (var row = 0; row < ROWS.length; row++) {
                for (var j = 0; j < 4; j++) {
                    c.table[o + 24 + row * 4 + j] = value(ordered, ROWS[row][j]);
                }
            }
            c.rain[i] = value(ordered, "_DisableRainEffectOnMaterial");
        }
        c.count = entries.size();
    }

    private static int alignedCount(ModelPackageData.Material m) {
        var n = 0;
        for (var kind : ALIGNED) {
            n += m.maps().containsKey(kind) ? 1 : 0;
        }
        return n;
    }

    /**
     * One {@link #TILED_PARAMS}/{@link #ROWS} expression over the materials of one region: the first material that
     * has every property it names ({@code >expr}: the maximum over all that have them), else its default.
     */
    static float value(List<ModelPackageData.Material> group, String expression) {
        if (expression.isEmpty()) {
            return 0f;
        }
        var max = expression.startsWith(">");
        if (max) {
            expression = expression.substring(1);
        }
        var fallback = 0f;
        var colon = expression.indexOf(':');
        if (colon >= 0) {
            fallback = Float.parseFloat(expression.substring(colon + 1));
            expression = expression.substring(0, colon);
        }
        var best = Float.NaN;
        for (var m : group) {
            var v = lookup(m, expression);
            if (Float.isNaN(v)) {
                continue;
            }
            if (!max) {
                return v;
            }
            best = Float.isNaN(best) ? v : Math.max(best, v);
        }
        return Float.isNaN(best) ? fallback : best;
    }

    /** An expression of one material, NaN when a property (or a {@code #kind} map) is missing. */
    private static float lookup(ModelPackageData.Material m, String expression) {
        var product = 1f;
        for (var factor : expression.split("\\*")) {
            if (factor.startsWith("#")) {
                if (!m.maps().containsKey(factor.substring(1))) {
                    return Float.NaN;
                }
                continue;
            }
            var component = 0;
            var dot = factor.indexOf('.');
            if (dot >= 0) {
                component = "rgba".indexOf(factor.charAt(dot + 1));
                component = component >= 0 ? component : "xyzw".indexOf(factor.charAt(dot + 1));
                factor = factor.substring(0, dot);
            }
            var v = m.params().get(factor);
            if (v == null || component < 0 || component >= v.length) {
                return Float.NaN;
            }
            product *= v[component];
        }
        return product;
    }

    private static void put(float[] t, int o, float x, float y, float z, float w) {
        t[o] = x;
        t[o + 1] = y;
        t[o + 2] = z;
        t[o + 3] = w;
    }

    // ---------------------------------------------------------------- render thread

    /** GL objects of a composite (render thread); frees its CPU memory. */
    static NprMaps upload(Composite c) {
        try {
            var array = 0;
            if (c.layers > 0 && c.pixels != null) {
                array = arrayTexture(c.width, c.height, c.layers, c.pixels);
            }
            DynamicTexture sheet = null;
            if (c.sheet != null) {
                sheet = NprTextures.upload(c.sheet); // owns the image now
                c.sheet = null;
            }
            return new NprMaps(array, sheet, c);
        } finally {
            c.close();
        }
    }

    private static int arrayTexture(int w, int h, int layers, @Nullable ByteBuffer pixels) {
        RenderSystem.assertOnRenderThread();
        var previous = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        var id = GL11.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, id);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // few levels: deeper mips would average neighbouring atlas regions (1 texel of padding at layer resolution)
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, MIP_LEVELS);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8, w, h, layers, 0, GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE, pixels);
        GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previous);
        return id;
    }

    void close() {
        if (array != 0) {
            RenderSystem.assertOnRenderThread();
            GL11.glDeleteTextures(array);
        }
        if (sheet != null) {
            sheet.close();
        }
    }

    /**
     * Before {@code shader.apply()}: the sheet on Sampler12 (or {@code fallback2d}, never sampled then) and the layer
     * table uniforms. Sampler11 itself is a 2D-array sampler that ShaderInstance cannot bind; {@link #afterApply}
     * binds it on the unit apply() gave it.
     */
    static void beforeApply(ShaderInstance shader, @Nullable NprMaps maps, int fallback2d) {
        shader.setSampler("Sampler12", maps != null && maps.sheet != null ? maps.sheet.getId() : fallback2d);
        var l = maps != null ? maps.layers : NO_LAYERS;
        shader.safeGetUniform("NprMapLayers0").set(l[0], l[1], l[2], l[3]);
        shader.safeGetUniform("NprMapLayers1").set(l[4], l[5], l[6], l[7]);
    }

    private static final float[] NO_LAYERS = {-1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f};

    /** After {@code shader.apply()} (program bound): the layer array on Sampler11's unit and the material table. */
    static void afterApply(ShaderInstance shader, @Nullable NprMaps maps) {
        var program = shader.getId();
        var loc = LOCATIONS.computeIfAbsent(shader, s -> new int[] {
            GL20.glGetUniformLocation(program, "Sampler11"), GL20.glGetUniformLocation(program, "NprMatCount"),
            GL20.glGetUniformLocation(program, "NprMatRect"), GL20.glGetUniformLocation(program, "NprMatInfo"),
            GL20.glGetUniformLocation(program, "NprMatTile"), GL20.glGetUniformLocation(program, "NprMatSub"),
            GL20.glGetUniformLocation(program, "NprMatParam"), GL20.glGetUniformLocation(program, "NprMatRain")});
        if (loc[0] >= 0) {
            var unit = GL20.glGetUniformi(program, loc[0]);
            var active = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, maps != null && maps.array != 0 ? maps.array : empty());
            GlStateManager._activeTexture(active);
        }
        var count = maps != null ? maps.count : 0;
        if (loc[1] >= 0) {
            GL20.glUniform1i(loc[1], count);
        }
        if (count == 0) {
            return;
        }
        for (var field = 0; field < 4; field++) {
            if (loc[2 + field] >= 0) {
                upload(loc[2 + field], maps.table, count, field, 1);
            }
        }
        if (loc[6] >= 0) {
            upload(loc[6], maps.table, count, 4, 4);
        }
        if (loc[7] >= 0) { // vec4[MAX_MATERIALS / 4], one value per entry
            UPLOAD.clear();
            UPLOAD.put(maps.rain, 0, MAX_MATERIALS);
            UPLOAD.flip();
            GL20.glUniform4fv(loc[7], UPLOAD);
        }
    }

    /** {@code rows} consecutive vec4 of every entry starting at vec4 {@code field}. */
    private static void upload(int location, float[] table, int count, int field, int rows) {
        UPLOAD.clear();
        for (var i = 0; i < count; i++) {
            UPLOAD.put(table, i * 32 + field * 4, rows * 4);
        }
        UPLOAD.flip();
        GL20.glUniform4fv(location, UPLOAD);
    }

    /** A 1x1x1 array for models without layers (a sampler2DArray must not read an incomplete texture). */
    static int empty() {
        if (emptyArray == 0) {
            var pixel = MemoryUtil.memAlloc(4);
            pixel.putInt(0, 0);
            emptyArray = arrayTexture(1, 1, 1, pixel);
            MemoryUtil.memFree(pixel);
        }
        return emptyArray;
    }

    private static float clamp01(float v) {
        return Math.min(1f, Math.max(0f, v));
    }

    private static int rgba(int r, int g, int b, int a) {
        return r | g << 8 | b << 16 | a << 24;
    }
}
