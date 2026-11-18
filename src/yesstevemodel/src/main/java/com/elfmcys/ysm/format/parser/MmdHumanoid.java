package com.elfmcys.ysm.format.parser;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** MMD conventions shared by PMX and PMD: units and standard bone names. */
final class MmdHumanoid {
    /** Meters per MMD unit (a 20-unit character is about 1.6 m). */
    static final float UNIT = 0.08f;
    /**
     * Outline width in meters per unit of MMD edge size (material flag 0x10). MMD widens edges with camera distance; a
     * fixed width that reads as a thin line at the distances a player model is seen from stands in for it.
     */
    static final float EDGE_METERS = 0.008f;

    /** Standard Japanese bone names, then common English universal names. */
    private static final Map<String,
        List<String>> BONE_NAMES = Map.ofEntries(Map.entry("spine", List.of("上半身", "upper body", "upperbody")),
            Map.entry("chest", List.of("上半身2", "上半身２", "upper body2", "upperbody2")),
            Map.entry("lowerBody", List.of("下半身", "lower body", "lowerbody")), Map.entry("neck", List.of("首", "neck")),
            Map.entry("head", List.of("頭", "head")),
            Map.entry("leftShoulder", List.of("左肩", "shoulder_l", "left shoulder")),
            Map.entry("leftUpperArm", List.of("左腕", "arm_l", "left arm")),
            Map.entry("leftLowerArm", List.of("左ひじ", "左肘", "elbow_l", "left elbow")),
            Map.entry("leftHand", List.of("左手首", "wrist_l", "left wrist")),
            Map.entry("leftUpperLeg", List.of("左足", "leg_l", "left leg")),
            Map.entry("leftLowerLeg", List.of("左ひざ", "左膝", "knee_l", "left knee")),
            Map.entry("leftFoot", List.of("左足首", "ankle_l", "left ankle")),
            Map.entry("rightShoulder", List.of("右肩", "shoulder_r", "right shoulder")),
            Map.entry("rightUpperArm", List.of("右腕", "arm_r", "right arm")),
            Map.entry("rightLowerArm", List.of("右ひじ", "右肘", "elbow_r", "right elbow")),
            Map.entry("rightHand", List.of("右手首", "wrist_r", "right wrist")),
            Map.entry("rightUpperLeg", List.of("右足", "leg_r", "right leg")),
            Map.entry("rightLowerLeg", List.of("右ひざ", "右膝", "knee_r", "right knee")),
            Map.entry("rightFoot", List.of("右足首", "ankle_r", "right ankle")),
            Map.entry("leftEye", List.of("左目", "eye_l", "left eye")),
            Map.entry("rightEye", List.of("右目", "eye_r", "right eye")));

    /** Finger bones (VRM humanoid names) from the standard names, with full-width or ASCII digits. */
    private static final Map<String, List<String>> FINGER_NAMES = fingerNames();

    private static Map<String, List<String>> fingerNames() {
        var result = new java.util.LinkedHashMap<String, List<String>>();
        String[][] fingers = {{"Thumb", "親指", "Metacarpal:0", "Proximal:1", "Distal:2"},
            {"Index", "人指", "Proximal:1", "Intermediate:2", "Distal:3"},
            {"Middle", "中指", "Proximal:1", "Intermediate:2", "Distal:3"},
            {"Ring", "薬指", "Proximal:1", "Intermediate:2", "Distal:3"},
            {"Little", "小指", "Proximal:1", "Intermediate:2", "Distal:3"}};
        for (var side : new String[][] {{"left", "左"}, {"right", "右"}}) {
            for (var finger : fingers) {
                for (var i = 2; i < finger.length; i++) {
                    var segment = finger[i].split(":");
                    var digit = Integer.parseInt(segment[1]);
                    var japanese = side[1] + finger[1];
                    var names = new java.util.ArrayList<>(List.of(japanese + (char)('０' + digit), japanese + digit));
                    if (finger[0].equals("Index")) {
                        names.add(side[1] + "人差指" + (char)('０' + digit));
                    }
                    result.put(side[0] + finger[0] + segment[0], List.copyOf(names));
                }
            }
        }
        return Map.copyOf(result);
    }

    /** Standard MMD morph names (with common spelling variants) to VRM 1.0 expression presets. */
    private static final Map<String,
        String> EXPRESSIONS = Map.ofEntries(Map.entry("まばたき", "blink"), Map.entry("ウィンク", "blinkLeft"),
            Map.entry("ウィンク右", "blinkRight"), Map.entry("ウインク", "blinkLeft"), Map.entry("ウインク右", "blinkRight"),
            Map.entry("あ", "aa"), Map.entry("い", "ih"), Map.entry("う", "ou"), Map.entry("え", "ee"),
            Map.entry("お", "oh"), Map.entry("笑い", "happy"), Map.entry("怒り", "angry"), Map.entry("困る", "sad"),
            Map.entry("なごみ", "relaxed"), Map.entry("びっくり", "surprised"));

    private MmdHumanoid() {}

    /** The VRM preset for a standard morph name, else the trimmed name itself. */
    static String expressionName(String morphName) {
        var trimmed = morphName.trim();
        return EXPRESSIONS.getOrDefault(trimmed, trimmed);
    }

    /**
     * Fills {@link HumanoidSource#humanoid} from bone names; universal names are consulted when a local name is
     * missing. Hips is derived later as the closest common ancestor of the upper and lower body.
     */
    static void mapHumanoid(HumanoidSource source, List<String> universalNames) {
        for (var names : List.of(BONE_NAMES, FINGER_NAMES)) {
            for (var entry : names.entrySet()) {
                var node = find(source.nodeNames, entry.getValue());
                if (node < 0 && universalNames != null) {
                    node = find(universalNames, entry.getValue());
                }
                if (node >= 0) {
                    source.humanoid.put(entry.getKey(), node);
                }
            }
        }
    }

    private static int find(List<String> names, List<String> candidates) {
        for (var candidate : candidates) {
            for (var i = 0; i < names.size(); i++) {
                if (names.get(i).trim().toLowerCase(Locale.ROOT).equals(candidate)) {
                    return i;
                }
            }
        }
        return -1;
    }
}
