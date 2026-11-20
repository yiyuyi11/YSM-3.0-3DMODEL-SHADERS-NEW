#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>

#include <cglm/mat3.h>
#include <cglm/mat4.h>

#include "gfx/renderer/model_state.h"

// Spring bones follow the VRM SpringBone model: each joint keeps its tail's
// current and previous position, integrates inertia, stiffness toward the
// animated direction and gravity, keeps the bone length, is pushed out of the
// chain's colliders, then rotates the joint's subtree about its pivot so the
// bone points at the tail. Joints run in baked order, so a parent's rotation
// is in place before its children step.
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
// shoulder and a backpack); passes repeat until none overlaps, up to this many.
constexpr int kCollisionPasses = 3;
// Share of the sliding motion a contact removes. Contacts also drop the
// motion along the contact normal, so a tail lying on a moving body part
// (hair on the back, a skirt on the thighs) slides with it instead of
// carrying the push-out into the next step.
constexpr float kContactFriction = 0.4f;
constexpr float kEpsilon = 1e-6f;

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
        return;
    }
    const auto& bones = baked_model_->Bones().list;

    auto dt = frame.delta_seconds;
    const bool motion_finite = std::all_of(
        &frame.motion[0][0], &frame.motion[0][0] + 16,
        [](float value) { return std::isfinite(value); });
    bool reset = frame.reset || spring_model_ != baked_model_.get() ||
                 spring_tails_.size() != joints.size() || !motion_finite ||
                 !std::isfinite(dt) || dt < 0.0f;
    dt = reset ? 0.0f : std::min(dt, kMaxStepSeconds);
    spring_model_ = baked_model_.get();
    auto& velocity_estimate = spring_body_velocity_;
    if (reset) {
        spring_tails_.assign(joints.size(), SpringTail{});
        spring_accumulator_ = 0.0f;
        velocity_estimate = {};
    } else {
        for (auto& tail : spring_tails_) {
            tail.current = TransformPoint(frame.motion, tail.current);
            tail.previous = TransformPoint(frame.motion, tail.previous);
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
    const auto integrate = [&](size_t index, const Animated& animated) {
        const auto& joint = joints[index];
        auto& tail = spring_tails_[index];
        const auto keep =
            std::pow(1.0f - joint.drag, kFixedStepSeconds * kDragReferenceHz);
        const auto gravity = Rotate(gravity_rotation, joint.gravity_dir);
        // Velocity relative to the moving body decays; the body's share is
        // carried along undamped.
        const auto velocity =
            Sub(Scale(Add(Sub(tail.current, tail.previous), carry), keep),
                carry);
        auto next = Add(
            Add(tail.current, velocity),
            Add(Scale(animated.direction, joint.stiffness * kFixedStepSeconds),
                Scale(gravity, joint.gravity_power * kFixedStepSeconds)));
        next = Add(animated.head,
                   Scale(Normalize(Sub(next, animated.head),
                                   animated.direction),
                         animated.length));

        const auto first = springs.chain_offsets[joint.chain];
        const auto last = springs.chain_offsets[joint.chain + 1];
        Vec3 contact_normal{};
        bool contact = false;
        for (int pass = 0; pass < kCollisionPasses; ++pass) {
            bool pushed = false;
            for (auto ref = first; ref < last; ++ref) {
                const auto& collider =
                    springs.colliders[springs.chain_colliders[ref]];
                if (bone_visited_[collider.bone] == 0) {
                    continue;
                }
                const auto& collider_pose = bone_poses_[collider.bone].pose;
                const auto closest = ClosestOnSegment(
                    next, TransformPoint(collider_pose, collider.head),
                    TransformPoint(collider_pose, collider.tail));
                const auto offset = Sub(next, closest);
                const auto distance = Length(offset);
                const auto reach = collider.radius + joint.hit_radius;
                if (distance < reach) {
                    const auto normal = Normalize(offset, animated.direction);
                    next = Add(closest, Scale(normal, reach));
                    contact_normal = Add(contact_normal, normal);
                    contact = pushed = true;
                }
            }
            next = Add(animated.head,
                       Scale(Normalize(Sub(next, animated.head),
                                       animated.direction),
                             animated.length));
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
    if (steps > 0) {
        spring_animated_poses_.assign(bone_poses_.begin(), bone_poses_.end());
    }
    for (int step = 0; step < steps; ++step) {
        if (step > 0) {
            std::copy(spring_animated_poses_.begin(),
                      spring_animated_poses_.end(), bone_poses_.begin());
        }
        for (size_t index = 0; index < joints.size(); ++index) {
            Animated animated;
            if (!begin(index, animated)) {
                continue;
            }
            if (!integrate(index, animated)) {
                // Restart from the animated pose next step.
                std::copy(spring_animated_poses_.begin(),
                          spring_animated_poses_.end(), bone_poses_.begin());
                spring_tails_.clear();
                return;
            }
            aim(joints[index], animated, spring_tails_[index].current);
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
        if (!begin(index, animated)) {
            continue;
        }
        const auto& tail = spring_tails_[index];
        aim(joints[index], animated,
            Add(tail.previous, Scale(Sub(tail.current, tail.previous), alpha)));
    }
}
}  // namespace ysm::gfx::renderer
