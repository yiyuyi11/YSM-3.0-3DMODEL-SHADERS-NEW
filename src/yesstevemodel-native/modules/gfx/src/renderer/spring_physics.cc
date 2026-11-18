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
// Colliders on simulated bones move with the simulation: they are posed as
// the previous fixed step left their bones, and a joint never collides with
// the colliders its own swing carries.
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
        // bones, centers. aim() also writes the joints' other descendants,
        // but nothing reads those before the final restore.
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
        for (uint32_t bone = 0; bone < read.size(); ++bone) {
            if (read[bone] != 0) {
                spring_read_bones_.push_back(bone);
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
        for (auto& tail : spring_tails_) {
            tail.current = TransformPoint(frame.motion, tail.current);
            tail.previous = TransformPoint(frame.motion, tail.previous);
        }
        // The previous extraction's animated poses, moved into this model
        // space the same way: where the body was then, seen from now.
        mat4 motion;
        std::memcpy(motion, frame.motion, sizeof(motion));
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
                ends.head = TransformPoint(frame.motion, ends.head);
                ends.tail = TransformPoint(frame.motion, ends.tail);
            }
        }
        mat3 turn;
        for (int column = 0; column < 3; ++column) {
            for (int row = 0; row < 3; ++row) {
                turn[column][row] = frame.motion[column][row];
            }
        }
        velocity_estimate = Rotate(turn, velocity_estimate);
        if (dt > 0.0f) {
            // The motion's translation is how far world-fixed points moved in
            // model space: the body's own displacement, negated.
            const auto weight = std::min(dt / kBodyVelocitySeconds, 1.0f);
            for (int axis = 0; axis < 3; ++axis) {
                velocity_estimate[axis] +=
                    (frame.motion[3][axis] / dt - velocity_estimate[axis]) *
                    weight;
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
    const auto integrate = [&](size_t index, const Animated& animated) {
        const auto& joint = joints[index];
        auto& tail = spring_tails_[index];
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
        // The limit space as the bone's current pose turns it.
        const bool limited = joint.limit != bake::SpringLimit::kNone;
        Vec3 limit_x{1.0f, 0.0f, 0.0f};
        Vec3 limit_y{0.0f, 1.0f, 0.0f};
        Vec3 limit_z{0.0f, 0.0f, 1.0f};
        if (limited) {
            mat3 basis;
            glm_mat4_pick3(bone_poses_[joint.bone].pose, basis);
            limit_y = Normalize(
                Rotate(basis, QuatRotate(joint.limit_rotation, limit_y)),
                animated.direction);
            limit_z = Normalize(
                Cross(Rotate(basis, QuatRotate(joint.limit_rotation, limit_x)),
                      limit_y),
                limit_z);
            limit_x = Cross(limit_y, limit_z);
        }
        // Back at the bone's length about its head, inside its limit.
        const auto constrain = [&](const Vec3& point) {
            auto direction =
                Normalize(Sub(point, animated.head), animated.direction);
            if (limited) {
                const auto local = LimitDirection(
                    joint.limit, joint.limit_angle, joint.limit_yaw,
                    {Dot(direction, limit_x), Dot(direction, limit_y),
                     Dot(direction, limit_z)});
                direction = Add(Add(Scale(limit_x, local[0]),
                                    Scale(limit_y, local[1])),
                                Scale(limit_z, local[2]));
            }
            return Add(animated.head, Scale(direction, animated.length));
        };
        auto next = constrain(Add(
            Add(tail.current, velocity),
            Add(Scale(animated.direction, joint.stiffness * kFixedStepSeconds),
                Scale(gravity, joint.gravity_power * kFixedStepSeconds))));

        const auto first = springs.chain_offsets[joint.chain];
        const auto last = springs.chain_offsets[joint.chain + 1];
        const auto subtree_end = bones[joint.bone].subtree_end;
        const auto animated_tail =
            Add(animated.head, Scale(animated.direction, animated.length));
        Vec3 contact_normal{};
        bool contact = false;
        // A push moved the tail along normal; limited joints go back into
        // their range right away.
        const auto pushed_along = [&](const Vec3& normal) {
            contact_normal = Add(contact_normal, normal);
            contact = true;
            if (limited) {
                next = constrain(next);
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
            next = constrain(next);
            if (!pushed) {
                break;
            }
        }
        auto previous = tail.current;
        if (contact) {
            // The next step's velocity is next - previous: keep only the
            // slide along the surface, slowed by friction.
            const auto normal = Normalize(contact_normal, animated.direction);
            auto slide = Sub(next, tail.current);
            slide = Sub(slide, Scale(normal, Dot(slide, normal)));
            previous = Sub(next, Scale(slide, 1.0f - kContactFriction));
        }
        tail.previous = previous;
        tail.current = next;
        return IsFinite(tail.current) && IsFinite(tail.previous);
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
    const bool blend = !reset && steps > 1 &&
                       spring_previous_poses_.size() == bone_poses_.size() &&
                       spring_previous_visited_.size() == bone_visited_.size();
    // Only the bones a step reads are blended and restored (the rest is
    // restored once after the steps): the same result for a fraction of the
    // bones (the sample model: 69 of 294).
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
            if (spring_tails_[index].holding && held(index)) {
                continue;  // HOLD: placed once per extraction, below
            }
            Animated animated;
            if (!begin(index, animated)) {
                continue;
            }
            if (!integrate(index, animated)) {
                // Restart from the animated pose next step.
                std::copy(spring_animated_poses_.begin(),
                          spring_animated_poses_.end(), bone_poses_.begin());
                spring_tails_.clear();
                spring_previous_poses_.clear();
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
    // integrated.
    const auto alpha =
        std::clamp(spring_accumulator_ / kFixedStepSeconds, 0.0f, 1.0f);
    for (size_t index = 0; index < joints.size(); ++index) {
        Animated animated;
        auto& tail = spring_tails_[index];
        if (!begin(index, animated)) {
            tail.holding = false;
            continue;
        }
        auto target =
            Add(tail.previous, Scale(Sub(tail.current, tail.previous), alpha));
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
