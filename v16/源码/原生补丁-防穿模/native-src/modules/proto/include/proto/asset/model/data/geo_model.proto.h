#pragma once
#include <optional>
#include <ylt/struct_pb.hpp>

namespace ysm::proto::asset::model::data {

struct CubeLegacy : public iguana::base_impl<CubeLegacy>  {
	CubeLegacy() = default;
	CubeLegacy(uint32_t a, std::vector<float> b, std::vector<uint32_t> c, std::vector<float> d, std::vector<uint32_t> e, std::vector<float> f) : faceCount(a), pos(std::move(b)), pos_indices(std::move(c)), uv(std::move(d)), uv_indices(std::move(e)), normal(std::move(f)) {}
	uint32_t faceCount = 0;
	std::vector<float>pos;
	std::vector<uint32_t>pos_indices;
	std::vector<float>uv;
	std::vector<uint32_t>uv_indices;
	std::vector<float>normal;
};
YLT_REFL(CubeLegacy, faceCount, pos, pos_indices, uv, uv_indices, normal);

struct Cubes : public iguana::base_impl<Cubes>  {
	Cubes() = default;
	Cubes(std::vector<CubeLegacy>a) : cubes_legacy(std::move(a)) {}
	std::vector<CubeLegacy>cubes_legacy;
};
YLT_REFL(Cubes, cubes_legacy);

struct Bone : public iguana::base_impl<Bone>  {
	Bone() = default;
	Bone(std::string a, std::optional<std::string> b, std::vector<float> c, std::vector<float> d, std::optional<bool> e, std::optional<uint32_t> f) : name(std::move(a)), parent(std::move(b)), pivot(std::move(c)), rotate(std::move(d)), debug(e), cubeCount(f) {}
	std::string name;
	std::optional<std::string> parent;
	std::vector<float>pivot;
	std::vector<float>rotate;
	std::optional<bool> debug;
	std::optional<uint32_t> cubeCount;
};
YLT_REFL(Bone, name, parent, pivot, rotate, debug, cubeCount);

struct GeoProperties : public iguana::base_impl<GeoProperties>  {
	GeoProperties() = default;
	GeoProperties(float a, float b) : texture_height(a), texture_width(b) {}
	float texture_height = 0;
	float texture_width = 0;
};
YLT_REFL(GeoProperties, texture_height, texture_width);

// Sparse rest-position offsets weighted by a driver bone's animation.
struct MorphTarget : public iguana::base_impl<MorphTarget>  {
	MorphTarget() = default;
	MorphTarget(uint32_t a, uint32_t b, std::vector<uint32_t> c, std::vector<float> d) : bone(a), driver(b), vertices(std::move(c)), deltas(std::move(d)) {}
	uint32_t bone = 0;
	uint32_t driver = 0;
	std::vector<uint32_t>vertices;
	std::vector<float>deltas;
};
YLT_REFL(MorphTarget, bone, driver, vertices, deltas);

struct SkinnedMesh : public iguana::base_impl<SkinnedMesh>  {
	SkinnedMesh() = default;
	SkinnedMesh(std::vector<float> a, std::vector<float> b, std::vector<float> c, std::vector<uint32_t> d, std::vector<float> e, std::vector<uint32_t> f, std::vector<MorphTarget> g, std::vector<uint32_t> h) : positions(std::move(a)), normals(std::move(b)), uvs(std::move(c)), joints(std::move(d)), weights(std::move(e)), triangles(std::move(f)), morphs(std::move(g)), outline_triangles(std::move(h)) {}
	std::vector<float>positions;
	std::vector<float>normals;
	std::vector<float>uvs;
	std::vector<uint32_t>joints;
	std::vector<float>weights;
	std::vector<uint32_t>triangles;
	std::vector<MorphTarget>morphs;
	// Inverted-hull outline triangles drawn with back-face culling in their own pass.
	std::vector<uint32_t>outline_triangles;
};
YLT_REFL(SkinnedMesh, positions, normals, uvs, joints, weights, triangles, morphs, outline_triangles);

// Spring bone physics. Points are in the zero-rotation model space in model
// units, like SkinnedMesh positions.
struct SpringJoint : public iguana::base_impl<SpringJoint>  {
	SpringJoint() = default;
	SpringJoint(uint32_t a, std::vector<float> b, float c, float d, float e, float f, std::vector<float> g, uint32_t h) : bone(a), tail(std::move(b)), hit_radius(c), stiffness(d), drag(e), gravity_power(f), gravity_dir(std::move(g)), chain(h) {}
	uint32_t bone = 0;
	std::vector<float>tail;
	float hit_radius = 0;
	float stiffness = 0;
	float drag = 0;
	float gravity_power = 0;
	std::vector<float>gravity_dir;
	uint32_t chain = 0;
	// Angle limit (VRMC_springBone_limit): 0 none, 1 cone, 2 hinge, 3 spherical.
	uint32_t limit = 0;
	// Limit space in the zero-rotation model space, quaternion xyzw.
	std::vector<float>limit_rotation;
	float limit_angle = 0;
	float limit_yaw = 0;
	// Anti-clip backstop: SpringBones.body index + 1 (0 none) and how much closer
	// to its axis than the animated pose the tail may come, model units.
	uint32_t backstop = 0;
	float backstop_allowance = 0;
	// Anti-clip leg sync: SpringBones.body index + 1 (0 none) whose bone carries
	// the rest tail; the animated tail moves out by sync_share of the outward part.
	uint32_t sync = 0;
	float sync_share = 0;
};
YLT_REFL(SpringJoint, bone, tail, hit_radius, stiffness, drag, gravity_power, gravity_dir, chain, limit, limit_rotation, limit_angle, limit_yaw, backstop, backstop_allowance, sync, sync_share);

struct SpringCollider : public iguana::base_impl<SpringCollider>  {
	SpringCollider() = default;
	SpringCollider(uint32_t a, std::vector<float> b, std::vector<float> c, float d, uint32_t e = 0) : bone(a), head(std::move(b)), tail(std::move(c)), radius(d), type(e) {}
	uint32_t bone = 0;
	std::vector<float>head;
	std::vector<float>tail;
	float radius = 0;
	// 0 keeps tails out, 1 keeps them inside, 2 plane through head, normal toward tail.
	uint32_t type = 0;
};
YLT_REFL(SpringCollider, bone, head, tail, radius, type);

struct SpringChain : public iguana::base_impl<SpringChain>  {
	SpringChain() = default;
	SpringChain(std::vector<uint32_t> a, uint32_t b = 0) : colliders(std::move(a)), center(b) {}
	std::vector<uint32_t>colliders;
	// GeoModel.bones index + 1 of the bone whose space carries the chain's inertia; 0 for world.
	uint32_t center = 0;
};
YLT_REFL(SpringChain, colliders, center);

// Anti-clip body proxy capsule carried by a bone; radius at head, tail_radius
// at tail, linear in between.
struct BodyCapsule : public iguana::base_impl<BodyCapsule>  {
	BodyCapsule() = default;
	BodyCapsule(uint32_t a, std::vector<float> b, std::vector<float> c, float d, float e) : bone(a), head(std::move(b)), tail(std::move(c)), radius(d), tail_radius(e) {}
	uint32_t bone = 0;
	std::vector<float>head;
	std::vector<float>tail;
	float radius = 0;
	float tail_radius = 0;
};
YLT_REFL(BodyCapsule, bone, head, tail, radius, tail_radius);

// Anti-clip distance limit between two joints' tails (GeoModel.bones indices
// of the joints' bones), rest distance in model units, and how deep points
// along it may come into a collider before they are pushed out.
struct SpringLink : public iguana::base_impl<SpringLink>  {
	SpringLink() = default;
	SpringLink(uint32_t a_, uint32_t b_, float c, float d = 0) : a(a_), b(b_), rest(c), allowance(d) {}
	uint32_t a = 0;
	uint32_t b = 0;
	float rest = 0;
	float allowance = 0;
};
YLT_REFL(SpringLink, a, b, rest, allowance);

struct SpringBones : public iguana::base_impl<SpringBones>  {
	SpringBones() = default;
	SpringBones(std::vector<SpringJoint> a, std::vector<SpringCollider> b, std::vector<SpringChain> c) : joints(std::move(a)), colliders(std::move(b)), chains(std::move(c)) {}
	std::vector<SpringJoint>joints;
	std::vector<SpringCollider>colliders;
	std::vector<SpringChain>chains;
	// Anti-clip body proxy (at most 32 capsules).
	std::vector<BodyCapsule>body;
	// Post-skinning push-out per SkinnedMesh vertex; all empty for none.
	std::vector<float>push_share;
	std::vector<float>push_allowance;
	std::vector<uint32_t>push_capsules;
	// Anti-clip links between joints' tails and their limits: stretch and
	// compression shares of the rest distance, points per link kept out of the
	// chains' colliders.
	std::vector<SpringLink>links;
	float link_stretch = 0;
	float link_compression = 0;
	uint32_t link_points = 0;
	// Soft push-out start per SkinnedMesh vertex, model units; empty for none.
	std::vector<float>push_margin;
	// Inertia limits (anti-clip): per second, the model's turn beyond
	// inertia_turn_limit radians and its travel beyond inertia_move_limit
	// model units, and inertia_damping of the rest, carry the spring tails
	// with the model instead of leaving them behind. 0: no limit, full inertia.
	float inertia_turn_limit = 0;
	float inertia_move_limit = 0;
	float inertia_damping = 0;
	// Time constant (seconds) of the body velocity the springs' drag follows;
	// 0: the original 0.1 s.
	float body_velocity_seconds = 0;
};
YLT_REFL(SpringBones, joints, colliders, chains, body, push_share, push_allowance, push_capsules, links, link_stretch, link_compression, link_points, push_margin, inertia_turn_limit, inertia_move_limit, inertia_damping, body_velocity_seconds);

struct GeoModel : public iguana::base_impl<GeoModel>  {
	GeoModel() = default;
	GeoModel(std::vector<Bone>a, GeoProperties b, Cubes c, std::optional<SkinnedMesh> d, std::optional<SpringBones> e) : bones(std::move(a)), properties(b), cubes(std::move(c)), mesh(std::move(d)), springs(std::move(e)) {}
	std::vector<Bone>bones;
	GeoProperties properties;
	Cubes cubes;
	std::optional<SkinnedMesh> mesh;
	std::optional<SpringBones> springs;
};
YLT_REFL(GeoModel, bones, properties, cubes, mesh, springs);

}
