package com.elfmcys.ysm.geckolib3.geo.render.built;

import com.elfmcys.ysm.natives.render.NativeBakedModel;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SkinnedMeshProto;
import com.elfmcys.ysm.util.Closeable;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import it.unimi.dsi.fastutil.objects.ReferenceLists;
import java.util.Objects;
import java.util.function.IntFunction;

public class GeoModel implements Closeable {
    private final String identity;
    private final ReferenceList<GeoBone> sortedBones;
    private final GeoLocatorType locatorType;
    private final ReferenceList<ReferenceArrayList<GeoBone>> locatorMap;
    private NativeBakedModel bakedModel;
    private float[] guiBoneBounds;
    /** Rest-pose bounds of skinned mesh vertices per sorted bone, by dominant joint; null without a mesh. */
    private float[] meshBoneBounds;

    public GeoModel(String identity, GeoModelProto model, GeoLocatorType locatorType,
        NativeBakedModel.ModelResult bakedModel) {
        this(identity, boneCount(model), index -> model.bones().get(index), bakedModel.sortedBoneIndices(), locatorType,
            bakedModel.bakedModel());
        if (model.mesh().hasRemaining()) {
            meshBoneBounds = meshBoneBounds(model, bakedModel.sortedBoneIndices());
        }
    }

    /** Whether the model is a skinned mesh (an imported humanoid) rather than cubes. */
    public boolean hasMesh() {
        return meshBoneBounds != null;
    }

    private static float[] meshBoneBounds(GeoModelProto model, short[] sortedBoneIndices) {
        var bytes = new byte[model.mesh().remaining()];
        model.mesh().duplicate().get(bytes);
        final SkinnedMeshProto mesh;
        try {
            mesh = SkinnedMeshProto.parseFrom(bytes);
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Invalid skinned mesh", error);
        }
        var sortedOfOriginal = new int[sortedBoneIndices.length];
        for (var sorted = 0; sorted < sortedBoneIndices.length; sorted++) {
            sortedOfOriginal[Short.toUnsignedInt(sortedBoneIndices[sorted])] = sorted;
        }
        var bounds = new float[sortedBoneIndices.length * 6];
        for (var bone = 0; bone < sortedBoneIndices.length; bone++) {
            java.util.Arrays.fill(bounds, bone * 6, bone * 6 + 3, Float.POSITIVE_INFINITY);
            java.util.Arrays.fill(bounds, bone * 6 + 3, bone * 6 + 6, Float.NEGATIVE_INFINITY);
        }
        var positions = mesh.positions();
        var joints = mesh.joints();
        var weights = mesh.weights();
        for (var vertex = 0; vertex < positions.size() / 3; vertex++) {
            var dominant = 0;
            for (var k = 1; k < 4; k++) {
                if (weights.getFloat(vertex * 4 + k) > weights.getFloat(vertex * 4 + dominant)) {
                    dominant = k;
                }
            }
            var offset = sortedOfOriginal[joints.getInt(vertex * 4 + dominant)] * 6;
            for (var axis = 0; axis < 3; axis++) {
                var value = positions.getFloat(vertex * 3 + axis);
                bounds[offset + axis] = Math.min(bounds[offset + axis], value);
                bounds[offset + 3 + axis] = Math.max(bounds[offset + 3 + axis], value);
            }
        }
        return bounds;
    }

    private GeoModel(String identity, int boneCount, IntFunction<BoneProto> boneByIndex, short[] sortedBoneIndices,
        GeoLocatorType locatorType, NativeBakedModel bakedModel) {
        this.identity = identity;
        Objects.requireNonNull(sortedBoneIndices, "sortedBoneIndices");
        this.locatorType = Objects.requireNonNull(locatorType, "locatorType");
        this.bakedModel = Objects.requireNonNull(bakedModel, "bakedModel");
        if (sortedBoneIndices.length != boneCount) {
            throw new IllegalArgumentException("Bone index count mismatch");
        }

        var locatorMap = new ReferenceArrayList<ReferenceArrayList<GeoBone>>(locatorType.size());
        for (int i = 0; i < locatorType.size(); i++) {
            locatorMap.add(new ReferenceArrayList<>(2));
        }
        var sortedBones = new ReferenceArrayList<GeoBone>(boneCount);
        for (var sortedIndex = 0; sortedIndex < boneCount; sortedIndex++) {
            var originalIndex = Short.toUnsignedInt(sortedBoneIndices[sortedIndex]);
            if (originalIndex >= boneCount) {
                throw new IllegalArgumentException("Invalid sorted bone indices");
            }
            var boneData = boneByIndex.apply(originalIndex);
            var locator = locatorType.getByBoneName(boneData.name());
            var bone = new GeoBone(boneData, locator);
            sortedBones.add(bone);
            if (locator != null) {
                locatorMap.get(locator.seq() - 1).add(bone);
            }
        }
        this.sortedBones = ReferenceLists.unmodifiable(sortedBones);
        this.locatorMap = ReferenceLists.unmodifiable(locatorMap);
        ResourceDiagnostics.track(ResourceDiagnostics.Kind.GEO, this, identity + " bones=" + boneCount);
        bakedModel.share();
    }

    public String identity() {
        return identity;
    }

    private static int boneCount(GeoModelProto model) {
        return model.bones().size();
    }

    public ReferenceList<GeoBone> sortedBones() {
        return sortedBones;
    }

    public GeoLocatorType locatorType() {
        return locatorType;
    }

    public ReferenceList<ReferenceArrayList<GeoBone>> locatorMap() {
        return locatorMap;
    }

    public NativeBakedModel bakedModel() {
        return Objects.requireNonNull(bakedModel, "Geometry is closed");
    }

    /** Borrowed immutable geometry, populated only when a GUI actually draws this model. */
    public float[] guiBoneBounds() {
        if (guiBoneBounds == null) {
            var bounds = bakedModel.getBoneBounds(sortedBones.size());
            if (meshBoneBounds != null) {
                for (var i = 0; i < bounds.length; i++) {
                    bounds[i] =
                        i % 6 < 3 ? Math.min(bounds[i], meshBoneBounds[i]) : Math.max(bounds[i], meshBoneBounds[i]);
                }
            }
            guiBoneBounds = bounds;
        }
        return guiBoneBounds;
    }

    @Override
    public void close() {
        bakedModel = null;
        guiBoneBounds = null;
        meshBoneBounds = null;
        ResourceDiagnostics.untrack(ResourceDiagnostics.Kind.GEO, this);
    }
}
