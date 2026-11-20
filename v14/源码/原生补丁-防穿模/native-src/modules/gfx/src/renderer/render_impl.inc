#ifndef GUARD_RENDER_IMPL
#define GUARD_RENDER_IMPL

#include <span>
#include "assume.h"
#include "fast_math.h"
#include "gfx/renderer/render.h"
#include "cube/output.h"
#include "cube/transform.h"
#include "render_state.h"

#endif

YSM_FAST_MATH_BEGIN
namespace ysm::gfx::renderer {
template <bool kCulling, bool kHasPbr>
YSM_SIMD_TARGET
RenderResult PerformRender(YSM_SIMD_TAG tag, const auto& vertex_consumer, const auto& model,
                   const RenderState& render_state,
                   const RenderTask::Partition& task,
                   const RenderParameters& params,
                   auto& waiter) noexcept {
    using CubeGroupType = std::remove_cvref_t<decltype(model)>::value_type;
    using VertexBufferType = std::remove_cvref_t<decltype(vertex_consumer)>;
    static constexpr bool kTranslucent = VertexBufferType::kTranslucent;
    static constexpr bool kPosOnly = VertexBufferType::kPosOnly;
    static constexpr bool kIris = VertexBufferType::kIris;

    if (task.expected_vertex_count == 0) [[unlikely]] {
        return RenderResult::kSuccess;
    }

    auto vertex_writer = vertex_consumer.CreateVertexWriter(
        task.vertex_offset, task.expected_vertex_count);
    const auto iris_entity_id = params.iris_entity_id;
    const auto overlay = params.overlay;

    for (auto cube_group_index : task.cube_indices) {
        auto& cube_group = model[cube_group_index];
        auto bone_index = cube_group.bone_index;
        if (!waiter.Wait(bone_index)) [[unlikely]] {
            return RenderResult::kStateInvalid;
        }

        auto& bone_state = render_state.GetBoneState(bone_index);
        if (bone_state.result != RenderResult::kSuccess) [[unlikely]] {
            return bone_state.result;
        }

        const auto light = bone_state.packed_light;
        const auto packed_color = kTranslucent
                                      ? bone_state.packed_translucent_color
                                      : bone_state.packed_opaque_color;
        cube::CubeOutput<CubeGroupType> out_cube_group;

        cube::Transform<kCulling, kIris, kHasPbr, kPosOnly>(
            tag, cube_group, bone_state, out_cube_group);

        // 遍历组内 Cube
        const int cube_count = (CubeGroupType::kCubeGroupCapacity > 1)
                                   ? cube_group.cube_count
                                   : 1;  // 期待编译器优化
        YSM_ASSUME(cube_count >= 1);
        YSM_ASSUME(cube_count <= CubeGroupType::kCubeGroupCapacity);
        for (auto cube_index = 0; cube_index < cube_count; cube_index++) {
            const auto& cube_attr = cube_group.cube_attr[cube_index];
            const int quad_count_after_culling =
                cube_attr.quad_count_after_culling;
            const auto quad_count = cube_attr.quad_count;

            // 遍历 Quad、剔除
            int processed_quads = 0;
            YSM_ASSUME(quad_count >= 1);
            YSM_ASSUME(quad_count <= 6);
            for (auto quad_index = 0; quad_index < quad_count; quad_index++) {
                const auto quad_attr_index =
                    CubeGroupType::GetQuadAttrIndex(cube_index, quad_index);
                const auto& quad_attr = cube_attr.quad_attr[quad_index];
                auto is_back_face = out_cube_group.back_face[quad_attr_index];
                if constexpr (kCulling) {
                    if (is_back_face) {
                        continue;
                    }
                    ++processed_quads;
                }
                vertex_writer.BeginQuad();
                if constexpr (kTranslucent) {
                    vertex_writer.SetQuadDepth(
                        out_cube_group.face_depth[quad_attr_index]);
                }

                const auto normal = out_cube_group.normal[quad_attr_index];
                uint32_t tangent = 0;
                if constexpr (kIris && kHasPbr && !kPosOnly) {
                    tangent = out_cube_group.tangent[quad_attr_index];
                }
                //if constexpr (!kGuiNoLighting) {
                // normal = out_cube_group.normal[quad_attr_index];
                //} else {
                //    normal = 0B10000001'00000000;
                //}

                for (uint32_t vertex_order = 0; vertex_order < 4;
                     ++vertex_order) {
                    auto vertex_index =
                        cube_group.vertex_index[vertex_order][quad_attr_index];
                    auto&& out_vertex = vertex_writer.QuadVertex(vertex_order);
                    out_vertex.SetVertex(out_cube_group.pos[0][vertex_index],
                                         out_cube_group.pos[1][vertex_index],
                                         out_cube_group.pos[2][vertex_index],
                                         quad_attr.uv[vertex_order]);
                    out_vertex.SetNormal(normal);
                    if constexpr (!kPosOnly) {
                        out_vertex.SetColor(packed_color);
                        out_vertex.SetOverlay(overlay);
                        out_vertex.SetLight(light);
                        if constexpr (kIris) {
                            out_vertex.SetIrisEntityId(iris_entity_id);
                            out_vertex.SetIrisMidUV(quad_attr.mid_uv);
                            out_vertex.SetIrisTangent(tangent);
                        }
                    }
                }
                vertex_writer.EndQuad();

                if constexpr (kCulling) {
                    if (processed_quads == quad_count_after_culling) {
                        break;
                    }
                }
            }  // quad
        }  // cube
    }  // cube group
    return RenderResult::kSuccess;
}

// Skins one lane block into meshlet scratch. Matrices are broadcast per joint
// and blended with per-lane weights; fixed-width lane loops let the compiler
// vectorize for the enclosing SIMD target. Rest positions come from pos_x/y/z:
// the block's own, or a morphed copy.
template <typename Block>
YSM_SIMD_TARGET
YSM_INLINE void SkinMeshBlock(YSM_SIMD_TAG, const Block& block,
                              const float* __restrict pos_x,
                              const float* __restrict pos_y,
                              const float* __restrict pos_z,
                              const RenderState& render_state,
                              float* __restrict out_x, float* __restrict out_y,
                              float* __restrict out_z,
                              uint32_t* __restrict out_normal) noexcept {
    static constexpr size_t kLanes = Block::kLanes;
    alignas(64) float px[kLanes]{};
    alignas(64) float py[kLanes]{};
    alignas(64) float pz[kLanes]{};
    alignas(64) float nx[kLanes]{};
    alignas(64) float ny[kLanes]{};
    alignas(64) float nz[kLanes]{};
    for (size_t k = 0; k < block.joint_count; ++k) {
        const auto& joint = render_state.GetJointState(block.joint[k]);
        const float m00 = joint.pose[0][0], m01 = joint.pose[0][1],
                    m02 = joint.pose[0][2];
        const float m10 = joint.pose[1][0], m11 = joint.pose[1][1],
                    m12 = joint.pose[1][2];
        const float m20 = joint.pose[2][0], m21 = joint.pose[2][1],
                    m22 = joint.pose[2][2];
        const float m30 = joint.pose[3][0], m31 = joint.pose[3][1],
                    m32 = joint.pose[3][2];
        const float n00 = joint.normal[0][0], n01 = joint.normal[0][1],
                    n02 = joint.normal[0][2];
        const float n10 = joint.normal[1][0], n11 = joint.normal[1][1],
                    n12 = joint.normal[1][2];
        const float n20 = joint.normal[2][0], n21 = joint.normal[2][1],
                    n22 = joint.normal[2][2];
        const auto& weight = block.weight[k];
        for (size_t lane = 0; lane < kLanes; ++lane) {
            const float w = weight[lane];
            const float x = pos_x[lane];
            const float y = pos_y[lane];
            const float z = pos_z[lane];
            px[lane] += w * (m00 * x + m10 * y + m20 * z + m30);
            py[lane] += w * (m01 * x + m11 * y + m21 * z + m31);
            pz[lane] += w * (m02 * x + m12 * y + m22 * z + m32);
            const float a = block.normal[0][lane];
            const float b = block.normal[1][lane];
            const float c = block.normal[2][lane];
            nx[lane] += w * (n00 * a + n10 * b + n20 * c);
            ny[lane] += w * (n01 * a + n11 * b + n21 * c);
            nz[lane] += w * (n02 * a + n12 * b + n22 * c);
        }
    }
    const auto quantize = [](float value) noexcept {
        value = std::clamp(value, -1.0f, 1.0f) * 127.0f;
        const auto rounded =
            static_cast<int32_t>(value + (value >= 0.0f ? 0.5f : -0.5f));
        return static_cast<uint32_t>(static_cast<uint8_t>(rounded));
    };
    for (size_t lane = 0; lane < kLanes; ++lane) {
        out_x[lane] = px[lane];
        out_y[lane] = py[lane];
        out_z[lane] = pz[lane];
        const float length_squared =
            nx[lane] * nx[lane] + ny[lane] * ny[lane] + nz[lane] * nz[lane];
        const float inverse =
            length_squared > 0.0f ? 1.0f / std::sqrt(length_squared) : 0.0f;
        out_normal[lane] = quantize(nx[lane] * inverse) |
                           quantize(ny[lane] * inverse) << 8 |
                           quantize(nz[lane] * inverse) << 16;
    }
}

template <bool kHasPbr>
YSM_SIMD_TARGET
RenderResult PerformMeshRender(YSM_SIMD_TAG tag, const auto& vertex_consumer,
                               const auto& mesh,
                               const std::vector<bake::Meshlet>& meshlets,
                               const RenderState& render_state,
                               const RenderTask::Partition& task,
                               const RenderParameters& params,
                               auto& waiter) noexcept {
    using MeshType = std::remove_cvref_t<decltype(mesh)>;
    using Block = typename MeshType::Block;
    using VertexBufferType = std::remove_cvref_t<decltype(vertex_consumer)>;
    static constexpr size_t kLanes = Block::kLanes;
    static constexpr size_t kMaxSlots = bake::Meshlet::kMaxSlots;
    static constexpr bool kTranslucent = VertexBufferType::kTranslucent;
    static constexpr bool kPosOnly = VertexBufferType::kPosOnly;
    static constexpr bool kIris = VertexBufferType::kIris;

    if (task.expected_vertex_count == 0) [[unlikely]] {
        return RenderResult::kSuccess;
    }

    auto vertex_writer = vertex_consumer.CreateVertexWriter(
        task.vertex_offset, task.expected_vertex_count);
    const auto iris_entity_id = params.iris_entity_id;
    const auto overlay = params.overlay;
    const float* depth_z = render_state.ViewDepthZ();
    const float* depth_w = render_state.ViewDepthW();

    alignas(64) std::array<float, kMaxSlots> skinned_x;
    alignas(64) std::array<float, kMaxSlots> skinned_y;
    alignas(64) std::array<float, kMaxSlots> skinned_z;
    alignas(64) std::array<uint32_t, kMaxSlots> packed_normal;
    const auto capsules = render_state.AntiClipCapsules();

    for (const auto meshlet_index : task.cube_indices) {
        const auto& meshlet = meshlets[meshlet_index];
        if (!waiter.Wait(meshlet.bone_index)) [[unlikely]] {
            return RenderResult::kStateInvalid;
        }
        const auto& owner = render_state.GetBoneState(meshlet.bone_index);
        if (owner.result != RenderResult::kSuccess) [[unlikely]] {
            return owner.result;
        }
        const auto blocks =
            std::span(mesh.blocks).subspan(meshlet.first_block,
                                           meshlet.block_count);
        const auto quads =
            std::span(mesh.quads).subspan(meshlet.first_quad,
                                          meshlet.quad_count);

        // A joint skipped this frame (hidden subtree, zero scale) has no
        // current pose; meshlets depending on it collapse to empty quads.
        bool visible = true;
        for (const auto& block : blocks) {
            for (size_t k = 0; k < block.joint_count; ++k) {
                visible &= render_state.GetJointState(block.joint[k]).valid;
            }
        }
        if (!visible) {
            for (size_t quad = 0; quad < quads.size(); ++quad) {
                vertex_writer.BeginQuad();
                if constexpr (kTranslucent) {
                    vertex_writer.SetQuadDepth(0.0f);
                }
                for (uint32_t corner = 0; corner < 4; ++corner) {
                    auto&& out_vertex = vertex_writer.QuadVertex(corner);
                    constexpr vec2 kZeroUv{0.0f, 0.0f};
                    out_vertex.SetVertex(0.0f, 0.0f, 0.0f, kZeroUv);
                    out_vertex.SetNormal(0);
                }
                vertex_writer.EndQuad();
            }
            continue;
        }

        const auto morph_weights = render_state.MorphWeights();
        for (size_t block_index = 0; block_index < blocks.size();
             ++block_index) {
            const auto base = block_index * kLanes;
            const auto& block = blocks[block_index];
            const float* pos_x = block.pos[0].data();
            const float* pos_y = block.pos[1].data();
            const float* pos_z = block.pos[2].data();
            // Morphed rest positions, only for blocks a morph touches.
            alignas(64) float morphed[3][kLanes];
            if (!morph_weights.empty()) {
                const auto global = meshlet.first_block + block_index;
                const auto first = mesh.morph_offsets[global];
                const auto last = mesh.morph_offsets[global + 1];
                if (first != last) {
                    for (size_t axis = 0; axis < 3; ++axis) {
                        std::copy_n(block.pos[axis].data(), kLanes,
                                    morphed[axis]);
                    }
                    for (auto d = first; d < last; ++d) {
                        const auto& delta = mesh.morph_deltas[d];
                        const auto w = morph_weights[delta.morph];
                        morphed[0][delta.lane] += w * delta.delta[0];
                        morphed[1][delta.lane] += w * delta.delta[1];
                        morphed[2][delta.lane] += w * delta.delta[2];
                    }
                    pos_x = morphed[0];
                    pos_y = morphed[1];
                    pos_z = morphed[2];
                }
            }
            SkinMeshBlock(tag, block, pos_x, pos_y, pos_z, render_state,
                          skinned_x.data() + base, skinned_y.data() + base,
                          skinned_z.data() + base, packed_normal.data() + base);
            // Anti-clip: soft lanes leave the body proxy beyond their rest
            // depth. Normals stay as skinned (see anti_clip.h).
            if (!mesh.anti_clip_offsets.empty() && !capsules.empty()) {
                const auto global = meshlet.first_block + block_index;
                const auto first = mesh.anti_clip_offsets[global];
                PushOutBlock(capsules,
                             std::span(mesh.anti_clip)
                                 .subspan(first, mesh.anti_clip_offsets[global + 1] - first),
                             skinned_x.data() + base, skinned_y.data() + base,
                             skinned_z.data() + base);
            }
        }

        const auto light = owner.packed_light;
        const auto packed_color = kTranslucent ? owner.packed_translucent_color
                                               : owner.packed_opaque_color;
        for (const auto& quad : quads) {
            vertex_writer.BeginQuad();
            if constexpr (kTranslucent) {
                float center[4]{0.0f, 0.0f, 0.0f, 1.0f};
                for (const auto slot : quad.slot) {
                    center[0] += skinned_x[slot] * 0.25f;
                    center[1] += skinned_y[slot] * 0.25f;
                    center[2] += skinned_z[slot] * 0.25f;
                }
                const auto clip_z = glm_vec4_dot(center, const_cast<float*>(depth_z));
                const auto clip_w = glm_vec4_dot(center, const_cast<float*>(depth_w));
                vertex_writer.SetQuadDepth(clip_z / clip_w);
            }

            uint32_t tangent = 0;
            if constexpr (kIris && kHasPbr && !kPosOnly) {
                vec3 source{quad.tangent[0], quad.tangent[1], quad.tangent[2]};
                vec4 transformed{};
                glm_mat3_mulv(const_cast<vec3*>(owner.normal), source,
                              transformed);
                cube::internal::NormalizeDirectionScalar(transformed);
                transformed[3] = quad.tangent[3] == 0.0f
                                     ? 1.0f
                                     : quad.tangent[3] * owner.tangent_orientation;
                tangent = buffer::PackSnorm4x8<false>(tag, transformed);
            }
            vec2 mid_uv{0.0f, 0.0f};
            std::array<std::array<float, 2>, 4> uvs{};
            for (uint32_t corner = 0; corner < 4; ++corner) {
                const auto slot = quad.slot[corner];
                const auto& block = blocks[slot / kLanes];
                uvs[corner] = {block.uv[0][slot % kLanes],
                               block.uv[1][slot % kLanes]};
                mid_uv[0] += uvs[corner][0] * 0.25f;
                mid_uv[1] += uvs[corner][1] * 0.25f;
            }

            for (uint32_t corner = 0; corner < 4; ++corner) {
                const auto slot = quad.slot[corner];
                auto&& out_vertex = vertex_writer.QuadVertex(corner);
                vec2 uv{uvs[corner][0], uvs[corner][1]};
                out_vertex.SetVertex(skinned_x[slot], skinned_y[slot],
                                     skinned_z[slot], uv);
                out_vertex.SetNormal(packed_normal[slot]);
                if constexpr (!kPosOnly) {
                    out_vertex.SetColor(packed_color);
                    out_vertex.SetOverlay(overlay);
                    out_vertex.SetLight(light);
                    if constexpr (kIris) {
                        out_vertex.SetIrisEntityId(iris_entity_id);
                        out_vertex.SetIrisMidUV(mid_uv);
                        out_vertex.SetIrisTangent(tangent);
                    }
                }
            }
            vertex_writer.EndQuad();
        }
    }
    return RenderResult::kSuccess;
}
}
YSM_FAST_MATH_END
