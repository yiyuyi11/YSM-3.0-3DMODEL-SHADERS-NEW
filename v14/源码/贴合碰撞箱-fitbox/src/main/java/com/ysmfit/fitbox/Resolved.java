package com.ysmfit.fitbox;

import com.ysmfit.fitbox.core.EyeMath;
import com.ysmfit.fitbox.core.FitPose;
import com.ysmfit.fitbox.core.FitShape;
import com.ysmfit.fitbox.core.Shape;
import java.io.IOException;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * A model's fit after the server's clamps: what {@code Player.getDimensions} answers per pose, and the hit shapes.
 * Both sides build it from the same fit bytes and the same {@link Params}, so the client predicts exactly the box the
 * server collides with.
 */
public final class Resolved {
    /** Server switches and limits, sent with every shape. */
    public record Params(boolean collision, boolean precise, boolean live, float minWidth, float maxWidth,
        float minHeight, float maxHeight, float minLyingHeight, float maxLyingHeight) {
        void write(FriendlyByteBuf buf) {
            buf.writeBoolean(collision);
            buf.writeBoolean(precise);
            buf.writeBoolean(live);
            buf.writeFloat(minWidth);
            buf.writeFloat(maxWidth);
            buf.writeFloat(minHeight);
            buf.writeFloat(maxHeight);
            buf.writeFloat(minLyingHeight);
            buf.writeFloat(maxLyingHeight);
        }

        static Params read(FriendlyByteBuf buf) {
            return new Params(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }
    }

    public final FitShape fit;
    public final byte[] fitBytes;
    public final Params params;
    private final EntityDimensions[] dims;
    private final float[] eyes;
    /** Hit shapes grow by this about the feet when the model is under minHeight (fairness). */
    public final float hitScale;
    /** Per pose: hit shapes in the model-local frame, scaled to blocks and by {@link #hitScale}. */
    private final Shape[][] shapes;
    /**
     * Per pose: how far the body (arms left out) reaches ahead of and behind the origin, blocks; ahead at least to
     * the eye plus half of vanilla's in-wall square (0.8 x the box width; Entity#isInWall reads getEyePosition), so a
     * lying player's face to a wall neither pulls the eye back nor suffocates.
     */
    private final double[] ahead;
    private final double[] behind;

    private Resolved(FitShape fit, byte[] fitBytes, Params params) {
        this.fit = fit;
        this.fitBytes = fitBytes;
        this.params = params;
        var poses = FitPose.values();
        dims = new EntityDimensions[poses.length];
        eyes = new float[poses.length];
        shapes = new Shape[poses.length][];
        ahead = new double[poses.length];
        behind = new double[poses.length];
        var standing = fit.pose(FitPose.STANDING).height;
        hitScale = standing > 1e-3f ? Math.max(1f, params.minHeight / standing) : 1f;
        for (var p : poses) {
            var f = fit.pose(p);
            var w = Mth.clamp(f.width, params.minWidth, params.maxWidth);
            var h = p.lowHeight() ? Mth.clamp(f.height, params.minLyingHeight, params.maxHeight)
                : p.lying ? Mth.clamp(f.height, params.minLyingHeight, params.maxLyingHeight)
                    : Mth.clamp(f.height, params.minHeight, params.maxHeight);
            dims[p.ordinal()] = EntityDimensions.scalable(w, h);
            eyes[p.ordinal()] = Mth.clamp(f.eye * hitScale, Math.min(0.1f, h * 0.5f), h - 0.05f);
            var s = new Shape[f.shapes.length];
            for (var i = 0; i < s.length; i++) {
                s[i] = hitScale == 1f ? f.shapes[i] : f.shapes[i].scaled(hitScale);
            }
            shapes[p.ordinal()] = s;
            var eyeAhead = -f.eyePoint[2] * hitScale;
            ahead[p.ordinal()] = Math.max(EyeMath.reach(s, true), eyeAhead + 0.4 * dims[p.box().ordinal()].width);
            behind[p.ordinal()] = EyeMath.reach(s, false);
        }
    }

    public static Resolved of(FitShape fit, byte[] bytes, Params params) {
        return new Resolved(fit, bytes, params);
    }

    public static Resolved read(FriendlyByteBuf buf) throws IOException {
        var params = Params.read(buf);
        var bytes = buf.readByteArray();
        return new Resolved(FitShape.fromBytes(bytes), bytes, params);
    }

    public void write(FriendlyByteBuf buf) {
        params.write(buf);
        buf.writeByteArray(fitBytes);
    }

    /**
     * The fitted state for a player in a pose, or null where vanilla stays (dying). Vanilla's crawl pose out of water is
     * {@link FitPose#CRAWLING}; a Battlefield Movement state (reported by the owner's client on the server, read
     * directly on clients) takes precedence on the ground.
     */
    @Nullable
    public FitPose state(Player player, Pose pose) {
        if (pose != Pose.DYING && pose != Pose.SLEEPING && pose != Pose.FALL_FLYING && pose != Pose.SPIN_ATTACK
            && !player.isPassenger() && !player.isSwimming()) {
            var bfm = bfmState(player);
            if (bfm != null) {
                return bfm;
            }
        }
        return vanillaState(player, pose);
    }

    @Nullable
    public static FitPose vanillaState(Player player, Pose pose) {
        return switch (pose) {
            case CROUCHING -> FitPose.CROUCHING;
            case SWIMMING -> player.isSwimming() || player.isInWater() ? FitPose.SWIMMING : FitPose.CRAWLING;
            case FALL_FLYING -> FitPose.FALL_FLYING;
            case SLEEPING -> FitPose.SLEEPING;
            case SPIN_ATTACK -> FitPose.SPIN_ATTACK;
            case DYING -> null;
            default -> {
                var vehicle = player.getVehicle();
                if (vehicle == null) {
                    yield FitPose.STANDING;
                }
                if (vehicle instanceof Boat) {
                    yield FitPose.BOAT;
                }
                if (vehicle instanceof AbstractHorse) {
                    yield FitPose.RIDING;
                }
                if (vehicle instanceof Pig || vehicle instanceof Strider) {
                    yield FitPose.RIDING_SMALL;
                }
                yield FitPose.SITTING;
            }
        };
    }

    /** Battlefield Movement state: the fresh report on the server, the mod's client API on clients. */
    @Nullable
    private FitPose bfmState(Player player) {
        if (player.level().isClientSide) {
            var source = Bfm.client;
            return source == null ? null : source.state(player);
        }
        var server = Bfm.server();
        if (server != null) {
            return server.state(player); // the mod's own server state
        }
        var fit = PlayerFit.of(player);
        return fit.reportedState != null && fit.reportFresh(player) ? fit.reportedState : null;
    }

    /** The state that picks the hit shapes: the box state, with TACZ's held-gun stance layered on where fitted. */
    @Nullable
    public FitPose shapeState(Player player) {
        var state = state(player, player.getPose());
        if (state == FitPose.CRAWLING) {
            return lyingPose(player);
        }
        if (state == FitPose.STANDING) {
            var stance = Tacz.stance(player);
            if (stance != Tacz.NONE) {
                var pistol = (stance & Tacz.PISTOL) != 0;
                if ((stance & Tacz.AIM) != 0) {
                    return pistol ? FitPose.GUN_AIM_PISTOL : FitPose.GUN_AIM_RIFLE;
                }
                return pistol ? FitPose.GUN_HOLD_PISTOL : FitPose.GUN_HOLD_RIFLE;
            }
        }
        return state;
    }

    @Nullable
    public EntityDimensions dimensions(Player player, Pose pose) {
        if (!params.collision) {
            return null;
        }
        var s = state(player, pose);
        return s == null ? null : dims[s.box().ordinal()];
    }

    /** The fitted box of a state (its own or its base state's). */
    public EntityDimensions dimensions(FitPose state) {
        return dims[state.box().ordinal()];
    }

    /** Eye height for a pose; NaN where vanilla stays. */
    public float eye(Player player, Pose pose) {
        if (!params.collision) {
            return Float.NaN;
        }
        var s = state(player, pose);
        return s == null ? Float.NaN : eyes[s.box().ordinal()];
    }

    /**
     * The server's approximation of a player's hit shapes, relative to the player's position (no animation: the
     * current pose's clip at rest, turned by the body yaw, with the head shapes turned to the look direction).
     */
    public Shape[] approxShapes(Player player, float partialTick) {
        var state = shapeState(player);
        if (state == null) {
            return new Shape[0];
        }
        var local = shapes[state.ordinal()];
        var root = new Matrix4f();
        if (state == FitPose.SLEEPING) {
            var bed = player.getBedOrientation();
            if (bed != null) {
                // YSM: translate(-bed * (standing eye - 0.1)), then vanilla's sleeping rotation by the bed.
                var offset = player.getEyeHeight(Pose.STANDING) - 0.1f;
                root.translate(-bed.getStepX() * offset, 0, -bed.getStepZ() * offset);
                root.rotateY((float)Math.toRadians(sleepRotation(bed)));
            } else {
                root.rotateY((float)Math.toRadians(Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot)));
            }
        } else {
            root.rotateY((float)Math.toRadians(180f - bodyYaw(player, partialTick)));
        }
        var head = new Matrix4f(root);
        if (state != FitPose.SLEEPING) {
            var pivot = fit.pose(state).headPivot;
            float px = pivot[0] * hitScale, py = pivot[1] * hitScale, pz = pivot[2] * hitScale;
            var bodyYaw = bodyYaw(player, partialTick);
            var headYaw = Mth.rotLerp(partialTick, player.yHeadRotO, player.yHeadRot);
            var pitch = Mth.lerp(partialTick, player.xRotO, player.getXRot());
            // Model space faces -Z: turning the head by +d degrees of yaw is rotY(-d); looking down is rotX(-pitch).
            head.translate(px, py, pz).rotateY((float)Math.toRadians(-Mth.wrapDegrees(headYaw - bodyYaw)))
                .rotateX((float)Math.toRadians(-pitch)).translate(-px, -py, -pz);
        }
        var out = new Shape[local.length];
        for (var i = 0; i < local.length; i++) {
            out[i] = local[i].transform(local[i].seg().isHead() ? head : root, 1f);
        }
        return out;
    }

    /** States whose eyes follow the model's head (and override Battlefield Movement's eye for YSM wearers). */
    public static boolean eyeManaged(FitPose state) {
        return switch (state) {
            case CRAWLING, PRONE_ENTER, PRONE_EXIT, SLIDE, SLIDE_CROUCH, DIVE -> true;
            default -> false;
        };
    }

    /** Whether this player's eye follows the model's head now ({@link #eyeManaged(FitPose)}). */
    public boolean eyeManaged(Player player) {
        var state = params.collision ? state(player, player.getPose()) : null;
        return state != null && eyeManaged(state);
    }

    /**
     * Where the eye should be this tick (model-local blocks: x across, y up, z = -ahead), before easing, heading and
     * cover, in a crawl, prone, slide or dive state: the fitted eye point of the pose the model is drawn in (prone with
     * a TACZ gun: the gun-prone pose, whose head sits about 0.3 block further ahead). Through Battlefield Movement's
     * transitions it follows the mod's own progress: getting up blends from the lying eye to the standing eye; going
     * down blends from where the eye was when the entry began (standing, or the dive or slide that ends in it, whose
     * entry the mod starts part way down) to the lying eye; the dive's 0.3 s entry blends from the standing eye where
     * its progress is known (clients). Each eye point keeps its horizontal part only from {@link EyeMath#MIN_AHEAD}
     * out. Null for every other state. Called once per tick by {@link Eyes#tick}, which keeps the entry's start in
     * {@code fit}.
     */
    @Nullable
    public double[] eyeTarget(Player player, PlayerFit fit) {
        var state = params.collision ? state(player, player.getPose()) : null;
        if (state != FitPose.PRONE_ENTER) {
            fit.entryFrom = null;
        }
        if (state == null || !eyeManaged(state)) {
            return null;
        }
        var source = player.level().isClientSide ? Bfm.client : Bfm.server();
        double[] eye;
        if (state == FitPose.PRONE_ENTER || state == FitPose.PRONE_EXIT) {
            var amount = source == null ? Float.NaN : source.proneAmount(player);
            if (Float.isNaN(amount)) {
                eye = eyePoint(state);
            } else if (state == FitPose.PRONE_EXIT) {
                eye = EyeMath.lerp(eyePoint(FitPose.STANDING), eyePoint(lyingPose(player)), amount);
            } else {
                if (fit.entryFrom == null) {
                    fit.entryFrom = fit.eyeNow != null ? fit.eyeNow.clone() : eyePoint(FitPose.STANDING);
                    fit.entryStart = amount;
                }
                var t = fit.entryStart >= 0.999f ? 1 : (amount - fit.entryStart) / (1 - fit.entryStart);
                eye = EyeMath.lerp(fit.entryFrom, eyePoint(lyingPose(player)), t);
            }
        } else if (state == FitPose.DIVE) {
            var amount = source == null ? Float.NaN : source.diveAmount(player);
            eye = Float.isNaN(amount) ? eyePoint(state)
                : EyeMath.lerp(eyePoint(FitPose.STANDING), eyePoint(state), amount);
        } else {
            eye = eyePoint(state == FitPose.CRAWLING ? lyingPose(player) : state);
        }
        eye[1] = Math.max(0.1, eye[1]);
        return eye;
    }

    private double[] eyePoint(FitPose pose) {
        return EyeMath.local(fit.pose(pose).eyePoint, hitScale);
    }

    /** The lying pose the model is drawn in: the crawl, or TACZ's gun-prone overlay for the held gun. */
    private FitPose lyingPose(Player player) {
        var stance = Tacz.stance(player);
        if (stance == Tacz.NONE) {
            return FitPose.CRAWLING;
        }
        return (stance & Tacz.PISTOL) != 0 ? FitPose.GUN_PRONE_PISTOL : FitPose.GUN_PRONE_RIFLE;
    }

    /**
     * Battlefield Movement's prone envelope for this player's body ({@code ProneGeometry.reach}, through
     * BfmReachMixin): how far the lying body reaches ahead (0) or behind (1) the position, or half its fitted width
     * across (2). Ahead it also covers the eye with vanilla's in-wall square (0.8 x width, Entity#isInWall reads
     * getEyePosition), so lying face to a wall neither pulls the eye back nor suffocates. Prone and its transitions
     * use the lying pose the model settles in (the mod scales the transitions itself); the dive its held last frame.
     * NaN elsewhere, which leaves the mod's own reach.
     */
    public double reach(Player player, int direction) {
        if (!params.collision) {
            return Double.NaN;
        }
        var state = state(player, player.getPose());
        FitPose body;
        if (state == FitPose.CRAWLING || state == FitPose.PRONE_ENTER || state == FitPose.PRONE_EXIT) {
            body = lyingPose(player);
        } else if (state == FitPose.DIVE) {
            body = state;
        } else {
            return Double.NaN;
        }
        return switch (direction) {
            case 0 -> ahead[body.ordinal()];
            case 1 -> behind[body.ordinal()];
            default -> dims[body.box().ordinal()].width / 2.0;
        };
    }

    /**
     * The heading the model is drawn at: vanilla's body yaw, or Battlefield Movement's prone heading (YSM turns a prone
     * model to it), which the server knows from the owner's report.
     */
    public float bodyYaw(Player player, float partialTick) {
        var vanilla = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        if (player.level().isClientSide) {
            var source = Bfm.client;
            return source == null ? vanilla : source.yaw(player, partialTick, vanilla);
        }
        var server = Bfm.server();
        if (server != null) {
            return server.yaw(player, partialTick, vanilla);
        }
        var fit = PlayerFit.of(player);
        return fit.reportedState != null && fit.reportFresh(player) ? fit.reportedYaw : vanilla;
    }

    private static float sleepRotation(Direction d) {
        return switch (d) {
            case SOUTH -> 90f;
            case NORTH -> 270f;
            case EAST -> 180f;
            default -> 0f;
        };
    }
}
