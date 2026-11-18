package com.elfmcys.ysm.format.parser;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Reads PMX 2.0 / 2.1 into a {@link HumanoidSource}. MMD space is left-handed in units of 8 cm; Z is mirrored. Bones
 * that copy another bone's full rotation (付与 ratio 1, such as 足D) stand for that bone: their weights move to it, and in
 * the YSM skeleton their children hang from it, so detail bones under the D chain (足先EX, ひざ補助) follow the animated leg.
 * Rigid bodies and their joints become spring bones ({@link MmdPhysics}).
 */
final class PmxReader {
    private static final int APPEND_ROTATION = 0x0100;
    private static final int APPEND_TRANSLATION = 0x0200;

    private final ByteBuffer buffer;
    private Charset charset = StandardCharsets.UTF_16LE;
    private int vertexIndexSize;
    private int textureIndexSize;
    private int boneIndexSize;
    private int materialIndexSize;
    private int morphIndexSize;
    private int rigidIndexSize;

    private PmxReader(ByteBuffer data) {
        this.buffer = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    }

    static HumanoidSource read(ByteBuffer data, HumanoidImport.TextureResolver textures) throws IOException {
        var source = new HumanoidSource();
        try {
            new PmxReader(data).read(source, textures);
            return source;
        } catch (BufferUnderflowException | IndexOutOfBoundsException truncated) {
            source.close();
            throw new IOException("Truncated PMX file", truncated);
        } catch (IOException | RuntimeException error) {
            source.close();
            throw error;
        }
    }

    private void read(HumanoidSource source, HumanoidImport.TextureResolver textures) throws IOException {
        if (buffer.remaining() < 9 || buffer.get() != 'P' || buffer.get() != 'M' || buffer.get() != 'X'
            || buffer.get() != ' ') {
            throw new IOException("Not a PMX file");
        }
        var version = buffer.getFloat();
        if (version < 2.0f || version > 2.1f) {
            throw new IOException("Unsupported PMX version " + version);
        }
        var globalCount = Byte.toUnsignedInt(buffer.get());
        if (globalCount < 8) {
            throw new IOException("Invalid PMX header");
        }
        var globals = new int[globalCount];
        for (var i = 0; i < globalCount; i++) {
            globals[i] = Byte.toUnsignedInt(buffer.get());
        }
        charset = globals[0] == 1 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE;
        var additionalUv = globals[1];
        vertexIndexSize = indexSize(globals[2]);
        textureIndexSize = indexSize(globals[3]);
        materialIndexSize = indexSize(globals[4]);
        boneIndexSize = indexSize(globals[5]);
        morphIndexSize = indexSize(globals[6]);
        rigidIndexSize = indexSize(globals[7]);
        if (additionalUv > 4) {
            throw new IOException("Invalid PMX header");
        }

        var name = text();
        var universalName = text();
        text();
        text();
        source.metadata = new HumanoidSource.Metadata(name.isBlank() ? universalName : name, List.of(), "");

        var vertexCount = count();
        var vertexJoints = new int[vertexCount * 4];
        var vertexWeights = new float[vertexCount * 4];
        for (var v = 0; v < vertexCount; v++) {
            var x = buffer.getFloat();
            var y = buffer.getFloat();
            var z = buffer.getFloat();
            source.positions.add(x * MmdHumanoid.UNIT);
            source.positions.add(y * MmdHumanoid.UNIT);
            source.positions.add(-z * MmdHumanoid.UNIT);
            var nx = buffer.getFloat();
            var ny = buffer.getFloat();
            var nz = buffer.getFloat();
            source.normals.add(nx);
            source.normals.add(ny);
            source.normals.add(-nz);
            source.uvs.add(buffer.getFloat());
            source.uvs.add(buffer.getFloat());
            buffer.position(buffer.position() + additionalUv * 16);
            var type = Byte.toUnsignedInt(buffer.get());
            java.util.Arrays.fill(vertexJoints, v * 4, v * 4 + 4, -1);
            switch (type) {
                case 0 -> {
                    vertexJoints[v * 4] = boneIndex();
                    vertexWeights[v * 4] = 1f;
                }
                case 1, 3 -> {
                    vertexJoints[v * 4] = boneIndex();
                    vertexJoints[v * 4 + 1] = boneIndex();
                    var weight = buffer.getFloat();
                    vertexWeights[v * 4] = weight;
                    vertexWeights[v * 4 + 1] = 1f - weight;
                    if (type == 3) {
                        // SDEF C, R0, R1: blended linearly instead.
                        buffer.position(buffer.position() + 36);
                    }
                }
                case 2, 4 -> {
                    for (var k = 0; k < 4; k++) {
                        vertexJoints[v * 4 + k] = boneIndex();
                    }
                    for (var k = 0; k < 4; k++) {
                        vertexWeights[v * 4 + k] = buffer.getFloat();
                    }
                }
                default -> throw new IOException("Unknown PMX weight type " + type);
            }
            source.edgeScales.add(buffer.getFloat());
        }

        var indexCount = count();
        var indices = new int[indexCount];
        for (var i = 0; i < indexCount; i++) {
            indices[i] = vertexIndex();
            if (indices[i] < 0 || indices[i] >= vertexCount) {
                throw new IOException("PMX face index out of range");
            }
        }

        var texturePaths = new ArrayList<String>();
        var textureCount = count();
        for (var i = 0; i < textureCount; i++) {
            texturePaths.add(text());
        }

        var loaded = new HashMap<Integer, HumanoidTexture>();
        var materialCount = count();
        var faceOffset = 0;
        for (var m = 0; m < materialCount; m++) {
            var materialName = text();
            text();
            var r = buffer.getFloat();
            var g = buffer.getFloat();
            var b = buffer.getFloat();
            var a = buffer.getFloat();
            buffer.position(buffer.position() + 12 + 4 + 12); // specular, power, ambient
            var flags = buffer.get();
            var edge = new float[] {buffer.getFloat(), buffer.getFloat(), buffer.getFloat(), buffer.getFloat()};
            var edgeSize = buffer.getFloat();
            var textureIndex = signedIndex(textureIndexSize);
            signedIndex(textureIndexSize);
            buffer.get();
            var sharedToon = buffer.get();
            if (sharedToon == 0) {
                signedIndex(textureIndexSize);
            } else {
                buffer.get();
            }
            text();
            var faceCount = buffer.getInt();
            if (faceCount < 0 || faceCount % 3 != 0 || faceOffset + faceCount > indexCount) {
                throw new IOException("PMX material face count out of range");
            }

            HumanoidTexture texture = null;
            if (textureIndex >= 0 && textureIndex < texturePaths.size()) {
                if (!loaded.containsKey(textureIndex)) {
                    loaded.put(textureIndex, LegacyImages.resolve(texturePaths.get(textureIndex), textures));
                }
                texture = loaded.get(textureIndex);
            }
            var outline = (flags & 0x10) != 0 && edge[3] > 0f && edgeSize > 0f
                ? HumanoidSource.Outline.of(edge[0], edge[1], edge[2], edgeSize * MmdHumanoid.EDGE_METERS) : null;
            source.materials.add(
                new HumanoidSource.Material(materialName, texture, r, g, b, a, HumanoidSource.BLEND, 0.5f, outline));
            for (var i = faceOffset; i < faceOffset + faceCount; i += 3) {
                source.triangles.add(indices[i]);
                source.triangles.add(indices[i + 1]);
                source.triangles.add(indices[i + 2]);
                source.triangleMaterials.add(m);
            }
            faceOffset += faceCount;
        }

        var boneCount = count();
        var universalNames = new ArrayList<String>(boneCount);
        var alias = new int[boneCount];
        for (var i = 0; i < boneCount; i++) {
            var boneName = text();
            universalNames.add(text());
            var x = buffer.getFloat();
            var y = buffer.getFloat();
            var z = buffer.getFloat();
            var parent = boneIndex();
            buffer.getInt();
            var flags = Short.toUnsignedInt(buffer.getShort());
            if ((flags & 0x0001) != 0) {
                boneIndex();
            } else {
                buffer.position(buffer.position() + 12);
            }
            alias[i] = i;
            if ((flags & (APPEND_ROTATION | APPEND_TRANSLATION)) != 0) {
                var appendParent = boneIndex();
                var ratio = buffer.getFloat();
                if ((flags & APPEND_ROTATION) != 0 && Math.abs(ratio - 1f) < 1e-4f && appendParent >= 0
                    && appendParent < boneCount && appendParent != i) {
                    alias[i] = appendParent;
                }
            }
            if ((flags & 0x0400) != 0) {
                buffer.position(buffer.position() + 12);
            }
            if ((flags & 0x0800) != 0) {
                buffer.position(buffer.position() + 24);
            }
            if ((flags & 0x2000) != 0) {
                buffer.getInt();
            }
            if ((flags & 0x0020) != 0) {
                boneIndex();
                buffer.getInt();
                buffer.getFloat();
                var links = buffer.getInt();
                for (var l = 0; l < links; l++) {
                    boneIndex();
                    if (buffer.get() != 0) {
                        buffer.position(buffer.position() + 24);
                    }
                }
            }
            if (parent < -1 || parent >= boneCount || parent == i) {
                throw new IOException("PMX bone parent out of range");
            }
            source.addNode(boneName, parent, x * MmdHumanoid.UNIT, y * MmdHumanoid.UNIT, -z * MmdHumanoid.UNIT);
        }
        rejectCycles(source);
        for (var i = 0; i < boneCount; i++) {
            var parent = source.parents.getInt(i);
            var resolved = resolveAlias(alias, parent);
            // Both bones share a rest position; skip a re-parent that would hang a bone below its own descendant.
            if (resolved != parent && !descendsFrom(source, resolved, i)) {
                source.skeletonParents.set(i, resolved);
            }
        }

        for (var v = 0; v < vertexCount * 4; v++) {
            var joint = vertexJoints[v];
            var weight = vertexWeights[v];
            if (joint < -1 || joint >= boneCount) {
                throw new IOException("PMX vertex bone index out of range");
            }
            if (joint >= 0) {
                joint = resolveAlias(alias, joint);
            }
            source.joints.add(weight > 0f ? joint : HumanoidSource.ROOT_JOINT);
            source.weights.add(Math.max(weight, 0f));
        }
        MmdHumanoid.mapHumanoid(source, universalNames);
        // Morphs and physics are optional: a model whose tail sections fail to parse still imports without them.
        var position = buffer.position();
        try {
            readMorphs(source, vertexCount, alias);
            var bodies = readBodies(alias);
            MmdPhysics.apply(source, bodies, readJoints());
        } catch (IOException | BufferUnderflowException | IndexOutOfBoundsException unreadable) {
            buffer.position(position);
            source.expressions.clear();
            source.springJoints.clear();
            source.springColliders.clear();
            source.springChains.clear();
        }
    }

    /**
     * Vertex morphs become expressions, and so do bone morphs: a face rigged with bones (eyebrows, eyelids or a jaw
     * moved by 怒り or 困る) has the vertex offsets of the posed bones baked in at full weight. Group morphs add their
     * member morphs scaled by the member ratio. Other morph kinds (UV, material, flip, impulse) are skipped.
     */
    private void readMorphs(HumanoidSource source, int vertexCount, int[] alias) throws IOException {
        var morphCount = count();
        var names = new ArrayList<String>(morphCount);
        var vertexMorphs = new HashMap<Integer, float[]>();
        var vertexIndices = new HashMap<Integer, int[]>();
        var groups = new HashMap<Integer, float[]>();
        var boneMorphs = new HashMap<Integer, float[]>();
        for (var m = 0; m < morphCount; m++) {
            names.add(MmdHumanoid.expressionName(text()));
            text();
            buffer.get();
            var type = Byte.toUnsignedInt(buffer.get());
            var offsets = count();
            switch (type) {
                case 1 -> {
                    var indices = new int[offsets];
                    var deltas = new float[offsets * 3];
                    for (var i = 0; i < offsets; i++) {
                        indices[i] = vertexIndex();
                        if (indices[i] < 0 || indices[i] >= vertexCount) {
                            throw new IOException("PMX morph vertex out of range");
                        }
                        deltas[i * 3] = buffer.getFloat() * MmdHumanoid.UNIT;
                        deltas[i * 3 + 1] = buffer.getFloat() * MmdHumanoid.UNIT;
                        deltas[i * 3 + 2] = -buffer.getFloat() * MmdHumanoid.UNIT;
                    }
                    vertexIndices.put(m, indices);
                    vertexMorphs.put(m, deltas);
                }
                case 0 -> {
                    // Pairs of (member morph index, ratio).
                    var members = new float[offsets * 2];
                    for (var i = 0; i < offsets; i++) {
                        members[i * 2] = signedIndex(morphIndexSize);
                        members[i * 2 + 1] = buffer.getFloat();
                    }
                    groups.put(m, members);
                }
                case 2 -> {
                    // Per bone: the bone, its translation, then its rotation quaternion, mirrored into our space.
                    var entries = new float[offsets * 8];
                    for (var i = 0; i < offsets; i++) {
                        var bone = signedIndex(boneIndexSize);
                        if (bone < 0 || bone >= source.nodeCount()) {
                            throw new IOException("PMX morph bone out of range");
                        }
                        entries[i * 8] = resolveAlias(alias, bone);
                        entries[i * 8 + 1] = buffer.getFloat() * MmdHumanoid.UNIT;
                        entries[i * 8 + 2] = buffer.getFloat() * MmdHumanoid.UNIT;
                        entries[i * 8 + 3] = -buffer.getFloat() * MmdHumanoid.UNIT;
                        entries[i * 8 + 4] = -buffer.getFloat();
                        entries[i * 8 + 5] = -buffer.getFloat();
                        entries[i * 8 + 6] = buffer.getFloat();
                        entries[i * 8 + 7] = buffer.getFloat();
                    }
                    boneMorphs.put(m, entries);
                }
                default -> {
                    var size = switch (type) {
                        case 9 -> morphIndexSize + 4;
                        case 3, 4, 5, 6, 7 -> vertexIndexSize + 16;
                        case 8 -> materialIndexSize + 113;
                        case 10 -> rigidIndexSize + 25;
                        default -> throw new IOException("Unknown PMX morph type " + type);
                    };
                    if ((long)offsets * size > buffer.remaining()) {
                        throw new IOException("PMX morph out of range");
                    }
                    buffer.position(buffer.position() + offsets * size);
                }
            }
        }
        for (var entry : boneMorphs.entrySet()) {
            bakeBoneMorph(source, entry.getValue(), entry.getKey(), vertexIndices, vertexMorphs);
        }
        for (var m = 0; m < morphCount; m++) {
            if (vertexMorphs.containsKey(m)) {
                addMorph(source.expression(names.get(m)), vertexIndices.get(m), vertexMorphs.get(m), 1f);
            } else if (groups.containsKey(m)) {
                var members = groups.get(m);
                for (var i = 0; i < members.length; i += 2) {
                    var member = (int)members[i];
                    if (vertexMorphs.containsKey(member)) {
                        addMorph(source.expression(names.get(m)), vertexIndices.get(member), vertexMorphs.get(member),
                            members[i + 1]);
                    }
                }
            }
        }
    }

    /**
     * The vertex offsets a bone morph causes at full weight: each listed bone moves and turns about its rest position
     * (MMD bones rest unrotated, so its local axes are the model's), carrying its descendants, and every vertex follows
     * its weighted bones.
     */
    private static void bakeBoneMorph(HumanoidSource source, float[] entries, int morph,
        Map<Integer, int[]> vertexIndices, Map<Integer, float[]> vertexMorphs) {
        var nodeCount = source.nodeCount();
        var local = new Matrix4f[nodeCount];
        for (var i = 0; i < entries.length; i += 8) {
            var bone = (int)entries[i];
            var x = source.nodePositions.getFloat(bone * 3);
            var y = source.nodePositions.getFloat(bone * 3 + 1);
            var z = source.nodePositions.getFloat(bone * 3 + 2);
            var rotation = new Quaternionf(entries[i + 4], entries[i + 5], entries[i + 6], entries[i + 7]);
            if (!(rotation.lengthSquared() > 1e-12f)) {
                rotation.identity();
            }
            var delta = new Matrix4f().translation(x + entries[i + 1], y + entries[i + 2], z + entries[i + 3])
                .rotate(rotation.normalize()).translate(-x, -y, -z);
            local[bone] = local[bone] == null ? delta : local[bone].mul(delta);
        }
        var global = new Matrix4f[nodeCount];
        var visited = new boolean[nodeCount];
        for (var node = 0; node < nodeCount; node++) {
            globalDelta(source, local, global, visited, node);
        }

        var indices = new IntArrayList();
        var deltas = new FloatArrayList();
        var position = new Vector3f();
        var moved = new Vector3f();
        var offset = new Vector3f();
        for (var v = 0; v < source.positions.size() / 3; v++) {
            offset.zero();
            for (var k = 0; k < 4; k++) {
                var joint = source.joints.getInt(v * 4 + k);
                var weight = source.weights.getFloat(v * 4 + k);
                if (joint < 0 || weight <= 0f || global[joint] == null) {
                    continue;
                }
                position.set(source.positions.getFloat(v * 3), source.positions.getFloat(v * 3 + 1),
                    source.positions.getFloat(v * 3 + 2));
                global[joint].transformPosition(position, moved);
                offset.fma(weight, moved.sub(position));
            }
            if (offset.lengthSquared() > 1e-14f && offset.isFinite()) {
                indices.add(v);
                deltas.add(offset.x);
                deltas.add(offset.y);
                deltas.add(offset.z);
            }
        }
        vertexIndices.put(morph, indices.toIntArray());
        vertexMorphs.put(morph, deltas.toFloatArray());
    }

    /** The rest-space motion of a node under a bone morph; null when neither it nor an ancestor moves. */
    @org.jetbrains.annotations.Nullable
    private static Matrix4f globalDelta(HumanoidSource source, Matrix4f[] local, Matrix4f[] global, boolean[] visited,
        int node) {
        if (visited[node]) {
            return global[node];
        }
        visited[node] = true;
        var parent = source.parents.getInt(node);
        var above = parent >= 0 ? globalDelta(source, local, global, visited, parent) : null;
        global[node] = above == null ? local[node] : local[node] == null ? above : new Matrix4f(above).mul(local[node]);
        return global[node];
    }

    private static void addMorph(HumanoidSource.Expression expression, int[] indices, float[] deltas, float ratio) {
        for (var i = 0; i < indices.length; i++) {
            expression.add(indices[i], deltas[i * 3] * ratio, deltas[i * 3 + 1] * ratio, deltas[i * 3 + 2] * ratio);
        }
    }

    /**
     * Skips display frames, then reads the rigid bodies. A static body on a bone that copies another bone's rotation
     * follows that bone instead, as its weights and children do, since YSM never animates the copy.
     */
    private List<MmdPhysics.Body> readBodies(int[] alias) throws IOException {
        var frameCount = count();
        for (var f = 0; f < frameCount; f++) {
            text();
            text();
            buffer.get();
            var elements = count();
            for (var e = 0; e < elements; e++) {
                var target = buffer.get();
                buffer.position(buffer.position() + (target == 0 ? boneIndexSize : morphIndexSize));
            }
        }
        var bodyCount = count();
        var bodies = new ArrayList<MmdPhysics.Body>(bodyCount);
        for (var b = 0; b < bodyCount; b++) {
            text();
            text();
            var bone = boneIndex();
            var group = Byte.toUnsignedInt(buffer.get());
            var mask = Short.toUnsignedInt(buffer.getShort());
            var shape = Byte.toUnsignedInt(buffer.get());
            var size =
                new org.joml.Vector3f(buffer.getFloat(), buffer.getFloat(), buffer.getFloat()).mul(MmdHumanoid.UNIT);
            var position =
                new org.joml.Vector3f(buffer.getFloat(), buffer.getFloat(), -buffer.getFloat()).mul(MmdHumanoid.UNIT);
            var rotation = MmdPhysics.rotation(buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
            buffer.getFloat(); // mass
            var linearDamping = buffer.getFloat();
            buffer.getFloat(); // angular damping
            buffer.getFloat(); // restitution
            buffer.getFloat(); // friction
            var mode = Byte.toUnsignedInt(buffer.get());
            if (mode == 0 && bone >= 0 && bone < alias.length) {
                bone = resolveAlias(alias, bone);
            }
            bodies.add(new MmdPhysics.Body(bone, group, mask, shape, size, position, rotation, linearDamping, mode));
        }
        return bodies;
    }

    /**
     * Every PMX joint type shares this layout; limits are radians in MMD's left-handed space. A file that ends after
     * its rigid bodies has none.
     */
    private List<MmdPhysics.Joint> readJoints() throws IOException {
        if (!buffer.hasRemaining()) {
            return List.of();
        }
        var jointCount = count();
        var joints = new ArrayList<MmdPhysics.Joint>(jointCount);
        for (var j = 0; j < jointCount; j++) {
            text();
            text();
            buffer.get();
            var bodyA = signedIndex(rigidIndexSize);
            var bodyB = signedIndex(rigidIndexSize);
            buffer.position(buffer.position() + 12 * 4); // position, rotation, translation limits
            var lower = new org.joml.Vector3f(buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
            var upper = new org.joml.Vector3f(buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
            buffer.position(buffer.position() + 6 * 4); // springs
            joints.add(new MmdPhysics.Joint(bodyA, bodyB, lower, upper));
        }
        return joints;
    }

    static void rejectCycles(HumanoidSource source) throws IOException {
        for (var i = 0; i < source.nodeCount(); i++) {
            var steps = 0;
            for (var current = source.parents.getInt(i); current >= 0; current = source.parents.getInt(current)) {
                if (++steps > source.nodeCount()) {
                    throw new IOException("Cyclic bone hierarchy");
                }
            }
        }
    }

    private static int indexSize(int value) throws IOException {
        if (value != 1 && value != 2 && value != 4) {
            throw new IOException("Invalid PMX index size " + value);
        }
        return value;
    }

    private int count() throws IOException {
        var count = buffer.getInt();
        if (count < 0 || count > buffer.remaining()) {
            throw new IOException("PMX count out of range");
        }
        return count;
    }

    private String text() throws IOException {
        var length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) {
            throw new IOException("PMX text length out of range");
        }
        var bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, charset);
    }

    private int boneIndex() {
        return signedIndex(boneIndexSize);
    }

    private int signedIndex(int size) {
        return switch (size) {
            case 1 -> buffer.get();
            case 2 -> buffer.getShort();
            default -> buffer.getInt();
        };
    }

    private int vertexIndex() {
        return switch (vertexIndexSize) {
            case 1 -> Byte.toUnsignedInt(buffer.get());
            case 2 -> Short.toUnsignedInt(buffer.getShort());
            default -> buffer.getInt();
        };
    }

    /** The bone a chain of full rotation copies ends at; -1 stays -1. */
    private static int resolveAlias(int[] alias, int bone) {
        for (var steps = 0; bone >= 0 && alias[bone] != bone && steps < alias.length; steps++) {
            bone = alias[bone];
        }
        return bone;
    }

    /** Whether node lies at or below ancestor in the YSM skeleton. */
    private static boolean descendsFrom(HumanoidSource source, int node, int ancestor) {
        var current = node;
        for (var steps = 0; current >= 0 && steps <= source.nodeCount(); steps++) {
            if (current == ancestor) {
                return true;
            }
            current = source.skeletonParents.getInt(current);
        }
        return false;
    }
}
