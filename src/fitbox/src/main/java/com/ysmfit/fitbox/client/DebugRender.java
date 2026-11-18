package com.ysmfit.fitbox.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ysmfit.fitbox.FitConfig;
import com.ysmfit.fitbox.PlayerFit;
import com.ysmfit.fitbox.core.Shape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

/**
 * With hitboxes shown (F3+B), fitted players also get their hit shapes: green on YSM's live bone poses (what the
 * crosshair tests), orange for the server's approximation (projectiles, explosions, melee validation). The fitted box
 * itself is vanilla's white hitbox, since it is the player's real bounding box.
 */
final class DebugRender {
    private static final int SEGMENTS = 16;

    private DebugRender() {}

    static void render(RenderLevelStageEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.getEntityRenderDispatcher().shouldRenderHitBoxes()) {
            return;
        }
        var live = get(FitConfig.DEBUG_LIVE);
        var server = get(FitConfig.DEBUG_SERVER);
        var cam = event.getCamera().getPosition();
        var pt = event.getPartialTick();
        var buffers = mc.renderBuffers().bufferSource();
        var lines = buffers.getBuffer(RenderType.lines());
        var pose = event.getPoseStack();
        var origin = new double[3];
        for (var player : mc.level.players()) {
            var shape = PlayerFit.applied(player);
            if (shape == null || player == event.getCamera().getEntity() && !event.getCamera().isDetached()) {
                continue;
            }
            if (live) {
                var shapes = ClientFits.live(player, origin);
                if (shapes != null) {
                    draw(pose, lines, shapes, origin, cam.x, cam.y, cam.z, 0.2f, 1f, 0.3f);
                }
            }
            if (server) {
                var pos = player.getPosition(pt);
                origin[0] = pos.x;
                origin[1] = pos.y;
                origin[2] = pos.z;
                draw(pose, lines, shape.approxShapes(player, pt), origin, cam.x, cam.y, cam.z, 1f, 0.55f, 0.1f);
            }
        }
        buffers.endBatch(RenderType.lines());
    }

    private static boolean get(net.minecraftforge.common.ForgeConfigSpec.BooleanValue v) {
        try {
            return v.get();
        } catch (IllegalStateException e) {
            return v.getDefault();
        }
    }

    private static void draw(PoseStack stack, VertexConsumer lines, Shape[] shapes, double[] origin, double cx,
        double cy, double cz, float r, float g, float b) {
        stack.pushPose();
        stack.translate(origin[0] - cx, origin[1] - cy, origin[2] - cz);
        var pose = stack.last();
        for (var s : shapes) {
            if (s.kind == Shape.CAPSULE) {
                capsule(pose, lines, s.d, r, g, b);
            } else {
                box(pose, lines, s.d, r, g, b);
            }
        }
        stack.popPose();
    }

    private static void capsule(PoseStack.Pose pose, VertexConsumer lines, float[] d, float r, float g, float b) {
        var a = new Vector3f(d[0], d[1], d[2]);
        var c = new Vector3f(d[3], d[4], d[5]);
        var radius = d[6];
        var u = new Vector3f(c).sub(a);
        if (u.lengthSquared() < 1e-8f) {
            u.set(0, 1, 0);
        }
        u.normalize();
        var v = Math.abs(u.y) < 0.9f ? new Vector3f(0, 1, 0).cross(u).normalize() : new Vector3f(1, 0, 0).cross(u)
            .normalize();
        var w = new Vector3f(u).cross(v);
        for (var i = 0; i < SEGMENTS; i++) {
            var t0 = (float)(2 * Math.PI * i / SEGMENTS);
            var t1 = (float)(2 * Math.PI * (i + 1) / SEGMENTS);
            for (var end : new Vector3f[] {a, c}) {
                line(pose, lines, ring(end, v, w, radius, t0), ring(end, v, w, radius, t1), r, g, b);
            }
        }
        for (var side : new Vector3f[] {v, w}) {
            for (var sign = -1; sign <= 1; sign += 2) {
                var off = new Vector3f(side).mul(sign * radius);
                line(pose, lines, new Vector3f(a).add(off), new Vector3f(c).add(off), r, g, b);
            }
            // Half circles over the two caps.
            for (var i = 0; i < SEGMENTS / 2; i++) {
                var t0 = (float)(Math.PI * i / (SEGMENTS / 2));
                var t1 = (float)(Math.PI * (i + 1) / (SEGMENTS / 2));
                line(pose, lines, cap(c, u, side, radius, t0), cap(c, u, side, radius, t1), r, g, b);
                line(pose, lines, cap(a, new Vector3f(u).negate(), side, radius, t0),
                    cap(a, new Vector3f(u).negate(), side, radius, t1), r, g, b);
            }
        }
    }

    private static Vector3f ring(Vector3f center, Vector3f v, Vector3f w, float radius, float t) {
        return new Vector3f(center).fma((float)Math.cos(t) * radius, v).fma((float)Math.sin(t) * radius, w);
    }

    private static Vector3f cap(Vector3f center, Vector3f out, Vector3f side, float radius, float t) {
        return new Vector3f(center).fma((float)Math.cos(t) * radius, side).fma((float)Math.sin(t) * radius, out);
    }

    private static void box(PoseStack.Pose pose, VertexConsumer lines, float[] d, float r, float g, float b) {
        var corners = new Vector3f[8];
        var k = 0;
        for (var su = -1; su <= 1; su += 2) {
            for (var sv = -1; sv <= 1; sv += 2) {
                for (var sw = -1; sw <= 1; sw += 2) {
                    corners[k++] = new Vector3f(d[0], d[1], d[2]).fma(su * d[12], new Vector3f(d[3], d[4], d[5]))
                        .fma(sv * d[13], new Vector3f(d[6], d[7], d[8])).fma(sw * d[14], new Vector3f(d[9], d[10], d[11]));
                }
            }
        }
        int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6},
            {3, 7}};
        for (var e : edges) {
            line(pose, lines, corners[e[0]], corners[e[1]], r, g, b);
        }
    }

    private static void line(PoseStack.Pose pose, VertexConsumer lines, Vector3f p, Vector3f q, float r, float g,
        float b) {
        var n = new Vector3f(q).sub(p);
        if (n.lengthSquared() < 1e-12f) {
            return;
        }
        n.normalize();
        lines.vertex(pose.pose(), p.x, p.y, p.z).color(r, g, b, 1f).normal(pose.normal(), n.x, n.y, n.z).endVertex();
        lines.vertex(pose.pose(), q.x, q.y, q.z).color(r, g, b, 1f).normal(pose.normal(), n.x, n.y, n.z).endVertex();
    }
}
