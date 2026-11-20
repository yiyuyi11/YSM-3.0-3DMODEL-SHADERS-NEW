package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringBonesProto;
import org.jetbrains.annotations.Nullable;

/**
 * Spring response on top of the model's own spring parameters.
 * <ul>
 * <li>Per kind of chain: hair (chains hung from the head or the neck, the chains {@link AntiClip} gives the hair
 * colliders) and cloth (every other chain: hems, skirts, coat tails, ribbons, tails). Damping d (humanoid.json
 * "spring_damping_hair" / "spring_damping_cloth", 0..1) takes a share of the velocity the drag leaves: drag + (1 -
 * drag) * d, so 0 keeps the model's drag and 1 stops the swing; stiffness multipliers ("spring_stiffness_hair" /
 * "spring_stiffness_cloth") scale how hard a joint is pulled back to its animated direction.</li>
 * <li>Body velocity time ("spring_body_velocity_seconds", 0..1 s, default {@link #BODY_VELOCITY_SECONDS}): the
 * native drag carries the tails on with the body's velocity (all but the air drag share), averaged over this time
 * constant. The native default of 0.1 s (0 here) lags a stop or a landing so long that the drag keeps pushing hair
 * and hems on past their rest; 0.03 s lets them stop with the body.</li>
 * </ul>
 */
final class SpringTune {
    /**
     * Default extra damping of hair and of cloth chains. Hair: with the quick body velocity the drag no longer holds a
     * stopped model's hair forward, and the hair (VRM drag 0.5 on every Endfield model) swings back through its rest
     * more often; 0.3 brings its overshoots below REVISION 27's (24-model bounce harness).
     */
    static final float DAMPING_HAIR = 0.3f;
    static final float DAMPING_CLOTH = 0f;
    /** Default body velocity time constant, seconds (0: the native 0.1 s). */
    static final float BODY_VELOCITY_SECONDS = 0.03f;
    /** Upper bound of the stiffness multipliers. */
    static final float MAX_STIFFNESS = 10f;

    private SpringTune() {}

    /** The springs with the chains' damping and stiffness and the body velocity time set; the same when unchanged. */
    @Nullable
    static SpringBonesProto apply(@Nullable SpringBonesProto springs, AntiClip.Skeleton skeleton, float hairDamping,
        float clothDamping, float hairStiffness, float clothStiffness, float bodyVelocitySeconds) {
        if (springs == null || springs.joints().isEmpty()) {
            return springs;
        }
        var out = springs.toBuilder();
        if (bodyVelocitySeconds > 0f) {
            out.setBodyVelocitySeconds(bodyVelocitySeconds);
        }
        if (hairDamping != 0f || clothDamping != 0f || hairStiffness != 1f || clothStiffness != 1f) {
            var hair = AntiClip.hairChains(springs, skeleton);
            out.clearJoints();
            for (var joint : springs.joints()) {
                var isHair = joint.chain() < hair.length && hair[joint.chain()];
                var damping = isHair ? hairDamping : clothDamping;
                out.addJoints(joint.toBuilder().setDrag(joint.drag() + (1f - joint.drag()) * damping)
                    .setStiffness(joint.stiffness() * (isHair ? hairStiffness : clothStiffness)).build());
            }
        }
        return out.build();
    }
}
