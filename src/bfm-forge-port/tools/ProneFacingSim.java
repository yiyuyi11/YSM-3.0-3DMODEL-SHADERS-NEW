import cn.bfm.ProneGeometry;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/**
 * Headless check of the prone heading and the prone envelope, against the mod's own compiled code
 * ({@code ProneGeometry.turn}, {@code ProneGeometry.envelope}); no game launch.
 *
 * <p>Facing. A YSM model lying prone is drawn at the mod's prone heading (YSM's BfmCompat.proneYaw), and YSM
 * turns its head and upper body by the head yaw measured from the VANILLA body yaw. Crawling moves along the view.
 * The scenario: lie down looking at 0 degrees, crawl forward, steer 90 degrees to the right at 3 degrees a tick,
 * keep crawling. 1.7.23: the heading keeps a 35 degree free-look cone while crawling, so the drawn body ends up 35
 * degrees off its travel (it slides sideways), and vanilla's body yaw (which at prone speed only follows the view
 * once 50 degrees behind) sets the head's turn. 1.7.24: crawling has no free-look cone and the vanilla body yaw is
 * the heading, so body, head and travel line up.
 *
 * <p>Envelope. Equal reaches ahead and behind reproduce the original centered envelope bit for bit; a body that
 * reaches farther behind than ahead grows the box on the right sides.
 *
 * <p>Run: build.sh, then with {@code CP=build/sim:build/classes:$MCDEV/client-moj.jar:<every jar in $MCDEV/libs>}
 * (Minecraft's math classes need Guava and friends): {@code javac -cp $CP -d build/sim tools/ProneFacingSim.java &&
 * java -cp $CP ProneFacingSim}
 */
public final class ProneFacingSim {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        facing();
        envelope();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    /** Vanilla 1.20.1 LivingEntity#tickHeadTurn for a player too slow for the walk branch (prone ~0.04 block/tick). */
    private static float vanillaBody(float body, float view) {
        body += Mth.wrapDegrees(body - body) * 0.3F; // target = current body below the 0.05 block/tick walk speed
        float g = Mth.wrapDegrees(view - body);
        if (Math.abs(g) > 50.0F) {
            body += g - Mth.sign(g) * 50;
        }
        return body;
    }

    private static void facing() {
        float view = 0;
        float oldHeading = 0;
        float newHeading = 0;
        float vanilla = 0;
        float worstOld = 0;
        float worstNew = 0;
        float headOld = 0;
        float headNew = 0;
        System.out.println("tick view | 1.7.23 heading body-travel head-view | 1.7.24 heading body-travel head-view");
        for (var tick = 1; tick <= 70; tick++) {
            if (tick <= 30) {
                view += 3;
            }
            oldHeading = oldTurn(oldHeading, view);
            newHeading = ProneGeometry.turn(newHeading, view, true);
            vanilla = vanillaBody(vanilla, view);
            // Travel = view (forward key). Head drawn = root + YSM's head yaw: 1.7.23 from the vanilla body yaw,
            // 1.7.24 from the heading (the vanilla body yaw is set to it).
            float oldSlant = Mth.wrapDegrees(oldHeading - view);
            float oldHead = Mth.wrapDegrees(oldHeading + Mth.clamp(Mth.wrapDegrees(view - vanilla), -85, 85) - view);
            float newSlant = Mth.wrapDegrees(newHeading - view);
            float newHead = Mth.wrapDegrees(newHeading + Mth.clamp(Mth.wrapDegrees(view - newHeading), -85, 85) - view);
            if (tick % 5 == 0) {
                System.out.printf("%4d %5.1f | %7.1f %10.1f %9.1f | %7.1f %10.1f %9.1f%n", tick, view, oldHeading,
                    oldSlant, oldHead, newHeading, newSlant, newHead);
            }
            if (tick > 50) {
                worstOld = Math.max(worstOld, Math.abs(oldSlant));
                worstNew = Math.max(worstNew, Math.abs(newSlant));
                headOld = Math.max(headOld, Math.abs(oldHead));
                headNew = Math.max(headNew, Math.abs(newHead));
            }
        }
        System.out.printf("after the turn (ticks 51-70): body vs travel 1.7.23 %.1f deg, 1.7.24 %.1f deg; "
            + "head vs view 1.7.23 %.1f deg, 1.7.24 %.1f deg%n", worstOld, worstNew, headOld, headNew);
        check("1.7.23 reproduces the report: crawling body stays >= 30 deg off its travel", worstOld >= 30);
        check("1.7.24: crawling body within 1 deg of its travel 20 ticks after the turn", worstNew <= 1);
        check("1.7.24: head within 1 deg of the view", headNew <= 1);
        // Lying still keeps the free-look cone.
        var still = 0f;
        for (var i = 0; i < 40; i++) {
            still = ProneGeometry.turn(still, 30, false);
        }
        check("lying still: a 30 deg look stays free look (body does not turn)", still == 0f);
        for (var i = 0; i < 40; i++) {
            still = ProneGeometry.turn(still, 80, false);
        }
        check("lying still: an 80 deg look turns the body to the cone's edge (45 deg)", Math.abs(still - 45) < 0.5);
        var crawl = 0f;
        var steps = 0;
        while (Math.abs(Mth.wrapDegrees(30 - crawl)) > 1 && steps < 100) {
            crawl = ProneGeometry.turn(crawl, 30, true);
            steps++;
        }
        System.out.printf("crawling: a 30 deg offset closes to 1 deg in %d ticks%n", steps);
        check("crawling: a 30 deg offset closes within 12 ticks, never more than 9 deg a tick", steps <= 12
            && ProneGeometry.turn(0, 30, true) <= 9);
    }

    /** 1.7.23's heading law: free-look cone always. */
    private static float oldTurn(float previous, float view) {
        float difference = Mth.wrapDegrees(view - previous);
        float excess = Math.max(0.0F, Math.abs(difference) - 35.0F);
        return previous + Math.copySign(Math.min(9.0F, excess * .35F), difference);
    }

    /** 1.7.23's envelope, verbatim. */
    private static AABB oldEnvelope(AABB base, float yaw, float amount) {
        var forward = ProneGeometry.forward(yaw);
        double halfX = Math.min(.72, .72 * Math.abs(forward.x) + .29 * Math.abs(forward.z));
        double halfZ = Math.min(.72, .72 * Math.abs(forward.z) + .29 * Math.abs(forward.x));
        double growX = Math.max(0, halfX - base.getXsize() * .5) * amount;
        double growZ = Math.max(0, halfZ - base.getZsize() * .5) * amount;
        return base.inflate(growX, 0, growZ);
    }

    private static void envelope() {
        var same = true;
        for (var width : new double[] {0.42, 0.6}) {
            var base = new AABB(10.3 - width / 2, 64, -5.7 - width / 2, 10.3 + width / 2, 64.6, -5.7 + width / 2);
            for (var yaw = -180; yaw < 180; yaw += 1) {
                for (var amount : new float[] {0.25f, 1f}) {
                    same &= oldEnvelope(base, yaw, amount).equals(ProneGeometry.envelope(base, yaw, amount));
                }
            }
        }
        check("Steve's reach: the envelope equals 1.7.23's for 360 headings x 2 amounts x 2 widths", same);
        var base = new AABB(-0.21, 0, -0.21, 0.21, 0.43, 0.21);
        // yaw 0 faces +Z (south): 0.85 ahead = +Z, 1.12 behind = -Z, 0.21 across.
        var south = ProneGeometry.envelope(base, 0, 1, 0.85, 1.12, 0.21);
        check("facing south: maxZ = +0.85 (head), minZ = -1.12 (feet), x stays the base",
            near(south.maxZ, 0.85) && near(south.minZ, -1.12) && near(south.minX, -0.21) && near(south.maxX, 0.21));
        // yaw 90 faces -X (west).
        var west = ProneGeometry.envelope(base, 90, 1, 0.85, 1.12, 0.21);
        check("facing west: minX = -0.85, maxX = +1.12", near(west.minX, -0.85) && near(west.maxX, 1.12)
            && near(west.minZ, -0.21) && near(west.maxZ, 0.21));
        var half = ProneGeometry.envelope(base, 0, 0.5f, 0.85, 1.12, 0.21);
        check("half way down: half the growth", near(half.maxZ, 0.21 + (0.85 - 0.21) / 2)
            && near(half.minZ, -0.21 - (1.12 - 0.21) / 2));
        var diagonal = ProneGeometry.envelope(base, 45, 1, 0.85, 1.12, 0.21);
        var s = Math.sqrt(0.5);
        check("diagonal: the body rectangle's corners, capped at the longer reach",
            near(diagonal.minX, -Math.min(1.12, 0.85 * s + 0.21 * s)) && near(diagonal.maxX, Math.min(1.12, 1.12 * s
                + 0.21 * s)) && near(diagonal.maxZ, Math.min(1.12, 0.85 * s + 0.21 * s)));
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }
}
