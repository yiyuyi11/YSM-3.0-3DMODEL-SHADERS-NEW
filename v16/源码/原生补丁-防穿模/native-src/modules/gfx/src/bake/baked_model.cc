#include "gfx/bake/baked_model.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <map>
#include <numeric>
#include <stdexcept>
#include <string>
#include <string_view>
#include <tuple>
#include <type_traits>
#include <absl/container/flat_hash_map.h>
#include <absl/strings/str_cat.h>
#include <utility>

#include <cglm/vec3.h>

#include "proto/asset/model/data/geo_model.proto.h"

namespace ysm::gfx::bake {
namespace {
namespace model_proto = ysm::proto::asset::model::data;

enum class UvProcessing : uint16_t {
    kLegacy = 0,
    k241 = 6,
    k250 = 17,
    k252 = 28,
    k254 = 29,
};

enum class QuadType {
    kEmpty,
    kTranslucent,
    kCutout,
    kSolid,
};

enum class PartitionKind : size_t {
    kCutout,
    kCutoutNoCulling,
    kTranslucent,
    kTranslucentCulling,
    kMeshOpaque,
    kMeshTranslucent,
    kMeshOutline,
};

constexpr size_t kPartitionKindCount = 7;

struct ProcessedQuad {
    QuadType type = QuadType::kEmpty;
    std::array<uint32_t, 4> pos_indices{};
    std::array<std::array<float, 2>, 4> uv{};
    std::array<float, 3> normal{};
    float plane_d = 0.0f;
    float winding_sign = 0.0f;
    std::array<float, 4> tangent{};
    std::array<float, 3> center{};
};

struct ProcessedCube {
    std::array<std::array<float, 3>, 8> pos{};
    std::vector<ProcessedQuad> quads;
    bool inverted = false;
};

bool IsFinite(std::span<const float> values) noexcept;

absl::Status ValidateCube(const model_proto::CubeLegacy& cube,
                          std::string_view location);

absl::Status ValidateMesh(const model_proto::SkinnedMesh& mesh,
                          size_t bone_count);

absl::Status ValidateSprings(const model_proto::SpringBones& springs,
                             size_t bone_count, size_t vertex_count);

struct BoneHierarchyInfo {
    std::array<float, 3> pivot{};
    uint32_t parent_index = UINT32_MAX;
    uint32_t subtree_end = 0;
    uint32_t depth = 0;
};

struct BoneHierarchy {
    std::vector<BoneHierarchyInfo> list;
    std::vector<uint16_t> sorted_bone_indices;
};

struct PreparedBakeModel {
    model_proto::GeoModel model;
    std::vector<size_t> cube_offsets;
    BoneHierarchy hierarchy;
};

absl::StatusOr<BoneHierarchy> BuildBoneHierarchy(
    const model_proto::GeoModel& model) {
    const auto bone_count = model.bones.size();
    if (bone_count > static_cast<size_t>(UINT16_MAX) + 1ULL) {
        return absl::InvalidArgumentError("Too many bones.");
    }

    absl::flat_hash_map<std::string_view, uint32_t> original_by_name;
    original_by_name.reserve(bone_count);
    for (uint32_t i = 0; i < bone_count; ++i) {
        const auto& bone = model.bones[i];
        if (!original_by_name.emplace(bone.name, i).second ||
            bone.pivot.size() != 3 || bone.rotate.size() != 3 ||
            !IsFinite(bone.pivot) || !IsFinite(bone.rotate)) {
            return absl::InvalidArgumentError("Invalid bone metadata.");
        }
    }

    std::vector<std::vector<uint32_t>> children(bone_count);
    std::vector<uint32_t> roots;
    roots.reserve(bone_count);
    for (uint32_t original = 0; original < bone_count; ++original) {
        const auto& parent_name = model.bones[original].parent;
        if (!parent_name.has_value() || parent_name->empty()) {
            roots.push_back(original);
            continue;
        }
        const auto parent = original_by_name.find(*parent_name);
        if (parent == original_by_name.end()) {
            return absl::InvalidArgumentError("Bone parent not found.");
        }
        children[parent->second].push_back(original);
    }

    struct TraversalFrame {
        uint32_t original_index;
        uint32_t sorted_index;
        size_t next_child = 0;
    };

    BoneHierarchy output;
    output.list.reserve(bone_count);
    output.sorted_bone_indices.reserve(bone_count);
    std::vector<uint8_t> visit_state(bone_count);
    std::vector<TraversalFrame> stack;
    stack.reserve(bone_count);

    const auto append_bone = [&](uint32_t original, uint32_t parent) {
        const auto sorted = static_cast<uint32_t>(output.list.size());
        output.sorted_bone_indices.push_back(static_cast<uint16_t>(original));
        auto& info = output.list.emplace_back();
        std::copy_n(model.bones[original].pivot.begin(), 3, info.pivot.begin());
        info.parent_index = parent;
        info.depth = parent == UINT32_MAX ? 0 : output.list[parent].depth + 1;
        visit_state[original] = 1;
        stack.push_back({original, sorted});
    };

    for (const auto root : roots) {
        if (visit_state[root] != 0) {
            return absl::InvalidArgumentError("Invalid bone hierarchy.");
        }
        append_bone(root, UINT32_MAX);
        while (!stack.empty()) {
            auto& frame = stack.back();
            const auto& bone_children = children[frame.original_index];
            if (frame.next_child < bone_children.size()) {
                const auto child = bone_children[frame.next_child++];
                if (visit_state[child] != 0) {
                    return absl::InvalidArgumentError(
                        "Invalid bone hierarchy.");
                }
                append_bone(child, frame.sorted_index);
                continue;
            }
            output.list[frame.sorted_index].subtree_end =
                static_cast<uint32_t>(output.list.size());
            visit_state[frame.original_index] = 2;
            stack.pop_back();
        }
    }

    if (output.list.size() != bone_count) {
        return absl::InvalidArgumentError("Invalid bone hierarchy.");
    }
    return output;
}

absl::Status ValidateTexture(Texture texture) {
    if (texture.data_handle() == nullptr || texture.extent(0) == 0 ||
        texture.extent(1) == 0 ||
        texture.extent(0) > std::numeric_limits<uint32_t>::max() ||
        texture.extent(1) > std::numeric_limits<uint32_t>::max()) {
        return absl::InvalidArgumentError("Invalid model texture.");
    }
    return absl::OkStatus();
}

absl::StatusOr<PreparedBakeModel> PrepareBakeModel(BufferViewR model_data) {
    PreparedBakeModel prepared;
    try {
        prepared.model.from_pb(BufStr(model_data));
    } catch (const std::exception& exception) {
        return absl::InvalidArgumentError(
            std::string("Invalid geometry protobuf: ") + exception.what());
    }
    prepared.cube_offsets.resize(prepared.model.bones.size() + 1);
    for (size_t i = 0; i < prepared.model.bones.size(); ++i) {
        const auto cube_count = prepared.model.bones[i].cubeCount.value_or(0);
        if (cube_count >
            prepared.model.cubes.cubes_legacy.size() -
                prepared.cube_offsets[i]) {
            return absl::InvalidArgumentError("Invalid bone cube count.");
        }
        prepared.cube_offsets[i + 1] =
            prepared.cube_offsets[i] + cube_count;
    }
    if (prepared.cube_offsets.back() !=
        prepared.model.cubes.cubes_legacy.size()) {
        return absl::InvalidArgumentError("Invalid bone cube count.");
    }
    for (size_t bone_index = 0; bone_index < prepared.model.bones.size();
         ++bone_index) {
        const auto first = prepared.cube_offsets[bone_index];
        const auto count =
            prepared.model.bones[bone_index].cubeCount.value_or(0);
        for (size_t cube_index = 0; cube_index < count; ++cube_index) {
            YSM_RETURN_IF_ERROR(ValidateCube(
                prepared.model.cubes.cubes_legacy[first + cube_index],
                absl::StrCat("bone[", bone_index, "] \"",
                             prepared.model.bones[bone_index].name, "\" cube[",
                             cube_index, "]")));
        }
    }
    if (prepared.model.mesh.has_value()) {
        YSM_RETURN_IF_ERROR(
            ValidateMesh(*prepared.model.mesh, prepared.model.bones.size()));
    }
    if (prepared.model.springs.has_value()) {
        YSM_RETURN_IF_ERROR(ValidateSprings(
            *prepared.model.springs, prepared.model.bones.size(),
            prepared.model.mesh.has_value()
                ? prepared.model.mesh->positions.size() / 3
                : 0));
    }
    YSM_ASSIGN_OR_RETURN(prepared.hierarchy,
                         BuildBoneHierarchy(prepared.model));
    return prepared;
}

void ComputeTangent(ProcessedQuad& quad,
                    const std::array<std::array<float, 3>, 8>& pos) noexcept {
    std::array<float, 3> edge1{};
    std::array<float, 3> edge2{};
    const auto& pos0 = pos[quad.pos_indices[0]];
    const auto& pos1 = pos[quad.pos_indices[1]];
    const auto& pos2 = pos[quad.pos_indices[2]];
    for (size_t axis = 0; axis < 3; ++axis) {
        edge1[axis] = pos1[axis] - pos0[axis];
        edge2[axis] = pos2[axis] - pos0[axis];
    }

    const float delta_u1 = quad.uv[1][0] - quad.uv[0][0];
    const float delta_v1 = quad.uv[1][1] - quad.uv[0][1];
    const float delta_u2 = quad.uv[2][0] - quad.uv[0][0];
    const float delta_v2 = quad.uv[2][1] - quad.uv[0][1];
    const float denominator = delta_u1 * delta_v2 - delta_u2 * delta_v1;
    const float factor = denominator != 0.0f ? 1.0f / denominator : 1.0f;

    std::array<float, 3> bitangent{};
    float tangent_length_sq = 0.0f;
    for (size_t axis = 0; axis < 3; ++axis) {
        quad.tangent[axis] =
            (edge1[axis] * delta_v2 - edge2[axis] * delta_v1) * factor;
        tangent_length_sq += quad.tangent[axis] * quad.tangent[axis];
        bitangent[axis] =
            (edge2[axis] * delta_u1 - edge1[axis] * delta_u2) * factor;
    }
    if (tangent_length_sq != 0.0f) {
        const float inverse_length = 1.0f / std::sqrt(tangent_length_sq);
        for (size_t axis = 0; axis < 3; ++axis) {
            quad.tangent[axis] *= inverse_length;
        }
    }

    const std::array<float, 3> tangent_cross_normal{
        quad.tangent[1] * quad.normal[2] - quad.tangent[2] * quad.normal[1],
        quad.tangent[2] * quad.normal[0] - quad.tangent[0] * quad.normal[2],
        quad.tangent[0] * quad.normal[1] - quad.tangent[1] * quad.normal[0],
    };
    float handedness = 0.0f;
    for (size_t axis = 0; axis < 3; ++axis) {
        handedness += bitangent[axis] * tangent_cross_normal[axis];
    }
    quad.tangent[3] =
        handedness < 0.0f ? -1.0f : (handedness > 0.0f ? 1.0f : 0.0f);
}

void ComputeFacePlane(ProcessedQuad& quad,
                      const std::array<std::array<float, 3>, 8>& pos) noexcept {
    const auto& pos0 = pos[quad.pos_indices[0]];
    const auto& pos1 = pos[quad.pos_indices[1]];
    const auto& pos2 = pos[quad.pos_indices[2]];
    vec3 edge1{
        pos1[0] - pos0[0],
        pos1[1] - pos0[1],
        pos1[2] - pos0[2],
    };
    vec3 edge2{
        pos2[0] - pos0[0],
        pos2[1] - pos0[1],
        pos2[2] - pos0[2],
    };
    vec3 geometric_normal;
    glm_vec3_cross(edge1, edge2, geometric_normal);

    quad.plane_d = -(quad.normal[0] * pos0[0] + quad.normal[1] * pos0[1] +
                     quad.normal[2] * pos0[2]);
    const auto orientation = quad.normal[0] * geometric_normal[0] +
                             quad.normal[1] * geometric_normal[1] +
                             quad.normal[2] * geometric_normal[2];
    quad.winding_sign =
        orientation < 0.0f ? -1.0f : (orientation > 0.0f ? 1.0f : 0.0f);
}

UvProcessing SelectUvProcessing(uint16_t origin_ver) noexcept {
    if (origin_ver >= static_cast<uint16_t>(UvProcessing::k254)) {
        return UvProcessing::k254;
    }
    if (origin_ver >= static_cast<uint16_t>(UvProcessing::k252)) {
        return UvProcessing::k252;
    }
    if (origin_ver >= static_cast<uint16_t>(UvProcessing::k250)) {
        return UvProcessing::k250;
    }
    if (origin_ver >= static_cast<uint16_t>(UvProcessing::k241)) {
        return UvProcessing::k241;
    }
    return UvProcessing::kLegacy;
}

bool IsFinite(std::span<const float> values) noexcept {
    return std::ranges::all_of(
        values, [](float value) { return std::isfinite(value); });
}

absl::Status ValidateCube(const model_proto::CubeLegacy& cube,
                          std::string_view location) {
    const auto invalid = [&](std::string_view condition) {
        return absl::InvalidArgumentError(
            absl::StrCat("Invalid cube geometry at ", location, ": ",
                         condition, "."));
    };
    if (cube.faceCount > 6) [[unlikely]] {
        return invalid(
            absl::StrCat("face_count exceeds 6 (got ", cube.faceCount, ")"));
    }
    if (cube.pos.size() % 3 != 0) [[unlikely]] {
        return invalid(absl::StrCat("position component count is not divisible "
                                    "by 3 (got ",
                                    cube.pos.size(), ")"));
    }
    if (cube.pos.size() / 3 > 8) [[unlikely]] {
        return invalid(absl::StrCat("position count exceeds 8 (got ",
                                    cube.pos.size() / 3, ")"));
    }
    if (cube.pos_indices.size() != cube.faceCount * 4ULL) [[unlikely]] {
        return invalid(absl::StrCat(
            "position index count does not equal face_count * 4 (face_count=",
            cube.faceCount, ", got ", cube.pos_indices.size(), ")"));
    }
    if (cube.uv.size() % 2 != 0) [[unlikely]] {
        return invalid(absl::StrCat(
            "UV component count is not divisible by 2 (got ", cube.uv.size(),
            ")"));
    }
    if (cube.uv_indices.size() != cube.faceCount * 4ULL) [[unlikely]] {
        return invalid(absl::StrCat(
            "UV index count does not equal face_count * 4 (face_count=",
            cube.faceCount, ", got ", cube.uv_indices.size(), ")"));
    }
    if (cube.normal.size() != cube.faceCount * 3ULL) [[unlikely]] {
        return invalid(absl::StrCat(
            "normal component count does not equal face_count * 3 "
            "(face_count=",
            cube.faceCount, ", got ", cube.normal.size(), ")"));
    }
    if (!IsFinite(cube.pos)) [[unlikely]] {
        return invalid("position contains a non-finite value");
    }
    if (!IsFinite(cube.uv)) [[unlikely]] {
        return invalid("UV contains a non-finite value");
    }
    if (!IsFinite(cube.normal)) [[unlikely]] {
        return invalid("normal contains a non-finite value");
    }
    const auto pos_count = cube.pos.size() / 3;
    const auto uv_count = cube.uv.size() / 2;
    if (std::ranges::any_of(
            cube.pos_indices,
            [=](uint32_t index) { return index >= pos_count; }) ||
        std::ranges::any_of(cube.uv_indices, [=](uint32_t index) {
            return index >= uv_count;
        })) [[likely]] {
        return invalid("position or UV index is out of range");
    }
    return OkStatus();
}

absl::Status ValidateMesh(const model_proto::SkinnedMesh& mesh,
                          size_t bone_count) {
    const auto invalid = [](std::string_view condition) {
        return absl::InvalidArgumentError(
            absl::StrCat("Invalid skinned mesh: ", condition, "."));
    };
    constexpr size_t kMaxVertices = size_t{1} << 24;
    constexpr size_t kMaxTriangles = size_t{1} << 24;
    if (mesh.positions.size() % 3 != 0) {
        return invalid("position component count is not divisible by 3");
    }
    const auto vertex_count = mesh.positions.size() / 3;
    if (vertex_count > kMaxVertices) {
        return invalid("too many vertices");
    }
    if (mesh.normals.size() != vertex_count * 3 ||
        mesh.uvs.size() != vertex_count * 2 ||
        mesh.joints.size() != vertex_count * 4 ||
        mesh.weights.size() != vertex_count * 4) {
        return invalid("vertex stream lengths disagree");
    }
    if (mesh.triangles.size() % 3 != 0 ||
        mesh.triangles.size() / 3 > kMaxTriangles) {
        return invalid("invalid triangle index count");
    }
    if (mesh.outline_triangles.size() % 3 != 0 ||
        mesh.outline_triangles.size() / 3 > kMaxTriangles) {
        return invalid("invalid outline triangle index count");
    }
    if (vertex_count == 0) {
        if (!mesh.triangles.empty() || !mesh.outline_triangles.empty()) {
            return invalid("triangles without vertices");
        }
        return OkStatus();
    }
    if (!IsFinite(mesh.positions) || !IsFinite(mesh.normals) ||
        !IsFinite(mesh.uvs) || !IsFinite(mesh.weights)) {
        return invalid("vertex stream contains a non-finite value");
    }
    for (size_t vertex = 0; vertex < vertex_count; ++vertex) {
        float sum = 0.0f;
        for (size_t slot = 0; slot < 4; ++slot) {
            const auto weight = mesh.weights[vertex * 4 + slot];
            if (weight < 0.0f) {
                return invalid("negative joint weight");
            }
            if (weight > 0.0f && mesh.joints[vertex * 4 + slot] >= bone_count) {
                return invalid("joint index is out of range");
            }
            sum += weight;
        }
        if (!(sum > 0.0f) || !std::isfinite(sum)) {
            return invalid("vertex has no positive joint weight");
        }
    }
    const auto out_of_range = [=](uint32_t index) {
        return index >= vertex_count;
    };
    if (std::ranges::any_of(mesh.triangles, out_of_range)) {
        return invalid("triangle index is out of range");
    }
    if (std::ranges::any_of(mesh.outline_triangles, out_of_range)) {
        return invalid("outline triangle index is out of range");
    }
    constexpr size_t kMaxMorphs = size_t{1} << 16;
    constexpr size_t kMaxMorphVertices = size_t{1} << 24;
    if (mesh.morphs.size() > kMaxMorphs) {
        return invalid("too many morph targets");
    }
    size_t morph_vertices = 0;
    for (const auto& morph : mesh.morphs) {
        morph_vertices += morph.vertices.size();
        if (morph.bone >= bone_count || morph.driver > 1 ||
            morph.deltas.size() != morph.vertices.size() * 3 ||
            morph_vertices > kMaxMorphVertices || !IsFinite(morph.deltas) ||
            std::ranges::any_of(morph.vertices, [=](uint32_t index) {
                return index >= vertex_count;
            })) {
            return invalid("malformed morph target");
        }
    }
    return OkStatus();
}

absl::Status ValidateSprings(const model_proto::SpringBones& springs,
                             size_t bone_count, size_t vertex_count) {
    const auto invalid = [](std::string_view condition) {
        return absl::InvalidArgumentError(
            absl::StrCat("Invalid spring bones: ", condition, "."));
    };
    constexpr size_t kMaxColliders = size_t{1} << 16;
    constexpr size_t kMaxChainColliders = size_t{1} << 20;
    if (springs.joints.size() > bone_count) {
        return invalid("more joints than bones");
    }
    if (springs.colliders.size() > kMaxColliders) {
        return invalid("too many colliders");
    }
    std::vector<uint8_t> jointed(bone_count);
    for (const auto& joint : springs.joints) {
        if (joint.bone >= bone_count || jointed[joint.bone] != 0) {
            return invalid("joint bone is out of range or repeated");
        }
        jointed[joint.bone] = 1;
        if (joint.chain >= springs.chains.size()) {
            return invalid("joint chain is out of range");
        }
        if (joint.tail.size() != 3 || joint.gravity_dir.size() != 3 ||
            !IsFinite(joint.tail) || !IsFinite(joint.gravity_dir)) {
            return invalid("joint vector is malformed");
        }
        const std::array scalars{joint.hit_radius, joint.stiffness, joint.drag,
                                 joint.gravity_power, joint.limit_angle,
                                 joint.limit_yaw};
        if (!IsFinite(scalars) || joint.hit_radius < 0.0f ||
            joint.stiffness < 0.0f || joint.drag < 0.0f || joint.drag > 1.0f ||
            joint.gravity_power < 0.0f || joint.limit_angle < 0.0f ||
            joint.limit_yaw < 0.0f) {
            return invalid("joint parameter is out of range");
        }
        if (joint.limit > static_cast<uint32_t>(SpringLimit::kSpherical) ||
            !(joint.limit_rotation.empty() ||
              (joint.limit_rotation.size() == 4 &&
               IsFinite(joint.limit_rotation) &&
               std::ranges::any_of(joint.limit_rotation,
                                   [](float v) { return v != 0.0f; })))) {
            return invalid("joint limit is malformed");
        }
        if (joint.backstop > springs.body.size() ||
            !std::isfinite(joint.backstop_allowance) ||
            joint.backstop_allowance < 0.0f) {
            return invalid("joint backstop is malformed");
        }
        if (joint.sync > springs.body.size() ||
            !std::isfinite(joint.sync_share) || joint.sync_share < 0.0f ||
            joint.sync_share > 1.0f) {
            return invalid("joint sync is malformed");
        }
    }
    for (const auto& collider : springs.colliders) {
        if (collider.bone >= bone_count || collider.head.size() != 3 ||
            collider.tail.size() != 3 || !IsFinite(collider.head) ||
            !IsFinite(collider.tail) || !std::isfinite(collider.radius) ||
            collider.radius < 0.0f ||
            collider.type > static_cast<uint32_t>(SpringColliderType::kPlane) ||
            (collider.type == static_cast<uint32_t>(SpringColliderType::kPlane) &&
             collider.head == collider.tail)) {
            return invalid("collider is malformed");
        }
    }
    size_t chain_colliders = 0;
    for (const auto& chain : springs.chains) {
        chain_colliders += chain.colliders.size();
        if (chain_colliders > kMaxChainColliders ||
            std::ranges::any_of(chain.colliders, [&](uint32_t collider) {
                return collider >= springs.colliders.size();
            })) {
            return invalid("chain collider is out of range");
        }
        if (chain.center > bone_count) {
            return invalid("chain center is out of range");
        }
    }
    if (springs.body.size() > kMaxBodyCapsules) {
        return invalid("too many body capsules");
    }
    for (const auto& capsule : springs.body) {
        if (capsule.bone >= bone_count || capsule.head.size() != 3 ||
            capsule.tail.size() != 3 || !IsFinite(capsule.head) ||
            !IsFinite(capsule.tail) || !std::isfinite(capsule.radius) ||
            !std::isfinite(capsule.tail_radius) || capsule.radius < 0.0f ||
            capsule.tail_radius < 0.0f) {
            return invalid("body capsule is malformed");
        }
    }
    if (!springs.push_share.empty() || !springs.push_allowance.empty() ||
        !springs.push_capsules.empty() || !springs.push_margin.empty()) {
        const auto capsule_bits =
            springs.body.size() >= 32
                ? ~uint32_t{0}
                : (uint32_t{1} << springs.body.size()) - 1;
        if (springs.push_share.size() != vertex_count ||
            springs.push_allowance.size() != vertex_count ||
            springs.push_capsules.size() != vertex_count ||
            (!springs.push_margin.empty() &&
             springs.push_margin.size() != vertex_count) ||
            !IsFinite(springs.push_share) || !IsFinite(springs.push_allowance) ||
            !IsFinite(springs.push_margin) ||
            std::ranges::any_of(springs.push_share,
                                [](float s) { return s < 0.0f || s > 1.0f; }) ||
            std::ranges::any_of(springs.push_allowance,
                                [](float a) { return a < 0.0f; }) ||
            std::ranges::any_of(springs.push_margin,
                                [](float m) { return m < 0.0f; }) ||
            std::ranges::any_of(springs.push_capsules, [&](uint32_t mask) {
                return (mask & ~capsule_bits) != 0;
            })) {
            return invalid("vertex push-out is malformed");
        }
    }
    constexpr size_t kMaxLinks = size_t{1} << 16;
    constexpr uint32_t kMaxLinkPoints = 16;
    if (springs.links.size() > kMaxLinks || springs.link_points > kMaxLinkPoints ||
        !std::isfinite(springs.link_stretch) ||
        !std::isfinite(springs.link_compression) || springs.link_stretch < 0.0f ||
        springs.link_compression < 0.0f || springs.link_compression > 1.0f) {
        return invalid("links are malformed");
    }
    for (const auto& link : springs.links) {
        if (link.a >= bone_count || link.b >= bone_count || link.a == link.b ||
            jointed[link.a] == 0 || jointed[link.b] == 0 ||
            !std::isfinite(link.rest) || link.rest < 0.0f ||
            !std::isfinite(link.allowance) || link.allowance < 0.0f) {
            return invalid("link is malformed");
        }
    }
    const auto limit_ok = [](float value) {
        return std::isfinite(value) && value >= 0.0f;
    };
    if (!limit_ok(springs.inertia_turn_limit) ||
        !limit_ok(springs.inertia_move_limit) ||
        !limit_ok(springs.inertia_damping) || springs.inertia_damping > 1.0f) {
        return invalid("inertia limits are malformed");
    }
    if (!limit_ok(springs.body_velocity_seconds) ||
        springs.body_velocity_seconds > 1.0f) {
        return invalid("body velocity time is malformed");
    }
    return OkStatus();
}

std::array<float, 3> ToArray3(const std::vector<float>& values) {
    return {values[0], values[1], values[2]};
}

// Remaps validated springs to baked bone order, joints ascending by bone.
BakedSprings BakeSprings(const model_proto::SpringBones& springs,
                         std::span<const uint16_t> sorted_bone_indices) {
    std::vector<uint32_t> sorted_by_original(sorted_bone_indices.size());
    for (size_t index = 0; index < sorted_bone_indices.size(); ++index) {
        sorted_by_original[sorted_bone_indices[index]] =
            static_cast<uint32_t>(index);
    }
    BakedSprings output;
    output.joints.reserve(springs.joints.size());
    for (const auto& joint : springs.joints) {
        SpringJoint baked{
            .bone = sorted_by_original[joint.bone],
            .chain = joint.chain,
            .tail = ToArray3(joint.tail),
            .gravity_dir = ToArray3(joint.gravity_dir),
            .hit_radius = joint.hit_radius,
            .stiffness = joint.stiffness,
            .drag = joint.drag,
            .gravity_power = joint.gravity_power,
            .limit = static_cast<SpringLimit>(joint.limit),
            .limit_angle = joint.limit_angle,
            .limit_yaw = joint.limit_yaw,
            .backstop = joint.backstop,
            .backstop_allowance = joint.backstop_allowance,
            .sync = joint.sync,
            .sync_share = joint.sync_share,
        };
        if (joint.limit_rotation.size() == 4) {
            const auto& q = joint.limit_rotation;
            const auto length =
                std::sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
            baked.limit_rotation = {q[0] / length, q[1] / length,
                                    q[2] / length, q[3] / length};
        }
        output.joints.push_back(baked);
    }
    std::ranges::sort(output.joints, {}, &SpringJoint::bone);
    output.colliders.reserve(springs.colliders.size());
    for (const auto& collider : springs.colliders) {
        output.colliders.push_back({
            .bone = sorted_by_original[collider.bone],
            .head = ToArray3(collider.head),
            .tail = ToArray3(collider.tail),
            .radius = collider.radius,
            .type = static_cast<SpringColliderType>(collider.type),
        });
    }
    output.chain_offsets.reserve(springs.chains.size() + 1);
    output.chain_offsets.push_back(0);
    output.chain_centers.reserve(springs.chains.size());
    for (const auto& chain : springs.chains) {
        output.chain_colliders.insert(output.chain_colliders.end(),
                                      chain.colliders.begin(),
                                      chain.colliders.end());
        output.chain_offsets.push_back(
            static_cast<uint32_t>(output.chain_colliders.size()));
        output.chain_centers.push_back(
            chain.center == 0 ? 0 : sorted_by_original[chain.center - 1] + 1);
    }
    output.body.reserve(springs.body.size());
    for (const auto& capsule : springs.body) {
        output.body.push_back({
            .bone = sorted_by_original[capsule.bone],
            .head = ToArray3(capsule.head),
            .tail = ToArray3(capsule.tail),
            .radius = capsule.radius,
            .tail_radius = capsule.tail_radius,
        });
    }
    // Links name joints by bone; baked joints ascend by bone.
    output.links.reserve(springs.links.size());
    for (const auto& link : springs.links) {
        const auto joint_of = [&](uint32_t bone) {
            const auto baked_bone = sorted_by_original[bone];
            return static_cast<uint32_t>(
                std::ranges::lower_bound(output.joints, baked_bone,
                                         {}, &SpringJoint::bone) -
                output.joints.begin());
        };
        output.links.push_back({.a = joint_of(link.a),
                                .b = joint_of(link.b),
                                .rest = link.rest,
                                .allowance = link.allowance});
    }
    output.link_stretch = springs.link_stretch;
    output.link_compression = springs.link_compression;
    output.link_points = springs.link_points;
    output.inertia_turn_limit = springs.inertia_turn_limit;
    output.inertia_move_limit = springs.inertia_move_limit;
    output.inertia_damping = springs.inertia_damping;
    output.body_velocity_seconds = springs.body_velocity_seconds;
    return output;
}

class QuadInspector {
    Texture texture_;
    UvProcessing processing_;

    static uint32_t Quantize(float value, uint32_t extent,
                             float threshold) noexcept {
        const auto scaled = std::clamp(value, 0.0f, 1.0f) * extent;
        auto base = static_cast<uint32_t>(scaled);
        if (base < extent && scaled - static_cast<float>(base) >= threshold) {
            ++base;
        }
        return std::min(base, extent);
    }

   public:
    QuadInspector(Texture texture, uint16_t origin_ver)
        : texture_(texture), processing_(SelectUvProcessing(origin_ver)) {}

    QuadType Process(std::array<std::array<float, 2>, 4>& uv) const noexcept {
        const auto width = static_cast<uint32_t>(texture_.extent(0));
        const auto height = static_cast<uint32_t>(texture_.extent(1));
        std::array<uint32_t, 4> u_pixels{};
        std::array<uint32_t, 4> v_pixels{};

        if (processing_ == UvProcessing::kLegacy ||
            processing_ == UvProcessing::k254) {
            for (size_t i = 0; i < 4; ++i) {
                u_pixels[i] = Quantize(uv[i][0], width, 0.6f);
                v_pixels[i] =
                    Quantize(uv[i][1], height,
                             processing_ == UvProcessing::k254 ? 0.4f : 0.6f);
                uv[i][0] = static_cast<float>(u_pixels[i]) / width;
                uv[i][1] = static_cast<float>(v_pixels[i]) / height;
            }
        } else {
            for (size_t i = 0; i < 4; ++i) {
                u_pixels[i] = static_cast<uint32_t>(
                    std::lround(std::clamp(uv[i][0], 0.0f, 1.0f) * width));
                v_pixels[i] = static_cast<uint32_t>(
                    std::lround(std::clamp(uv[i][1], 0.0f, 1.0f) * height));
            }
        }

        auto [min_u_it, max_u_it] =
            std::minmax_element(u_pixels.begin(), u_pixels.end());
        auto [min_v_it, max_v_it] =
            std::minmax_element(v_pixels.begin(), v_pixels.end());
        uint32_t min_u = *min_u_it;
        uint32_t max_u = *max_u_it;
        uint32_t min_v = *min_v_it;
        uint32_t max_v = *max_v_it;
        float u_offset = 0.0f;
        float v_offset = 0.0f;

        if (processing_ == UvProcessing::k241) {
            if (min_u == max_u && max_u < width) {
                ++max_u;
            }
            if (min_v == max_v && max_v < height) {
                ++max_v;
            }
        } else {
            if (min_u == max_u) {
                if (max_u < width) {
                    ++max_u;
                } else if (min_u > 0) {
                    --min_u;
                    u_offset = -1.0f / width;
                }
            }
            if (min_v == max_v) {
                const bool prefer_grow = processing_ == UvProcessing::kLegacy;
                if (prefer_grow && max_v < height) {
                    ++max_v;
                } else if (min_v > 0) {
                    --min_v;
                    v_offset = -1.0f / height;
                } else if (max_v < height) {
                    ++max_v;
                }
            }
        }

        if (processing_ != UvProcessing::kLegacy &&
            processing_ != UvProcessing::k254) {
            if (processing_ == UvProcessing::k252) {
                uv[0][0] += u_offset;
                uv[3][0] += u_offset;
                uv[2][1] += v_offset;
                uv[3][1] += v_offset;
            } else {
                for (auto& value : uv) {
                    value[0] += u_offset;
                    value[1] += v_offset;
                }
            }
        } else {
            if (u_offset != 0.0f) {
                uv[0][0] += u_offset;
                uv[3][0] += u_offset;
            }
            if (v_offset != 0.0f) {
                uv[2][1] += v_offset;
                uv[3][1] += v_offset;
            }
        }

        min_u = std::min(min_u, width);
        max_u = std::min(max_u, width);
        min_v = std::min(min_v, height);
        max_v = std::min(max_v, height);
        bool empty = true;
        bool has_transparent = false;
        for (uint32_t y = min_v; y < max_v; ++y) {
            for (uint32_t x = min_u; x < max_u; ++x) {
                const auto alpha = texture_[x, y].a;
                if (alpha != 0) {
                    empty = false;
                    if (alpha != 255) {
                        return QuadType::kTranslucent;
                    }
                } else {
                    has_transparent = true;
                }
            }
        }
        if (empty) {
            return QuadType::kEmpty;
        }
        return has_transparent ? QuadType::kCutout : QuadType::kSolid;
    }
};

ProcessedCube ProcessCube(const model_proto::CubeLegacy& cube,
                          const QuadInspector& inspector, bool has_pbr) {
    ProcessedCube output;
    const auto pos_count = cube.pos.size() / 3;
    for (size_t i = 0; i < pos_count; ++i) {
        std::copy_n(cube.pos.data() + i * 3, 3, output.pos[i].begin());
    }
    output.quads.reserve(cube.faceCount);
    std::array<std::array<float, 3>, 6> face_centers{};
    std::array<float, 3> cube_center{};

    for (uint32_t face = 0; face < cube.faceCount; ++face) {
        auto& quad = output.quads.emplace_back();
        std::copy_n(cube.normal.data() + face * 3, 3, quad.normal.begin());
        for (size_t vertex = 0; vertex < 4; ++vertex) {
            const auto flat_index = face * 4 + vertex;
            quad.pos_indices[vertex] = cube.pos_indices[flat_index];
            const auto uv_index = cube.uv_indices[flat_index];
            quad.uv[vertex][0] = cube.uv[uv_index * 2];
            quad.uv[vertex][1] = cube.uv[uv_index * 2 + 1];
            const auto& pos = output.pos[quad.pos_indices[vertex]];
            for (size_t axis = 0; axis < 3; ++axis) {
                quad.center[axis] += pos[axis] * 0.25f;
            }
        }
        quad.type = inspector.Process(quad.uv);
        ComputeFacePlane(quad, output.pos);
        if (has_pbr) {
            ComputeTangent(quad, output.pos);
        }
        face_centers[face] = quad.center;
        for (size_t axis = 0; axis < 3; ++axis) {
            cube_center[axis] += quad.center[axis] / cube.faceCount;
        }
    }

    if (cube.faceCount == 6) {
        for (uint32_t face = 0; face < cube.faceCount; ++face) {
            std::array<float, 3> outward{};
            float length_sq = 0.0f;
            float dot = 0.0f;
            for (size_t axis = 0; axis < 3; ++axis) {
                outward[axis] = face_centers[face][axis] - cube_center[axis];
                length_sq += outward[axis] * outward[axis];
                dot += output.quads[face].normal[axis] * outward[axis];
            }
            if (length_sq > 1e-12f && dot < 0.0f) {
                output.inverted = true;
                break;
            }
        }
    }
    return output;
}

QuadType ClassifyTexels(Texture texture, float min_u_in, float max_u_in,
                        float min_v_in, float max_v_in) noexcept {
    const auto width = static_cast<uint32_t>(texture.extent(0));
    const auto height = static_cast<uint32_t>(texture.extent(1));
    const auto to_pixel = [](float value, uint32_t extent, bool upper) {
        const auto scaled = std::clamp(value, 0.0f, 1.0f) * extent;
        const auto rounded = upper ? std::ceil(scaled) : std::floor(scaled);
        return std::min(static_cast<uint32_t>(rounded), extent);
    };
    auto min_u = to_pixel(min_u_in, width, false);
    auto max_u = to_pixel(max_u_in, width, true);
    auto min_v = to_pixel(min_v_in, height, false);
    auto max_v = to_pixel(max_v_in, height, true);
    if (min_u == max_u) {
        max_u < width ? ++max_u : --min_u;
    }
    if (min_v == max_v) {
        max_v < height ? ++max_v : --min_v;
    }
    bool empty = true;
    bool has_transparent = false;
    for (uint32_t y = min_v; y < max_v; ++y) {
        for (uint32_t x = min_u; x < max_u; ++x) {
            const auto alpha = texture[x, y].a;
            if (alpha == 0) {
                has_transparent = true;
            } else if (alpha != 255) {
                return QuadType::kTranslucent;
            } else {
                empty = false;
            }
        }
    }
    if (empty) {
        return QuadType::kEmpty;
    }
    return has_transparent ? QuadType::kCutout : QuadType::kSolid;
}

// Sorted joints (renderer bone indices) with matching normalized weights.
// Unused joint slots hold UINT16_MAX so the packed joints form the signature.
struct MeshInfluence {
    std::array<uint16_t, 4> joint{UINT16_MAX, UINT16_MAX, UINT16_MAX,
                                  UINT16_MAX};
    std::array<float, 4> weight{};
    uint8_t count = 0;

    [[nodiscard]] uint64_t Signature() const noexcept {
        uint64_t result = 0;
        for (size_t slot = 0; slot < 4; ++slot) {
            result |= static_cast<uint64_t>(joint[slot]) << (slot * 16);
        }
        return result;
    }
};

MeshInfluence BuildInfluence(const model_proto::SkinnedMesh& mesh,
                             size_t vertex,
                             std::span<const uint16_t> sorted_by_original) {
    std::array<std::pair<uint16_t, float>, 4> entries{};
    size_t count = 0;
    for (size_t slot = 0; slot < 4; ++slot) {
        const auto weight = mesh.weights[vertex * 4 + slot];
        if (!(weight > 0.0f)) {
            continue;
        }
        const auto joint = sorted_by_original[mesh.joints[vertex * 4 + slot]];
        auto existing = std::find_if(
            entries.begin(), entries.begin() + count,
            [&](const auto& entry) { return entry.first == joint; });
        if (existing != entries.begin() + count) {
            existing->second += weight;
        } else {
            entries[count++] = {joint, weight};
        }
    }
    float sum = 0.0f;
    for (size_t i = 0; i < count; ++i) {
        sum += entries[i].second;
    }
    // Drop influences below 1/1024 of the total; they only widen joint sets.
    size_t kept = 0;
    for (size_t i = 0; i < count; ++i) {
        if (entries[i].second >= sum * (1.0f / 1024.0f)) {
            entries[kept++] = entries[i];
        }
    }
    float kept_sum = 0.0f;
    for (size_t i = 0; i < kept; ++i) {
        kept_sum += entries[i].second;
    }
    std::sort(entries.begin(), entries.begin() + kept,
              [](const auto& a, const auto& b) { return a.first < b.first; });
    MeshInfluence output;
    output.count = static_cast<uint8_t>(kept);
    for (size_t i = 0; i < kept; ++i) {
        output.joint[i] = entries[i].first;
        output.weight[i] = entries[i].second / kept_sum;
    }
    return output;
}

using SourceQuad = std::array<uint32_t, 4>;

// Pairs triangles sharing an edge with opposite winding into quads whose
// diagonal (vertex 0 to 2) is the shared edge, matching QUADS index order
// (0,1,2)(2,3,0). Unpaired triangles become quads with a repeated vertex.
// Both halves of a quad are cyclic rotations of their source triangles, so
// every triangle keeps its winding, which the culled outline pass relies on.
std::vector<SourceQuad> PairTriangles(
    std::span<const std::array<uint32_t, 3>> triangles) {
    const auto edge_key = [](uint32_t from, uint32_t to) {
        return (static_cast<uint64_t>(from) << 32) | to;
    };
    absl::flat_hash_map<uint64_t, uint32_t> open_edges;
    open_edges.reserve(triangles.size() * 3);
    std::vector<uint8_t> paired(triangles.size());
    std::vector<SourceQuad> quads;
    quads.reserve(triangles.size());

    for (uint32_t index = 0; index < triangles.size(); ++index) {
        const auto& triangle = triangles[index];
        bool done = false;
        for (size_t edge = 0; edge < 3 && !done; ++edge) {
            const auto a = triangle[edge];
            const auto b = triangle[(edge + 1) % 3];
            const auto found = open_edges.find(edge_key(b, a));
            if (found == open_edges.end() || paired[found->second] != 0) {
                continue;
            }
            const auto& other = triangles[found->second];
            uint32_t w = other[0];
            for (const auto vertex : other) {
                if (vertex != a && vertex != b) {
                    w = vertex;
                }
            }
            const auto c = triangle[(edge + 2) % 3];
            quads.push_back({b, c, a, w});
            paired[found->second] = 1;
            paired[index] = 1;
            done = true;
        }
        if (done) {
            continue;
        }
        for (size_t edge = 0; edge < 3; ++edge) {
            open_edges.try_emplace(
                edge_key(triangle[edge], triangle[(edge + 1) % 3]), index);
        }
    }
    for (uint32_t index = 0; index < triangles.size(); ++index) {
        if (paired[index] == 0) {
            const auto& triangle = triangles[index];
            quads.push_back({triangle[0], triangle[1], triangle[2], triangle[2]});
        }
    }
    return quads;
}

void ComputeMeshTangent(const model_proto::SkinnedMesh& mesh,
                        const SourceQuad& quad, std::array<float, 4>& out) {
    ProcessedQuad scratch;
    std::array<std::array<float, 3>, 8> pos{};
    for (size_t corner = 0; corner < 3; ++corner) {
        const auto vertex = quad[corner];
        scratch.pos_indices[corner] = static_cast<uint32_t>(corner);
        std::copy_n(mesh.positions.data() + vertex * 3, 3, pos[corner].begin());
        scratch.uv[corner][0] = mesh.uvs[vertex * 2];
        scratch.uv[corner][1] = mesh.uvs[vertex * 2 + 1];
        for (size_t axis = 0; axis < 3; ++axis) {
            scratch.normal[axis] += mesh.normals[vertex * 3 + axis];
        }
    }
    vec3 normal{scratch.normal[0], scratch.normal[1], scratch.normal[2]};
    glm_vec3_normalize(normal);
    std::copy_n(normal, 3, scratch.normal.begin());
    ComputeTangent(scratch, pos);
    out = scratch.tangent;
}

template <simd::Width kWidth>
class MeshBaker {
    using Block = MeshVertexBlock<kWidth>;
    static constexpr size_t kLanes = Block::kLanes;

    const model_proto::SkinnedMesh& source_;
    const std::vector<MeshInfluence>& influences_;
    bool has_pbr_;
    BakedModelMesh<kWidth>& output_;

    // Morph deltas grouped by source vertex; empty without morphs.
    std::vector<uint32_t> morph_vertex_offsets_;
    std::vector<MeshMorphDelta> morph_vertex_deltas_;
    // Validated per-vertex push-out (anti-clip); null without.
    const model_proto::SpringBones* push_ = nullptr;

    // Meshlet under construction.
    std::vector<uint32_t> vertices_;
    absl::flat_hash_map<uint32_t, uint16_t> local_;
    absl::flat_hash_map<uint64_t, uint32_t> signature_counts_;
    std::vector<SourceQuad> quads_;
    size_t slots_ = 0;

    size_t SlotsAfterAdding(std::span<const uint32_t> added) const {
        size_t slots = slots_;
        std::array<std::pair<uint64_t, uint32_t>, 4> extra{};
        size_t extra_count = 0;
        for (const auto vertex : added) {
            const auto signature = influences_[vertex].Signature();
            auto it = std::find_if(
                extra.begin(), extra.begin() + extra_count,
                [&](const auto& entry) { return entry.first == signature; });
            if (it == extra.begin() + extra_count) {
                const auto found = signature_counts_.find(signature);
                *it = {signature,
                       found == signature_counts_.end() ? 0U : found->second};
                ++extra_count;
            }
            if (it->second % kLanes == 0) {
                slots += kLanes;
            }
            ++it->second;
        }
        return slots;
    }

    void Flush(std::vector<Meshlet>& meshlets, uint32_t bone_index) {
        if (quads_.empty()) {
            return;
        }
        Meshlet meshlet;
        meshlet.first_block = static_cast<uint32_t>(output_.blocks.size());
        meshlet.first_quad = static_cast<uint32_t>(output_.quads.size());
        meshlet.bone_index = bone_index;

        // Group local vertices by joint signature, in first-seen order.
        std::vector<uint64_t> order;
        absl::flat_hash_map<uint64_t, std::vector<uint32_t>> members;
        for (const auto vertex : vertices_) {
            const auto signature = influences_[vertex].Signature();
            auto [it, inserted] = members.try_emplace(signature);
            if (inserted) {
                order.push_back(signature);
            }
            it->second.push_back(vertex);
        }
        absl::flat_hash_map<uint32_t, uint16_t> slot_of;
        slot_of.reserve(vertices_.size());
        for (const auto signature : order) {
            const auto& list = members[signature];
            for (size_t begin = 0; begin < list.size(); begin += kLanes) {
                const auto local_block =
                    output_.blocks.size() - meshlet.first_block;
                auto& block = output_.blocks.emplace_back();
                const auto& influence = influences_[list[begin]];
                block.joint_count = influence.count;
                block.joint = influence.joint;
                const auto end = std::min(list.size(), begin + kLanes);
                block.vertex_count = static_cast<uint8_t>(end - begin);
                for (size_t lane = 0; lane < end - begin; ++lane) {
                    const auto vertex = list[begin + lane];
                    vec3 normal{source_.normals[vertex * 3],
                                source_.normals[vertex * 3 + 1],
                                source_.normals[vertex * 3 + 2]};
                    glm_vec3_normalize(normal);
                    for (size_t axis = 0; axis < 3; ++axis) {
                        block.pos[axis][lane] =
                            source_.positions[vertex * 3 + axis];
                        block.normal[axis][lane] = normal[axis];
                    }
                    block.uv[0][lane] = source_.uvs[vertex * 2];
                    block.uv[1][lane] = source_.uvs[vertex * 2 + 1];
                    for (size_t k = 0; k < influence.count; ++k) {
                        block.weight[k][lane] = influences_[vertex].weight[k];
                    }
                    slot_of[vertex] =
                        static_cast<uint16_t>(local_block * kLanes + lane);
                    if (!morph_vertex_offsets_.empty()) {
                        for (auto d = morph_vertex_offsets_[vertex];
                             d < morph_vertex_offsets_[vertex + 1]; ++d) {
                            auto delta = morph_vertex_deltas_[d];
                            delta.lane = static_cast<uint8_t>(lane);
                            output_.morph_deltas.push_back(delta);
                        }
                    }
                    if (push_ != nullptr && push_->push_share[vertex] > 0.0f &&
                        push_->push_capsules[vertex] != 0) {
                        output_.anti_clip.push_back({
                            .capsules = push_->push_capsules[vertex],
                            .share = push_->push_share[vertex],
                            .allowance = push_->push_allowance[vertex],
                            .margin = push_->push_margin.empty()
                                          ? 0.0f
                                          : push_->push_margin[vertex],
                            .lane = static_cast<uint8_t>(lane),
                        });
                    }
                }
                if (!morph_vertex_offsets_.empty()) {
                    output_.morph_offsets.push_back(
                        static_cast<uint32_t>(output_.morph_deltas.size()));
                }
                if (push_ != nullptr) {
                    output_.anti_clip_offsets.push_back(
                        static_cast<uint32_t>(output_.anti_clip.size()));
                }
            }
        }
        meshlet.block_count = static_cast<uint16_t>(output_.blocks.size() -
                                                    meshlet.first_block);
        for (const auto& quad : quads_) {
            auto& out = output_.quads.emplace_back();
            for (size_t corner = 0; corner < 4; ++corner) {
                out.slot[corner] = slot_of[quad[corner]];
            }
            if (has_pbr_) {
                ComputeMeshTangent(source_, quad, out.tangent);
            }
        }
        meshlet.quad_count = static_cast<uint16_t>(quads_.size());
        meshlets.push_back(meshlet);

        vertices_.clear();
        local_.clear();
        signature_counts_.clear();
        quads_.clear();
        slots_ = 0;
    }

   public:
    MeshBaker(const model_proto::SkinnedMesh& source,
              const std::vector<MeshInfluence>& influences, bool has_pbr,
              const model_proto::SpringBones* springs,
              BakedModelMesh<kWidth>& output)
        : source_(source),
          influences_(influences),
          has_pbr_(has_pbr),
          output_(output) {
        if (springs != nullptr && !springs->push_share.empty() &&
            !springs->body.empty()) {
            push_ = springs;
            output_.anti_clip_offsets.assign(1, 0);
        }
        if (source.morphs.empty()) {
            return;
        }
        // Per source vertex, the deltas of every morph moving it (lane unset).
        const auto vertex_count = source.positions.size() / 3;
        morph_vertex_offsets_.assign(vertex_count + 1, 0);
        for (const auto& morph : source.morphs) {
            for (const auto vertex : morph.vertices) {
                ++morph_vertex_offsets_[vertex + 1];
            }
        }
        std::partial_sum(morph_vertex_offsets_.begin(),
                         morph_vertex_offsets_.end(),
                         morph_vertex_offsets_.begin());
        morph_vertex_deltas_.resize(morph_vertex_offsets_.back());
        auto next = morph_vertex_offsets_;
        for (size_t m = 0; m < source.morphs.size(); ++m) {
            const auto& morph = source.morphs[m];
            for (size_t i = 0; i < morph.vertices.size(); ++i) {
                morph_vertex_deltas_[next[morph.vertices[i]]++] = {
                    .morph = static_cast<uint16_t>(m),
                    .delta = {morph.deltas[i * 3], morph.deltas[i * 3 + 1],
                              morph.deltas[i * 3 + 2]},
                };
            }
        }
        output_.morph_offsets.assign(1, 0);
    }

    // Appends meshlets for one (partition, owner bone) group; returns the
    // indices of the new meshlets within `meshlets`.
    std::vector<uint32_t> AddGroup(std::span<const SourceQuad> quads,
                                   std::vector<Meshlet>& meshlets,
                                   uint32_t bone_index) {
        const auto first = static_cast<uint32_t>(meshlets.size());
        const auto new_vertices = [&](const SourceQuad& quad) {
            std::array<uint32_t, 4> added{};
            size_t added_count = 0;
            for (const auto vertex : quad) {
                if (!local_.contains(vertex) &&
                    std::find(added.begin(), added.begin() + added_count,
                              vertex) == added.begin() + added_count) {
                    added[added_count++] = vertex;
                }
            }
            return std::pair{added, added_count};
        };
        for (const auto& quad : quads) {
            auto [added, added_count] = new_vertices(quad);
            if (quads_.size() + 1 > Meshlet::kMaxQuads ||
                SlotsAfterAdding(std::span(added).first(added_count)) >
                    Meshlet::kMaxSlots) {
                Flush(meshlets, bone_index);
                std::tie(added, added_count) = new_vertices(quad);
            }
            const auto view = std::span(added).first(added_count);
            slots_ = SlotsAfterAdding(view);
            for (const auto vertex : view) {
                local_.emplace(vertex, static_cast<uint16_t>(vertices_.size()));
                vertices_.push_back(vertex);
                ++signature_counts_[influences_[vertex].Signature()];
            }
            quads_.push_back(quad);
        }
        Flush(meshlets, bone_index);
        std::vector<uint32_t> indices(meshlets.size() - first);
        std::iota(indices.begin(), indices.end(), first);
        return indices;
    }
};

enum class MeshTriangleKind : uint64_t {
    kOpaque = 0,
    kTranslucent = 1,
    kOutline = 2,
};

// Key: (MeshTriangleKind << 32) | owner bone. std::map keeps output order
// deterministic, which baked cache identity relies on.
using MeshTriangleGroups =
    std::map<uint64_t, std::vector<std::array<uint32_t, 3>>>;

// Owner: joint with the largest summed weight; ties take the lower bone index.
uint16_t MeshTriangleOwner(const std::array<uint32_t, 3>& vertices,
                           const std::vector<MeshInfluence>& influences) {
    std::array<std::pair<uint16_t, float>, 12> totals{};
    size_t total_count = 0;
    for (const auto vertex : vertices) {
        const auto& influence = influences[vertex];
        for (size_t k = 0; k < influence.count; ++k) {
            auto it = std::find_if(
                totals.begin(), totals.begin() + total_count,
                [&](const auto& entry) {
                    return entry.first == influence.joint[k];
                });
            if (it == totals.begin() + total_count) {
                *it = {influence.joint[k], 0.0f};
                ++total_count;
            }
            it->second += influence.weight[k];
        }
    }
    auto owner = totals[0];
    for (size_t i = 1; i < total_count; ++i) {
        if (totals[i].second > owner.second ||
            (totals[i].second == owner.second &&
             totals[i].first < owner.first)) {
            owner = totals[i];
        }
    }
    return owner.first;
}

MeshTriangleGroups GroupMeshTriangles(
    const model_proto::SkinnedMesh& mesh,
    const std::vector<MeshInfluence>& influences, Texture texture,
    bool force_translucent) {
    MeshTriangleGroups output;
    const auto add = [&](MeshTriangleKind kind,
                         const std::array<uint32_t, 3>& vertices) {
        const auto key = (static_cast<uint64_t>(kind) << 32) |
                         MeshTriangleOwner(vertices, influences);
        output[key].push_back(vertices);
    };
    const auto read = [](const std::vector<uint32_t>& indices, size_t triangle,
                         std::array<uint32_t, 3>& vertices) {
        std::copy_n(indices.data() + triangle * 3, 3, vertices.begin());
        return vertices[0] != vertices[1] && vertices[1] != vertices[2] &&
               vertices[0] != vertices[2];
    };
    std::array<uint32_t, 3> vertices{};
    const auto triangle_count = mesh.triangles.size() / 3;
    for (size_t triangle = 0; triangle < triangle_count; ++triangle) {
        if (!read(mesh.triangles, triangle, vertices)) {
            continue;
        }
        float min_u = 1.0f;
        float max_u = 0.0f;
        float min_v = 1.0f;
        float max_v = 0.0f;
        for (const auto vertex : vertices) {
            const auto u = mesh.uvs[vertex * 2];
            const auto v = mesh.uvs[vertex * 2 + 1];
            min_u = std::min(min_u, u);
            max_u = std::max(max_u, u);
            min_v = std::min(min_v, v);
            max_v = std::max(max_v, v);
        }
        const auto type = ClassifyTexels(texture, min_u, max_u, min_v, max_v);
        if (type == QuadType::kEmpty && !force_translucent) {
            continue;
        }
        // Binary alpha stays opaque: alpha test discards it without sorting.
        const bool translucent =
            force_translucent || type == QuadType::kTranslucent;
        add(translucent ? MeshTriangleKind::kTranslucent
                        : MeshTriangleKind::kOpaque,
            vertices);
    }
    // Outline hulls sample opaque outline texels by contract, so they are
    // never classified or dropped.
    const auto outline_count = mesh.outline_triangles.size() / 3;
    for (size_t triangle = 0; triangle < outline_count; ++triangle) {
        if (read(mesh.outline_triangles, triangle, vertices)) {
            add(MeshTriangleKind::kOutline, vertices);
        }
    }
    return output;
}

template <simd::Width kWidth>
struct BuiltModel {
    BakedModelBones bones;
    BakedModelCubes<kWidth> cubes;
};

template <simd::Width kWidth>
class ModelBaker {
    using CutoutGroup = CubeGroup<kWidth, false>;
    using TranslucentGroup = CubeGroup<kWidth, true>;

    const BakeModelOptions& options_;
    BuiltModel<kWidth> output_;
    std::array<std::vector<std::vector<uint32_t>>, kPartitionKindCount>
        cube_indices_;

    auto& BonePartition(PartitionKind kind, size_t bone_index) {
        auto& bone = output_.bones.list[bone_index];
        switch (kind) {
            case PartitionKind::kCutout:
                return bone.cutout;
            case PartitionKind::kCutoutNoCulling:
                return bone.cutout_no_culling;
            case PartitionKind::kTranslucent:
                return bone.translucent;
            case PartitionKind::kTranslucentCulling:
                return bone.translucent_culling;
            case PartitionKind::kMeshOpaque:
                return bone.mesh_opaque;
            case PartitionKind::kMeshTranslucent:
                return bone.mesh_translucent;
            case PartitionKind::kMeshOutline:
                return bone.mesh_outline;
        }
        std::unreachable();
    }

    struct GroupCounts {
        uint32_t cubes = 0;
        uint32_t quads = 0;
        uint32_t quads_after_culling = 0;
    };

    template <bool kTranslucent>
    static GroupCounts CountGroup(const CubeGroup<kWidth, kTranslucent>& group) {
        GroupCounts counts{.cubes = group.cube_count};
        for (size_t cube = 0; cube < group.cube_count; ++cube) {
            counts.quads += group.cube_attr[cube].quad_count;
            counts.quads_after_culling +=
                group.cube_attr[cube].quad_count_after_culling;
        }
        return counts;
    }

    // Meshlets are never culled on the CPU, so both quad counts agree.
    static GroupCounts CountGroup(const Meshlet& meshlet) {
        return {1, meshlet.quad_count, meshlet.quad_count};
    }

    template <bool kTranslucent>
    auto& Groups(PartitionKind kind) {
        if constexpr (kTranslucent) {
            return kind == PartitionKind::kTranslucent
                       ? output_.cubes.translucent
                       : output_.cubes.translucent_culling;
        } else {
            return kind == PartitionKind::kCutout
                       ? output_.cubes.cutout
                       : output_.cubes.cutout_no_culling;
        }
    }

    template <typename Group>
    static void NormalizeSingleQuadGroups(std::vector<Group>& groups) {
        for (auto& group : groups) {
            group.NormalizeSingleQuadVertexLayout();
        }
    }

    template <bool kTranslucent>
    void AddCube(PartitionKind kind, uint32_t bone_index,
                 const ProcessedCube& source,
                 std::span<const ProcessedQuad* const> quads,
                 uint8_t culling_quad_count) {
        using Group = CubeGroup<kWidth, kTranslucent>;
        auto& groups = Groups<kTranslucent>(kind);
        auto& bone_info = BonePartition(kind, bone_index);
        auto& indices = cube_indices_[static_cast<size_t>(kind)][bone_index];
        Group* group = nullptr;
        if (!groups.empty() && groups.back().bone_index == bone_index &&
            groups.back().cube_count < Group::kCubeGroupCapacity) {
            group = &groups.back();
        } else {
            group = &groups.emplace_back();
            group->bone_index = bone_index;
            indices.push_back(static_cast<uint32_t>(groups.size() - 1));
        }

        const auto cube_index = group->cube_count++;
        auto& cube_attr = group->cube_attr[cube_index];
        cube_attr.quad_count = static_cast<uint8_t>(quads.size());
        cube_attr.quad_count_after_culling = culling_quad_count;
        for (size_t pos_index = 0; pos_index < source.pos.size(); ++pos_index) {
            for (size_t axis = 0; axis < 3; ++axis) {
                group->pos[axis][cube_index * 8 + pos_index] =
                    source.pos[pos_index][axis];
            }
        }
        for (size_t quad_index = 0; quad_index < quads.size(); ++quad_index) {
            const auto& quad = *quads[quad_index];
            const auto attr_index =
                Group::GetQuadAttrIndex(cube_index, quad_index);
            for (size_t axis = 0; axis < 3; ++axis) {
                group->normal[axis][attr_index] = quad.normal[axis];
                if constexpr (kTranslucent) {
                    group->center[axis][attr_index] = quad.center[axis];
                }
            }
            group->plane_d[attr_index] = quad.plane_d;
            group->winding_sign[attr_index] = quad.winding_sign;
            if (options_.has_pbr) {
                for (size_t axis = 0; axis < 4; ++axis) {
                    group->tangent[axis][attr_index] = quad.tangent[axis];
                }
            }
            auto& quad_attr = cube_attr.quad_attr[quad_index];
            for (size_t vertex = 0; vertex < 4; ++vertex) {
                group->vertex_index[vertex][attr_index] =
                    static_cast<uint32_t>(cube_index * 8) +
                    quad.pos_indices[vertex];
                quad_attr.uv[vertex][0] = quad.uv[vertex][0];
                quad_attr.uv[vertex][1] = quad.uv[vertex][1];
                quad_attr.mid_uv[0] += quad.uv[vertex][0] * 0.25f;
                quad_attr.mid_uv[1] += quad.uv[vertex][1] * 0.25f;
            }
        }
        bone_info.full_vertex_count += static_cast<uint32_t>(quads.size() * 4);
        bone_info.culling_vertex_count += culling_quad_count * 4U;
    }

    void AddProcessedCube(uint32_t bone_index, const ProcessedCube& cube) {
        std::array<const ProcessedQuad*, 6> cutout_storage{};
        std::array<const ProcessedQuad*, 6> translucent_storage{};
        size_t cutout_count = 0;
        size_t translucent_count = 0;
        for (const auto& quad : cube.quads) {
            if (options_.force_translucent ||
                quad.type == QuadType::kTranslucent ||
                quad.type == QuadType::kCutout) {
                if (quad.type != QuadType::kEmpty ||
                    options_.force_translucent) {
                    translucent_storage[translucent_count++] = &quad;
                }
            } else if (quad.type == QuadType::kSolid) {
                cutout_storage[cutout_count++] = &quad;
            }
        }
        const auto cutout = std::span(cutout_storage).first(cutout_count);
        const auto translucent =
            std::span(translucent_storage).first(translucent_count);
        const bool full_cube = cube.quads.size() == 6;
        if ((options_.force_culling && full_cube) || cube.inverted) {
            if (!translucent.empty()) {
                const auto cap = cube.inverted
                                     ? std::min<size_t>(5, translucent.size())
                                     : std::min<size_t>(3, translucent.size());
                AddCube<true>(PartitionKind::kTranslucentCulling, bone_index,
                              cube, translucent, static_cast<uint8_t>(cap));
            }
            if (!cutout.empty()) {
                const auto cap = cube.inverted
                                     ? std::min<size_t>(5, cutout.size())
                                     : std::min<size_t>(3, cutout.size());
                AddCube<false>(PartitionKind::kCutout, bone_index, cube, cutout,
                               static_cast<uint8_t>(cap));
            }
            return;
        }
        if (cutout.size() == 6) {
            AddCube<false>(PartitionKind::kCutout, bone_index, cube, cutout, 3);
        } else if (!cutout.empty()) {
            AddCube<false>(
                PartitionKind::kCutoutNoCulling, bone_index, cube, cutout,
                static_cast<uint8_t>(std::min<size_t>(3, cutout.size())));
        }
        if (!translucent.empty()) {
            AddCube<true>(
                PartitionKind::kTranslucent, bone_index, cube, translucent,
                static_cast<uint8_t>(std::min<size_t>(3, translucent.size())));
        }
    }

    template <typename Group>
    void FinishPartition(PartitionKind kind, const std::vector<Group>& groups,
                         size_t& cache_offset) {
        const auto kind_index = static_cast<size_t>(kind);
        auto indices_cache = std::span(output_.bones.cube_indices_cache);
        auto group_info_cache = std::span(output_.bones.cube_group_info_cache);
        for (size_t bone = 0; bone < output_.bones.list.size(); ++bone) {
            const auto& source_indices = cube_indices_[kind_index][bone];
            const auto indices =
                indices_cache.subspan(cache_offset, source_indices.size());
            const auto group_info =
                group_info_cache.subspan(cache_offset, source_indices.size());
            std::copy(source_indices.begin(), source_indices.end(),
                      indices.begin());

            uint32_t cube_count = 0;
            uint32_t quad_count = 0;
            uint32_t quad_count_after_culling = 0;
            for (size_t i = 0; i < indices.size(); ++i) {
                const auto counts = CountGroup(groups[indices[i]]);
                cube_count += counts.cubes;
                quad_count += counts.quads;
                quad_count_after_culling += counts.quads_after_culling;
                group_info[i] = {
                    cube_count,
                    quad_count,
                    quad_count_after_culling,
                };
            }

            auto& destination = BonePartition(kind, bone);
            destination.cube_indices = indices;
            destination.cube_group_info = group_info;
            destination.cube_count = cube_count;
            destination.full_vertex_count = quad_count * 4U;
            destination.culling_vertex_count = quad_count_after_culling * 4U;
            cache_offset += indices.size();
        }
    }

   public:
    ModelBaker(std::span<const BoneHierarchyInfo> hierarchy,
               std::span<const uint16_t> sorted_bone_indices,
               const BakeModelOptions& options)
        : options_(options) {
        output_.bones.sorted_bone_indices.assign(sorted_bone_indices.begin(),
                                                 sorted_bone_indices.end());
        output_.bones.list.resize(hierarchy.size());
        for (size_t i = 0; i < hierarchy.size(); ++i) {
            auto& bone = output_.bones.list[i];
            bone.pivot = hierarchy[i].pivot;
            bone.parent_index = hierarchy[i].parent_index;
            bone.subtree_end = hierarchy[i].subtree_end;
            bone.depth = hierarchy[i].depth;
            bone.solid = true;
        }
        for (auto& partition : cube_indices_) {
            partition.resize(hierarchy.size());
        }
    }

    void AddBone(uint32_t sorted_index,
                 std::span<const model_proto::CubeLegacy> cubes,
                 const QuadInspector& inspector) {
        bool solid = true;
        for (const auto& source : cubes) {
            auto cube = ProcessCube(source, inspector, options_.has_pbr);
            for (const auto& quad : cube.quads) {
                solid &= quad.type == QuadType::kSolid;
            }
            AddProcessedCube(sorted_index, cube);
        }
        output_.bones.list[sorted_index].solid = solid;
    }

    void AddMesh(const model_proto::SkinnedMesh& mesh, Texture texture,
                 const model_proto::SpringBones* springs) {
        const auto vertex_count = mesh.positions.size() / 3;
        if (vertex_count == 0 ||
            (mesh.triangles.empty() && mesh.outline_triangles.empty())) {
            return;
        }
        const auto& sorted = output_.bones.sorted_bone_indices;
        std::vector<uint16_t> sorted_by_original(sorted.size());
        for (size_t index = 0; index < sorted.size(); ++index) {
            sorted_by_original[sorted[index]] = static_cast<uint16_t>(index);
        }
        std::vector<MeshInfluence> influences(vertex_count);
        for (size_t vertex = 0; vertex < vertex_count; ++vertex) {
            influences[vertex] = BuildInfluence(mesh, vertex, sorted_by_original);
        }

        auto& output_mesh = output_.cubes.mesh;
        for (const auto& morph : mesh.morphs) {
            output_mesh.morphs.push_back(
                {.bone = sorted_by_original[morph.bone],
                 .driver = static_cast<MorphDriver>(morph.driver)});
        }
        MeshBaker<kWidth> mesh_baker(mesh, influences, options_.has_pbr,
                                     springs, output_mesh);
        for (const auto& [key, triangles] : GroupMeshTriangles(
                 mesh, influences, texture, options_.force_translucent)) {
            const auto triangle_kind = static_cast<MeshTriangleKind>(key >> 32);
            const auto owner = static_cast<uint32_t>(key & 0xFFFFFFFFu);
            PartitionKind kind = PartitionKind::kMeshOpaque;
            auto* meshlets = &output_mesh.opaque;
            if (triangle_kind == MeshTriangleKind::kTranslucent) {
                kind = PartitionKind::kMeshTranslucent;
                meshlets = &output_mesh.translucent;
            } else if (triangle_kind == MeshTriangleKind::kOutline) {
                kind = PartitionKind::kMeshOutline;
                meshlets = &output_mesh.outline;
            }
            const auto quads = PairTriangles(triangles);
            auto indices = mesh_baker.AddGroup(quads, *meshlets, owner);
            auto& owned = cube_indices_[static_cast<size_t>(kind)][owner];
            owned.insert(owned.end(), indices.begin(), indices.end());
        }

        std::vector<uint8_t> used(output_.bones.list.size());
        for (const auto& block : output_mesh.blocks) {
            for (size_t k = 0; k < block.joint_count; ++k) {
                used[block.joint[k]] = 1;
            }
        }
        for (size_t bone = 0; bone < used.size(); ++bone) {
            if (used[bone] != 0) {
                output_.bones.joint_bone_indices.push_back(
                    static_cast<uint16_t>(bone));
            }
        }
    }

    BuiltModel<kWidth> Finish() {
        NormalizeSingleQuadGroups(output_.cubes.cutout);
        NormalizeSingleQuadGroups(output_.cubes.cutout_no_culling);
        NormalizeSingleQuadGroups(output_.cubes.translucent);
        NormalizeSingleQuadGroups(output_.cubes.translucent_culling);

        size_t index_count = 0;
        for (const auto& partition : cube_indices_) {
            for (const auto& indices : partition) {
                index_count += indices.size();
            }
        }
        output_.bones.cube_indices_cache.resize(index_count);
        output_.bones.cube_group_info_cache.resize(index_count);
        size_t offset = 0;
        FinishPartition(PartitionKind::kCutout, output_.cubes.cutout, offset);
        FinishPartition(PartitionKind::kCutoutNoCulling,
                        output_.cubes.cutout_no_culling, offset);
        FinishPartition(PartitionKind::kTranslucent, output_.cubes.translucent,
                        offset);
        FinishPartition(PartitionKind::kTranslucentCulling,
                        output_.cubes.translucent_culling, offset);
        FinishPartition(PartitionKind::kMeshOpaque, output_.cubes.mesh.opaque,
                        offset);
        FinishPartition(PartitionKind::kMeshTranslucent,
                        output_.cubes.mesh.translucent, offset);
        FinishPartition(PartitionKind::kMeshOutline,
                        output_.cubes.mesh.outline, offset);
        return std::move(output_);
    }
};

template <simd::Width kWidth>
absl::StatusOr<std::unique_ptr<BakedModel>> BakeForWidth(
    const model_proto::GeoModel& model,
    std::span<const uint16_t> sorted_bone_indices,
    Texture texture, const BakeModelOptions& options,
    std::span<const size_t> cube_offsets,
    std::span<const BoneHierarchyInfo> hierarchy) {
    ModelBaker<kWidth> baker(hierarchy, sorted_bone_indices, options);
    QuadInspector inspector(texture, options.origin_ver);
    for (size_t sorted_index = 0; sorted_index < sorted_bone_indices.size();
         ++sorted_index) {
        const auto original_index = sorted_bone_indices[sorted_index];
        const auto first = cube_offsets[original_index];
        const auto count = model.bones[original_index].cubeCount.value_or(0);
        baker.AddBone(static_cast<uint32_t>(sorted_index),
                      std::span(model.cubes.cubes_legacy).subspan(first, count),
                      inspector);
    }
    if (model.mesh.has_value()) {
        baker.AddMesh(*model.mesh, texture,
                      model.springs.has_value() ? &*model.springs : nullptr);
    }
    auto output = baker.Finish();
    auto springs = model.springs.has_value()
                       ? BakeSprings(*model.springs, sorted_bone_indices)
                       : BakedSprings{};
    return std::make_unique<BakedModel>(
        BakedModelInfo{.has_pbr = options.has_pbr}, std::move(output.bones),
        std::move(output.cubes), std::move(springs));
}
}  // namespace

absl::StatusOr<std::unique_ptr<BakedModel>> BakeModel(
    BufferViewR model_data, Texture texture, const BakeModelOptions& options) {
    YSM_RETURN_IF_ERROR(ValidateTexture(texture));
    YSM_DECLARE_OR_RETURN(prepared, PrepareBakeModel(model_data));

    return magic_enum::enum_switch(
        [&](auto kType) {
            return BakeForWidth<simd::width<kType>()>(
                prepared.model, prepared.hierarchy.sorted_bone_indices,
                texture, options, prepared.cube_offsets,
                prepared.hierarchy.list);
        },
        simd::kSupported);
}

absl::Status TryBakeModel(BufferViewR model_data, Texture texture,
                          const BakeModelOptions& options) {
    YSM_RETURN_IF_ERROR(ValidateTexture(texture));
    YSM_DECLARE_OR_RETURN(prepared, PrepareBakeModel(model_data));

    absl::Status first_error;
    const auto try_width = [&]<simd::Width kWidth>() {
        auto result = BakeForWidth<kWidth>(
            prepared.model, prepared.hierarchy.sorted_bone_indices, texture,
            options, prepared.cube_offsets, prepared.hierarchy.list);
        if (!result.ok() && first_error.ok()) {
            first_error = result.status();
        }
    };
    try_width.template operator()<simd::Width::B128>();
    try_width.template operator()<simd::Width::B256>();
    try_width.template operator()<simd::Width::B512>();
    return first_error;
}
}  // namespace ysm::gfx::bake
