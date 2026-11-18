#pragma once

#include <cstdint>

#include <cglm/mat4.h>

#include "buffer.h"
#include "color.h"
#include "vertex/kind.h"
#include "model_state.h"

namespace ysm::gfx::renderer {
enum class RenderContext : uint16_t { kLevel, kIrisShadow, kGui };
// kMain emits every partition except mesh outlines, filling
// RenderSchedule::vertex_count vertices; kOutline emits only the opaque
// inverted-hull outline meshlets, filling RenderSchedule::outline_vertex_count.
enum class RenderPass : uint8_t { kMain, kOutline };
enum class RenderResult : uint32_t { kStateInvalid = 0, kSuccess = 1, kOutputRejected = 2 };

struct alignas(64) RenderParameters {
    mat4 model;
    mat4 view;
    mat4 projection;
    mat3 normal;
    RenderContext ctx;
    RenderPass pass = RenderPass::kMain;
    uint32_t light = 0;
    uint32_t overlay = 0;
    Color color;

    uint64_t iris_entity_id = 0;
};

absl::Status Render(BufferView vertex_buffer, VertexKind vertex_kind,
                    const ModelState& model_state,
                    const RenderParameters& parameters, RenderResult& result);

inline absl::Status Render(BufferView vertex_buffer, VertexKind vertex_kind,
                          const ModelState& model_state,
                          const RenderParameters& parameters) {
    RenderResult result;
    return Render(vertex_buffer, vertex_kind, model_state, parameters, result);
}
}  // namespace ysm::gfx::renderer
