package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.proto.mixel.asset.model.data.MorphTargetProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SkinnedMeshProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringBonesProto;
import com.elfmcys.ysm.util.ProtoUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.jetbrains.annotations.Nullable;

/**
 * Import-time geometric LOD of a humanoid's main mesh (docs/LOD设计.md, section 2). Each level is an index buffer over
 * the main mesh's own vertices ({@link MeshSimplifier} only collapses onto existing vertices), so a level carries no
 * vertex data: weights, uvs and morph deltas of the vertices it keeps are the main mesh's.
 *
 * <p>The levels ride in the player data blob as {@code geo_models["mesh_lod"]} (YSM itself reads only "main" and
 * "arm"): a ZIP of {@code lod.json} (format, the mesh extent, per level its error as a share of the extent and its
 * counts, and the spring chain classes for the spring LOD) and {@code lod<N>.mesh}, a {@code SkinnedMeshProto} holding
 * only {@code triangles} and {@code outline_triangles}. The patched client bakes each level as a sibling of the main
 * geometry and picks one per extract by projected error.
 *
 * <p>What the levels keep: material borders (vertices shared by two materials never collapse across them; not on the
 * last level), eyes and the endfield_npr overlay cards (locked on every level), morph-driven vertices (locked on the
 * first level, protected after), the face and hair with a smaller error budget, and skin weights through a
 * deformation term: a collapse that makes a
 * region follow other bones costs the distance that region would move.
 */
final class MeshLod {
    static final String GEO_KEY = "mesh_lod";
    static final int FORMAT = 1;
    /** Error bounds of the levels as a share of the mesh extent (about the character's height). */
    private static final float[] LEVEL_ERRORS = {0.001f, 0.005f, 0.02f};
    /** A level is dropped unless it has at most this share of the previous level's triangles. */
    private static final float MIN_REDUCTION = 0.85f;
    /**
     * Joint rotation the skinning term assumes, radians. Tuned on the sample model: 0.5 kept 72% of the triangles at the first
     * level for deformation differences far below its 0.1% error, 0.15 keeps 57% and still stops collapses across
     * elbows, knees and the strands of hair at the coarser levels.
     */
    private static final float SKIN_ANGLE = 0.15f;
    /** Error factor (errors are squared: budget / 1.4) of the face, eyes, morph-driven vertices and hair. */
    private static final float IMPORTANT_FACTOR = 2f;
    private static final float NORMAL_WEIGHT = 1f;
    private static final float UV_WEIGHT = 2f;

    static final int CHAIN_PRIMARY = 0, CHAIN_SECONDARY = 1, CHAIN_ACCESSORY = 2;

    /** The main mesh as the importer holds it. */
    record Input(float[] positions, float[] normals, float[] uvs, int[] joints, float[] weights, int[] triangles,
        int[] outlineTriangles, byte[] triangleClasses, int[] vertexMaterials, int[] outlineHulls,
        List<MorphTargetProto> morphs, @Nullable SpringBonesProto springs, String[] boneNames, int[] boneParents,
        float[] bonePivots, @Nullable byte[] nprPackage) {
    }

    record Level(float error, int[] triangles, int[] outlineTriangles, int vertices) {
    }

    record Result(float extent, List<Level> levels, int[] chainClasses, long millis) {
    }

    private MeshLod() {
    }

    static Result build(Input in) throws IOException {
        var start = System.nanoTime();
        var vertexCount = in.positions.length / 3;
        var extent = MeshSimplifier.scale(in.positions, vertexCount);
        var levels = new ArrayList<Level>();
        if (in.triangles.length >= 3 * 64 && extent > 0) {
            var flags = vertexFlags(in, vertexCount, extent);
            var attributes = new float[vertexCount * 5];
            for (var i = 0; i < vertexCount; i++) {
                System.arraycopy(in.normals, i * 3, attributes, i * 5, 3);
                attributes[i * 5 + 3] = in.uvs[i * 2];
                attributes[i * 5 + 4] = in.uvs[i * 2 + 1];
            }
            float[] weights = {NORMAL_WEIGHT, NORMAL_WEIGHT, NORMAL_WEIGHT, UV_WEIGHT, UV_WEIGHT};
            var skin = skinMetric(in, vertexCount);
            var previous = in.triangles.length;
            for (var level = 0; level < LEVEL_ERRORS.length; level++) {
                var lock = new byte[vertexCount];
                var factor = new float[vertexCount];
                for (var i = 0; i < vertexCount; i++) {
                    lock[i] = flags.lock(level, i);
                    factor[i] = flags.factor(level, i);
                }
                var options = level == 0 ? 0 : MeshSimplifier.PERMISSIVE | MeshSimplifier.PRUNE;
                var destination = new int[in.triangles.length];
                var error = new float[1];
                var count = MeshSimplifier.simplify(destination, in.triangles, in.triangles.length, in.positions,
                    vertexCount, attributes, 5, weights, lock, factor, skin, in.triangles.length / 200 * 3,
                    LEVEL_ERRORS[level], options, error);
                if (count > previous * MIN_REDUCTION) {
                    continue;
                }
                var triangles = Arrays.copyOf(destination, count);
                levels.add(new Level(Math.max(error[0], 1e-6f), triangles, outline(in, triangles),
                    usedVertices(triangles, vertexCount)));
                previous = count;
            }
        }
        return new Result(extent, levels, chainClasses(in, vertexCount), (System.nanoTime() - start) / 1_000_000);
    }

    /** The sidecar entry, or null when no level is worth keeping and no chains need classes. */
    @Nullable
    static byte[] encode(Result result) throws IOException {
        if (result.levels.isEmpty()) {
            return null;
        }
        var json = new JsonObject();
        json.addProperty("format", FORMAT);
        json.addProperty("extent", result.extent);
        var levels = new JsonArray();
        for (var level : result.levels) {
            var entry = new JsonObject();
            entry.addProperty("error", level.error);
            entry.addProperty("triangles", level.triangles.length / 3);
            entry.addProperty("vertices", level.vertices);
            levels.add(entry);
        }
        json.add("levels", levels);
        var chains = new JsonArray();
        for (var chainClass : result.chainClasses) {
            chains.add(chainClass);
        }
        json.add("chains", chains);
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            put(zip, "lod.json", json.toString().getBytes(StandardCharsets.UTF_8));
            for (var i = 0; i < result.levels.size(); i++) {
                var level = result.levels.get(i);
                var mesh = SkinnedMeshProto.newBuilder().addTriangles(level.triangles)
                    .addOutlineTriangles(level.outlineTriangles).build();
                put(zip, "lod" + (i + 1) + ".mesh", ProtoUtil.serializeToArray(mesh));
            }
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        var entry = new ZipEntry(name);
        entry.setTime(0L); // same bytes for the same model: the data blob is content-hashed
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static int usedVertices(int[] triangles, int vertexCount) {
        var used = new java.util.BitSet(vertexCount);
        for (var index : triangles) {
            used.set(index);
        }
        return used.cardinality();
    }

    /** Outline hulls of a level: every level triangle whose corners all have hull copies drawn by the main outline. */
    private static int[] outline(Input in, int[] triangles) {
        if (in.outlineTriangles.length == 0 || in.outlineHulls == null) {
            return new int[0];
        }
        var drawn = new java.util.BitSet();
        for (var index : in.outlineTriangles) {
            drawn.set(index);
        }
        var out = new IntArrayList();
        for (var t = 0; t < triangles.length; t += 3) {
            int a = hull(in, triangles[t]), b = hull(in, triangles[t + 1]), c = hull(in, triangles[t + 2]);
            if (a >= 0 && b >= 0 && c >= 0 && drawn.get(a) && drawn.get(b) && drawn.get(c)) {
                // main triangles face along their normals, so the hull winds against them (HumanoidImport.emitOutlines)
                out.add(a);
                out.add(c);
                out.add(b);
            }
        }
        return out.toIntArray();
    }

    private static int hull(Input in, int vertex) {
        return vertex < in.outlineHulls.length ? in.outlineHulls[vertex] : -1;
    }

    /** Per-vertex locks and error factors of every level. */
    private record Flags(byte[] material, boolean[] eye, boolean[] morph, boolean[] card, boolean[] face,
        boolean[] hair) {
        byte lock(int level, int v) {
            // the last level (a figure some 50 px tall) lets material borders go: on models whose materials share
            // most positions (柚叶: 25k of 59k vertices) protecting them stops it at 42% of the triangles
            byte flags = level < 2 ? material[v] : 0;
            // eyes stay whole at every level (a few hundred vertices): unlocked, the coarsest level pruned them and
            // left empty sockets
            if (card[v] || eye[v] || level == 0 && morph[v]) {
                flags |= MeshSimplifier.VERTEX_LOCK;
            } else if (morph[v]) {
                flags |= MeshSimplifier.VERTEX_PROTECT;
            }
            return flags;
        }

        float factor(int level, int v) {
            var important = face[v] || hair[v] || eye[v] || morph[v];
            return important ? IMPORTANT_FACTOR : 1f;
        }
    }

    private static Flags vertexFlags(Input in, int vertexCount, float extent) throws IOException {
        var material = new byte[vertexCount];
        // vertices at one position with different materials: the material border never collapses across
        var first = new Long2IntOpenHashMap();
        first.defaultReturnValue(-1);
        var border = new boolean[vertexCount];
        for (var v = 0; v < vertexCount && v < in.vertexMaterials.length; v++) {
            var key = positionKey(in.positions, v);
            var other = first.putIfAbsent(key, v);
            if (other >= 0 && in.vertexMaterials[other] != in.vertexMaterials[v]) {
                border[v] = border[other] = true;
            }
        }
        for (var v = 0; v < vertexCount && v < in.vertexMaterials.length; v++) {
            if (border[v]) {
                material[v] = MeshSimplifier.VERTEX_PROTECT;
            }
        }
        // protect has to reach every wedge at a position
        for (var v = 0; v < vertexCount; v++) {
            var root = first.get(positionKey(in.positions, v));
            if (root >= 0 && border[root]) {
                material[v] = MeshSimplifier.VERTEX_PROTECT;
            }
        }
        var eye = new boolean[vertexCount];
        var face = new boolean[vertexCount];
        var hair = new boolean[vertexCount];
        for (var t = 0; t < in.triangles.length / 3 && t < in.triangleClasses.length; t++) {
            var type = in.triangleClasses[t];
            for (var c = 0; c < 3; c++) {
                var v = in.triangles[t * 3 + c];
                eye[v] |= type == SpecularAtlas.EYE;
                face[v] |= type == SpecularAtlas.FACE || type == SpecularAtlas.FACE_TONE;
                hair[v] |= type == SpecularAtlas.HAIR;
            }
        }
        var morph = new boolean[vertexCount];
        var threshold = 1e-4f * extent;
        for (var target : in.morphs) {
            for (var i = 0; i < target.vertices().size(); i++) {
                float dx = target.deltas().getFloat(i * 3), dy = target.deltas().getFloat(i * 3 + 1),
                    dz = target.deltas().getFloat(i * 3 + 2);
                if (dx * dx + dy * dy + dz * dz > threshold * threshold) {
                    morph[target.vertices().getInt(i)] = true;
                }
            }
        }
        var card = new boolean[vertexCount];
        for (var rect : cardRects(in.nprPackage)) {
            for (var t = 0; t < in.triangles.length; t += 3) {
                float u = 0, w = 0;
                for (var c = 0; c < 3; c++) {
                    u += in.uvs[in.triangles[t + c] * 2] / 3f;
                    w += in.uvs[in.triangles[t + c] * 2 + 1] / 3f;
                }
                if (u >= rect[0] && u <= rect[2] && w >= rect[1] && w <= rect[3]) {
                    for (var c = 0; c < 3; c++) {
                        card[in.triangles[t + c]] = true;
                    }
                }
            }
        }
        return new Flags(material, eye, morph, card, face, hair);
    }

    private static long positionKey(float[] positions, int v) {
        long x = Float.floatToRawIntBits(positions[v * 3] + 0f), y = Float.floatToRawIntBits(positions[v * 3 + 1] + 0f),
            z = Float.floatToRawIntBits(positions[v * 3 + 2] + 0f);
        return x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z;
    }

    /**
     * The endfield_npr overlay cards' atlas rectangles as normalized {u0, v0, u1, v1}: the render mod finds the card
     * quads by their uvs (CpuMesh.cardRuns), so every level keeps them whole.
     */
    static List<float[]> cardRects(@Nullable byte[] nprPackage) throws IOException {
        var rects = new ArrayList<float[]>();
        if (nprPackage == null) {
            return rects;
        }
        JsonObject profile = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(nprPackage))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("profile.json")) {
                    profile = JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                }
            }
        }
        if (profile == null || !profile.has("atlas") || !profile.has("overlays")) {
            return rects;
        }
        var atlas = profile.getAsJsonArray("atlas");
        float width = atlas.get(0).getAsFloat(), height = atlas.get(1).getAsFloat();
        for (JsonElement overlay : profile.getAsJsonArray("overlays")) {
            var card = overlay.getAsJsonObject().getAsJsonObject("card");
            if (card == null || !card.has("rect")) {
                continue;
            }
            var r = card.getAsJsonArray("rect");
            float x = r.get(0).getAsFloat(), y = r.get(1).getAsFloat(), w = r.get(2).getAsFloat(),
                h = r.get(3).getAsFloat();
            rects.add(new float[] {x / width, y / height, (x + w) / width, (y + h) / height});
        }
        return rects;
    }

    /** Deformation error when {@code from} takes {@code to}'s skin: weight change times lever arm times angle. */
    private static MeshSimplifier.SkinMetric skinMetric(Input in, int vertexCount) {
        var arm = new float[vertexCount];
        for (var v = 0; v < vertexCount; v++) {
            var dominant = 0;
            for (var k = 1; k < 4; k++) {
                if (in.weights[v * 4 + k] > in.weights[v * 4 + dominant]) {
                    dominant = k;
                }
            }
            var bone = in.joints[v * 4 + dominant];
            float dx = in.positions[v * 3] - in.bonePivots[bone * 3] / 16f,
                dy = in.positions[v * 3 + 1] - in.bonePivots[bone * 3 + 1] / 16f,
                dz = in.positions[v * 3 + 2] - in.bonePivots[bone * 3 + 2] / 16f;
            arm[v] = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return (from, to) -> {
            var change = 0f;
            for (var k = 0; k < 4; k++) {
                var joint = in.joints[from * 4 + k];
                var weight = in.weights[from * 4 + k];
                if (weight > 0) {
                    change += Math.abs(weight - weightOf(in, to, joint));
                }
                var otherJoint = in.joints[to * 4 + k];
                var otherWeight = in.weights[to * 4 + k];
                if (otherWeight > 0 && weightOf(in, from, otherJoint) == 0) {
                    change += otherWeight;
                }
            }
            var d = 0.5f * change * arm[from] * SKIN_ANGLE;
            return d * d;
        };
    }

    private static float weightOf(Input in, int vertex, int joint) {
        var total = 0f;
        for (var k = 0; k < 4; k++) {
            if (in.joints[vertex * 4 + k] == joint) {
                total += in.weights[vertex * 4 + k];
            }
        }
        return total;
    }

    /**
     * Spring chain classes for the spring LOD: chains ranked by the skinned area they move. The largest ones up to 80%
     * of the moved area are primary (main hair, skirt), chains under 1% are accessories, the rest secondary.
     */
    static int[] chainClasses(Input in, int vertexCount) {
        if (in.springs == null || in.springs.chains().isEmpty()) {
            return new int[0];
        }
        var chainCount = in.springs.chains().size();
        var boneCount = in.boneNames.length;
        var boneChain = new int[boneCount];
        Arrays.fill(boneChain, -1);
        for (var joint : in.springs.joints()) {
            boneChain[joint.bone()] = joint.chain();
        }
        // bones below a joint bone without a joint of their own (tails, attached meshes) move with that chain
        for (var b = 0; b < boneCount; b++) {
            if (boneChain[b] < 0) {
                for (var p = in.boneParents[b]; p >= 0; p = in.boneParents[p]) {
                    if (boneChain[p] >= 0) {
                        boneChain[b] = boneChain[p];
                        break;
                    }
                }
            }
        }
        var area = new double[chainCount];
        for (var t = 0; t < in.triangles.length; t += 3) {
            int a = in.triangles[t], b = in.triangles[t + 1], c = in.triangles[t + 2];
            float ux = in.positions[b * 3] - in.positions[a * 3], uy = in.positions[b * 3 + 1] - in.positions[a * 3 + 1],
                uz = in.positions[b * 3 + 2] - in.positions[a * 3 + 2];
            float vx = in.positions[c * 3] - in.positions[a * 3], vy = in.positions[c * 3 + 1] - in.positions[a * 3 + 1],
                vz = in.positions[c * 3 + 2] - in.positions[a * 3 + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            var triangleArea = Math.sqrt(nx * nx + ny * ny + nz * nz) / 6.0; // a third per corner
            for (var corner : new int[] {a, b, c}) {
                for (var k = 0; k < 4; k++) {
                    var joint = in.joints[corner * 4 + k];
                    if (joint < boneCount && boneChain[joint] >= 0 && boneChain[joint] < chainCount) {
                        area[boneChain[joint]] += triangleArea * in.weights[corner * 4 + k];
                    }
                }
            }
        }
        var total = Arrays.stream(area).sum();
        var order = new Integer[chainCount];
        for (var i = 0; i < chainCount; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (x, y) -> Double.compare(area[y], area[x]));
        var classes = new int[chainCount];
        var covered = 0.0;
        for (var rank = 0; rank < chainCount; rank++) {
            var chain = order[rank];
            if (rank == 0 || covered < 0.8 * total) {
                classes[chain] = CHAIN_PRIMARY;
            } else if (area[chain] < 0.01 * total) {
                classes[chain] = CHAIN_ACCESSORY;
            } else {
                classes[chain] = CHAIN_SECONDARY;
            }
            covered += area[chain];
        }
        return classes;
    }
}
