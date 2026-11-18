package com.elfmcys.ysm.client.lod;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.api.rendering.event.RenderModelEvent;
import com.elfmcys.ysm.geckolib3.geo.animated.GeoModelState;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.natives.render.NativeBakedModel;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SkinnedMeshProto;
import com.elfmcys.ysm.util.ProtoUtil;
import com.google.common.collect.MapMaker;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Client side of the geometric and spring LOD (docs/LOD设计.md): the importer's {@code geo_models["mesh_lod"]} sidecar
 * (format.parser.MeshLod) holds index-only levels of the main mesh. Each level is baked as a sibling of the main
 * geometry (same bones, springs and vertices, other triangles), and every extraction of a level-pass model state hands
 * native the baked model of the state's current level: native accepts another baked model per extraction, and the
 * patched solver keeps the springs when the new model's spring table is the same.
 *
 * <ul>
 * <li>{@code ModelRenderTargetLoader.geoModel} returns through {@link #registerGeo}: remembers the sidecar of the
 * definition a "main" proto came from;</li>
 * <li>{@code ModelRenderTargetLoader$DefaultBuildStage.bakeModel} returns through {@link #afterBake}: bakes the levels
 * through the same method (disk cache, resident and cache-only paths alike) as {@code <resource>#lod<N>};</li>
 * <li>{@code GeoModelState.extract} asks {@link #bakedModel} instead of {@code GeoModel.bakedModel()};</li>
 * <li>{@code GeoReplacedEntityRenderer.renderPrepared} passes each RenderModelEvent to {@link #observe} before posting
 * it: the level pass's projected size picks the level and the spring stage;</li>
 * <li>{@code AnimationParallelTicker.scheduleAll} calls {@link #beginFrame} before the frame's extractions: last
 * frame's choices take effect, refinements first for the largest characters, at most {@code ysm.lod.refines} per
 * frame.</li>
 * </ul>
 *
 * <p>The published model of a state stays the main GeoModel, so identity checks downstream (endfield_npr's capture and
 * animation LOD, fitbox) see one model at every level; the vertex count is the level's. The local player, GUI and
 * paper-doll states are always level 0 with every spring chain simulated.
 */
public final class MeshLodRuntime {
    /** Added to GeoModelState by LodPatch: one field for the state's {@link State}. */
    public interface Holder {
        Object ysm$lod();

        void ysm$setLod(Object value);
    }

    static final String GEO_KEY = "mesh_lod";
    private static final String LEVEL_SUFFIX = "#lod";

    private static final boolean ENABLED = !"false".equals(System.getProperty("ysm.lod"));
    private static final boolean SPRINGS = !"false".equals(System.getProperty("ysm.lod.springs"));
    /** Allowed geometric error on screen, pixels. */
    private static final float TAU = floatProperty("ysm.lod.tau", 0.5f);
    /** Characters at least this share of the screen height stay at level 0 (endfield_npr's FULL tier). */
    private static final float LOD0_SIZE = floatProperty("ysm.lod.lod0Size", 0.10f);
    private static final float HYSTERESIS = 0.15f;
    /** Frames a state keeps its level before it may get coarser; finer levels apply at once (within the cap). */
    private static final int COARSEN_DWELL = 15;
    private static final int REFINES_PER_FRAME = (int)floatProperty("ysm.lod.refines", 4);
    /** Spring stages S1-S3 below these shares of the screen height (endfield_npr's animLodSizes). */
    private static final float[] STAGE_SIZES = {0.12f, 0.06f, 0.03f};
    /** A level-pass state not drawn for this long is off screen: every chain holds. */
    private static final long OFFSCREEN_NANOS = 250_000_000L;
    /** Per spring stage, the chain classes (primary, secondary, accessory) that hold. */
    private static final boolean[][] STAGE_HOLDS = {
        {false, false, false}, {false, false, true}, {false, true, true}, {true, true, true}};

    private static final ConcurrentMap<GeoModelProto, ByteBuffer> SIDECARS = new MapMaker().weakKeys().makeMap();
    private static final ConcurrentMap<GeoModel, Levels> LEVELS = new MapMaker().weakKeys().makeMap();
    private static final ArrayList<State> OBSERVED = new ArrayList<>();
    private static final ArrayList<State> REFINES = new ArrayList<>();
    private static long frame;
    @Nullable
    private static volatile Method bakeMethod;

    /** Baked levels of one main geometry: levels[i] is LOD i+1. */
    record Levels(NativeBakedModel[] models, float[] errors, byte[] chainClasses) {
    }

    /** LOD state of one GeoModelState. */
    static final class State {
        /** Level the next extraction uses (render thread writes, workers read). */
        volatile int level;
        /** Level the latest extraction used. */
        volatile int extracted;
        /** Spring stage S0-S3 chosen in the level pass. */
        volatile int stage;
        volatile boolean local;
        volatile long seenNanos;
        // render thread only
        int want;
        float size;
        int dwell;
        long observedFrame = -1;
        // extracting thread only (extractions of one state never overlap)
        int appliedStage = -1;
        @Nullable
        byte[] modes;
    }

    private MeshLodRuntime() {
    }

    private static float floatProperty(String name, float fallback) {
        try {
            var value = System.getProperty(name);
            return value == null ? fallback : Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@code ModelRenderTargetLoader.geoModel(source, name)} result: remembers the sidecar beside "main". */
    public static GeoModelProto registerGeo(GeoModelProto proto, ModelDataProto source, String name) {
        if (ENABLED && "main".equals(name)) {
            var sidecar = source.geoModels().get(GEO_KEY);
            if (sidecar != null) {
                SIDECARS.put(proto, sidecar);
            }
        }
        return proto;
    }

    /**
     * {@code DefaultBuildStage.bakeModel} result: bakes the levels of a main geometry that came with a sidecar, by
     * calling the same bakeModel with each level's proto under {@code <resource>#lod<N>}. A level that fails (e.g. not
     * in the disk cache on a cache-only load) leaves the model without levels: it renders at full detail.
     */
    public static GeoModel afterBake(GeoModel model, Object stage, Object cache, Object representation,
        String resourceName, byte[] textureHash, GeoModelProto geo, Object texturePixels, Object target,
        Object textureProto, boolean defaultModel, boolean cacheOnly) {
        if (model == null || resourceName.contains(LEVEL_SUFFIX)) {
            return model;
        }
        var sidecar = SIDECARS.remove(geo);
        if (sidecar == null) {
            return model;
        }
        var siblings = new ArrayList<GeoModel>();
        try {
            var decoded = decode(sidecar);
            var mesh = SkinnedMeshProto.parseFrom(bytes(geo.mesh()));
            var models = new NativeBakedModel[decoded.meshes.length];
            var method = bakeMethod(stage);
            for (var i = 0; i < models.length; i++) {
                var levelMesh = mesh.toBuilder().clearTriangles().addAllTriangles(decoded.meshes[i].triangles())
                    .clearOutlineTriangles().addAllOutlineTriangles(decoded.meshes[i].outlineTriangles()).build();
                var levelGeo = geo.withMesh(ByteBuffer.wrap(ProtoUtil.serializeToArray(levelMesh)));
                var sibling = (GeoModel)method.invoke(stage, cache, representation, resourceName + LEVEL_SUFFIX + (i + 1),
                    textureHash, levelGeo, texturePixels, target, textureProto, defaultModel, cacheOnly);
                siblings.add(sibling);
                if (sibling.sortedBones().size() != model.sortedBones().size()) {
                    throw new IOException("level " + (i + 1) + " bakes " + sibling.sortedBones().size() + " bones, main "
                        + model.sortedBones().size());
                }
                models[i] = sibling.bakedModel(); // shared: native memory lives while this reference does
            }
            LEVELS.put(model, new Levels(models, decoded.errors, decoded.chainClasses));
        } catch (InvocationTargetException e) {
            YesSteveModel.LOGGER.warn("[YSM] mesh LOD of {} not baked: {}", resourceName, e.getCause().toString());
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            YesSteveModel.LOGGER.warn("[YSM] mesh LOD of {} not baked: {}", resourceName, e.toString());
        } finally {
            // the siblings only lend their baked models
            for (var sibling : siblings) {
                sibling.close();
            }
        }
        return model;
    }

    private static Method bakeMethod(Object stage) throws NoSuchMethodException {
        var method = bakeMethod;
        if (method == null) {
            for (var candidate : stage.getClass().getDeclaredMethods()) {
                if (candidate.getName().equals("bakeModel") && candidate.getParameterCount() == 10) {
                    method = candidate;
                }
            }
            if (method == null) {
                throw new NoSuchMethodException("bakeModel");
            }
            method.setAccessible(true);
            bakeMethod = method;
        }
        return method;
    }

    private record Decoded(SkinnedMeshProto[] meshes, float[] errors, byte[] chainClasses) {
    }

    private static Decoded decode(ByteBuffer sidecar) throws IOException {
        JsonObject json = null;
        var meshes = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes(sidecar)))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("lod.json")) {
                    json = JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                } else {
                    meshes.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        if (json == null || json.get("format").getAsInt() != 1) {
            throw new IOException("unsupported mesh_lod sidecar");
        }
        var levels = json.getAsJsonArray("levels");
        var result = new SkinnedMeshProto[levels.size()];
        var errors = new float[levels.size()];
        for (var i = 0; i < result.length; i++) {
            var data = meshes.get("lod" + (i + 1) + ".mesh");
            if (data == null) {
                throw new IOException("lod" + (i + 1) + ".mesh missing");
            }
            result[i] = SkinnedMeshProto.parseFrom(data);
            errors[i] = levels.get(i).getAsJsonObject().get("error").getAsFloat();
        }
        var chains = json.has("chains") ? json.getAsJsonArray("chains") : null;
        var classes = new byte[chains == null ? 0 : chains.size()];
        for (var i = 0; i < classes.length; i++) {
            classes[i] = (byte)Math.min(Math.max(chains.get(i).getAsInt(), 0), 2);
        }
        return new Decoded(result, errors, classes);
    }

    private static byte[] bytes(ByteBuffer buffer) {
        var out = new byte[buffer.remaining()];
        buffer.duplicate().get(out);
        return out;
    }

    private static State state(Object holder) {
        var h = (Holder)holder;
        var state = h.ysm$lod();
        if (state == null) {
            synchronized (h) {
                state = h.ysm$lod();
                if (state == null) {
                    state = new State();
                    h.ysm$setLod(state);
                }
            }
        }
        return (State)state;
    }

    /**
     * The baked model {@code GeoModelState.extract} hands native: the state's current level of the main geometry, and
     * the spring chain modes of its stage set first.
     */
    public static NativeBakedModel bakedModel(GeoModel model, GeoModelState geoState) {
        var base = model.bakedModel();
        if (!ENABLED) {
            return base;
        }
        var levels = LEVELS.get(model);
        if (levels == null) {
            return base;
        }
        var state = state(geoState);
        if (SPRINGS && levels.chainClasses.length > 0) {
            applySprings(state, levels, geoState);
        }
        var level = Math.min(state.level, levels.models.length);
        state.extracted = level;
        return level == 0 ? base : levels.models[level - 1];
    }

    private static void applySprings(State state, Levels levels, GeoModelState geoState) {
        var stage = state.local || state.observedFrame < 0 ? 0 : state.stage;
        if (!state.local && state.observedFrame >= 0 && System.nanoTime() - state.seenNanos > OFFSCREEN_NANOS) {
            stage = 3;
        }
        if (stage == state.appliedStage) {
            return;
        }
        var classes = levels.chainClasses;
        var modes = state.modes != null && state.modes.length == classes.length ? state.modes : new byte[classes.length];
        var holds = STAGE_HOLDS[stage];
        for (var chain = 0; chain < classes.length; chain++) {
            modes[chain] = (byte)(holds[classes[chain]] ? 1 : 0);
        }
        state.modes = modes;
        if (geoState.getNativeState().setSpringModes(modes)) {
            state.appliedStage = stage;
        }
    }

    /** A RenderModelEvent about to be posted: level-pass draws choose their state's level and spring stage. */
    public static void observe(Object posted) {
        if (!ENABLED || !(posted instanceof RenderModelEvent event) || !(event.target() instanceof Entity entity)) {
            return;
        }
        var data = event.renderData();
        var ctx = data.ctx;
        if (ctx == null || !ctx.immutable() || !ctx.level() || ctx.irisShadow() || ctx.firstPersonMod()
            || ctx.inventory() || ctx.paperDoll() || ctx.offScreen()) {
            return;
        }
        var model = data.modelState.getModel();
        var levels = model == null ? null : LEVELS.get(model);
        if (levels == null) {
            return;
        }
        var state = state(data.modelState);
        var mc = Minecraft.m_91087_();
        state.seenNanos = System.nanoTime();
        state.local = entity == mc.f_91074_;
        if (state.observedFrame == frame) {
            return; // second level-pass draw of the same state this frame
        }
        state.observedFrame = frame;
        var cam = mc.f_91063_.m_109153_().m_90583_();
        var pos = entity.m_20318_(mc.m_91296_());
        var height = entity.m_20206_();
        double dx = pos.f_82479_ - cam.f_82479_, dy = pos.f_82480_ + height * 0.5 - cam.f_82480_,
            dz = pos.f_82481_ - cam.f_82481_;
        var distance = Math.max(Math.sqrt(dx * dx + dy * dy + dz * dz), 0.05);
        // projected height as a share of the screen height, with this frame's (zoomed) projection
        var size = (float)(height * RenderSystem.getProjectionMatrix().m11() / (2.0 * distance));
        state.size = size;
        var pixels = size * mc.m_91268_().m_85442_();
        var current = state.level;
        var want = 0;
        var debug = debugLevel();
        if (debug >= 0) {
            want = Math.min(debug, levels.models.length);
        } else if (!state.local && size < LOD0_SIZE * (current == 0 ? 1f - HYSTERESIS : 1f + HYSTERESIS)) {
            for (var i = levels.models.length; i >= 1; i--) {
                var allowed = TAU * (i <= current ? 1f + HYSTERESIS : 1f - HYSTERESIS);
                if (levels.errors[i - 1] * pixels <= allowed) {
                    want = i;
                    break;
                }
            }
        }
        state.want = want;
        state.stage = state.local ? 0 : stage(size, state.stage);
        OBSERVED.add(state);
    }

    private static int stage(float size, int current) {
        var stage = 0;
        for (var i = 0; i < STAGE_SIZES.length; i++) {
            // deeper stages need the size below the bound by the margin, and keep it until above it by the margin
            var bound = STAGE_SIZES[i] * (i + 1 <= current ? 1f + HYSTERESIS : 1f - HYSTERESIS);
            if (size < bound) {
                stage = i + 1;
            }
        }
        return stage;
    }

    /** -1, or the level every observed state uses: {@code ysm.lod.debugLevel}, or cycling every {@code ysm.lod.debugCycle} s. */
    private static int debugLevel() {
        var cycle = floatProperty("ysm.lod.debugCycle", 0f);
        if (cycle > 0) {
            return (int)(System.nanoTime() / (long)(cycle * 1e9) % 4);
        }
        return (int)floatProperty("ysm.lod.debugLevel", -1f);
    }

    /** Start of a frame (render thread, before the frame's extractions): last frame's choices take effect. */
    public static void beginFrame() {
        frame++;
        if (OBSERVED.isEmpty()) {
            return;
        }
        for (var state : OBSERVED) {
            state.dwell++;
            if (state.want > state.level) {
                if (state.dwell >= COARSEN_DWELL || debugLevel() >= 0) {
                    state.level = state.want;
                    state.dwell = 0;
                }
            } else if (state.want < state.level) {
                REFINES.add(state);
            }
        }
        if (!REFINES.isEmpty()) {
            REFINES.sort((a, b) -> Float.compare(b.size, a.size));
            for (var i = 0; i < REFINES.size(); i++) {
                var state = REFINES.get(i);
                if (i < REFINES_PER_FRAME || state.local) {
                    state.level = state.want;
                    state.dwell = 0;
                }
            }
            REFINES.clear();
        }
        OBSERVED.clear();
    }

    /** The level the state's latest extraction used (0 = full mesh), for render mods. */
    public static int level(GeoModelState geoState) {
        var state = ((Holder)(Object)geoState).ysm$lod();
        return state == null ? 0 : ((State)state).extracted;
    }

    /**
     * Whether the state's next extraction switches levels: a render mod that holds back extractions (endfield_npr's
     * animation LOD) lets this one through, so the switch is not delayed by its interval.
     */
    public static boolean switchPending(GeoModelState geoState) {
        var state = ((Holder)(Object)geoState).ysm$lod();
        return state != null && ((State)state).level != ((State)state).extracted;
    }

    /** Levels baked for a main geometry (0 without a sidecar). */
    public static int levelCount(GeoModel model) {
        var levels = model == null ? null : LEVELS.get(model);
        return levels == null ? 0 : levels.models.length;
    }
}
