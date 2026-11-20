package com.elfmcys.ysm.format.parser;

import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Offline visual check of the mesh LOD levels ({@link GenLod}'s {@code <out>.lod.bin} and the atlas as PNG): renders
 * every level in the rest pose, front and side full body and a front close-up of the head, textured (alpha-tested) with
 * flat lighting, into one sheet (columns: LOD0..LODn; rows: front, side, head). Prints, per level and view, the pixels
 * whose coverage differs from LOD0 and the farthest such pixel from LOD0's silhouette edge.
 * Usage: LodRender <out.lod.bin> <atlas.png> <sheet.png>
 */
public final class LodRender {
    private static final int W = 360, H = 720, HEAD = 360;

    public static void main(String[] args) throws IOException {
        var in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(args[0])));
        var vertexCount = readInt(in);
        var positions = new float[vertexCount * 3];
        for (var i = 0; i < positions.length; i++) {
            positions[i] = Float.intBitsToFloat(readInt(in));
        }
        var uvs = new float[vertexCount * 2];
        for (var i = 0; i < uvs.length; i++) {
            uvs[i] = Float.intBitsToFloat(readInt(in));
        }
        var triangleCount = readInt(in);
        var levels = new java.util.ArrayList<int[]>();
        var main = new int[triangleCount * 3];
        for (var i = 0; i < main.length; i++) {
            main[i] = readInt(in);
        }
        in.readNBytes(triangleCount);
        levels.add(main);
        var levelCount = readInt(in);
        for (var l = 0; l < levelCount; l++) {
            var t = new int[readInt(in) * 3];
            for (var i = 0; i < t.length; i++) {
                t[i] = readInt(in);
            }
            levels.add(t);
        }
        var atlas = ImageIO.read(Path.of(args[1]).toFile());
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (var v = 0; v < vertexCount; v++) {
            minY = Math.min(minY, positions[v * 3 + 1]);
            maxY = Math.max(maxY, positions[v * 3 + 1]);
        }
        var height = maxY - minY;
        var sheet = new BufferedImage(W * levels.size(), H * 2 + HEAD, BufferedImage.TYPE_INT_RGB);
        boolean[][] reference = new boolean[3][];
        for (var l = 0; l < levels.size(); l++) {
            var tris = levels.get(l);
            var report = new StringBuilder(String.format("LOD%d %6d tris:", l, tris.length / 3));
            for (var view = 0; view < 3; view++) {
                int w = W, h = view == 2 ? HEAD : H;
                // full body: the mesh height fills the frame; head: the top 16% of it, 4.5x larger
                var scale = view == 2 ? h / (height * 0.16f) : (h - 20) / height;
                var top = view == 2 ? maxY + height * 0.01f : maxY + 10 / scale;
                var image = render(positions, uvs, tris, atlas, w, h, view == 1, scale, top);
                sheet.getGraphics().drawImage(image.image, l * W, view == 2 ? 2 * H : view * H, null);
                if (l == 0) {
                    reference[view] = image.coverage;
                } else {
                    var diff = 0;
                    var farthest = 0.0;
                    for (var y = 0; y < h; y++) {
                        for (var x = 0; x < w; x++) {
                            if (image.coverage[y * w + x] != reference[view][y * w + x]) {
                                diff++;
                                farthest = Math.max(farthest, edgeDistance(reference[view], w, h, x, y));
                            }
                        }
                    }
                    report.append(String.format("  %s %5d px differ (max %.1f px from LOD0's edge)",
                        new String[] {"front", "side", "head"}[view], diff, farthest));
                }
            }
            System.out.println(report);
        }
        ImageIO.write(sheet, "png", Path.of(args[2]).toFile());
        System.out.println("wrote " + args[2]);
    }

    private record Rendered(BufferedImage image, boolean[] coverage) {
    }

    private static Rendered render(float[] p, float[] uv, int[] tris, BufferedImage atlas, int w, int h, boolean side,
        float scale, float top) {
        var depth = new float[w * h];
        java.util.Arrays.fill(depth, -Float.MAX_VALUE);
        var color = new int[w * h];
        java.util.Arrays.fill(color, 0xEBEBEB);
        var coverage = new boolean[w * h];
        int aw = atlas.getWidth(), ah = atlas.getHeight();
        var argb = atlas.getRGB(0, 0, aw, ah, null, 0, aw);
        float lx = 0.35f, ly = 0.6f, lz = 0.72f;
        var ll = (float)Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= ll;
        ly /= ll;
        lz /= ll;
        var sx = new float[3];
        var sy = new float[3];
        var sz = new float[3];
        for (var t = 0; t < tris.length; t += 3) {
            for (var c = 0; c < 3; c++) {
                var v = tris[t + c];
                float x = p[v * 3], y = p[v * 3 + 1], z = p[v * 3 + 2];
                // YSM's mesh faces -Z: front is seen from -Z, side from +X
                var vx = side ? -z : -x;
                var vz = side ? x : -z;
                sx[c] = w / 2f + vx * scale;
                sy[c] = (top - y) * scale;
                sz[c] = vz;
            }
            var det = (sy[1] - sy[2]) * (sx[0] - sx[2]) + (sx[2] - sx[1]) * (sy[0] - sy[2]);
            if (Math.abs(det) < 1e-12f) {
                continue;
            }
            int x0 = Math.max((int)Math.floor(Math.min(sx[0], Math.min(sx[1], sx[2]))), 0);
            int x1 = Math.min((int)Math.ceil(Math.max(sx[0], Math.max(sx[1], sx[2]))), w - 1);
            int y0 = Math.max((int)Math.floor(Math.min(sy[0], Math.min(sy[1], sy[2]))), 0);
            int y1 = Math.min((int)Math.ceil(Math.max(sy[0], Math.max(sy[1], sy[2]))), h - 1);
            if (x0 > x1 || y0 > y1) {
                continue;
            }
            int a = tris[t], b = tris[t + 1], c = tris[t + 2];
            float ux = p[b * 3] - p[a * 3], uy = p[b * 3 + 1] - p[a * 3 + 1], uz = p[b * 3 + 2] - p[a * 3 + 2];
            float wx = p[c * 3] - p[a * 3], wy = p[c * 3 + 1] - p[a * 3 + 1], wz = p[c * 3 + 2] - p[a * 3 + 2];
            float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
            var nl = (float)Math.sqrt(nx * nx + ny * ny + nz * nz) + 1e-20f;
            var vnx = side ? -nz : -nx;
            var vnz = side ? nx : -nz;
            var shade = 0.45f + 0.55f * Math.abs((vnx * lx + ny * ly + vnz * lz) / nl);
            for (var y = y0; y <= y1; y++) {
                for (var x = x0; x <= x1; x++) {
                    float gx = x + 0.5f, gy = y + 0.5f;
                    var l0 = ((sy[1] - sy[2]) * (gx - sx[2]) + (sx[2] - sx[1]) * (gy - sy[2])) / det;
                    var l1 = ((sy[2] - sy[0]) * (gx - sx[2]) + (sx[0] - sx[2]) * (gy - sy[2])) / det;
                    var l2 = 1 - l0 - l1;
                    if (l0 < 0 || l1 < 0 || l2 < 0) {
                        continue;
                    }
                    var zz = l0 * sz[0] + l1 * sz[1] + l2 * sz[2];
                    var i = y * w + x;
                    if (zz <= depth[i]) {
                        continue;
                    }
                    var u = l0 * uv[a * 2] + l1 * uv[b * 2] + l2 * uv[c * 2];
                    var v = l0 * uv[a * 2 + 1] + l1 * uv[b * 2 + 1] + l2 * uv[c * 2 + 1];
                    int tx = Math.floorMod((int)(u * aw), aw), ty = Math.floorMod((int)(v * ah), ah);
                    var texel = argb[ty * aw + tx];
                    if ((texel >>> 24) < 128) {
                        continue;
                    }
                    depth[i] = zz;
                    coverage[i] = true;
                    int r = (int)(((texel >> 16) & 255) * shade), g = (int)(((texel >> 8) & 255) * shade),
                        bl = (int)((texel & 255) * shade);
                    color[i] = r << 16 | g << 8 | bl;
                }
            }
        }
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, w, h, color, 0, w);
        return new Rendered(image, coverage);
    }

    /** Distance to the nearest pixel whose coverage differs from this one's in the reference (its silhouette edge). */
    private static double edgeDistance(boolean[] reference, int w, int h, int x, int y) {
        var inside = reference[y * w + x];
        for (var r = 1; r < 64; r++) {
            for (var dy = -r; dy <= r; dy++) {
                for (var dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) {
                        continue;
                    }
                    int xx = x + dx, yy = y + dy;
                    if (xx >= 0 && yy >= 0 && xx < w && yy < h && reference[yy * w + xx] != inside) {
                        return Math.sqrt(dx * dx + dy * dy);
                    }
                }
            }
        }
        return 64;
    }

    private static int readInt(DataInputStream in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }

    private LodRender() {
    }
}
