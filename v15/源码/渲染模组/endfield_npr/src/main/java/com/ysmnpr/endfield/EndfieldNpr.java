package com.ysmnpr.endfield;

import com.elfmcys.ysm.api.rendering.event.RenderModelEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Endfield-style (HGRP/CharacterNPR) shading for YSM humanoid-import models that stays in charge under Oculus/Iris
 * shader packs. YSM's own model draw is captured instead of being submitted to the pack's gbuffer programs, and is
 * drawn with this mod's NPR program after the pack has finished its composite and final passes; the pack still sees
 * the model in its shadow pass, so the character keeps casting shadows into the world.
 */
@Mod(EndfieldNpr.MOD_ID)
public final class EndfieldNpr {
    public static final String MOD_ID = "endfield_npr";
    public static final Logger LOG = LoggerFactory.getLogger("EndfieldNPR");

    public EndfieldNpr() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            var bus = FMLJavaModLoadingContext.get().getModEventBus();
            bus.addListener(NprShaders::register);
            bus.addListener(EndfieldNpr::clientSetup);
        }
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        NprConfig.load();
        // Model packages are per connection: freed when leaving a world/server, read again on the next one.
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
            (net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) -> {
                ModelPackages.clear();
                RainWet.clear(); // rain/wet states are per world
            });
        var ysm = ModList.get().getModContainerById("ysm");
        if (ysm.isEmpty() || !(ysm.get() instanceof FMLModContainer container)) {
            LOG.error("YesSteveModel (modid ysm) is not loaded; Endfield NPR shading stays off");
            return;
        }
        // YSM posts RenderModelEvent on its own mod event bus.
        container.getEventBus().addListener((RenderModelEvent e) -> CharacterCapture.onRenderModel(e));
        LOG.info("Endfield NPR attached to YSM model rendering (shader pack aware)");
    }
}
