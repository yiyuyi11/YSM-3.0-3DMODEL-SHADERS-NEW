package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile;
import com.elfmcys.ysm.format.parser.pojo.model.GeoModel;
import com.google.gson.Gson;
import com.ysmfit.fitbox.core.Clip;
import com.ysmfit.fitbox.core.FitPose;
import com.ysmfit.fitbox.core.FitShape;
import com.ysmfit.fitbox.core.Fitter;
import com.ysmfit.fitbox.core.Fk;
import com.ysmfit.fitbox.core.Rig;
import com.ysmfit.fitbox.core.Shape;
import com.ysmfit.fitbox.ysm.YsmGeometry;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Offline check of the fit: runs YSM's own humanoid import (the same GeoModelProto and compiled clips the server's
 * catalog stores) or YSM's classic geo builder on the builtin default model, feeds the result through the mod's
 * fitter, prints boxes and shapes, ray-tests a few hits and misses, and draws front and side overlays.
 *
 * <p>usage: FitOffline &lt;out dir&gt; &lt;model.vrm|pmx|...|default&gt;...
 */
public final class FitOffline {
    public static void main(String[] args) throws Exception {
        com.elfmcys.ysm.natives.NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        com.elfmcys.ysm.natives.NativeRuntime.initialize(
            com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.WARN));
        var out = Path.of(args[0]);
        Files.createDirectories(out);
        for (var i = 1; i < args.length; i++) {
            run(out, args[i]);
        }
    }

    private static void run(Path out, String arg) throws Exception {
        Rig rig;
        Clip.Library clips;
        String name;
        if (arg.equals("default")) {
            name = "default";
            try (var in = GeoModel.class.getResourceAsStream("/assets/ysm/builtin/default/models/main.json")) {
                var raw = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), GeoModel.class);
                var result = GeoBuilder.build(raw);
                rig = YsmGeometry.rig(result.model, 0.7f, 0.7f); // ysm.json height_scale/width_scale
            }
            clips = YsmGeometry.defaults();
        } else {
            var model = Path.of(arg);
            name = model.getFileName().toString().replaceAll("\\.[^.]+$", "");
            var data = Files.readAllBytes(model);
            var buffer = ByteBuffer.allocateDirect(data.length).put(data).flip();
            try (var humanoid = HumanoidImport.load(model.getFileName().toString(), buffer, file -> {
                var f = model.resolveSibling(file);
                return Files.exists(f) ? ByteBuffer.wrap(Files.readAllBytes(f)) : null;
            })) {
                var main = humanoid.main();
                rig = YsmGeometry.rig(main.model, main.widthScale, main.heightScale);
                var file = AnimationFile.createGson(false).fromJson(humanoid.animation(), AnimationFile.class);
                var proto = AnimationBuilder.build(file, new MolangFrontend(), model.toString());
                clips = YsmGeometry.clips(proto, "model").orElse(YsmGeometry.defaults());
            }
        }
        var start = System.nanoTime();
        var fit = Fitter.fit(rig, clips, new Fitter.Params());
        var ms = (System.nanoTime() - start) / 1e6;
        var report = new StringBuilder();
        report.append("== ").append(name).append(String.format(Locale.ROOT, " (fit %.0f ms)%n", ms));
        report.append(fit.notes);
        report.append(fit.summary());
        for (var p : new FitPose[] {FitPose.STANDING, FitPose.CROUCHING}) {
            report.append("  ").append(p).append(" shapes (model-local blocks, model faces -Z):\n");
            var pf = fit.pose(p);
            for (var s : pf.shapes) {
                report.append("    ").append(describe(s)).append('\n');
            }
        }
        report.append(rayChecks(fit));
        // Compiled Molang keyframes (e.g. the importer's held-item selectors) evaluated for an empty-handed body.
        for (var clipName : new String[] {"idle", "sneaking"}) {
            var clip = clips.get(clipName);
            for (var bone : new String[] {"RightArm", "LeftArm", "RightForeArm"}) {
                var ch = clip == null ? null : clip.get(bone);
                if (ch != null) {
                    report.append(String.format(Locale.ROOT, "  clip %s(%s) %s rotation (%.2f, %.2f, %.2f)%n",
                        clipName, clip.source, bone, ch[0], ch[1], ch[2]));
                }
            }
        }
        Files.write(out.resolve(name + ".fit.bin"), fit.toBytes());
        Files.writeString(out.resolve(name + ".report.txt"), report);
        System.out.print(report);
        draw(out.resolve(name + ".overlay.png"), name, rig, clips, fit,
            new FitPose[] {FitPose.STANDING, FitPose.CROUCHING, FitPose.SWIMMING, FitPose.SITTING});
        draw(out.resolve(name + ".overlay-bfm-tacz.png"), name, rig, clips, fit,
            new FitPose[] {FitPose.CRAWLING, FitPose.PRONE_ENTER, FitPose.SLIDE, FitPose.SLIDE_CROUCH, FitPose.DIVE,
                FitPose.GUN_PRONE_RIFLE, FitPose.GUN_AIM_RIFLE});
    }

    /** Horizontal rays from in front of the standing model (it faces -Z): between the legs, beside the arm, ... */
    private static String rayChecks(FitShape fit) {
        var b = new StringBuilder("  ray checks, standing (ray along +Z from z=-2, model-local):\n");
        var pf = fit.pose(FitPose.STANDING);
        var half = pf.width / 2;
        // Gap between the thighs at a third of the height; outer side of the upper arm at shoulder height minus a bit.
        Shape shinL = null, armL = null, chest = null, head = null;
        for (var s : pf.shapes) {
            switch (s.seg()) {
                case LEFT_SHIN -> shinL = s;
                case LEFT_UPPER_ARM -> armL = s;
                case CHEST -> chest = s;
                case HEAD -> head = s;
                default -> {
                }
            }
        }
        var probes = new java.util.ArrayList<double[]>();
        var labels = new java.util.ArrayList<String>();
        if (shinL != null) {
            var y = (shinL.d[1] + shinL.d[4]) / 2;
            probes.add(new double[] {0, y});
            labels.add("between the legs at shin height");
            probes.add(new double[] {(shinL.d[0] + shinL.d[3]) / 2, y});
            labels.add("through the left shin");
        }
        if (armL != null) {
            var y = (armL.d[1] + armL.d[4]) / 2;
            var x = (armL.d[0] + armL.d[3]) / 2;
            var outer = x + Math.signum(x) * (armL.d[6] + 0.04);
            probes.add(new double[] {outer, y});
            labels.add("4 cm outside the left upper arm");
            probes.add(new double[] {x, y});
            labels.add("through the left upper arm");
        }
        if (chest != null) {
            probes.add(new double[] {chest.d[0], chest.d[1]});
            labels.add("chest center");
        }
        if (head != null) {
            probes.add(new double[] {(head.d[0] + head.d[3]) / 2, (head.d[1] + head.d[4]) / 2 + head.d[6] + 0.05});
            labels.add("5 cm above the head");
        }
        for (var i = 0; i < probes.size(); i++) {
            var x = probes.get(i)[0];
            var y = probes.get(i)[1];
            var inBox = Math.abs(x) <= half && y >= 0 && y <= pf.height;
            String hit = "miss";
            for (var s : pf.shapes) {
                var t = s.clip(x, y, -2, 0, 0, 4);
                if (t >= 0) {
                    hit = "HIT " + s.seg() + String.format(Locale.ROOT, " at z=%.3f", -2 + 4 * t);
                    break;
                }
            }
            b.append(String.format(Locale.ROOT, "    %-34s x=%6.3f y=%6.3f  inside AABB=%-5s shapes: %s%n",
                labels.get(i), x, y, inBox, hit));
        }
        return b.toString();
    }

    private static String describe(Shape s) {
        var d = s.d;
        if (s.kind == Shape.CAPSULE) {
            return String.format(Locale.ROOT, "%-15s capsule a=(%.3f %.3f %.3f) b=(%.3f %.3f %.3f) r=%.3f", s.seg(),
                d[0], d[1], d[2], d[3], d[4], d[5], d[6]);
        }
        return String.format(Locale.ROOT, "%-15s box c=(%.3f %.3f %.3f) half=(%.3f %.3f %.3f) u=(%.2f %.2f %.2f)",
            s.seg(), d[0], d[1], d[2], d[12], d[13], d[14], d[3], d[4], d[5]);
    }

    // ---------------- drawing ----------------

    private static final int PANEL = 420;
    private static final float PX_PER_BLOCK = 190;

    private static void draw(Path file, String name, Rig rig, Clip.Library clips, FitShape fit, FitPose[] poses)
        throws Exception {
        var img = new BufferedImage(PANEL * poses.length, PANEL * 2 + 40, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(250, 250, 248));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        g.setColor(Color.BLACK);
        g.drawString(name + ": grey = core mesh, light blue = loose (spring hair/cloth), black = fitted AABB, "
            + "red = capsules, green = boxes. Top row front view, bottom row side view (front to the right).", 10, 24);
        for (var col = 0; col < poses.length; col++) {
            for (var row = 0; row < 2; row++) {
                panel(g, rig, clips, fit, poses[col], row == 1, col * PANEL, 40 + row * PANEL);
            }
        }
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
    }

    private static void panel(Graphics2D g, Rig rig, Clip.Library clips, FitShape fit, FitPose pose, boolean side,
        int ox, int oy) {
        var pf = fit.pose(pose);
        var clip = pick(clips, pose);
        if (pose.base != null) {
            var top = pick(clips, pose);
            clip = pick(clips, pose.base);
            if (top != null && clip != null) {
                clip = clip.overlay(top);
            }
        }
        var gm = Fk.pose(rig, clip);
        var local = new Matrix4f().translation(0, pose.renderOffsetY, 0)
            .scale(rig.widthScale, rig.heightScale, rig.widthScale);
        var groundY = oy + PANEL - 30;
        var cx = ox + PANEL / 2;
        g.setColor(new Color(225, 225, 220));
        g.drawLine(ox + 10, groundY, ox + PANEL - 10, groundY);
        var p = new Vector3f();
        var acc = new Vector3f();
        var dominantLoose = new boolean[rig.count];
        for (var v = 0; v < rig.count; v++) {
            var best = 0;
            for (var k = 1; k < 4; k++) {
                if (rig.weights[v * 4 + k] > rig.weights[v * 4 + best]) {
                    best = k;
                }
            }
            var j = rig.joints[v * 4 + best];
            dominantLoose[v] = j >= 0 && j < rig.boneCount() && rig.loose[j];
        }
        var step = Math.max(1, rig.count / 60000);
        for (var v = 0; v < rig.count; v += step) {
            if (rig.area[v] <= 0) {
                continue;
            }
            acc.zero();
            var ws = 0f;
            for (var k = 0; k < 4; k++) {
                var w = rig.weights[v * 4 + k];
                var j = rig.joints[v * 4 + k];
                if (w <= 0 || j < 0 || j >= gm.length) {
                    continue;
                }
                new Matrix4f(local).mul(gm[j]).transformPosition(p.set(rig.pos[v * 3], rig.pos[v * 3 + 1],
                    rig.pos[v * 3 + 2]));
                acc.fma(w, p);
                ws += w;
            }
            if (ws > 0) {
                acc.div(ws);
            }
            var u = side ? -acc.z : -acc.x;
            g.setColor(dominantLoose[v] ? new Color(150, 190, 235) : new Color(150, 150, 150));
            g.fillRect(Math.round(cx + u * PX_PER_BLOCK), Math.round(groundY - acc.y * PX_PER_BLOCK), 1, 1);
        }
        // AABB (centered on the entity position, which is the model origin)
        g.setStroke(new BasicStroke(2f));
        g.setColor(Color.BLACK);
        var hw = pf.width / 2 * PX_PER_BLOCK;
        g.drawRect(Math.round(cx - hw), Math.round(groundY - pf.height * PX_PER_BLOCK), Math.round(2 * hw),
            Math.round(pf.height * PX_PER_BLOCK));
        g.setColor(new Color(200, 120, 0));
        g.drawLine(Math.round(cx - hw - 8), Math.round(groundY - pf.eye * PX_PER_BLOCK), Math.round(cx + hw + 8),
            Math.round(groundY - pf.eye * PX_PER_BLOCK));
        g.setStroke(new BasicStroke(1.5f));
        for (var s : pf.shapes) {
            if (s.kind == Shape.CAPSULE) {
                g.setColor(new Color(220, 30, 30));
                capsule(g, s, side, cx, groundY);
            } else {
                g.setColor(new Color(20, 150, 40));
                box(g, s, side, cx, groundY);
            }
        }
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.drawString(String.format(Locale.ROOT, "%s %s: %.2f x %.2f, eye %.2f", pose, side ? "side" : "front",
            pf.width, pf.height, pf.eye), ox + 8, oy + 16);
        g.drawString("clip " + pf.clip, ox + 8, oy + 32);
    }

    private static Clip pick(Clip.Library clips, FitPose pose) {
        for (var c : pose.clips) {
            var clip = clips.get(c, pose.sampleAt);
            if (clip != null) {
                return clip;
            }
        }
        return null;
    }

    private static double[] proj(double x, double y, double z, boolean side, int cx, int gy) {
        var u = side ? -z : -x;
        return new double[] {cx + u * PX_PER_BLOCK, gy - y * PX_PER_BLOCK};
    }

    private static void capsule(Graphics2D g, Shape s, boolean side, int cx, int gy) {
        var d = s.d;
        var a = proj(d[0], d[1], d[2], side, cx, gy);
        var b = proj(d[3], d[4], d[5], side, cx, gy);
        var r = d[6] * PX_PER_BLOCK;
        // Projected capsule outline: two circles and the two tangent lines.
        g.draw(new java.awt.geom.Ellipse2D.Double(a[0] - r, a[1] - r, 2 * r, 2 * r));
        g.draw(new java.awt.geom.Ellipse2D.Double(b[0] - r, b[1] - r, 2 * r, 2 * r));
        var dx = b[0] - a[0];
        var dy = b[1] - a[1];
        var len = Math.hypot(dx, dy);
        if (len > 1e-6) {
            var nx = -dy / len * r;
            var ny = dx / len * r;
            g.draw(new Line2D.Double(a[0] + nx, a[1] + ny, b[0] + nx, b[1] + ny));
            g.draw(new Line2D.Double(a[0] - nx, a[1] - ny, b[0] - nx, b[1] - ny));
        }
    }

    private static void box(Graphics2D g, Shape s, boolean side, int cx, int gy) {
        var d = s.d;
        var corners = new double[8][];
        var k = 0;
        for (var su = -1; su <= 1; su += 2) {
            for (var sv = -1; sv <= 1; sv += 2) {
                for (var sw = -1; sw <= 1; sw += 2) {
                    var x = d[0] + su * d[12] * d[3] + sv * d[13] * d[6] + sw * d[14] * d[9];
                    var y = d[1] + su * d[12] * d[4] + sv * d[13] * d[7] + sw * d[14] * d[10];
                    var z = d[2] + su * d[12] * d[5] + sv * d[13] * d[8] + sw * d[14] * d[11];
                    corners[k++] = proj(x, y, z, side, cx, gy);
                }
            }
        }
        int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6},
            {3, 7}};
        var path = new Path2D.Double();
        for (var e : edges) {
            path.moveTo(corners[e[0]][0], corners[e[0]][1]);
            path.lineTo(corners[e[1]][0], corners[e[1]][1]);
        }
        g.draw(path);
    }

}
