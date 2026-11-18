package com.elfmcys.ysm.config;

import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.google.common.collect.Lists;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public class ServerConfig {
    public static ForgeConfigSpec.IntValue THREAD_COUNT;
    public static ForgeConfigSpec.IntValue BANDWIDTH_LIMIT;
    public static ForgeConfigSpec.IntValue DISPATCH_SOFT_LIMIT;
    public static ForgeConfigSpec.IntValue DISPATCH_HARD_LIMIT;
    public static ForgeConfigSpec.BooleanValue RESTRICTED_AUTH;
    public static ForgeConfigSpec.BooleanValue LOW_BANDWIDTH_USAGE;
    public static ForgeConfigSpec.BooleanValue CAN_SWITCH_MODEL;
    public static ForgeConfigSpec.ConfigValue<String> DEFAULT_MODEL_PATH;
    public static ForgeConfigSpec.ConfigValue<String> DEFAULT_MODEL_TEXTURE;
    public static ForgeConfigSpec.BooleanValue PLAYER_UPLOADS;
    public static ForgeConfigSpec.IntValue UPLOAD_PERMISSION_LEVEL;
    public static ForgeConfigSpec.IntValue UPLOAD_MAX_MEGABYTES;
    public static ForgeConfigSpec.IntValue UPLOAD_MAX_MODELS_PER_PLAYER;

    // Complete published addresses hidden from the client model list.
    public static ForgeConfigSpec.ConfigValue<List<String>> CLIENT_NOT_DISPLAY_MODEL_PATHS;

    public static ForgeConfigSpec init() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ServerConfig.init(builder);
        return builder.build();
    }

    private static void init(ForgeConfigSpec.Builder builder) {
        builder.comment("The published address of the default model when a player first enters the game");
        DEFAULT_MODEL_PATH = builder.define("DefaultModelPath", CatalogLocation.DEFAULT.value());

        builder.comment("The default model texture when a player first enters the game");
        DEFAULT_MODEL_TEXTURE = builder.define("DefaultModelTexture", "default");

        builder.comment("Whether or not players are allowed to switch models");
        CAN_SWITCH_MODEL = builder.define("CanSwitchModel", true);

        builder.comment("Complete published addresses hidden from the client model selection screen");
        builder.comment("Example: [\"local:custom/model.mxc\", \"global:auth/pack/private/legacy.ysm\"]");
        CLIENT_NOT_DISPLAY_MODEL_PATHS = builder.define("ClientNotDisplayModelPaths", Lists.newArrayList());

        builder.push("server_scheduler");

        builder.comment("Concurrent level for processing models. Value 0 means AUTO.");
        THREAD_COUNT = builder.defineInRange("ThreadCount", 0, 0, Math.max(2, NativeRuntime.availableProcessors() - 1));

        builder.comment("Global model distribution limit in Mbps. 0 means unlimited.");
        BANDWIDTH_LIMIT = builder.defineInRange("BandwidthLimit", 5, 0, 999);

        builder.comment("Per-player queue size that enables one-fragment round-robin visits.");
        DISPATCH_SOFT_LIMIT = builder.defineInRange("DispatchSoftLimit", 24, 1, 255);

        builder.comment("Atomic per-player logical-packet admission cap.");
        DISPATCH_HARD_LIMIT = builder.defineInRange("DispatchHardLimit", 48, 2, 256);

        builder.comment("Restrict unauthorized auth-model icon and chunk downloads.");
        RESTRICTED_AUTH = builder.define("RestrictedAuth", false);

        builder.comment("Suppress network synchronization of partial features to reduce bandwidth usage");
        builder.comment("Only effective when there are tons of players");
        LOW_BANDWIDTH_USAGE = builder.define("LowBandwidthUsage", false);

        builder.pop();

        builder.push("player_uploads");

        builder.comment("Whether players may upload their own models. An uploaded model is stored under auth/uploads,");
        builder.comment("only its uploader may select it, and everyone else still sees it on the uploader.");
        PLAYER_UPLOADS = builder.define("Enabled", false);

        builder.comment("Permission level needed to upload: 0 lets every player upload, 2 only operators.");
        UPLOAD_PERMISSION_LEVEL = builder.defineInRange("PermissionLevel", 0, 0, 4);

        builder.comment("Largest upload, in megabytes, both as sent and once unpacked.");
        UPLOAD_MAX_MEGABYTES = builder.defineInRange("MaxMegabytes", 64, 1, 1024);

        builder.comment("How many uploaded models one player may keep on the server.");
        UPLOAD_MAX_MODELS_PER_PLAYER = builder.defineInRange("MaxModelsPerPlayer", 3, 1, 100);

        builder.pop();
    }
}
