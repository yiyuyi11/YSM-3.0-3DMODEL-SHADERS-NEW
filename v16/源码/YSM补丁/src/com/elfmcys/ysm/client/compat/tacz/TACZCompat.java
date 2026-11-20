package com.elfmcys.ysm.client.compat.tacz;

import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * Source of the v18 class (bytecode-equivalent) except {@link #heldGrip}, which asks for the holder's grip so that a
 * first-person profile can give each YSM model its own (TaczGrips.of).
 */
public class TACZCompat {
    private static final String MOD_ID = "tacz";
    private static boolean INSTALLED = false;

    public static void init() {
        INSTALLED = ModList.get().isLoaded(MOD_ID);
        if (INSTALLED) {
            TacCompatInner.registerEvent();
        }
    }

    public static void renderOffsetHand(ItemStack offhandItem, GeoRenderData data, LivingEntity livingEntity,
        PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        if (isInstalled() && TacCompatInner.isGun(offhandItem)) {
            poseStack.m_85836_();
            TacCompatInner.renderOffhandGun(offhandItem, data, livingEntity, poseStack, buffer, packedLight);
            poseStack.m_85849_();
        }
    }

    @Nullable
    public static PlayState playGunMainAnimation(LivingEntity livingEntity,
        AnimationEvent<? extends CustomHumanoidEntity<?>> event, String animationName, LoopType loopType) {
        return isInstalled() && TacCompatInner.isGun(livingEntity.m_21205_())
            ? TacCompatInner.playGunMainAnimation(event, animationName, loopType) : null;
    }

    @Nullable
    public static PlayState playGunHoldAnimation(ItemStack mainHandItem,
        AnimationEvent<? extends CustomHumanoidEntity<?>> event) {
        return isInstalled() && TacCompatInner.isGun(mainHandItem)
            ? TacCompatInner.playGunHoldAnimation(event, mainHandItem) : null;
    }

    @Nullable
    public static PlayState playGunOnceAnimation(ItemStack mainHandItem,
        AnimationEvent<? extends CustomHumanoidEntity<?>> event) {
        return isInstalled() && TacCompatInner.isGun(mainHandItem)
            ? TacCompatInner.playGunOnceAnimation(event, mainHandItem) : null;
    }

    public static void openFlashShellRender(LivingEntity livingEntity, ItemStack mainHandItem) {
        if (isInstalled() && TacCompatInner.isGun(mainHandItem)) {
            TacCompatInner.openFlashShellRender(livingEntity);
        }
    }

    public static void stopFlashShellRender(ItemStack mainHandItem) {
        if (isInstalled() && TacCompatInner.isGun(mainHandItem)) {
            TacCompatInner.stopFlashShellRender();
        }
    }

    @Nullable
    public static ResourceLocation getGunId(ItemStack stack) {
        return isInstalled() ? TacCompatInner.getGunId(stack) : null;
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    public static boolean isMainhandHoldingGun(LivingEntity entity) {
        return INSTALLED && TacCompatInner.isMainhandHoldingGun(entity);
    }

    /** The fitted grip of the gun in {@code entity}'s main hand (its model's profile grip, else built in), or null. */
    @Nullable
    public static TaczGrips.Grip heldGrip(LivingEntity entity) {
        if (!INSTALLED) {
            return null;
        }
        String id = TacCompatInner.mainhandGunId(entity);
        return id == null ? null : TaczGrips.of(id, entity);
    }

    public static boolean mainhandHoldingGun(Entity entity) {
        return entity instanceof LivingEntity living && isMainhandHoldingGun(living);
    }
}
