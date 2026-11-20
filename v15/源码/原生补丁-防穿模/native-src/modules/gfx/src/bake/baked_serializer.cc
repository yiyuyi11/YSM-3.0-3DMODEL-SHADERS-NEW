#include "gfx/bake/baked_serializer.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <utility>

#include <ylt/struct_pack.hpp>

namespace ysm::gfx::bake {
struct BakedModelSerdeAccess {
    static BakedModelInfo& Info(BakedModel& model) { return model.info_; }

    static BakedModelBones& Bones(BakedModel& model) { return model.bones_; }

    static BakedSprings& Springs(BakedModel& model) { return model.springs_; }

    template <simd::Width kWidth>
    static BakedModelCubes<kWidth>& Cubes(BakedModel& model) {
        return std::any_cast<BakedModelCubes<kWidth>&>(model.cubes_);
    }
};

namespace serde {
struct BakedModelHeader {
    uint8_t vec_bytes = 0;
};

template <simd::Width kWidth>
struct BakedModelBody {
    BakedModel& model;
};

template <simd::Width kWidth>
constexpr std::string_view sp_set_type_name(BakedModelBody<kWidth>*) {
    if constexpr (kWidth == simd::Width::B128) {
        return "ysm.BakedModelBody.128";
    } else if constexpr (kWidth == simd::Width::B256) {
        return "ysm.BakedModelBody.256";
    } else {
        return "ysm.BakedModelBody.512";
    }
}

using BonePartitionInfo = BakedModelBones::BonePartitionInfo;
using BoneInfo = BakedModelBones::BoneInfo;

// Reflect borrowed fields so runtime layouts and array bulk copies stay intact.
template <typename Bone>
struct BoneMetadataView {
    Bone& value;
    YLT_REFL(BoneMetadataView, value.pivot, value.parent_index,
             value.subtree_end, value.depth, value.solid);
};

template <typename Cube>
struct CubeCountsView {
    Cube& value;
    YLT_REFL(CubeCountsView, value.quad_count, value.quad_count_after_culling);
};

template <typename Quad>
struct QuadUvView {
    Quad& value;
    YLT_REFL(QuadUvView, value.uv, value.mid_uv);
};

template <typename Reader, typename View>
struct_pack::err_code ReadFields(Reader& reader, View view) {
    return View::refl_visit_members(view, [&](auto&... fields) {
        struct_pack::err_code ec{};
        ([&] {
            if (!ec) {
                ec = struct_pack::read(reader, fields);
            }
        }(), ...);
        return ec;
    });
}

template <typename Block>
struct MeshBlockView {
    Block& value;
    YLT_REFL(MeshBlockView, value.pos, value.normal, value.uv, value.weight,
             value.joint, value.joint_count, value.vertex_count);
};

template <typename Quad>
struct MeshQuadView {
    Quad& value;
    YLT_REFL(MeshQuadView, value.slot, value.tangent);
};

template <typename Value>
struct MeshletView {
    Value& value;
    YLT_REFL(MeshletView, value.first_block, value.first_quad,
             value.block_count, value.quad_count, value.bone_index);
};

template <typename Value>
struct MeshMorphView {
    Value& value;
    YLT_REFL(MeshMorphView, value.bone, value.driver);
};

template <typename Value>
struct MeshMorphDeltaView {
    Value& value;
    YLT_REFL(MeshMorphDeltaView, value.morph, value.lane, value.delta);
};

template <typename Joint>
struct SpringJointView {
    Joint& value;
    YLT_REFL(SpringJointView, value.bone, value.chain, value.tail,
             value.gravity_dir, value.hit_radius, value.stiffness, value.drag,
             value.gravity_power, value.limit, value.limit_rotation,
             value.limit_angle, value.limit_yaw, value.backstop,
             value.backstop_allowance, value.sync, value.sync_share);
};

template <typename Link>
struct SpringLinkView {
    Link& value;
    YLT_REFL(SpringLinkView, value.a, value.b, value.rest, value.allowance);
};

template <typename Capsule>
struct BodyCapsuleView {
    Capsule& value;
    YLT_REFL(BodyCapsuleView, value.bone, value.head, value.tail, value.radius,
             value.tail_radius);
};

template <typename Value>
struct MeshAntiClipView {
    Value& value;
    YLT_REFL(MeshAntiClipView, value.capsules, value.share, value.allowance,
             value.margin, value.lane);
};

template <typename Collider>
struct SpringColliderView {
    Collider& value;
    YLT_REFL(SpringColliderView, value.bone, value.head, value.tail,
             value.radius, value.type);
};

template <template <typename> class View, typename T>
size_t ViewsSize(const std::vector<T>& values) {
    size_t result =
        struct_pack::get_write_size(static_cast<uint64_t>(values.size()));
    for (const auto& value : values) {
        result += struct_pack::get_write_size(View<const T>{value});
    }
    return result;
}

template <template <typename> class View, typename Writer, typename T>
void WriteViews(Writer& writer, const std::vector<T>& values) {
    struct_pack::write(writer, static_cast<uint64_t>(values.size()));
    for (const auto& value : values) {
        struct_pack::write(writer, View<const T>{value});
    }
}

template <template <typename> class View, typename Reader, typename T>
struct_pack::err_code ReadViews(Reader& reader, std::vector<T>& values) {
    uint64_t size = 0;
    if (auto ec = struct_pack::read(reader, size); ec) {
        return ec;
    }
    if (size > BufferManaged::kMaxSize / sizeof(T)) {
        return struct_pack::errc::invalid_buffer;
    }
    values.resize(static_cast<size_t>(size));
    for (auto& value : values) {
        if (auto ec = ReadFields(reader, View<T>{value}); ec) {
            return ec;
        }
    }
    return {};
}

template <simd::Width kWidth>
size_t MeshSize(const BakedModelMesh<kWidth>& mesh) {
    return ViewsSize<MeshBlockView>(mesh.blocks) +
           ViewsSize<MeshQuadView>(mesh.quads) +
           ViewsSize<MeshletView>(mesh.opaque) +
           ViewsSize<MeshletView>(mesh.translucent) +
           ViewsSize<MeshletView>(mesh.outline) +
           ViewsSize<MeshMorphView>(mesh.morphs) +
           struct_pack::get_write_size(mesh.morph_offsets) +
           ViewsSize<MeshMorphDeltaView>(mesh.morph_deltas) +
           struct_pack::get_write_size(mesh.anti_clip_offsets) +
           ViewsSize<MeshAntiClipView>(mesh.anti_clip);
}

template <typename Writer, simd::Width kWidth>
void WriteMesh(Writer& writer, const BakedModelMesh<kWidth>& mesh) {
    WriteViews<MeshBlockView>(writer, mesh.blocks);
    WriteViews<MeshQuadView>(writer, mesh.quads);
    WriteViews<MeshletView>(writer, mesh.opaque);
    WriteViews<MeshletView>(writer, mesh.translucent);
    WriteViews<MeshletView>(writer, mesh.outline);
    WriteViews<MeshMorphView>(writer, mesh.morphs);
    struct_pack::write(writer, mesh.morph_offsets);
    WriteViews<MeshMorphDeltaView>(writer, mesh.morph_deltas);
    struct_pack::write(writer, mesh.anti_clip_offsets);
    WriteViews<MeshAntiClipView>(writer, mesh.anti_clip);
}

template <typename Reader, simd::Width kWidth>
struct_pack::err_code ReadMesh(Reader& reader, BakedModelMesh<kWidth>& mesh) {
    if (auto ec = ReadViews<MeshBlockView>(reader, mesh.blocks); ec) {
        return ec;
    }
    if (auto ec = ReadViews<MeshQuadView>(reader, mesh.quads); ec) {
        return ec;
    }
    if (auto ec = ReadViews<MeshletView>(reader, mesh.opaque); ec) {
        return ec;
    }
    if (auto ec = ReadViews<MeshletView>(reader, mesh.translucent); ec) {
        return ec;
    }
    if (auto ec = ReadViews<MeshletView>(reader, mesh.outline); ec) {
        return ec;
    }
    if (auto ec = ReadViews<MeshMorphView>(reader, mesh.morphs); ec) {
        return ec;
    }
    if (auto ec = struct_pack::read(reader, mesh.morph_offsets); ec) {
        return ec;
    }
    if (auto ec = ReadViews<MeshMorphDeltaView>(reader, mesh.morph_deltas); ec) {
        return ec;
    }
    if (auto ec = struct_pack::read(reader, mesh.anti_clip_offsets); ec) {
        return ec;
    }
    return ReadViews<MeshAntiClipView>(reader, mesh.anti_clip);
}

size_t SpringsSize(const BakedSprings& springs) {
    return ViewsSize<SpringJointView>(springs.joints) +
           ViewsSize<SpringColliderView>(springs.colliders) +
           struct_pack::get_write_size(springs.chain_offsets) +
           struct_pack::get_write_size(springs.chain_colliders) +
           struct_pack::get_write_size(springs.chain_centers) +
           ViewsSize<BodyCapsuleView>(springs.body) +
           ViewsSize<SpringLinkView>(springs.links) +
           struct_pack::get_write_size(springs.link_stretch) +
           struct_pack::get_write_size(springs.link_compression) +
           struct_pack::get_write_size(springs.link_points) +
           struct_pack::get_write_size(springs.inertia_turn_limit) +
           struct_pack::get_write_size(springs.inertia_move_limit) +
           struct_pack::get_write_size(springs.inertia_damping) +
           struct_pack::get_write_size(springs.body_velocity_seconds);
}

template <typename Writer>
void WriteSprings(Writer& writer, const BakedSprings& springs) {
    WriteViews<SpringJointView>(writer, springs.joints);
    WriteViews<SpringColliderView>(writer, springs.colliders);
    struct_pack::write(writer, springs.chain_offsets);
    struct_pack::write(writer, springs.chain_colliders);
    struct_pack::write(writer, springs.chain_centers);
    WriteViews<BodyCapsuleView>(writer, springs.body);
    WriteViews<SpringLinkView>(writer, springs.links);
    struct_pack::write(writer, springs.link_stretch);
    struct_pack::write(writer, springs.link_compression);
    struct_pack::write(writer, springs.link_points);
    struct_pack::write(writer, springs.inertia_turn_limit);
    struct_pack::write(writer, springs.inertia_move_limit);
    struct_pack::write(writer, springs.inertia_damping);
    struct_pack::write(writer, springs.body_velocity_seconds);
}

template <typename Reader>
struct_pack::err_code ReadSprings(Reader& reader, BakedSprings& springs) {
    if (auto ec = ReadViews<SpringJointView>(reader, springs.joints); ec) {
        return ec;
    }
    if (auto ec = ReadViews<SpringColliderView>(reader, springs.colliders);
        ec) {
        return ec;
    }
    if (auto ec = struct_pack::read(reader, springs.chain_offsets); ec) {
        return ec;
    }
    if (auto ec = struct_pack::read(reader, springs.chain_colliders); ec) {
        return ec;
    }
    if (auto ec = struct_pack::read(reader, springs.chain_centers); ec) {
        return ec;
    }
    if (auto ec = ReadViews<BodyCapsuleView>(reader, springs.body); ec) {
        return ec;
    }
    if (auto ec = ReadViews<SpringLinkView>(reader, springs.links); ec) {
        return ec;
    }
    if (auto ec = struct_pack::read(reader, springs.link_stretch); ec) {
        return ec;
    }
    if (auto ec = struct_pack::read(reader, springs.link_compression); ec) {
        return ec;
    }
    if (auto ec = struct_pack::read(reader, springs.link_points); ec) {
        return ec;
    }
    if (auto ec = struct_pack::read(reader, springs.inertia_turn_limit); ec) {
        return ec;
    }
    if (auto ec = struct_pack::read(reader, springs.inertia_move_limit); ec) {
        return ec;
    }
    if (auto ec = struct_pack::read(reader, springs.inertia_damping); ec) {
        return ec;
    }
    return struct_pack::read(reader, springs.body_velocity_seconds);
}

constexpr size_t kBonePartitionCount = 7;

std::array<const BonePartitionInfo*, kBonePartitionCount> Partitions(
    const BoneInfo& bone) {
    return {&bone.cutout,           &bone.cutout_no_culling,
            &bone.translucent,      &bone.translucent_culling,
            &bone.mesh_opaque,      &bone.mesh_translucent,
            &bone.mesh_outline};
}

std::array<BonePartitionInfo*, kBonePartitionCount> Partitions(
    BoneInfo& bone) {
    return {&bone.cutout,           &bone.cutout_no_culling,
            &bone.translucent,      &bone.translucent_culling,
            &bone.mesh_opaque,      &bone.mesh_translucent,
            &bone.mesh_outline};
}

uint64_t IndexCount(const BakedModelBones& bones) {
    uint64_t result = 0;
    for (const auto& bone : bones.list) {
        for (const auto* partition : Partitions(bone)) {
            result += partition->cube_indices.size();
        }
    }
    return result;
}

template <simd::Width kWidth, bool kTranslucent>
size_t GroupSize(const CubeGroup<kWidth, kTranslucent>& group, bool has_pbr) {
    using Group = CubeGroup<kWidth, kTranslucent>;
    size_t result = struct_pack::get_write_size(group.pos) +
                    struct_pack::get_write_size(group.normal) +
                    struct_pack::get_write_size(group.plane_d) +
                    struct_pack::get_write_size(group.winding_sign) +
                    struct_pack::get_write_size(group.vertex_index) +
                    struct_pack::get_write_size(group.cube_count) +
                    struct_pack::get_write_size(group.bone_index);
    if constexpr (kTranslucent) {
        result += struct_pack::get_write_size(group.center);
    }
    if (has_pbr) {
        result += struct_pack::get_write_size(group.tangent);
    }
    const auto cube_count =
        std::min<size_t>(group.cube_count, Group::kCubeGroupCapacity);
    for (size_t cube_index = 0; cube_index < cube_count; ++cube_index) {
        const auto& cube = group.cube_attr[cube_index];
        result += struct_pack::get_write_size(CubeCountsView{cube});
        const auto quad_count = std::min<size_t>(cube.quad_count, 6);
        for (size_t quad = 0; quad < quad_count; ++quad) {
            const auto& attr = cube.quad_attr[quad];
            result += struct_pack::get_write_size(QuadUvView{attr});
        }
    }
    return result;
}

template <typename Writer, simd::Width kWidth, bool kTranslucent>
void WriteGroup(Writer& writer, const CubeGroup<kWidth, kTranslucent>& group,
                bool has_pbr) {
    using Group = CubeGroup<kWidth, kTranslucent>;
    struct_pack::write(writer, group.pos);
    if constexpr (kTranslucent) {
        struct_pack::write(writer, group.center);
    }
    struct_pack::write(writer, group.normal);
    struct_pack::write(writer, group.plane_d);
    struct_pack::write(writer, group.winding_sign);
    if (has_pbr) {
        struct_pack::write(writer, group.tangent);
    }
    struct_pack::write(writer, group.vertex_index);
    struct_pack::write(writer, group.cube_count);
    struct_pack::write(writer, group.bone_index);
    const auto cube_count =
        std::min<size_t>(group.cube_count, Group::kCubeGroupCapacity);
    for (size_t cube_index = 0; cube_index < cube_count; ++cube_index) {
        const auto& cube = group.cube_attr[cube_index];
        struct_pack::write(writer, CubeCountsView{cube});
        const auto quad_count = std::min<size_t>(cube.quad_count, 6);
        for (size_t quad = 0; quad < quad_count; ++quad) {
            const auto& attr = cube.quad_attr[quad];
            struct_pack::write(writer, QuadUvView{attr});
        }
    }
}

template <typename Reader, simd::Width kWidth, bool kTranslucent>
struct_pack::err_code ReadGroup(Reader& reader,
                                CubeGroup<kWidth, kTranslucent>& group,
                                bool has_pbr) {
    using Group = CubeGroup<kWidth, kTranslucent>;
    if (auto ec = struct_pack::read(reader, group.pos); ec) {
        return ec;
    }
    if constexpr (kTranslucent) {
        if (auto ec = struct_pack::read(reader, group.center); ec) {
            return ec;
        }
    }
    if (auto ec = struct_pack::read(reader, group.normal); ec) {
        return ec;
    }
    if (auto ec = struct_pack::read(reader, group.plane_d); ec) {
        return ec;
    }
    if (auto ec = struct_pack::read(reader, group.winding_sign); ec) {
        return ec;
    }
    if (has_pbr) {
        if (auto ec = struct_pack::read(reader, group.tangent); ec) {
            return ec;
        }
    }
    if (auto ec = struct_pack::read(reader, group.vertex_index); ec) {
        return ec;
    }
    if (auto ec = struct_pack::read(reader, group.cube_count); ec) {
        return ec;
    }
    if (auto ec = struct_pack::read(reader, group.bone_index); ec) {
        return ec;
    }
    if (group.cube_count == 0 ||
        group.cube_count > Group::kCubeGroupCapacity) {
        return struct_pack::errc::invalid_buffer;
    }
    for (size_t cube_index = 0; cube_index < group.cube_count; ++cube_index) {
        auto& cube = group.cube_attr[cube_index];
        if (auto ec = ReadFields(reader, CubeCountsView{cube}); ec) {
            return ec;
        }
        if (cube.quad_count == 0 || cube.quad_count > 6 ||
            cube.quad_count_after_culling > cube.quad_count) {
            return struct_pack::errc::invalid_buffer;
        }
        for (size_t quad = 0; quad < cube.quad_count; ++quad) {
            auto& attr = cube.quad_attr[quad];
            if (auto ec = ReadFields(reader, QuadUvView{attr}); ec) {
                return ec;
            }
        }
    }
    return {};
}

template <typename Group>
size_t GroupsSize(const std::vector<Group>& groups, bool has_pbr) {
    size_t result =
        struct_pack::get_write_size(static_cast<uint64_t>(groups.size()));
    for (const auto& group : groups) {
        result += GroupSize(group, has_pbr);
    }
    return result;
}

template <typename Writer, typename Group>
void WriteGroups(Writer& writer, const std::vector<Group>& groups,
                 bool has_pbr) {
    struct_pack::write(writer, static_cast<uint64_t>(groups.size()));
    for (const auto& group : groups) {
        WriteGroup(writer, group, has_pbr);
    }
}

template <typename Reader, typename Group>
struct_pack::err_code ReadGroups(Reader& reader, std::vector<Group>& groups,
                                 bool has_pbr) {
    uint64_t size = 0;
    if (auto ec = struct_pack::read(reader, size); ec) {
        return ec;
    }
    if (size > BufferManaged::kMaxSize / sizeof(Group)) {
        return struct_pack::errc::invalid_buffer;
    }
    groups.resize(static_cast<size_t>(size));
    for (auto& group : groups) {
        if (auto ec = ReadGroup(reader, group, has_pbr); ec) {
            return ec;
        }
    }
    return {};
}

template <simd::Width kWidth>
size_t CubesSize(const BakedModelCubes<kWidth>& cubes, bool has_pbr) {
    return GroupsSize(cubes.cutout, has_pbr) +
           GroupsSize(cubes.cutout_no_culling, has_pbr) +
           GroupsSize(cubes.translucent, has_pbr) +
           GroupsSize(cubes.translucent_culling, has_pbr);
}

template <typename Writer, simd::Width kWidth>
void WriteCubes(Writer& writer, const BakedModelCubes<kWidth>& cubes,
                bool has_pbr) {
    WriteGroups(writer, cubes.cutout, has_pbr);
    WriteGroups(writer, cubes.cutout_no_culling, has_pbr);
    WriteGroups(writer, cubes.translucent, has_pbr);
    WriteGroups(writer, cubes.translucent_culling, has_pbr);
}

template <typename Reader, simd::Width kWidth>
struct_pack::err_code ReadCubes(Reader& reader,
                                BakedModelCubes<kWidth>& cubes,
                                bool has_pbr) {
    if (auto ec = ReadGroups(reader, cubes.cutout, has_pbr); ec) {
        return ec;
    }
    if (auto ec = ReadGroups(reader, cubes.cutout_no_culling, has_pbr); ec) {
        return ec;
    }
    if (auto ec = ReadGroups(reader, cubes.translucent, has_pbr); ec) {
        return ec;
    }
    return ReadGroups(reader, cubes.translucent_culling, has_pbr);
}

template <simd::Width kWidth>
size_t sp_get_needed_size(const BakedModelBody<kWidth>& body) {
    auto& model = body.model;
    const auto& info = BakedModelSerdeAccess::Info(model);
    const auto& bones = BakedModelSerdeAccess::Bones(model);
    const auto& cubes = BakedModelSerdeAccess::Cubes<kWidth>(model);
    const auto bone_count = static_cast<uint64_t>(bones.list.size());
    const auto index_count = IndexCount(bones);

    size_t result = struct_pack::get_write_size(info) +
                    CubesSize(cubes, info.has_pbr) + MeshSize(cubes.mesh) +
                    struct_pack::get_write_size(bones.sorted_bone_indices) +
                    struct_pack::get_write_size(bones.joint_bone_indices) +
                    SpringsSize(BakedModelSerdeAccess::Springs(model)) +
                    struct_pack::get_write_size(bone_count) +
                    struct_pack::get_write_size(index_count);
    for (const auto& bone : bones.list) {
        result += struct_pack::get_write_size(BoneMetadataView{bone});
        for (const auto* partition : Partitions(bone)) {
            const auto size =
                static_cast<uint64_t>(partition->cube_indices.size());
            result += struct_pack::get_write_size(size) +
                      struct_pack::get_write_size(
                          partition->cube_indices.data(),
                          partition->cube_indices.size());
        }
    }
    return result;
}

template <typename Writer, simd::Width kWidth>
void sp_serialize_to(Writer& writer, const BakedModelBody<kWidth>& body) {
    auto& model = body.model;
    const auto& info = BakedModelSerdeAccess::Info(model);
    const auto& bones = BakedModelSerdeAccess::Bones(model);
    const auto& cubes = BakedModelSerdeAccess::Cubes<kWidth>(model);
    const auto bone_count = static_cast<uint64_t>(bones.list.size());
    const auto index_count = IndexCount(bones);

    struct_pack::write(writer, info);
    WriteCubes(writer, cubes, info.has_pbr);
    WriteMesh(writer, cubes.mesh);
    struct_pack::write(writer, bones.sorted_bone_indices);
    struct_pack::write(writer, bones.joint_bone_indices);
    WriteSprings(writer, BakedModelSerdeAccess::Springs(model));
    struct_pack::write(writer, bone_count);
    struct_pack::write(writer, index_count);
    for (const auto& bone : bones.list) {
        struct_pack::write(writer, BoneMetadataView{bone});
        for (const auto* partition : Partitions(bone)) {
            const auto size =
                static_cast<uint64_t>(partition->cube_indices.size());
            struct_pack::write(writer, size);
            struct_pack::write(writer, partition->cube_indices.data(),
                               partition->cube_indices.size());
        }
    }
}

template <typename Reader, typename T>
struct_pack::err_code Read(Reader& reader, T& value) {
    return struct_pack::read(reader, value);
}

template <typename Reader, simd::Width kWidth>
struct_pack::err_code sp_deserialize_to(Reader& reader,
                                        BakedModelBody<kWidth>& body) {
    auto& model = body.model;
    auto& info = BakedModelSerdeAccess::Info(model);
    auto& bones = BakedModelSerdeAccess::Bones(model);
    auto& cubes = BakedModelSerdeAccess::Cubes<kWidth>(model);
    uint64_t bone_count = 0;
    uint64_t index_count = 0;

    if (auto ec = Read(reader, info); ec) {
        return ec;
    }
    if (auto ec = ReadCubes(reader, cubes, info.has_pbr); ec) {
        return ec;
    }
    if (auto ec = ReadMesh(reader, cubes.mesh); ec) {
        return ec;
    }
    if (auto ec = Read(reader, bones.sorted_bone_indices); ec) {
        return ec;
    }
    if (auto ec = Read(reader, bones.joint_bone_indices); ec) {
        return ec;
    }
    if (auto ec = ReadSprings(reader, BakedModelSerdeAccess::Springs(model));
        ec) {
        return ec;
    }
    if (auto ec = Read(reader, bone_count); ec) {
        return ec;
    }
    if (auto ec = Read(reader, index_count); ec) {
        return ec;
    }
    if (bone_count > std::numeric_limits<uint32_t>::max() ||
        index_count > BufferManaged::kMaxSize / sizeof(uint32_t)) {
        return struct_pack::errc::invalid_buffer;
    }

    constexpr BoneInfo kEmptyBone{};
    constexpr size_t kMinBoneBytes =
        struct_pack::get_write_size(BoneMetadataView{kEmptyBone}) +
        kBonePartitionCount * sizeof(uint64_t);
    if constexpr (struct_pack::checkable_reader_t<Reader>) {
        if (bone_count > std::numeric_limits<size_t>::max() / kMinBoneBytes ||
            !reader.check(static_cast<size_t>(bone_count) * kMinBoneBytes +
                          static_cast<size_t>(index_count) * sizeof(uint32_t))) {
            return struct_pack::errc::no_buffer_space;
        }
    }

    bones.list.clear();
    bones.cube_indices_cache.clear();
    bones.cube_group_info_cache.clear();
    bones.list.resize(static_cast<size_t>(bone_count));
    bones.cube_indices_cache.resize(static_cast<size_t>(index_count));
    bones.cube_group_info_cache.resize(static_cast<size_t>(index_count));
    auto indices = std::span(bones.cube_indices_cache);
    auto group_info = std::span(bones.cube_group_info_cache);
    size_t offset = 0;
    for (auto& bone : bones.list) {
        if (auto ec = ReadFields(reader, BoneMetadataView{bone}); ec) {
            return ec;
        }
        for (auto* partition : Partitions(bone)) {
            uint64_t partition_size = 0;
            if (auto ec = Read(reader, partition_size); ec) {
                return ec;
            }
            if (partition_size > index_count - offset) {
                return struct_pack::errc::invalid_buffer;
            }
            const auto size = static_cast<size_t>(partition_size);
            partition->cube_indices = indices.subspan(offset, size);
            partition->cube_group_info = group_info.subspan(offset, size);
            if (auto ec = struct_pack::read(
                    reader, bones.cube_indices_cache.data() + offset, size);
                ec) {
                return ec;
            }
            offset += size;
        }
    }
    if (offset != index_count) {
        return struct_pack::errc::invalid_buffer;
    }
    return {};
}
}  // namespace serde

namespace {
absl::Status Corrupt() { return DataCorruption(); }

template <simd::Width kWidth, bool kTranslucent>
absl::Status ValidateGroups(
    const std::vector<CubeGroup<kWidth, kTranslucent>>& groups,
    size_t bone_count) {
    using Group = CubeGroup<kWidth, kTranslucent>;
    for (const auto& group : groups) {
        if (group.cube_count == 0 ||
            group.cube_count > Group::kCubeGroupCapacity ||
            group.bone_index >= bone_count) {
            return Corrupt();
        }
        for (size_t cube_index = 0; cube_index < group.cube_count;
             ++cube_index) {
            const auto& cube = group.cube_attr[cube_index];
            if (cube.quad_count == 0 || cube.quad_count > 6 ||
                cube.quad_count_after_culling > cube.quad_count) {
                return Corrupt();
            }
            for (size_t quad = 0; quad < cube.quad_count; ++quad) {
                const auto attr_index =
                    Group::GetQuadAttrIndex(cube_index, quad);
                for (size_t vertex = 0; vertex < 4; ++vertex) {
                    const auto vertex_index =
                        group.vertex_index[vertex][attr_index];
                    if (vertex_index < cube_index * 8 ||
                        vertex_index >= (cube_index + 1) * 8) {
                        return Corrupt();
                    }
                }
            }
        }
    }
    return OkStatus();
}

struct GroupCounts {
    uint64_t cubes = 0;
    uint64_t quads = 0;
    uint64_t quads_after_culling = 0;
};

template <simd::Width kWidth, bool kTranslucent>
GroupCounts CountGroup(const CubeGroup<kWidth, kTranslucent>& group) {
    GroupCounts counts{.cubes = group.cube_count};
    for (size_t cube = 0; cube < group.cube_count; ++cube) {
        counts.quads += group.cube_attr[cube].quad_count;
        counts.quads_after_culling +=
            group.cube_attr[cube].quad_count_after_culling;
    }
    return counts;
}

GroupCounts CountGroup(const Meshlet& meshlet) {
    return {1, meshlet.quad_count, meshlet.quad_count};
}

template <simd::Width kWidth>
absl::Status ValidateMesh(const BakedModelMesh<kWidth>& mesh,
                          const BakedModelBones& bones) {
    using Block = MeshVertexBlock<kWidth>;
    const auto bone_count = bones.list.size();
    const auto& joints = bones.joint_bone_indices;
    for (size_t i = 0; i < joints.size(); ++i) {
        if (joints[i] >= bone_count || (i != 0 && joints[i] <= joints[i - 1])) {
            return Corrupt();
        }
    }
    for (const auto& block : mesh.blocks) {
        if (block.joint_count == 0 || block.joint_count > Block::kMaxJoints ||
            block.vertex_count == 0 || block.vertex_count > Block::kLanes) {
            return Corrupt();
        }
        for (size_t k = 0; k < block.joint_count; ++k) {
            if (!std::ranges::binary_search(joints, block.joint[k])) {
                return Corrupt();
            }
        }
    }
    for (const auto* meshlets :
         {&mesh.opaque, &mesh.translucent, &mesh.outline}) {
        for (const auto& meshlet : *meshlets) {
            const auto slots =
                static_cast<size_t>(meshlet.block_count) * Block::kLanes;
            if (meshlet.block_count == 0 || slots > Meshlet::kMaxSlots ||
                meshlet.first_block > mesh.blocks.size() ||
                meshlet.block_count > mesh.blocks.size() - meshlet.first_block ||
                meshlet.quad_count == 0 ||
                meshlet.quad_count > Meshlet::kMaxQuads ||
                meshlet.first_quad > mesh.quads.size() ||
                meshlet.quad_count > mesh.quads.size() - meshlet.first_quad ||
                meshlet.bone_index >= bone_count) {
                return Corrupt();
            }
            for (size_t quad = 0; quad < meshlet.quad_count; ++quad) {
                for (const auto slot :
                     mesh.quads[meshlet.first_quad + quad].slot) {
                    if (slot >= slots) {
                        return Corrupt();
                    }
                }
            }
        }
    }
    for (const auto& morph : mesh.morphs) {
        if (morph.bone >= bone_count ||
            static_cast<uint8_t>(morph.driver) > 1) {
            return Corrupt();
        }
    }
    const auto& offsets = mesh.morph_offsets;
    if (offsets.empty()) {
        if (!mesh.morph_deltas.empty() || !mesh.morphs.empty()) {
            return Corrupt();
        }
        return OkStatus();
    }
    if (offsets.size() != mesh.blocks.size() + 1 || offsets.front() != 0 ||
        offsets.back() != mesh.morph_deltas.size() ||
        !std::ranges::is_sorted(offsets)) {
        return Corrupt();
    }
    for (size_t block = 0; block < mesh.blocks.size(); ++block) {
        for (auto d = offsets[block]; d < offsets[block + 1]; ++d) {
            const auto& delta = mesh.morph_deltas[d];
            if (delta.morph >= mesh.morphs.size() ||
                delta.lane >= mesh.blocks[block].vertex_count ||
                !std::ranges::all_of(delta.delta, [](float value) {
                    return std::isfinite(value);
                })) {
                return Corrupt();
            }
        }
    }
    return OkStatus();
}

template <typename Group>
absl::Status ValidatePartition(
    BakedModelBones::BonePartitionInfo& partition,
    const std::vector<Group>& groups, size_t bone_index,
    std::span<BakedModelBones::CubeGroupInfo> group_info_cache,
    size_t& cache_offset) {
    if (cache_offset > group_info_cache.size() ||
        partition.cube_group_info.size() != partition.cube_indices.size() ||
        partition.cube_indices.size() >
            group_info_cache.size() - cache_offset) {
        return Corrupt();
    }
    uint64_t cube_count = 0;
    uint64_t quad_count = 0;
    uint64_t culled_quad_count = 0;
    for (size_t position = 0; position < partition.cube_indices.size();
         ++position) {
        const auto index = partition.cube_indices[position];
        if (index >= groups.size() || groups[index].bone_index != bone_index ||
            (position != 0 && index <= partition.cube_indices[position - 1])) {
            return Corrupt();
        }
        const auto counts = CountGroup(groups[index]);
        cube_count += counts.cubes;
        quad_count += counts.quads;
        culled_quad_count += counts.quads_after_culling;
        if (cube_count > std::numeric_limits<uint32_t>::max() ||
            quad_count > std::numeric_limits<uint32_t>::max() ||
            culled_quad_count > std::numeric_limits<uint32_t>::max()) {
            return Corrupt();
        }
        group_info_cache[cache_offset + position] = {
            static_cast<uint32_t>(cube_count),
            static_cast<uint32_t>(quad_count),
            static_cast<uint32_t>(culled_quad_count),
        };
    }
    partition.cube_count = static_cast<uint32_t>(cube_count);
    partition.full_vertex_count = static_cast<uint32_t>(quad_count * 4);
    partition.culling_vertex_count =
        static_cast<uint32_t>(culled_quad_count * 4);
    cache_offset += partition.cube_indices.size();
    return OkStatus();
}

absl::Status ValidateHierarchy(BakedModelBones& bones) {
    const size_t bone_count = bones.list.size();
    if (bones.sorted_bone_indices.size() != bone_count) {
        return Corrupt();
    }
    std::vector<bool> seen_original_indices(bone_count);
    for (const auto original : bones.sorted_bone_indices) {
        if (original >= bone_count || seen_original_indices[original]) {
            return Corrupt();
        }
        seen_original_indices[original] = true;
    }

    for (size_t index = 0; index < bone_count; ++index) {
        const auto& bone = bones.list[index];
        if (bone.subtree_end <= index || bone.subtree_end > bone_count ||
            (bone.parent_index != UINT32_MAX && bone.parent_index >= index)) {
            return Corrupt();
        }
        const auto expected_depth =
            bone.parent_index == UINT32_MAX
                ? 0ULL
                : static_cast<uint64_t>(bones.list[bone.parent_index].depth) +
                      1;
        if (bone.depth != expected_depth ||
            std::ranges::any_of(
                bone.pivot,
                [](float value) { return !std::isfinite(value); })) {
            return Corrupt();
        }
    }

    std::vector<uint32_t> open_bones;
    open_bones.reserve(bone_count);
    for (uint32_t index = 0; index < bone_count; ++index) {
        const auto parent = bones.list[index].parent_index;
        while (!open_bones.empty() && open_bones.back() != parent) {
            if (bones.list[open_bones.back()].subtree_end != index) {
                return Corrupt();
            }
            open_bones.pop_back();
        }
        if (parent != UINT32_MAX &&
            (open_bones.empty() || open_bones.back() != parent)) {
            return Corrupt();
        }
        open_bones.push_back(index);
    }
    while (!open_bones.empty()) {
        if (bones.list[open_bones.back()].subtree_end != bone_count) {
            return Corrupt();
        }
        open_bones.pop_back();
    }
    return OkStatus();
}

absl::Status ValidateSprings(const BakedSprings& springs, size_t bone_count) {
    const auto finite = [](std::span<const float> values) {
        return std::ranges::all_of(
            values, [](float value) { return std::isfinite(value); });
    };
    const auto chain_count = springs.chain_offsets.empty()
                                 ? size_t{0}
                                 : springs.chain_offsets.size() - 1;
    if (springs.chain_offsets.empty()
            ? !springs.joints.empty() || !springs.chain_colliders.empty()
            : springs.chain_offsets.front() != 0 ||
                  springs.chain_offsets.back() !=
                      springs.chain_colliders.size() ||
                  !std::ranges::is_sorted(springs.chain_offsets)) {
        return Corrupt();
    }
    if (springs.chain_centers.size() != chain_count ||
        std::ranges::any_of(springs.chain_centers, [&](uint32_t center) {
            return center > bone_count;
        })) {
        return Corrupt();
    }
    for (size_t i = 0; i < springs.joints.size(); ++i) {
        const auto& joint = springs.joints[i];
        const std::array scalars{joint.hit_radius, joint.stiffness, joint.drag,
                                 joint.gravity_power, joint.limit_angle,
                                 joint.limit_yaw};
        if (joint.bone >= bone_count || joint.chain >= chain_count ||
            (i != 0 && joint.bone <= springs.joints[i - 1].bone) ||
            !finite(joint.tail) || !finite(joint.gravity_dir) ||
            !finite(scalars) || joint.drag < 0.0f || joint.drag > 1.0f ||
            joint.limit > SpringLimit::kSpherical ||
            !finite(joint.limit_rotation) ||
            joint.backstop > springs.body.size() ||
            !std::isfinite(joint.backstop_allowance) ||
            joint.sync > springs.body.size() || !std::isfinite(joint.sync_share)) {
            return Corrupt();
        }
    }
    for (const auto& collider : springs.colliders) {
        if (collider.bone >= bone_count || !finite(collider.head) ||
            !finite(collider.tail) || !std::isfinite(collider.radius) ||
            collider.type > SpringColliderType::kPlane) {
            return Corrupt();
        }
    }
    if (std::ranges::any_of(springs.chain_colliders, [&](uint32_t collider) {
            return collider >= springs.colliders.size();
        })) {
        return Corrupt();
    }
    if (springs.body.size() > kMaxBodyCapsules) {
        return Corrupt();
    }
    for (const auto& capsule : springs.body) {
        if (capsule.bone >= bone_count || !finite(capsule.head) ||
            !finite(capsule.tail) || !std::isfinite(capsule.radius) ||
            !std::isfinite(capsule.tail_radius)) {
            return Corrupt();
        }
    }
    if (std::ranges::any_of(springs.links,
                            [&](const SpringLink& link) {
                                return link.a >= springs.joints.size() ||
                                       link.b >= springs.joints.size() ||
                                       link.a == link.b ||
                                       !std::isfinite(link.rest) ||
                                       !std::isfinite(link.allowance);
                            }) ||
        !std::isfinite(springs.link_stretch) ||
        !std::isfinite(springs.link_compression) || springs.link_points > 16 ||
        !(springs.inertia_turn_limit >= 0.0f) ||
        !(springs.inertia_move_limit >= 0.0f) ||
        !(springs.inertia_damping >= 0.0f && springs.inertia_damping <= 1.0f) ||
        !(springs.body_velocity_seconds >= 0.0f &&
          springs.body_velocity_seconds <= 1.0f) ||
        std::isinf(springs.inertia_turn_limit) ||
        std::isinf(springs.inertia_move_limit)) {
        return Corrupt();
    }
    return OkStatus();
}

template <simd::Width kWidth>
absl::Status ValidateAntiClip(const BakedModelMesh<kWidth>& mesh,
                              const BakedSprings& springs) {
    const auto& offsets = mesh.anti_clip_offsets;
    if (offsets.empty()) {
        return mesh.anti_clip.empty() ? OkStatus() : Corrupt();
    }
    if (offsets.size() != mesh.blocks.size() + 1 || offsets.front() != 0 ||
        offsets.back() != mesh.anti_clip.size() ||
        !std::ranges::is_sorted(offsets)) {
        return Corrupt();
    }
    const auto capsule_bits = springs.body.size() >= 32
                                  ? ~uint32_t{0}
                                  : (uint32_t{1} << springs.body.size()) - 1;
    for (size_t block = 0; block < mesh.blocks.size(); ++block) {
        for (auto i = offsets[block]; i < offsets[block + 1]; ++i) {
            const auto& lane = mesh.anti_clip[i];
            if (lane.lane >= mesh.blocks[block].vertex_count ||
                (lane.capsules & ~capsule_bits) != 0 ||
                !std::isfinite(lane.share) || !std::isfinite(lane.allowance) ||
                !std::isfinite(lane.margin)) {
                return Corrupt();
            }
        }
    }
    return OkStatus();
}

template <simd::Width kWidth>
absl::Status ValidateModel(BakedModel& model) {
    auto& bones = BakedModelSerdeAccess::Bones(model);
    auto& cubes = BakedModelSerdeAccess::Cubes<kWidth>(model);
    YSM_RETURN_IF_ERROR(ValidateHierarchy(bones));
    YSM_RETURN_IF_ERROR(
        ValidateSprings(BakedModelSerdeAccess::Springs(model), bones.list.size()));
    YSM_RETURN_IF_ERROR(ValidateMesh(cubes.mesh, bones));
    YSM_RETURN_IF_ERROR(
        ValidateAntiClip(cubes.mesh, BakedModelSerdeAccess::Springs(model)));
    YSM_RETURN_IF_ERROR(ValidateGroups(cubes.cutout, bones.list.size()));
    YSM_RETURN_IF_ERROR(
        ValidateGroups(cubes.cutout_no_culling, bones.list.size()));
    YSM_RETURN_IF_ERROR(ValidateGroups(cubes.translucent, bones.list.size()));
    YSM_RETURN_IF_ERROR(
        ValidateGroups(cubes.translucent_culling, bones.list.size()));

    auto group_info_cache = std::span(bones.cube_group_info_cache);
    size_t cache_offset = 0;
    for (size_t index = 0; index < bones.list.size(); ++index) {
        auto& bone = bones.list[index];
        YSM_RETURN_IF_ERROR(ValidatePartition(bone.cutout, cubes.cutout, index,
                                              group_info_cache, cache_offset));
        YSM_RETURN_IF_ERROR(ValidatePartition(
            bone.cutout_no_culling, cubes.cutout_no_culling, index,
            group_info_cache, cache_offset));
        YSM_RETURN_IF_ERROR(ValidatePartition(
            bone.translucent, cubes.translucent, index, group_info_cache,
            cache_offset));
        YSM_RETURN_IF_ERROR(ValidatePartition(
            bone.translucent_culling, cubes.translucent_culling, index,
            group_info_cache, cache_offset));
        YSM_RETURN_IF_ERROR(ValidatePartition(
            bone.mesh_opaque, cubes.mesh.opaque, index, group_info_cache,
            cache_offset));
        YSM_RETURN_IF_ERROR(ValidatePartition(
            bone.mesh_translucent, cubes.mesh.translucent, index,
            group_info_cache, cache_offset));
        YSM_RETURN_IF_ERROR(ValidatePartition(
            bone.mesh_outline, cubes.mesh.outline, index, group_info_cache,
            cache_offset));
    }
    if (cache_offset != group_info_cache.size()) {
        return Corrupt();
    }
    return OkStatus();
}

template <simd::Width kWidth>
absl::StatusOr<std::unique_ptr<BakedModel>> ReadForWidth(const char* data,
                                                         size_t size) {
    BakedModel model(
        BakedModelInfo{}, BakedModelBones{}, BakedModelCubes<kWidth>{});
    serde::BakedModelBody<kWidth> body{model};
    size_t consumed = 0;
    if (struct_pack::deserialize_to(body, data, size, consumed) ||
        consumed != size) {
        return Corrupt();
    }
    YSM_RETURN_IF_ERROR(ValidateModel<kWidth>(model));
    return std::make_unique<BakedModel>(std::move(model));
}
}  // namespace

size_t BakedModelSerializationBytes(const BakedModel& model) {
    return model.VisitCubes([&]<simd::Width kWidth>(
                                const BakedModelCubes<kWidth>&) {
        const auto header = struct_pack::get_needed_size(
            serde::BakedModelHeader{static_cast<uint8_t>(simd::bytes(kWidth))});
        const auto body = struct_pack::get_needed_size(
            serde::BakedModelBody<kWidth>{const_cast<BakedModel&>(model)});
        return header.size() + body.size();
    });
}

BufferManaged SerializeBakedModel(const BakedModel& model) {
    BufferManaged output;
    output.reserve(BakedModelSerializationBytes(model));
    model.VisitCubes(
        [&]<simd::Width kWidth>(const BakedModelCubes<kWidth>&) {
            const auto header = struct_pack::serialize(
                serde::BakedModelHeader{static_cast<uint8_t>(
                    simd::bytes(kWidth))});
            struct_pack::serialize_to_with_offset(
                output, header.size(), serde::BakedModelBody<kWidth>{
                    const_cast<BakedModel&>(model)});
            std::memcpy(output.data(), header.data(), header.size());
        });
    return output;
}

absl::StatusOr<std::unique_ptr<BakedModel>> ReadBakedModel(
    BufferViewR baked_model_data) {
    serde::BakedModelHeader header;
    size_t header_size = 0;
    const auto* data = reinterpret_cast<const char*>(baked_model_data.data());
    if (struct_pack::deserialize_to(header, data, baked_model_data.size(),
                                    header_size) ||
        header_size >= baked_model_data.size()) {
        return Corrupt();
    }

    const auto expected_vec_bytes = magic_enum::enum_switch(
        [](auto kType) {
            return static_cast<uint8_t>(simd::bytes(simd::width<kType>()));
        },
        simd::kSupported);
    if (header.vec_bytes != expected_vec_bytes) {
        return Corrupt();
    }

    data += header_size;
    const auto size = baked_model_data.size() - header_size;
    switch (header.vec_bytes) {
        case simd::bytes(simd::Width::B128):
            return ReadForWidth<simd::Width::B128>(data, size);
        case simd::bytes(simd::Width::B256):
            return ReadForWidth<simd::Width::B256>(data, size);
        case simd::bytes(simd::Width::B512):
            return ReadForWidth<simd::Width::B512>(data, size);
        default:
            return Corrupt();
    }
}
}  // namespace ysm::gfx::bake
