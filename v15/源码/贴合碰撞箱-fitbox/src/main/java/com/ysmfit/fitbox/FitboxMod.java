package com.ysmfit.fitbox;

import com.ysmfit.fitbox.net.FitNet;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * YSM FitBox: fits each player's collision box to their YesSteveModel model per pose, and replaces hit detection on
 * them with bone-attached capsules and boxes fitted to the model's surface.
 */
@Mod(FitboxMod.MOD_ID)
public final class FitboxMod {
    public static final String MOD_ID = "ysm_fitbox";
    public static final Logger LOG = LoggerFactory.getLogger("YSM FitBox");

    public FitboxMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, FitConfig.SERVER_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, FitConfig.CLIENT_SPEC);
        FitNet.init();
        FitNet.reportHandler = FitEvents::onReport;
        MinecraftForge.EVENT_BUS.register(new FitEvents());
        MinecraftForge.EVENT_BUS.register(new FitCommand());
        if (FMLEnvironment.dist == Dist.CLIENT) {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(FitboxMod::clientSetup);
        }
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        com.ysmfit.fitbox.client.ClientFits.init();
    }
}
