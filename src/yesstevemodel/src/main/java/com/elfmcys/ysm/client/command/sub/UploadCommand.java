package com.elfmcys.ysm.client.command.sub;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.upload.UploadNames;
import com.elfmcys.ysm.model.upload.UploadPacker;
import com.elfmcys.ysm.model.upload.UploadRejectedException;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** {@code /ysmclient upload <name>}: packs a model from the custom model folder and uploads it to the server. */
public final class UploadCommand {
    private static final String NAME = "name";
    private static final long MEGABYTE = 1024L * 1024;

    private static final SuggestionProvider<CommandSourceStack> SOURCES =
        SuggestionProviders.register(new ResourceLocation(YesSteveModel.MOD_ID, "upload_sources"),
            (context, builder) -> SharedSuggestionProvider.suggest(sources().keySet(), builder));

    private UploadCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal("upload").then(Commands.argument(NAME, StringArgumentType.greedyString())
            .suggests(SOURCES).executes(UploadCommand::upload));
    }

    private static int upload(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var requested = StringArgumentType.getString(context, NAME);
        var minecraft = Minecraft.getInstance();
        if (minecraft.isLocalServer() || !NetworkHandler.isRemoteChannelPresent()) {
            source.sendFailure(Component.translatable("message.yes_steve_model.upload.no_server"));
            return 0;
        }
        if (!ServerConfig.PLAYER_UPLOADS.get()) {
            source.sendFailure(Component.translatable("message.yes_steve_model.upload.disabled"));
            return 0;
        }
        var model = sources().get(requested);
        if (model == null) {
            source.sendFailure(Component.translatable("message.yes_steve_model.upload.not_found", requested));
            return 0;
        }
        var stem = UploadNames.sanitize(requested).orElse(null);
        if (stem == null) {
            source.sendFailure(Component.translatable("message.yes_steve_model.upload.invalid", requested,
                "The name has no usable characters"));
            return 0;
        }
        if (ClientSessionRuntime.modelUploadInProgress()) {
            source.sendFailure(Component.translatable("message.yes_steve_model.upload.busy"));
            return 0;
        }
        var maxBytes = ServerConfig.UPLOAD_MAX_MEGABYTES.get() * MEGABYTE;
        var output = ModelRuntime.paths().gameCacheRoot().resolve("upload").resolve(UUID.randomUUID() + ".zip");
        source.sendSuccess(() -> Component.translatable("message.yes_steve_model.upload.packing", requested), false);
        Util.backgroundExecutor().execute(() -> {
            UploadPacker.Packed packed;
            Component failure = null;
            try {
                Files.createDirectories(output.getParent());
                packed = UploadPacker.pack(model, output, maxBytes);
            } catch (UploadRejectedException rejected) {
                packed = null;
                failure =
                    Component.translatable("message.yes_steve_model.upload.invalid", requested, rejected.getMessage());
            } catch (IOException | RuntimeException unreadable) {
                YesSteveModel.LOGGER.warn("Failed to pack model {} for upload", model, unreadable);
                packed = null;
                failure = Component.translatable("message.yes_steve_model.upload.invalid", requested,
                    String.valueOf(unreadable.getMessage()));
            }
            var result = packed;
            var refusal = failure;
            minecraft.execute(() -> start(stem, result, refusal));
        });
        return Command.SINGLE_SUCCESS;
    }

    private static void start(String stem, UploadPacker.Packed packed, Component failure) {
        var refusal = packed == null ? failure : ClientSessionRuntime.startModelUpload(stem, packed);
        if (refusal == null)
            return;
        if (packed != null) {
            try {
                Files.deleteIfExists(packed.file());
            } catch (IOException ignored) {
                // The upload cache directory only ever holds throwaway archives.
            }
        }
        var player = Minecraft.getInstance().player;
        if (player != null)
            player.sendSystemMessage(refusal);
    }

    /** Model folders and .zip/.ysm archives directly inside the custom roots; the first root wins a name. */
    static Map<String, Path> sources() {
        var paths = ModelRuntime.paths();
        var roots = new ArrayList<Path>();
        roots.add(paths.customModelsRoot());
        paths.global().ifPresent(global -> roots.add(global.customRoot()));
        var result = new LinkedHashMap<String, Path>();
        for (var root : roots) {
            for (var entry : children(root)) {
                var name = entry.getFileName().toString();
                var lower = name.toLowerCase(Locale.ROOT);
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                    || Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        && (lower.endsWith(".zip") || lower.endsWith(".ysm")))
                    result.putIfAbsent(name, entry);
            }
        }
        return result;
    }

    private static List<Path> children(Path root) {
        if (!Files.isDirectory(root))
            return List.of();
        try (var entries = Files.list(root)) {
            return entries.sorted().toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }
}
