package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.compat.tacz.fp.TaczFpRuntime;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoBone;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.natives.render.NativeRenderer;
import com.elfmcys.ysm.natives.render.RenderContextType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

/** Derived data belongs to the accepted resource, never to its logical name or wrapper. */
final class FirstPersonModelState {
    private ModelRenderTarget target;
    private GeoModel model;
    private float[] initialAttributes;
    private int leftRoot = -1;
    private int rightRoot = -1;
    private int backgroundRoot = -1;
    private final NativeModelState[] outputs = new NativeModelState[3];
    private final boolean[] prepared = new boolean[3];
    private final long[] revisions = new long[3];
    private float armSample = Float.NaN;
    private float backgroundSample = Float.NaN;
    /** Offset of the scale in a bone's attributes (AnimatedGeoBone.SCALE_OFFSET). */
    private static final int ATTRIBUTE_SCALE = 6;
    /** An arm's attributes: the other arm hidden, the TACZ profile applied (imported humanoids, see {@link #render}). */
    private float[] armScratch;
    /** Bone name -> index in the attributes, for the first-person TACZ profiles (TaczFpRuntime). */
    private final Object2IntOpenHashMap<String> boneIndex = new Object2IntOpenHashMap<>();

    FirstPersonModelState() {
        boneIndex.defaultReturnValue(-1);
    }

    void bind(ModelRenderTarget target, GeoModel model) {
        if (this.target == target && this.model == model) {
            return;
        }
        clear();
        if (target == null || model == null) {
            return;
        }
        var bones = model.sortedBones();
        var initial = new float[bones.size() * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT];
        for (int i = 0; i < bones.size(); i++) {
            var bone = bones.get(i);
            AnimatedGeoBone.initializeAttributes(bone, initial, i);
            boneIndex.put(bone.name(), i);
            switch (bone.name()) {
                case "LeftArm" -> leftRoot = i;
                case "RightArm" -> rightRoot = i;
                case "Background" -> backgroundRoot = i;
            }
        }
        this.target = target;
        this.model = model;
        initialAttributes = initial;
    }

    boolean hasPart(FirstPersonPart part) {
        return model != null && root(part) >= 0;
    }

    private int root(FirstPersonPart part) {
        return switch (part) {
            case LEFT_ARM -> leftRoot;
            case RIGHT_ARM -> rightRoot;
            case BACKGROUND -> backgroundRoot;
        };
    }

    void prepareArms(float sample, boolean poseUpdated) {
        if (armSample != sample || poseUpdated) {
            invalidateArms();
            armSample = sample;
        }
    }

    long revision(FirstPersonPart part) {
        return revisions[part.ordinal()];
    }

    void invalidateArms() {
        revisions[0]++;
        revisions[1]++;
        prepared[FirstPersonPart.LEFT_ARM.ordinal()] = false;
        prepared[FirstPersonPart.RIGHT_ARM.ordinal()] = false;
    }

    void prepareBackground(float sample) {
        if (backgroundSample != sample) {
            revisions[FirstPersonPart.BACKGROUND.ordinal()]++;
            prepared[FirstPersonPart.BACKGROUND.ordinal()] = false;
            backgroundSample = sample;
        }
    }

    boolean render(FirstPersonPart part, float[] animatedAttributes, VertexConsumer consumer, PoseStack.Pose pose,
        int light, int overlay, int color) {
        if (!hasPart(part)) {
            return false;
        }
        var index = part.ordinal();
        var output = outputs[index];
        if (output == null) {
            outputs[index] = output = NativeModelState.create();
        }
        if (part != FirstPersonPart.BACKGROUND && model.hasMesh()) {
            // An imported humanoid's arm may carry spring chains (sleeves, cuffs): extract every draw through the
            // physics path. The arm model's two arms are roots of their own, so the full extract with the other arm
            // hidden (scale 0 skips its subtree) poses this arm exactly as the subtree extract does, and the springs
            // step against the motion NativeRenderer.render records for this arm's draws (camera-space hand pose
            // mapped to the world: the player's travel and the view's turns both swing them). A model without arm
            // springs only pays the extract.
            prepared[index] = false;
            if (!output.extract(model.bakedModel(), armAttributes(part, animatedAttributes))) {
                return false;
            }
            prepared[index] = true;
        } else if (!prepared[index] || !output.isValid()) {
            prepared[index] = false;
            var attributes = part == FirstPersonPart.BACKGROUND ? initialAttributes : animatedAttributes;
            if (!output.extractSubtree(model.bakedModel(), attributes, root(part))) {
                return false;
            }
            prepared[index] = true;
        }
        return NativeRenderer.render(consumer, pose, output, output.getTotalVertexCount(), light, overlay, color,
            RenderContextType.LEVEL);
    }

    /**
     * The animated attributes with the other arm's root scaled to 0 and the held TACZ gun's first-person profile
     * (idle and clip hand/finger rotations, after YSM's own arm animation) written over them, in a buffer reused
     * across draws.
     */
    private float[] armAttributes(FirstPersonPart part, float[] animatedAttributes) {
        var other = root(part == FirstPersonPart.LEFT_ARM ? FirstPersonPart.RIGHT_ARM : FirstPersonPart.LEFT_ARM);
        var profile = TaczFpRuntime.armPose(target);
        if (other < 0 && profile == null) {
            return animatedAttributes;
        }
        if (armScratch == null || armScratch.length != animatedAttributes.length) {
            armScratch = new float[animatedAttributes.length];
        }
        System.arraycopy(animatedAttributes, 0, armScratch, 0, animatedAttributes.length);
        if (other >= 0) {
            var scale = other * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT + ATTRIBUTE_SCALE;
            armScratch[scale] = 0f;
            armScratch[scale + 1] = 0f;
            armScratch[scale + 2] = 0f;
        }
        if (profile != null) {
            profile.apply(boneIndex, initialAttributes, armScratch);
        }
        return armScratch;
    }

    void clear() {
        target = null;
        model = null;
        initialAttributes = null;
        boneIndex.clear();
        leftRoot = rightRoot = backgroundRoot = -1;
        armSample = backgroundSample = Float.NaN;
        Throwable failure = null;
        for (int i = 0; i < outputs.length; i++) {
            revisions[i]++;
            prepared[i] = false;
            var previous = outputs[i];
            outputs[i] = null;
            if (previous != null) {
                try {
                    previous.close();
                } catch (RuntimeException | Error closeFailure) {
                    if (failure == null)
                        failure = closeFailure;
                    else
                        failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure instanceof RuntimeException runtime)
            throw runtime;
        if (failure instanceof Error error)
            throw error;
    }
}
