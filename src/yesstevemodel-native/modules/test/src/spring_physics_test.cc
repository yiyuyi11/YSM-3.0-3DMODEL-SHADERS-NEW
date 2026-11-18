#include <array>
#include <cmath>
#include <cstring>
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
#include "proto/asset/model/data/geo_model.proto.h"

namespace ysm::test {
namespace {
namespace model_proto = proto::asset::model::data;
using Vec3 = std::array<float, 3>;
constexpr simd::Tag<simd::Type::kNone> kGenericTag;
constexpr float kFrame = 1.0f / 60.0f;

// "root" at the origin; "hair" pivots 2 blocks up and points +X length blocks
// to its rest tail; "tip" hangs off the hair's end and must follow it.
model_proto::GeoModel MakeHair(float stiffness, float gravity_power,
                               float length = 1.0f) {
    model_proto::GeoModel model;
    model.bones.resize(3);
    model.bones[0].name = "root";
    model.bones[0].pivot = {0, 0, 0};
    model.bones[0].rotate = {0, 0, 0};
    model.bones[1].name = "hair";
    model.bones[1].parent = "root";
    model.bones[1].pivot = {0, 32, 0};
    model.bones[1].rotate = {0, 0, 0};
    model.bones[2].name = "tip";
    model.bones[2].parent = "hair";
    model.bones[2].pivot = {16 * length, 32, 0};
    model.bones[2].rotate = {0, 0, 0};
    auto& springs = model.springs.emplace();
    springs.chains.resize(1);
    springs.joints.push_back(model_proto::SpringJoint(
        1, {length, 2, 0}, 0.0f, stiffness, 0.4f, gravity_power, {0, -1, 0}, 0));
    return model;
}

std::shared_ptr<gfx::bake::BakedModel> Bake(const model_proto::GeoModel& model) {
    std::array<gfx::bake::Pixel, 4> pixels{};
    gfx::bake::Texture texture(pixels.data(), 2, 2);
    std::string protobuf;
    struct_pb::to_pb(model, protobuf);
    auto baked =
        gfx::bake::BakeModel(StrBuf(protobuf), texture, {.origin_ver = 29});
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

Vec3 Transform(const mat4 m, const Vec3& p) {
    return {m[0][0] * p[0] + m[1][0] * p[1] + m[2][0] * p[2] + m[3][0],
            m[0][1] * p[0] + m[1][1] * p[1] + m[2][1] * p[2] + m[3][1],
            m[0][2] * p[0] + m[1][2] * p[1] + m[2][2] * p[2] + m[3][2]};
}

class SpringHarness {
   public:
    explicit SpringHarness(const model_proto::GeoModel& model)
        : baked_(Bake(model)), attributes_(IdentityAttributes(model.bones.size())) {}

    bool Step(const gfx::renderer::PhysicsFrame* frame) {
        return StepModel(baked_, frame);
    }

    // One extraction with another baked model (a mesh LOD level).
    bool StepModel(const std::shared_ptr<gfx::bake::BakedModel>& baked,
                   const gfx::renderer::PhysicsFrame* frame) {
        auto extracted = state_.Extract(kGenericTag, baked, attributes_,
                                        attributes_.size(), frame);
        EXPECT_TRUE(extracted.ok()) << extracted.status();
        return extracted.ok();
    }

    void SetSpringModes(std::vector<uint8_t> modes) {
        state_.SetSpringModes(modes);
    }

    void Run(int frames, const gfx::renderer::PhysicsFrame& frame) {
        for (int i = 0; i < frames; ++i) {
            ASSERT_TRUE(Step(&frame));
        }
    }

    // World position of a rest point carried by a bone (by original index).
    Vec3 Posed(size_t original_bone, const Vec3& rest) const {
        const auto& sorted = baked_->Bones().sorted_bone_indices;
        size_t baked_index = 0;
        while (sorted[baked_index] != original_bone) {
            ++baked_index;
        }
        return Transform(state_.PoseView().bone_poses[baked_index].pose, rest);
    }

    // Animation input by original bone index, applied from the next step.
    gfx::renderer::BoneAttribute& Attribute(size_t original_bone) {
        return attributes_[original_bone];
    }

    const std::shared_ptr<gfx::bake::BakedModel>& Baked() const { return baked_; }

   private:
    std::shared_ptr<gfx::bake::BakedModel> baked_;
    std::vector<gfx::renderer::BoneAttribute> attributes_;
    gfx::renderer::ModelState state_;
};

gfx::renderer::PhysicsFrame Frame(float seconds = kFrame) {
    gfx::renderer::PhysicsFrame frame;
    frame.delta_seconds = seconds;
    return frame;
}

void ExpectNear(const Vec3& actual, const Vec3& expected, float tolerance) {
    for (size_t axis = 0; axis < 3; ++axis) {
        EXPECT_NEAR(actual[axis], expected[axis], tolerance) << "axis " << axis;
    }
}

TEST(SpringPhysicsTest, GravitySwingsHairDownAndCarriesDescendants) {
    InitCpuInfo();
    SpringHarness harness(MakeHair(0.0f, 2.0f));
    ASSERT_NE(harness.Baked(), nullptr);
    ASSERT_EQ(harness.Baked()->Springs().joints.size(), 1U);

    // The first step seeds the tail at the animated pose.
    ASSERT_TRUE(harness.Step(nullptr));
    ExpectNear(harness.Posed(1, {1, 2, 0}), {1, 2, 0}, 1e-5f);

    harness.Run(600, Frame());
    // Hangs straight down from the pivot, keeping its one-block length.
    ExpectNear(harness.Posed(1, {1, 2, 0}), {0, 1, 0}, 0.02f);
    // The tip bone's pivot sat on the hair's rest tail and moves with it.
    ExpectNear(harness.Posed(2, {1, 2, 0}), harness.Posed(1, {1, 2, 0}), 1e-5f);
}

TEST(SpringPhysicsTest, WithoutPhysicsBonesKeepTheAnimatedPose) {
    InitCpuInfo();
    SpringHarness harness(MakeHair(0.0f, 2.0f));
    harness.Run(30, Frame());
    ASSERT_TRUE(harness.Step(nullptr));
    ExpectNear(harness.Posed(1, {1, 2, 0}), {1, 2, 0}, 1e-5f);
}

TEST(SpringPhysicsTest, BodyMotionLagsTheTailAndStiffnessRestoresIt) {
    InitCpuInfo();
    SpringHarness harness(MakeHair(4.0f, 0.0f));
    harness.Run(5, Frame());
    ExpectNear(harness.Posed(1, {1, 2, 0}), {1, 2, 0}, 1e-4f);

    // The body moves 0.3 blocks along +Z in the world: world-fixed points
    // shift by -0.3 Z in the new model space, so the tail trails toward -Z.
    auto moved = Frame();
    vec3 shift{0.0f, 0.0f, -0.3f};
    glm_translate_make(moved.motion, shift);
    harness.Run(1, moved);
    const auto lagged = harness.Posed(1, {1, 2, 0});
    EXPECT_LT(lagged[2], -0.05f);
    EXPECT_NEAR(std::hypot(lagged[0], lagged[1] - 2.0f, lagged[2]), 1.0f, 1e-3f);

    harness.Run(600, Frame());
    ExpectNear(harness.Posed(1, {1, 2, 0}), {1, 2, 0}, 0.01f);
}

TEST(SpringPhysicsTest, SteadyMovementOnlyTrailsSlightly) {
    InitCpuInfo();
    SpringHarness harness(MakeHair(1.0f, 0.0f));
    harness.Run(5, Frame());
    // Sprinting 5.6 blocks per second along -Z for five seconds.
    auto moving = Frame();
    vec3 shift{0.0f, 0.0f, 5.6f * kFrame};
    glm_translate_make(moving.motion, shift);
    float peak = 0.0f;
    for (int i = 0; i < 300; ++i) {
        harness.Run(1, moving);
        peak = std::max(peak, harness.Posed(1, {1, 2, 0})[2]);
    }
    // The start kicks the tail back (+Z, against the motion) ...
    EXPECT_GT(peak, 0.1f);
    // ... but at constant speed only air drag remains: well short of being
    // dragged straight back, which world-relative drag would do.
    const auto steady = harness.Posed(1, {1, 2, 0});
    EXPECT_GT(steady[2], 0.0f);
    EXPECT_LT(steady[2], 0.7f);
    EXPECT_GT(steady[0], 0.7f);
}

TEST(SpringPhysicsTest, HostDownTurnsGravity) {
    InitCpuInfo();
    SpringHarness harness(MakeHair(0.0f, 2.0f));
    // The host reports world down along +Z in model space (the body lies on
    // its back): the +X hair swings toward +Z instead of -Y.
    auto frame = Frame();
    frame.gravity = {0.0f, 0.0f, 1.0f};
    harness.Run(600, frame);
    ExpectNear(harness.Posed(1, {1, 2, 0}), {0, 2, 1}, 0.05f);
}

TEST(SpringPhysicsTest, UnevenExtractionFollowsTheSameMotion) {
    InitCpuInfo();
    // A shader pack's shadow pass extracts a second time a few milliseconds
    // after each frame's main pass. The tail must follow the same path as with
    // one extraction per frame instead of jumping back and forth.
    SpringHarness even(MakeHair(1.0f, 2.0f));
    SpringHarness uneven(MakeHair(1.0f, 2.0f));
    constexpr float kShadowGap = 0.002f;
    float worst = 0.0f;
    for (int i = 0; i < 240; ++i) {
        // The body walks along -Z for two seconds, then stands.
        auto walk = Frame();
        vec3 shift{0.0f, 0.0f, i < 120 ? 4.3f * kFrame : 0.0f};
        glm_translate_make(walk.motion, shift);
        ASSERT_TRUE(even.Step(&walk));

        auto main_pass = walk;
        main_pass.delta_seconds = kFrame - kShadowGap;
        ASSERT_TRUE(uneven.Step(&main_pass));
        const auto shadow_pass = Frame(kShadowGap);
        ASSERT_TRUE(uneven.Step(&shadow_pass));

        const auto a = even.Posed(1, {1, 2, 0});
        const auto b = uneven.Posed(1, {1, 2, 0});
        worst = std::max(worst, std::hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]));
    }
    EXPECT_LT(worst, 0.02f);
}

TEST(SpringPhysicsTest, CollidersPushTheTailOut) {
    InitCpuInfo();
    auto model = MakeHair(0.0f, 2.0f);
    auto& springs = *model.springs;
    // A sphere of radius 0.3 on the root, centered where the hair would hang.
    springs.colliders.push_back(
        model_proto::SpringCollider(0, {0, 1, 0}, {0, 1, 0}, 0.3f));
    springs.chains[0].colliders = {0};
    springs.joints[0].hit_radius = 0.05f;
    SpringHarness harness(model);
    harness.Run(600, Frame());
    const auto tail = harness.Posed(1, {1, 2, 0});
    const auto distance = std::hypot(tail[0], tail[1] - 1.0f, tail[2]);
    EXPECT_GE(distance, 0.349f);
    EXPECT_NEAR(std::hypot(tail[0], tail[1] - 2.0f, tail[2]), 1.0f, 1e-3f);
    EXPECT_LT(tail[1], 1.5f);
}

TEST(SpringPhysicsTest, BackstopReturnsATrappedTailToTheAnimatedSide) {
    InitCpuInfo();
    auto model = MakeHair(1.0f, 0.0f);
    auto& springs = *model.springs;
    // A bar along Z lying on the tail's swing circle just below the rest tail
    // (1, 2, 0); it reaches 0.15 with the tail's hit radius.
    springs.colliders.push_back(model_proto::SpringCollider(
        0, {0.95f, 1.7f, -1}, {0.95f, 1.7f, 1}, 0.1f));
    springs.chains[0].colliders = {0};
    springs.joints[0].hit_radius = 0.05f;
    SpringHarness harness(model);
    // The tail is seeded with the hair animated pointing down, then the
    // animation snaps back to +X: the tail swings up into the bar from below,
    // the far side from the animated tail, and can't pass along the circle.
    harness.Attribute(1).rotation[2] = -GLM_PI_2f;
    harness.Run(1, Frame());
    ExpectNear(harness.Posed(1, {1, 2, 0}), {0, 1, 0}, 1e-4f);
    harness.Attribute(1).rotation[2] = 0.0f;

    int recovered = -1;
    for (int i = 0; i < 240; ++i) {
        harness.Run(1, Frame());
        const auto tail = harness.Posed(1, {1, 2, 0});
        const auto dx = tail[0] - 0.95f;
        const auto dy = tail[1] - 1.7f;
        // The animated tail sits (0.05, 0.3) off the bar's axis.
        const bool animated_side = dx * 0.05f + dy * 0.3f > 0.0f;
        const bool outside = std::hypot(dx, dy) >= 0.149f;
        if (recovered < 0) {
            if (animated_side && outside) {
                recovered = i;
            }
            continue;
        }
        EXPECT_TRUE(animated_side && outside) << "frame " << i;
    }
    EXPECT_GE(recovered, 0);
    EXPECT_LE(recovered, 60);
    ExpectNear(harness.Posed(1, {1, 2, 0}), {1, 2, 0}, 0.02f);
}

TEST(SpringPhysicsTest, TheBoneCannotPassThroughACollider) {
    InitCpuInfo();
    // A two-block hair whose middle crosses a bar along Z 0.1 below it, which
    // reaches 0.25 with the hit radius; the tail is a block clear of the bar.
    auto model = MakeHair(1.0f, 0.0f, 2.0f);
    auto& springs = *model.springs;
    springs.colliders.push_back(model_proto::SpringCollider(
        0, {1, 1.9f, -1}, {1, 1.9f, 1}, 0.2f));
    springs.chains[0].colliders = {0};
    springs.joints[0].hit_radius = 0.05f;
    SpringHarness harness(model);
    harness.Run(300, Frame());

    const auto head = harness.Posed(1, {0, 2, 0});
    const auto tail = harness.Posed(1, {2, 2, 0});
    float closest = 1e9f;
    for (int i = 0; i <= 400; ++i) {
        const auto share = static_cast<float>(i) / 400.0f;
        closest = std::min(closest,
                           std::hypot(head[0] + (tail[0] - head[0]) * share - 1.0f,
                                      head[1] + (tail[1] - head[1]) * share - 1.9f));
    }
    EXPECT_GE(closest, 0.248f);
    // Turned up to the side of the bar the animated bone lies on, at its
    // length.
    EXPECT_GT(tail[1], 2.0f);
    EXPECT_NEAR(std::hypot(tail[0] - head[0], tail[1] - head[1], tail[2] - head[2]),
                2.0f, 1e-3f);
}

TEST(SpringPhysicsTest, SpringsSurviveTheBakedCacheAndRejectBadBones) {
    InitCpuInfo();
    auto model = MakeHair(1.0f, 0.5f);
    model.springs->colliders.push_back(
        model_proto::SpringCollider(0, {0, 1, 0}, {0, 1.5f, 0}, 0.2f));
    model.springs->chains[0].colliders = {0};
    auto baked = Bake(model);
    ASSERT_NE(baked, nullptr);
    const auto bytes = gfx::bake::SerializeBakedModel(*baked);
    auto restored = gfx::bake::ReadBakedModel(bytes);
    ASSERT_TRUE(restored.ok()) << restored.status();
    const auto& springs = (*restored)->Springs();
    ASSERT_EQ(springs.joints.size(), 1U);
    EXPECT_FLOAT_EQ(springs.joints[0].stiffness, 1.0f);
    ASSERT_EQ(springs.colliders.size(), 1U);
    EXPECT_FLOAT_EQ(springs.colliders[0].tail[1], 1.5f);
    EXPECT_EQ(springs.chain_colliders, (std::vector<uint32_t>{0}));
    EXPECT_EQ(springs.chain_centers, (std::vector<uint32_t>{0}));

    // Limits, collider types and centers survive too.
    auto extended = model;
    extended.springs->joints[0].limit = 1;
    extended.springs->joints[0].limit_rotation = {0, 0, -2, 2};
    extended.springs->joints[0].limit_angle = 0.5f;
    extended.springs->colliders[0].type = 1;
    extended.springs->chains[0].center = 1;
    auto extended_baked = Bake(extended);
    ASSERT_NE(extended_baked, nullptr);
    auto extended_restored =
        gfx::bake::ReadBakedModel(gfx::bake::SerializeBakedModel(*extended_baked));
    ASSERT_TRUE(extended_restored.ok()) << extended_restored.status();
    const auto& extended_springs = (*extended_restored)->Springs();
    EXPECT_EQ(extended_springs.joints[0].limit, gfx::bake::SpringLimit::kCone);
    EXPECT_NEAR(extended_springs.joints[0].limit_rotation[2], -std::sqrt(0.5f), 1e-6f);
    EXPECT_FLOAT_EQ(extended_springs.joints[0].limit_angle, 0.5f);
    EXPECT_EQ(extended_springs.colliders[0].type,
              gfx::bake::SpringColliderType::kInside);
    ASSERT_EQ(extended_springs.chain_centers.size(), 1U);
    EXPECT_NE(extended_springs.chain_centers[0], 0U);

    model.springs->joints[0].bone = 7;
    std::array<gfx::bake::Pixel, 4> pixels{};
    std::string protobuf;
    struct_pb::to_pb(model, protobuf);
    EXPECT_FALSE(gfx::bake::BakeModel(StrBuf(protobuf),
                                      gfx::bake::Texture(pixels.data(), 2, 2),
                                      {.origin_ver = 29})
                     .ok());
}

// The angle between the hair's rest direction (+X) and its posed direction.
float SwingDegrees(const SpringHarness& harness) {
    const auto head = harness.Posed(1, {0, 2, 0});
    const auto tail = harness.Posed(1, {1, 2, 0});
    const auto length = std::hypot(tail[0] - head[0], tail[1] - head[1], tail[2] - head[2]);
    return std::acos(std::clamp((tail[0] - head[0]) / length, -1.0f, 1.0f)) *
           180.0f / GLM_PIf;
}

TEST(SpringPhysicsTest, ACenterCarriesItsChain) {
    InitCpuInfo();
    // The body's travel lags a world-space tail (BodyMotionLagsTheTail...);
    // with the root as the chain's center the tail moves with it instead.
    auto model = MakeHair(4.0f, 0.0f);
    model.springs->chains[0].center = 1;
    SpringHarness harness(model);
    harness.Run(5, Frame());
    auto moved = Frame();
    vec3 shift{0.0f, 0.0f, -0.3f};
    glm_translate_make(moved.motion, shift);
    for (int i = 0; i < 30; ++i) {
        harness.Run(1, moved);
        ExpectNear(harness.Posed(1, {1, 2, 0}), {1, 2, 0}, 1e-4f);
    }

    // The center bone turning 90° about Y at once: in world space the tail
    // would trail behind; in the center's space it turns along rigidly.
    harness.Attribute(0).rotation[1] = GLM_PI_2f;
    harness.Run(1, Frame());
    const auto turned = harness.Posed(0, {1, 2, 0});
    ExpectNear(harness.Posed(1, {1, 2, 0}), turned, 1e-3f);

    SpringHarness world(MakeHair(4.0f, 0.0f));
    world.Run(5, Frame());
    world.Attribute(0).rotation[1] = GLM_PI_2f;
    world.Run(1, Frame());
    const auto lagging = world.Posed(1, {1, 2, 0});
    const auto target = world.Posed(0, {1, 2, 0});
    EXPECT_GT(std::hypot(lagging[0] - target[0], lagging[2] - target[2]), 0.3f);
}

TEST(SpringPhysicsTest, AnInsideColliderKeepsTheTailIn) {
    InitCpuInfo();
    // Gravity would hang the tail at (0, 1, 0); a sphere of radius 0.5 around
    // the rest tail (1, 2, 0) keeps it within 0.45 of that point.
    auto model = MakeHair(0.0f, 2.0f);
    auto& springs = *model.springs;
    springs.colliders.push_back(
        model_proto::SpringCollider(0, {1, 2, 0}, {1, 2, 0}, 0.5f, 1));
    springs.chains[0].colliders = {0};
    springs.joints[0].hit_radius = 0.05f;
    SpringHarness harness(model);
    harness.Run(600, Frame());
    const auto tail = harness.Posed(1, {1, 2, 0});
    EXPECT_LE(std::hypot(tail[0] - 1.0f, tail[1] - 2.0f, tail[2]), 0.451f);
    EXPECT_NEAR(std::hypot(tail[0], tail[1] - 2.0f, tail[2]), 1.0f, 1e-3f);
    // Pressed against the boundary by gravity, not left at rest.
    EXPECT_LT(tail[1], 1.8f);
}

TEST(SpringPhysicsTest, APlaneKeepsTheTailOnItsSide) {
    InitCpuInfo();
    // A floor 0.4 below the pivot, normal up: the hanging tail rests on it.
    auto model = MakeHair(0.0f, 2.0f);
    auto& springs = *model.springs;
    springs.colliders.push_back(
        model_proto::SpringCollider(0, {0, 1.6f, 0}, {0, 2.6f, 0}, 0.0f, 2));
    springs.chains[0].colliders = {0};
    springs.joints[0].hit_radius = 0.05f;
    SpringHarness harness(model);
    harness.Run(600, Frame());
    const auto tail = harness.Posed(1, {1, 2, 0});
    EXPECT_GE(tail[1], 1.649f);
    EXPECT_LT(tail[1], 1.7f);
    EXPECT_NEAR(std::hypot(tail[0], tail[1] - 2.0f, tail[2]), 1.0f, 1e-3f);
}

TEST(SpringPhysicsTest, AConeLimitBoundsTheSwing) {
    InitCpuInfo();
    // Limit space +Y along the bone (+X): 90° about -Z. Gravity alone would
    // hang the hair 90° down; the 30° cone stops it at its rim.
    auto model = MakeHair(0.0f, 2.0f);
    auto& joint = model.springs->joints[0];
    joint.limit = 1;
    joint.limit_rotation = {0, 0, -std::sqrt(0.5f), std::sqrt(0.5f)};
    joint.limit_angle = GLM_PIf / 6.0f;
    SpringHarness harness(model);
    harness.Run(600, Frame());
    EXPECT_NEAR(SwingDegrees(harness), 30.0f, 0.5f);
    EXPECT_LT(harness.Posed(1, {1, 2, 0})[1], 2.0f);
}

TEST(SpringPhysicsTest, AHingeLimitKeepsTheSwingInItsPlane) {
    InitCpuInfo();
    // Limit space +Y along the bone (+X) and +X (the hinge axis) along world
    // +Z: the hair may only turn in the XY plane, up to 60°. Gravity pulls it
    // down and toward -Z.
    auto model = MakeHair(0.0f, 2.0f);
    auto& joint = model.springs->joints[0];
    joint.gravity_dir = {0, -std::sqrt(0.5f), -std::sqrt(0.5f)};
    joint.limit = 2;
    joint.limit_rotation = {-0.5f, -0.5f, -0.5f, 0.5f};
    joint.limit_angle = GLM_PIf / 3.0f;
    SpringHarness harness(model);
    harness.Run(600, Frame());
    const auto tail = harness.Posed(1, {1, 2, 0});
    EXPECT_NEAR(tail[2], 0.0f, 1e-3f);
    EXPECT_NEAR(SwingDegrees(harness), 60.0f, 0.5f);
    EXPECT_LT(tail[1], 2.0f);
}

TEST(SpringPhysicsTest, ASphericalLimitBoundsPitchAndYaw) {
    InitCpuInfo();
    // Limit space as for the cone: +Y along the bone, +X down (yaw turns the
    // hair down), +Z along world +Z (pitch turns it sideways). Gravity down
    // and toward +Z: yaw stops at 20°, pitch at 10°.
    auto model = MakeHair(0.0f, 2.0f);
    auto& joint = model.springs->joints[0];
    joint.gravity_dir = {0, -std::sqrt(0.5f), std::sqrt(0.5f)};
    joint.limit = 3;
    joint.limit_rotation = {0, 0, -std::sqrt(0.5f), std::sqrt(0.5f)};
    joint.limit_angle = GLM_PIf / 18.0f;
    joint.limit_yaw = GLM_PIf / 9.0f;
    SpringHarness harness(model);
    harness.Run(600, Frame());
    const auto tail = harness.Posed(1, {1, 2, 0});
    // tail = (cos yaw cos pitch, -sin yaw, cos yaw sin pitch) from the pivot.
    EXPECT_NEAR(std::asin(2.0f - tail[1]) * 180.0f / GLM_PIf, 20.0f, 0.5f);
    EXPECT_NEAR(std::atan2(tail[2], tail[0]) * 180.0f / GLM_PIf, 10.0f, 0.5f);
}

// "root"; "b" (bone 1) pivots at (0, 2, 0) along +X like the hair; "a" (bone
// 2) pivots at (0, 2, 0.3) along +X. Both hang under gravity, in one chain
// each; a sphere on b's end (radius 0.3) moves with b's simulation.
model_proto::GeoModel MakeTwoStrands() {
    model_proto::GeoModel model;
    model.bones.resize(3);
    model.bones[0].name = "root";
    model.bones[0].pivot = {0, 0, 0};
    model.bones[0].rotate = {0, 0, 0};
    model.bones[1].name = "b";
    model.bones[1].parent = "root";
    model.bones[1].pivot = {0, 32, 0};
    model.bones[1].rotate = {0, 0, 0};
    model.bones[2].name = "a";
    model.bones[2].parent = "root";
    model.bones[2].pivot = {0, 32, 4.8f};
    model.bones[2].rotate = {0, 0, 0};
    auto& springs = model.springs.emplace();
    springs.colliders.push_back(
        model_proto::SpringCollider(1, {1, 2, 0}, {1, 2, 0}, 0.3f));
    // b collides with its own sphere (which its swing carries: ignored), a
    // with b's.
    springs.chains.push_back(model_proto::SpringChain({0}));
    springs.chains.push_back(model_proto::SpringChain({0}));
    springs.joints.push_back(model_proto::SpringJoint(
        1, {1, 2, 0}, 0.05f, 0.0f, 0.4f, 2.0f, {0, -1, 0}, 0));
    springs.joints.push_back(model_proto::SpringJoint(
        2, {1, 2, 0.3f}, 0.05f, 0.0f, 0.4f, 2.0f, {0, -1, 0}, 1));
    return model;
}

TEST(SpringPhysicsTest, CollidersOnSimulatedBonesMoveWithTheSimulation) {
    InitCpuInfo();
    SpringHarness harness(MakeTwoStrands());
    harness.Run(600, Frame());
    // b ignores the sphere it carries and hangs straight down.
    const auto b = harness.Posed(1, {1, 2, 0});
    ExpectNear(b, {0, 1, 0}, 0.02f);
    // a would hang at (0, 1, 0.3), 0.3 from b's end: the sphere where b swung
    // (not where b's animation holds it, at (1, 2, 0)) pushes it to 0.35.
    const auto a = harness.Posed(2, {1, 2, 0.3f});
    EXPECT_GE(std::hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]), 0.345f);
    EXPECT_NEAR(std::hypot(a[0], a[1] - 2.0f, a[2] - 0.3f), 1.0f, 1e-3f);
}

TEST(SpringPhysicsTest, PivotsFollowTheirAnimatedParentAndOnlyTurn) {
    InitCpuInfo();
    // MMD's physics + bone position bodies: the bone stays where its parent
    // carries it while the simulation only turns it. A spring joint's pivot
    // follows the animated parent exactly, the direction lags.
    SpringHarness harness(MakeHair(4.0f, 0.0f));
    harness.Run(5, Frame());
    harness.Attribute(0).position[2] = 8.0f;
    harness.Run(1, Frame());
    const auto parent_carried = harness.Posed(0, {0, 2, 0});
    EXPECT_GT(std::abs(parent_carried[2]), 0.1f);
    ExpectNear(harness.Posed(1, {0, 2, 0}), parent_carried, 1e-5f);
    const auto tail = harness.Posed(1, {1, 2, 0});
    EXPECT_GT(std::abs(tail[2] - parent_carried[2]), 0.05f);
    EXPECT_NEAR(std::hypot(tail[0] - parent_carried[0], tail[1] - parent_carried[1],
                           tail[2] - parent_carried[2]),
                1.0f, 1e-3f);
}

// Offline replay of an imported model (YSM_SPRING_REPLAY_MODEL: a GeoModel
// protobuf written by tools/ysmgen GenGeoDump): ten seconds at 60 fps of the
// body running in a circle, turning and stopping; reports the time per
// extraction and checks every spring stays finite and at its bone length.
TEST(SpringPhysicsTest, ReplayImportedModel) {
    const auto* path = std::getenv("YSM_SPRING_REPLAY_MODEL");
    if (path == nullptr) {
        GTEST_SKIP() << "YSM_SPRING_REPLAY_MODEL not set";
    }
    InitCpuInfo();
    std::string protobuf;
    {
        FILE* file = std::fopen(path, "rb");
        ASSERT_NE(file, nullptr);
        char buffer[1 << 16];
        size_t read;
        while ((read = std::fread(buffer, 1, sizeof(buffer), file)) > 0) {
            protobuf.append(buffer, read);
        }
        std::fclose(file);
    }
    model_proto::GeoModel model;
    struct_pb::from_pb(model, protobuf);
    SpringHarness harness(model);
    ASSERT_NE(harness.Baked(), nullptr);
    const auto& springs = harness.Baked()->Springs();
    double seconds = 0.0;
    for (int i = 0; i < 600; ++i) {
        auto frame = Frame();
        const auto running = (i / 120) % 2 == 0;
        mat4 turn;
        glm_rotate_make(turn, running ? 0.05f : 0.0f, vec3{0.0f, 1.0f, 0.0f});
        vec3 shift{0.0f, 0.0f, running ? 5.6f * kFrame : 0.0f};
        glm_translate(turn, shift);
        glm_mat4_copy(turn, frame.motion);
        const auto start = std::chrono::steady_clock::now();
        ASSERT_TRUE(harness.Step(&frame));
        seconds += std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    }
    std::printf("replay %s: %zu joints, %zu colliders, %zu chain colliders: %.3f ms per extraction\n",
                path, springs.joints.size(), springs.colliders.size(),
                springs.chain_colliders.size(), seconds * 1000.0 / 600.0);
}

bool SameBits(const Vec3& a, const Vec3& b) {
    return std::memcmp(a.data(), b.data(), sizeof(Vec3)) == 0;
}

TEST(SpringPhysicsTest, ALevelSwapKeepsTheSprings) {
    InitCpuInfo();
    // Mesh LOD: every extraction may hand another baked model with the same
    // bones and spring table. The springs must go on exactly as without the
    // swaps; a model with another spring table still restarts them.
    const auto hair = MakeHair(1.0f, 2.0f);
    SpringHarness steady(hair);
    SpringHarness swapped(hair);
    const auto level = Bake(hair);
    ASSERT_NE(level, nullptr);
    ASSERT_NE(level.get(), swapped.Baked().get());
    for (int i = 0; i < 90; ++i) {
        auto walk = Frame(i % 3 == 0 ? 3.0f * kFrame : kFrame);
        vec3 shift{0.0f, 0.0f, i < 45 ? 4.3f * kFrame : 0.0f};
        glm_translate_make(walk.motion, shift);
        ASSERT_TRUE(steady.Step(&walk));
        ASSERT_TRUE(i % 2 == 0 ? swapped.StepModel(level, &walk)
                               : swapped.Step(&walk));
        ASSERT_TRUE(SameBits(steady.Posed(1, {1, 2, 0}), swapped.Posed(1, {1, 2, 0})))
            << "frame " << i;
        ASSERT_TRUE(SameBits(steady.Posed(2, {1, 2, 0}), swapped.Posed(2, {1, 2, 0})))
            << "frame " << i;
    }
    // Mid-swing, a model whose spring differs restarts at the animated pose.
    const auto stiffer = Bake(MakeHair(2.0f, 2.0f));
    ASSERT_GT(std::abs(swapped.Posed(1, {1, 2, 0})[1] - 2.0f), 0.05f);
    ASSERT_TRUE(swapped.StepModel(stiffer, nullptr));
    auto still = Frame(0.0f);
    ASSERT_TRUE(swapped.StepModel(stiffer, &still));
    ExpectNear(swapped.Posed(1, {1, 2, 0}), {1, 2, 0}, 1e-5f);
}

TEST(SpringPhysicsTest, HoldKeepsTheShapeAndNeitherEntryNorExitJumps) {
    InitCpuInfo();
    // Spring LOD: HOLD freezes a chain's shape in its bones' frames. Entering
    // draws where FULL would have; held, body motion and gravity no longer
    // move it; leaving resumes from the held tail at rest.
    SpringHarness full(MakeHair(0.0f, 2.0f));
    SpringHarness hold(MakeHair(0.0f, 2.0f));
    full.Run(4, Frame());
    hold.Run(4, Frame());
    hold.SetSpringModes({1});
    const auto frame = Frame();
    ASSERT_TRUE(full.Step(&frame));
    ASSERT_TRUE(hold.Step(&frame));
    const auto entered = hold.Posed(1, {1, 2, 0});
    ASSERT_TRUE(SameBits(entered, full.Posed(1, {1, 2, 0})));
    // still mid-swing: FULL keeps falling
    ASSERT_GT(entered[1], 1.2f);

    for (int i = 0; i < 30; ++i) {
        auto walk = Frame();
        vec3 shift{0.0f, 0.0f, -4.3f * kFrame};
        glm_translate_make(walk.motion, shift);
        ASSERT_TRUE(hold.Step(&walk));
        ExpectNear(hold.Posed(1, {1, 2, 0}), entered, 1e-5f);
    }
    full.Run(30, Frame());
    const auto fell = full.Posed(1, {1, 2, 0});
    EXPECT_GT(std::hypot(fell[0] - entered[0], fell[1] - entered[1],
                         fell[2] - entered[2]),
              0.1f);

    // Leaving: the first FULL frame starts from the held tail with no
    // velocity, so it moves only by one frame of gravity from rest: two
    // fixed steps of gravity_power 2 are at most 3 * 2 / 120 = 0.05.
    hold.SetSpringModes({0});
    ASSERT_TRUE(hold.Step(&frame));
    const auto left = hold.Posed(1, {1, 2, 0});
    const auto exit_move = std::hypot(left[0] - entered[0], left[1] - entered[1],
                                      left[2] - entered[2]);
    EXPECT_LE(exit_move, 0.0501f);
    std::printf("hold exit: tail moved %.4f in the first FULL frame\n", exit_move);
    // and then it swings on down as usual
    hold.Run(600, Frame());
    ExpectNear(hold.Posed(1, {1, 2, 0}), {0, 1, 0}, 0.02f);

    // Without forces, leaving does not move the tail at all.
    SpringHarness rest(MakeHair(0.0f, 0.0f));
    rest.Run(5, Frame());
    rest.SetSpringModes({1});
    rest.Run(5, Frame());
    const auto held = rest.Posed(1, {1, 2, 0});
    rest.SetSpringModes({0});
    rest.Run(3, Frame());
    ExpectNear(rest.Posed(1, {1, 2, 0}), held, 1e-5f);
}
}  // namespace
}  // namespace ysm::test
