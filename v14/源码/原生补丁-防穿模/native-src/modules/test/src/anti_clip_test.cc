#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <string>
#include <vector>

#include <cglm/affine.h>
#include <gtest/gtest.h>
#include <ylt/struct_pb.hpp>

#include "buffer.h"
#include "cpu.h"
#include "gfx/bake/baked_model.h"
#include "gfx/bake/baked_serializer.h"
#include "gfx/renderer/model_state.h"
#include "gfx/renderer/render.h"
#include "proto/asset/model/data/geo_model.proto.h"
#include "renderer/anti_clip.h"
#include "renderer/vertex/vanilla.h"

namespace ysm::test {
namespace {
namespace model_proto = proto::asset::model::data;
constexpr simd::Tag<simd::Type::kNone> kGenericTag;

std::string ReadFile(const char* path) {
    std::string data;
    FILE* file = std::fopen(path, "rb");
    if (file == nullptr) {
        return data;
    }
    char buffer[1 << 16];
    size_t read;
    while ((read = std::fread(buffer, 1, sizeof(buffer), file)) > 0) {
        data.append(buffer, read);
    }
    std::fclose(file);
    return data;
}

std::shared_ptr<gfx::bake::BakedModel> BakeOpaque(const model_proto::GeoModel& model) {
    std::array<gfx::bake::Pixel, 4> pixels{};
    for (auto& pixel : pixels) {
        pixel = {255, 255, 255, 255};
    }
    gfx::bake::Texture texture(pixels.data(), 2, 2);
    std::string protobuf;
    struct_pb::to_pb(model, protobuf);
    auto baked = gfx::bake::BakeModel(StrBuf(protobuf), texture, {.origin_ver = 29});
    EXPECT_TRUE(baked.ok()) << baked.status();
    return baked.ok() ? std::shared_ptr<gfx::bake::BakedModel>(std::move(*baked)) : nullptr;
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

// Offline anti-clip harness (tools/anticlip/harness.py). Replays a pose stream
// on an imported model through the real extraction (springs included) and
// render path, and writes every drawn vertex's position by source index (the
// UVs carry the index) plus the bone poses.
//
// YSM_ANTICLIP_MODEL: GeoModel protobuf. YSM_ANTICLIP_POSES: int32 magic,
// bones, frames; per frame float32 measure, the 21 PhysicsFrame floats, then
// rotation, position, scale per bone. YSM_ANTICLIP_OUT: per measured frame
// float32 bone poses (16 per bone, original order, NaN when not posed),
// positions (3 per vertex, NaN when not drawn), extract and render
// milliseconds.
TEST(AntiClipTest, Replay) {
    const auto* model_path = std::getenv("YSM_ANTICLIP_MODEL");
    const auto* poses_path = std::getenv("YSM_ANTICLIP_POSES");
    const auto* out_path = std::getenv("YSM_ANTICLIP_OUT");
    if (model_path == nullptr || poses_path == nullptr || out_path == nullptr) {
        GTEST_SKIP() << "YSM_ANTICLIP_MODEL / _POSES / _OUT not set";
    }
    InitCpuInfo();
    const auto protobuf = ReadFile(model_path);
    ASSERT_FALSE(protobuf.empty());
    model_proto::GeoModel model;
    struct_pb::from_pb(model, protobuf);
    ASSERT_TRUE(model.mesh.has_value());
    auto& mesh = *model.mesh;
    const auto vertex_count = mesh.positions.size() / 3;
    for (size_t vertex = 0; vertex < vertex_count; ++vertex) {
        mesh.uvs[vertex * 2] = static_cast<float>(vertex);
        mesh.uvs[vertex * 2 + 1] = 0.5f;
    }
    const auto baked = BakeOpaque(model);
    ASSERT_NE(baked, nullptr);
    const auto& sorted = baked->Bones().sorted_bone_indices;
    const auto bone_count = model.bones.size();

    const auto poses = ReadFile(poses_path);
    ASSERT_GE(poses.size(), 12U);
    int32_t header[3];
    std::memcpy(header, poses.data(), sizeof(header));
    ASSERT_EQ(header[0], 0x53504341);  // "ACPS"
    ASSERT_EQ(static_cast<size_t>(header[1]), bone_count);
    const size_t frame_floats = 1 + gfx::renderer::PhysicsFrame::kFloatCount + bone_count * 9;
    ASSERT_EQ(poses.size(), 12 + static_cast<size_t>(header[2]) * frame_floats * 4);
    const auto* stream = reinterpret_cast<const float*>(poses.data() + 12);

    FILE* out = std::fopen(out_path, "wb");
    ASSERT_NE(out, nullptr);
    std::vector<gfx::renderer::BoneAttribute> attributes(bone_count);
    gfx::renderer::ModelState state;
    std::vector<Byte> buffer;
    std::vector<float> record(bone_count * 16 + vertex_count * 3 + 2);
    const auto nan = std::numeric_limits<float>::quiet_NaN();
    for (int32_t frame_index = 0; frame_index < header[2]; ++frame_index) {
        const auto* frame = stream + frame_index * frame_floats;
        const auto physics = gfx::renderer::PhysicsFrame::Unpack(
            std::span<const float, gfx::renderer::PhysicsFrame::kFloatCount>(frame + 1, 21));
        const auto* values = frame + 1 + gfx::renderer::PhysicsFrame::kFloatCount;
        // Attributes come in baked (sorted) bone order, like the Java side's.
        for (size_t baked_index = 0; baked_index < bone_count; ++baked_index) {
            auto& attribute = attributes[baked_index];
            const auto* bone = values + sorted[baked_index] * 9;
            for (int axis = 0; axis < 3; ++axis) {
                attribute.rotation[axis] = bone[axis];
                attribute.position[axis] = bone[3 + axis];
                attribute.scale[axis] = bone[6 + axis];
            }
        }
        const auto start = std::chrono::steady_clock::now();
        auto extracted = state.Extract(kGenericTag, baked, attributes, attributes.size(), &physics);
        ASSERT_TRUE(extracted.ok()) << extracted.status();
        const auto extracted_at = std::chrono::steady_clock::now();
        buffer.resize(extracted->vertex_count * gfx::renderer::vertex::VanillaVertex::kSize);
        const auto status = gfx::renderer::Render(buffer, gfx::renderer::VertexKind::kVanilla, state,
                                                  IdentityParameters());
        ASSERT_TRUE(status.ok()) << status;
        const auto rendered_at = std::chrono::steady_clock::now();
        if (frame[0] == 0.0f) {
            continue;
        }
        std::fill(record.begin(), record.end(), nan);
        const auto view = state.PoseView();
        for (size_t baked_index = 0; baked_index < sorted.size(); ++baked_index) {
            if (view.bone_visited[baked_index] != 0) {
                std::memcpy(&record[sorted[baked_index] * 16], &view.bone_poses[baked_index].pose[0][0],
                            16 * sizeof(float));
            }
        }
        auto* positions = record.data() + bone_count * 16;
        for (uint32_t i = 0; i < extracted->vertex_count; ++i) {
            gfx::renderer::vertex::VanillaVertex vertex;
            std::memcpy(&vertex, buffer.data() + i * gfx::renderer::vertex::VanillaVertex::kSize,
                        gfx::renderer::vertex::VanillaVertex::kSize);
            const auto source = static_cast<size_t>(vertex.tex_uv[0]);
            if (vertex.tex_uv[1] != 0.5f || source >= vertex_count) {
                continue;  // collapsed quad (hidden joint)
            }
            std::memcpy(positions + source * 3, vertex.pos, 3 * sizeof(float));
        }
        record[record.size() - 2] =
            std::chrono::duration<float, std::milli>(extracted_at - start).count();
        record[record.size() - 1] =
            std::chrono::duration<float, std::milli>(rendered_at - extracted_at).count();
        ASSERT_EQ(std::fwrite(record.data(), sizeof(float), record.size(), out), record.size());
    }
    std::fclose(out);
}

gfx::renderer::PosedBodyCapsule UprightCapsule(float radius) {
    gfx::bake::BodyCapsule capsule{.bone = 0, .head = {0, 0, 0}, .tail = {0, 2, 0},
                                   .radius = radius, .tail_radius = radius};
    mat4 identity = GLM_MAT4_IDENTITY_INIT;
    gfx::renderer::PosedBodyCapsule posed;
    gfx::renderer::PoseBodyCapsule(capsule, identity, posed);
    return posed;
}

TEST(AntiClipTest, PushesOnlyBeyondTheRestDepth) {
    const std::array capsules{UprightCapsule(0.5f)};
    // 0.3 deep with 0.1 allowed: out to 0.4 from the axis.
    float x = 0.2f, y = 1.0f, z = 0.0f;
    gfx::renderer::PushOutOfBody(capsules, 1, 1.0f, 0.1f, 0.0f, x, y, z);
    EXPECT_NEAR(x, 0.4f, 1e-6f);
    EXPECT_EQ(y, 1.0f);
    EXPECT_EQ(z, 0.0f);
    // Half the share moves half the excess.
    x = 0.2f;
    gfx::renderer::PushOutOfBody(capsules, 1, 0.5f, 0.1f, 0.0f, x, y, z);
    EXPECT_NEAR(x, 0.3f, 1e-6f);
    // Within the allowance and outside the mask: bit for bit unchanged.
    const float inside = 0.45f;
    x = inside;
    gfx::renderer::PushOutOfBody(capsules, 1, 1.0f, 0.1f, 0.0f, x, y, z);
    EXPECT_EQ(std::memcmp(&x, &inside, sizeof(float)), 0);
    x = 0.2f;
    gfx::renderer::PushOutOfBody(capsules, 2, 1.0f, 0.0f, 0.0f, x, y, z);
    EXPECT_EQ(x, 0.2f);
    // A scaled pose scales the radius and the allowance.
    gfx::bake::BodyCapsule capsule{.bone = 0, .head = {0, 0, 0}, .tail = {0, 2, 0},
                                   .radius = 0.5f, .tail_radius = 0.5f};
    mat4 scaled = GLM_MAT4_IDENTITY_INIT;
    glm_scale_uni(scaled, 2.0f);
    std::array<gfx::renderer::PosedBodyCapsule, 1> big;
    gfx::renderer::PoseBodyCapsule(capsule, scaled, big[0]);
    x = 0.4f;
    y = 2.0f;
    gfx::renderer::PushOutOfBody(big, 1, 1.0f, 0.1f, 0.0f, x, y, z);
    EXPECT_NEAR(x, 0.8f, 1e-6f);
}

TEST(AntiClipTest, PushRampsInOverTheMargin) {
    const std::array capsules{UprightCapsule(0.5f)};
    // Limit 0.4 from the axis (allowance 0.1), ramp 2 * 0.05 = 0.1 deep.
    const auto pushed = [&](float from) {
        float x = from, y = 1.0f, z = 0.0f;
        gfx::renderer::PushOutOfBody(capsules, 1, 1.0f, 0.1f, 0.05f, x, y, z);
        return x;
    };
    // Within the allowance: bit for bit unchanged.
    const float inside = 0.45f;
    const float kept = pushed(inside);
    EXPECT_EQ(std::memcmp(&kept, &inside, sizeof(float)), 0);
    // Past the ramp: all the way out to the limit.
    EXPECT_NEAR(pushed(0.2f), 0.4f, 1e-6f);
    // In the ramp: part of the excess, more the deeper, never past the limit.
    const float shallow = pushed(0.38f);
    const float deeper = pushed(0.34f);
    EXPECT_GT(shallow, 0.38f);
    EXPECT_LT(shallow, 0.4f);
    EXPECT_LT(shallow - 0.38f, 0.02f * 0.5f);
    EXPECT_GT(deeper - 0.34f, shallow - 0.38f);
    EXPECT_LE(deeper, 0.4f);
    // Continuous where the ramp ends.
    EXPECT_NEAR(pushed(0.3001f), pushed(0.2999f), 1e-3f);
}

// Two pendulums one block long hang from (0, 2, 0) (A) and (-0.5, 2, 0) (B);
// their tips' pivots are the tails. A "leg" bone carries a sphere at
// (-0.35, 1, 0), radius 0.3, in A's chain only, so moving the leg +X pushes
// A's tail away from B. A link holds the tails within 1.1 of their rest
// distance; leg sync moves A's target out with a body capsule on the leg.
model_proto::GeoModel MakePendulumPair(bool link, bool sync) {
    model_proto::GeoModel model;
    model.bones.resize(6);
    const char* names[] = {"root", "a", "a_tip", "b", "b_tip", "leg"};
    const float pivots[][3] = {{0, 0, 0}, {0, 32, 0}, {0, 16, 0}, {-8, 32, 0}, {-8, 16, 0}, {0, 0, 0}};
    const char* parents[] = {"", "root", "a", "root", "b", "root"};
    for (size_t i = 0; i < model.bones.size(); ++i) {
        model.bones[i].name = names[i];
        if (i != 0) {
            model.bones[i].parent = parents[i];
        }
        model.bones[i].pivot = {pivots[i][0], pivots[i][1], pivots[i][2]};
        model.bones[i].rotate = {0, 0, 0};
    }
    auto& springs = model.springs.emplace();
    springs.chains.resize(2);
    springs.joints.push_back(
        model_proto::SpringJoint(1, {0, 1, 0}, 0.0f, 2.0f, 0.4f, 1.0f, {0, -1, 0}, 0));
    springs.joints.push_back(
        model_proto::SpringJoint(3, {-0.5f, 1, 0}, 0.0f, 2.0f, 0.4f, 1.0f, {0, -1, 0}, 1));
    springs.colliders.emplace_back(5, std::vector<float>{-0.35f, 1, 0},
                                   std::vector<float>{-0.35f, 1, 0}, 0.3f);
    springs.chains[0].colliders = {0};
    if (link) {
        springs.links.emplace_back(1, 3, 0.5f);
        springs.link_stretch = 0.1f;
        springs.link_compression = 0.35f;
    }
    springs.body.emplace_back(5, std::vector<float>{-0.3f, 0.5f, 0}, std::vector<float>{-0.3f, 1.5f, 0},
                              0.1f, 0.1f);
    if (sync) {
        springs.joints[0].sync = 1;
        springs.joints[0].sync_share = 1.0f;
    }
    return model;
}

// Tail positions (x of A's and B's tip pivots) after 120 frames at 60 Hz with
// the leg moved leg_x blocks along +X.
std::array<float, 2> PendulumTails(const model_proto::GeoModel& model, float leg_x) {
    const auto baked = BakeOpaque(model);
    EXPECT_NE(baked, nullptr);
    std::vector<gfx::renderer::BoneAttribute> attributes(6);
    for (auto& attribute : attributes) {
        attribute.scale[0] = attribute.scale[1] = attribute.scale[2] = 1.0f;
    }
    const auto& sorted = baked->Bones().sorted_bone_indices;
    const auto baked_index = [&](uint16_t original) {
        size_t index = 0;
        while (sorted[index] != original) {
            ++index;
        }
        return index;
    };
    attributes[baked_index(5)].position[0] = -leg_x * 16.0f;  // +X
    gfx::renderer::ModelState state;
    gfx::renderer::PhysicsFrame frame;
    frame.delta_seconds = 1.0f / 60.0f;
    for (int i = 0; i < 120; ++i) {
        EXPECT_TRUE(state.Extract(kGenericTag, baked, attributes, attributes.size(), &frame).ok());
    }
    std::array<float, 2> out{};
    for (int side = 0; side < 2; ++side) {
        const auto& pose = state.PoseView().bone_poses[baked_index(side == 0 ? 2 : 4)].pose;
        const float rest_x = side == 0 ? 0.0f : -0.5f;
        out[side] = pose[0][0] * rest_x + pose[1][0] * 1.0f + pose[3][0];
    }
    return out;
}

TEST(AntiClipTest, LinksKeepNeighbouringTailsTogether) {
    InitCpuInfo();
    // At rest the links change nothing.
    const auto rest_free = PendulumTails(MakePendulumPair(false, false), 0.0f);
    const auto rest_linked = PendulumTails(MakePendulumPair(true, false), 0.0f);
    EXPECT_EQ(std::memcmp(rest_free.data(), rest_linked.data(), sizeof(rest_free)), 0);
    EXPECT_NEAR(rest_free[0] - rest_free[1], 0.5f, 1e-3f);
    // The leg pushes A 0.25 away from B: alone the gap opens, linked B follows.
    const auto free = PendulumTails(MakePendulumPair(false, false), 0.3f);
    const auto linked = PendulumTails(MakePendulumPair(true, false), 0.3f);
    EXPECT_GT(free[0] - free[1], 0.7f);
    EXPECT_LT(linked[0] - linked[1], 0.62f);
    EXPECT_GT(linked[1], -0.45f);
    // The baked cache keeps the links.
    const auto baked = BakeOpaque(MakePendulumPair(true, false));
    auto restored = gfx::bake::ReadBakedModel(gfx::bake::SerializeBakedModel(*baked));
    ASSERT_TRUE(restored.ok()) << restored.status();
    EXPECT_EQ((*restored)->Springs().links.size(), 1U);
    EXPECT_EQ((*restored)->Springs().link_stretch, 0.1f);
}

TEST(AntiClipTest, LinksHoldAgainstAColliderBetweenTheTails) {
    InitCpuInfo();
    // A collider both chains test sits between the tails and pushes them
    // apart (0.35 from its centre each, 0.7 apart): alone they stay out of it,
    // linked the links have the last word and keep them within rest * 1.1.
    const auto between = [](bool link) {
        auto model = MakePendulumPair(link, false);
        auto& collider = model.springs->colliders[0];
        collider.head = {-0.25f, 1, 0};
        collider.tail = {-0.25f, 1, 0};
        collider.radius = 0.35f;
        model.springs->chains[1].colliders = {0};
        return PendulumTails(model, 0.0f);
    };
    const auto free = between(false);
    const auto linked = between(true);
    EXPECT_GT(free[0] - free[1], 0.65f);
    EXPECT_LT(linked[0] - linked[1], 0.5f * 1.1f + 0.01f);
}

TEST(AntiClipTest, SyncMovesTheTargetOutWithTheLeg) {
    InitCpuInfo();
    // Without the collider in the way (leg moved away from A at rest), sync
    // keeps A's tail at its rest distance from the moved capsule.
    auto plain = MakePendulumPair(false, false);
    auto synced = MakePendulumPair(false, true);
    plain.springs->chains[0].colliders.clear();
    synced.springs->chains[0].colliders.clear();
    EXPECT_EQ(std::memcmp(PendulumTails(plain, 0.0f).data(), PendulumTails(synced, 0.0f).data(),
                          2 * sizeof(float)),
              0);
    const auto moved_plain = PendulumTails(plain, 0.2f);
    const auto moved_synced = PendulumTails(synced, 0.2f);
    EXPECT_LT(std::abs(moved_plain[0]), 0.02f);
    EXPECT_GT(moved_synced[0], 0.08f);
    // B has no sync.
    EXPECT_EQ(moved_plain[1], moved_synced[1]);
}

TEST(AntiClipTest, RejectsMalformedLinks) {
    InitCpuInfo();
    std::array<gfx::bake::Pixel, 4> pixels{};
    gfx::bake::Texture texture(pixels.data(), 2, 2);
    const auto bakes = [&](const model_proto::GeoModel& model) {
        std::string protobuf;
        struct_pb::to_pb(model, protobuf);
        return gfx::bake::BakeModel(StrBuf(protobuf), texture, {.origin_ver = 29}).ok();
    };
    EXPECT_TRUE(bakes(MakePendulumPair(true, true)));
    auto model = MakePendulumPair(true, false);
    model.springs->links[0].b = 2;  // not a joint's bone
    EXPECT_FALSE(bakes(model));
    model = MakePendulumPair(true, false);
    model.springs->links[0].b = 1;  // itself
    EXPECT_FALSE(bakes(model));
    model = MakePendulumPair(true, false);
    model.springs->link_compression = 1.5f;
    EXPECT_FALSE(bakes(model));
    model = MakePendulumPair(false, true);
    model.springs->joints[0].sync = 2;  // no capsule 1
    EXPECT_FALSE(bakes(model));
}

// "root"; "body" carries an upright capsule of radius 0.5 blocks from the
// origin to 2 blocks up; "cloth" (its own bone) carries a triangle at x = 0.6.
model_proto::GeoModel MakeClothBesideBody(bool anti_clip) {
    model_proto::GeoModel model;
    model.bones.resize(3);
    model.bones[0].name = "root";
    model.bones[1].name = "body";
    model.bones[1].parent = "root";
    model.bones[2].name = "cloth";
    model.bones[2].parent = "root";
    for (auto& bone : model.bones) {
        bone.pivot = {0, 0, 0};
        bone.rotate = {0, 0, 0};
    }
    auto& mesh = model.mesh.emplace();
    mesh.positions = {0.6f, 0.5f, 0.0f, 0.6f, 1.5f, 0.0f, 0.6f, 1.0f, 0.1f};
    mesh.normals = {1, 0, 0, 1, 0, 0, 1, 0, 0};
    mesh.uvs = {0, 0, 1, 0, 0, 1};
    mesh.joints = {2, 0, 0, 0, 2, 0, 0, 0, 2, 0, 0, 0};
    mesh.weights = {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};
    mesh.triangles = {0, 1, 2};
    auto& springs = model.springs.emplace();
    if (anti_clip) {
        springs.body.emplace_back(1, std::vector<float>{0, 0, 0}, std::vector<float>{0, 2, 0},
                                  0.5f, 0.5f);
        springs.push_share = {1.0f, 1.0f, 1.0f};
        springs.push_allowance = {0.0f, 0.0f, 0.0f};
        springs.push_capsules = {1, 1, 1};
    }
    return model;
}

std::vector<gfx::renderer::vertex::VanillaVertex> Draw(
    const std::shared_ptr<gfx::bake::BakedModel>& baked, float cloth_shift) {
    std::vector<gfx::renderer::BoneAttribute> attributes(3);
    for (auto& attribute : attributes) {
        attribute.scale[0] = attribute.scale[1] = attribute.scale[2] = 1.0f;
    }
    attributes[2].position[0] = cloth_shift * 16.0f;  // moves -X
    gfx::renderer::ModelState state;
    auto extracted = state.Extract(kGenericTag, baked, attributes, attributes.size());
    EXPECT_TRUE(extracted.ok()) << extracted.status();
    std::vector<Byte> output(extracted->vertex_count * gfx::renderer::vertex::VanillaVertex::kSize);
    EXPECT_TRUE(gfx::renderer::Render(output, gfx::renderer::VertexKind::kVanilla, state,
                                      IdentityParameters())
                    .ok());
    std::vector<gfx::renderer::vertex::VanillaVertex> vertices(extracted->vertex_count);
    std::memcpy(vertices.data(), output.data(), output.size());
    return vertices;
}

TEST(AntiClipTest, RenderPushesSkinnedClothOutAndKeepsTheRestPose) {
    InitCpuInfo();
    const auto plain = BakeOpaque(MakeClothBesideBody(false));
    const auto guarded = BakeOpaque(MakeClothBesideBody(true));
    ASSERT_NE(plain, nullptr);
    ASSERT_NE(guarded, nullptr);
    // Rest: identical output.
    const auto rest_plain = Draw(plain, 0.0f);
    const auto rest_guarded = Draw(guarded, 0.0f);
    ASSERT_EQ(rest_plain.size(), rest_guarded.size());
    EXPECT_EQ(std::memcmp(rest_plain.data(), rest_guarded.data(),
                          rest_plain.size() * sizeof(rest_plain[0])),
              0);
    // The cloth moves 0.3 into the body: the guarded copy stays on its surface.
    const auto moved_plain = Draw(plain, 0.3f);
    const auto moved_guarded = Draw(guarded, 0.3f);
    for (size_t i = 0; i < moved_plain.size(); ++i) {
        EXPECT_NEAR(moved_plain[i].pos[0], 0.3f, 1e-5f);
        EXPECT_NEAR(std::hypot(moved_guarded[i].pos[0], moved_guarded[i].pos[2]), 0.5f, 1e-5f);
        EXPECT_EQ(moved_guarded[i].pos[1], moved_plain[i].pos[1]);
        EXPECT_EQ(moved_guarded[i].normal, moved_plain[i].normal);
    }
    // The baked cache keeps the push-out.
    auto restored = gfx::bake::ReadBakedModel(gfx::bake::SerializeBakedModel(*guarded));
    ASSERT_TRUE(restored.ok()) << restored.status();
    const auto again = Draw(std::shared_ptr<gfx::bake::BakedModel>(std::move(*restored)), 0.3f);
    ASSERT_EQ(again.size(), moved_guarded.size());
    EXPECT_EQ(std::memcmp(again.data(), moved_guarded.data(), again.size() * sizeof(again[0])), 0);
}

TEST(AntiClipTest, RejectsMalformedPushOut) {
    InitCpuInfo();
    auto model = MakeClothBesideBody(true);
    model.springs->push_capsules[1] = 2;  // no capsule 1
    std::array<gfx::bake::Pixel, 4> pixels{};
    gfx::bake::Texture texture(pixels.data(), 2, 2);
    std::string protobuf;
    struct_pb::to_pb(model, protobuf);
    EXPECT_FALSE(gfx::bake::BakeModel(StrBuf(protobuf), texture, {.origin_ver = 29}).ok());
    model = MakeClothBesideBody(true);
    model.springs->push_share.pop_back();
    protobuf.clear();
    struct_pb::to_pb(model, protobuf);
    EXPECT_FALSE(gfx::bake::BakeModel(StrBuf(protobuf), texture, {.origin_ver = 29}).ok());
}

// A horizontal hair (pivot 2 blocks up, rest tail 1 block along +X) falls
// under gravity toward a pole capsule standing below its pivot.
model_proto::GeoModel MakeHairOverPole(float allowance) {
    model_proto::GeoModel model;
    model.bones.resize(3);
    model.bones[0].name = "root";
    model.bones[0].pivot = {0, 0, 0};
    model.bones[1].name = "hair";
    model.bones[1].parent = "root";
    model.bones[1].pivot = {0, 32, 0};
    model.bones[2].name = "tip";
    model.bones[2].parent = "hair";
    model.bones[2].pivot = {16, 32, 0};
    for (auto& bone : model.bones) {
        bone.rotate = {0, 0, 0};
    }
    auto& springs = model.springs.emplace();
    springs.chains.resize(1);
    springs.joints.push_back(
        model_proto::SpringJoint(1, {1, 2, 0}, 0.0f, 0.0f, 0.4f, 4.0f, {0, -1, 0}, 0));
    springs.body.emplace_back(0, std::vector<float>{0, 0, 0}, std::vector<float>{0, 1, 0},
                              0.2f, 0.2f);
    if (allowance >= 0.0f) {
        springs.joints[0].backstop = 1;
        springs.joints[0].backstop_allowance = allowance;
    }
    return model;
}

float TailDistanceFromPoleTop(const model_proto::GeoModel& model) {
    const auto baked = BakeOpaque(model);
    EXPECT_NE(baked, nullptr);
    std::vector<gfx::renderer::BoneAttribute> attributes(3);
    for (auto& attribute : attributes) {
        attribute.scale[0] = attribute.scale[1] = attribute.scale[2] = 1.0f;
    }
    gfx::renderer::ModelState state;
    gfx::renderer::PhysicsFrame frame;
    frame.delta_seconds = 1.0f / 60.0f;
    for (int i = 0; i < 240; ++i) {
        EXPECT_TRUE(state.Extract(kGenericTag, baked, attributes, attributes.size(), &frame).ok());
    }
    const auto& sorted = baked->Bones().sorted_bone_indices;
    size_t tip = 0;
    while (sorted[tip] != 2) {
        ++tip;
    }
    const auto& pose = state.PoseView().bone_poses[tip].pose;
    // The tip bone's pivot is the hair's tail.
    const float x = pose[0][0] * 1.0f + pose[1][0] * 2.0f + pose[3][0];
    const float y = pose[0][1] * 1.0f + pose[1][1] * 2.0f + pose[3][1];
    return std::hypot(x, y - 1.0f);
}

TEST(AntiClipTest, BackstopKeepsTheTailNearItsAnimatedDistance) {
    InitCpuInfo();
    // Animated tail (1, 2) is sqrt(2) from the pole's top. Draws interpolate
    // between the last two steps and the length constraint follows each push,
    // so the drawn tail may sit a few millimetres inside the bound.
    const float animated = std::sqrt(2.0f);
    EXPECT_LT(TailDistanceFromPoleTop(MakeHairOverPole(-1.0f)), 0.5f);
    EXPECT_GT(TailDistanceFromPoleTop(MakeHairOverPole(0.2f)), animated - 0.2f - 5e-3f);
    EXPECT_GT(TailDistanceFromPoleTop(MakeHairOverPole(0.0f)), animated - 5e-3f);
}

// Pendulum B of the pair (no colliders), half a block off the vertical axis
// through the origin: 60 frames at rest at 60 Hz, then `frames` frames each
// turning the model `degrees` about that axis. Returns B's drawn tail.
std::array<float, 3> TurnedTail(float turn_limit, float damping, float degrees, int frames) {
    auto model = MakePendulumPair(false, false);
    model.springs->chains[0].colliders.clear();
    model.springs->inertia_turn_limit = turn_limit;
    model.springs->inertia_damping = damping;
    const auto baked = BakeOpaque(model);
    EXPECT_NE(baked, nullptr);
    std::vector<gfx::renderer::BoneAttribute> attributes(6);
    for (auto& attribute : attributes) {
        attribute.scale[0] = attribute.scale[1] = attribute.scale[2] = 1.0f;
    }
    gfx::renderer::ModelState state;
    gfx::renderer::PhysicsFrame frame;
    frame.delta_seconds = 1.0f / 60.0f;
    for (int i = 0; i < 60; ++i) {
        EXPECT_TRUE(state.Extract(kGenericTag, baked, attributes, attributes.size(), &frame).ok());
    }
    // World-fixed points turn the other way in model space.
    const float angle = -degrees * 3.14159265f / 180.0f;
    frame.motion[0][0] = frame.motion[2][2] = std::cos(angle);
    frame.motion[0][2] = -std::sin(angle);
    frame.motion[2][0] = std::sin(angle);
    for (int i = 0; i < frames; ++i) {
        EXPECT_TRUE(state.Extract(kGenericTag, baked, attributes, attributes.size(), &frame).ok());
    }
    const auto& sorted = baked->Bones().sorted_bone_indices;
    size_t tip = 0;
    while (sorted[tip] != 4) {
        ++tip;
    }
    const auto& pose = state.PoseView().bone_poses[tip].pose;
    std::array<float, 3> out{};
    for (int row = 0; row < 3; ++row) {
        out[row] = pose[0][row] * -0.5f + pose[1][row] * 1.0f + pose[3][row];
    }
    return out;
}

float TurnLag(const std::array<float, 3>& tail) {
    return std::hypot(tail[0] + 0.5f, tail[1] - 1.0f, tail[2]);
}

TEST(AntiClipTest, InertiaLimitCarriesFastTurnsWithTheModel) {
    InitCpuInfo();
    constexpr float kLimit = 6.2831853f;  // 360 degrees per second
    // Under the limit (120 deg/s) nothing changes.
    const auto slow = TurnedTail(0.0f, 0.0f, 2.0f, 20);
    const auto slow_limited = TurnedTail(kLimit, 0.0f, 2.0f, 20);
    EXPECT_EQ(std::memcmp(slow.data(), slow_limited.data(), sizeof(slow)), 0);
    EXPECT_GT(TurnLag(slow), 1e-3f);
    // A snap turn (1200 deg/s for 0.1 s) leaves the free tail far behind; the
    // limit carries the excess with the model, so B lags much less.
    const auto free = TurnLag(TurnedTail(0.0f, 0.0f, 20.0f, 6));
    const auto limited = TurnLag(TurnedTail(kLimit, 0.0f, 20.0f, 6));
    EXPECT_GT(free, 0.1f);
    EXPECT_LT(limited, 0.5f * free);
    // Full damping carries every turn: the tail stays where it hangs.
    EXPECT_LT(TurnLag(TurnedTail(0.0f, 1.0f, 20.0f, 6)), 1e-4f);
    // The baked cache keeps the limits.
    auto model = MakePendulumPair(false, false);
    model.springs->inertia_turn_limit = kLimit;
    model.springs->inertia_move_limit = 10.0f;
    model.springs->inertia_damping = 0.25f;
    auto restored = gfx::bake::ReadBakedModel(gfx::bake::SerializeBakedModel(*BakeOpaque(model)));
    ASSERT_TRUE(restored.ok()) << restored.status();
    EXPECT_EQ((*restored)->Springs().inertia_turn_limit, kLimit);
    EXPECT_EQ((*restored)->Springs().inertia_move_limit, 10.0f);
    EXPECT_EQ((*restored)->Springs().inertia_damping, 0.25f);
}

TEST(AntiClipTest, RejectsMalformedInertiaLimits) {
    InitCpuInfo();
    std::array<gfx::bake::Pixel, 4> pixels{};
    gfx::bake::Texture texture(pixels.data(), 2, 2);
    const auto bakes = [&](float turn, float move, float damping) {
        auto model = MakePendulumPair(false, false);
        model.springs->inertia_turn_limit = turn;
        model.springs->inertia_move_limit = move;
        model.springs->inertia_damping = damping;
        std::string protobuf;
        struct_pb::to_pb(model, protobuf);
        return gfx::bake::BakeModel(StrBuf(protobuf), texture, {.origin_ver = 29}).ok();
    };
    EXPECT_TRUE(bakes(6.0f, 10.0f, 0.5f));
    EXPECT_FALSE(bakes(-1.0f, 0.0f, 0.0f));
    EXPECT_FALSE(bakes(0.0f, std::nanf(""), 0.0f));
    EXPECT_FALSE(bakes(0.0f, 0.0f, 1.5f));
    EXPECT_FALSE(bakes(INFINITY, 0.0f, 0.0f));
}
}  // namespace
}  // namespace ysm::test
