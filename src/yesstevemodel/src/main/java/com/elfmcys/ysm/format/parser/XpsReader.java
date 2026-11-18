package com.elfmcys.ysm.format.parser;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads XNALara / XPS models: binary .xps (with header) and .mesh, and text .mesh.ascii. Space is right-handed, Y up;
 * front faces wind clockwise. Each mesh draws its first texture (always the diffuse map); the render group prefix of
 * the mesh name ("6_hair_...") decides whether texture alpha blends.
 */
final class XpsReader {
    private static final int MAGIC = 323232;
    /** Render groups whose shaders blend by diffuse alpha (XNALara render group table). */
    private static final Set<Integer> ALPHA_GROUPS =
        Set.of(6, 7, 8, 9, 12, 15, 18, 19, 20, 21, 23, 25, 27, 29, 31, 33, 37, 39, 41, 43);
    /** Mesh names without a render group prefix use group 5: diffuse only, no alpha. */
    private static final int DEFAULT_GROUP = 5;
    /** Accepted model heights in meters; other sizes are rescaled to {@link #FALLBACK_HEIGHT}. */
    private static final float MIN_HEIGHT = 0.3f;
    private static final float MAX_HEIGHT = 3f;
    private static final float FALLBACK_HEIGHT = 1.7f;

    private record Bone(String name, int parent, float x, float y, float z) {
    }

    private record Mesh(String name, String texture, float[] positions, float[] normals, float[] uvs, int[] joints,
        float[] weights, int[] triangles) {
    }

    private final List<Bone> bones = new ArrayList<>();
    private final List<Mesh> meshes = new ArrayList<>();

    private XpsReader() {}

    static HumanoidSource read(String path, ByteBuffer data, HumanoidImport.TextureResolver textures)
        throws IOException {
        var reader = new XpsReader();
        if (path.toLowerCase(Locale.ROOT).endsWith(".ascii")) {
            reader.readAscii(new Ascii(StandardCharsets.UTF_8.decode(data.duplicate()).toString()));
        } else {
            reader.readBinary(data.duplicate().order(ByteOrder.LITTLE_ENDIAN));
        }
        var source = new HumanoidSource();
        try {
            reader.fill(source, textures);
            var name = path.substring(path.lastIndexOf('/') + 1);
            source.metadata = new HumanoidSource.Metadata(
                name.substring(0, name.indexOf('.') > 0 ? name.indexOf('.') : name.length()), List.of(), "");
            return source;
        } catch (IOException | RuntimeException error) {
            source.close();
            throw error;
        }
    }

    private void readBinary(ByteBuffer in) throws IOException {
        var header = in.remaining() >= 4 && in.getInt(in.position()) == MAGIC;
        // Header-less .mesh files always carry tangents and four weights.
        var tangents = true;
        var alternative = true;
        var variableWeights = false;
        try {
            if (header) {
                in.getInt();
                var major = Short.toUnsignedInt(in.getShort());
                var minor = Short.toUnsignedInt(in.getShort());
                string(in);
                var settings = Integer.toUnsignedLong(in.getInt());
                string(in);
                string(in);
                string(in);
                if (settings * 4 > in.remaining()) {
                    throw new IOException("XPS settings block out of range");
                }
                in.position(in.position() + (int)(settings * 4));
                // XPS tools disagree on whether 2.x files up to minor 12 store tangents; try the other layout on
                // failure.
                tangents = major <= 2 && minor <= 12;
                alternative = major < 2;
                variableWeights = major >= 3;
            }
        } catch (BufferUnderflowException truncated) {
            throw new IOException("Truncated XPS header", truncated);
        }
        var body = in.position();
        try {
            readBinaryBody(in, tangents, variableWeights);
        } catch (IOException | BufferUnderflowException | IllegalArgumentException first) {
            if (alternative == tangents) {
                throw first instanceof IOException io ? io : new IOException("Truncated XPS file", first);
            }
            bones.clear();
            meshes.clear();
            in.position(body);
            try {
                readBinaryBody(in, alternative, variableWeights);
            } catch (BufferUnderflowException | IllegalArgumentException truncated) {
                throw new IOException("Truncated XPS file", truncated);
            }
        }
    }

    private void readBinaryBody(ByteBuffer in, boolean tangents, boolean variableWeights) throws IOException {
        var boneCount = count(in, 15);
        for (var i = 0; i < boneCount; i++) {
            bones.add(new Bone(string(in), in.getShort(), in.getFloat(), in.getFloat(), in.getFloat()));
        }
        var meshCount = count(in, 12);
        for (var m = 0; m < meshCount; m++) {
            var name = string(in);
            var uvLayers = count(in, 0);
            var textureCount = count(in, 5);
            String texture = null;
            var diffuseLayer = 0;
            for (var t = 0; t < textureCount; t++) {
                var file = string(in);
                var layer = in.getInt();
                if (t == 0) {
                    texture = file;
                    diffuseLayer = layer;
                }
            }
            var vertexCount = count(in, 28 + uvLayers * 8);
            var positions = new float[vertexCount * 3];
            var normals = new float[vertexCount * 3];
            var uvs = new float[vertexCount * 2];
            var joints = new int[vertexCount * 4];
            var weights = new float[vertexCount * 4];
            var ids = new int[4];
            var values = new float[4];
            for (var v = 0; v < vertexCount; v++) {
                positions[v * 3] = in.getFloat();
                positions[v * 3 + 1] = in.getFloat();
                positions[v * 3 + 2] = in.getFloat();
                normals[v * 3] = in.getFloat();
                normals[v * 3 + 1] = in.getFloat();
                normals[v * 3 + 2] = in.getFloat();
                in.getInt(); // vertex color
                for (var layer = 0; layer < uvLayers; layer++) {
                    var u = in.getFloat();
                    var w = in.getFloat();
                    if (layer == diffuseLayer) {
                        uvs[v * 2] = u;
                        uvs[v * 2 + 1] = w;
                    }
                    if (tangents) {
                        in.position(in.position() + 16);
                    }
                }
                if (boneCount > 0) {
                    var influences = variableWeights ? in.getShort() : 4;
                    if (influences < 0 || influences * 6 > in.remaining()) {
                        throw new IOException("XPS weight count out of range");
                    }
                    if (influences > ids.length) {
                        ids = new int[influences];
                        values = new float[influences];
                    }
                    for (var k = 0; k < influences; k++) {
                        ids[k] = Short.toUnsignedInt(in.getShort());
                    }
                    for (var k = 0; k < influences; k++) {
                        values[k] = in.getFloat();
                    }
                    strongestFour(ids, values, influences, joints, weights, v);
                } else {
                    unweighted(joints, v);
                }
            }
            var triangleCount = count(in, 12);
            var triangles = new int[triangleCount * 3];
            for (var i = 0; i < triangles.length; i++) {
                triangles[i] = in.getInt();
            }
            meshes.add(new Mesh(name, texture, positions, normals, uvs, joints, weights, triangles));
        }
    }

    private void readAscii(Ascii in) throws IOException {
        var boneCount = in.integer();
        for (var i = 0; i < boneCount; i++) {
            var name = in.text();
            var parent = in.integer();
            var position = in.floats(3);
            bones.add(new Bone(name, parent, position[0], position[1], position[2]));
        }
        var meshCount = in.integer();
        for (var m = 0; m < meshCount; m++) {
            var name = in.text();
            var uvLayers = in.integer();
            var textureCount = in.integer();
            String texture = null;
            var diffuseLayer = 0;
            for (var t = 0; t < textureCount; t++) {
                var file = in.text();
                var layer = in.integer();
                if (t == 0) {
                    texture = file;
                    diffuseLayer = layer;
                }
            }
            var vertexCount = in.integer();
            if (vertexCount < 0 || vertexCount > in.remainingLines()) {
                throw new IOException("XPS vertex count out of range");
            }
            var positions = new float[vertexCount * 3];
            var normals = new float[vertexCount * 3];
            var uvs = new float[vertexCount * 2];
            var joints = new int[vertexCount * 4];
            var weights = new float[vertexCount * 4];
            for (var v = 0; v < vertexCount; v++) {
                System.arraycopy(in.floats(3), 0, positions, v * 3, 3);
                System.arraycopy(in.floats(3), 0, normals, v * 3, 3);
                in.skipLine(); // color
                for (var layer = 0; layer < uvLayers; layer++) {
                    var uv = in.floats(2);
                    if (layer == diffuseLayer) {
                        uvs[v * 2] = uv[0];
                        uvs[v * 2 + 1] = uv[1];
                    }
                }
                if (boneCount > 0) {
                    var ids = in.values();
                    var values = in.values();
                    var influences = Math.min(ids.length, values.length);
                    var idValues = new int[influences];
                    var weightValues = new float[influences];
                    for (var k = 0; k < influences; k++) {
                        idValues[k] = Ascii.parseInt(ids[k]);
                        weightValues[k] = Ascii.parseFloat(values[k]);
                    }
                    strongestFour(idValues, weightValues, influences, joints, weights, v);
                } else {
                    unweighted(joints, v);
                }
            }
            var triangleCount = in.integer();
            if (triangleCount < 0 || triangleCount > in.remainingLines()) {
                throw new IOException("XPS triangle count out of range");
            }
            var triangles = new int[triangleCount * 3];
            for (var i = 0; i < triangleCount; i++) {
                var values = in.values();
                if (values.length < 3) {
                    throw new IOException("XPS triangle line needs three indices");
                }
                for (var k = 0; k < 3; k++) {
                    triangles[i * 3 + k] = Ascii.parseInt(values[k]);
                }
            }
            meshes.add(new Mesh(name, texture, positions, normals, uvs, joints, weights, triangles));
        }
    }

    /** Keeps the four largest positive weights, normalized; vertices without any attach to the root. */
    private static void strongestFour(int[] ids, float[] values, int count, int[] joints, float[] weights, int vertex) {
        var total = 0f;
        for (var slot = 0; slot < 4; slot++) {
            var best = -1;
            for (var k = 0; k < count; k++) {
                if (values[k] > 0f && (best < 0 || values[k] > values[best])) {
                    best = k;
                }
            }
            if (best < 0) {
                joints[vertex * 4 + slot] = HumanoidSource.ROOT_JOINT;
                continue;
            }
            joints[vertex * 4 + slot] = ids[best];
            weights[vertex * 4 + slot] = values[best];
            total += values[best];
            values[best] = 0f;
        }
        if (total > 0f) {
            for (var slot = 0; slot < 4; slot++) {
                weights[vertex * 4 + slot] /= total;
            }
        }
    }

    private static void unweighted(int[] joints, int vertex) {
        for (var slot = 0; slot < 4; slot++) {
            joints[vertex * 4 + slot] = HumanoidSource.ROOT_JOINT;
        }
    }

    private void fill(HumanoidSource source, HumanoidImport.TextureResolver resolver) throws IOException {
        var scale = scale();
        for (var i = 0; i < bones.size(); i++) {
            var bone = bones.get(i);
            if (bone.parent() < -1 || bone.parent() >= bones.size() || bone.parent() == i) {
                throw new IOException("XPS bone parent out of range");
            }
            source.addNode(bone.name(), bone.parent(), bone.x() * scale, bone.y() * scale, bone.z() * scale);
        }
        PmxReader.rejectCycles(source);

        var textures = new HashMap<String, HumanoidTexture>();
        for (var mesh : meshes) {
            var base = source.vertexCount();
            var vertexCount = mesh.positions().length / 3;
            for (var v = 0; v < vertexCount; v++) {
                for (var k = 0; k < 3; k++) {
                    source.positions.add(mesh.positions()[v * 3 + k] * scale);
                    source.normals.add(mesh.normals()[v * 3 + k]);
                }
                source.uvs.add(mesh.uvs()[v * 2]);
                source.uvs.add(mesh.uvs()[v * 2 + 1]);
                for (var slot = 0; slot < 4; slot++) {
                    var joint = mesh.joints()[v * 4 + slot];
                    if (joint < HumanoidSource.ROOT_JOINT || joint >= bones.size()) {
                        throw new IOException("XPS vertex bone index out of range");
                    }
                    source.joints.add(joint);
                    source.weights.add(mesh.weights()[v * 4 + slot]);
                }
            }
            var material = source.materials.size();
            var texture = mesh.texture();
            if (texture != null && !textures.containsKey(texture)) {
                textures.put(texture, LegacyImages.resolve(baseName(texture), resolver));
            }
            var alpha = ALPHA_GROUPS.contains(renderGroup(mesh.name())) ? HumanoidSource.BLEND : HumanoidSource.OPAQUE;
            source.materials.add(new HumanoidSource.Material(mesh.name(),
                texture == null ? null : textures.get(texture), 1f, 1f, 1f, 1f, alpha, 0.5f, null));
            var triangles = mesh.triangles();
            for (var i = 0; i < triangles.length; i += 3) {
                for (var k : new int[] {0, 2, 1}) {
                    var index = triangles[i + k];
                    if (index < 0 || index >= vertexCount) {
                        throw new IOException("XPS face index out of range");
                    }
                    source.triangles.add(base + index);
                }
                source.triangleMaterials.add(material);
            }
        }
        // Many XPS models are MMD conversions that keep the Japanese bone names.
        MmdHumanoid.mapHumanoid(source, null);
    }

    /** Models are usually in meters; anything implausibly small or large is resized to a human height. */
    private float scale() {
        var min = Float.POSITIVE_INFINITY;
        var max = Float.NEGATIVE_INFINITY;
        for (var mesh : meshes) {
            for (var i = 1; i < mesh.positions().length; i += 3) {
                min = Math.min(min, mesh.positions()[i]);
                max = Math.max(max, mesh.positions()[i]);
            }
        }
        var height = max - min;
        if (!(height > 0f) || !Float.isFinite(height) || (height >= MIN_HEIGHT && height <= MAX_HEIGHT)) {
            return 1f;
        }
        return FALLBACK_HEIGHT / height;
    }

    static int renderGroup(String meshName) {
        var underscore = meshName.indexOf('_');
        try {
            return Integer.parseInt((underscore >= 0 ? meshName.substring(0, underscore) : meshName).trim());
        } catch (NumberFormatException unprefixed) {
            return DEFAULT_GROUP;
        }
    }

    private static String baseName(String path) {
        var normalized = path.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private static int count(ByteBuffer in, int minimumElementSize) throws IOException {
        var count = in.getInt();
        if (count < 0 || (long)count * minimumElementSize > in.remaining()) {
            throw new IOException("XPS count out of range");
        }
        return count;
    }

    /** One or two length bytes (7 bits each, low first), then UTF-8. */
    private static String string(ByteBuffer in) throws IOException {
        var length = Byte.toUnsignedInt(in.get());
        if (length >= 128) {
            length = (length & 0x7F) + Byte.toUnsignedInt(in.get()) * 128;
        }
        if (length > in.remaining()) {
            throw new IOException("XPS string length out of range");
        }
        var bytes = new byte[length];
        in.get(bytes);
        var text = new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** Line reader for .mesh.ascii: one value group per line, '#' starts a comment. */
    private static final class Ascii {
        private final String[] lines;
        private int next;

        Ascii(String text) {
            this.lines = (text.startsWith("\uFEFF") ? text.substring(1) : text).split("\r?\n|\r", -1);
        }

        int remainingLines() {
            return lines.length - next;
        }

        private String line() throws IOException {
            if (next >= lines.length) {
                throw new IOException("Truncated XPS ASCII file at line " + next);
            }
            return lines[next++].trim();
        }

        void skipLine() throws IOException {
            line();
        }

        String text() throws IOException {
            var line = line();
            var comment = line.indexOf('#');
            return (comment >= 0 ? line.substring(0, comment) : line).trim();
        }

        String[] values() throws IOException {
            var line = line().replace('#', ' ').trim();
            return line.isEmpty() ? new String[0] : line.split("\\s+");
        }

        int integer() throws IOException {
            var values = values();
            if (values.length == 0) {
                throw new IOException("Expected a number at XPS ASCII line " + next);
            }
            return parseInt(values[0]);
        }

        float[] floats(int count) throws IOException {
            var values = values();
            if (values.length < count) {
                throw new IOException("Expected " + count + " numbers at XPS ASCII line " + next);
            }
            var result = new float[count];
            for (var i = 0; i < count; i++) {
                result[i] = parseFloat(values[i]);
            }
            return result;
        }

        static int parseInt(String value) throws IOException {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException invalid) {
                throw new IOException("Invalid XPS integer '" + value + "'", invalid);
            }
        }

        static float parseFloat(String value) throws IOException {
            try {
                return Float.parseFloat(value);
            } catch (NumberFormatException invalid) {
                throw new IOException("Invalid XPS number '" + value + "'", invalid);
            }
        }
    }
}
