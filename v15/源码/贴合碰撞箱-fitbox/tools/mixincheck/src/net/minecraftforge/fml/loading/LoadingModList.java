package net.minecraftforge.fml.loading;

import java.util.Set;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;

/**
 * Offline stand-in for FML's mod list, read by the mixin config plugins (fitbox's and Battlefield Movement's) to
 * decide which optional mixins apply: a mod counts as loaded when its id is in {@code -Dmixincheck.mods}.
 */
public final class LoadingModList {
    private static final LoadingModList INSTANCE = new LoadingModList();
    private static final Set<String> MODS = Set.of(System.getProperty("mixincheck.mods", "").split(","));
    private static ModFileInfo present;

    public static LoadingModList get() {
        return INSTANCE;
    }

    public ModFileInfo getModFileById(String id) {
        if (!MODS.contains(id)) {
            return null;
        }
        if (present == null) {
            try {
                var f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                present = (ModFileInfo)((sun.misc.Unsafe)f.get(null)).allocateInstance(ModFileInfo.class);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return present;
    }
}
