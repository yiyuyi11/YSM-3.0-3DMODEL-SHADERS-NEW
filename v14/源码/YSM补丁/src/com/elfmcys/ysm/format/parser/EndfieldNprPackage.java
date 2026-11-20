package com.elfmcys.ysm.format.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jetbrains.annotations.Nullable;

/**
 * Carries a VRM's {@code ENDFIELD_npr} glTF extension (the endfield_npr render mod's per-model package: lookup textures,
 * face/iris/overlay-card placement and material parameters) through the humanoid import into the converted model, so
 * it reaches clients with the model itself (the player render target's data blob, entry {@link #GEO_KEY}), under
 * YSM's own transfer, authorization and cache.
 *
 * <p>The extension is copied as it is with two rewrites, so this class knows nothing of the render mod's schema:
 * <ul>
 * <li>an object with {@code "material": "<glTF material name>"} gains {@code "rect": [x, y, w, h]}, that material's
 * region in the atlas this import built, in atlas texels; an optional {@code "uv": [u, v, w, h]} narrows it to that
 * part of the material's own 0..1 texture space;</li>
 * <li>an object with {@code "image": <glTF image index>} gets {@code "file": "images/<index>.png"} instead, and the
 * image's PNG bytes are stored under that name.</li>
 * </ul>
 * The top level gains {@code "atlas": [width, height]}. The result is a ZIP of {@code profile.json} and the images.
 * A malformed extension fails the import with a message naming the problem (shown by {@code /ysm model errors}).
 */
final class EndfieldNprPackage {
    static final String EXTENSION = "ENDFIELD_npr";
    /** Key of the package in the player target's {@code ModelData.geo_models}; YSM only reads "main" and "arm". */
    static final String GEO_KEY = "endfield_npr";
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;
    private static final byte[] PNG = {(byte)0x89, 'P', 'N', 'G'};

    private EndfieldNprPackage() {}

    /**
     * The package of a VRM file, or null without the extension.
     *
     * @param materials the import's materials, indexed as the atlas regions (names are glTF material names)
     */
    @Nullable
    static byte[] build(ByteBuffer glb, List<HumanoidSource.Material> materials, TextureAtlas atlas)
        throws IOException {
        var data = glb.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        var base = data.position();
        if (data.remaining() < 20 || data.getInt(base) != GLB_MAGIC) {
            return null;
        }
        JsonObject document = null;
        ByteBuffer binary = null;
        var length = Math.min(data.getInt(base + 8), data.remaining());
        for (var at = 12; at + 8 <= length; ) {
            var size = data.getInt(base + at);
            var kind = data.getInt(base + at + 4);
            if (size < 0 || at + 8 + size > length) {
                throw new IOException(EXTENSION + ": truncated GLB chunk");
            }
            var chunk = data.slice(base + at + 8, size).order(ByteOrder.LITTLE_ENDIAN);
            if (kind == CHUNK_JSON) {
                var bytes = new byte[size];
                chunk.get(bytes);
                document = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            } else if (kind == CHUNK_BIN && binary == null) {
                binary = chunk;
            }
            at += 8 + size;
        }
        if (document == null || !document.has("extensions")
            || !document.getAsJsonObject("extensions").has(EXTENSION)) {
            return null;
        }
        var profile = document.getAsJsonObject("extensions").getAsJsonObject(EXTENSION).deepCopy();
        var images = new TreeMap<Integer, byte[]>();
        var rewriter = new Rewriter(document, binary, materials, atlas, images);
        rewriter.walk(profile, "extension");
        var atlasSize = new JsonArray();
        atlasSize.add(atlas.width);
        atlasSize.add(atlas.height);
        profile.add("atlas", atlasSize);

        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("profile.json"));
            zip.write(new Gson().toJson(profile).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Map.Entry<Integer, byte[]> image : images.entrySet()) {
                var entry = new ZipEntry(imageFile(image.getKey()));
                entry.setMethod(ZipEntry.DEFLATED);
                zip.putNextEntry(entry);
                zip.write(image.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static String imageFile(int index) {
        return "images/" + index + ".png";
    }

    private record Rewriter(JsonObject document, @Nullable ByteBuffer binary, List<HumanoidSource.Material> materials,
        TextureAtlas atlas, Map<Integer, byte[]> images) {
        void walk(JsonElement element, String path) throws IOException {
            if (element.isJsonArray()) {
                var array = element.getAsJsonArray();
                for (var i = 0; i < array.size(); i++) {
                    walk(array.get(i), path + "[" + i + "]");
                }
                return;
            }
            if (!element.isJsonObject()) {
                return;
            }
            var object = element.getAsJsonObject();
            for (var key : List.copyOf(object.keySet())) {
                walk(object.get(key), path + "." + key);
            }
            if (object.has("material")) {
                object.add("rect", rect(object, path));
            }
            if (object.has("image")) {
                var index = integer(object.get("image"), path + ".image");
                images.put(index, image(index, path));
                object.remove("image");
                object.addProperty("file", imageFile(index));
            }
        }

        /** The atlas texels of the named material (first used material of that name), narrowed by "uv". */
        private JsonArray rect(JsonObject object, String path) throws IOException {
            var name = object.get("material").getAsString();
            var out = new float[4];
            var found = false;
            for (var i = 0; i < materials.size() && !found; i++) {
                if (!name.equals(materials.get(i).name())) {
                    continue;
                }
                try {
                    atlas.remap(i, 0f, 0f, out, 0);
                    atlas.remap(i, 1f, 1f, out, 2);
                    found = true;
                } catch (NullPointerException unused) {
                    // a material no triangle uses has no region; a later one of the same name may
                }
            }
            if (!found) {
                throw new IOException(EXTENSION + ": " + path + " names material '" + name
                    + "', which is not a used material of this model");
            }
            float x = Math.round(out[0] * atlas.width);
            float y = Math.round(out[1] * atlas.height);
            var w = Math.round(out[2] * atlas.width) - x;
            var h = Math.round(out[3] * atlas.height) - y;
            var uv = new float[] {0f, 0f, 1f, 1f};
            if (object.has("uv")) {
                var array = object.getAsJsonArray("uv");
                if (array.size() != 4) {
                    throw new IOException(EXTENSION + ": " + path + ".uv must be [u, v, width, height]");
                }
                for (var i = 0; i < 4; i++) {
                    uv[i] = array.get(i).getAsFloat();
                }
            }
            var rect = new JsonArray();
            rect.add(x + uv[0] * w);
            rect.add(y + uv[1] * h);
            rect.add(uv[2] * w);
            rect.add(uv[3] * h);
            return rect;
        }

        private byte[] image(int index, String path) throws IOException {
            var list = document.getAsJsonArray("images");
            if (list == null || index < 0 || index >= list.size()) {
                throw new IOException(EXTENSION + ": " + path + " refers to missing image " + index);
            }
            var image = list.get(index).getAsJsonObject();
            if (!image.has("bufferView") || binary == null) {
                throw new IOException(EXTENSION + ": image " + index + " is not embedded in the GLB buffer");
            }
            var view = document.getAsJsonArray("bufferViews")
                .get(integer(image.get("bufferView"), "images[" + index + "].bufferView")).getAsJsonObject();
            if (view.has("buffer") && view.get("buffer").getAsInt() != 0) {
                throw new IOException(EXTENSION + ": image " + index + " is not in the GLB buffer");
            }
            var offset = view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0;
            var size = view.get("byteLength").getAsInt();
            if (offset < 0 || size < PNG.length || offset + size > binary.limit()) {
                throw new IOException(EXTENSION + ": image " + index + " lies outside the GLB buffer");
            }
            var bytes = new byte[size];
            binary.get(offset, bytes);
            for (var i = 0; i < PNG.length; i++) {
                if (bytes[i] != PNG[i]) {
                    throw new IOException(EXTENSION + ": image " + index + " is not a PNG");
                }
            }
            return bytes;
        }

        private static int integer(JsonElement element, String path) throws IOException {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new IOException(EXTENSION + ": " + path + " must be an index");
            }
            return element.getAsInt();
        }
    }
}
