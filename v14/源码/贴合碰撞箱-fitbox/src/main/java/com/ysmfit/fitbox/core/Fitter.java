package com.ysmfit.fitbox.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Fits the per-pose boxes and the hit shapes of one model from its real geometry.
 *
 * <ul>
 *   <li>Samples are the skinned mesh vertices (weighted by the triangle area around them) or the classic cube corners
 *       (weighted by cube surface). Samples whose strongest joint is a loose bone (spring-bone hair and cloth, or
 *       hair/skirt/ribbon/... by name) are left out, so strands and skirts never widen the box or the hit shapes.</li>
 *   <li>Each pose is posed with the model's clip (else the YSM default clip of that name) sampled at time 0, with
 *       linear blend skinning; the box is then the area-weighted quantile extent of the posed core samples.</li>
 *   <li>Hit shapes: one capsule or oriented box per body part, fitted in zero-rotation model space by PCA with
 *       quantile-trimmed extents, carried by the part's root bone.</li>
 * </ul>
 */
public final class Fitter {
    /** Quantiles: box side extent, box top, capsule radius, shape end trim (each end). */
    public static final class Params {
        public float side = 0.985f;
        public float top = 0.995f;
        public float radius = 0.9f;
        public float trim = 0.01f;
    }

    private Fitter() {}

    public static FitShape fit(Rig rig, Clip.Library clips, Params params) {
        var notes = new StringBuilder();
        var n = rig.boneCount();
        // ---- body part of every bone ----
        var partRoot = new int[Segment.values().length];
        Arrays.fill(partRoot, -1);
        var found = 0;
        for (var s : Segment.values()) {
            if (s == Segment.BODY) {
                continue;
            }
            for (var name : s.roots) {
                var b = rig.bone(name);
                if (b >= 0) {
                    partRoot[s.ordinal()] = b;
                    found++;
                    break;
                }
            }
        }
        var segOf = new int[n];
        Arrays.fill(segOf, -1);
        var humanoid = found >= 8;
        for (int i : rig.order) {
            var own = -1;
            if (humanoid) {
                for (var s = 0; s < partRoot.length; s++) {
                    if (partRoot[s] == i) {
                        own = s;
                    }
                }
            }
            segOf[i] = own >= 0 ? own : rig.parent[i] >= 0 ? segOf[rig.parent[i]] : -1;
        }
        if (!humanoid) {
            // No humanoid part names: one box over the whole core body, carried by the topmost bone.
            var top = rig.order.length > 0 ? rig.order[0] : 0;
            partRoot[Segment.BODY.ordinal()] = top;
            Arrays.fill(segOf, Segment.BODY.ordinal());
            notes.append("skeleton without humanoid part names: single body box\n");
        }

        // ---- core samples ----
        var count = rig.count;
        var dominant = new int[count];
        var core = new boolean[count];
        var coreCount = 0;
        double looseArea = 0, coreArea = 0;
        for (var v = 0; v < count; v++) {
            var best = 0;
            for (var k = 1; k < 4; k++) {
                if (rig.weights[v * 4 + k] > rig.weights[v * 4 + best]) {
                    best = k;
                }
            }
            var j = rig.joints[v * 4 + best];
            dominant[v] = j;
            if (rig.area[v] <= 0 || j < 0 || j >= n) {
                continue;
            }
            if (rig.loose[j]) {
                looseArea += rig.area[v];
                continue;
            }
            core[v] = true;
            coreCount++;
            coreArea += rig.area[v];
        }
        if (coreCount == 0) {
            // Everything is loose (a model made of "hair"): fit the whole surface rather than nothing.
            for (var v = 0; v < count; v++) {
                if (rig.area[v] > 0 && dominant[v] >= 0 && dominant[v] < n) {
                    core[v] = true;
                    coreCount++;
                }
            }
        }
        notes.append(String.format(Locale.ROOT, "%s, %d bones, %d samples: %d core (area %.3f), loose area %.3f%n",
            rig.mesh ? "skinned mesh" : "cubes", n, count, coreCount, coreArea, looseArea));

        // ---- hit templates (zero-rotation model space) ----
        var templateBones = new ArrayList<String>();
        var templates = new ArrayList<Shape>();
        var templateBoneIndex = new ArrayList<Integer>();
        for (var s : Segment.values()) {
            var root = partRoot[s.ordinal()];
            if (root < 0) {
                continue;
            }
            var pts = new Samples(count);
            for (var v = 0; v < count; v++) {
                if (core[v] && segOf[dominant[v]] == s.ordinal()) {
                    pts.add(rig.pos[v * 3], rig.pos[v * 3 + 1], rig.pos[v * 3 + 2], rig.area[v]);
                }
            }
            if (pts.n < 4) {
                continue;
            }
            Shape shape;
            if (s.kind == Shape.CAPSULE) {
                var axis = boneAxis(rig, s, partRoot);
                shape = fitCapsule(s, pts, axis, params);
            } else {
                // Trunk boxes stay on the model's axes (an upright torso is not well served by PCA's diagonal axes
                // on a roughly cubic point cloud); hands and feet follow their own principal axes.
                var aligned = s == Segment.CHEST || s == Segment.PELVIS || s == Segment.BODY;
                shape = fitBox(s, pts, aligned, params);
            }
            templates.add(shape);
            templateBones.add(rig.names[root]);
            templateBoneIndex.add(root);
            notes.append(String.format(Locale.ROOT, "  part %-15s bone %-14s %6d samples  %s%n", s, rig.names[root],
                pts.n, describe(shape)));
        }

        // ---- eye point ----
        var eyeBones = new ArrayList<Integer>();
        for (var name : List.of("LeftEye", "RightEye")) {
            if (rig.bone(name) >= 0) {
                eyeBones.add(rig.bone(name));
            }
        }
        if (eyeBones.isEmpty()) {
            for (var name : List.of("LeftEyesBase", "RightEyesBase", "LeftPupil", "RightPupil")) {
                if (rig.bone(name) >= 0) {
                    eyeBones.add(rig.bone(name));
                }
            }
        }
        var headBone = partRoot[Segment.HEAD.ordinal()];
        Vector3f eyeRest = null;
        var eyeBone = -1;
        if (!eyeBones.isEmpty()) {
            eyeRest = new Vector3f();
            for (int b : eyeBones) {
                eyeRest.add(rig.pivot[b * 3], rig.pivot[b * 3 + 1], rig.pivot[b * 3 + 2]);
            }
            eyeRest.div(eyeBones.size());
            eyeBone = eyeBones.get(0);
        } else if (headBone >= 0) {
            // Without eye bones: 30% up the head above its pivot, as the head's samples reach.
            var top = Float.NEGATIVE_INFINITY;
            for (var v = 0; v < count; v++) {
                if (core[v] && segOf[dominant[v]] == Segment.HEAD.ordinal()) {
                    top = Math.max(top, rig.pos[v * 3 + 1]);
                }
            }
            var py = rig.pivot[headBone * 3 + 1];
            eyeRest = new Vector3f(rig.pivot[headBone * 3], py + 0.3f * Math.max(top - py, 0.1f),
                rig.pivot[headBone * 3 + 2]);
            eyeBone = headBone;
        }
        notes.append(eyeRest == null ? "no eye point: eye height = 90% of box height\n"
            : String.format(Locale.ROOT, "eye point (rest) %.3f %.3f %.3f on %s%n", eyeRest.x, eyeRest.y, eyeRest.z,
                rig.names[eyeBone]));

        // ---- per pose ----
        var poses = new FitShape.PoseFit[FitPose.values().length];
        var xs = new float[coreCount];
        var ys = new float[coreCount];
        var zs = new float[coreCount];
        var ws = new float[coreCount];
        // Footprint weights: arms and hands leave the collision footprint out, as vanilla's 0.6 box leaves out the
        // vanilla arms; they stay in the height and in the hit shapes.
        var fw = new float[coreCount];
        var p = new Vector3f();
        var acc = new Vector3f();
        for (var pose : FitPose.values()) {
            var clip = clip(clips, pose);
            if (pose.base != null) {
                // Overlay state: the base state's body with the overlay clip's bones on top.
                var top = clip(clips, pose);
                clip = clip(clips, pose.base);
                if (!top.source.equals("none")) {
                    clip = clip.overlay(top);
                }
            }
            var g = Fk.pose(rig, clip);
            var local = new Matrix4f().translation(0, pose.renderOffsetY, 0);
            if (pose == FitPose.SLEEPING) {
                local.rotateZ((float)Math.toRadians(FitShape.SLEEP_ROOT[0]))
                    .rotateY((float)Math.toRadians(FitShape.SLEEP_ROOT[1]));
            }
            local.scale(rig.widthScale, rig.heightScale, rig.widthScale);
            var full = new Matrix4f[n];
            for (var b = 0; b < n; b++) {
                full[b] = new Matrix4f(local).mul(g[b]);
            }
            var k = 0;
            for (var v = 0; v < count; v++) {
                if (!core[v]) {
                    continue;
                }
                acc.zero();
                var wsum = 0f;
                for (var j = 0; j < 4; j++) {
                    var w = rig.weights[v * 4 + j];
                    if (w <= 0) {
                        continue;
                    }
                    var joint = rig.joints[v * 4 + j];
                    if (joint < 0 || joint >= n) {
                        continue;
                    }
                    full[joint].transformPosition(p.set(rig.pos[v * 3], rig.pos[v * 3 + 1], rig.pos[v * 3 + 2]));
                    acc.fma(w, p);
                    wsum += w;
                }
                if (wsum > 0) {
                    acc.div(wsum);
                }
                xs[k] = acc.x;
                ys[k] = acc.y;
                zs[k] = acc.z;
                ws[k] = rig.area[v];
                fw[k] = isArm(segOf[dominant[v]]) ? 0f : rig.area[v];
                k++;
            }
            float width;
            float height = quantile(ys, ws, k, params.top);
            if (!pose.lying) {
                var side = new float[k];
                for (var i = 0; i < k; i++) {
                    side[i] = Math.max(Math.abs(xs[i]), Math.abs(zs[i]));
                }
                width = 2 * quantile(side, fw, k, params.side);
            } else {
                // Cross-section across the body's long horizontal axis, as vanilla's 0.6 swim box.
                var lateral = lateralAxis(xs, zs, fw, k);
                var l = new float[k];
                for (var i = 0; i < k; i++) {
                    l[i] = xs[i] * lateral[0] + zs[i] * lateral[1];
                }
                var lo = quantile(l, fw, k, 1 - params.side);
                var hi = quantile(l, fw, k, params.side);
                width = 2 * Math.max(Math.abs(lo), Math.abs(hi));
            }
            float eye;
            float[] eyePoint;
            if (eyeRest != null) {
                full[eyeBone].transformPosition(p.set(eyeRest));
                eye = p.y;
                eyePoint = new float[] {p.x, p.y, p.z};
            } else {
                eye = 0.9f * height;
                eyePoint = new float[] {0, eye, 0};
            }
            var shapes = new Shape[templates.size()];
            float[] headPivot = {0, 0, 0};
            for (var t = 0; t < shapes.length; t++) {
                shapes[t] = templates.get(t).transform(full[templateBoneIndex.get(t)], 1f);
            }
            if (headBone >= 0) {
                full[headBone].transformPosition(
                    p.set(rig.pivot[headBone * 3], rig.pivot[headBone * 3 + 1], rig.pivot[headBone * 3 + 2]));
                headPivot = new float[] {p.x, p.y, p.z};
            }
            if (eyeRest == null && headBone >= 0) {
                eyePoint = new float[] {headPivot[0], eye, headPivot[2]};
            }
            poses[pose.ordinal()] = new FitShape.PoseFit(width, height, eye, shapes, headPivot, eyePoint,
                clip.name + "(" + clip.source + ")");
        }
        return new FitShape(rig.widthScale, rig.heightScale, templateBones.toArray(String[]::new),
            templates.toArray(Shape[]::new), poses, notes.toString());
    }

    /** The first of a state's clips the model (or the defaults) has, sampled where the state says. */
    private static Clip clip(Clip.Library clips, FitPose pose) {
        for (var name : pose.clips) {
            var clip = clips.get(name, pose.sampleAt);
            if (clip != null) {
                return clip;
            }
        }
        return Clip.rest(pose.clips[0]);
    }

    private static boolean isArm(int segment) {
        if (segment < 0) {
            return false;
        }
        var s = Segment.values()[segment];
        return s == Segment.LEFT_UPPER_ARM || s == Segment.LEFT_FOREARM || s == Segment.LEFT_HAND
            || s == Segment.RIGHT_UPPER_ARM || s == Segment.RIGHT_FOREARM || s == Segment.RIGHT_HAND;
    }

    /** Capsule axis for limbs: from the part's root pivot to the next part's pivot (elbow, wrist, knee, ankle). */
    private static Vector3f boneAxis(Rig rig, Segment s, int[] partRoot) {
        var next = switch (s) {
            case LEFT_UPPER_ARM -> Segment.LEFT_FOREARM;
            case LEFT_FOREARM -> Segment.LEFT_HAND;
            case RIGHT_UPPER_ARM -> Segment.RIGHT_FOREARM;
            case RIGHT_FOREARM -> Segment.RIGHT_HAND;
            case LEFT_THIGH -> Segment.LEFT_SHIN;
            case LEFT_SHIN -> Segment.LEFT_FOOT;
            case RIGHT_THIGH -> Segment.RIGHT_SHIN;
            case RIGHT_SHIN -> Segment.RIGHT_FOOT;
            default -> null;
        };
        if (next == null || partRoot[next.ordinal()] < 0) {
            return null;
        }
        var a = partRoot[s.ordinal()];
        var b = partRoot[next.ordinal()];
        var axis = new Vector3f(rig.pivot[b * 3] - rig.pivot[a * 3], rig.pivot[b * 3 + 1] - rig.pivot[a * 3 + 1],
            rig.pivot[b * 3 + 2] - rig.pivot[a * 3 + 2]);
        return axis.lengthSquared() > 1e-6f ? axis.normalize() : null;
    }

    private static Shape fitCapsule(Segment s, Samples pts, Vector3f axis, Params params) {
        var c = pts.mean();
        var u = axis != null ? axis : pts.pca(c)[0];
        var t = new float[pts.n];
        var dist = new float[pts.n];
        var q = new Vector3f();
        for (var i = 0; i < pts.n; i++) {
            q.set(pts.x[i] - c.x, pts.y[i] - c.y, pts.z[i] - c.z);
            var along = q.dot(u);
            t[i] = along;
            dist[i] = q.fma(-along, u).length();
        }
        var lo = quantile(t, pts.w, pts.n, params.trim);
        var hi = quantile(t, pts.w, pts.n, 1 - params.trim);
        var r = quantile(dist, pts.w, pts.n, params.radius);
        float ta;
        float tb;
        if (hi - lo > 2 * r) {
            ta = lo + r;
            tb = hi - r;
        } else {
            ta = tb = (lo + hi) / 2;
            r = Math.max(r, (hi - lo) / 2);
        }
        return Shape.capsule(s, new Vector3f(u).mul(ta).add(c), new Vector3f(u).mul(tb).add(c), r);
    }

    private static Shape fitBox(Segment s, Samples pts, boolean aligned, Params params) {
        var c = pts.mean();
        Vector3f[] axes = null;
        if (!aligned) {
            axes = pts.pca(c);
            // PCA axes of a roughly cubic cloud are arbitrary: only follow them when the part is clearly elongated.
            if (pts.eigen[0] < 2.5 * pts.eigen[1]) {
                axes = null;
            }
        }
        if (axes == null) {
            axes = new Vector3f[] {new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)};
        }
        var proj = new float[pts.n];
        var center = new Vector3f(c);
        var half = new float[3];
        var q = new Vector3f();
        for (var a = 0; a < 3; a++) {
            for (var i = 0; i < pts.n; i++) {
                proj[i] = q.set(pts.x[i] - c.x, pts.y[i] - c.y, pts.z[i] - c.z).dot(axes[a]);
            }
            var lo = quantile(proj, pts.w, pts.n, params.trim);
            var hi = quantile(proj, pts.w, pts.n, 1 - params.trim);
            center.fma((lo + hi) / 2, axes[a]);
            half[a] = Math.max((hi - lo) / 2, 0.01f);
        }
        return Shape.box(s, center, axes[0], axes[1], axes[2], half[0], half[1], half[2]);
    }

    /** Unit horizontal direction across the body's longest horizontal extent (x, z). */
    private static float[] lateralAxis(float[] xs, float[] zs, float[] ws, int n) {
        double sw = 0, mx = 0, mz = 0;
        for (var i = 0; i < n; i++) {
            sw += ws[i];
            mx += ws[i] * xs[i];
            mz += ws[i] * zs[i];
        }
        if (sw <= 0) {
            return new float[] {1, 0};
        }
        mx /= sw;
        mz /= sw;
        double cxx = 0, cxz = 0, czz = 0;
        for (var i = 0; i < n; i++) {
            var dx = xs[i] - mx;
            var dz = zs[i] - mz;
            cxx += ws[i] * dx * dx;
            cxz += ws[i] * dx * dz;
            czz += ws[i] * dz * dz;
        }
        // Major axis angle of the 2x2 covariance; the lateral axis is perpendicular to it.
        var theta = 0.5 * Math.atan2(2 * cxz, cxx - czz);
        return new float[] {(float)-Math.sin(theta), (float)Math.cos(theta)};
    }

    /** Area-weighted quantile of the first n values. */
    static float quantile(float[] v, float[] w, int n, double q) {
        if (n == 0) {
            return 0;
        }
        var packed = new long[n];
        double total = 0;
        for (var i = 0; i < n; i++) {
            var bits = Float.floatToIntBits(v[i]);
            bits ^= (bits >> 31) & 0x7fffffff;
            packed[i] = ((long)bits << 32) | (i & 0xffffffffL);
            total += w[i];
        }
        Arrays.sort(packed);
        var target = q * total;
        double cum = 0;
        for (var i = 0; i < n; i++) {
            var idx = (int)(packed[i] & 0xffffffffL);
            cum += w[idx];
            if (cum >= target) {
                return v[idx];
            }
        }
        return v[(int)(packed[n - 1] & 0xffffffffL)];
    }

    private static String describe(Shape s) {
        if (s.kind == Shape.CAPSULE) {
            var d = s.d;
            var len = (float)Math.sqrt((d[3] - d[0]) * (d[3] - d[0]) + (d[4] - d[1]) * (d[4] - d[1])
                + (d[5] - d[2]) * (d[5] - d[2]));
            return String.format(Locale.ROOT, "capsule r=%.3f len=%.3f a=(%.3f %.3f %.3f) b=(%.3f %.3f %.3f)", d[6],
                len, d[0], d[1], d[2], d[3], d[4], d[5]);
        }
        var d = s.d;
        return String.format(Locale.ROOT, "box half=(%.3f %.3f %.3f) c=(%.3f %.3f %.3f)", d[12], d[13], d[14], d[0],
            d[1], d[2]);
    }

    /** Weighted point set. */
    private static final class Samples {
        final float[] x;
        final float[] y;
        final float[] z;
        final float[] w;
        /** Eigenvalues of the last {@link #pca}, largest first. */
        final double[] eigen = new double[3];
        int n;

        Samples(int capacity) {
            x = new float[capacity];
            y = new float[capacity];
            z = new float[capacity];
            w = new float[capacity];
        }

        void add(float px, float py, float pz, float pw) {
            x[n] = px;
            y[n] = py;
            z[n] = pz;
            w[n] = pw;
            n++;
        }

        Vector3f mean() {
            double sx = 0, sy = 0, sz = 0, sw = 0;
            for (var i = 0; i < n; i++) {
                sx += w[i] * x[i];
                sy += w[i] * y[i];
                sz += w[i] * z[i];
                sw += w[i];
            }
            return sw > 0 ? new Vector3f((float)(sx / sw), (float)(sy / sw), (float)(sz / sw)) : new Vector3f();
        }

        /** Principal axes, largest variance first, right-handed. */
        Vector3f[] pca(Vector3f c) {
            var m = new double[3][3];
            for (var i = 0; i < n; i++) {
                double[] d = {x[i] - c.x, y[i] - c.y, z[i] - c.z};
                for (var a = 0; a < 3; a++) {
                    for (var b = 0; b < 3; b++) {
                        m[a][b] += w[i] * d[a] * d[b];
                    }
                }
            }
            var vec = new double[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
            jacobi(m, vec);
            Integer[] idx = {0, 1, 2};
            Arrays.sort(idx, (a, b) -> Double.compare(m[b][b], m[a][a]));
            var out = new Vector3f[3];
            for (var k = 0; k < 3; k++) {
                var col = idx[k];
                eigen[k] = m[col][col];
                out[k] = new Vector3f((float)vec[0][col], (float)vec[1][col], (float)vec[2][col]).normalize();
            }
            out[2] = new Vector3f(out[0]).cross(out[1]).normalize();
            return out;
        }

        /** Cyclic Jacobi eigen decomposition of a symmetric 3x3 matrix; eigenvectors are the columns of v. */
        private static void jacobi(double[][] a, double[][] v) {
            for (var sweep = 0; sweep < 32; sweep++) {
                var off = Math.abs(a[0][1]) + Math.abs(a[0][2]) + Math.abs(a[1][2]);
                if (off < 1e-15) {
                    return;
                }
                for (var p = 0; p < 2; p++) {
                    for (var q = p + 1; q < 3; q++) {
                        if (Math.abs(a[p][q]) < 1e-18) {
                            continue;
                        }
                        var theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                        var t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                        if (theta == 0) {
                            t = 1;
                        }
                        var c = 1 / Math.sqrt(t * t + 1);
                        var s = t * c;
                        for (var k = 0; k < 3; k++) {
                            var akp = a[k][p];
                            var akq = a[k][q];
                            a[k][p] = c * akp - s * akq;
                            a[k][q] = s * akp + c * akq;
                        }
                        for (var k = 0; k < 3; k++) {
                            var apk = a[p][k];
                            var aqk = a[q][k];
                            a[p][k] = c * apk - s * aqk;
                            a[q][k] = s * apk + c * aqk;
                        }
                        for (var k = 0; k < 3; k++) {
                            var vkp = v[k][p];
                            var vkq = v[k][q];
                            v[k][p] = c * vkp - s * vkq;
                            v[k][q] = s * vkp + c * vkq;
                        }
                    }
                }
            }
        }
    }
}
