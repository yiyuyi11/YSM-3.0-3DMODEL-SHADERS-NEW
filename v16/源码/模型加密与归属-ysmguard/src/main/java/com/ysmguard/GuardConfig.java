package com.ysmguard;

import net.minecraftforge.common.ForgeConfigSpec;

/** Server config: {@code <world>/serverconfig/ysmguard-server.toml}. */
public final class GuardConfig {
    public enum ContentPolicy { WORN, OPEN }

    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue REQUIRE_CLIENT_MOD;
    private static final ForgeConfigSpec.BooleanValue ADMINS_USE_ALL_MODELS;
    private static final ForgeConfigSpec.BooleanValue PROTECT_PUBLIC_ROOTS;
    private static final ForgeConfigSpec.EnumValue<ContentPolicy> CONTENT_POLICY;

    static {
        var builder = new ForgeConfigSpec.Builder();
        builder.comment("Refuse clients without ysmguard. With false such clients may join, but YSM data (models)",
            "travels to them unencrypted.");
        REQUIRE_CLIENT_MOD = builder.define("RequireClientMod", true);
        builder.comment("Operators (permission level >= 2, YSM's admin level) may select and assign every model.");
        ADMINS_USE_ALL_MODELS = builder.define("AdminsUseAllModels", true);
        builder.comment("Treat models in ysm/custom and YSM's built-in models (except the built-in 'default') like",
            "ysm/auth models: only admins, the uploader or players granted with /ysm auth may use them.");
        PROTECT_PUBLIC_ROOTS = builder.define("ProtectCustomAndBuiltinModels", true);
        builder.comment("Who may download a protected model's runtime content (mesh, textures, animations):",
            "WORN = admins, the owner/granted players, and anyone while some online player is wearing the model",
            "       (so everyone still sees everyone's model);",
            "OPEN = YSM's own rule (any player may download any model unless YSM's RestrictedAuth is true).");
        CONTENT_POLICY = builder.defineEnum("ContentPolicy", ContentPolicy.WORN);
        SPEC = builder.build();
    }

    private GuardConfig() {}

    public static boolean requireClientMod() {
        return get(REQUIRE_CLIENT_MOD, true);
    }

    public static boolean adminsUseAllModels() {
        return get(ADMINS_USE_ALL_MODELS, true);
    }

    public static boolean protectPublicRoots() {
        return get(PROTECT_PUBLIC_ROOTS, true);
    }

    public static ContentPolicy contentPolicy() {
        try {
            return CONTENT_POLICY.get();
        } catch (IllegalStateException notLoaded) {
            return ContentPolicy.WORN;
        }
    }

    private static boolean get(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }
}
