import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Hooks the geometric and spring LOD (com.elfmcys.ysm.client.lod.MeshLodRuntime) into YSM v18's classes. Every change
 * is a straight-line insertion (no new branches), so the existing stack maps stay valid; max stack is recomputed.
 * <ol>
 * <li>{@code ModelRenderTargetLoader.geoModel(source, name)}: each returned proto goes through
 * {@code MeshLodRuntime.registerGeo(proto, source, name)}.</li>
 * <li>{@code ModelRenderTargetLoader$DefaultBuildStage.bakeModel(...)}: each returned GeoModel goes through
 * {@code MeshLodRuntime.afterBake(model, this, <the 10 arguments>)}.</li>
 * <li>{@code GeoModelState}: implements {@code MeshLodRuntime$Holder} (field {@code ysm$lod} with its accessors), and
 * {@code extract}'s one {@code GeoModel.bakedModel()} call becomes {@code MeshLodRuntime.bakedModel(model, this)}.</li>
 * <li>{@code GeoReplacedEntityRenderer.renderPrepared}: the event passed to its one {@code YesSteveModel.postEvent}
 * call is first handed to {@code MeshLodRuntime.observe}.</li>
 * <li>{@code AnimationParallelTicker.scheduleAll} starts with {@code MeshLodRuntime.beginFrame()}.</li>
 * </ol>
 * Usage: LodPatch &lt;v18 jar&gt; &lt;out dir&gt;; writes the five patched classes under out dir. Fails unless every hook
 * site is found the expected number of times.
 */
public final class LodPatch {
    private static final String RUNTIME = "com/elfmcys/ysm/client/lod/MeshLodRuntime";
    private static final String HOLDER = RUNTIME + "$Holder";
    private static final String LOADER = "com/elfmcys/ysm/model/resource/client/render/ModelRenderTargetLoader";
    private static final String STAGE = LOADER + "$DefaultBuildStage";
    private static final String STATE = "com/elfmcys/ysm/geckolib3/geo/animated/GeoModelState";
    private static final String RENDERER = "com/elfmcys/ysm/geckolib3/geo/GeoReplacedEntityRenderer";
    private static final String TICKER = "com/elfmcys/ysm/client/animation/AnimationParallelTicker";
    private static final String GEO_MODEL = "com/elfmcys/ysm/geckolib3/geo/render/built/GeoModel";
    private static final String BAKED = "com/elfmcys/ysm/natives/render/NativeBakedModel";
    private static final String GEO_PROTO = "com/elfmcys/ysm/proto/mixel/asset/model/data/GeoModelProto";
    private static final String DATA_PROTO = "com/elfmcys/ysm/proto/mixel/asset/model/ModelDataProto";
    private static final String BAKE_DESC = "(Lcom/elfmcys/ysm/model/resource/client/render/BakedModelCache;"
        + "Lcom/elfmcys/ysm/model/domain/ModelRepresentation;Ljava/lang/String;[BL" + GEO_PROTO + ";"
        + "Lcom/elfmcys/ysm/model/resource/client/render/BakedModelCache$TexturePixelsSupplier;"
        + "Lcom/elfmcys/ysm/format/schema/model/views/RenderTargetView;"
        + "Lcom/elfmcys/ysm/proto/mixel/manifest/asset/PBRTextureSetProto;ZZ)L" + GEO_MODEL + ";";

    public static void main(String[] args) throws Exception {
        var out = Path.of(args[1]);
        try (var jar = new ZipFile(args[0])) {
            patch(jar, out, LOADER, LodPatch::loader);
            patch(jar, out, STAGE, LodPatch::stage);
            patch(jar, out, STATE, LodPatch::state);
            patch(jar, out, RENDERER, LodPatch::renderer);
            patch(jar, out, TICKER, LodPatch::ticker);
        }
    }

    private interface Patch {
        void apply(ClassNode node);
    }

    private static void patch(ZipFile jar, Path out, String name, Patch patch) throws Exception {
        var entry = jar.getEntry(name + ".class");
        if (entry == null) {
            throw new IllegalStateException(name + " not in the jar");
        }
        byte[] bytes;
        try (var in = jar.getInputStream(entry)) {
            bytes = in.readAllBytes();
        }
        var reader = new ClassReader(bytes);
        var node = new ClassNode();
        reader.accept(node, 0);
        patch.apply(node);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        var target = out.resolve(name + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, writer.toByteArray());
        System.out.println("patched " + name);
    }

    private static void loader(ClassNode node) {
        var method = method(node, "geoModel", "(L" + DATA_PROTO + ";Ljava/lang/String;)L" + GEO_PROTO + ";");
        var returns = opcodes(method, Opcodes.ARETURN);
        require(returns.size() >= 1, "geoModel returns: " + returns.size());
        for (var ret : returns) {
            var hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "registerGeo",
                "(L" + GEO_PROTO + ";L" + DATA_PROTO + ";Ljava/lang/String;)L" + GEO_PROTO + ";"));
            method.instructions.insertBefore(ret, hook);
        }
    }

    private static void stage(ClassNode node) {
        var method = method(node, "bakeModel", BAKE_DESC);
        var returns = opcodes(method, Opcodes.ARETURN);
        require(returns.size() >= 1, "bakeModel returns: " + returns.size());
        var args = Type.getArgumentTypes(BAKE_DESC);
        var desc = new StringBuilder("(L" + GEO_MODEL + ";Ljava/lang/Object;");
        for (var arg : args) {
            desc.append(switch (arg.getSort()) {
                case Type.BOOLEAN -> "Z";
                case Type.OBJECT -> arg.getInternalName().equals("java/lang/String") ? "Ljava/lang/String;"
                    : arg.getInternalName().equals(GEO_PROTO) ? "L" + GEO_PROTO + ";" : "Ljava/lang/Object;";
                case Type.ARRAY -> "[B";
                default -> throw new IllegalStateException("argument " + arg);
            });
        }
        desc.append(")L").append(GEO_MODEL).append(';');
        for (var ret : returns) {
            var hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            var local = 1;
            for (var arg : args) {
                hook.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), local));
                local += arg.getSize();
            }
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "afterBake", desc.toString()));
            method.instructions.insertBefore(ret, hook);
        }
    }

    private static void state(ClassNode node) {
        var method = method(node, "extract", "(Lcom/elfmcys/ysm/geckolib3/model/AnimatedGeoModel;)Z");
        var calls = new ArrayList<MethodInsnNode>();
        for (var insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && call.owner.equals(GEO_MODEL) && call.name.equals("bakedModel")
                && call.desc.equals("()L" + BAKED + ";")) {
                calls.add(call);
            }
        }
        require(calls.size() == 1, "GeoModel.bakedModel() in extract: " + calls.size());
        var call = calls.get(0);
        method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "bakedModel",
            "(L" + GEO_MODEL + ";L" + STATE + ";)L" + BAKED + ";"));

        node.interfaces.add(HOLDER);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE, "ysm$lod", "Ljava/lang/Object;",
            null, null));
        var getter = new MethodNode(Opcodes.ACC_PUBLIC, "ysm$lod", "()Ljava/lang/Object;", null, null);
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, STATE, "ysm$lod", "Ljava/lang/Object;"));
        getter.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(getter);
        var setter = new MethodNode(Opcodes.ACC_PUBLIC, "ysm$setLod", "(Ljava/lang/Object;)V", null, null);
        setter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        setter.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, STATE, "ysm$lod", "Ljava/lang/Object;"));
        setter.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(setter);
    }

    private static void renderer(ClassNode node) {
        var method = method(node, "renderPrepared", "(Lcom/elfmcys/ysm/client/entity/CustomHumanoidEntity;"
            + "Lcom/elfmcys/ysm/geckolib3/geo/GeoRenderData;Lnet/minecraft/resources/ResourceLocation;FF"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V");
        var calls = new ArrayList<MethodInsnNode>();
        for (var insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                && call.owner.equals("com/elfmcys/ysm/YesSteveModel") && call.name.equals("postEvent")) {
                calls.add(call);
            }
        }
        require(calls.size() == 1, "postEvent in renderPrepared: " + calls.size());
        var previous = real(calls.get(0).getPrevious());
        require(previous instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
            && stores(method, load.var, "com/elfmcys/ysm/api/rendering/event/RenderModelEvent"),
            "postEvent argument is not the RenderModelEvent local");
        var hook = new InsnList();
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "observe", "(Ljava/lang/Object;)V"));
        method.instructions.insertBefore(calls.get(0), hook);
    }

    private static void ticker(ClassNode node) {
        var method = method(node, "scheduleAll", "(F)V");
        method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "beginFrame", "()V"));
    }

    /** Whether the local is stored right after a {@code new type; dup; ...; invokespecial <init>}. */
    private static boolean stores(MethodNode method, int local, String type) {
        var created = false;
        for (var insn : method.instructions) {
            if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.equals(type)) {
                created = true;
            }
            if (created && insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.ASTORE && v.var == local) {
                return true;
            }
        }
        return false;
    }

    private static AbstractInsnNode real(AbstractInsnNode insn) {
        while (insn != null && insn.getOpcode() < 0) {
            insn = insn.getPrevious();
        }
        return insn;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        var found = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).toList();
        require(found.size() == 1, node.name + "." + name + desc + " found " + found.size() + " times");
        return found.get(0);
    }

    private static List<AbstractInsnNode> opcodes(MethodNode method, int opcode) {
        var result = new ArrayList<AbstractInsnNode>();
        for (var insn : method.instructions) {
            if (insn.getOpcode() == opcode) {
                result.add(insn);
            }
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
