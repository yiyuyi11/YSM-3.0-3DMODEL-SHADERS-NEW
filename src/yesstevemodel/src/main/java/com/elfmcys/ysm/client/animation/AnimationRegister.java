package com.elfmcys.ysm.client.animation;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.compat.bfm.BfmCompat;
import com.elfmcys.ysm.client.animation.predicate.PlayerMainPredicate;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import java.util.function.BiPredicate;

public class AnimationRegister {
    public static final String IDLE = "idle";
    public static final String HOVER = "hover";
    public static final String HOVER_FADEOUT = "hover_fadeout";
    public static final String FOCUS = "focus";
    public static final String EMPTY = "empty";

    private static final float MIN_SPEED = 0.05f;
    /** Cosine of the angle between movement and facing below which a step counts as backward. */
    private static final double BACKWARD = -0.3;

    public static void registerAnimationState() {
        register("death", LoopType.PLAY_ONCE, Priority.HIGHEST, (player, event) -> player.isDeadOrDying());
        register("riptide", Priority.HIGHEST, (player, event) -> player.isAutoSpinAttack());
        register("sleep", Priority.HIGHEST, (player, event) -> player.getPose() == Pose.SLEEPING);
        register("swim", Priority.HIGHEST, (player, event) -> player.isSwimming());
        // Battlefield Movement's prone stance: going down and getting up play the model's transitions where it has
        // them; the camera moves over the same few ticks.
        register("bfm:prone_enter", LoopType.HOLD_ON_LAST_FRAME, Priority.HIGHEST, (player, event) ->
            !player.isSwimming() && BfmCompat.isGoingProne(player)
                && event.getAnimatableEntity().getAnimation("bfm:prone_enter") != null);
        register("bfm:prone_exit", LoopType.HOLD_ON_LAST_FRAME, Priority.HIGHEST, (player, event) ->
            !player.isSwimming() && BfmCompat.isGettingUp(player)
                && event.getAnimatableEntity().getAnimation("bfm:prone_exit") != null);
        // Crawling includes Battlefield Movement's prone stance, so its pose resets do not flicker standing clips in.
        register("climb", Priority.HIGHEST, (player, event) -> BfmCompat.isCrawling(player)
            && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED);
        register("climbing", Priority.HIGHEST, (player, event) -> BfmCompat.isCrawling(player));

        register("ladder_up", Priority.HIGHEST,
            (player, event) -> player.onClimbable() && getVerticalSpeed(player) > 0);
        register("ladder_stillness", Priority.HIGHEST,
            (player, event) -> player.onClimbable() && getVerticalSpeed(player) == 0);
        register("ladder_down", Priority.HIGHEST,
            (player, event) -> player.onClimbable() && getVerticalSpeed(player) < 0);

        // Battlefield Movement slides in the style chosen in its config, and dives.
        register("bfm:dive", LoopType.HOLD_ON_LAST_FRAME, Priority.HIGH, (player, event) -> BfmCompat.isDiving(player));
        register("bfm:slide_crouch", Priority.HIGH,
            (player, event) -> BfmCompat.isSliding(player) && BfmCompat.crouchedSlides());
        register("bfm:slide", Priority.HIGH, (player, event) -> BfmCompat.isSliding(player));
        register("fly", Priority.HIGH, (player, event) -> {
            if (event.getAnimatableEntity() instanceof PlayerAnimatableCapability cap && !cap.isLocalPlayer()) {
                return cap.getStateTracker().isFlying();
            }
            return player.getAbilities().flying;
        });
        register("elytra_fly", Priority.HIGH,
            (player, event) -> player.getPose() == Pose.FALL_FLYING && player.isFallFlying());

        register("swim_stand", Priority.NORMAL, (player, event) -> player.isInWater() && !player.onGround());
        register("attacked", LoopType.PLAY_ONCE, Priority.NORMAL, (player, event) -> player.hurtTime > 0);
        register("jump", Priority.NORMAL, (player, event) -> !player.onGround() && !player.isInWater());
        // Moving backward plays the model's reversed gaits where it has them (imported humanoids do); other models
        // keep their sneak and walk.
        register("sneak_back", Priority.NORMAL, (player, event) -> player.onGround()
            && player.getPose() == Pose.CROUCHING && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED
            && movingBackward(player) && event.getAnimatableEntity().getAnimation("sneak_back") != null);
        register("sneak", Priority.NORMAL, (player, event) -> player.onGround() && player.getPose() == Pose.CROUCHING
            && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED);
        register("sneaking", Priority.NORMAL,
            (player, event) -> player.onGround() && player.getPose() == Pose.CROUCHING);

        register("run", Priority.LOW, (player, event) -> player.onGround() && player.isSprinting());
        register("walk_back", Priority.LOW, (player, event) -> player.onGround()
            && event.getLimbSwingAmount() > MIN_SPEED && movingBackward(player)
            && event.getAnimatableEntity().getAnimation("walk_back") != null);
        register("walk", Priority.LOW, (player, event) -> player.onGround() && event.getLimbSwingAmount() > MIN_SPEED);

        register(IDLE, Priority.LOWEST, (player, event) -> true);
    }

    private static void register(String animationName, LoopType loopType, int priority,
        BiPredicate<Player, AnimationEvent<CustomPlayerEntity>> predicate) {
        PlayerMainPredicate.register(new AnimationState<>(animationName, loopType, priority, predicate));
    }

    private static void register(String animationName, int priority,
        BiPredicate<Player, AnimationEvent<CustomPlayerEntity>> predicate) {
        register(animationName, LoopType.LOOP, priority, predicate);
    }

    /**
     * Whether the player moved mostly backward this tick: against the body's facing by more than about 107 degrees.
     * Vanilla keeps the body facing forward when walking backward and turns it toward a sideways step, so only
     * backward steps need their own gait.
     */
    private static boolean movingBackward(Player player) {
        var dx = player.getX() - player.xo;
        var dz = player.getZ() - player.zo;
        var squared = dx * dx + dz * dz;
        if (squared < 1e-8) {
            return false;
        }
        // A yaw of 0 faces +Z; it turns clockwise seen from above.
        var yaw = Math.toRadians(player.yBodyRot);
        return (-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / Math.sqrt(squared) < BACKWARD;
    }

    private static float getVerticalSpeed(Player player) {
        return 20 * (float)(player.position().y - player.yo);
    }
}
