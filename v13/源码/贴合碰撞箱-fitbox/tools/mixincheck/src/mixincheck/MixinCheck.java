package mixincheck;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.IPropertyKey;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.service.MixinServiceAbstract;
import org.spongepowered.asm.util.IConsumer;

/**
 * Applies real mixin configs offline with SpongePowered Mixin 0.8.5 (the version Forge 1.20.1 runs) to the production
 * (SRG-named) classes, exactly as the game would, and reports for each requested target method which mixin handlers
 * ended up in it, in execution order, with the method's return points. A handler that is missing from its target was
 * silently skipped (require = 0) or failed.
 *
 * <p>usage: MixinCheck &lt;jar:jar:...&gt; &lt;config,config&gt; &lt;class#method,...&gt; ; -Dmixincheck.mods lists the mod
 * ids the config plugins see as loaded. Classes come from the jars, first match wins (put Forge's patched client jar
 * before the SRG game jar).
 */
public final class MixinCheck {
    static final List<JarFile> JARS = new ArrayList<>();
    static final Map<String, Object> PROPS = new HashMap<>();
    static IConsumer<MixinEnvironment.Phase> phases;
    static IMixinTransformerFactory factory;
    static final Set<String> APPLIED = new LinkedHashSet<>();

    public static void main(String[] args) throws Exception {
        for (var p : args[0].split(":")) {
            JARS.add(new JarFile(p));
        }
        MixinBootstrap.init();
        for (var c : args[1].split(",")) {
            Mixins.addConfiguration(c);
        }
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        MixinEnvironment.getDefaultEnvironment().setObfuscationContext("searge");
        phases.accept(MixinEnvironment.Phase.DEFAULT);
        IMixinTransformer transformer = factory.createTransformer();
        var env = MixinEnvironment.getCurrentEnvironment();
        var failed = 0;
        Map<String, ClassNode> done = new HashMap<>();
        for (var spec : args[2].split(",")) {
            var cls = spec.substring(0, spec.indexOf('#'));
            var method = spec.substring(spec.indexOf('#') + 1);
            var node = done.get(cls);
            if (node == null) {
                node = read(cls);
                transformer.transformClass(env, cls.replace('/', '.'), node);
                done.put(cls, node);
            }
            var found = false;
            for (var m : node.methods) {
                if (!(m.name + m.desc).startsWith(method)) {
                    continue;
                }
                found = true;
                System.out.println("== " + cls + "." + m.name + m.desc);
                describe(node, m);
            }
            if (!found) {
                System.out.println("== " + cls + "." + method + ": NO SUCH METHOD");
                failed++;
            }
        }
        System.out.println("mixins applied: " + APPLIED);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Handler calls (methods the mixins merged, named handler$...$name or name$suffix) and return points, in order. */
    private static void describe(ClassNode owner, MethodNode m) {
        var i = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext(), i++) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner.name) && isMerged(owner, call.name)) {
                System.out.printf("  %4d call %s%n", i, call.name);
            } else if (insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN) {
                System.out.printf("  %4d return%n", i);
            }
        }
    }

    private static boolean isMerged(ClassNode owner, String name) {
        for (var m : owner.methods) {
            if (m.name.equals(name) && m.visibleAnnotations != null) {
                for (var a : m.visibleAnnotations) {
                    if (a.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static byte[] bytes(String internalName) throws IOException {
        var entry = internalName + ".class";
        for (var jar : JARS) {
            var e = jar.getJarEntry(entry);
            if (e != null) {
                try (var in = jar.getInputStream(e)) {
                    return in.readAllBytes();
                }
            }
        }
        // JDK classes (hierarchy and type metadata)
        try (var in = ClassLoader.getSystemResourceAsStream(entry)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    static ClassNode read(String internalName) throws ClassNotFoundException, IOException {
        var b = bytes(internalName.replace('.', '/'));
        if (b == null) {
            throw new ClassNotFoundException(internalName);
        }
        var node = new ClassNode();
        new ClassReader(b).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    /** The mixin service: classes and resources from the jars, plugins loaded by this JVM's class path. */
    public static final class Service extends MixinServiceAbstract implements IClassProvider, IClassBytecodeProvider,
        IClassTracker, IMixinAuditTrail {
        @Override
        public String getName() {
            return "MixinCheck";
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void offer(IMixinInternal internal) {
            if (internal instanceof IMixinTransformerFactory f) {
                factory = f;
            }
            super.offer(internal);
        }

        @Override
        public void wire(MixinEnvironment.Phase phase, IConsumer<MixinEnvironment.Phase> consumer) {
            super.wire(phase, consumer);
            phases = consumer;
        }

        @Override
        public IClassProvider getClassProvider() {
            return this;
        }

        @Override
        public IClassBytecodeProvider getBytecodeProvider() {
            return this;
        }

        @Override
        public ITransformerProvider getTransformerProvider() {
            return null;
        }

        @Override
        public IClassTracker getClassTracker() {
            return this;
        }

        @Override
        public IMixinAuditTrail getAuditTrail() {
            return this;
        }

        @Override
        public Collection<String> getPlatformAgents() {
            return List.of();
        }

        @Override
        public IContainerHandle getPrimaryContainer() {
            return new ContainerHandleVirtual("mixincheck");
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            for (var jar : JARS) {
                var e = jar.getJarEntry(name);
                if (e != null) {
                    try (var in = jar.getInputStream(e)) {
                        return new ByteArrayInputStream(in.readAllBytes());
                    } catch (IOException ex) {
                        return null;
                    }
                }
            }
            return MixinCheck.class.getClassLoader().getResourceAsStream(name);
        }

        @Override
        public URL[] getClassPath() {
            return new URL[0];
        }

        @Override
        public Class<?> findClass(String name) throws ClassNotFoundException {
            return Class.forName(name);
        }

        @Override
        public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
            return Class.forName(name, initialize, MixinCheck.class.getClassLoader());
        }

        @Override
        public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
            return findClass(name, initialize);
        }

        @Override
        public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
            return read(name);
        }

        @Override
        public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
            return read(name);
        }

        @Override
        public void registerInvalidClass(String className) {
        }

        @Override
        public boolean isClassLoaded(String className) {
            return false;
        }

        @Override
        public String getClassRestrictions(String className) {
            return "";
        }

        @Override
        public void onApply(String className, String mixinName) {
            APPLIED.add(mixinName.substring(mixinName.lastIndexOf('.') + 1) + "->"
                + className.substring(className.lastIndexOf('.') + 1));
        }

        @Override
        public void onPostProcess(String className) {
        }

        @Override
        public void onGenerate(String className, String generatorName) {
        }
    }

    /** Global properties: a plain map. */
    public static final class Properties implements IGlobalPropertyService {
        record Key(String name) implements IPropertyKey {}

        @Override
        public IPropertyKey resolveKey(String name) {
            return new Key(name);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getProperty(IPropertyKey key) {
            return (T)PROPS.get(((Key)key).name());
        }

        @Override
        public void setProperty(IPropertyKey key, Object value) {
            PROPS.put(((Key)key).name(), value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getProperty(IPropertyKey key, T defaultValue) {
            return (T)PROPS.getOrDefault(((Key)key).name(), defaultValue);
        }

        @Override
        public String getPropertyString(IPropertyKey key, String defaultValue) {
            var v = PROPS.get(((Key)key).name());
            return v == null ? defaultValue : v.toString();
        }
    }

    static {
        MixinService.class.getName();
    }
}
