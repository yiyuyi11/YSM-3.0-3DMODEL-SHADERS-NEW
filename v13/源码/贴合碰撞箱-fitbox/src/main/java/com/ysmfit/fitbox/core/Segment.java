package com.ysmfit.fitbox.core;

/**
 * Body parts that get one hit primitive each, keyed by YSM's player skeleton names (the default model's and the
 * humanoid importer's). A bone belongs to the part of its nearest ancestor-or-self that is a part root.
 */
public enum Segment {
    HEAD(Shape.CAPSULE, "Head", "MHead", "AllHead"),
    CHEST(Shape.BOX, "UpBody", "UpperBody"),
    PELVIS(Shape.BOX, "AllBody", "MAllBody", "DownBody"),
    LEFT_UPPER_ARM(Shape.CAPSULE, "LeftArm"),
    LEFT_FOREARM(Shape.CAPSULE, "LeftForeArm"),
    LEFT_HAND(Shape.BOX, "LeftHand"),
    RIGHT_UPPER_ARM(Shape.CAPSULE, "RightArm"),
    RIGHT_FOREARM(Shape.CAPSULE, "RightForeArm"),
    RIGHT_HAND(Shape.BOX, "RightHand"),
    LEFT_THIGH(Shape.CAPSULE, "LeftLeg"),
    LEFT_SHIN(Shape.CAPSULE, "LeftLowerLeg"),
    LEFT_FOOT(Shape.BOX, "LeftFoot"),
    RIGHT_THIGH(Shape.CAPSULE, "RightLeg"),
    RIGHT_SHIN(Shape.CAPSULE, "RightLowerLeg"),
    RIGHT_FOOT(Shape.BOX, "RightFoot"),
    /** Fallback for skeletons without the humanoid part names: one box over the whole core body. */
    BODY(Shape.BOX, "Root");

    public final byte kind;
    public final String[] roots;

    Segment(byte kind, String... roots) {
        this.kind = kind;
        this.roots = roots;
    }

    public boolean isHead() {
        return this == HEAD;
    }
}
