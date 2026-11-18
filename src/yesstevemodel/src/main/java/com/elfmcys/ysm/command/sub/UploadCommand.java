package com.elfmcys.ysm.command.sub;

import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.model.upload.PlayerUploadService;
import com.elfmcys.ysm.model.upload.PlayerUploads;
import com.elfmcys.ysm.model.upload.UploadNames;
import com.elfmcys.ysm.util.CommandUtil;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /ysm upload list [<player>]} and {@code /ysm upload remove [<player>] <name>}. A player manages their own
 * uploads with the upload permission level; naming another player needs level 2.
 */
public final class UploadCommand {
    private static final String PLAYER = "player";
    private static final String TARGET = "target";
    private static final String NAME = "name";

    private UploadCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        var upload = Commands.literal("upload");
        upload.then(Commands.literal("list").executes(UploadCommand::listOwn)
            .then(Commands.argument(PLAYER, GameProfileArgument.gameProfile())
                .requires(source -> CommandUtil.hasPermission(source, 2)).executes(UploadCommand::listOther)));
        upload.then(Commands.literal("remove")
            .then(Commands.argument(TARGET, StringArgumentType.string()).suggests(UploadCommand::suggestTarget)
                .executes(UploadCommand::removeOwn)
                .then(Commands.argument(NAME, StringArgumentType.string())
                    .requires(source -> CommandUtil.hasPermission(source, 2)).suggests(UploadCommand::suggestOther)
                    .executes(UploadCommand::removeOther))));
        return upload;
    }

    private static int listOwn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        var player = source.getPlayerOrException();
        if (!ownAllowed(source))
            return 0;
        return list(source, player.getGameProfile());
    }

    private static int listOther(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var profiles = GameProfileArgument.getGameProfiles(context, PLAYER);
        var result = 0;
        for (var profile : profiles)
            result += list(context.getSource(), profile);
        return result;
    }

    private static int list(CommandSourceStack source, GameProfile profile) {
        var uploads = ServerModelService.instance().uploads();
        var stems = uploads.stems(profile.getId());
        if (stems.isEmpty()) {
            source.sendSuccess(
                () -> Component.translatable("commands.yes_steve_model.upload.list.empty", profile.getName()), false);
            return Command.SINGLE_SUCCESS;
        }
        source.sendSuccess(() -> Component.translatable("commands.yes_steve_model.upload.list.header",
            profile.getName(), stems.size(), ServerConfig.UPLOAD_MAX_MODELS_PER_PLAYER.get()), false);
        for (var stem : stems) {
            var location = PlayerUploads.location(profile.getId(), stem).value();
            var published = uploads.published(profile.getId(), stem);
            source.sendSuccess(() -> Component.translatable(published ? "commands.yes_steve_model.upload.list.published"
                : "commands.yes_steve_model.upload.list.unpublished", stem, location), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int removeOwn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        var player = source.getPlayerOrException();
        if (!ownAllowed(source))
            return 0;
        return remove(source, player.getGameProfile(), StringArgumentType.getString(context, TARGET));
    }

    private static int removeOther(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var name = StringArgumentType.getString(context, TARGET);
        var profile = profile(source, name);
        if (profile == null) {
            source.sendFailure(Component.translatable("argument.player.unknown"));
            return 0;
        }
        return remove(source, profile, StringArgumentType.getString(context, NAME));
    }

    private static int remove(CommandSourceStack source, GameProfile profile, String requested) {
        var uploads = ServerModelService.instance().uploads();
        var stem = UploadNames.sanitize(requested).orElse(null);
        if (stem == null || !uploads.remove(profile.getId(), stem,
            message -> CommandUtil.sendAsyncFeedback(source, Component.literal(message), true))) {
            source.sendFailure(Component.translatable("commands.yes_steve_model.upload.remove.not_found",
                profile.getName(), requested));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.yes_steve_model.upload.remove.queued",
            PlayerUploads.location(profile.getId(), stem).value()), true);
        return Command.SINGLE_SUCCESS;
    }

    private static boolean ownAllowed(CommandSourceStack source) {
        if (CommandUtil.hasPermission(source, ServerConfig.UPLOAD_PERMISSION_LEVEL.get()))
            return true;
        source.sendFailure(Component.translatable("commands.yes_steve_model.upload.no_permission"));
        return false;
    }

    private static GameProfile profile(CommandSourceStack source, String name) {
        var server = source.getServer();
        var online = server.getPlayerList().getPlayerByName(name);
        if (online != null)
            return online.getGameProfile();
        var cache = server.getProfileCache();
        return cache == null ? null : cache.get(name).orElse(null);
    }

    /** Own upload names, plus online player names for operators who may name another player. */
    private static CompletableFuture<Suggestions> suggestTarget(CommandContext<CommandSourceStack> context,
        SuggestionsBuilder builder) {
        var source = context.getSource();
        var candidates = new ArrayList<String>();
        var player = source.getPlayer();
        var uploads = ServerModelService.current().map(ServerModelService::uploads).orElse(null);
        if (player != null && uploads != null)
            candidates.addAll(uploads.stems(player.getUUID()));
        if (CommandUtil.hasPermission(source, 2))
            candidates.addAll(source.getOnlinePlayerNames());
        return SharedSuggestionProvider.suggest(candidates.stream().map(StringArgumentType::escapeIfRequired), builder);
    }

    private static CompletableFuture<Suggestions> suggestOther(CommandContext<CommandSourceStack> context,
        SuggestionsBuilder builder) {
        var profile = profile(context.getSource(), StringArgumentType.getString(context, TARGET));
        PlayerUploadService uploads = ServerModelService.current().map(ServerModelService::uploads).orElse(null);
        if (profile == null || uploads == null)
            return builder.buildFuture();
        return SharedSuggestionProvider
            .suggest(uploads.stems(profile.getId()).stream().map(StringArgumentType::escapeIfRequired), builder);
    }
}
