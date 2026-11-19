package com.ysmnpr.endfield;

import com.elfmcys.ysm.geckolib3.geo.animated.GeoModelState;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Bridge to the patched YSM's mesh LOD (com.elfmcys.ysm.client.lod.MeshLodRuntime, YSM jars from the LOD build on):
 * looked up once, so this mod still runs on a YSM without it.
 */
public final class YsmMeshLod {
    private static final MethodHandle SWITCH_PENDING = find();

    private YsmMeshLod() {}

    private static MethodHandle find() {
        try {
            var runtime = Class.forName("com.elfmcys.ysm.client.lod.MeshLodRuntime");
            return MethodHandles.publicLookup().findStatic(runtime, "switchPending",
                MethodType.methodType(boolean.class, GeoModelState.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** Whether the state's next extraction switches its mesh LOD level (the animation LOD then lets it through). */
    public static boolean switchPending(GeoModelState state) {
        if (SWITCH_PENDING == null) {
            return false;
        }
        try {
            return (boolean)SWITCH_PENDING.invokeExact(state);
        } catch (Throwable e) {
            return false;
        }
    }
}
