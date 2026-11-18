package com.elfmcys.ysm.client.compat.tacz;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * First-person hand placements fitted to individual TACZ guns. TACZ poses each first-person arm as the vanilla 4 by 12
 * pixel block, with its end on the gun, and the default gun pack's idle animation stretches that block to 1.5 times its
 * length. An imported humanoid's arm is a person's arm, much thinner, with a hand and fingers: on a fitted gun the
 * stretch is undone, the wrist turns the hand onto the gun (Molang {@code ctrl.tac_grip}), and the arm slides along so
 * the palm lands where the fit put it ({@link #place}). Everything follows TACZ's hand bones, so drawing, aiming,
 * reloading and inspecting move the hands with the gun.
 *
 * <p>
 * Palms are in the block arm's pixels before the stretch (x outward from the body, y from the shoulder toward the hand,
 * z the block's depth), wrist turns in keyframe degrees. They were fitted offline against the default gun pack's models
 * with an imported arm: the gun hand's palm on the right side of the pistol grip, the fingers wrapped round its front
 * and the trigger finger straight along the frame; the support hand under the handguard, fingers curled up its far
 * side. A gun without an entry keeps the block arm's placement, the hands closed into fists.
 */
public final class TaczGrips {
    /** CustomFirstPersonArmRenderer places a model pixel (x, y, z) at (side - x, 28.8 - y, z) in block arm pixels. */
    private static final float ARM_SIDE = 4f;
    private static final float ARM_TOP = 28.8f;
    private static final Map<String, Grip> GRIPS =
        Map.of("tacz:m4a1", new Grip(new Hand(5f, 18.282f, 1.155f, 80.64f, -63.68f, -49.49f),
            new Hand(-7.092f, 13.806f, -1.283f, 38.73f, 0f, 9f)));

    private TaczGrips() {}

    /** One hand on a gun: the palm's place and the wrist's turn. */
    public record Hand(float palmX, float palmY, float palmZ, float wristX, float wristY, float wristZ) {
    }

    public record Grip(Hand left, Hand right) {
        public Hand hand(boolean left) {
            return left ? this.left : right;
        }
    }

    /** The fitted grip of a gun, by its TACZ id, or null. */
    @Nullable
    public static Grip of(String gunId) {
        return GRIPS.get(gunId);
    }

    /**
     * The Molang value {@code ctrl.tac_grip(key)}: "fitted" is 1 for a gun with a grip, "left_x" to "right_z" the wrist
     * turns; 0 otherwise.
     */
    public static float value(@Nullable Grip grip, String key) {
        if (grip == null) {
            return 0f;
        }
        return switch (key) {
            case "fitted" -> 1f;
            case "left_x" -> grip.left.wristX;
            case "left_y" -> grip.left.wristY;
            case "left_z" -> grip.left.wristZ;
            case "right_x" -> grip.right.wristX;
            case "right_y" -> grip.right.wristY;
            case "right_z" -> grip.right.wristZ;
            default -> 0f;
        };
    }

    /**
     * Moves the pose stack TACZ hands the block arm to onto the fitted grip: undoes the stretch along the arm and
     * slides the arm so its palm, turned at the wrist, lands on the hand's palm.
     *
     * @param wrist the arm model's wrist pivot, in model pixels
     * @param palm the arm model's palm (hand locator) pivot, in model pixels
     */
    public static void place(PoseStack stack, Hand hand, boolean left, Vector3f wrist, Vector3f palm) {
        var pose = stack.last().pose();
        var across = (float)Math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01() + pose.m02() * pose.m02());
        var along = (float)Math.sqrt(pose.m10() * pose.m10() + pose.m11() * pose.m11() + pose.m12() * pose.m12());
        if (along > 1e-6f) {
            stack.scale(1f, across / along, 1f);
        }
        var shift = palmShift(hand, left, wrist, palm);
        stack.translate(shift.x / 16f, shift.y / 16f, shift.z / 16f);
    }

    /** Block arm pixels the arm slides so its palm, turned at the wrist, lands on the hand's palm. */
    static Vector3f palmShift(Hand hand, boolean left, Vector3f wrist, Vector3f palm) {
        var side = left ? ARM_SIDE : -ARM_SIDE;
        var w = new Vector3f(side - wrist.x, ARM_TOP - wrist.y, wrist.z);
        var p = new Vector3f(side - palm.x, ARM_TOP - palm.y, palm.z);
        // A keyframe turns the model by Z, -Y, -X; in block arm axes, which flip x and y, that is Z, Y, X.
        var turn = new Matrix3f().rotateZ((float)Math.toRadians(hand.wristZ))
            .rotateY((float)Math.toRadians(hand.wristY)).rotateX((float)Math.toRadians(hand.wristX));
        var turned = p.sub(w).mul(turn).add(w);
        return new Vector3f(hand.palmX, hand.palmY, hand.palmZ).sub(turned);
    }
}
