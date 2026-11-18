package com.elfmcys.ysm.format.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.Nullable;

/** Binary glTF 2.0 container: JSON document plus the embedded BIN chunk, with typed accessor reads. */
final class GlbDocument {
    private static final int MAGIC = 0x46546C67;
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;

    private static final int BYTE = 5120;
    private static final int UNSIGNED_BYTE = 5121;
    private static final int SHORT = 5122;
    private static final int UNSIGNED_SHORT = 5123;
    private static final int UNSIGNED_INT = 5125;
    private static final int FLOAT = 5126;

    final JsonObject json;
    @Nullable
    private final ByteBuffer bin;

    private GlbDocument(JsonObject json, @Nullable ByteBuffer bin) {
        this.json = json;
        this.bin = bin;
    }

    static GlbDocument parse(ByteBuffer data) throws IOException {
        var buffer = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        var base = buffer.position();
        if (buffer.remaining() < 12 || buffer.getInt(base) != MAGIC) {
            throw new IOException("Not a binary glTF (GLB) file");
        }
        if (buffer.getInt(base + 4) != 2) {
            throw new IOException("Unsupported GLB version " + buffer.getInt(base + 4));
        }
        var length = buffer.getInt(base + 8);
        if (length < 12 || length > buffer.remaining()) {
            throw new IOException("Truncated GLB file");
        }
        JsonObject json = null;
        ByteBuffer bin = null;
        var offset = 12;
        while (offset + 8 <= length) {
            var chunkLength = buffer.getInt(base + offset);
            var type = buffer.getInt(base + offset + 4);
            offset += 8;
            if (chunkLength < 0 || chunkLength > length - offset) {
                throw new IOException("Truncated GLB chunk");
            }
            var chunk = buffer.slice(base + offset, chunkLength).order(ByteOrder.LITTLE_ENDIAN);
            if (type == CHUNK_JSON && json == null) {
                json = JsonParser.parseString(StandardCharsets.UTF_8.decode(chunk).toString()).getAsJsonObject();
            } else if (type == CHUNK_BIN && bin == null) {
                bin = chunk;
            }
            offset += (chunkLength + 3) & ~3;
        }
        if (json == null) {
            throw new IOException("GLB file has no JSON chunk");
        }
        return new GlbDocument(json, bin);
    }

    JsonArray array(String name) {
        var value = json.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    JsonObject element(String collection, int index) throws IOException {
        var array = array(collection);
        if (index < 0 || index >= array.size() || !array.get(index).isJsonObject()) {
            throw new IOException("Invalid glTF " + collection + " index " + index);
        }
        return array.get(index).getAsJsonObject();
    }

    /** Raw bytes of a buffer view in the embedded BIN chunk. */
    ByteBuffer bufferView(int index) throws IOException {
        var view = element("bufferViews", index);
        if (intOr(view, "buffer", 0) != 0 || bin == null) {
            throw new IOException("glTF buffer view " + index + " does not reference the GLB BIN chunk");
        }
        var offset = intOr(view, "byteOffset", 0);
        var length = requiredInt(view, "byteLength");
        if (offset < 0 || length < 0 || offset > bin.capacity() - length) {
            throw new IOException("glTF buffer view " + index + " is out of range");
        }
        return bin.slice(offset, length).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Accessor elements as floats; normalized integer components map to [0, 1] or [-1, 1]. */
    float[] readFloats(int accessorIndex, int components) throws IOException {
        var accessor = element("accessors", accessorIndex);
        checkComponents(accessor, components, accessorIndex);
        var count = requiredInt(accessor, "count");
        var componentType = requiredInt(accessor, "componentType");
        var normalized = accessor.has("normalized") && accessor.get("normalized").getAsBoolean();
        var output = new float[Math.multiplyExact(count, components)];
        if (accessor.has("bufferView")) {
            var reader = elementReader(accessor, components, componentType);
            for (var i = 0; i < count; i++) {
                for (var c = 0; c < components; c++) {
                    output[i * components + c] = reader.readFloat(i, c, normalized);
                }
            }
        }
        applySparse(accessor, components, (index, reader) -> {
            for (var c = 0; c < components; c++) {
                output[index * components + c] = reader.readFloat(0, c, normalized);
            }
        });
        for (var value : output) {
            if (!Float.isFinite(value)) {
                throw new IOException("glTF accessor " + accessorIndex + " contains a non-finite value");
            }
        }
        return output;
    }

    /** Accessor elements as non-negative integers (indices, joint ids). */
    int[] readInts(int accessorIndex, int components) throws IOException {
        var accessor = element("accessors", accessorIndex);
        checkComponents(accessor, components, accessorIndex);
        var count = requiredInt(accessor, "count");
        var componentType = requiredInt(accessor, "componentType");
        var output = new int[Math.multiplyExact(count, components)];
        if (accessor.has("bufferView")) {
            var reader = elementReader(accessor, components, componentType);
            for (var i = 0; i < count; i++) {
                for (var c = 0; c < components; c++) {
                    output[i * components + c] = reader.readInt(i, c);
                }
            }
        }
        applySparse(accessor, components, (index, reader) -> {
            for (var c = 0; c < components; c++) {
                output[index * components + c] = reader.readInt(0, c);
            }
        });
        return output;
    }

    int accessorCount(int accessorIndex) throws IOException {
        return requiredInt(element("accessors", accessorIndex), "count");
    }

    private void applySparse(JsonObject accessor, int components, SparseSink sink) throws IOException {
        if (!accessor.has("sparse")) {
            return;
        }
        var sparse = accessor.getAsJsonObject("sparse");
        var count = requiredInt(sparse, "count");
        var indices = sparse.getAsJsonObject("indices");
        var values = sparse.getAsJsonObject("values");
        var indexReader = new ElementReader(bufferView(requiredInt(indices, "bufferView")),
            intOr(indices, "byteOffset", 0), 0, 1, requiredInt(indices, "componentType"), count);
        var valueBuffer = bufferView(requiredInt(values, "bufferView"));
        var componentType = requiredInt(accessor, "componentType");
        var limit = requiredInt(accessor, "count");
        for (var i = 0; i < count; i++) {
            var target = indexReader.readInt(i, 0);
            if (target < 0 || target >= limit) {
                throw new IOException("glTF sparse index out of range");
            }
            var offset = intOr(values, "byteOffset", 0) + i * components * componentSize(componentType);
            sink.accept(target, new ElementReader(valueBuffer, offset, 0, components, componentType, 1));
        }
    }

    private ElementReader elementReader(JsonObject accessor, int components, int componentType) throws IOException {
        var viewIndex = requiredInt(accessor, "bufferView");
        var view = bufferView(viewIndex);
        var stride = intOr(element("bufferViews", viewIndex), "byteStride", 0);
        return new ElementReader(view, intOr(accessor, "byteOffset", 0), stride, components, componentType,
            requiredInt(accessor, "count"));
    }

    private static void checkComponents(JsonObject accessor, int components, int index) throws IOException {
        var type = accessor.has("type") ? accessor.get("type").getAsString() : "";
        var expected = switch (components) {
            case 1 -> "SCALAR";
            case 2 -> "VEC2";
            case 3 -> "VEC3";
            case 4 -> "VEC4";
            case 16 -> "MAT4";
            default -> throw new IllegalArgumentException("components " + components);
        };
        if (!expected.equals(type)) {
            throw new IOException("glTF accessor " + index + " has type " + type + ", expected " + expected);
        }
    }

    static int componentSize(int componentType) throws IOException {
        return switch (componentType) {
            case BYTE, UNSIGNED_BYTE -> 1;
            case SHORT, UNSIGNED_SHORT -> 2;
            case UNSIGNED_INT, FLOAT -> 4;
            default -> throw new IOException("Unsupported glTF component type " + componentType);
        };
    }

    static int intOr(JsonObject object, String name, int fallback) {
        var value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }

    static int requiredInt(JsonObject object, String name) throws IOException {
        var value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IOException("glTF object is missing '" + name + "'");
        }
        return value.getAsInt();
    }

    static float floatOr(@Nullable JsonElement value, float fallback) {
        return value != null && value.isJsonPrimitive() ? value.getAsFloat() : fallback;
    }

    @FunctionalInterface
    private interface SparseSink {
        void accept(int index, ElementReader reader) throws IOException;
    }

    private static final class ElementReader {
        private final ByteBuffer buffer;
        private final int offset;
        private final int stride;
        private final int componentType;
        private final int componentSize;

        ElementReader(ByteBuffer buffer, int offset, int stride, int components, int componentType, int count)
            throws IOException {
            this.buffer = buffer;
            this.offset = offset;
            this.componentType = componentType;
            this.componentSize = componentSize(componentType);
            var packed = components * componentSize;
            this.stride = stride > 0 ? stride : packed;
            if (count > 0
                && (offset < 0 || (long)offset + (long)this.stride * (count - 1) + packed > buffer.capacity())) {
                throw new IOException("glTF accessor exceeds its buffer view");
            }
        }

        private int position(int element, int component) {
            return offset + element * stride + component * componentSize;
        }

        int readInt(int element, int component) throws IOException {
            var at = position(element, component);
            return switch (componentType) {
                case UNSIGNED_BYTE -> Byte.toUnsignedInt(buffer.get(at));
                case UNSIGNED_SHORT -> Short.toUnsignedInt(buffer.getShort(at));
                case UNSIGNED_INT -> {
                    var value = buffer.getInt(at);
                    if (value < 0) {
                        throw new IOException("glTF index exceeds 2^31");
                    }
                    yield value;
                }
                default -> throw new IOException("glTF integer accessor has component type " + componentType);
            };
        }

        float readFloat(int element, int component, boolean normalized) {
            var at = position(element, component);
            return switch (componentType) {
                case FLOAT -> buffer.getFloat(at);
                case UNSIGNED_BYTE ->
                    normalized ? Byte.toUnsignedInt(buffer.get(at)) / 255f : Byte.toUnsignedInt(buffer.get(at));
                case BYTE -> normalized ? Math.max(buffer.get(at) / 127f, -1f) : buffer.get(at);
                case UNSIGNED_SHORT -> normalized ? Short.toUnsignedInt(buffer.getShort(at)) / 65535f
                    : Short.toUnsignedInt(buffer.getShort(at));
                case SHORT -> normalized ? Math.max(buffer.getShort(at) / 32767f, -1f) : buffer.getShort(at);
                default -> buffer.getInt(at);
            };
        }
    }
}
