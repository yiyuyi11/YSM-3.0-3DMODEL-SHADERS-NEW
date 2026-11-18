#include <algorithm>
#include <cmath>

#include "gfx/renderer/model_state.h"

namespace ysm::gfx::renderer {
namespace {
float Clamp01(float value) {
    return std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.0f;
}
}  // namespace

// Each morph target reads its driver bone's raw animation attributes, so
// hiding or zero-scaling the driver (as the default blink does to "Eyelid")
// still yields a weight.
void ModelState::UpdateMorphWeights(
    std::span<const BoneAttribute> bone_attributes) {
    morphs_active_ = false;
    baked_model_->VisitCubes([&](const auto& cubes) {
        const auto& morphs = cubes.mesh.morphs;
        morph_weights_.resize(morphs.size());
        for (size_t i = 0; i < morphs.size(); ++i) {
            const auto& attribute = bone_attributes[morphs[i].bone];
            const auto weight =
                morphs[i].driver == bake::MorphDriver::kClosingScale
                    ? Clamp01(1.0f - attribute.scale[1])
                    : Clamp01(attribute.position[0]);
            morph_weights_[i] = weight;
            morphs_active_ |= weight != 0.0f;
        }
    });
}
}  // namespace ysm::gfx::renderer
