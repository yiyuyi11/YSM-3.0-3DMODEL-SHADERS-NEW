package com.ysmguard.mixin;

import com.ysmguard.net.GuardNet;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Installs the YSM channel guard on every network connection (channelActive is Netty's name, never obfuscated). */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "channelActive", remap = false, at = @At("TAIL"))
    private void ysmguard$install(ChannelHandlerContext ctx, CallbackInfo ci) {
        GuardNet.install((Connection)(Object)this, ctx);
    }
}
