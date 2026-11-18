package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.util.Closeable;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * Format-neutral humanoid read from VRM, PMX or PMD: a node tree with rest positions and a triangle mesh already
 * skinned into the rest pose. Space is right-handed, Y up, meters; facing is resolved later.
 */
final class HumanoidSource implements Closeable {
    static final int OPAQUE = 0;
    static final int MASK = 1;
    /** Partial alpha; imports dither it to cutout coverage (see {@link TextureAtlas}). */
    static final int BLEND = 2;

    /** Joint value for vertices attached to the synthetic YSM root. */
    static final int ROOT_JOINT = -1;

    /** @param outline the toon outline drawn around this material's surfaces, or null for none */
    record Material(String name, @Nullable HumanoidTexture texture, float r, float g, float b, float a, int alphaMode,
        float cutoff, @Nullable Outline outline) {
    }

    /**
     * Inverted-hull outline: the surface pushed out along its normal by width meters and drawn from the inside in a
     * solid color, so only a rim shows around the silhouette and creases.
     */
    record Outline(float r, float g, float b, float width) {

        /** Thinner outlines vanish at the distances a player model is seen from; thicker ones look like a shell. */
        static final float MIN_WIDTH = 0.002f;
        static final float MAX_WIDTH = 0.012f;

        static Outline of(float r, float g, float b, float width) {
            return new Outline(clamp01(r), clamp01(g), clamp01(b), Math.min(Math.max(width, MIN_WIDTH), MAX_WIDTH));
        }

        private static float clamp01(float value) {
            return Float.isFinite(value) ? Math.min(Math.max(value, 0f), 1f) : 0f;
        }
    }

    record Metadata(String name, List<String> authors, String license) {
    }

    /**
     * A simulated bone: it swings about its rest position so that it points at a tail that follows inertia, gravity and
     * stiffness. tail and the gravity direction are in rest space; gravity (0, -1, 0) is world down.
     */
    record SpringJoint(int node, Vector3f tail, float hitRadius, float stiffness, float drag, float gravityPower,
        Vector3f gravity, int chain) {
    }

    /** Capsule from head to tail in rest space carried by a node; a sphere when both coincide. */
    record SpringCollider(int node, Vector3f head, Vector3f tail, float radius) {
    }

    /**
     * A facial expression or other shape key: rest-space vertex offsets at full weight, summed when several sources
     * move the same vertex. Names follow VRM 1.0 presets where the source has an equivalent (blink, aa, happy, ...).
     */
    static final class Expression {
        final String name;
        final IntArrayList vertices = new IntArrayList();
        final FloatArrayList deltas = new FloatArrayList();
        private final Int2IntOpenHashMap slots = new Int2IntOpenHashMap();

        Expression(String name) {
            this.name = name;
            slots.defaultReturnValue(-1);
        }

        void add(int vertex, float x, float y, float z) {
            if (x == 0f && y == 0f && z == 0f) {
                return;
            }
            var slot = slots.get(vertex);
            if (slot < 0) {
                slots.put(vertex, vertices.size());
                vertices.add(vertex);
                deltas.add(x);
                deltas.add(y);
                deltas.add(z);
            } else {
                deltas.set(slot * 3, deltas.getFloat(slot * 3) + x);
                deltas.set(slot * 3 + 1, deltas.getFloat(slot * 3 + 1) + y);
                deltas.set(slot * 3 + 2, deltas.getFloat(slot * 3 + 2) + z);
            }
        }
    }

    /** Default rendered height in blocks, feet to the top of the mesh: two blocks, like a tall player model. */
    static final float DEFAULT_HEIGHT = 2f;
    /** Rendered height in blocks; humanoid.json may set it. */
    float height = DEFAULT_HEIGHT;
    /**
     * Default scale of held items. Items keep the default model's size, sized for its blocky limbs; at two blocks tall
     * a humanoid's limbs are far slimmer, and full-size items look oversized in its hands.
     */
    static final float DEFAULT_ITEM_SCALE = 0.7f;
    /** Scale of held items; humanoid.json may set it. */
    float itemScale = DEFAULT_ITEM_SCALE;
    /**
     * Whether the model's own toon outlines (PMX edges, MToon outlines) become inverted hulls; humanoid.json may turn
     * them on. Off by default: the stylized-PBR shading separates the figure with rim light instead.
     */
    boolean outline;

    final List<String> nodeNames = new ArrayList<>();
    final IntArrayList parents = new IntArrayList();
    /** Parent in the YSM skeleton; differs from the source parent where a constraint drives the node. */
    final IntArrayList skeletonParents = new IntArrayList();
    final FloatArrayList nodePositions = new FloatArrayList();

    /**
     * VRM humanoid bone names to nodes. Required: hips, spine, head and the upper/lower arm, hand, upper/lower leg and
     * foot of both sides. Optional: neck, chest, shoulders, and lowerBody (a pelvis bone between hips and legs).
     */
    final Map<String, Integer> humanoid = new HashMap<>();

    final FloatArrayList positions = new FloatArrayList();
    final FloatArrayList normals = new FloatArrayList();
    final FloatArrayList uvs = new FloatArrayList();
    /** Four node indices per vertex; {@link #ROOT_JOINT} where weight is zero or the vertex is unparented. */
    final IntArrayList joints = new IntArrayList();
    final FloatArrayList weights = new FloatArrayList();
    final IntArrayList triangles = new IntArrayList();
    final IntArrayList triangleMaterials = new IntArrayList();
    final List<Material> materials = new ArrayList<>();
    /** Per-vertex outline width factor (PMX edge scale); empty means 1 for every vertex. */
    final FloatArrayList edgeScales = new FloatArrayList();
    Metadata metadata = new Metadata("", List.of(), "");

    /** At most one joint per node. */
    final List<SpringJoint> springJoints = new ArrayList<>();
    final List<SpringCollider> springColliders = new ArrayList<>();
    /** Per chain, the {@link #springColliders} indices its joints collide with. */
    final List<int[]> springChains = new ArrayList<>();

    /** Unique names; empty expressions are dropped on import. */
    final List<Expression> expressions = new ArrayList<>();

    /** The expression of that name, created on first use. */
    Expression expression(String name) {
        for (var expression : expressions) {
            if (expression.name.equals(name)) {
                return expression;
            }
        }
        var created = new Expression(name);
        expressions.add(created);
        return created;
    }

    int addNode(String name, int parent, float x, float y, float z) {
        nodeNames.add(name);
        parents.add(parent);
        skeletonParents.add(parent);
        nodePositions.add(x);
        nodePositions.add(y);
        nodePositions.add(z);
        return nodeNames.size() - 1;
    }

    int nodeCount() {
        return nodeNames.size();
    }

    int vertexCount() {
        return positions.size() / 3;
    }

    @Override
    public void close() {
        var closed = new IdentityHashMap<HumanoidTexture, Boolean>();
        for (var material : materials) {
            if (material.texture() != null && closed.put(material.texture(), Boolean.TRUE) == null) {
                material.texture().close();
            }
        }
    }
}
