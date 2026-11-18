package com.elfmcys.ysm.client.compat.tacz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class TaczGripsTest {
    /**
     * On the M4A1, an imported arm (wrist and palm pivots of an imported model's first-person arm) slides so its palm,
     * turned at the wrist by the grip's angles, lands on the fitted palm: the shifts match the offline fit that placed
     * the hands, which turned the hand by the same keyframe through the model's own skeleton.
     */
    @Test
    void armSlidesSoTheTurnedPalmLandsOnTheGrip() {
        var grip = TaczGrips.of("tacz:m4a1");
        var left = TaczGrips.palmShift(grip.left(), true, new Vector3f(-1.15036f, 19.7907f, -0.116864f),
            new Vector3f(-1.35752f, 18.3283f, -0.181828f));
        assertVector(0.3944f, 8.1703f, 0.4511f, left);
        var right = TaczGrips.palmShift(grip.right(), false, new Vector3f(1.15036f, 19.7907f, -0.116864f),
            new Vector3f(1.35752f, 18.3283f, -0.181828f));
        assertVector(-1.5522f, 3.6622f, -2.0304f, right);
    }

    /** TACZ's 1.5 stretch along the block arm is undone; the gun's own turn and the view's scale stay. */
    @Test
    void placementUndoesTheStretchAlongTheArm() {
        var stack = new PoseStack();
        stack.scale(0.5f, 0.5f, 0.5f);
        stack.mulPose(new org.joml.Quaternionf().rotateX(0.7f));
        stack.scale(1f, 1.5f, 1f);
        var hand = new TaczGrips.Hand(0f, 0f, 0f, 0f, 0f, 0f);
        TaczGrips.place(stack, hand, true, new Vector3f(), new Vector3f());
        var pose = stack.last().pose();
        var along = new Vector3f(pose.m10(), pose.m11(), pose.m12()).length();
        var across = new Vector3f(pose.m00(), pose.m01(), pose.m02()).length();
        assertEquals(0.5f, along, 1e-5f);
        assertEquals(0.5f, across, 1e-5f);
    }

    @Test
    void onlyFittedGunsHaveGrips() {
        assertNull(TaczGrips.of("tacz:ump45"));
        assertEquals(0f, TaczGrips.value(null, "fitted"));
        assertEquals(1f, TaczGrips.value(TaczGrips.of("tacz:m4a1"), "fitted"));
        assertEquals(-63.68f, TaczGrips.value(TaczGrips.of("tacz:m4a1"), "left_y"));
    }

    private static void assertVector(float x, float y, float z, Vector3f actual) {
        assertEquals(x, actual.x, 2e-3f, "x");
        assertEquals(y, actual.y, 2e-3f, "y");
        assertEquals(z, actual.z, 2e-3f, "z");
    }
}
