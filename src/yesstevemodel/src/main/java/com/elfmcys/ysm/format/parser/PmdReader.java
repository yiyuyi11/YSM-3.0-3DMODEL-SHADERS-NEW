package com.elfmcys.ysm.format.parser;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

/**
 * Reads PMD 1.0 into a {@link HumanoidSource}; same space and bone names as PMX. Rigid bodies become spring bones
 * ({@link MmdPhysics}); morphs are skipped.
 */
final class PmdReader {
    private static final Charset SHIFT_JIS = shiftJis();

    private final ByteBuffer buffer;

    private PmdReader(ByteBuffer data) {
        this.buffer = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    }

    static HumanoidSource read(ByteBuffer data, HumanoidImport.TextureResolver textures) throws IOException {
        var source = new HumanoidSource();
        try {
            new PmdReader(data).read(source, textures);
            return source;
        } catch (BufferUnderflowException | IndexOutOfBoundsException truncated) {
            source.close();
            throw new IOException("Truncated PMD file", truncated);
        } catch (IOException | RuntimeException error) {
            source.close();
            throw error;
        }
    }

    private static Charset shiftJis() {
        for (var name : List.of("windows-31j", "Shift_JIS")) {
            if (Charset.isSupported(name)) {
                return Charset.forName(name);
            }
        }
        return StandardCharsets.ISO_8859_1;
    }

    private void read(HumanoidSource source, HumanoidImport.TextureResolver textures) throws IOException {
        if (buffer.remaining() < 283 || buffer.get() != 'P' || buffer.get() != 'm' || buffer.get() != 'd') {
            throw new IOException("Not a PMD file");
        }
        buffer.getFloat();
        var name = fixedText(20);
        fixedText(256);
        source.metadata = new HumanoidSource.Metadata(name, List.of(), "");

        var vertexCount = count(38);
        var vertexJoints = new int[vertexCount * 2];
        var vertexWeights = new float[vertexCount];
        for (var v = 0; v < vertexCount; v++) {
            source.positions.add(buffer.getFloat() * MmdHumanoid.UNIT);
            source.positions.add(buffer.getFloat() * MmdHumanoid.UNIT);
            source.positions.add(-buffer.getFloat() * MmdHumanoid.UNIT);
            source.normals.add(buffer.getFloat());
            source.normals.add(buffer.getFloat());
            source.normals.add(-buffer.getFloat());
            source.uvs.add(buffer.getFloat());
            source.uvs.add(buffer.getFloat());
            vertexJoints[v * 2] = Short.toUnsignedInt(buffer.getShort());
            vertexJoints[v * 2 + 1] = Short.toUnsignedInt(buffer.getShort());
            vertexWeights[v] = Math.min(Byte.toUnsignedInt(buffer.get()), 100) / 100f;
            buffer.get();
        }

        var indexCount = count(2);
        var indices = new int[indexCount];
        for (var i = 0; i < indexCount; i++) {
            indices[i] = Short.toUnsignedInt(buffer.getShort());
            if (indices[i] >= vertexCount) {
                throw new IOException("PMD face index out of range");
            }
        }

        var loaded = new HashMap<String, HumanoidTexture>();
        var materialCount = count(70);
        var faceOffset = 0;
        for (var m = 0; m < materialCount; m++) {
            var r = buffer.getFloat();
            var g = buffer.getFloat();
            var b = buffer.getFloat();
            var a = buffer.getFloat();
            buffer.position(buffer.position() + 4 + 12 + 12 + 1); // power, specular, ambient, toon
            // PMD's edge flag draws MMD's black edge at size 1.
            var outline = buffer.get() != 0 ? HumanoidSource.Outline.of(0f, 0f, 0f, MmdHumanoid.EDGE_METERS) : null;
            var faceCount = buffer.getInt();
            var file = fixedText(20);
            if (faceCount < 0 || faceCount % 3 != 0 || faceOffset + faceCount > indexCount) {
                throw new IOException("PMD material face count out of range");
            }
            // "texture.bmp*sphere.spa" names a base texture and a sphere map.
            var star = file.indexOf('*');
            var path = star >= 0 ? file.substring(0, star) : file;
            if (!loaded.containsKey(path)) {
                loaded.put(path, LegacyImages.resolve(path, textures));
            }
            source.materials.add(
                new HumanoidSource.Material(path, loaded.get(path), r, g, b, a, HumanoidSource.BLEND, 0.5f, outline));
            for (var i = faceOffset; i < faceOffset + faceCount; i += 3) {
                source.triangles.add(indices[i]);
                source.triangles.add(indices[i + 1]);
                source.triangles.add(indices[i + 2]);
                source.triangleMaterials.add(m);
            }
            faceOffset += faceCount;
        }

        var boneCount = Short.toUnsignedInt(buffer.getShort());
        if ((long)boneCount * 39 > buffer.remaining()) {
            throw new IOException("PMD bone count out of range");
        }
        for (var i = 0; i < boneCount; i++) {
            var boneName = fixedText(20);
            var parent = Short.toUnsignedInt(buffer.getShort());
            buffer.getShort();
            buffer.get();
            buffer.getShort();
            var x = buffer.getFloat();
            var y = buffer.getFloat();
            var z = buffer.getFloat();
            var parentIndex = parent == 0xFFFF ? -1 : parent;
            if (parentIndex >= boneCount || parentIndex == i) {
                throw new IOException("PMD bone parent out of range");
            }
            source.addNode(boneName, parentIndex, x * MmdHumanoid.UNIT, y * MmdHumanoid.UNIT, -z * MmdHumanoid.UNIT);
        }
        PmxReader.rejectCycles(source);

        for (var v = 0; v < vertexCount; v++) {
            var first = vertexJoints[v * 2];
            var second = vertexJoints[v * 2 + 1];
            if (first >= boneCount || second >= boneCount) {
                throw new IOException("PMD vertex bone index out of range");
            }
            var weight = vertexWeights[v];
            source.joints.add(weight > 0f ? first : HumanoidSource.ROOT_JOINT);
            source.weights.add(weight);
            source.joints.add(weight < 1f ? second : HumanoidSource.ROOT_JOINT);
            source.weights.add(1f - weight);
            source.joints.add(HumanoidSource.ROOT_JOINT);
            source.weights.add(0f);
            source.joints.add(HumanoidSource.ROOT_JOINT);
            source.weights.add(0f);
        }
        MmdHumanoid.mapHumanoid(source, null);
        // Morph and physics sections are optional; a model whose tail fails to parse imports without them.
        try {
            var bodies = readTail(source, boneCount);
            MmdPhysics.apply(source, bodies, readJoints());
        } catch (IOException | BufferUnderflowException | IndexOutOfBoundsException unreadable) {
            source.expressions.clear();
            source.springJoints.clear();
            source.springColliders.clear();
            source.springChains.clear();
        }
    }

    /**
     * Skips IK; turns skins (morphs) into expressions; skips display lists, English names and toon textures; reads the
     * rigid bodies.
     */
    private List<MmdPhysics.Body> readTail(HumanoidSource source, int boneCount) throws IOException {
        var ikCount = Short.toUnsignedInt(buffer.getShort());
        for (var i = 0; i < ikCount; i++) {
            buffer.position(buffer.position() + 4);
            var chain = Byte.toUnsignedInt(buffer.get());
            buffer.position(buffer.position() + 6 + chain * 2);
        }
        // Skin 0 lists the base vertices; every other skin offsets entries of that list.
        var skinCount = Short.toUnsignedInt(buffer.getShort());
        int[] baseVertices = new int[0];
        for (var i = 0; i < skinCount; i++) {
            var name = MmdHumanoid.expressionName(fixedText(20));
            var vertices = count(16);
            var type = Byte.toUnsignedInt(buffer.get());
            if (type == 0) {
                baseVertices = new int[vertices];
                for (var v = 0; v < vertices; v++) {
                    baseVertices[v] = buffer.getInt();
                    if (baseVertices[v] < 0 || baseVertices[v] >= source.vertexCount()) {
                        throw new IOException("PMD base skin vertex out of range");
                    }
                    buffer.position(buffer.position() + 12);
                }
                continue;
            }
            var expression = source.expression(name);
            for (var v = 0; v < vertices; v++) {
                var entry = buffer.getInt();
                if (entry < 0 || entry >= baseVertices.length) {
                    throw new IOException("PMD skin entry out of range");
                }
                expression.add(baseVertices[entry], buffer.getFloat() * MmdHumanoid.UNIT,
                    buffer.getFloat() * MmdHumanoid.UNIT, -buffer.getFloat() * MmdHumanoid.UNIT);
            }
        }
        var skinDisplay = Byte.toUnsignedInt(buffer.get());
        buffer.position(buffer.position() + skinDisplay * 2);
        var displayNames = Byte.toUnsignedInt(buffer.get());
        buffer.position(buffer.position() + displayNames * 50);
        var boneDisplay = count(3);
        buffer.position(buffer.position() + boneDisplay * 3);
        if (!buffer.hasRemaining()) {
            return List.of();
        }
        if (buffer.get() != 0) {
            buffer.position(
                buffer.position() + 276 + boneCount * 20 + Math.max(skinCount - 1, 0) * 20 + displayNames * 50);
        }
        if (!buffer.hasRemaining()) {
            return List.of();
        }
        buffer.position(buffer.position() + 1000);
        if (!buffer.hasRemaining()) {
            return List.of();
        }
        var bodyCount = count(83);
        var bodies = new java.util.ArrayList<MmdPhysics.Body>(bodyCount);
        for (var b = 0; b < bodyCount; b++) {
            buffer.position(buffer.position() + 20);
            var raw = Short.toUnsignedInt(buffer.getShort());
            var bone = raw == 0xFFFF || raw >= boneCount ? -1 : raw;
            var group = Byte.toUnsignedInt(buffer.get());
            var mask = Short.toUnsignedInt(buffer.getShort());
            var shape = Byte.toUnsignedInt(buffer.get());
            var size =
                new org.joml.Vector3f(buffer.getFloat(), buffer.getFloat(), buffer.getFloat()).mul(MmdHumanoid.UNIT);
            // Offset from the bone, mirrored like every PMD position.
            var position =
                new org.joml.Vector3f(buffer.getFloat(), buffer.getFloat(), -buffer.getFloat()).mul(MmdHumanoid.UNIT);
            if (bone >= 0) {
                position.add(source.nodePositions.getFloat(bone * 3), source.nodePositions.getFloat(bone * 3 + 1),
                    source.nodePositions.getFloat(bone * 3 + 2));
            }
            var rotation = MmdPhysics.rotation(buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
            buffer.getFloat(); // mass
            var linearDamping = buffer.getFloat();
            buffer.position(buffer.position() + 12); // angular damping, restitution, friction
            var mode = Byte.toUnsignedInt(buffer.get());
            bodies
                .add(new MmdPhysics.Body(bone, group, mask, shape, size, position, rotation, linearDamping, mode != 0));
        }
        return bodies;
    }

    /** The joints after the rigid bodies, absent in files without physics. */
    private List<MmdPhysics.Joint> readJoints() throws IOException {
        if (!buffer.hasRemaining()) {
            return List.of();
        }
        var jointCount = count(124);
        var joints = new java.util.ArrayList<MmdPhysics.Joint>(jointCount);
        for (var j = 0; j < jointCount; j++) {
            buffer.position(buffer.position() + 20);
            var bodyA = buffer.getInt();
            var bodyB = buffer.getInt();
            buffer.position(buffer.position() + 12 * 4); // position, rotation, translation limits
            var lower = new org.joml.Vector3f(buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
            var upper = new org.joml.Vector3f(buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
            buffer.position(buffer.position() + 6 * 4); // springs
            joints.add(new MmdPhysics.Joint(bodyA, bodyB, lower, upper));
        }
        return joints;
    }

    private int count(int elementSize) throws IOException {
        var count = buffer.getInt();
        if (count < 0 || (long)count * elementSize > buffer.remaining()) {
            throw new IOException("PMD count out of range");
        }
        return count;
    }

    private String fixedText(int length) {
        var bytes = new byte[length];
        buffer.get(bytes);
        var end = 0;
        while (end < length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, SHIFT_JIS).trim();
    }
}
