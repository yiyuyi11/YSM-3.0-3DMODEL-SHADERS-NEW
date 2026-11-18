package com.ysmfit.fitbox;

import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.ysmfit.fitbox.core.FitPose;
import com.ysmfit.fitbox.ysm.YsmServer;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Admin commands (permission level 2):
 * <ul>
 *   <li>{@code /fitbox info <player>}: the player's fitted boxes per pose;</li>
 *   <li>{@code /fitbox mannequin <model> <pos> <yaw>}: a stand-in player wearing a catalog model (same id syntax as
 *       {@code /ysm model set}), for looking at a fit from outside and for testing hits on it (it takes no damage);
 *   </li>
 *   <li>{@code /fitbox mannequin remove}.</li>
 * </ul>
 */
public final class FitCommand {
    private static final GameProfile PROFILE =
        new GameProfile(UUID.nameUUIDFromBytes("ysm_fitbox:mannequin".getBytes()), "FitBoxDummy");
    @Nullable
    private static ServerPlayer mannequin;

    @SubscribeEvent
    public void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fitbox").requires(s -> s.hasPermission(2))
            .then(Commands.literal("info").then(Commands.argument("player", EntityArgument.player())
                .executes(FitCommand::info)))
            .then(Commands.literal("mannequin")
                .then(Commands.literal("remove").executes(c -> {
                    remove();
                    return 1;
                }))
                .then(Commands.argument("model", StringArgumentType.string())
                    .then(Commands.argument("pos", Vec3Argument.vec3())
                        .then(Commands.argument("yaw", FloatArgumentType.floatArg())
                            .executes(FitCommand::spawn))))));
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent event) {
        remove();
    }

    private static int info(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = EntityArgument.getPlayer(c, "player");
        var shape = PlayerFit.applied(player);
        if (shape == null) {
            c.getSource().sendSuccess(() -> Component.literal(player.getGameProfile().getName() + ": vanilla box"),
                false);
            return 0;
        }
        var eye = player.getEyePosition().subtract(player.position());
        var state = shape.state(player, player.getPose());
        var yaw = shape.bodyYaw(player, 1f);
        // The box actually colliding (Battlefield Movement's prone/dive envelope included), relative to the position.
        var box = player.getBoundingBox().move(-player.getX(), -player.getY(), -player.getZ());
        var b = new StringBuilder(player.getGameProfile().getName()).append(": hit scale ")
            .append(String.format(java.util.Locale.ROOT, "%.2f", shape.hitScale))
            .append(String.format(java.util.Locale.ROOT, ", now %s, box %.2f x %.2f, eye at %+.3f %+.3f %+.3f "
                + "(%.3f ahead along the body)", state, player.getBbWidth(), player.getBbHeight(), eye.x, eye.y, eye.z,
                ahead(eye, yaw)))
            .append(String.format(java.util.Locale.ROOT, "\n collision box x %+.2f..%+.2f y %+.2f..%+.2f z %+.2f..%+.2f,"
                + " body yaw %.1f, view yaw %.1f", box.minX, box.maxX, box.minY, box.maxY, box.minZ, box.maxZ, yaw,
                player.getYRot()));
        for (var p : FitPose.values()) {
            var f = shape.fit.pose(p);
            b.append(String.format(java.util.Locale.ROOT, "\n %s %.2f x %.2f (fitted %.2f x %.2f), eye %.2f, %s", p,
                shape.dimensions(p).width,
                shape.dimensions(p).height,
                f.width, f.height, f.eye, f.clip));
        }
        c.getSource().sendSuccess(() -> Component.literal(b.toString()), false);
        return 1;
    }

    /** Distance along the body's facing (vanilla's look vector for a yaw: -sin, cos). */
    private static double ahead(net.minecraft.world.phys.Vec3 v, float yaw) {
        var r = Math.toRadians(yaw);
        return -v.x * Math.sin(r) + v.z * Math.cos(r);
    }

    private static int spawn(CommandContext<CommandSourceStack> c) {
        var source = c.getSource();
        var path = StringArgumentType.getString(c, "model");
        var pos = Vec3Argument.getVec3(c, "pos");
        var yaw = FloatArgumentType.getFloat(c, "yaw");
        var catalog = ServerModelService.current().flatMap(ServerModelService::catalog);
        var container = catalog.flatMap(cat -> cat.findPath(path));
        if (container.isEmpty()) {
            source.sendFailure(Component.literal("Model not found in the YSM catalog: " + path));
            return 0;
        }
        try {
            var hash = container.get().view().getModelHash();
            var fit = YsmServer.fit(container.get());
            if (fit == null) {
                source.sendFailure(Component.literal("Model has no player geometry: " + path));
                return 0;
            }
            remove();
            var level = source.getLevel();
            // Typed as ServerPlayer so the calls below name a Minecraft owner class the SRG remap knows.
            ServerPlayer fake = FakePlayerFactory.get(level, PROFILE);
            fake.moveTo(pos.x, pos.y, pos.z, yaw, 0f);
            fake.setYHeadRot(yaw);
            fake.yHeadRotO = yaw;
            fake.yBodyRot = yaw;
            fake.yBodyRotO = yaw;
            // It never ticks: mark it grounded so clients (which get onGround with its periodic position sync) play
            // the idle animation rather than falling.
            fake.setOnGround(true);
            var view = container.get().view();
            var requested = view.getMetadata().getSettings().defaultTexture().orElse("");
            var textures = view.getPlayer().getTextureNames();
            var texture = textures.contains(requested) ? requested : textures.stream().sorted().findFirst().orElse("");
            fake.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP)
                .ifPresent(cap -> cap.setCommandSelection(hash, texture, true));
            var fitState = PlayerFit.of(fake);
            fitState.applied = Resolved.of(fit, fit.toBytes(), FitConfig.params());
            fitState.key = "mannequin";
            fitState.pending = PlayerFit.NONE;
            fake.refreshDimensions();
            var info = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(fake));
            // To the players of this level (the only ones who can see it), before it spawns for them.
            level.players().forEach(p -> p.connection.send(info));
            level.addNewPlayer(fake);
            // Clients start a remote player's body at a random yaw within 50 degrees of its facing; a swing turns
            // it to face exactly where the server has it.
            fake.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            mannequin = fake;
            source.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "Mannequin wearing %s at %.2f %.2f %.2f: box %.2f x %.2f", path, pos.x, pos.y, pos.z,
                fake.getBbWidth(), fake.getBbHeight())), true);
            return 1;
        } catch (Exception e) {
            FitboxMod.LOG.warn("mannequin failed", e);
            source.sendFailure(Component.literal("Mannequin failed: " + e));
            return 0;
        }
    }

    private static void remove() {
        var fake = mannequin;
        if (fake == null) {
            return;
        }
        mannequin = null;
        var level = fake.serverLevel();
        level.removePlayerImmediately(fake, Entity.RemovalReason.DISCARDED);
        var info = new ClientboundPlayerInfoRemovePacket(List.of(fake.getUUID()));
        level.players().forEach(p -> p.connection.send(info));
    }
}
