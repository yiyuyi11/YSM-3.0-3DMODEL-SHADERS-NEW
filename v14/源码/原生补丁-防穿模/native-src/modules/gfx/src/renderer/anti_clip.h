#pragma once

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstdint>
#include <span>

#include <cglm/mat4.h>

#include "gfx/bake/baked_model.h"

namespace ysm::gfx::renderer {
// A body proxy capsule (BakedSprings::body) posed for one draw, in the space
// the skinned vertices come out in. Radii and the allowance unit carry the
// pose's scale. center and bound: a sphere around the whole capsule.
struct PosedBodyCapsule {
    float head[3]{};
    float axis[3]{};
    float center[3]{};
    float bound = 0.0f;
    float inverse_length_squared = 0.0f;
    float radius = 0.0f;
    float radius_delta = 0.0f;
    float scale = 1.0f;
    bool valid = false;
};

// Poses a capsule with a bone's skin matrix (outer model matrix times the
// bone pose). The scale is the mean length of the matrix's basis columns.
inline void PoseBodyCapsule(const bake::BodyCapsule& capsule, const mat4 skin,
                            PosedBodyCapsule& out) noexcept {
    float head[3];
    float tail[3];
    for (int row = 0; row < 3; ++row) {
        head[row] = skin[0][row] * capsule.head[0] +
                    skin[1][row] * capsule.head[1] +
                    skin[2][row] * capsule.head[2] + skin[3][row];
        tail[row] = skin[0][row] * capsule.tail[0] +
                    skin[1][row] * capsule.tail[1] +
                    skin[2][row] * capsule.tail[2] + skin[3][row];
    }
    float scale = 0.0f;
    for (int column = 0; column < 3; ++column) {
        scale += std::sqrt(skin[column][0] * skin[column][0] +
                           skin[column][1] * skin[column][1] +
                           skin[column][2] * skin[column][2]);
    }
    scale /= 3.0f;
    float length_squared = 0.0f;
    for (int row = 0; row < 3; ++row) {
        out.head[row] = head[row];
        out.axis[row] = tail[row] - head[row];
        length_squared += out.axis[row] * out.axis[row];
    }
    out.inverse_length_squared =
        length_squared > 1e-12f ? 1.0f / length_squared : 0.0f;
    out.radius = capsule.radius * scale;
    out.radius_delta = (capsule.tail_radius - capsule.radius) * scale;
    out.scale = scale;
    for (int row = 0; row < 3; ++row) {
        out.center[row] = head[row] + 0.5f * out.axis[row];
    }
    out.bound = 0.5f * std::sqrt(length_squared) +
                std::max(out.radius, out.radius + out.radius_delta);
    out.valid = std::isfinite(scale) && scale > 0.0f && std::isfinite(out.bound);
}

// Anti-clip push-out of one skinned vertex: for each capsule of the mask the
// vertex is more than allowance (model units, its rest depth) inside, it
// moves share of the excess out along the capsule's radius. With a margin
// (model units) the move ramps in smoothly (zero slope at the start) over the
// first 2 * margin of the excess and is the whole excess from there, so
// neighbouring vertices just beyond their allowance do not jump apart. A
// vertex at most its rest depth inside every capsule is left bit for bit as
// skinned.
inline void PushOutOfBody(std::span<const PosedBodyCapsule> capsules,
                          uint32_t mask, float share, float allowance,
                          float margin, float& x, float& y, float& z) noexcept {
    while (mask != 0) {
        const auto index = static_cast<size_t>(std::countr_zero(mask));
        mask &= mask - 1;
        if (index >= capsules.size()) {
            break;
        }
        const auto& capsule = capsules[index];
        if (!capsule.valid) {
            continue;
        }
        const float dx = x - capsule.head[0];
        const float dy = y - capsule.head[1];
        const float dz = z - capsule.head[2];
        const float t = std::clamp(
            (dx * capsule.axis[0] + dy * capsule.axis[1] + dz * capsule.axis[2]) *
                capsule.inverse_length_squared,
            0.0f, 1.0f);
        const float ox = dx - capsule.axis[0] * t;
        const float oy = dy - capsule.axis[1] * t;
        const float oz = dz - capsule.axis[2] * t;
        // Distance from the axis below which the vertex is too deep.
        const float limit = capsule.radius + capsule.radius_delta * t -
                            allowance * capsule.scale;
        const float squared = ox * ox + oy * oy + oz * oz;
        if (!(limit > 0.0f) || squared >= limit * limit) {
            continue;
        }
        const float distance = std::sqrt(squared);
        if (distance <= 1e-6f * capsule.scale) {
            continue;  // on the axis: no radial direction
        }
        float excess = limit - distance;
        const float ramp = 2.0f * margin * capsule.scale;
        if (excess < ramp) {
            const float s = excess / ramp;
            excess *= s * s * (3.0f - 2.0f * s);
        }
        const float move = excess / distance * share;
        x += ox * move;
        y += oy * move;
        z += oz * move;
    }
}

// Normals stay as skinned. The push is a translation of a few millimetres to
// centimetres along the body's radius; it does not turn the cloth, whose
// surface already runs along the body where it is pushed, and a corrected
// normal would need the vertex's neighbours, which a lane does not have.
//
// Push-out of one skinned lane block's soft lanes (x, y, z: the block's
// skinned lanes). Capsules whose bounding sphere misses the sphere around the
// lanes are dropped for the whole block first: a block's lanes share their
// joints and sit together, so most of them test only the capsules nearby.
inline void PushOutBlock(std::span<const PosedBodyCapsule> capsules,
                         std::span<const bake::MeshAntiClip> lanes, float* x,
                         float* y, float* z) noexcept {
    if (lanes.empty() || capsules.empty()) {
        return;
    }
    uint32_t mask = 0;
    float cx = 0.0f;
    float cy = 0.0f;
    float cz = 0.0f;
    for (const auto& lane : lanes) {
        mask |= lane.capsules;
        cx += x[lane.lane];
        cy += y[lane.lane];
        cz += z[lane.lane];
    }
    const float inverse = 1.0f / static_cast<float>(lanes.size());
    cx *= inverse;
    cy *= inverse;
    cz *= inverse;
    float spread = 0.0f;
    for (const auto& lane : lanes) {
        const float dx = x[lane.lane] - cx;
        const float dy = y[lane.lane] - cy;
        const float dz = z[lane.lane] - cz;
        spread = std::max(spread, dx * dx + dy * dy + dz * dz);
    }
    spread = std::sqrt(spread);
    uint32_t near = 0;
    for (auto bits = mask; bits != 0; bits &= bits - 1) {
        const auto index = static_cast<size_t>(std::countr_zero(bits));
        if (index >= capsules.size() || !capsules[index].valid) {
            continue;
        }
        const auto& capsule = capsules[index];
        const float dx = capsule.center[0] - cx;
        const float dy = capsule.center[1] - cy;
        const float dz = capsule.center[2] - cz;
        const float reach = capsule.bound + spread;
        if (dx * dx + dy * dy + dz * dz < reach * reach) {
            near |= uint32_t{1} << index;
        }
    }
    if (near == 0) {
        return;
    }
    for (const auto& lane : lanes) {
        if ((lane.capsules & near) != 0) {
            PushOutOfBody(capsules, lane.capsules & near, lane.share,
                          lane.allowance, lane.margin, x[lane.lane],
                          y[lane.lane], z[lane.lane]);
        }
    }
}
}  // namespace ysm::gfx::renderer
