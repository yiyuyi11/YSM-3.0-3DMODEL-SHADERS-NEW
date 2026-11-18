#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <type_traits>
#include <vector>

#include <absl/status/statusor.h>

#include "gfx/math/pose_stack.h"
#include "gfx/bake/baked_model.h"
#include "bone_attribute.h"
#include "color.h"
#include "schedule.h"

namespace ysm::gfx::renderer {
struct alignas(64) BonePose {
    mat4 pose = GLM_MAT4_IDENTITY_INIT;
    mat3 normal = GLM_MAT3_IDENTITY_INIT;

    bool uniform_scale = true;
    float tangent_orientation = 1.0f;
    float normal_scale = 1.0f;

    Color color{.packed = 0xFFFFFFFF};
    uint8_t glowing = 0xFFu;
};

static_assert(std::is_standard_layout_v<BonePose>);
static_assert(std::is_trivially_copyable_v<BonePose>);
static_assert(alignof(BonePose) == 64);
static_assert(sizeof(bool) == 1);
static_assert(offsetof(BonePose, normal) == 64);
static_assert(offsetof(BonePose, uniform_scale) == 100);
static_assert(offsetof(BonePose, tangent_orientation) == 104);
static_assert(offsetof(BonePose, normal_scale) == 108);
static_assert(offsetof(BonePose, color) == 112);
static_assert(offsetof(BonePose, glowing) == 116);
static_assert(sizeof(BonePose) == 128);

struct ModelPoseView {
    std::span<const BonePose> bone_poses;
    std::span<const uint16_t> render_bone_indices;
    // Nonzero when the latest extraction wrote the bone's pose.
    std::span<const uint8_t> bone_visited;
};

// Host motion for one spring bone step, all in the current model space.
struct PhysicsFrame {
    static constexpr size_t kFloatCount = 21;

    // Seconds since the previous extraction. Springs integrate them in fixed
    // steps and draw between the last two; 0 re-poses without integrating.
    float delta_seconds = 0.0f;
    // Restarts every spring from its animated pose.
    bool reset = false;
    // World down, any length.
    std::array<float, 3> gravity{0.0f, -1.0f, 0.0f};
    // Column-major map from the previous step's model space to the current one.
    mat4 motion = GLM_MAT4_IDENTITY_INIT;

    // Packed as delta_seconds, reset (0 or 1), gravity xyz, motion.
    static PhysicsFrame Unpack(std::span<const float, kFloatCount> packed);
};

class ModelState {
   public:
    struct ExtractOutput {
        uint32_t vertex_count = 0;
        // Separate outline pass output; excluded from vertex_count.
        uint32_t outline_vertex_count = 0;
        uint32_t locator_count = 0;
        bool schedule_updated = false;
    };

    // physics: steps the model's spring bones after posing; null clears them
    // and leaves bones at their animated pose.
    template <typename SimdTag>
    absl::StatusOr<ExtractOutput> Extract(
        SimdTag tag,
        const std::shared_ptr<bake::BakedModel>& baked_model,
        std::span<const BoneAttribute> bone_attributes,
        size_t locator_capacity, const PhysicsFrame* physics = nullptr);

    template <typename SimdTag>
    absl::StatusOr<ExtractOutput> ExtractSubtree(
        SimdTag tag,
        const std::shared_ptr<bake::BakedModel>& baked_model,
        std::span<const BoneAttribute> bone_attributes,
        size_t locator_capacity, size_t root_index);

    void Invalidate() noexcept { valid_ = false; }

    [[nodiscard]] std::span<const uint16_t> StagedLocatorBoneIndices(
        size_t count) const {
        return std::span(locator_bone_indices_scratch_).first(count);
    }

    [[nodiscard]] bool IsValid() const noexcept { return valid_; }

    [[nodiscard]] const std::shared_ptr<bake::BakedModel>& Model() const {
        return baked_model_;
    }

    [[nodiscard]] size_t RenderBoneSize() const noexcept {
        return render_bone_indices_.size();
    }

    [[nodiscard]] const RenderSchedule& Schedule() const { return schedule_; }

    [[nodiscard]] ModelPoseView PoseView() const {
        return {bone_poses_, render_bone_indices_, bone_visited_};
    }

    // Per mesh morph target, its weight from the latest extraction; all zero
    // or empty when nothing deforms.
    [[nodiscard]] std::span<const float> MorphWeights() const {
        return morph_weights_;
    }

    [[nodiscard]] bool HasActiveMorphs() const noexcept {
        return morphs_active_;
    }

    // Spring LOD: per spring chain, 0 simulates it (FULL), 1 holds it
    // (HOLD): from the next extraction the chain keeps the shape it had then,
    // in its joints' animated frames, with no integration or collision; on
    // return to FULL it starts at rest from where it was held. Chains past
    // the span are FULL. Neither change moves a tail.
    void SetSpringModes(std::span<const uint8_t> modes) {
        spring_chain_modes_.assign(modes.begin(), modes.end());
    }

   private:
    template <typename SimdTag>
    struct Extraction;

    struct SpringTail {
        std::array<float, 3> current{};
        std::array<float, 3> previous{};
        // False until seeded from the animated pose.
        bool live = false;
        // HOLD: the tail in the joint bone's animated frame, set on entry.
        std::array<float, 3> held{};
        bool holding = false;
    };

    // A chain's center bone (VRM spring center) across fixed steps.
    struct SpringCenter {
        // The center's pose at the latest fixed step, in the latest model
        // space; and its motion over the current step.
        mat4 pose = GLM_MAT4_IDENTITY_INIT;
        mat4 delta = GLM_MAT4_IDENTITY_INIT;
        // pose holds a step; delta holds this step's motion; the chain runs
        // in the center's space this step.
        bool valid = false;
        bool moved = false;
        bool active = false;
    };

    // A collider's ends as the latest fixed step left them, kept for the
    // colliders on simulated bones (moving), which collide where their bones
    // last swung.
    struct SpringColliderEnds {
        std::array<float, 3> head{};
        std::array<float, 3> tail{};
        bool moving = false;
        bool valid = false;
    };

    void StepSprings(const PhysicsFrame& frame);

    void UpdateMorphWeights(std::span<const BoneAttribute> bone_attributes);

    std::shared_ptr<bake::BakedModel> baked_model_;
    std::vector<BonePose> bone_poses_;
    std::vector<uint16_t> render_bone_indices_;
    std::vector<uint8_t> bone_visited_;
    std::vector<uint16_t> locator_bone_indices_scratch_;
    RenderSchedule schedule_;
    size_t schedule_worker_count_ = 0;
    bool schedule_valid_ = false;
    bool valid_ = false;
    // Spring tails in the latest model space, one per baked joint: the state
    // after the latest fixed step and the one before it.
    std::vector<SpringTail> spring_tails_;
    const bake::BakedModel* spring_model_ = nullptr;
    // Spring table and bone tree of spring_model_ (SpringFingerprint): a new
    // baked model with the same one (a mesh LOD level of the same geometry)
    // keeps the springs going instead of restarting them.
    uint64_t spring_fingerprint_ = 0;
    // Bones the fixed steps read (joints, colliders, centers): only these
    // are blended and restored per step.
    std::vector<uint32_t> spring_read_bones_;
    std::vector<uint8_t> spring_chain_modes_;
    // Seconds received but not yet integrated, below one fixed step.
    float spring_accumulator_ = 0.0f;
    // Body velocity in model units per second, smoothed across extractions.
    std::array<float, 3> spring_body_velocity_{};
    // Animated poses, restored before each fixed step and the final pose.
    std::vector<BonePose> spring_animated_poses_;
    // The previous extraction's animated poses and visited flags, moved into
    // the current model space; fixed steps blend from them to the current
    // animated poses.
    std::vector<BonePose> spring_previous_poses_;
    std::vector<uint8_t> spring_previous_visited_;
    // Per chain, its center's state; per collider, its moving ends.
    std::vector<SpringCenter> spring_centers_;
    std::vector<SpringColliderEnds> spring_collider_ends_;
    std::vector<float> morph_weights_;
    bool morphs_active_ = false;
};
}  // namespace ysm::gfx::renderer
