package com.ysmfit.fitbox;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

/**
 * TACZ held-gun stance (soft dependency). Its common API works on both sides: the main-hand gun's type from its
 * common gun index, and the synced aiming progress. YSM layers the {@code tac:hold|aim|climbing:<type>} clips on the
 * body for these; fitbox fits the rifle and pistol variants (other types use the rifle's).
 */
public final class Tacz {
    public static final int NONE = 0;
    public static final int HOLD = 1;
    public static final int AIM = 2;
    public static final int PISTOL = 4;
    private static Boolean loaded;

    private Tacz() {}

    public static int stance(Player player) {
        var l = loaded;
        if (l == null) {
            l = ModList.get() != null && ModList.get().isLoaded("tacz");
            loaded = l;
        }
        if (!l) {
            return NONE;
        }
        try {
            return Api.stance(player);
        } catch (LinkageError | RuntimeException e) {
            loaded = false; // incompatible TACZ: no stances
            return NONE;
        }
    }

    /** Only loaded when TACZ is present. */
    private static final class Api {
        static int stance(Player player) {
            var stack = player.getMainHandItem();
            var gun = com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
            if (gun == null) {
                return NONE;
            }
            var type = com.tacz.guns.api.TimelessAPI.getCommonGunIndex(gun.getGunId(stack))
                .map(com.tacz.guns.resource.index.CommonGunIndex::getType).orElse("rifle");
            var aiming = com.tacz.guns.api.entity.IGunOperator.fromLivingEntity(player).getSynAimingProgress() > 0;
            return HOLD | (aiming ? AIM : 0) | ("pistol".equals(type) ? PISTOL : 0);
        }
    }
}
