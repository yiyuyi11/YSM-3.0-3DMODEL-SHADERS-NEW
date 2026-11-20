package com.elfmcys.ysm.format.parser;

import com.elfmcys.quickbuf.ProtoSource;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringBonesProto;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Imports a humanoid model and prints the spring chains, joints and colliders of its main and first-person arm
 * models (bone names), to check which chains reach the first-person arms. Usage: GenArmSprings &lt;model&gt;
 */
public final class GenArmSprings {
    public static void main(String[] args) throws Exception {
        com.elfmcys.ysm.natives.NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        com.elfmcys.ysm.natives.NativeRuntime.initialize(
            com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.WARN));
        var model = Path.of(args[0]);
        var data = Files.readAllBytes(model);
        var buffer = ByteBuffer.allocateDirect(data.length).put(data).flip();
        try (var humanoid = HumanoidImport.load(model.getFileName().toString(), buffer, name -> {
            var file = model.resolveSibling(name);
            return Files.exists(file) ? ByteBuffer.wrap(Files.readAllBytes(file)) : null;
        })) {
            print("main", humanoid.main().model);
            print("arm", humanoid.arm().model);
        }
    }

    private static void print(String label, com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto geo)
        throws java.io.IOException {
        if (!geo.springs().hasRemaining()) {
            System.out.println(label + ": no springs");
            return;
        }
        var springs = SpringBonesProto.parseFrom(ProtoSource.newInstance(geo.springs().duplicate()));
        System.out.println(label + ": " + springs.chains().size() + " chains, " + springs.joints().size() + " joints, "
            + springs.colliders().size() + " colliders");
        // the arm model in full; of the main model, the joints and colliders on arm bones (for comparison)
        var all = label.equals("arm");
        for (var joint : springs.joints()) {
            var name = geo.bones().get(joint.bone()).name();
            if (all || name.contains("sleeve")) {
                System.out.printf("  joint %-28s chain %d tail %s hit %.4f stiffness %.3f gravity %.3f%n", name,
                    joint.chain(), joint.tail(), joint.hitRadius(), joint.stiffness(), joint.gravityPower());
            }
        }
        if (all) {
            for (var i = 0; i < springs.chains().size(); i++) {
                System.out.println("  chain " + i + " colliders " + springs.chains().get(i).colliders());
            }
        }
        for (var collider : springs.colliders()) {
            var name = geo.bones().get(collider.bone()).name();
            if (all || name.endsWith("Arm")) {
                System.out.printf("  collider on %-24s head %s tail %s r %.4f%n", name, collider.head(),
                    collider.tail(), collider.radius());
            }
        }
    }
}
