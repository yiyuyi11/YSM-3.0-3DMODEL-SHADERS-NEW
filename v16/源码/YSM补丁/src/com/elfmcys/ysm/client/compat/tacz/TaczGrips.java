package com.elfmcys.ysm.client.compat.tacz;

import com.elfmcys.ysm.client.compat.tacz.fp.TaczFpRuntime;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Fitted first-person grips on TACZ guns: per hand the palm position on the gun's {@code lefthand_pos} /
 * {@code righthand_pos} locator ({@code palm*}, model px) and the wrist turn ({@code wrist*}, degrees, the importer's
 * {@code ctrl.tac_grip('<side>_<axis>')} keyframes). The first-person profiles ({@code config/ysm_tacz_fp},
 * {@link TaczFpRuntime}) replace a gun's entry; the built-in table applies only to guns whose profile gives no grip.
 * Source of the v18 class (bytecode-equivalent apart from the profile lookup and hands that may be absent).
 */
public final class TaczGrips {
    private static final float ARM_SIDE = 4.0F;
    private static final float ARM_TOP = 28.8F;
    /** Built-in grips: the default when no profile gives one (the m4a1 entry is also m4a1.json's {@code *} grip). */
    private static final Map<String, Grip> GRIPS = Map.of(
        "tacz:m4a1", new Grip(new Hand(5.0F, 18.282F, 1.155F, 80.64F, -63.68F, -49.49F),
            new Hand(-7.092F, 13.806F, -1.283F, 38.73F, 0.0F, 9.0F)));

    private TaczGrips() {
    }

    /** The grip of {@code gunId} in {@code holder}'s hands: its profile's for the holder's model, else built in. */
    @Nullable
    public static Grip of(String gunId, LivingEntity holder) {
        return TaczFpRuntime.grip(gunId, holder, builtin(gunId));
    }

    /** The built-in grip of {@code gunId}. */
    @Nullable
    public static Grip builtin(String gunId) {
        return GRIPS.get(gunId);
    }

    public static float value(@Nullable Grip grip, String key) {
        if (grip == null) {
            return 0.0F;
        }
        return switch (key) {
            case "fitted" -> 1.0F;
            case "left_x" -> grip.left == null ? 0.0F : grip.left.wristX;
            case "left_y" -> grip.left == null ? 0.0F : grip.left.wristY;
            case "left_z" -> grip.left == null ? 0.0F : grip.left.wristZ;
            case "right_x" -> grip.right == null ? 0.0F : grip.right.wristX;
            case "right_y" -> grip.right == null ? 0.0F : grip.right.wristY;
            case "right_z" -> grip.right == null ? 0.0F : grip.right.wristZ;
            default -> 0.0F;
        };
    }

    /** Moves the arm so that its palm lands on the locator; a hand without a grip entry is left as it is. */
    public static void place(PoseStack stack, @Nullable Hand hand, boolean left, Vector3f wrist, Vector3f palm) {
        if (hand == null) {
            return;
        }
        Matrix4f pose = stack.m_85850_().m_252922_();
        float across = (float)Math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01() + pose.m02() * pose.m02());
        float along = (float)Math.sqrt(pose.m10() * pose.m10() + pose.m11() * pose.m11() + pose.m12() * pose.m12());
        if (along > 1.0E-6F) {
            stack.m_85841_(1.0F, across / along, 1.0F);
        }
        Vector3f shift = palmShift(hand, left, wrist, palm);
        stack.m_252880_(shift.x / 16.0F, shift.y / 16.0F, shift.z / 16.0F);
    }

    static Vector3f palmShift(Hand hand, boolean left, Vector3f wrist, Vector3f palm) {
        float side = left ? ARM_SIDE : -ARM_SIDE;
        Vector3f w = new Vector3f(side - wrist.x, ARM_TOP - wrist.y, wrist.z);
        Vector3f p = new Vector3f(side - palm.x, ARM_TOP - palm.y, palm.z);
        Matrix3f turn = new Matrix3f()
            .rotateZ((float)Math.toRadians(hand.wristZ))
            .rotateY((float)Math.toRadians(hand.wristY))
            .rotateX((float)Math.toRadians(hand.wristX));
        Vector3f turned = p.sub(w).mul(turn).add(w);
        return new Vector3f(hand.palmX, hand.palmY, hand.palmZ).sub(turned);
    }

    /** A grip; either hand may be null (no correction for that hand). */
    public record Grip(@Nullable Hand left, @Nullable Hand right) {
        @Nullable
        public Hand hand(boolean left) {
            return left ? this.left : this.right;
        }
    }

    public record Hand(float palmX, float palmY, float palmZ, float wristX, float wristY, float wristZ) {
    }
}
