package com.elfmcys.ysm.client.compat.tacz;

import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.host.builtin.BuiltinComponent;
import com.elfmcys.ysm.expression.host.builtin.BuiltinFunction;
import net.minecraft.world.entity.LivingEntity;

@BuiltinComponent(value = "ctrl", dependencies = @com.elfmcys.ysm.api.annotation.MolangDependency(modId = "tacz"))
public final class TaczBuiltinComponent implements EntityComponent<LivingEntity> {
    public TaczBuiltinComponent(com.elfmcys.ysm.client.entity.AnimatableEntity<LivingEntity> owner) {}

    @BuiltinFunction(value = "tac_hold_gun", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public static boolean holdsGun(MolangContextView<LivingEntity> context) {
        return TACZCompat.isMainhandHoldingGun(context.subject());
    }

    /** The fitted first-person grip of the held gun ({@link TaczGrips#value}): "fitted", or a wrist turn. */
    @BuiltinFunction(value = "tac_grip", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 1,
        maxArgs = 1)
    public static float grip(MolangContextView<LivingEntity> context, String key) {
        return TaczGrips.value(TACZCompat.heldGrip(context.subject()), key);
    }
}
