package com.ysmnpr.endfield;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.jetbrains.annotations.Nullable;

/**
 * The model-bundled NPR package as the patched YSM import stores it (format 1, ~/ysm/npr/BUNDLE-CONTRACT.md): a ZIP of
 * {@code profile.json} and {@code images/*.png} in the player render target's {@code ModelData.geo_models}, entry
 * {@link #GEO_KEY}. The importer resolved every placement to {@code "rect": [x, y, w, h]} in atlas texels and every
 * texture to {@code "file"}. Plain Java (no Minecraft classes), so it is checked offline against converted data.
 */
final class ModelPackageData {
    static final String GEO_KEY = "endfield_npr";
    static final int FORMAT = 1;
    /** Ramp atlas rows in order (NprTextures.ROWS): RD ramps, then the 2D cloth and 1D hair specular ramps. */
    static final String[] RAMPS = {"clothRD", "skinRD", "faceRD", "hairRD", "eyeRD", "clothRS", "hairRS"};
    /** Per-model shading parameters (NprRenderer uniforms); a missing one takes ModelPackage's generic value. */
    static final String[] SHADING = {"skin", "face", "hair", "cloth", "eye", "skinRimOffScale", "faceSkinRimOffScale",
        "faceRimOffScale", "sdfRimColor", "hairAniso", "hairSpecMask", "hairAniso2", "hairAnisoColor2", "outlineWidth",
        "outlineOffsetZ", "outlineColorSkinCloth", "outlineColorHairEye"};
    private static final long MAX_FILE = 64L << 20;

    /** One OverlayShadow layer; {@code cardRect} in atlas texels, {@code texture} a file of the package. */
    record Overlay(String card, float[] cardRect, String texture, float[] baseColor, float useGrayAsAlpha,
        int stencilRef, boolean disableDrawUnderHair, float angleRange) {
    }

    /** One map of a material ({@code materials.<name>.maps.<kind>}): a package file and its Unity _ST tiling. */
    record MapRef(String file, float[] scale, float[] offset) {
    }

    /**
     * One entry of {@code materials} (~/ysm/handoff/npr-materials-schema.md): the glTF material, its atlas rectangle
     * (texels), the original Unity material's name, its maps by kind (NprMaps.KINDS) and its m_Floats/m_Colors
     * verbatim (a number as [v], a colour as [r, g, b, a]).
     */
    record Material(String name, float[] rect, @Nullable String source, Map<String, MapRef> maps,
        Map<String, float[]> params) {
    }

    /**
     * A parsed package. {@code textures} maps a role ("ramp.clothRD", "skinLut", "face.sdf", "face.sdfMask",
     * "face.highlight", "iris.matcap") to a file; {@code files} holds every file's bytes; {@code materials} is the
     * per-material map/parameter list in profile order.
     */
    record Parsed(float[] atlas, @Nullable float[] faceRect, @Nullable float[] irisRect, Map<String, float[]> shading,
        @Nullable float[] highlightVector, @Nullable float[] matcapColor, float matcapNormalScale,
        List<Overlay> overlays, Map<String, String> textures, Map<String, byte[]> files,
        Map<String, Material> materials) {
    }

    /** No package: every value generic, no textures. */
    static final Parsed EMPTY = new Parsed(new float[] {1, 1}, null, null, Map.of(), null, null, 1f, List.of(),
        Map.of(), Map.of(), Map.of());

    private ModelPackageData() {}

    /**
     * The value of {@code key} in field 1 ({@code map<string, bytes> geo_models}) of a serialized ModelData, or null.
     * Other fields (the animation files) are skipped unparsed.
     */
    @Nullable
    static byte[] findGeoEntry(ByteBuffer modelData, String key) throws IOException {
        var in = modelData.duplicate();
        var wanted = key.getBytes(StandardCharsets.UTF_8);
        while (in.hasRemaining()) {
            var tag = (int)varint(in);
            if (tag >>> 3 == 1 && (tag & 7) == 2) {
                var entry = slice(in);
                byte[] name = null;
                ByteBuffer value = null;
                while (entry.hasRemaining()) {
                    var inner = (int)varint(entry);
                    if ((inner & 7) == 2 && (inner >>> 3 == 1 || inner >>> 3 == 2)) {
                        var field = slice(entry);
                        if (inner >>> 3 == 1) {
                            name = new byte[field.remaining()];
                            field.get(name);
                        } else {
                            value = field;
                        }
                    } else {
                        skip(entry, inner & 7);
                    }
                }
                if (name != null && value != null && java.util.Arrays.equals(name, wanted)) {
                    var out = new byte[value.remaining()];
                    value.get(out);
                    return out;
                }
            } else {
                skip(in, tag & 7);
            }
        }
        return null;
    }

    private static long varint(ByteBuffer in) throws IOException {
        long value = 0;
        for (var shift = 0; shift < 64; shift += 7) {
            if (!in.hasRemaining()) {
                throw new IOException("truncated varint");
            }
            var b = in.get();
            value |= (long)(b & 0x7F) << shift;
            if (b >= 0) {
                return value;
            }
        }
        throw new IOException("malformed varint");
    }

    private static ByteBuffer slice(ByteBuffer in) throws IOException {
        var length = varint(in);
        if (length < 0 || length > in.remaining()) {
            throw new IOException("truncated field");
        }
        var out = in.slice(in.position(), (int)length);
        in.position(in.position() + (int)length);
        return out;
    }

    private static void skip(ByteBuffer in, int wireType) throws IOException {
        switch (wireType) {
            case 0 -> varint(in);
            case 1 -> in.position(in.position() + 8);
            case 2 -> slice(in);
            case 5 -> in.position(in.position() + 4);
            default -> throw new IOException("unsupported wire type " + wireType);
        }
    }

    static Parsed parse(byte[] zip) throws IOException {
        var files = new HashMap<String, byte[]>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (entry.isDirectory()) {
                    continue;
                }
                var bytes = in.readNBytes((int)MAX_FILE + 1);
                if (bytes.length > MAX_FILE) {
                    throw new IOException("package file " + entry.getName() + " is too large");
                }
                files.put(entry.getName(), bytes);
            }
        }
        var json = files.remove("profile.json");
        if (json == null) {
            throw new IOException("package has no profile.json");
        }
        var root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        var format = root.has("format") ? root.get("format").getAsInt() : FORMAT;
        if (format > FORMAT) {
            throw new IOException("package format " + format + " is newer than this renderer (" + FORMAT + ")");
        }
        var atlas = floats(root.get("atlas"), 2, "atlas");
        var textures = new LinkedHashMap<String, String>();
        var ramps = object(root, "ramps");
        if (ramps != null) {
            for (var name : RAMPS) {
                texture(ramps, name, "ramp." + name, textures, files);
            }
        }
        texture(root, "skinLut", "skinLut", textures, files);
        var face = object(root, "face");
        var iris = object(root, "iris");
        if (face != null) {
            texture(face, "sdf", "face.sdf", textures, files);
            texture(face, "sdfMask", "face.sdfMask", textures, files);
            texture(face, "highlight", "face.highlight", textures, files);
        }
        if (iris != null) {
            texture(iris, "matcap", "iris.matcap", textures, files);
        }
        var shading = new LinkedHashMap<String, float[]>();
        var shadingObject = object(root, "shading");
        if (shadingObject != null) {
            for (var name : SHADING) {
                if (shadingObject.has(name)) {
                    var value = shadingObject.get(name);
                    shading.put(name, value.isJsonArray() ? floats(value, value.getAsJsonArray().size(), name)
                        : new float[] {value.getAsFloat()});
                }
            }
        }
        var overlays = new ArrayList<Overlay>();
        if (root.has("overlays")) {
            var list = root.getAsJsonArray("overlays");
            for (var i = 0; i < list.size(); i++) {
                var layer = list.get(i).getAsJsonObject();
                var card = object(layer, "card");
                var texture = object(layer, "texture");
                if (card == null || !card.has("rect") || texture == null || !texture.has("file")) {
                    throw new IOException("overlays[" + i + "] needs card.rect and texture.file");
                }
                var file = file(texture, "overlays[" + i + "].texture", files);
                overlays.add(new Overlay(card.has("material") ? card.get("material").getAsString() : "card" + i,
                    floats(card.get("rect"), 4, "overlays[" + i + "].card.rect"), file,
                    layer.has("baseColor") ? floats(layer.get("baseColor"), 4, "baseColor") : new float[] {1, 1, 1, 1},
                    number(layer, "useGrayAsAlpha", 0f), (int)number(layer, "stencilRef", 4f),
                    layer.has("disableDrawUnderHair") && layer.get("disableDrawUnderHair").getAsBoolean(),
                    number(layer, "angleRange", 0f)));
            }
        }
        return new Parsed(atlas, rect(face, "face"), rect(iris, "iris"), Map.copyOf(shading),
            face != null && face.has("highlightVector") ? floats(face.get("highlightVector"), 3, "highlightVector")
                : null,
            iris != null && iris.has("matcapColor") ? floats(iris.get("matcapColor"), 4, "matcapColor") : null,
            iris != null ? number(iris, "matcapNormalScale", 1f) : 1f, List.copyOf(overlays), Map.copyOf(textures),
            Map.copyOf(files), materials(object(root, "materials"), files));
    }

    /** {@code materials}: entries without an importer rect (no "material" marker) are rejected like other markers. */
    private static Map<String, Material> materials(@Nullable JsonObject list, Map<String, byte[]> files)
        throws IOException {
        var out = new LinkedHashMap<String, Material>();
        if (list == null) {
            return out;
        }
        for (var name : list.keySet()) {
            var entry = object(list, name);
            var where = "materials." + name;
            if (entry == null || !entry.has("rect")) {
                throw new IOException(where + " needs a material marker (rect)");
            }
            var maps = new LinkedHashMap<String, MapRef>();
            var mapObject = object(entry, "maps");
            if (mapObject != null) {
                for (var kind : mapObject.keySet()) {
                    var map = object(mapObject, kind);
                    if (map == null) {
                        throw new IOException(where + ".maps." + kind + " must be an object");
                    }
                    maps.put(kind, new MapRef(file(map, where + ".maps." + kind, files),
                        map.has("scale") ? floats(map.get("scale"), 2, where + ".maps." + kind + ".scale")
                            : new float[] {1f, 1f},
                        map.has("offset") ? floats(map.get("offset"), 2, where + ".maps." + kind + ".offset")
                            : new float[] {0f, 0f}));
                }
            }
            var params = new LinkedHashMap<String, float[]>();
            var paramObject = object(entry, "params");
            if (paramObject != null) {
                for (var key : paramObject.keySet()) {
                    var value = paramObject.get(key);
                    if (value.isJsonArray()) {
                        params.put(key, floats(value, value.getAsJsonArray().size(), where + ".params." + key));
                    } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                        params.put(key, new float[] {value.getAsFloat()});
                    }
                }
            }
            out.put(name, new Material(entry.has("material") ? entry.get("material").getAsString() : name,
                floats(entry.get("rect"), 4, where + ".rect"),
                entry.has("source") ? entry.get("source").getAsString() : null, Map.copyOf(maps),
                Map.copyOf(params)));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    @Nullable
    private static JsonObject object(@Nullable JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonObject() ? parent.getAsJsonObject(name)
            : null;
    }

    @Nullable
    private static float[] rect(@Nullable JsonObject placement, String name) throws IOException {
        return placement != null && placement.has("rect") ? floats(placement.get("rect"), 4, name + ".rect") : null;
    }

    private static void texture(JsonObject parent, String name, String role, Map<String, String> textures,
        Map<String, byte[]> files) throws IOException {
        var texture = object(parent, name);
        if (texture != null) {
            textures.put(role, file(texture, role, files));
        }
    }

    private static String file(JsonObject texture, String where, Map<String, byte[]> files) throws IOException {
        var file = texture.has("file") ? texture.get("file").getAsString() : null;
        if (file == null || !files.containsKey(file)) {
            throw new IOException(where + " names a file the package does not hold: " + file);
        }
        return file;
    }

    private static float number(JsonObject object, String name, float fallback) {
        return object.has(name) ? object.get(name).getAsFloat() : fallback;
    }

    private static float[] floats(@Nullable JsonElement value, int n, String name) throws IOException {
        if (value == null || !value.isJsonArray() || ((JsonArray)value).size() < n) {
            throw new IOException(name + " must be an array of " + n + " numbers");
        }
        var array = value.getAsJsonArray();
        var out = new float[n];
        for (var i = 0; i < n; i++) {
            out[i] = array.get(i).getAsFloat();
        }
        return out;
    }
}
