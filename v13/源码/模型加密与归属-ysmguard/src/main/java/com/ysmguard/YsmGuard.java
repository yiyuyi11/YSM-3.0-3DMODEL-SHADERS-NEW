package com.ysmguard;

import com.mojang.logging.LogUtils;
import com.ysmguard.cache.CachePolicy;
import com.ysmguard.net.GuardNet;
import com.ysmguard.server.GuardCommands;
import com.ysmguard.server.Ownership;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * ysmguard: YSM model protection. Both sides: the encrypted YSM channel. Server: admin/owner model policy and the
 * /ysmguard commands. Client: encrypted YSM model caches (see {@link CachePolicy}).
 */
@Mod(YsmGuard.MOD_ID)
public final class YsmGuard {
    public static final String MOD_ID = "ysmguard";
    private static final Logger LOG = LogUtils.getLogger();

    public YsmGuard() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, GuardConfig.SPEC);
        GuardNet.register();
        MinecraftForge.EVENT_BUS.addListener(GuardCommands::register);
        MinecraftForge.EVENT_BUS.addListener(Ownership::onServerTick);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            var cache = FMLPaths.GAMEDIR.get().resolve("ysm").resolve("cache");
            var removed = CachePolicy.purgePlaintext(cache, LOG);
            if (removed > 0)
                LOG.info("ysmguard: removed {} unencrypted YSM cache files from {}", removed, cache);
        }
    }
}
