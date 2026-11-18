package com.ysmguard.server;

import com.elfmcys.ysm.event.CommandRegistry;
import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog;
import com.elfmcys.ysm.model.catalog.source.CatalogRoot;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.upload.PlayerUploads;
import com.elfmcys.ysm.util.CommandUtil;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.ysmguard.net.GuardHandler;
import com.ysmguard.net.GuardNet;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * {@code /ysmguard status | list [player] | owner <model> | transfer <model> admin | transfer <model> player <name>};
 * permission level 2 (YSM's admin level).
 */
public final class GuardCommands {
    private static final String MODEL = "model";
    private static final String PLAYER = "player";

    private GuardCommands() {}

    public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("ysmguard").requires(source -> CommandUtil.hasPermission(source, 2));
        root.then(Commands.literal("status").executes(GuardCommands::status));
        root.then(Commands.literal("list").executes(context -> list(context, null))
            .then(Commands.argument(PLAYER, GameProfileArgument.gameProfile()).executes(
                context -> list(context, single(context)))));
        root.then(Commands.literal("owner").then(Commands.argument(MODEL, StringArgumentType.string())
            .suggests(CommandRegistry.ALL_MODELS).executes(GuardCommands::owner)));
        root.then(Commands.literal("transfer").then(Commands.argument(MODEL, StringArgumentType.string())
            .suggests(CommandRegistry.ALL_MODELS)
            .then(Commands.literal("admin").executes(context -> transfer(context, null)))
            .then(Commands.literal("player").then(Commands.argument(PLAYER, GameProfileArgument.gameProfile())
                .executes(context -> transfer(context, single(context)))))));
        event.getDispatcher().register(root);
    }

    private static GameProfile single(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var profiles = GameProfileArgument.getGameProfiles(context, PLAYER);
        if (profiles.size() != 1)
            throw GameProfileArgument.ERROR_UNKNOWN_PLAYER.create();
        return profiles.iterator().next();
    }

    private static Optional<ServerCatalog> catalog(CommandSourceStack source) {
        var catalog = ServerModelService.current().flatMap(ServerModelService::catalog);
        if (catalog.isEmpty())
            source.sendFailure(Component.literal("YSM 模型目录尚未加载"));
        return catalog;
    }

    private static Optional<ManagedContainer> resolve(CommandSourceStack source, ServerCatalog catalog, String path) {
        var resolution = catalog.resolvePath(path);
        var model = resolution.found().flatMap(catalog::find);
        if (model.isEmpty())
            source.sendFailure(Component.literal("找不到模型 " + path + "：" + resolution.message()));
        return model;
    }

    private static String name(MinecraftServer server, UUID id) {
        var online = server.getPlayerList().getPlayer(id);
        if (online != null)
            return online.getGameProfile().getName();
        // getProfileCache() (m_129927_): the patched MinecraftServer in the compile jar keeps the SRG name.
        var cache = server.m_129927_();
        return cache == null ? id.toString() : cache.get(id).map(GameProfile::getName).orElse(id.toString());
    }

    private static String ownerText(MinecraftServer server, ManagedContainer model) {
        return Ownership.owner(model.location()).map(id -> "玩家 " + name(server, id) + "（上传者）")
            .orElse("管理员（服务器模型）");
    }

    private static String access(ServerCatalog catalog, Hash256 id) {
        var record = catalog.catalog().byModelId().get(id);
        return record != null && record.entry().access() == CatalogAccess.PUBLIC ? "公开" : "需授权";
    }

    private static String wearers(MinecraftServer server, Hash256 id) {
        var names = new ArrayList<String>();
        for (var player : server.getPlayerList().getPlayers()) {
            player.getCapability(com.elfmcys.ysm.capability.ModelInfoCapabilityProvider.MODEL_INFO_CAP)
                .ifPresent(capability -> {
                    if (id.equals(capability.getModelId()))
                        names.add(player.getGameProfile().getName());
                });
        }
        return names.isEmpty() ? "无" : String.join(", ", names);
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var server = source.getServer();
        var catalog = ServerModelService.current().flatMap(ServerModelService::catalog).orElse(null);
        var players = server.getPlayerList().getPlayers();
        source.sendSuccess(() -> Component.literal("ysmguard：" + players.size() + " 名在线玩家"), false);
        for (ServerPlayer player : players) {
            var state = GuardNet.state(player.connection.connection);
            var encryption = state == GuardHandler.State.SEALED ? "已加密"
                : state == null ? "本地连接" : state == GuardHandler.State.PLAIN ? "未加密（客户端无 ysmguard）" : "握手中";
            var session = ServerModelService.current().flatMap(service -> service.session(player)).orElse(null);
            var grants = session == null || !session.active() ? "-" : String.valueOf(session.grants().size());
            var owned = catalog == null ? "-"
                : String.valueOf(catalog.catalog().byLocation().keySet().stream()
                    .filter(location -> Ownership.owner(location).filter(player.getUUID()::equals).isPresent())
                    .count());
            var model = player.getCapability(com.elfmcys.ysm.capability.ModelInfoCapabilityProvider.MODEL_INFO_CAP)
                .resolve().map(capability -> capability.getModelId() == null || catalog == null ? "default"
                    : catalog.find(capability.getModelId()).map(found -> found.location().value())
                        .orElse(capability.getModelId().toString()))
                .orElse("?");
            var line = player.getGameProfile().getName() + "：" + (Ownership.isAdmin(player) ? "管理员" : "普通玩家")
                + "，YSM 通道" + encryption + "，可用模型 " + grants + "（自己上传 " + owned + "），当前模型 " + model;
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int list(CommandContext<CommandSourceStack> context, GameProfile filter) {
        var source = context.getSource();
        var catalog = catalog(source).orElse(null);
        if (catalog == null)
            return 0;
        var server = source.getServer();
        var models = new ArrayList<>(catalog.models().values());
        models.sort(Comparator.comparing(model -> model.location().value()));
        var shown = 0;
        for (var model : models) {
            var owner = Ownership.owner(model.location());
            if (filter != null && !owner.filter(filter.getId()::equals).isPresent())
                continue;
            var id = model.representation().modelId();
            var line = Component.literal(model.location().value()).withStyle(ChatFormatting.AQUA)
                .append(Component.literal("  [" + access(catalog, id) + "] 所有者：" + ownerText(server, model)
                    + "，佩戴中：" + wearers(server, id)).withStyle(ChatFormatting.GRAY));
            source.sendSuccess(() -> line, false);
            shown++;
        }
        var total = shown;
        source.sendSuccess(() -> Component.literal("共 " + total + " 个模型"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int owner(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var catalog = catalog(source).orElse(null);
        if (catalog == null)
            return 0;
        var model = resolve(source, catalog, StringArgumentType.getString(context, MODEL)).orElse(null);
        if (model == null)
            return 0;
        var server = source.getServer();
        var id = model.representation().modelId();
        var granted = new ArrayList<String>();
        for (var player : server.getPlayerList().getPlayers()) {
            ServerModelService.current().flatMap(service -> service.session(player))
                .filter(session -> session.active() && session.grants().contains(id))
                .ifPresent(session -> granted.add(player.getGameProfile().getName()));
        }
        source.sendSuccess(() -> Component.literal(model.location().value() + "：所有者 " + ownerText(server, model)
            + "，访问 " + access(catalog, id) + "，在线可用玩家 " + (granted.isEmpty() ? "无" : String.join(", ", granted))
            + "，佩戴中 " + wearers(server, id)), false);
        return Command.SINGLE_SUCCESS;
    }

    /** Moves the model's file or folder into the new owner's upload folder (or out of uploads), then reloads. */
    private static int transfer(CommandContext<CommandSourceStack> context, GameProfile target) {
        var source = context.getSource();
        var catalog = catalog(source).orElse(null);
        if (catalog == null)
            return 0;
        var model = resolve(source, catalog, StringArgumentType.getString(context, MODEL)).orElse(null);
        if (model == null)
            return 0;
        var location = model.location();
        var paths = ModelRuntime.paths();
        Path root;
        if (location.root() == CatalogRoot.LOCAL_AUTH)
            root = paths.authModelsRoot();
        else if (location.root() == CatalogRoot.LOCAL_CUSTOM)
            root = paths.customModelsRoot();
        else {
            source.sendFailure(Component.literal("只能转移服务器 ysm/auth 或 ysm/custom 里的模型：" + location.value()));
            return 0;
        }
        var from = root.resolve(location.entry()).normalize();
        if (!from.startsWith(root) || !Files.exists(from)) {
            source.sendFailure(Component.literal("找不到模型文件：" + from));
            return 0;
        }
        var authRoot = paths.authModelsRoot();
        var name = from.getFileName().toString();
        var to = target == null ? authRoot.resolve(name)
            : PlayerUploads.playerDirectory(authRoot, target.getId()).resolve(name);
        if (to.equals(from)) {
            source.sendFailure(Component.literal("模型已经属于该所有者"));
            return 0;
        }
        if (Files.exists(to)) {
            source.sendFailure(Component.literal("目标已存在同名模型：" + to));
            return 0;
        }
        try {
            Files.createDirectories(to.getParent());
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException crossDevice) {
                Files.move(from, to);
            }
        } catch (IOException failure) {
            source.sendFailure(Component.literal("移动失败：" + failure));
            return 0;
        }
        var who = target == null ? "管理员（服务器模型）" : "玩家 " + target.getName();
        source.sendSuccess(() -> Component.literal("已将 " + location.value() + " 转给 " + who + "，正在重新加载模型目录…"), true);
        ServerModelService.instance().reload().whenComplete((outcome, failure) -> CommandUtil.sendAsyncFeedback(source,
            Component.literal(failure == null && outcome.success() ? "模型目录已重新加载：" + outcome.modelCount() + " 个模型"
                : "模型目录重新加载失败：" + (failure != null ? failure : outcome.message())), true));
        return Command.SINGLE_SUCCESS;
    }
}
