#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <span>
#include <string>
#include <vector>

#include <gtest/gtest.h>
#include <ylt/struct_pb.hpp>

#include "buffer.h"
#include "cpu.h"
#include "gfx/bake/baked_model.h"
#include "gfx/bake/baked_serializer.h"
#include "gfx/renderer/model_state.h"
#include "gfx/renderer/render.h"
#include "proto/asset/model/data/geo_model.proto.h"
#include "renderer/vertex/vanilla.h"

namespace ysm::test {
namespace {
namespace model_proto = proto::asset::model::data;
constexpr simd::Tag<simd::Type::kNone> kGenericTag;

void UseSse41() {
    InitCpuInfo();
#ifdef YSM_X64
    simd::Mock(simd::Type::SSE41);
#endif
}

// Two bones: "root" at the origin and "upper" one block above it. A unit-wide
// two-block-tall square: bottom vertices follow root, top vertices are split
// evenly between root and upper, so both triangles are owned by root and
// pair into one quad.
model_proto::GeoModel MakeSkinnedSquare() {
    model_proto::GeoModel model;
    model.bones.resize(2);
    model.bones[0].name = "root";
    model.bones[0].pivot = {0, 0, 0};
    model.bones[0].rotate = {0, 0, 0};
    model.bones[1].name = "upper";
    model.bones[1].parent = "root";
    model.bones[1].pivot = {0, 16, 0};
    model.bones[1].rotate = {0, 0, 0};

    auto& mesh = model.mesh.emplace();
    mesh.positions = {0, 0, 0, 1, 0, 0, 1, 2, 0, 0, 2, 0};
    mesh.normals = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
    mesh.uvs = {0, 1, 1, 1, 1, 0, 0, 0};
    mesh.joints = {0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0};
    mesh.weights = {1, 0, 0, 0, 1, 0, 0, 0, 0.5f, 0.5f, 0, 0, 0.5f, 0.5f, 0, 0};
    mesh.triangles = {0, 1, 2, 2, 3, 0};
    return model;
}

std::string Serialize(const model_proto::GeoModel& model) {
    std::string protobuf;
    struct_pb::to_pb(model, protobuf);
    return protobuf;
}

std::shared_ptr<gfx::bake::BakedModel> Bake(const model_proto::GeoModel& model,
                                            uint8_t alpha) {
    std::array<gfx::bake::Pixel, 4> pixels{};
    for (auto& pixel : pixels) {
        pixel = {255, 255, 255, alpha};
    }
    gfx::bake::Texture texture(pixels.data(), 2, 2);
    const auto protobuf = Serialize(model);
    auto baked = gfx::bake::BakeModel(StrBuf(protobuf), texture,
                                      {.origin_ver = 29});
    EXPECT_TRUE(baked.ok()) << baked.status();
    return baked.ok() ? std::shared_ptr<gfx::bake::BakedModel>(std::move(*baked))
                      : nullptr;
}

std::vector<gfx::renderer::BoneAttribute> IdentityAttributes(size_t count) {
    std::vector<gfx::renderer::BoneAttribute> attributes(count);
    for (auto& attribute : attributes) {
        attribute.scale[0] = 1.0f;
        attribute.scale[1] = 1.0f;
        attribute.scale[2] = 1.0f;
    }
    return attributes;
}

gfx::renderer::RenderParameters IdentityParameters() {
    gfx::renderer::RenderParameters parameters{};
    glm_mat4_identity(parameters.model);
    glm_mat4_identity(parameters.view);
    glm_mat4_identity(parameters.projection);
    glm_mat3_identity(parameters.normal);
    parameters.ctx = gfx::renderer::RenderContext::kLevel;
    parameters.light = 0x00f000f0;
    parameters.color.packed = 0xffffffff;
    return parameters;
}

std::vector<gfx::renderer::vertex::VanillaVertex> RenderVanilla(
    const gfx::renderer::ModelState& state, uint32_t vertex_count) {
    std::vector<Byte> output(vertex_count *
                             gfx::renderer::vertex::VanillaVertex::kSize);
    const auto status = gfx::renderer::Render(
        output, gfx::renderer::VertexKind::kVanilla, state, IdentityParameters());
    EXPECT_TRUE(status.ok()) << status;
    std::vector<gfx::renderer::vertex::VanillaVertex> vertices(vertex_count);
    std::memcpy(vertices.data(), output.data(), output.size());
    return vertices;
}

TEST(MeshRenderTest, BakesPairedQuadIntoOwnerBoneAndRoundTripsCache) {
    UseSse41();
    auto baked = Bake(MakeSkinnedSquare(), 255);
    ASSERT_NE(baked, nullptr);

    const auto& bones = baked->Bones();
    EXPECT_EQ(bones.joint_bone_indices, (std::vector<uint16_t>{0, 1}));
    ASSERT_EQ(bones.list[0].mesh_opaque.cube_indices.size(), 1U);
    EXPECT_TRUE(bones.list[1].mesh_opaque.cube_indices.empty());
    EXPECT_EQ(bones.list[0].mesh_opaque.full_vertex_count, 4U);
    baked->VisitCubes([](const auto& cubes) {
        ASSERT_EQ(cubes.mesh.opaque.size(), 1U);
        EXPECT_TRUE(cubes.mesh.translucent.empty());
        EXPECT_EQ(cubes.mesh.opaque[0].quad_count, 1U);
        // Root-only and root+upper signatures land in separate blocks.
        EXPECT_EQ(cubes.mesh.opaque[0].block_count, 2U);
    });

    const auto bytes = gfx::bake::SerializeBakedModel(*baked);
    auto restored = gfx::bake::ReadBakedModel(bytes);
    ASSERT_TRUE(restored.ok()) << restored.status();
    const auto again = gfx::bake::SerializeBakedModel(**restored);
    ASSERT_EQ(bytes.size(), again.size());
    EXPECT_EQ(std::memcmp(bytes.data(), again.data(), bytes.size()), 0);
}

TEST(MeshRenderTest, BlendsJointPosesWithVertexWeights) {
    UseSse41();
    auto baked = Bake(MakeSkinnedSquare(), 255);
    ASSERT_NE(baked, nullptr);

    auto attributes = IdentityAttributes(2);
    attributes[1].position[1] = 16.0f;  // upper rises one block
    gfx::renderer::ModelState state;
    auto extracted =
        state.Extract(kGenericTag, baked, attributes, attributes.size());
    ASSERT_TRUE(extracted.ok()) << extracted.status();
    ASSERT_EQ(extracted->vertex_count, 4U);

    const auto vertices = RenderVanilla(state, extracted->vertex_count);
    // Quad order (2, 3, 0, 1): diagonal 2-0 is the shared edge.
    const std::array<std::array<float, 3>, 4> expected{{
        {1.0f, 2.5f, 0.0f},
        {0.0f, 2.5f, 0.0f},
        {0.0f, 0.0f, 0.0f},
        {1.0f, 0.0f, 0.0f},
    }};
    for (size_t i = 0; i < 4; ++i) {
        for (size_t axis = 0; axis < 3; ++axis) {
            EXPECT_NEAR(vertices[i].pos[axis], expected[i][axis], 1e-5f)
                << "vertex " << i << " axis " << axis;
        }
        EXPECT_EQ(vertices[i].normal, 0x7fU << 16);
        EXPECT_EQ(vertices[i].light, 0x00f000f0U);
    }
    EXPECT_FLOAT_EQ(vertices[0].tex_uv[0], 1.0f);
    EXPECT_FLOAT_EQ(vertices[0].tex_uv[1], 0.0f);

    auto restored =
        gfx::bake::ReadBakedModel(gfx::bake::SerializeBakedModel(*baked));
    ASSERT_TRUE(restored.ok()) << restored.status();
    gfx::renderer::ModelState restored_state;
    ASSERT_TRUE(restored_state
                    .Extract(kGenericTag,
                             std::shared_ptr<gfx::bake::BakedModel>(
                                 std::move(*restored)),
                             attributes, attributes.size())
                    .ok());
    const auto restored_vertices =
        RenderVanilla(restored_state, extracted->vertex_count);
    EXPECT_EQ(std::memcmp(vertices.data(), restored_vertices.data(),
                          vertices.size() * sizeof(vertices[0])),
              0);
}

TEST(MeshRenderTest, MeshletWithSkippedJointCollapses) {
    UseSse41();
    auto baked = Bake(MakeSkinnedSquare(), 255);
    ASSERT_NE(baked, nullptr);

    auto attributes = IdentityAttributes(2);
    attributes[1].scale[1] = 0.0f;  // zero scale prunes the upper subtree
    gfx::renderer::ModelState state;
    auto extracted =
        state.Extract(kGenericTag, baked, attributes, attributes.size());
    ASSERT_TRUE(extracted.ok()) << extracted.status();
    ASSERT_EQ(extracted->vertex_count, 4U);

    for (const auto& vertex : RenderVanilla(state, extracted->vertex_count)) {
        EXPECT_EQ(vertex.pos[0], 0.0f);
        EXPECT_EQ(vertex.pos[1], 0.0f);
        EXPECT_EQ(vertex.pos[2], 0.0f);
    }
}

TEST(MeshRenderTest, PartialAlphaGoesToSortedTranslucentPartition) {
    UseSse41();
    auto baked = Bake(MakeSkinnedSquare(), 128);
    ASSERT_NE(baked, nullptr);
    EXPECT_TRUE(baked->Bones().list[0].mesh_opaque.cube_indices.empty());
    ASSERT_EQ(baked->Bones().list[0].mesh_translucent.cube_indices.size(), 1U);

    auto attributes = IdentityAttributes(2);
    gfx::renderer::ModelState state;
    auto extracted =
        state.Extract(kGenericTag, baked, attributes, attributes.size());
    ASSERT_TRUE(extracted.ok()) << extracted.status();
    EXPECT_EQ(state.Schedule().translucent_vertex_count, 4U);
    const auto vertices = RenderVanilla(state, extracted->vertex_count);
    EXPECT_NEAR(vertices[0].pos[1], 2.0f, 1e-5f);
}

TEST(MeshRenderTest, UnpairedTriangleBecomesQuadWithRepeatedVertex) {
    UseSse41();
    auto model = MakeSkinnedSquare();
    model.mesh->triangles = {0, 1, 2};
    auto baked = Bake(model, 255);
    ASSERT_NE(baked, nullptr);

    auto attributes = IdentityAttributes(2);
    gfx::renderer::ModelState state;
    auto extracted =
        state.Extract(kGenericTag, baked, attributes, attributes.size());
    ASSERT_TRUE(extracted.ok()) << extracted.status();
    ASSERT_EQ(extracted->vertex_count, 4U);
    const auto vertices = RenderVanilla(state, extracted->vertex_count);
    EXPECT_EQ(std::memcmp(vertices[2].pos, vertices[3].pos, sizeof(vec3)), 0);
    EXPECT_NEAR(vertices[2].pos[0], 1.0f, 1e-5f);
    EXPECT_NEAR(vertices[2].pos[1], 2.0f, 1e-5f);
}

TEST(MeshRenderTest, MorphTargetsFollowTheirDriverBones) {
    UseSse41();
    auto model = MakeSkinnedSquare();
    // Driver bones without geometry: "Eyelid" closes by scale, an expression
    // bone weighs by X translation.
    model.bones.resize(4);
    model.bones[2].name = "Eyelid";
    model.bones[2].parent = "root";
    model.bones[2].pivot = {0, 0, 0};
    model.bones[2].rotate = {0, 0, 0};
    model.bones[3].name = "expression.smile";
    model.bones[3].parent = "root";
    model.bones[3].pivot = {0, 0, 0};
    model.bones[3].rotate = {0, 0, 0};
    model.mesh->morphs.push_back(
        model_proto::MorphTarget(3, 0, {2, 3}, {0.5f, 0, 0, 0.5f, 0, 0}));
    model.mesh->morphs.push_back(model_proto::MorphTarget(2, 1, {0}, {0, 1, 0}));
    auto baked = Bake(model, 255);
    ASSERT_NE(baked, nullptr);

    auto attributes = IdentityAttributes(4);
    gfx::renderer::ModelState state;
    auto rest = state.Extract(kGenericTag, baked, attributes, attributes.size());
    ASSERT_TRUE(rest.ok()) << rest.status();
    EXPECT_FALSE(state.HasActiveMorphs());
    auto vertices = RenderVanilla(state, rest->vertex_count);
    // Quad order (2, 3, 0, 1).
    EXPECT_NEAR(vertices[0].pos[0], 1.0f, 1e-5f);
    EXPECT_NEAR(vertices[2].pos[1], 0.0f, 1e-5f);

    attributes[3].position[0] = 0.5f;  // smile at half weight
    attributes[2].scale[0] = attributes[2].scale[1] = attributes[2].scale[2] =
        0.0f;  // eyes closed: full weight, though the bone itself is pruned
    auto posed = state.Extract(kGenericTag, baked, attributes, attributes.size());
    ASSERT_TRUE(posed.ok()) << posed.status();
    vertices = RenderVanilla(state, posed->vertex_count);
    const std::array<std::array<float, 3>, 4> expected{{
        {1.25f, 2.0f, 0.0f},
        {0.25f, 2.0f, 0.0f},
        {0.0f, 1.0f, 0.0f},
        {1.0f, 0.0f, 0.0f},
    }};
    for (size_t i = 0; i < 4; ++i) {
        for (size_t axis = 0; axis < 3; ++axis) {
            EXPECT_NEAR(vertices[i].pos[axis], expected[i][axis], 1e-5f)
                << "vertex " << i << " axis " << axis;
        }
    }

    const auto bytes = gfx::bake::SerializeBakedModel(*baked);
    auto restored = gfx::bake::ReadBakedModel(bytes);
    ASSERT_TRUE(restored.ok()) << restored.status();
    const auto again = gfx::bake::SerializeBakedModel(**restored);
    ASSERT_EQ(bytes.size(), again.size());
    EXPECT_EQ(std::memcmp(bytes.data(), again.data(), bytes.size()), 0);

    model.mesh->morphs[0].vertices[0] = 9;
    std::array<gfx::bake::Pixel, 1> pixels{gfx::bake::Pixel{255, 255, 255, 255}};
    const auto protobuf = Serialize(model);
    EXPECT_FALSE(gfx::bake::BakeModel(StrBuf(protobuf),
                                      gfx::bake::Texture(pixels.data(), 1, 1), {})
                     .ok());
}

TEST(MeshRenderTest, RejectsInconsistentMeshStreams) {
    UseSse41();
    std::array<gfx::bake::Pixel, 1> pixels{gfx::bake::Pixel{255, 255, 255, 255}};
    gfx::bake::Texture texture(pixels.data(), 1, 1);

    auto out_of_range = MakeSkinnedSquare();
    out_of_range.mesh->joints[0] = 7;
    auto zero_weight = MakeSkinnedSquare();
    zero_weight.mesh->weights[0] = 0.0f;
    auto bad_triangle = MakeSkinnedSquare();
    bad_triangle.mesh->triangles[1] = 9;
    auto bad_outline = MakeSkinnedSquare();
    bad_outline.mesh->outline_triangles = {0, 1, 9};
    auto partial_outline = MakeSkinnedSquare();
    partial_outline.mesh->outline_triangles = {0, 1};
    auto short_uvs = MakeSkinnedSquare();
    short_uvs.mesh->uvs.pop_back();
    for (const auto* model : {&out_of_range, &zero_weight, &bad_triangle,
                              &bad_outline, &partial_outline, &short_uvs}) {
        const auto protobuf = Serialize(*model);
        EXPECT_FALSE(gfx::bake::BakeModel(StrBuf(protobuf), texture, {}).ok());
    }
}

// MakeSkinnedSquare plus an inverted hull: vertices 4-7 copy 0-3 pushed 0.25
// along the +z normal with the same joints and weights, listed as the square's
// two triangles with reversed winding (paired, owned by root). Vertices 8-10
// form a lone reversed triangle fully weighted to "upper".
model_proto::GeoModel MakeOutlinedSquare() {
    auto model = MakeSkinnedSquare();
    auto& mesh = *model.mesh;
    constexpr float kPush = 0.25f;
    for (uint32_t vertex = 0; vertex < 4; ++vertex) {
        mesh.positions.push_back(mesh.positions[vertex * 3]);
        mesh.positions.push_back(mesh.positions[vertex * 3 + 1]);
        mesh.positions.push_back(mesh.positions[vertex * 3 + 2] + kPush);
        mesh.normals.insert(mesh.normals.end(), {0, 0, 1});
        mesh.uvs.insert(mesh.uvs.end(), {0.25f, 0.25f});
        for (size_t slot = 0; slot < 4; ++slot) {
            mesh.joints.push_back(mesh.joints[vertex * 4 + slot]);
            mesh.weights.push_back(mesh.weights[vertex * 4 + slot]);
        }
    }
    mesh.positions.insert(mesh.positions.end(),
                          {0, 3, kPush, 1, 3, kPush, 0, 4, kPush});
    for (size_t vertex = 0; vertex < 3; ++vertex) {
        mesh.normals.insert(mesh.normals.end(), {0, 0, 1});
        mesh.uvs.insert(mesh.uvs.end(), {0.75f, 0.75f});
        mesh.joints.insert(mesh.joints.end(), {1, 0, 0, 0});
        mesh.weights.insert(mesh.weights.end(), {1, 0, 0, 0});
    }
    mesh.outline_triangles = {4, 6, 5, 6, 4, 7, 8, 10, 9};
    return model;
}

std::vector<gfx::renderer::vertex::VanillaVertex> RenderVanillaPass(
    const gfx::renderer::ModelState& state, uint32_t vertex_count,
    gfx::renderer::RenderPass pass) {
    std::vector<Byte> output(vertex_count *
                             gfx::renderer::vertex::VanillaVertex::kSize);
    auto parameters = IdentityParameters();
    parameters.pass = pass;
    gfx::renderer::RenderResult result;
    const auto status =
        gfx::renderer::Render(output, gfx::renderer::VertexKind::kVanilla,
                              state, parameters, result);
    EXPECT_TRUE(status.ok()) << status;
    EXPECT_EQ(result, gfx::renderer::RenderResult::kSuccess);
    std::vector<gfx::renderer::vertex::VanillaVertex> vertices(vertex_count);
    std::memcpy(vertices.data(), output.data(), output.size());
    return vertices;
}

// Source vertex whose rest position the emitted vertex carries; identity
// poses leave rest positions unchanged.
uint32_t SourceVertex(const model_proto::SkinnedMesh& mesh,
                      const gfx::renderer::vertex::VanillaVertex& vertex) {
    for (uint32_t index = 0; index < mesh.positions.size() / 3; ++index) {
        bool same = true;
        for (size_t axis = 0; axis < 3; ++axis) {
            same &= std::abs(vertex.pos[axis] -
                             mesh.positions[index * 3 + axis]) < 1e-5f;
        }
        if (same) {
            return index;
        }
    }
    ADD_FAILURE() << "emitted vertex matches no source vertex";
    return UINT32_MAX;
}

// Checks QUADS output (triangles (0,1,2) and (2,3,0) per quad): every
// non-degenerate triangle is a cyclic rotation of a source triangle, so its
// winding survives, and the source triangles are each emitted exactly once.
void ExpectSourceWinding(
    const model_proto::SkinnedMesh& mesh, std::span<const uint32_t> triangles,
    const std::vector<gfx::renderer::vertex::VanillaVertex>& vertices,
    float expected_area_sign) {
    ASSERT_EQ(vertices.size() % 4, 0U);
    std::vector<int> emitted(triangles.size() / 3);
    for (size_t quad = 0; quad < vertices.size() / 4; ++quad) {
        for (const auto& corners : {std::array<size_t, 3>{0, 1, 2},
                                    std::array<size_t, 3>{2, 3, 0}}) {
            std::array<uint32_t, 3> source{};
            for (size_t i = 0; i < 3; ++i) {
                source[i] = SourceVertex(mesh, vertices[quad * 4 + corners[i]]);
            }
            if (source[0] == source[1] || source[1] == source[2] ||
                source[0] == source[2]) {
                continue;
            }
            const auto& a = vertices[quad * 4 + corners[0]].pos;
            const auto& b = vertices[quad * 4 + corners[1]].pos;
            const auto& c = vertices[quad * 4 + corners[2]].pos;
            const float area = (b[0] - a[0]) * (c[1] - a[1]) -
                               (b[1] - a[1]) * (c[0] - a[0]);
            EXPECT_GT(area * expected_area_sign, 0.0f) << "quad " << quad;
            bool matched = false;
            for (size_t t = 0; t < emitted.size() && !matched; ++t) {
                for (size_t rotation = 0; rotation < 3 && !matched;
                     ++rotation) {
                    matched = source[0] == triangles[t * 3 + rotation] &&
                              source[1] == triangles[t * 3 + (rotation + 1) % 3] &&
                              source[2] == triangles[t * 3 + (rotation + 2) % 3];
                    if (matched) {
                        ++emitted[t];
                    }
                }
            }
            EXPECT_TRUE(matched) << "quad " << quad << " flips or invents a "
                                 << "triangle";
        }
    }
    for (size_t t = 0; t < emitted.size(); ++t) {
        EXPECT_EQ(emitted[t], 1) << "source triangle " << t;
    }
}

TEST(MeshOutlineTest, BakesOutlineIntoSeparateOpaquePartitionPerOwner) {
    UseSse41();
    // Partial alpha everywhere: regular triangles turn translucent, outline
    // triangles are never classified.
    auto baked = Bake(MakeOutlinedSquare(), 128);
    ASSERT_NE(baked, nullptr);

    const auto& bones = baked->Bones();
    EXPECT_EQ(bones.joint_bone_indices, (std::vector<uint16_t>{0, 1}));
    EXPECT_TRUE(bones.list[0].mesh_opaque.cube_indices.empty());
    EXPECT_EQ(bones.list[0].mesh_translucent.cube_indices.size(), 1U);
    ASSERT_EQ(bones.list[0].mesh_outline.cube_indices.size(), 1U);
    EXPECT_EQ(bones.list[0].mesh_outline.full_vertex_count, 4U);
    ASSERT_EQ(bones.list[1].mesh_outline.cube_indices.size(), 1U);
    EXPECT_EQ(bones.list[1].mesh_outline.full_vertex_count, 4U);
    baked->VisitCubes([](const auto& cubes) {
        ASSERT_EQ(cubes.mesh.outline.size(), 2U);
        EXPECT_EQ(cubes.mesh.outline[0].bone_index, 0U);
        EXPECT_EQ(cubes.mesh.outline[1].bone_index, 1U);
    });

    // Fully transparent texels drop regular triangles but keep the outline.
    auto transparent = Bake(MakeOutlinedSquare(), 0);
    ASSERT_NE(transparent, nullptr);
    auto attributes = IdentityAttributes(2);
    gfx::renderer::ModelState state;
    auto extracted =
        state.Extract(kGenericTag, transparent, attributes, attributes.size());
    ASSERT_TRUE(extracted.ok()) << extracted.status();
    EXPECT_EQ(extracted->vertex_count, 0U);
    EXPECT_EQ(extracted->outline_vertex_count, 8U);
}

TEST(MeshOutlineTest, MainAndOutlinePassesEmitDisjointGeometry) {
    UseSse41();
    const auto model = MakeOutlinedSquare();
    auto baked = Bake(model, 255);
    ASSERT_NE(baked, nullptr);

    auto attributes = IdentityAttributes(2);
    gfx::renderer::ModelState state;
    auto extracted =
        state.Extract(kGenericTag, baked, attributes, attributes.size());
    ASSERT_TRUE(extracted.ok()) << extracted.status();
    ASSERT_EQ(extracted->vertex_count, 4U);
    ASSERT_EQ(extracted->outline_vertex_count, 8U);
    EXPECT_EQ(state.Schedule().translucent_vertex_count, 0U);

    const auto main = RenderVanillaPass(state, extracted->vertex_count,
                                        gfx::renderer::RenderPass::kMain);
    for (const auto& vertex : main) {
        EXPECT_LT(SourceVertex(*model.mesh, vertex), 4U);
    }
    // Front faces are counter-clockwise seen from +z.
    ExpectSourceWinding(*model.mesh, model.mesh->triangles, main, 1.0f);

    const auto outline =
        RenderVanillaPass(state, extracted->outline_vertex_count,
                          gfx::renderer::RenderPass::kOutline);
    for (const auto& vertex : outline) {
        EXPECT_GE(SourceVertex(*model.mesh, vertex), 4U);
        EXPECT_EQ(vertex.light, 0x00f000f0U);
        EXPECT_EQ(vertex.color, main[0].color);
        EXPECT_EQ(vertex.normal, 0x7fU << 16);
    }
    ExpectSourceWinding(*model.mesh, model.mesh->outline_triangles, outline,
                        -1.0f);
    // The lone triangle repeats its last corner.
    EXPECT_EQ(std::memcmp(outline[6].pos, outline[7].pos, sizeof(vec3)), 0);

    // The outline pass checks capacity against its own count.
    std::vector<Byte> too_small(
        (extracted->outline_vertex_count - 1) *
        gfx::renderer::vertex::VanillaVertex::kSize);
    auto parameters = IdentityParameters();
    parameters.pass = gfx::renderer::RenderPass::kOutline;
    EXPECT_FALSE(gfx::renderer::Render(too_small,
                                       gfx::renderer::VertexKind::kVanilla,
                                       state, parameters)
                     .ok());

    auto restored =
        gfx::bake::ReadBakedModel(gfx::bake::SerializeBakedModel(*baked));
    ASSERT_TRUE(restored.ok()) << restored.status();
    const auto again = gfx::bake::SerializeBakedModel(**restored);
    const auto bytes = gfx::bake::SerializeBakedModel(*baked);
    ASSERT_EQ(bytes.size(), again.size());
    EXPECT_EQ(std::memcmp(bytes.data(), again.data(), bytes.size()), 0);
    gfx::renderer::ModelState restored_state;
    auto restored_extracted = restored_state.Extract(
        kGenericTag,
        std::shared_ptr<gfx::bake::BakedModel>(std::move(*restored)),
        attributes, attributes.size());
    ASSERT_TRUE(restored_extracted.ok()) << restored_extracted.status();
    ASSERT_EQ(restored_extracted->vertex_count, 4U);
    ASSERT_EQ(restored_extracted->outline_vertex_count, 8U);
    const auto restored_main = RenderVanillaPass(
        restored_state, 4, gfx::renderer::RenderPass::kMain);
    const auto restored_outline = RenderVanillaPass(
        restored_state, 8, gfx::renderer::RenderPass::kOutline);
    EXPECT_EQ(std::memcmp(main.data(), restored_main.data(),
                          main.size() * sizeof(main[0])),
              0);
    EXPECT_EQ(std::memcmp(outline.data(), restored_outline.data(),
                          outline.size() * sizeof(outline[0])),
              0);
}

TEST(MeshOutlineTest, MeshWithoutOutlineHasEmptyOutlinePass) {
    UseSse41();
    auto baked = Bake(MakeSkinnedSquare(), 255);
    ASSERT_NE(baked, nullptr);
    for (const auto& bone : baked->Bones().list) {
        EXPECT_TRUE(bone.mesh_outline.cube_indices.empty());
    }
    baked->VisitCubes(
        [](const auto& cubes) { EXPECT_TRUE(cubes.mesh.outline.empty()); });

    auto attributes = IdentityAttributes(2);
    gfx::renderer::ModelState state;
    auto extracted =
        state.Extract(kGenericTag, baked, attributes, attributes.size());
    ASSERT_TRUE(extracted.ok()) << extracted.status();
    EXPECT_EQ(extracted->vertex_count, 4U);
    EXPECT_EQ(extracted->outline_vertex_count, 0U);
    EXPECT_EQ(state.Schedule().outline_vertex_count, 0U);
    EXPECT_TRUE(RenderVanillaPass(state, 0, gfx::renderer::RenderPass::kOutline)
                    .empty());
}
}  // namespace
}  // namespace ysm::test
