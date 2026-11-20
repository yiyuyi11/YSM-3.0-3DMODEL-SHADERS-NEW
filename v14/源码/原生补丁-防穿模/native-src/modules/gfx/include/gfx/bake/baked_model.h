#pragma once

#include <any>
#include <array>
#include <mdspan>
#include <memory>
#include <span>
#include <vector>

#include <cglm/vec2.h>
#include <magic_enum_switch.hpp>

#include "buffer_scalar.h"
#include "cpu.h"
#include "empty.h"
#include "non_copyable.h"

namespace ysm::gfx::bake {
struct BakedModelSerdeAccess;

struct CubeAttribute {
    struct QuadAttr {
        std::array<vec2, 4> uv{};
        vec2 mid_uv{};
    };

    std::array<QuadAttr, 6> quad_attr{};
    uint8_t quad_count = 0;
    uint8_t quad_count_after_culling = 0;
};

template <simd::Width kVecWidthIn, bool kTranslucentIn>
struct alignas(simd::bytes(kVecWidthIn)) CubeGroup {
    using CubeAttr = CubeAttribute;

    static constexpr simd::Width kVecWidth = kVecWidthIn;
    static constexpr bool kTranslucent = kTranslucentIn;

    static constexpr size_t kCubeGroupCapacity =
        (kVecWidth == simd::Width::B256) ? 1 : 2;
    static constexpr size_t kQuadAttrCapacity =
        kVecWidth == simd::Width::B128 ? 12 : (kCubeGroupCapacity * 8);

    std::array<std::array<float, 8 * kCubeGroupCapacity>, 3> pos{};
    YSM_EMPTY_OR(kTranslucent,
                 std::array<std::array<float, kQuadAttrCapacity>, 3>) center;
    std::array<std::array<float, kQuadAttrCapacity>, 3> normal{};
    std::array<float, kQuadAttrCapacity> plane_d{};
    std::array<float, kQuadAttrCapacity> winding_sign{};
    std::array<std::array<float, kQuadAttrCapacity>, 4> tangent{};
    std::array<std::array<uint32_t, kQuadAttrCapacity>, 4> vertex_index{};
    std::array<CubeAttr, kCubeGroupCapacity> cube_attr{};

    uint32_t cube_count = 0;
    uint32_t bone_index = 0;

    static uint32_t GetQuadAttrIndex(uint32_t cubeIndex,
                                     uint32_t quadIndex) noexcept {
        if constexpr (kVecWidth == simd::Width::B128) {
            return quadIndex + cubeIndex * 6;
        } else if constexpr (kCubeGroupCapacity > 1) {
            return quadIndex + cubeIndex * 8;
        } else {
            return quadIndex;
        }
    }

    [[nodiscard]] bool IsSingleQuad() const noexcept {
        return cube_count == 1 && cube_attr[0].quad_count == 1;
    }

    void NormalizeSingleQuadVertexLayout() noexcept {
        if (!IsSingleQuad()) {
            return;
        }

        const uint32_t attr_index = GetQuadAttrIndex(0, 0);
        std::array<std::array<float, 4>, 3> ordered_pos{};
        for (size_t vertex = 0; vertex < 4; ++vertex) {
            const auto source_index = vertex_index[vertex][attr_index];
            for (size_t axis = 0; axis < 3; ++axis) {
                ordered_pos[axis][vertex] = pos[axis][source_index];
            }
        }
        for (size_t vertex = 0; vertex < 4; ++vertex) {
            for (size_t axis = 0; axis < 3; ++axis) {
                pos[axis][vertex] = ordered_pos[axis][vertex];
            }
            vertex_index[vertex][attr_index] = static_cast<uint32_t>(vertex);
        }
    }
};

// Skinned mesh vertices in SoA lane blocks. Every lane of a block shares the
// same joint set, so skinning broadcasts one matrix per joint and blends with
// per-lane weights; padding lanes carry zero weights.
template <simd::Width kVecWidthIn>
struct alignas(simd::bytes(kVecWidthIn)) MeshVertexBlock {
    static constexpr simd::Width kVecWidth = kVecWidthIn;
    static constexpr size_t kLanes = simd::bytes(kVecWidthIn) / sizeof(float);
    static constexpr size_t kMaxJoints = 4;

    std::array<std::array<float, kLanes>, 3> pos{};
    std::array<std::array<float, kLanes>, 3> normal{};
    std::array<std::array<float, kLanes>, 2> uv{};
    std::array<std::array<float, kLanes>, kMaxJoints> weight{};
    std::array<uint16_t, kMaxJoints> joint{};
    uint8_t joint_count = 0;
    uint8_t vertex_count = 0;
};

struct MeshQuad {
    // Meshlet-local slot: block offset * lanes + lane. Triangles are emitted
    // as quads whose diagonal (slot 0 to slot 2) is the shared edge.
    std::array<uint16_t, 4> slot{};
    std::array<float, 4> tangent{};
};

struct Meshlet {
    static constexpr size_t kMaxSlots = 256;
    static constexpr size_t kMaxQuads = 128;

    uint32_t first_block = 0;
    uint32_t first_quad = 0;
    uint16_t block_count = 0;
    uint16_t quad_count = 0;
    // Owner bone: dominant joint, supplies light, color and cubes_hidden.
    uint32_t bone_index = 0;
};

// A morph target's weight comes from its driver bone's animation attributes.
enum class MorphDriver : uint8_t {
    // Weight is the bone's X translation, clamped to [0, 1].
    kTranslateX = 0,
    // Weight is 1 minus the bone's Y scale, clamped to [0, 1]; the default
    // blink animation scales "Eyelid" to 0 while the eyes are closed.
    kClosingScale = 1,
};

struct MeshMorph {
    uint32_t bone = 0;
    MorphDriver driver = MorphDriver::kTranslateX;
};

// Rest-position offset of one lane at full weight, in model units.
struct MeshMorphDelta {
    uint16_t morph = 0;
    uint8_t lane = 0;
    std::array<float, 3> delta{};
};

// Anti-clip push-out of one lane: after skinning, the vertex leaves each body
// capsule of its mask it is more than allowance (its rest depth, model units)
// inside, by share of the excess, along the capsule's radius. With a margin
// the push ramps in smoothly over the first 2 * margin of the excess.
struct MeshAntiClip {
    uint32_t capsules = 0;
    float share = 0.0f;
    float allowance = 0.0f;
    float margin = 0.0f;
    uint8_t lane = 0;
};

template <simd::Width kVecWidth>
struct BakedModelMesh {
    using Block = MeshVertexBlock<kVecWidth>;

    std::vector<Block> blocks;
    std::vector<MeshQuad> quads;
    std::vector<Meshlet> opaque;
    std::vector<Meshlet> translucent;
    // Inverted-hull outline: always opaque, drawn only by the outline pass
    // with back-face culling, so quads keep their source triangle winding.
    std::vector<Meshlet> outline;
    std::vector<MeshMorph> morphs;
    // Without morphs empty; otherwise block b's deltas are
    // morph_deltas[morph_offsets[b], morph_offsets[b + 1]).
    std::vector<uint32_t> morph_offsets;
    std::vector<MeshMorphDelta> morph_deltas;
    // Without push-out empty; otherwise block b's lanes are
    // anti_clip[anti_clip_offsets[b], anti_clip_offsets[b + 1]).
    std::vector<uint32_t> anti_clip_offsets;
    std::vector<MeshAntiClip> anti_clip;
};

// 渲染用
template <simd::Width kVecWidth>
struct BakedModelCubes {
    std::vector<CubeGroup<kVecWidth, false>> cutout;
    std::vector<CubeGroup<kVecWidth, false>> cutout_no_culling;
    std::vector<CubeGroup<kVecWidth, true>> translucent;
    std::vector<CubeGroup<kVecWidth, true>> translucent_culling;
    BakedModelMesh<kVecWidth> mesh;
};

// 调度用
struct BakedModelBones {
    struct CubeGroupInfo {
        uint32_t cube_count = 0;
        uint32_t quad_count = 0;
        uint32_t quad_count_after_culling = 0;
    };

    struct BonePartitionInfo {
        std::span<const uint32_t> cube_indices;
        std::span<const CubeGroupInfo> cube_group_info;
        uint32_t cube_count = 0;
        uint32_t full_vertex_count = 0;
        uint32_t culling_vertex_count = 0;
    };

    struct BoneInfo {
        BonePartitionInfo cutout;
        BonePartitionInfo cutout_no_culling;
        BonePartitionInfo translucent;
        BonePartitionInfo translucent_culling;
        // Meshlets owned by this bone; cube_indices index the mesh vectors.
        BonePartitionInfo mesh_opaque;
        BonePartitionInfo mesh_translucent;
        BonePartitionInfo mesh_outline;
        std::array<float, 3> pivot{};
        uint32_t parent_index = UINT32_MAX;
        uint32_t subtree_end = 0;
        uint32_t depth = 0;
        bool solid = false;

        [[nodiscard]] bool HasGeometry() const noexcept {
            return !cutout.cube_indices.empty() ||
                   !cutout_no_culling.cube_indices.empty() ||
                   !translucent.cube_indices.empty() ||
                   !translucent_culling.cube_indices.empty() ||
                   !mesh_opaque.cube_indices.empty() ||
                   !mesh_translucent.cube_indices.empty() ||
                   !mesh_outline.cube_indices.empty();
        }
    };

    std::vector<BoneInfo> list;
    std::vector<uint16_t> sorted_bone_indices;
    std::vector<uint32_t> cube_indices_cache;
    std::vector<CubeGroupInfo> cube_group_info_cache;
    // Strictly ascending bones referenced by any mesh vertex block.
    std::vector<uint16_t> joint_bone_indices;
};

// Spring bone physics in baked bone order. Points are zero-rotation model
// space in model units; the renderer maps them through bone poses.
enum class SpringLimit : uint32_t { kNone, kCone, kHinge, kSpherical };

struct SpringJoint {
    uint32_t bone = 0;
    uint32_t chain = 0;
    // Rest end of the bone the simulation swings around its pivot.
    std::array<float, 3> tail{};
    std::array<float, 3> gravity_dir{0.0f, -1.0f, 0.0f};
    float hit_radius = 0.0f;
    float stiffness = 0.0f;
    float drag = 0.0f;
    float gravity_power = 0.0f;
    // Range of directions the bone may take (VRMC_springBone_limit). The
    // limit space, a rest-space rotation (quaternion xyzw) the bone's pose
    // turns, has the cone / hinge axis at +Y; the hinge turns about +X,
    // spherical pitch about +X and yaw toward +X. Angles in radians:
    // limit_angle is the cone or hinge angle or the spherical pitch.
    SpringLimit limit = SpringLimit::kNone;
    std::array<float, 4> limit_rotation{0.0f, 0.0f, 0.0f, 1.0f};
    float limit_angle = 0.0f;
    float limit_yaw = 0.0f;
    // Anti-clip backstop: BakedSprings::body index + 1, 0 for none. The tail
    // stays at most backstop_allowance (model units) closer to that capsule's
    // axis than the animated pose puts it.
    uint32_t backstop = 0;
    float backstop_allowance = 0.0f;
    // Anti-clip leg sync: BakedSprings::body index + 1, 0 for none. Every
    // step the animated tail moves out by sync_share of how far that capsule's
    // bone would carry the rest tail beyond it, away from the capsule's axis.
    uint32_t sync = 0;
    float sync_share = 0.0f;
};

// Anti-clip distance limit between two joints' tails (BakedSprings::joints
// indices), rest distance in model units; points along it may come allowance
// (model units, their rest depth) into a collider before they are pushed out.
struct SpringLink {
    uint32_t a = 0;
    uint32_t b = 0;
    float rest = 0.0f;
    float allowance = 0.0f;
};

enum class SpringColliderType : uint32_t {
    // Keeps tails (and bones) out of the sphere / capsule.
    kOutside,
    // Keeps tails inside the sphere / capsule.
    kInside,
    // Keeps tails on the side of the plane through head its normal (toward
    // tail) points to.
    kPlane,
};

// Capsule from head to tail; a sphere when both coincide.
struct SpringCollider {
    uint32_t bone = 0;
    std::array<float, 3> head{};
    std::array<float, 3> tail{};
    float radius = 0.0f;
    SpringColliderType type = SpringColliderType::kOutside;
};

// Anti-clip body proxy capsule: radius at head, tail_radius at tail, linear
// in between along the axis.
struct BodyCapsule {
    uint32_t bone = 0;
    std::array<float, 3> head{};
    std::array<float, 3> tail{};
    float radius = 0.0f;
    float tail_radius = 0.0f;
};

// At most this many body capsules (the push-out masks are 32 bits).
inline constexpr size_t kMaxBodyCapsules = 32;

struct BakedSprings {
    // Strictly ascending bone, so ancestors simulate before descendants.
    std::vector<SpringJoint> joints;
    std::vector<SpringCollider> colliders;
    // Chain c collides with chain_colliders[chain_offsets[c], chain_offsets[c + 1]).
    std::vector<uint32_t> chain_offsets;
    std::vector<uint32_t> chain_colliders;
    // Per chain, the baked bone index + 1 whose space carries the chain's
    // inertia (VRM spring center); 0 for world space.
    std::vector<uint32_t> chain_centers;
    // Anti-clip body proxy: joint backstops and the mesh push-out test these.
    std::vector<BodyCapsule> body;
    // Anti-clip links, after every step: a link longer than rest * (1 +
    // link_stretch) pulls its tails back to that length, one shorter than
    // rest * (1 - link_compression) pushes them apart; link_points points
    // along each link stay out of both chains' colliders.
    std::vector<SpringLink> links;
    float link_stretch = 0.0f;
    float link_compression = 0.0f;
    uint32_t link_points = 0;
    // Inertia limits (anti-clip, after MagicaCloth's world movement and
    // rotation speed limits): per second, the model's turn beyond
    // inertia_turn_limit radians and its travel beyond inertia_move_limit
    // model units, and inertia_damping of the rest, carry the tails with the
    // model instead of leaving them behind. 0: no limit, full inertia.
    float inertia_turn_limit = 0.0f;
    float inertia_move_limit = 0.0f;
    float inertia_damping = 0.0f;
};

struct BakedModelInfo {
    bool gui_no_shadow = false;
    bool has_pbr = false;
};

class BakedModel {
    friend struct BakedModelSerdeAccess;

    BakedModelInfo info_;
    BakedModelBones bones_;
    BakedSprings springs_;
    std::any cubes_ = nullptr;
    simd::Width vec_width_;

   public:
    template <simd::Width kVecWidth>
    explicit BakedModel(BakedModelInfo info, BakedModelBones bones,
                        BakedModelCubes<kVecWidth> cubes,
                        BakedSprings springs = {})
        : info_(info),
          bones_(std::move(bones)),
          springs_(std::move(springs)),
          cubes_(std::move(cubes)),
          vec_width_(kVecWidth) {}

    decltype(auto) VisitCubes(auto&& visitor) const {
        return magic_enum::enum_switch(
            [&](auto width) {
                return visitor(
                    std::any_cast<const BakedModelCubes<width>&>(cubes_));
            },
            vec_width_);
    }

    const BakedModelBones& Bones() const { return bones_; }

    const BakedSprings& Springs() const { return springs_; }

    const BakedModelInfo& Info() const {
        return info_;
    }

    [[nodiscard]] size_t CapacityBytes() const {
        const auto vector_bytes = [](const auto& values) {
            return values.capacity() * sizeof(typename std::remove_cvref_t<
                                              decltype(values)>::value_type);
        };
        return sizeof(*this) + vector_bytes(bones_.list) +
               vector_bytes(bones_.sorted_bone_indices) +
               vector_bytes(bones_.cube_indices_cache) +
               vector_bytes(bones_.cube_group_info_cache) +
               vector_bytes(bones_.joint_bone_indices) +
               vector_bytes(springs_.joints) + vector_bytes(springs_.colliders) +
               vector_bytes(springs_.chain_offsets) +
               vector_bytes(springs_.chain_colliders) +
               vector_bytes(springs_.chain_centers) +
               vector_bytes(springs_.body) +
               VisitCubes([&](const auto& cubes) {
                   return sizeof(cubes) + vector_bytes(cubes.cutout) +
                          vector_bytes(cubes.cutout_no_culling) +
                          vector_bytes(cubes.translucent) +
                          vector_bytes(cubes.translucent_culling) +
                          vector_bytes(cubes.mesh.blocks) +
                          vector_bytes(cubes.mesh.quads) +
                          vector_bytes(cubes.mesh.opaque) +
                          vector_bytes(cubes.mesh.translucent) +
                          vector_bytes(cubes.mesh.outline) +
                          vector_bytes(cubes.mesh.morphs) +
                          vector_bytes(cubes.mesh.morph_offsets) +
                          vector_bytes(cubes.mesh.morph_deltas) +
                          vector_bytes(cubes.mesh.anti_clip_offsets) +
                          vector_bytes(cubes.mesh.anti_clip);
               });
    }

    template <simd::Width kVecWidth>
    const BakedModelCubes<kVecWidth>& Cubes() const {
        if (kVecWidth != vec_width_) [[unlikely]] {
            throw std::invalid_argument("Inconsistent simd width");
        }
        return std::any_cast<const BakedModelCubes<kVecWidth>&>(cubes_);
    }
};

#pragma pack(push, 1)

struct Pixel {
    uint8_t r;
    uint8_t g;
    uint8_t b;
    uint8_t a;
};

#pragma pack(pop)
static_assert(sizeof(Pixel) == 4);

using Texture = std::mdspan<
    const Pixel,
    std::extents<std::size_t, std::dynamic_extent, std::dynamic_extent>,
    std::layout_left>;

struct BakeModelOptions {
    uint16_t origin_ver = 0;
    bool force_culling = false;
    bool force_translucent = false;
    bool has_pbr = false;
};

absl::StatusOr<std::unique_ptr<BakedModel>> BakeModel(
    BufferViewR model_data, Texture texture, const BakeModelOptions& options);

absl::Status TryBakeModel(BufferViewR model_data, Texture texture,
                          const BakeModelOptions& options);
}  // namespace ysm::gfx::bake
