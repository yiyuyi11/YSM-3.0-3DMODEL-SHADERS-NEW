package com.elfmcys.ysm.format.parser;

import com.elfmcys.quickbuf.ProtoSource;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringBonesProto;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Checks the spring import. Unit check: a VRM 0.x sphere collider (one offset vector passed as both ends) keeps both
 * ends on the transformed center; the v18 code transformed the shared vector in place and then again, so the second
 * end landed a whole node offset further. Model checks on the variants made by make_spring_variants.py: the VRM 0.x
 * copy imports its colliders as spheres at the 1.0 model's sphere centers and keeps its bone group's center; the
 * extended copy imports one inside sphere, one plane, a center and the three limit types.
 * Usage: GenSpringCheck &lt;model.vrm&gt; &lt;model_vrm0.vrm&gt; &lt;model_ext.vrm&gt;; exits 1 on any failure.
 */
public final class GenSpringCheck {
    private static int failures;

    public static void main(String[] args) throws Exception {
        unitCheck();
        com.elfmcys.ysm.natives.NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        com.elfmcys.ysm.natives.NativeRuntime.initialize(
            com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.WARN));
        var base = springs(Path.of(args[0]));
        var zero = springs(Path.of(args[1]));
        var ext = springs(Path.of(args[2]));
        for (var entry : List.of(List.of(args[0], base), List.of(args[1], zero), List.of(args[2], ext))) {
            var s = (SpringBonesProto)entry.get(1);
            var types = new int[3];
            s.colliders().forEach(c -> types[c.type()]++);
            var limits = new int[4];
            s.joints().forEach(j -> limits[j.limit()]++);
            var centers = s.chains().stream().filter(c -> c.center() != 0).count();
            System.out.printf("%s: %d chains (%d with a center), %d joints (limits cone/hinge/spherical %d/%d/%d), "
                + "%d colliders (outside/inside/plane %d/%d/%d), %d capsules%n", Path.of((String)entry.get(0))
                .getFileName(), s.chains().size(), centers, s.joints().size(), limits[1], limits[2], limits[3],
                s.colliders().size(), types[0], types[1], types[2], capsules(s));
        }

        // VRM 0.x: spheres only, each at a sphere center of the 1.0 model.
        check(capsules(zero) == 0, "VRM 0.x colliders are spheres (head == tail)");
        var matched = 0;
        var spheres = 0;
        for (var c : base.colliders()) {
            if (distance(c.head(), c.tail()) > 1e-6f) {
                continue;
            }
            spheres++;
            for (var z : zero.colliders()) {
                if (distance(c.head(), z.head()) < 1e-4f) {
                    matched++;
                    break;
                }
            }
        }
        check(spheres > 0 && matched == spheres,
            "VRM 0.x sphere centers match the 1.0 model's (" + matched + " of " + spheres + ")");
        check(zero.chains().stream().anyMatch(c -> c.center() != 0), "VRM 0.x bone group center kept");

        // Extensions.
        check(ext.colliders().stream().filter(c -> c.type() == 1).count() == 1, "one inside collider");
        check(ext.colliders().stream().filter(c -> c.type() == 2).count() == 1, "one plane collider");
        check(ext.chains().stream().anyMatch(c -> c.center() != 0), "spring center kept");
        for (var type = 1; type <= 3; type++) {
            final var t = type;
            check(ext.joints().stream().filter(j -> j.limit() == t).count() == 1, "one joint with limit type " + t);
        }
        check(ext.joints().stream().filter(j -> j.limit() != 0).allMatch(j -> j.limitRotation().size() == 4),
            "limits carry their rotation");
        System.out.println(failures == 0 ? "GenSpringCheck: all checks passed" : "GenSpringCheck: " + failures
            + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void unitCheck() {
        // Node 1 m up, sphere offset 0.1 m up: both ends belong at 1.1 m.
        var world = new Matrix4f().translation(0f, 1f, 0f);
        var offset = new Vector3f(0f, 0.1f, 0f);
        var collider = VrmReader.collider(world, 0, offset, offset, 0.05f, HumanoidSource.SpringCollider.OUTSIDE);
        System.out.printf("unit: fixed ends %.3f / %.3f m%n", collider.head().y, collider.tail().y);
        check(Math.abs(collider.head().y - 1.1f) < 1e-6f && Math.abs(collider.tail().y - 1.1f) < 1e-6f,
            "a VRM 0.x sphere keeps both ends on its center");
        check(offset.y == 0.1f, "the offset vector is left unchanged");
        // The v18 order of JOML calls on one shared vector, for the record.
        var shared = new Vector3f(0f, 0.1f, 0f);
        var oldHead = world.transformPosition(shared);
        var oldTail = world.transformPosition(new Vector3f(shared));
        System.out.printf("unit: v18 ends %.3f / %.3f m (capsule %.3f m long)%n", oldHead.y, oldTail.y,
            oldTail.y - oldHead.y);
    }

    private static SpringBonesProto springs(Path model) throws Exception {
        var data = Files.readAllBytes(model);
        var buffer = ByteBuffer.allocateDirect(data.length).put(data).flip();
        try (var humanoid = HumanoidImport.load(model.getFileName().toString(), buffer, name -> null)) {
            var geo = humanoid.main().model;
            return SpringBonesProto.parseFrom(ProtoSource.newInstance(geo.springs().duplicate()));
        }
    }

    private static long capsules(SpringBonesProto springs) {
        return springs.colliders().stream().filter(c -> distance(c.head(), c.tail()) > 1e-6f).count();
    }

    private static float distance(it.unimi.dsi.fastutil.floats.FloatList a, it.unimi.dsi.fastutil.floats.FloatList b) {
        return (float)Math.sqrt(Math.pow(a.getFloat(0) - b.getFloat(0), 2) + Math.pow(a.getFloat(1) - b.getFloat(1), 2)
            + Math.pow(a.getFloat(2) - b.getFloat(2), 2));
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    private GenSpringCheck() {}
}
