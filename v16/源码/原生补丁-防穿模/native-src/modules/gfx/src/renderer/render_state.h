#pragma once

#include <cglm/mat3.h>
#include <cglm/mat4.h>
#include <cglm/vec4.h>

#include <atomic>
#include <memory>
#include <span>
#include <vector>

#include "anti_clip.h"
#include "gfx/bake/baked_model.h"
#include "gfx/renderer/model_state.h"
#include "gfx/renderer/render.h"
#include "system_allocator.h"

namespace ysm::gfx::renderer {
struct alignas(64) WorkerReadyFlag {
    std::atomic_bool value = false;
};

struct alignas(64) RenderBoneState {
    mat4 pose;
    mat3 normal;
    vec4 facing_coeff;
    vec4 depth_z;
    vec4 depth_w;
    float tangent_orientation;
    uint32_t packed_light;
    uint32_t packed_opaque_color;
    uint32_t packed_translucent_color;
    bool uniform_scale;

    RenderResult result = RenderResult::kSuccess;
};

// Skin matrix of a mesh joint for the current draw: outer model matrix times
// the bone pose. Invalid when the joint was not extracted this frame.
struct alignas(64) RenderJointState {
    mat4 pose;
    mat3 normal;
    bool valid = false;
};

class alignas(64) RenderState {
    mat4 clip_model{};
    vec4 clip_facing{};
    // Rows 2 and 3 of projection * view, for depth of skinned output.
    vec4 view_depth_z{};
    vec4 view_depth_w{};
    float outer_tangent_orientation = 0;
    bool outer_uniform_scale = false;

    std::vector<RenderBoneState, SystemAllocator<RenderBoneState>> bone_states;
    std::vector<RenderJointState, SystemAllocator<RenderJointState>> joint_states;
    std::vector<WorkerReadyFlag, SystemAllocator<WorkerReadyFlag>> worker_ready_flags;
    size_t worker_ready_size = 0;
    // Borrowed from the ModelState for the draw; empty when no morph deforms.
    std::span<const float> morph_weights;
    // Anti-clip body capsules posed for the draw; empty without push-out.
    std::vector<PosedBodyCapsule> anti_clip_capsules;
    size_t anti_clip_capsule_count = 0;

public:
    template <typename SimdTag>
    absl::Status UpdateCommon(SimdTag tag, const RenderParameters& params,
                        const ModelState& model_state);

    template <typename SimdTag>
    RenderResult Update(SimdTag tag, const RenderParameters& params,
                        const ModelState& model_state,
                        uint32_t worker_index, uint32_t worker_count);

    [[nodiscard]] const RenderBoneState& GetBoneState(uint32_t bone_index) const {
        return bone_states.at(bone_index);
    }

    [[nodiscard]] const RenderJointState& GetJointState(uint32_t bone_index) const {
        return joint_states[bone_index];
    }

    [[nodiscard]] const float* ViewDepthZ() const { return view_depth_z; }

    [[nodiscard]] const float* ViewDepthW() const { return view_depth_w; }

    [[nodiscard]] std::span<const float> MorphWeights() const {
        return morph_weights;
    }

    [[nodiscard]] std::span<const PosedBodyCapsule> AntiClipCapsules() const {
        return {anti_clip_capsules.data(), anti_clip_capsule_count};
    }

    [[nodiscard]] std::span<const WorkerReadyFlag> WorkerReadyFlags() const {
        return {worker_ready_flags.data(), worker_ready_size};
    }

    void PublishWorkerReady(uint32_t worker_index) {
        worker_ready_flags[worker_index].value.store(
            true, std::memory_order_release);
    }
};
}  // namespace ysm::gfx::renderer
