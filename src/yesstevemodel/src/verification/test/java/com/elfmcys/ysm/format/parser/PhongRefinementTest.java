package com.elfmcys.ysm.format.parser;

import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhongRefinementTest {
    private static final int SIDES = 12;
    private static final float RADIUS = 0.06f;

    /**
     * A twelve-sided leg with smooth normals and a UV seam (duplicated vertices on one side) becomes rounder: every new
     * point lies nearer the true cylinder than the flat midpoint it replaces. A flat fin with its own normals hangs on
     * the seam edge; it is not curved, so it splits only along that shared edge, into two triangles. Nothing cracks: by
     * position, rim edges belong to one triangle, the seam edge's halves to three (both cylinder sides and the fin),
     * and every other edge to two.
     */
    @Test
    void coarseLimbBecomesRoundWithoutCracks() {
        var source = new HumanoidSource();
        // Rings at y = 0 and y = 0.4, each with SIDES + 1 vertices: the last repeats the first with another U.
        for (var ring = 0; ring < 2; ring++) {
            for (var i = 0; i <= SIDES; i++) {
                var angle = 2 * Math.PI * i / SIDES;
                vertex(source, (float)(RADIUS * Math.cos(angle)), ring * 0.4f, (float)(RADIUS * Math.sin(angle)),
                    (float)Math.cos(angle), 0, (float)Math.sin(angle), (float)i / SIDES, ring);
            }
        }
        // A tall cylinder of the model's full height so the 0.6% bulge threshold is meaningful.
        vertex(source, 0, 1.7f, 0, 0, 1, 0, 0, 0);
        for (var i = 0; i < SIDES; i++) {
            var a = i;
            var b = i + 1;
            var c = SIDES + 1 + i;
            var d = SIDES + 2 + i;
            triangle(source, a, c, b);
            triangle(source, b, c, d);
        }
        var fin = source.vertexCount();
        vertex(source, RADIUS, 0, 0, 0, 0, 1, 0, 0);
        vertex(source, RADIUS, 0.4f, 0, 0, 0, 1, 0, 1);
        vertex(source, RADIUS + 0.1f, 0, 0, 0, 0, 1, 1, 0);
        vertex(source, RADIUS + 0.1f, 0.4f, 0, 0, 0, 1, 1, 1);
        triangle(source, fin, fin + 2, fin + 1);
        triangle(source, fin + 2, fin + 3, fin + 1);
        var original = source.vertexCount();
        source.expression("aa").add(0, 0, 0.01f, 0);

        PhongRefinement.refine(source);

        assertTrue(source.vertexCount() > original, "nothing was split");
        var circle = 0;
        for (var v = original; v < source.vertexCount(); v++) {
            var x = source.positions.getFloat(v * 3);
            var z = source.positions.getFloat(v * 3 + 2);
            var radius = Math.sqrt(x * x + z * z);
            // A flat chord's midpoint sits at r cos(15 deg); the Phong point must be closer to r.
            if (radius > 1e-4) {
                assertTrue(Math.abs(radius - RADIUS) < RADIUS * (1 - Math.cos(Math.PI / SIDES)) * 0.5,
                    "new point " + v + " at radius " + radius);
                circle++;
            }
        }
        assertTrue(circle >= SIDES, "rings were not split: " + circle);

        // Closed: by welded position, rims and the fin's free edge belong to one triangle, the seam edge to three.
        var edges = new HashMap<String, Integer>();
        for (var t = 0; t < source.triangles.size(); t += 3) {
            for (var k = 0; k < 3; k++) {
                var from = key(source, source.triangles.getInt(t + k));
                var to = key(source, source.triangles.getInt(t + (k + 1) % 3));
                edges.merge(from.compareTo(to) < 0 ? from + "|" + to : to + "|" + from, 1, Integer::sum);
            }
        }
        var seamHalves = 0;
        for (var entry : edges.entrySet()) {
            var ends = entry.getKey().split("\\|");
            var rim = ends[0].contains(" y0 ") && ends[1].contains(" y0 ")
                || ends[0].contains(" y4000 ") && ends[1].contains(" y4000 ");
            var free = ends[0].startsWith("x1600 ") && ends[1].startsWith("x1600 ");
            var seam = !rim && ends[0].startsWith("x600 ") && ends[0].endsWith(" z0") && ends[1].startsWith("x600 ")
                && ends[1].endsWith(" z0");
            if (seam) {
                seamHalves++;
            }
            assertEquals(rim || free ? 1 : seam ? 3 : 2, entry.getValue(), entry.getKey());
        }
        assertEquals(2, seamHalves);

        // The expression on vertex 0 moves the new points on its edges by half as much.
        var aa = source.expression("aa");
        var halves = 0;
        for (var i = 0; i < aa.vertices.size(); i++) {
            if (aa.vertices.getInt(i) >= original) {
                assertEquals(0.005f, aa.deltas.getFloat(i * 3 + 1), 1e-6f);
                halves++;
            }
        }
        assertTrue(halves > 0);
    }

    /** Flat and dense parts stay untouched. */
    @Test
    void flatSurfaceStaysAsItIs() {
        var source = new HumanoidSource();
        vertex(source, 0, 0, 0, 0, 0, 1, 0, 0);
        vertex(source, 1, 0, 0, 0, 0, 1, 1, 0);
        vertex(source, 0, 1.7f, 0, 0, 0, 1, 0, 1);
        vertex(source, 1, 1.7f, 0, 0, 0, 1, 1, 1);
        triangle(source, 0, 1, 2);
        triangle(source, 1, 3, 2);

        PhongRefinement.refine(source);

        assertEquals(4, source.vertexCount());
        assertEquals(6, source.triangles.size());
    }

    /**
     * Two copies of a point across a UV seam, their normals 20 degrees apart, end with one normal, so lighting does not
     * draw the seam; a third copy 90 degrees away is the other side of a hard edge and keeps its own.
     */
    @Test
    void seamCopiesShareOneNormalAndHardEdgesKeepTheirs() {
        var source = new HumanoidSource();
        var tilt = (float)Math.toRadians(10);
        vertex(source, 0, 0, 0, (float)Math.sin(tilt), 0, (float)Math.cos(tilt), 0, 0);
        vertex(source, 0, 0, 0, -(float)Math.sin(tilt), 0, (float)Math.cos(tilt), 1, 0);
        vertex(source, 0, 0, 0, 0, 1, 0, 0, 1);
        vertex(source, 0.01f, 0, 0, 0, 0, 1, 0, 0);
        vertex(source, 0, 1.7f, 0, 0, 0, 1, 0, 0);
        triangle(source, 0, 3, 4);
        triangle(source, 1, 4, 3);
        triangle(source, 2, 3, 4);

        PhongRefinement.refine(source);

        for (var copy = 0; copy < 2; copy++) {
            assertEquals(0f, source.normals.getFloat(copy * 3), 1e-5f, "copy " + copy);
            assertEquals(1f, source.normals.getFloat(copy * 3 + 2), 1e-5f, "copy " + copy);
        }
        assertEquals(1f, source.normals.getFloat(2 * 3 + 1), 1e-5f);
        assertEquals(0f, source.normals.getFloat(2 * 3 + 2), 1e-5f);
    }

    /** A position to 0.1 mm, as integers so -0 and 0 agree. */
    private static String key(HumanoidSource source, int v) {
        return "x" + Math.round(source.positions.getFloat(v * 3) * 1e4f) + " y"
            + Math.round(source.positions.getFloat(v * 3 + 1) * 1e4f) + " z"
            + Math.round(source.positions.getFloat(v * 3 + 2) * 1e4f);
    }

    private static void vertex(HumanoidSource source, float x, float y, float z, float nx, float ny, float nz, float u,
        float v) {
        source.positions.add(x);
        source.positions.add(y);
        source.positions.add(z);
        source.normals.add(nx);
        source.normals.add(ny);
        source.normals.add(nz);
        source.uvs.add(u);
        source.uvs.add(v);
        for (var k = 0; k < 4; k++) {
            source.joints.add(k == 0 ? 0 : HumanoidSource.ROOT_JOINT);
            source.weights.add(k == 0 ? 1f : 0f);
        }
    }

    private static void triangle(HumanoidSource source, int a, int b, int c) {
        source.triangles.add(a);
        source.triangles.add(b);
        source.triangles.add(c);
        source.triangleMaterials.add(0);
    }
}
