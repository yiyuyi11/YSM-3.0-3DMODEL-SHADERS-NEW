package com.ysmnpr.endfield;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.io.IOException;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;

/** Core programs (assets/endfield_npr/shaders/core): NPR body, outline hull, multiplicative shadow overlay. */
public final class NprShaders {
    @Nullable
    static ShaderInstance character;
    @Nullable
    static ShaderInstance outline;
    @Nullable
    static ShaderInstance overlay;

    private NprShaders() {}

    static void register(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                new ResourceLocation(EndfieldNpr.MOD_ID, "npr_character"), DefaultVertexFormat.NEW_ENTITY),
                shader -> character = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                new ResourceLocation(EndfieldNpr.MOD_ID, "npr_outline"), DefaultVertexFormat.NEW_ENTITY),
                shader -> outline = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                new ResourceLocation(EndfieldNpr.MOD_ID, "npr_overlay"), DefaultVertexFormat.NEW_ENTITY),
                shader -> overlay = shader);
        } catch (IOException failure) {
            EndfieldNpr.LOG.error("Failed to load Endfield NPR shaders", failure);
        }
    }

    static boolean ready() {
        return character != null && outline != null;
    }
}
