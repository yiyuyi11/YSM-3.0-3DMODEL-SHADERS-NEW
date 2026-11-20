import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Makes YSM v18 draw the off-hand item. CustomPlayerItemInHandLayer.render's off-hand branch calls
 * {@code renderArmWithItem(player, mainHandItem, THIRD_PERSON_RIGHT_HAND, HumanoidArm.RIGHT, ...)}: it draws the main-hand
 * stack a second time at the right hand, so shields, totems and every other off-hand item never show in third person.
 * This patch changes exactly those three operands to the off-hand stack (local 9), THIRD_PERSON_LEFT_HAND and
 * HumanoidArm.LEFT. Operand types stay the same, so stack maps and max stack are untouched.
 *
 * <p>
 * Usage: OffhandItemPatch &lt;in.class&gt; &lt;out.class&gt;. Fails unless the original instruction sequence is found exactly
 * once (and locals 8 and 9 are the main- and off-hand stacks).
 */
public final class OffhandItemPatch {
    private static final String OWNER = "com/elfmcys/ysm/client/renderer/layer/CustomPlayerItemInHandLayer";
    private static final String RENDER = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/"
        + "MultiBufferSource;Lcom/elfmcys/ysm/client/entity/CustomPlayerEntity;Lcom/elfmcys/ysm/geckolib3/geo/"
        + "GeoRenderData;II)V";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String CONTEXT = "net/minecraft/world/item/ItemDisplayContext";
    private static final String ARM = "net/minecraft/world/entity/HumanoidArm";
    private static final String RENDER_ARM = "(Lnet/minecraft/world/entity/LivingEntity;" + STACK + "L" + CONTEXT
        + ";L" + ARM + ";Lcom/elfmcys/ysm/geckolib3/geo/GeoRenderData;Lcom/mojang/blaze3d/vertex/PoseStack;"
        + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    public static void main(String[] args) throws Exception {
        var reader = new ClassReader(Files.readAllBytes(Path.of(args[0])));
        var node = new ClassNode();
        reader.accept(node, 0);
        if (!node.name.equals(OWNER)) {
            throw new IllegalStateException("not " + OWNER + ": " + node.name);
        }
        var render = node.methods.stream().filter(m -> m.name.equals("render") && m.desc.equals(RENDER)).toList();
        if (render.size() != 1) {
            throw new IllegalStateException("render" + RENDER + " found " + render.size() + " times");
        }
        var method = render.get(0);
        var code = instructions(method);
        // Locals 8 and 9: getMainHandItem (m_21205_) and getOffhandItem (m_21206_) results.
        requireStore(code, "m_21205_", 8);
        requireStore(code, "m_21206_", 9);
        var matches = new ArrayList<Integer>();
        for (var i = 0; i + PATTERN_LENGTH <= code.size(); i++) {
            if (matches(code, i)) {
                matches.add(i);
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("off-hand renderArmWithItem sequence found " + matches.size() + " times");
        }
        var at = matches.get(0);
        ((VarInsnNode)code.get(at + 5)).var = 9;
        ((FieldInsnNode)code.get(at + 6)).name = "THIRD_PERSON_LEFT_HAND";
        ((FieldInsnNode)code.get(at + 7)).name = "LEFT";
        var writer = new ClassWriter(reader, 0);
        node.accept(writer);
        Files.write(Path.of(args[1]), writer.toByteArray());
        System.out.println("patched off-hand item rendering in " + OWNER + ".render");
    }

    private static final int PATTERN_LENGTH = 13;

    /**
     * aload 9; invokestatic SWarfareCompat.isGun; ifne; aload_0; aload 7; aload 8; getstatic THIRD_PERSON_RIGHT_HAND;
     * getstatic HumanoidArm.RIGHT; aload 4; aload_1; aload_2; iload 5; invokevirtual renderArmWithItem.
     */
    private static boolean matches(List<AbstractInsnNode> code, int i) {
        return var(code.get(i), Opcodes.ALOAD, 9)
            && call(code.get(i + 1), Opcodes.INVOKESTATIC, "com/elfmcys/ysm/client/compat/swarfare/SWarfareCompat",
                "isGun", "(" + STACK + ")Z")
            && code.get(i + 2) instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNE
            && var(code.get(i + 3), Opcodes.ALOAD, 0) && var(code.get(i + 4), Opcodes.ALOAD, 7)
            && var(code.get(i + 5), Opcodes.ALOAD, 8)
            && field(code.get(i + 6), CONTEXT, "THIRD_PERSON_RIGHT_HAND", "L" + CONTEXT + ";")
            && field(code.get(i + 7), ARM, "RIGHT", "L" + ARM + ";")
            && var(code.get(i + 8), Opcodes.ALOAD, 4) && var(code.get(i + 9), Opcodes.ALOAD, 1)
            && var(code.get(i + 10), Opcodes.ALOAD, 2) && var(code.get(i + 11), Opcodes.ILOAD, 5)
            && call(code.get(i + 12), Opcodes.INVOKEVIRTUAL, OWNER, "renderArmWithItem", RENDER_ARM);
    }

    private static void requireStore(List<AbstractInsnNode> code, String getter, int local) {
        var found = 0;
        for (var i = 0; i + 1 < code.size(); i++) {
            if (code.get(i) instanceof MethodInsnNode call && call.name.equals(getter)
                && call.desc.equals("()" + STACK) && var(code.get(i + 1), Opcodes.ASTORE, local)) {
                found++;
            }
        }
        if (found != 1) {
            throw new IllegalStateException(getter + " stored to local " + local + " found " + found + " times");
        }
    }

    /** The method's real instructions: labels, line numbers and frames left out. */
    private static List<AbstractInsnNode> instructions(MethodNode method) {
        var result = new ArrayList<AbstractInsnNode>();
        for (var insn : method.instructions) {
            if (insn.getOpcode() >= 0) {
                result.add(insn);
            }
        }
        return result;
    }

    private static boolean var(AbstractInsnNode insn, int opcode, int local) {
        return insn instanceof VarInsnNode v && v.getOpcode() == opcode && v.var == local;
    }

    private static boolean field(AbstractInsnNode insn, String owner, String name, String desc) {
        return insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.owner.equals(owner)
            && f.name.equals(name) && f.desc.equals(desc);
    }

    private static boolean call(AbstractInsnNode insn, int opcode, String owner, String name, String desc) {
        return insn instanceof MethodInsnNode m && m.getOpcode() == opcode && m.owner.equals(owner)
            && m.name.equals(name) && m.desc.equals(desc);
    }
}
