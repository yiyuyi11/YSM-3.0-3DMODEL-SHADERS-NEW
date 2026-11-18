package com.elfmcys.ysm.format.parser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Set;

/**
 * Completes a source's humanoid bone map: guesses from common rig naming conventions, applies a user's humanoid.json,
 * derives hips and the pelvis bone from the hierarchy, and checks that the limbs form chains.
 */
final class HumanoidMapping {
    static final String OVERRIDE_FILE = "humanoid.json";
    /** Humanoid bones that may be mapped besides {@link HumanoidImport#REQUIRED_HUMANOID}. */
    static final Set<String> OPTIONAL =
        Set.of("neck", "chest", "lowerBody", "leftShoulder", "rightShoulder", "leftEye", "rightEye");

    private static final Set<String> EXCLUDED = Set.of("end", "nub", "twist", "roll", "tip", "helper", "ik", "ctrl",
        "control", "target", "pole", "adj", "null", "dummy", "ref", "socket", "share", "weapon", "attach", "fx",
        "finger", "thumb", "index", "middle", "ring", "pinky", "little", "toe", "toes", "eye", "jaw", "breast", "hair",
        "skirt", "cloth", "tail", "ear", "corrective", "deform", "scale", "offset");

    private HumanoidMapping() {}

    /**
     * Guesses humanoid bones for names following common conventions (XNALara, 3ds Max Biped, Valve, Mixamo, Unreal,
     * Unity). Only fills bones that are not mapped yet.
     */
    static void guessFromNames(HumanoidSource source) {
        var candidates = new HashMap<String, Integer>();
        for (var node = 0; node < source.nodeCount(); node++) {
            var tokens = tokens(source.nodeNames.get(node));
            if (tokens.isEmpty() || tokens.stream().anyMatch(EXCLUDED::contains)) {
                continue;
            }
            var concept = concept(tokens);
            if (concept == null) {
                continue;
            }
            var side = side(tokens);
            var key = switch (concept) {
                case "hips", "spine", "neck", "head" -> side == 0 ? concept : null;
                default -> side == 0 ? null : (side < 0 ? "left" : "right") + concept;
            };
            if (key == null) {
                continue;
            }
            // Several matches (spine, spine1, spine2): keep the one closest to the root.
            var existing = candidates.get(key);
            if (existing == null || depth(source, node) < depth(source, existing)) {
                candidates.put(key, node);
            }
        }
        candidates.forEach(source.humanoid::putIfAbsent);
    }

    /** Lower-case words split at case changes, digits and punctuation. */
    static List<String> tokens(String name) {
        var spaced = name.replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("([A-Za-z])([0-9])", "$1 $2")
            .replaceAll("([0-9])([A-Za-z])", "$1 $2").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return spaced.isEmpty() ? List.of() : List.of(spaced.split(" "));
    }

    /** -1 left, 1 right, 0 center. */
    private static int side(List<String> tokens) {
        if (tokens.contains("left") || tokens.contains("l")) {
            return -1;
        }
        if (tokens.contains("right") || tokens.contains("r")) {
            return 1;
        }
        return 0;
    }

    /** Humanoid bone suffix (UpperArm, Hand, ...) or center bone name; null when no rule matches. */
    private static String concept(List<String> tokens) {
        var t = new HashSet<>(tokens);
        if (t.contains("hand") || t.contains("wrist")) {
            return "Hand";
        }
        if (t.contains("forearm") || t.contains("lowerarm") || t.contains("elbow")
            || (t.contains("arm") && (t.contains("fore") || t.contains("lower")))) {
            return "LowerArm";
        }
        if (t.contains("upperarm") || (t.contains("arm") && t.contains("upper"))
            || (t.contains("arm") && t.contains("shoulder") && t.contains("2"))) {
            return "UpperArm";
        }
        if (t.contains("clavicle") || t.contains("collar")
            || (t.contains("shoulder") && (!t.contains("arm") || t.contains("1")))) {
            return "Shoulder";
        }
        if (t.contains("arm")) {
            return "UpperArm";
        }
        if (t.contains("thigh") || t.contains("upleg")
            || (t.contains("leg") && (t.contains("up") || t.contains("upper")))) {
            return "UpperLeg";
        }
        if (t.contains("foot") || t.contains("ankle")) {
            return "Foot";
        }
        if (t.contains("calf") || t.contains("knee") || t.contains("shin") || t.contains("lowerleg")
            || t.contains("leg")) {
            return "LowerLeg";
        }
        if (t.contains("neck")) {
            return "neck";
        }
        if (t.contains("head")) {
            return "head";
        }
        if (t.contains("spine") || t.contains("chest") || t.contains("torso")) {
            return "spine";
        }
        if (t.contains("hips") || t.contains("hip") || t.contains("pelvis")) {
            return "hips";
        }
        return null;
    }

    private static int depth(HumanoidSource source, int node) {
        var depth = 0;
        for (var current = source.skeletonParents.getInt(node); current >= 0 && depth <= source.nodeCount();
            current = source.skeletonParents.getInt(current)) {
            depth++;
        }
        return depth;
    }

    /** Range accepted for humanoid.json's height, in blocks. */
    static final float MIN_HEIGHT = 0.25f;
    static final float MAX_HEIGHT = 16f;
    /** Range accepted for humanoid.json's item_scale. */
    static final float MIN_ITEM_SCALE = 0.25f;
    static final float MAX_ITEM_SCALE = 4f;

    /**
     * Applies humanoid.json: an object from humanoid bone names (hips, spine, leftUpperArm, ...) to node names, an
     * optional "height": the rendered height in blocks from the feet to the top of the mesh, an optional "item_scale":
     * the size of held items relative to the default model's, an optional "outline": true to keep the model's own
     * toon outlines, and optional "cloth_colliders" / "cloth_follow": true to fit leg colliders to skirt chains / let
     * the upper skirt follow the thighs (both off by default, see {@link ClothColliders}). Listed bones replace any guess.
     */
    static void applyOverride(HumanoidSource source, ByteBuffer json) throws IOException {
        final JsonObject object;
        try {
            object =
                JsonParser.parseString(StandardCharsets.UTF_8.decode(json.duplicate()).toString()).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IOException(OVERRIDE_FILE + " is not a JSON object", invalid);
        }
        for (var entry : object.entrySet()) {
            if (entry.getKey().equals("height")) {
                source.height = number(entry.getValue(), "height", "a number of blocks", MIN_HEIGHT, MAX_HEIGHT);
                continue;
            }
            if (entry.getKey().equals("item_scale")) {
                source.itemScale = number(entry.getValue(), "item_scale", "a number", MIN_ITEM_SCALE, MAX_ITEM_SCALE);
                continue;
            }
            if (entry.getKey().equals("outline") || entry.getKey().equals("cloth_colliders")
                || entry.getKey().equals("cloth_follow")) {
                var value = entry.getValue();
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    throw new IOException(OVERRIDE_FILE + ": " + entry.getKey() + " must be true or false");
                }
                switch (entry.getKey()) {
                    case "outline" -> source.outline = value.getAsBoolean();
                    case "cloth_colliders" -> source.clothColliders = value.getAsBoolean();
                    default -> source.clothFollow = value.getAsBoolean();
                }
                continue;
            }
            if (!isKnownBone(entry.getKey())) {
                throw new IOException(OVERRIDE_FILE + ": unknown humanoid bone '" + entry.getKey() + "'");
            }
            var value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IOException(OVERRIDE_FILE + ": " + entry.getKey() + " must be a bone name string");
            }
            var node = source.nodeNames.indexOf(value.getAsString());
            if (node < 0) {
                throw new IOException(
                    OVERRIDE_FILE + ": bone '" + value.getAsString() + "' for " + entry.getKey() + " not found");
            }
            source.humanoid.put(entry.getKey(), node);
        }
    }

    private static float number(com.google.gson.JsonElement value, String key, String kind, float min, float max)
        throws IOException {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            var number = value.getAsFloat();
            if (number >= min && number <= max) {
                return number;
            }
        }
        throw new IOException(OVERRIDE_FILE + ": " + key + " must be " + kind + " from " + min + " to " + max);
    }

    /**
     * Derives hips as the closest common ancestor of the spine and the legs when it is missing or not such an ancestor,
     * and the pelvis bone (lowerBody) as the child of hips that carries both legs but not the spine.
     */
    static void complete(HumanoidSource source) throws IOException {
        var humanoid = source.humanoid;
        var spine = humanoid.get("spine");
        var leftLeg = humanoid.get("leftUpperLeg");
        var rightLeg = humanoid.get("rightUpperLeg");
        if (spine == null || leftLeg == null || rightLeg == null) {
            return;
        }
        var hips = humanoid.get("hips");
        if (hips == null || !isAncestor(source, hips, spine) || !isAncestor(source, hips, leftLeg)
            || !isAncestor(source, hips, rightLeg)) {
            var lower = humanoid.getOrDefault("lowerBody", leftLeg);
            hips = commonAncestor(source, spine, lower);
            if (hips < 0) {
                throw new IOException("The spine and legs share no ancestor bone to serve as hips");
            }
            humanoid.put("hips", hips);
        }
        if (humanoid.get("lowerBody") != null && humanoid.get("lowerBody").intValue() == hips) {
            humanoid.remove("lowerBody");
        }
        if (humanoid.get("lowerBody") == null) {
            // Walk up from the left thigh to the child of hips; a bone there other than the thigh is the pelvis.
            var current = leftLeg.intValue();
            for (var steps = 0;
                current >= 0 && source.skeletonParents.getInt(current) != hips && steps <= source.nodeCount();
                steps++) {
                current = source.skeletonParents.getInt(current);
            }
            if (current >= 0 && current != leftLeg && source.skeletonParents.getInt(current) == hips
                && isAncestor(source, current, rightLeg) && !isAncestor(source, current, spine)) {
                humanoid.put("lowerBody", current);
            }
        }
        if (spine.intValue() == hips) {
            throw new IOException("The spine bone is also the root of the legs");
        }
        guessFingers(source, "left");
        guessFingers(source, "right");
    }

    static final List<String> FINGERS = List.of("Thumb", "Index", "Middle", "Ring", "Little");
    private static final java.util.regex.Pattern FINGER_KEY = java.util.regex.Pattern
        .compile("(left|right)(Thumb|Index|Middle|Ring|Little)(Metacarpal|Proximal|Intermediate|Distal|[1-4])");
    private static final Set<String> FINGER_EXCLUDED =
        Set.of("end", "nub", "tip", "ik", "ctrl", "control", "target", "twist", "helper", "dummy", "null");

    /** Humanoid bone names accepted in humanoid.json. */
    static boolean isKnownBone(String key) {
        return HumanoidImport.REQUIRED_HUMANOID.contains(key) || OPTIONAL.contains(key)
            || FINGER_KEY.matcher(key).matches();
    }

    /**
     * When a hand has no mapped finger bones, finds them below it by name: thumb / index / middle / ring / pinky or
     * little, or numbered fingers (3ds Max Biped "Finger0" counts from the thumb, XNALara "finger 1" from 1). Segments
     * are keyed side + finger + 1..4 in hierarchy order.
     */
    private static void guessFingers(HumanoidSource source, String side) {
        var hand = source.humanoid.get(side + "Hand");
        if (hand == null || source.humanoid.keySet().stream()
            .anyMatch(key -> FINGER_KEY.matcher(key).matches() && key.startsWith(side))) {
            return;
        }
        var candidates = new HashMap<Integer, List<String>>();
        var zeroBased = false;
        for (var node = 0; node < source.nodeCount(); node++) {
            if (!isAncestor(source, hand, node)) {
                continue;
            }
            var tokens = tokens(source.nodeNames.get(node));
            if (tokens.stream().anyMatch(FINGER_EXCLUDED::contains)) {
                continue;
            }
            candidates.put(node, tokens);
            var digit = fingerDigit(tokens);
            zeroBased |= digit == 0;
        }
        var segments = new HashMap<String, List<Integer>>();
        for (var entry : candidates.entrySet()) {
            var finger = fingerOf(entry.getValue(), zeroBased);
            if (finger != null) {
                segments.computeIfAbsent(finger, ignored -> new ArrayList<>()).add(entry.getKey());
            }
        }
        for (var entry : segments.entrySet()) {
            var nodes = entry.getValue();
            nodes.sort(java.util.Comparator.comparingInt(node -> depth(source, node)));
            for (var i = 0; i < Math.min(nodes.size(), 4); i++) {
                source.humanoid.put(side + entry.getKey() + (i + 1), nodes.get(i));
            }
        }
    }

    private static String fingerOf(List<String> tokens, boolean zeroBased) {
        if (tokens.contains("thumb")) {
            return "Thumb";
        }
        if (tokens.contains("index") || tokens.contains("pointer")) {
            return "Index";
        }
        if (tokens.contains("middle")) {
            return "Middle";
        }
        if (tokens.contains("ring")) {
            return "Ring";
        }
        if (tokens.contains("pinky") || tokens.contains("little")) {
            return "Little";
        }
        var digit = fingerDigit(tokens);
        if (digit >= 0) {
            var index = digit - (zeroBased ? 0 : 1);
            return index >= 0 && index < FINGERS.size() ? FINGERS.get(index) : null;
        }
        return null;
    }

    /** First digit of the number following "finger" ("Finger12" gives 1), or -1. */
    private static int fingerDigit(List<String> tokens) {
        var at = tokens.indexOf("finger");
        if (at < 0 || at + 1 >= tokens.size()) {
            return -1;
        }
        var next = tokens.get(at + 1);
        return Character.isDigit(next.charAt(0)) ? next.charAt(0) - '0' : -1;
    }

    /** Throws listing every missing or out-of-chain humanoid bone. */
    static void validate(HumanoidSource source) throws IOException {
        var problems = new ArrayList<String>();
        for (var required : HumanoidImport.REQUIRED_HUMANOID) {
            var node = source.humanoid.get(required);
            if (node == null || node < 0 || node >= source.nodeCount()) {
                problems.add(required + " missing");
            }
        }
        if (problems.isEmpty()) {
            for (var side : List.of("left", "right")) {
                chain(source, problems, side + "UpperArm", side + "LowerArm");
                chain(source, problems, side + "LowerArm", side + "Hand");
                chain(source, problems, side + "UpperLeg", side + "LowerLeg");
                chain(source, problems, side + "LowerLeg", side + "Foot");
            }
        }
        if (!problems.isEmpty()) {
            throw new IOException("Cannot map humanoid bones (" + String.join(", ", problems) + "); add "
                + OVERRIDE_FILE + " next to the model mapping humanoid bone names to bone names");
        }
    }

    private static void chain(HumanoidSource source, List<String> problems, String upper, String lower) {
        if (!isAncestor(source, source.humanoid.get(upper), source.humanoid.get(lower))) {
            problems.add(lower + " is not below " + upper);
        }
    }

    static boolean isAncestor(HumanoidSource source, int ancestor, int node) {
        var steps = 0;
        for (var current = source.skeletonParents.getInt(node); current >= 0 && steps++ <= source.nodeCount();
            current = source.skeletonParents.getInt(current)) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static int commonAncestor(HumanoidSource source, int a, int b) {
        var ancestors = new HashSet<Integer>();
        for (var current = source.skeletonParents.getInt(a); current >= 0 && ancestors.add(current);
            current = source.skeletonParents.getInt(current)) {
        }
        var steps = 0;
        for (var current = source.skeletonParents.getInt(b); current >= 0 && steps++ <= source.nodeCount();
            current = source.skeletonParents.getInt(current)) {
            if (ancestors.contains(current)) {
                return current;
            }
        }
        return -1;
    }
}
