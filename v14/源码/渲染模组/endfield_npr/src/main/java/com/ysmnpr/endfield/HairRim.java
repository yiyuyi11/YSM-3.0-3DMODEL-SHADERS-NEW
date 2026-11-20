package com.ysmnpr.endfield;

import net.minecraft.client.renderer.ShaderInstance;

/**
 * The hair rim's depth gate (npr_lights.glsl hairRimDepth; HGRP_CharacterNPR_Hair asm 1353-1377): the game's opaque
 * hair gates its CP8 rim with the camera depth texture (which holds the character) at a point offset along the screen
 * normal, not with the 1 - |N.V| angle. Here that depth is SceneDepth, the pack's depth with her gbuffer prepass
 * (DepthPrepass), so the gate is on only for groups whose SceneDepth holds their own depth; elsewhere (no shader pack,
 * no prepass, MINIMAL) the hair keeps the angular gate.
 */
final class HairRim {
    private HairRim() {}

    /** @param ownDepth whether this group's SceneDepth holds the depth of every character in it */
    static void apply(ShaderInstance shader, boolean ownDepth) {
        var range = NprConfig.vec("rimDepthRange", 2);
        shader.safeGetUniform("RimDepth").set(ownDepth && NprConfig.bool("rimDepth") ? 1f : 0f, range[0], range[1],
            0f);
    }
}
