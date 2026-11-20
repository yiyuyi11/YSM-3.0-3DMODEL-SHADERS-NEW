#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>

#include <cglm/mat3.h>
#include <cglm/mat4.h>

#include "gfx/renderer/model_state.h"

// Spring bones follow the VRM SpringBone model: each joint keeps its tail's
// current and previous position, integrates inertia, stiffness toward the
// animated direction and gravity, keeps the bone length and its angle limit,
// keeps the tail and the bone out of the chain's colliders on the side the
// animation puts them (or inside inside-colliders, or in front of planes),
// then rotates the joint's subtree about its pivot so the bone points at the
// tail. Joints run in baked order, so a parent's rotation is in place before
// its children step. A chain with a center bone keeps its tails in that
// bone's space: the center's motion carries them instead of swinging them.
// Inertia limits (anti-clip, after MagicaCloth's world rotation / movement
// speed limits): the model's turn and travel beyond a speed, and a damping
// share of the rest, carry every tail with the model instead of leaving it
// behind in the world, so a snap turn does not fling cloth through the body.
// Colliders on simulated bones move with the simulation: they are posed as
// the previous fixed step left their bones, and a joint never collides with
// the colliders its own swing carries. A joint with a backstop (anti-clip,
// after MagicaCloth's) keeps its tail at most an allowance closer to a body
// proxy capsule's axis than the animated pose puts it, so a swing can follow
// the body but not sink into it. A joint with a leg sync (anti-clip, after
// Kawaii Physics' sync bone) has its animated tail moved out by a share of
// how far a leg would carry its rest tail into it. Linked chains (anti-clip,
// after Kawaii Physics' bone constraints: neighbouring hem chains) step level
// by level: the level's joints integrate and collide, links between their
// tails limit stretch firmly and compression loosely and keep points along
// them out of the chains' colliders, the tails collide again, the links pull
// them back into their limits with the bone lengths restored last, and only
// then do the bones turn; linked joints are drawn at their latest step.
namespace ysm::gfx::renderer {
namespace {
constexpr float kModelUnit = 1.0f / 16.0f;
// Longer gaps (hitches, paused games) integrate as this many seconds.
constexpr float kMaxStepSeconds = 0.1f;
// Springs integrate in steps of this length whatever the extraction rate. The
// tail velocity is the last step's displacement, which assumes equal steps;
// hosts extract unevenly (a shader pack's shadow pass extracts a second time a
// few milliseconds after the main pass), and uneven steps make the tails
// overshoot and fall back on alternate frames. Draws between steps
// interpolate the last two steps.
constexpr float kFixedStepSeconds = 1.0f / 120.0f;
// Time constant of the body velocity estimate. A frame's body motion arrives
// with one of its extractions and the others see none; smoothing over several
// frames keeps the estimate steady.
constexpr float kBodyVelocitySeconds = 0.1f;
// Drag is defined per frame at this rate and rescaled to the actual step.
constexpr float kDragReferenceHz = 60.0f;
// VRM SpringBone damps tail velocity relative to the world, which makes any
// steady movement push the tails back like a strong wind; Minecraft moves at
// sprint speed for long stretches. Drag acts on velocity relative to the
// body's translation instead, keeping this fraction as air drag. Rotation
// stays world-relative, so turning still swings.
constexpr float kAirDrag = 0.15f;
// A tail pushed out of one collider can land inside another (hair on a
// shoulder and a backpack), and turning a bone out of one collider can move
// its tail into another; passes repeat until none overlaps, up to this many.
constexpr int kCollisionPasses = 3;
// Share of the sliding motion a contact removes. Contacts also drop the
// motion along the contact normal, so a tail lying on a moving body part
// (hair on the back, a skirt on the thighs) slides with it instead of
// carrying the push-out into the next step.
constexpr float kContactFriction = 0.4f;
constexpr float kEpsilon = 1e-6f;
// Bone contacts this close to the joint's head are left to the tail test:
// turning about the head barely moves them.
constexpr float kMinBoneContactShare = 1e-3f;
// Link passes per level (anti-clip links) before the tails collide again:
// limits, then points along links.
constexpr int kLinkIterations = 2;
// Limit passes per level after that collision, each ending at the bone
// lengths: the links have the last word, so contacts and colliders that
// differ between neighbouring chains cannot pull the hem apart; the push-out
// keeps the cloth itself out of the body.
constexpr int kLinkFinalPasses = 4;
// Share of a link's correction a tail in contact with a collider takes
// relative to a free one, so the free neighbour follows the held tail
// instead of dragging it back into the collider.
constexpr float kLinkContactWeight = 0.25f;

using Vec3 = std::array<float, 3>;

Vec3 Add(const Vec3& a, const Vec3& b) {
    return {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
}

Vec3 Sub(const Vec3& a, const Vec3& b) {
    return {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
}

Vec3 Scale(const Vec3& a, float s) { return {a[0] * s, a[1] * s, a[2] * s}; }

float Dot(const Vec3& a, const Vec3& b) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

Vec3 Cross(const Vec3& a, const Vec3& b) {
    return {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]};
}

float Length(const Vec3& a) { return std::sqrt(Dot(a, a)); }

Vec3 Normalize(const Vec3& a, const Vec3& fallback) {
    const auto length = Length(a);
    return length > kEpsilon ? Scale(a, 1.0f / length) : fallback;
}

bool IsFinite(const Vec3& a) {
    return std::isfinite(a[0]) && std::isfinite(a[1]) && std::isfinite(a[2]);
}

Vec3 TransformPoint(const mat4 m, const Vec3& p) {
    return {m[0][0] * p[0] + m[1][0] * p[1] + m[2][0] * p[2] + m[3][0],
            m[0][1] * p[0] + m[1][1] * p[1] + m[2][1] * p[2] + m[3][1],
            m[0][2] * p[0] + m[1][2] * p[1] + m[2][2] * p[2] + m[3][2]};
}

Vec3 Rotate(const mat3 m, const Vec3& v) {
    return {m[0][0] * v[0] + m[1][0] * v[1] + m[2][0] * v[2],
            m[0][1] * v[0] + m[1][1] * v[1] + m[2][1] * v[2],
            m[0][2] * v[0] + m[1][2] * v[1] + m[2][2] * v[2]};
}

// Rotation about a unit axis (Rodrigues), column-major.
void AxisAngle(const Vec3& axis, float cosine, float sine, mat3 out) {
    const auto t = 1.0f - cosine;
    const auto [x, y, z] = axis;
    out[0][0] = t * x * x + cosine;
    out[0][1] = t * x * y + sine * z;
    out[0][2] = t * x * z - sine * y;
    out[1][0] = t * x * y - sine * z;
    out[1][1] = t * y * y + cosine;
    out[1][2] = t * y * z + sine * x;
    out[2][0] = t * x * z + sine * y;
    out[2][1] = t * y * z - sine * x;
    out[2][2] = t * z * z + cosine;
}

// Shortest rotation taking unit vector from onto unit vector to.
void RotationBetween(const Vec3& from, const Vec3& to, mat3 out) {
    const auto cosine = std::clamp(Dot(from, to), -1.0f, 1.0f);
    const auto axis = Cross(from, to);
    const auto sine = Length(axis);
    if (sine > kEpsilon) {
        AxisAngle(Scale(axis, 1.0f / sine), cosine, sine, out);
    } else if (cosine > 0.0f) {
        glm_mat3_identity(out);
    } else {
        // Opposite: half turn about any axis perpendicular to from.
        const Vec3 helper = std::abs(from[0]) < 0.9f ? Vec3{1.0f, 0.0f, 0.0f}
                                                     : Vec3{0.0f, 1.0f, 0.0f};
        AxisAngle(Normalize(Cross(from, helper), helper), -1.0f, 0.0f, out);
    }
}

Vec3 ClosestOnSegment(const Vec3& point, const Vec3& a, const Vec3& b) {
    const auto ab = Sub(b, a);
    const auto squared = Dot(ab, ab);
    if (squared <= kEpsilon * kEpsilon) {
        return a;
    }
    const auto t = std::clamp(Dot(Sub(point, a), ab) / squared, 0.0f, 1.0f);
    return Add(a, Scale(ab, t));
}

Vec3 Lerp(const Vec3& a, const Vec3& b, float share) {
    return Add(a, Scale(Sub(b, a), share));
}

// Shares along [p0, p1] and [q0, q1] of their closest points.
struct SegmentShares {
    float p;
    float q;
};

SegmentShares ClosestBetweenSegments(const Vec3& p0, const Vec3& p1,
                                     const Vec3& q0, const Vec3& q1) {
    const auto dp = Sub(p1, p0);
    const auto dq = Sub(q1, q0);
    const auto r = Sub(p0, q0);
    const auto pp = Dot(dp, dp);
    const auto qq = Dot(dq, dq);
    const auto qr = Dot(dq, r);
    constexpr auto kDegenerate = kEpsilon * kEpsilon;
    if (pp <= kDegenerate) {
        return {0.0f, qq <= kDegenerate ? 0.0f : std::clamp(qr / qq, 0.0f, 1.0f)};
    }
    const auto pr = Dot(dp, r);
    if (qq <= kDegenerate) {
        return {std::clamp(-pr / pp, 0.0f, 1.0f), 0.0f};
    }
    const auto pq = Dot(dp, dq);
    const auto denominator = pp * qq - pq * pq;
    // Parallel segments: any point of the overlap will do; start at p0.
    auto s = denominator > kEpsilon * pp * qq
                 ? std::clamp((pq * qr - pr * qq) / denominator, 0.0f, 1.0f)
                 : 0.0f;
    auto t = (pq * s + qr) / qq;
    if (t < 0.0f) {
        t = 0.0f;
        s = std::clamp(-pr / pp, 0.0f, 1.0f);
    } else if (t > 1.0f) {
        t = 1.0f;
        s = std::clamp((pq - pr) / pp, 0.0f, 1.0f);
    }
    return {s, t};
}

// Push-out direction for a point overlapping a collider, offset from its
// closest point on the collider's axis. The animated pose's offset from the
// axis decides the side: a tail or bone that crossed the collider (a fast
// swing, a teleport) goes back out on the side the animation puts it instead
// of staying trapped on the far side.
Vec3 PushNormal(const Vec3& offset, const Vec3& animated_offset,
                const Vec3& fallback) {
    const auto length = Length(offset);
    if (length > kEpsilon && Dot(offset, animated_offset) >= 0.0f) {
        return Scale(offset, 1.0f / length);
    }
    return Normalize(animated_offset, Normalize(offset, fallback));
}

// A pose a share of the way from one animated pose to the next. The bone's
// pivot travels in a straight line and each basis column turns and scales
// between its two ends, so an intermediate pose neither shrinks nor swings
// about the model origin the way a plain matrix blend does.
void BlendPose(const mat4 from, const mat4 to, const Vec3& pivot, float share,
               mat4 out) {
    for (int column = 0; column < 3; ++column) {
        Vec3 blended;
        float from_squared = 0.0f;
        float to_squared = 0.0f;
        for (int row = 0; row < 3; ++row) {
            const auto a = from[column][row];
            const auto b = to[column][row];
            blended[row] = a + (b - a) * share;
            from_squared += a * a;
            to_squared += b * b;
        }
        const auto from_length = std::sqrt(from_squared);
        const auto target =
            from_length + (std::sqrt(to_squared) - from_length) * share;
        const auto length = Length(blended);
        const auto scale = length > kEpsilon ? target / length : 1.0f;
        for (int row = 0; row < 3; ++row) {
            out[column][row] = blended[row] * scale;
        }
        out[column][3] = 0.0f;
    }
    const auto start = TransformPoint(from, pivot);
    const auto head =
        Add(start, Scale(Sub(TransformPoint(to, pivot), start), share));
    for (int row = 0; row < 3; ++row) {
        out[3][row] = head[row] - (out[0][row] * pivot[0] +
                                   out[1][row] * pivot[1] +
                                   out[2][row] * pivot[2]);
    }
    out[3][3] = 1.0f;
}

// v turned by the unit quaternion q (xyzw).
Vec3 QuatRotate(const std::array<float, 4>& q, const Vec3& v) {
    const Vec3 axis{q[0], q[1], q[2]};
    const auto t = Scale(Cross(axis, v), 2.0f);
    return Add(Add(v, Scale(t, q[3])), Cross(axis, t));
}

// The share of an extraction's model motion the tails are left behind by
// (anti-clip inertia limits, after MagicaCloth's world rotation and movement
// speed limits): the turn beyond turn_limit * seconds and the travel beyond
// move_limit * seconds, then damping of what remains, are taken out of it,
// so that much of the motion carries the tails with the model, as if body
// and cloth had moved together. The model origin (the root) is the pivot.
// Motion that is not rigid (a scale change) is kept whole.
void LimitMotion(const mat4 motion, float seconds, float turn_limit,
                 float move_limit, float damping, mat4 out) {
    std::memcpy(out, motion, sizeof(mat4));
    if (turn_limit <= 0.0f && move_limit <= 0.0f && damping <= 0.0f) {
        return;
    }
    for (int column = 0; column < 3; ++column) {
        const Vec3 basis{motion[column][0], motion[column][1], motion[column][2]};
        if (std::abs(Dot(basis, basis) - 1.0f) > 1e-3f) {
            return;
        }
    }
    const auto keep = 1.0f - damping;
    // The turn as a unit quaternion xyzw (Shepperd's method, stable near a
    // half turn), then its angle in [0, pi] about a unit axis.
    const auto at = [&](int row, int column) { return motion[column][row]; };
    const auto trace = at(0, 0) + at(1, 1) + at(2, 2);
    std::array<float, 4> q;
    if (trace > 0.0f) {
        const auto s = std::sqrt(trace + 1.0f) * 2.0f;
        q = {(at(2, 1) - at(1, 2)) / s, (at(0, 2) - at(2, 0)) / s,
             (at(1, 0) - at(0, 1)) / s, 0.25f * s};
    } else if (at(0, 0) > at(1, 1) && at(0, 0) > at(2, 2)) {
        const auto s = std::sqrt(1.0f + at(0, 0) - at(1, 1) - at(2, 2)) * 2.0f;
        q = {0.25f * s, (at(0, 1) + at(1, 0)) / s, (at(0, 2) + at(2, 0)) / s,
             (at(2, 1) - at(1, 2)) / s};
    } else if (at(1, 1) > at(2, 2)) {
        const auto s = std::sqrt(1.0f + at(1, 1) - at(0, 0) - at(2, 2)) * 2.0f;
        q = {(at(0, 1) + at(1, 0)) / s, 0.25f * s, (at(1, 2) + at(2, 1)) / s,
             (at(0, 2) - at(2, 0)) / s};
    } else {
        const auto s = std::sqrt(1.0f + at(2, 2) - at(0, 0) - at(1, 1)) * 2.0f;
        q = {(at(0, 2) + at(2, 0)) / s, (at(1, 2) + at(2, 1)) / s, 0.25f * s,
             (at(1, 0) - at(0, 1)) / s};
    }
    const Vec3 vector{q[0], q[1], q[2]};
    const auto half_sine = Length(vector);
    const auto angle = 2.0f * std::atan2(half_sine, std::abs(q[3]));
    auto turned = angle;
    if (turn_limit > 0.0f) {
        turned = std::min(turned, turn_limit * seconds);
    }
    turned *= keep;
    if (turned < angle && half_sine > kEpsilon) {
        const auto axis = Scale(vector, (q[3] < 0.0f ? -1.0f : 1.0f) / half_sine);
        mat3 rotation;
        AxisAngle(axis, std::cos(turned), std::sin(turned), rotation);
        for (int column = 0; column < 3; ++column) {
            for (int row = 0; row < 3; ++row) {
                out[column][row] = rotation[column][row];
            }
        }
    }
    const Vec3 travel{motion[3][0], motion[3][1], motion[3][2]};
    const auto distance = Length(travel);
    auto moved = distance;
    if (move_limit > 0.0f) {
        moved = std::min(moved, move_limit * seconds);
    }
    moved *= keep;
    if (moved < distance) {
        const auto share = distance > kEpsilon ? moved / distance : 0.0f;
        for (int row = 0; row < 3; ++row) {
            out[3][row] = motion[3][row] * share;
        }
    }
}

// A unit direction in limit space moved into the limit's range, as the
// VRMC_springBone_limit reference implementations do (singular directions
// resolved as the extension requires).
Vec3 LimitDirection(bake::SpringLimit type, float angle, float yaw,
                    const Vec3& direction) {
    constexpr float kPi = 3.14159265358979f;
    auto [x, y, z] = direction;
    switch (type) {
        case bake::SpringLimit::kCone: {
            const auto cosine = std::cos(std::clamp(angle, 0.0f, kPi));
            if (y < cosine) {
                const auto sine = std::sqrt(std::max(1.0f - cosine * cosine, 0.0f));
                const auto across = std::hypot(x, z);
                if (across <= kEpsilon) {
                    return {0.0f, cosine, sine};
                }
                return {x * sine / across, cosine, z * sine / across};
            }
            return direction;
        }
        case bake::SpringLimit::kHinge: {
            const auto projected = std::hypot(y, z);
            if (projected <= kEpsilon) {
                return {0.0f, 1.0f, 0.0f};
            }
            y /= projected;
            z /= projected;
            const auto cosine = std::cos(std::clamp(angle, 0.0f, kPi));
            if (y < cosine) {
                const auto sine = std::sqrt(std::max(1.0f - cosine * cosine, 0.0f));
                return {0.0f, cosine, z < 0.0f ? -sine : sine};
            }
            return {0.0f, y, z};
        }
        case bake::SpringLimit::kSpherical: {
            const auto pitch_limit = std::clamp(angle, 0.0f, kPi);
            const auto yaw_limit = std::clamp(yaw, 0.0f, 0.5f * kPi);
            float pitch;
            if (std::hypot(x, z) <= kEpsilon && y < 0.0f) {
                pitch = kPi;
            } else if (std::hypot(y, z) <= kEpsilon) {
                pitch = 0.0f;
            } else {
                pitch = std::atan2(z, y);
            }
            auto turn = std::asin(std::clamp(x, -1.0f, 1.0f));
            pitch = std::clamp(pitch, -pitch_limit, pitch_limit);
            turn = std::clamp(turn, -yaw_limit, yaw_limit);
            return {std::sin(turn), std::cos(turn) * std::cos(pitch),
                    std::cos(turn) * std::sin(pitch)};
        }
        case bake::SpringLimit::kNone:
            break;
    }
    return direction;
}

uint64_t HashBytes(uint64_t hash, const void* data, size_t size) {
    const auto* bytes = static_cast<const unsigned char*>(data);
    for (size_t i = 0; i < size; ++i) {
        hash = (hash ^ bytes[i]) * 0x100000001B3ULL;  // FNV-1a
    }
    return hash;
}

template <typename T>
uint64_t HashVector(uint64_t hash, const std::vector<T>& values) {
    static_assert(std::is_trivially_copyable_v<T>);
    const uint64_t size = values.size();
    hash = HashBytes(hash, &size, sizeof(size));
    return HashBytes(hash, values.data(), values.size() * sizeof(T));
}

// The spring table and the bone tree it runs on. Two baked models with the
// same fingerprint (the mesh LOD levels of one geometry differ only in their
// triangles) can swap under a ModelState without restarting its springs.
uint64_t SpringFingerprint(const bake::BakedModel& model) {
    const auto& springs = model.Springs();
    auto hash = 0xCBF29CE484222325ULL;
    hash = HashVector(hash, springs.joints);
    hash = HashVector(hash, springs.colliders);
    hash = HashVector(hash, springs.chain_offsets);
    hash = HashVector(hash, springs.chain_colliders);
    hash = HashVector(hash, springs.chain_centers);
    hash = HashVector(hash, springs.body);
    hash = HashVector(hash, springs.links);
    hash = HashBytes(hash, &springs.link_stretch, sizeof(springs.link_stretch));
    hash = HashBytes(hash, &springs.link_compression,
                     sizeof(springs.link_compression));
    hash = HashBytes(hash, &springs.link_points, sizeof(springs.link_points));
    const auto& bones = model.Bones().list;
    const uint64_t bone_count = bones.size();
    hash = HashBytes(hash, &bone_count, sizeof(bone_count));
    for (const auto& bone : bones) {
        hash = HashBytes(hash, bone.pivot.data(), sizeof(bone.pivot));
        hash = HashBytes(hash, &bone.subtree_end, sizeof(bone.subtree_end));
    }
    return hash;
}
}  // namespace

PhysicsFrame PhysicsFrame::Unpack(std::span<const float, kFloatCount> packed) {
    PhysicsFrame frame;
    frame.delta_seconds = packed[0];
    frame.reset = packed[1] != 0.0f;
    frame.gravity = {packed[2], packed[3], packed[4]};
    std::memcpy(&frame.motion[0][0], packed.data() + 5, sizeof(float) * 16);
    return frame;
}

void ModelState::StepSprings(const PhysicsFrame& frame) {
    const auto& springs = baked_model_->Springs();
    const auto& joints = springs.joints;
    if (joints.empty()) {
        spring_tails_.clear();
        spring_model_ = nullptr;
        spring_previous_poses_.clear();
        spring_centers_.clear();
        spring_collider_ends_.clear();
        return;
    }
    const auto& bones = baked_model_->Bones().list;
    const auto& colliders = springs.colliders;

    auto dt = frame.delta_seconds;
    const bool motion_finite = std::all_of(
        &frame.motion[0][0], &frame.motion[0][0] + 16,
        [](float value) { return std::isfinite(value); });
    // Another baked model with the same springs and bones (a mesh LOD level)
    // continues them; anything else restarts them.
    bool same_springs = spring_model_ == baked_model_.get();
    if (!same_springs) {
        const auto fingerprint = SpringFingerprint(*baked_model_);
        same_springs =
            spring_model_ != nullptr && fingerprint == spring_fingerprint_;
        spring_fingerprint_ = fingerprint;
        spring_read_bones_.clear();
    }
    bool reset = frame.reset || !same_springs ||
                 spring_tails_.size() != joints.size() || !motion_finite ||
                 !std::isfinite(dt) || dt < 0.0f;
    dt = reset ? 0.0f : std::min(dt, kMaxStepSeconds);
    spring_model_ = baked_model_.get();
    if (spring_read_bones_.empty()) {
        // Bones a fixed step reads: joint pivots and limit frames, collider
        // bones, centers, backstop and sync capsules. aim() also writes the
        // joints' other descendants, but nothing reads those before the
        // final restore.
        std::vector<uint8_t> read(bones.size(), 0);
        for (const auto& joint : joints) {
            read[joint.bone] = 1;
        }
        for (const auto& collider : colliders) {
            read[collider.bone] = 1;
        }
        for (const auto center : springs.chain_centers) {
            if (center != 0) {
                read[center - 1] = 1;
            }
        }
        for (const auto& joint : joints) {
            if (joint.backstop != 0) {
                read[springs.body[joint.backstop - 1].bone] = 1;
            }
            if (joint.sync != 0) {
                read[springs.body[joint.sync - 1].bone] = 1;
            }
        }
        for (uint32_t bone = 0; bone < read.size(); ++bone) {
            if (read[bone] != 0) {
                spring_read_bones_.push_back(bone);
            }
        }
        // Links: a joint's level is the number of joints of its chain above
        // it. Linked chains step level by level at their first joint, so
        // none may hang below a joint of another chain; links join joints of
        // one level.
        spring_link_order_.clear();
        spring_link_level_offsets_.clear();
        spring_link_links_.clear();
        spring_link_link_offsets_.clear();
        spring_linked_.assign(joints.size(), 0);
        spring_link_states_.clear();
        if (!springs.links.empty()) {
            const auto chain_count = springs.chain_centers.size();
            std::vector<uint8_t> chain_linked(chain_count, 0);
            for (const auto& link : springs.links) {
                chain_linked[joints[link.a].chain] = 1;
                chain_linked[joints[link.b].chain] = 1;
            }
            std::vector<uint32_t> joint_of(bones.size(), UINT32_MAX);
            for (uint32_t index = 0; index < joints.size(); ++index) {
                joint_of[joints[index].bone] = index;
            }
            std::vector<uint32_t> level(joints.size(), 0);
            uint32_t levels = 0;
            bool usable = true;
            for (uint32_t index = 0; index < joints.size(); ++index) {
                const auto chain = joints[index].chain;
                for (auto bone = bones[joints[index].bone].parent_index;
                     bone < bones.size(); bone = bones[bone].parent_index) {
                    const auto above = joint_of[bone];
                    if (above == UINT32_MAX) {
                        continue;
                    }
                    if (joints[above].chain == chain) {
                        level[index] = level[above] + 1;
                    } else if (chain_linked[chain] != 0) {
                        usable = false;
                    }
                    break;
                }
                if (chain_linked[chain] != 0) {
                    levels = std::max(levels, level[index] + 1);
                }
            }
            if (usable) {
                spring_link_first_ = joints.size();
                spring_link_level_offsets_.push_back(0);
                spring_link_link_offsets_.push_back(0);
                for (uint32_t current = 0; current < levels; ++current) {
                    for (uint32_t index = 0; index < joints.size(); ++index) {
                        if (chain_linked[joints[index].chain] != 0 &&
                            level[index] == current) {
                            spring_link_order_.push_back(index);
                            spring_linked_[index] = 1;
                            spring_link_first_ =
                                std::min<size_t>(spring_link_first_, index);
                        }
                    }
                    spring_link_level_offsets_.push_back(
                        static_cast<uint32_t>(spring_link_order_.size()));
                    for (uint32_t link = 0; link < springs.links.size();
                         ++link) {
                        const auto& value = springs.links[link];
                        if (level[value.a] == current &&
                            level[value.b] == current) {
                            spring_link_links_.push_back(link);
                        }
                    }
                    spring_link_link_offsets_.push_back(
                        static_cast<uint32_t>(spring_link_links_.size()));
                }
                spring_link_states_.assign(joints.size(), SpringLinkState{});
            }
        }
    }
    auto& velocity_estimate = spring_body_velocity_;
    if (reset) {
        spring_tails_.assign(joints.size(), SpringTail{});
        spring_accumulator_ = 0.0f;
        velocity_estimate = {};
        spring_centers_.assign(springs.chain_centers.size(), SpringCenter{});
        spring_collider_ends_.assign(colliders.size(), SpringColliderEnds{});
        // Colliders a simulated bone carries move with the simulation.
        for (const auto& joint : joints) {
            for (size_t index = 0; index < colliders.size(); ++index) {
                if (colliders[index].bone >= joint.bone &&
                    colliders[index].bone < bones[joint.bone].subtree_end) {
                    spring_collider_ends_[index].moving = true;
                }
            }
        }
    } else {
        // The motion the springs feel: beyond the inertia limits the model
        // carries its tails (and everything below that stands for the body
        // a step ago) instead of leaving them behind in the world.
        mat4 motion;
        LimitMotion(frame.motion, dt, springs.inertia_turn_limit,
                    springs.inertia_move_limit, springs.inertia_damping, motion);
        for (auto& tail : spring_tails_) {
            tail.current = TransformPoint(motion, tail.current);
            tail.previous = TransformPoint(motion, tail.previous);
        }
        // The previous extraction's animated poses, moved into this model
        // space the same way: where the body was then, seen from now.
        for (auto& previous : spring_previous_poses_) {
            mat4 moved;
            glm_mat4_mul(motion, previous.pose, moved);
            glm_mat4_copy(moved, previous.pose);
        }
        for (auto& center : spring_centers_) {
            if (center.valid) {
                mat4 moved;
                glm_mat4_mul(motion, center.pose, moved);
                glm_mat4_copy(moved, center.pose);
            }
        }
        for (auto& ends : spring_collider_ends_) {
            if (ends.valid) {
                ends.head = TransformPoint(motion, ends.head);
                ends.tail = TransformPoint(motion, ends.tail);
            }
        }
        mat3 turn;
        for (int column = 0; column < 3; ++column) {
            for (int row = 0; row < 3; ++row) {
                turn[column][row] = motion[column][row];
            }
        }
        velocity_estimate = Rotate(turn, velocity_estimate);
        if (dt > 0.0f) {
            // The motion's translation is how far world-fixed points moved in
            // model space: the body's own displacement, negated.
            const auto weight = std::min(dt / kBodyVelocitySeconds, 1.0f);
            for (int axis = 0; axis < 3; ++axis) {
                velocity_estimate[axis] +=
                    (motion[3][axis] / dt - velocity_estimate[axis]) * weight;
            }
        }
    }
    if (!IsFinite(velocity_estimate)) {
        velocity_estimate = {};
    }
    // World-fixed points drift this far per step; drag acts on velocity
    // relative to that drift, except for the air drag share.
    const auto carry =
        Scale(velocity_estimate, kFixedStepSeconds * (1.0f - kAirDrag));

    // Joint gravity directions are authored with world down at -Y; turn them
    // with the host's current down.
    const Vec3 rest_down{0.0f, -1.0f, 0.0f};
    const auto down = Normalize(
        frame.gravity[0] == 0.0f && frame.gravity[1] == 0.0f &&
                frame.gravity[2] == 0.0f
            ? rest_down
            : Vec3{frame.gravity[0], frame.gravity[1], frame.gravity[2]},
        rest_down);
    mat3 gravity_rotation;
    RotationBetween(rest_down, IsFinite(down) ? down : rest_down,
                    gravity_rotation);

    struct Animated {
        Vec3 head;
        Vec3 direction;
        float length;
    };
    // The joint's head and bone direction in the current poses, which hold
    // the rotations of the joints before it.
    const auto animate = [&](const auto& joint, Animated& out) {
        const auto& pose = bone_poses_[joint.bone].pose;
        const auto& pivot = bones[joint.bone].pivot;
        out.head = TransformPoint(pose, {pivot[0] * kModelUnit,
                                         pivot[1] * kModelUnit,
                                         pivot[2] * kModelUnit});
        const auto offset = Sub(TransformPoint(pose, joint.tail), out.head);
        out.length = Length(offset);
        if (!(out.length > kEpsilon) || !IsFinite(out.head)) {
            return false;
        }
        out.direction = Scale(offset, 1.0f / out.length);
        return true;
    };
    // Rotates the joint's subtree about its head so the bone points at target.
    const auto aim = [&](const auto& joint, const Animated& animated,
                         const Vec3& target) {
        mat3 rotation;
        RotationBetween(
            animated.direction,
            Normalize(Sub(target, animated.head), animated.direction),
            rotation);
        // delta = translate(head) * rotation * translate(-head)
        mat4 delta = GLM_MAT4_IDENTITY_INIT;
        for (int column = 0; column < 3; ++column) {
            for (int row = 0; row < 3; ++row) {
                delta[column][row] = rotation[column][row];
            }
        }
        const auto moved_head = Rotate(rotation, animated.head);
        delta[3][0] = animated.head[0] - moved_head[0];
        delta[3][1] = animated.head[1] - moved_head[1];
        delta[3][2] = animated.head[2] - moved_head[2];
        for (auto bone = joint.bone; bone < bones[joint.bone].subtree_end;
             ++bone) {
            if (bone_visited_[bone] == 0) {
                continue;
            }
            auto& target_pose = bone_poses_[bone];
            mat4 posed;
            glm_mat4_mul(delta, target_pose.pose, posed);
            glm_mat4_copy(posed, target_pose.pose);
            mat3 normal;
            glm_mat3_mul(rotation, target_pose.normal, normal);
            glm_mat3_copy(normal, target_pose.normal);
        }
    };
    // Prepares the joint for this pass; false skips it.
    const auto begin = [&](size_t index, Animated& animated) {
        const auto& joint = joints[index];
        auto& tail = spring_tails_[index];
        if (bone_visited_[joint.bone] == 0 || !animate(joint, animated)) {
            tail.live = false;
            return false;
        }
        if (!tail.live) {
            tail.current = tail.previous =
                Add(animated.head, Scale(animated.direction, animated.length));
            tail.live = true;
        }
        return true;
    };
    // A collider's ends now: as the previous fixed step left them for
    // colliders on simulated bones, else carried by their bone's pose.
    const auto collider_ends = [&](size_t index, Vec3& head, Vec3& tail) {
        const auto& ends = spring_collider_ends_[index];
        if (ends.moving && ends.valid) {
            head = ends.head;
            tail = ends.tail;
            return;
        }
        const auto& collider = colliders[index];
        const auto& pose = bone_poses_[collider.bone].pose;
        head = TransformPoint(pose, collider.head);
        tail = TransformPoint(pose, collider.tail);
    };
    // The limit space as the bone's current pose turns it.
    struct LimitFrame {
        bool limited = false;
        Vec3 x{1.0f, 0.0f, 0.0f};
        Vec3 y{0.0f, 1.0f, 0.0f};
        Vec3 z{0.0f, 0.0f, 1.0f};
    };
    const auto limit_frame = [&](const bake::SpringJoint& joint,
                                 const Animated& animated) {
        LimitFrame frame;
        frame.limited = joint.limit != bake::SpringLimit::kNone;
        if (frame.limited) {
            mat3 basis;
            glm_mat4_pick3(bone_poses_[joint.bone].pose, basis);
            frame.y = Normalize(
                Rotate(basis, QuatRotate(joint.limit_rotation, frame.y)),
                animated.direction);
            frame.z = Normalize(
                Cross(Rotate(basis, QuatRotate(joint.limit_rotation, frame.x)),
                      frame.y),
                frame.z);
            frame.x = Cross(frame.y, frame.z);
        }
        return frame;
    };
    // Back at the bone's length about its head, inside its limit.
    const auto constrain = [&](const bake::SpringJoint& joint,
                               const Animated& animated,
                               const LimitFrame& frame, const Vec3& point) {
        auto direction =
            Normalize(Sub(point, animated.head), animated.direction);
        if (frame.limited) {
            const auto local = LimitDirection(
                joint.limit, joint.limit_angle, joint.limit_yaw,
                {Dot(direction, frame.x), Dot(direction, frame.y),
                 Dot(direction, frame.z)});
            direction = Add(Add(Scale(frame.x, local[0]),
                                Scale(frame.y, local[1])),
                            Scale(frame.z, local[2]));
        }
        return Add(animated.head, Scale(direction, animated.length));
    };
    // The tail's next position from inertia, stiffness and gravity, at the
    // bone's length.
    const auto predict = [&](size_t index, const Animated& animated,
                             const LimitFrame& frame) {
        const auto& joint = joints[index];
        const auto& tail = spring_tails_[index];
        const auto keep =
            std::pow(1.0f - joint.drag, kFixedStepSeconds * kDragReferenceHz);
        const auto gravity = Rotate(gravity_rotation, joint.gravity_dir);
        // Velocity relative to the moving body decays; the body's share is
        // carried along undamped. A chain in its center's space has no body
        // drift to discount: the center carries its tails.
        const auto drift =
            spring_centers_[joint.chain].active ? Vec3{} : carry;
        const auto velocity =
            Sub(Scale(Add(Sub(tail.current, tail.previous), drift), keep),
                drift);
        return constrain(
            joint, animated, frame,
            Add(Add(tail.current, velocity),
                Add(Scale(animated.direction,
                          joint.stiffness * kFixedStepSeconds),
                    Scale(gravity, joint.gravity_power * kFixedStepSeconds))));
    };
    // The directions colliders pushed a tail along in a step.
    struct Contact {
        Vec3 normal{};
        bool touched = false;
    };
    // Keeps next and the bone to it out of the chain's colliders and the
    // joint's backstop, on the side the animated tail is, in up to
    // kCollisionPasses passes, each ending back at the bone's length.
    const auto collide = [&](size_t index, const Animated& animated,
                             const LimitFrame& frame, Vec3& next,
                             Contact& contact) {
        const auto& joint = joints[index];
        const auto first = springs.chain_offsets[joint.chain];
        const auto last = springs.chain_offsets[joint.chain + 1];
        const auto subtree_end = bones[joint.bone].subtree_end;
        const auto animated_tail =
            Add(animated.head, Scale(animated.direction, animated.length));
        // A push moved the tail along normal; limited joints go back into
        // their range right away.
        const auto pushed_along = [&](const Vec3& normal) {
            contact.normal = Add(contact.normal, normal);
            contact.touched = true;
            if (frame.limited) {
                next = constrain(joint, animated, frame, next);
            }
        };
        for (int pass = 0; pass < kCollisionPasses; ++pass) {
            bool pushed = false;
            for (auto ref = first; ref < last; ++ref) {
                const auto collider_index = springs.chain_colliders[ref];
                const auto& collider = colliders[collider_index];
                // Colliders this joint's own swing carries turn with it.
                if (bone_visited_[collider.bone] == 0 ||
                    (collider.bone >= joint.bone && collider.bone < subtree_end)) {
                    continue;
                }
                Vec3 axis_head;
                Vec3 axis_tail;
                collider_ends(collider_index, axis_head, axis_tail);
                if (collider.type == bake::SpringColliderType::kPlane) {
                    const auto normal =
                        Normalize(Sub(axis_tail, axis_head), animated.direction);
                    const auto depth =
                        joint.hit_radius - Dot(Sub(next, axis_head), normal);
                    if (depth > 0.0f) {
                        next = Add(next, Scale(normal, depth));
                        pushed = true;
                        pushed_along(normal);
                    }
                    continue;
                }
                if (collider.type == bake::SpringColliderType::kInside) {
                    const auto closest =
                        ClosestOnSegment(next, axis_head, axis_tail);
                    const auto offset = Sub(next, closest);
                    const auto room =
                        std::max(collider.radius - joint.hit_radius, 0.0f);
                    if (Length(offset) > room) {
                        const auto outward = Normalize(offset, animated.direction);
                        next = Add(closest, Scale(outward, room));
                        pushed = true;
                        pushed_along(Scale(outward, -1.0f));
                    }
                    continue;
                }
                const auto reach = collider.radius + joint.hit_radius;
                // Neither the tail nor the bone can touch a capsule whose
                // bounding sphere is out of reach of the bone's.
                if (Length(Sub(Scale(Add(axis_head, axis_tail), 0.5f),
                               Scale(Add(animated.head, next), 0.5f))) >
                    0.5f * (Length(Sub(axis_tail, axis_head)) +
                            Length(Sub(next, animated.head))) +
                        reach) {
                    continue;
                }
                const auto closest = ClosestOnSegment(next, axis_head, axis_tail);
                const auto offset = Sub(next, closest);
                if (Length(offset) < reach) {
                    const auto normal = PushNormal(
                        offset,
                        Sub(animated_tail, ClosestOnSegment(animated_tail,
                                                            axis_head, axis_tail)),
                        animated.direction);
                    next = Add(closest, Scale(normal, reach));
                    pushed = true;
                    pushed_along(normal);
                }
                // The bone between head and tail. Turning about the head can
                // only clear it when the head itself is outside.
                if (Length(Sub(animated.head,
                               ClosestOnSegment(animated.head, axis_head,
                                                axis_tail))) < reach) {
                    continue;
                }
                const auto bone = ClosestBetweenSegments(animated.head, next,
                                                         axis_head, axis_tail);
                if (bone.p <= kMinBoneContactShare || bone.p >= 1.0f) {
                    continue;
                }
                const auto on_axis = Lerp(axis_head, axis_tail, bone.q);
                const auto bone_offset =
                    Sub(Lerp(animated.head, next, bone.p), on_axis);
                if (Length(bone_offset) >= reach) {
                    continue;
                }
                const auto rest = ClosestBetweenSegments(
                    animated.head, animated_tail, axis_head, axis_tail);
                const auto normal = PushNormal(
                    bone_offset,
                    Sub(Lerp(animated.head, animated_tail, rest.p),
                        Lerp(axis_head, axis_tail, rest.q)),
                    animated.direction);
                // Turn the bone about its head through the contact point
                // moved out to the surface.
                const auto cleared = Add(on_axis, Scale(normal, reach));
                next = Add(animated.head,
                           Scale(Normalize(Sub(cleared, animated.head),
                                           animated.direction),
                                 Length(Sub(next, animated.head))));
                pushed = true;
                pushed_along(normal);
            }
            // Backstop: no closer to the body capsule's axis than the
            // animated tail less the allowance, pushed out on the animated
            // side. A capsule this joint's own swing carries is skipped.
            if (joint.backstop != 0) {
                const auto& capsule = springs.body[joint.backstop - 1];
                if (bone_visited_[capsule.bone] != 0 &&
                    !(capsule.bone >= joint.bone && capsule.bone < subtree_end)) {
                    const auto& pose = bone_poses_[capsule.bone].pose;
                    const auto axis_head = TransformPoint(pose, capsule.head);
                    const auto axis_tail = TransformPoint(pose, capsule.tail);
                    const auto animated_offset = Sub(
                        animated_tail,
                        ClosestOnSegment(animated_tail, axis_head, axis_tail));
                    const auto minimum =
                        Length(animated_offset) - joint.backstop_allowance;
                    const auto closest =
                        ClosestOnSegment(next, axis_head, axis_tail);
                    const auto offset = Sub(next, closest);
                    if (minimum > 0.0f && Length(offset) < minimum) {
                        const auto normal = PushNormal(offset, animated_offset,
                                                       animated.direction);
                        next = Add(closest, Scale(normal, minimum));
                        pushed = true;
                        pushed_along(normal);
                    }
                }
            }
            next = constrain(joint, animated, frame, next);
            if (!pushed) {
                break;
            }
        }
    };
    // Makes next the tail. After a contact the next step's velocity (next -
    // previous) keeps only the slide along the surface, slowed by friction.
    const auto commit = [&](size_t index, const Animated& animated,
                            const Vec3& next, const Contact& contact) {
        auto& tail = spring_tails_[index];
        auto previous = tail.current;
        if (contact.touched) {
            const auto normal = Normalize(contact.normal, animated.direction);
            auto slide = Sub(next, tail.current);
            slide = Sub(slide, Scale(normal, Dot(slide, normal)));
            previous = Sub(next, Scale(slide, 1.0f - kContactFriction));
        }
        tail.previous = previous;
        tail.current = next;
        return IsFinite(tail.current) && IsFinite(tail.previous);
    };
    const auto integrate = [&](size_t index, const Animated& animated) {
        const auto frame = limit_frame(joints[index], animated);
        auto next = predict(index, animated, frame);
        Contact contact;
        collide(index, animated, frame, next, contact);
        return commit(index, animated, next, contact);
    };
    // Leg sync: the animated tail moves out by sync_share of how far the
    // sync capsule's bone would carry the rest tail beyond it, away from the
    // capsule's axis. Only the direction changes; the bone keeps its length.
    const auto sync = [&](const bake::SpringJoint& joint, Animated& animated) {
        if (joint.sync == 0) {
            return;
        }
        const auto& capsule = springs.body[joint.sync - 1];
        if (bone_visited_[capsule.bone] == 0) {
            return;
        }
        const auto& pose = bone_poses_[capsule.bone].pose;
        const auto carried = TransformPoint(pose, joint.tail);
        const auto outward = Normalize(
            Sub(carried,
                ClosestOnSegment(carried, TransformPoint(pose, capsule.head),
                                 TransformPoint(pose, capsule.tail))),
            Vec3{});
        const auto animated_tail =
            Add(animated.head, Scale(animated.direction, animated.length));
        const auto beyond = Dot(Sub(carried, animated_tail), outward);
        if (!(beyond > 0.0f)) {
            return;
        }
        animated.direction = Normalize(
            Sub(Add(animated_tail, Scale(outward, beyond * joint.sync_share)),
                animated.head),
            animated.direction);
    };

    spring_accumulator_ += dt;
    const auto steps =
        static_cast<int>(spring_accumulator_ / kFixedStepSeconds);
    spring_accumulator_ =
        std::max(spring_accumulator_ - steps * kFixedStepSeconds, 0.0f);
    // Every fixed step sees the animated poses the share of the way from the
    // previous extraction to this one at which it falls. Posing all of them
    // at this extraction's poses would move the colliders, the joints'
    // pivots and the body's travel by a whole frame at once: at low frame
    // rates a raised knee then lands beyond a skirt's tails and pushes them
    // out on the far side, and the travel throws every tail back by the
    // frame's distance before the bone length is restored.
    spring_animated_poses_.assign(bone_poses_.begin(), bone_poses_.end());
    // Spring LOD: the joint's chain is held (SetSpringModes).
    const auto held = [&](size_t index) {
        const auto chain = joints[index].chain;
        return chain < spring_chain_modes_.size() &&
               spring_chain_modes_[chain] != 0;
    };
    // Linked chains step level by level (see the top of the file). False
    // when a tail is no longer finite.
    const auto step_linked = [&]() {
        auto& states = spring_link_states_;
        // Pulls a link's tails back to rest * (1 + link_stretch) or pushes
        // them apart to rest * (1 - link_compression); a tail in contact
        // takes kLinkContactWeight of a free one's share.
        const auto limit = [&](const bake::SpringLink& link) {
            auto& a = states[link.a];
            auto& b = states[link.b];
            if (!a.live || !b.live) {
                return;
            }
            const auto delta = Sub(b.next, a.next);
            const auto length = Length(delta);
            const auto longest = link.rest * (1.0f + springs.link_stretch);
            const auto shortest = link.rest * (1.0f - springs.link_compression);
            float target;
            if (length > longest) {
                target = longest;
            } else if (length < shortest && length > kEpsilon) {
                target = shortest;
            } else {
                return;
            }
            const auto weight_a = a.contact ? kLinkContactWeight : 1.0f;
            const auto weight_b = b.contact ? kLinkContactWeight : 1.0f;
            const auto correction = Scale(
                delta, (length - target) / (length * (weight_a + weight_b)));
            a.next = Add(a.next, Scale(correction, weight_a));
            b.next = Sub(b.next, Scale(correction, weight_b));
            a.moved = true;
            b.moved = true;
        };
        // Keeps link_points points evenly along a link out of both chains'
        // outside colliders (with the hit radius blended along the link) but
        // for the link's allowance (their rest depth): the ends move by their
        // lever shares so the point lands there, on the side the animated
        // link is.
        const auto keep_out = [&](const bake::SpringLink& link) {
            auto& a = states[link.a];
            auto& b = states[link.b];
            if (!a.live || !b.live) {
                return;
            }
            const auto& joint_a = joints[link.a];
            const auto& joint_b = joints[link.b];
            const auto animated_a = Add(a.head, Scale(a.direction, a.length));
            const auto animated_b = Add(b.head, Scale(b.direction, b.length));
            const auto carried = [&](uint32_t bone) {
                return (bone >= joint_a.bone &&
                        bone < bones[joint_a.bone].subtree_end) ||
                       (bone >= joint_b.bone &&
                        bone < bones[joint_b.bone].subtree_end);
            };
            const auto a_first =
                springs.chain_colliders.begin() +
                springs.chain_offsets[joint_a.chain];
            const auto a_last = springs.chain_colliders.begin() +
                                springs.chain_offsets[joint_a.chain + 1];
            for (int side = 0; side < 2; ++side) {
                const auto chain = side == 0 ? joint_a.chain : joint_b.chain;
                if (side == 1 && chain == joint_a.chain) {
                    break;
                }
                for (auto ref = springs.chain_offsets[chain];
                     ref < springs.chain_offsets[chain + 1]; ++ref) {
                    const auto collider_index = springs.chain_colliders[ref];
                    if (side == 1 &&
                        std::find(a_first, a_last, collider_index) != a_last) {
                        continue;  // tested with a's chain
                    }
                    const auto& collider = colliders[collider_index];
                    if (collider.type != bake::SpringColliderType::kOutside ||
                        bone_visited_[collider.bone] == 0 ||
                        carried(collider.bone)) {
                        continue;
                    }
                    Vec3 axis_head;
                    Vec3 axis_tail;
                    collider_ends(collider_index, axis_head, axis_tail);
                    for (uint32_t point = 1; point <= springs.link_points;
                         ++point) {
                        const auto t = static_cast<float>(point) /
                                       static_cast<float>(springs.link_points + 1);
                        const auto on_link = Lerp(a.next, b.next, t);
                        const auto offset = Sub(
                            on_link,
                            ClosestOnSegment(on_link, axis_head, axis_tail));
                        const auto reach =
                            collider.radius + joint_a.hit_radius +
                            (joint_b.hit_radius - joint_a.hit_radius) * t -
                            link.allowance;
                        const auto distance = Length(offset);
                        if (distance >= reach) {
                            continue;
                        }
                        const auto animated_point = Lerp(animated_a, animated_b, t);
                        const auto normal = PushNormal(
                            offset,
                            Sub(animated_point,
                                ClosestOnSegment(animated_point, axis_head,
                                                 axis_tail)),
                            a.direction);
                        const auto move = (reach - distance) /
                                          ((1.0f - t) * (1.0f - t) + t * t);
                        a.next = Add(a.next, Scale(normal, move * (1.0f - t)));
                        b.next = Add(b.next, Scale(normal, move * t));
                        a.contact_normal = Add(a.contact_normal, normal);
                        b.contact_normal = Add(b.contact_normal, normal);
                        a.contact = true;
                        b.contact = true;
                        a.moved = true;
                        b.moved = true;
                    }
                }
            }
        };
        for (size_t level = 0; level + 1 < spring_link_level_offsets_.size();
             ++level) {
            const auto first_joint = spring_link_level_offsets_[level];
            const auto last_joint = spring_link_level_offsets_[level + 1];
            for (auto k = first_joint; k < last_joint; ++k) {
                const auto index = spring_link_order_[k];
                auto& state = states[index];
                state.live = false;
                state.moved = false;
                if (spring_tails_[index].holding && held(index)) {
                    continue;  // HOLD: placed once per extraction, below
                }
                Animated animated;
                if (!begin(index, animated)) {
                    continue;
                }
                state.posed = animated.direction;
                sync(joints[index], animated);
                const auto frame = limit_frame(joints[index], animated);
                auto next = predict(index, animated, frame);
                Contact contact;
                collide(index, animated, frame, next, contact);
                state.head = animated.head;
                state.direction = animated.direction;
                state.length = animated.length;
                state.limited = frame.limited;
                state.limit_x = frame.x;
                state.limit_y = frame.y;
                state.limit_z = frame.z;
                state.next = next;
                state.contact_normal = contact.normal;
                state.contact = contact.touched;
                state.live = true;
            }
            const auto first_link = spring_link_link_offsets_[level];
            const auto last_link = spring_link_link_offsets_[level + 1];
            // Moved tails back at their bone lengths (and in their limits);
            // with collide also out of their colliders again.
            const auto settle = [&](bool with_collision) {
                for (auto k = first_joint; k < last_joint; ++k) {
                    const auto index = spring_link_order_[k];
                    auto& state = states[index];
                    if (!state.live || !state.moved) {
                        continue;
                    }
                    const Animated animated{state.head, state.direction,
                                            state.length};
                    const LimitFrame frame{state.limited, state.limit_x,
                                           state.limit_y, state.limit_z};
                    state.next =
                        constrain(joints[index], animated, frame, state.next);
                    if (with_collision) {
                        Contact contact{state.contact_normal, state.contact};
                        collide(index, animated, frame, state.next, contact);
                        state.contact_normal = contact.normal;
                        state.contact = contact.touched;
                    }
                    state.moved = false;
                }
            };
            for (int iteration = 0; iteration < kLinkIterations; ++iteration) {
                for (auto k = first_link; k < last_link; ++k) {
                    limit(springs.links[spring_link_links_[k]]);
                }
                if (springs.link_points != 0) {
                    for (auto k = first_link; k < last_link; ++k) {
                        keep_out(springs.links[spring_link_links_[k]]);
                    }
                }
            }
            settle(true);
            for (int pass = 0; pass < kLinkFinalPasses; ++pass) {
                for (auto k = first_link; k < last_link; ++k) {
                    limit(springs.links[spring_link_links_[k]]);
                }
                settle(false);
            }
            for (auto k = first_joint; k < last_joint; ++k) {
                const auto index = spring_link_order_[k];
                const auto& state = states[index];
                if (!state.live) {
                    continue;
                }
                const Animated animated{state.head, state.direction,
                                        state.length};
                if (!commit(index, animated, state.next,
                            Contact{state.contact_normal, state.contact})) {
                    return false;
                }
                aim(joints[index], Animated{state.head, state.posed, state.length},
                    spring_tails_[index].current);
            }
        }
        return true;
    };
    // A tail is no longer finite: restart from the animated pose next step.
    const auto restart = [&]() {
        std::copy(spring_animated_poses_.begin(), spring_animated_poses_.end(),
                  bone_poses_.begin());
        spring_tails_.clear();
        spring_previous_poses_.clear();
    };
    const bool linking = !spring_link_order_.empty();
    const bool blend = !reset && steps > 1 &&
                       spring_previous_poses_.size() == bone_poses_.size() &&
                       spring_previous_visited_.size() == bone_visited_.size();
    // Only the bones a step reads are blended and restored (the rest is
    // restored once after the steps): the same result for a fraction of the
    // bones (Pelica: 69 of 294).
    for (int step = 0; step < steps; ++step) {
        if (blend) {
            const auto share =
                static_cast<float>(step + 1) / static_cast<float>(steps);
            for (const auto bone : spring_read_bones_) {
                bone_poses_[bone] = spring_animated_poses_[bone];
                if (bone_visited_[bone] == 0 ||
                    spring_previous_visited_[bone] == 0) {
                    continue;
                }
                const auto& pivot = bones[bone].pivot;
                BlendPose(spring_previous_poses_[bone].pose,
                          spring_animated_poses_[bone].pose,
                          {pivot[0] * kModelUnit, pivot[1] * kModelUnit,
                           pivot[2] * kModelUnit},
                          share, bone_poses_[bone].pose);
            }
        } else if (step > 0) {
            for (const auto bone : spring_read_bones_) {
                bone_poses_[bone] = spring_animated_poses_[bone];
            }
        }
        // Chains with a center: the center bone's motion over this step
        // carries their tails, so only motion relative to it swings them.
        for (size_t chain = 0; chain < spring_centers_.size(); ++chain) {
            auto& center = spring_centers_[chain];
            center.moved = false;
            center.active = false;
            const auto center_bone = springs.chain_centers[chain];
            if (center_bone == 0) {
                continue;
            }
            auto& now = bone_poses_[center_bone - 1].pose;
            if (bone_visited_[center_bone - 1] == 0) {
                center.valid = false;
                continue;
            }
            if (center.valid) {
                mat4 inverse;
                glm_mat4_inv(center.pose, inverse);
                glm_mat4_mul(now, inverse, center.delta);
                center.moved = true;
            }
            glm_mat4_copy(now, center.pose);
            center.valid = center.active = true;
        }
        for (size_t index = 0; index < joints.size(); ++index) {
            auto& center = spring_centers_[joints[index].chain];
            auto& tail = spring_tails_[index];
            if (center.moved && tail.live) {
                tail.current = TransformPoint(center.delta, tail.current);
                tail.previous = TransformPoint(center.delta, tail.previous);
            }
        }
        for (size_t index = 0; index < joints.size(); ++index) {
            if (linking && spring_linked_[index] != 0) {
                if (index == spring_link_first_ && !step_linked()) {
                    restart();
                    return;
                }
                continue;
            }
            if (spring_tails_[index].holding && held(index)) {
                continue;  // HOLD: placed once per extraction, below
            }
            Animated animated;
            if (!begin(index, animated)) {
                continue;
            }
            auto target = animated;
            sync(joints[index], target);
            if (!integrate(index, target)) {
                restart();
                return;
            }
            aim(joints[index], animated, spring_tails_[index].current);
        }
        // Colliders on simulated bones as this step left them, for the next.
        for (size_t index = 0; index < colliders.size(); ++index) {
            auto& ends = spring_collider_ends_[index];
            if (!ends.moving) {
                continue;
            }
            const auto& collider = colliders[index];
            ends.valid = bone_visited_[collider.bone] != 0;
            if (ends.valid) {
                const auto& pose = bone_poses_[collider.bone].pose;
                ends.head = TransformPoint(pose, collider.head);
                ends.tail = TransformPoint(pose, collider.tail);
            }
        }
    }
    if (steps > 0) {
        std::copy(spring_animated_poses_.begin(), spring_animated_poses_.end(),
                  bone_poses_.begin());
    }

    // Draw between the last two steps, by the share of a step not yet
    // integrated. Linked joints draw their latest step: it holds the links
    // and was taken against the current pose, while previous is only a
    // velocity reference after a contact and the step before lags the body.
    const auto alpha =
        std::clamp(spring_accumulator_ / kFixedStepSeconds, 0.0f, 1.0f);
    for (size_t index = 0; index < joints.size(); ++index) {
        Animated animated;
        auto& tail = spring_tails_[index];
        if (!begin(index, animated)) {
            tail.holding = false;
            continue;
        }
        auto target = linking && spring_linked_[index] != 0
                          ? tail.current
                          : Add(tail.previous,
                                Scale(Sub(tail.current, tail.previous), alpha));
        if (held(index)) {
            // HOLD keeps the tail in the joint's animated frame: entered at
            // the drawn position, so neither entering nor leaving moves it;
            // leaving starts the integration at rest from the held tail.
            auto& pose = bone_poses_[joints[index].bone].pose;
            if (!tail.holding) {
                mat4 frame_pose;
                mat4 inverse;
                glm_mat4_copy(pose, frame_pose);
                glm_mat4_inv(frame_pose, inverse);
                tail.held = TransformPoint(inverse, target);
                tail.holding = true;
            } else {
                target = TransformPoint(pose, tail.held);
            }
            tail.current = tail.previous = target;
        } else {
            tail.holding = false;
        }
        aim(joints[index], animated, target);
    }
    // This extraction's animated poses start the next one's blend.
    std::swap(spring_previous_poses_, spring_animated_poses_);
    spring_previous_visited_.assign(bone_visited_.begin(), bone_visited_.end());
}
}  // namespace ysm::gfx::renderer
