package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.proto.mixel.asset.model.data.BodyCapsuleProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringBonesProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringChainProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringColliderProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringJointProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringLinkProto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Anti-clipping for hair, skirts and accessories on spring bones (on by default; humanoid.json "anti_clip": false turns
 * it off, "anti_clip_colliders" / "anti_clip_backstop" / "anti_clip_push": false each layer). Works on the finished
 * output (YSM rest space, arms down, model units) so every test sees the pose the game draws at rest:
 * <ul>
 * <li><b>Body proxy</b>: capsules fitted to the body vertices (under {@link #BODY_WEIGHT} spring weight) of each
 * segment: head (vertical, through the head vertices), neck, {@link #TORSO_BANDS} torso bands from just below the hip
 * joints to the shoulders (horizontal, left to right, as deep as the band), upper arms, forearms, thighs and calves
 * (along the bones, one radius per half). Radii are percentiles of the vertices' distance from the axis
 * ({@link #LIMB_PERCENTILE} etc.), so a proxy sits at or inside the skin, not around loose clothing.</li>
 * <li><b>Hair colliders</b>: chains hung from the head or neck collide with the head, neck, torso band and upper arm
 * capsules. Per chain each collider is shrunk to keep {@link #REST_GAP} plus the joint's hit radius clear of every
 * joint's rest tail (and of its bone, where the native solver tests the bone) in both the bind pose and the default pose
 * (rest rotations), so the modeled pose does not move; one shrunk below {@link #MIN_COLLIDER_SHARE} of its capsule is
 * left out.</li>
 * <li><b>Backstop</b> (after MagicaCloth): each joint within {@link #BACKSTOP_REACH} of a capsule may bring its tail
 * at most {@link #BACKSTOP_BASE} plus {@link #BACKSTOP_GROWTH} of its distance down the chain closer to that capsule's
 * axis than the animation puts it.</li>
 * <li><b>Push-out</b>: per vertex, the share of the push ({@link #PUSH_FULL_WEIGHT} spring weight and more: all of it),
 * the capsules it is tested against (not those it is more than {@link #DEEP_SHARE} of a radius inside at rest, such as
 * hair roots in the head, nor capsules farther than it can swing) and its allowance: its deepest rest depth in those
 * capsules over the bind and the default pose, plus {@link #PUSH_EPSILON}. The renderer moves a skinned vertex out of
 * a capsule only beyond that depth, so the rest pose draws exactly as before.</li>
 * </ul>
 * Hems (skirts, dress hems: spring chains of one piece of cloth hung from the trunk around the hips, see
 * {@link #hems}) get more, each layer behind its own humanoid.json switch, all on by default:
 * <ul>
 * <li><b>Hem colliders</b> ("anti_clip_hem_colliders"): unless the "cloth_colliders" opt-in already fitted leg
 * colliders to them, the chains collide with the proxy's thighs, calves and hip band ({@link #HEM_LEG_MARGIN} thighs
 * fuller), and the proxy's thighs and calves replace the chains' own colliders on those legs (authored per chain for
 * one pose, those differ between neighbouring chains and shove them apart), with one hit radius per level round the
 * hem (the median of the level's joints). The chains' remaining sphere / capsule
 * colliders are shrunk until every hem joint's rest tail and bone keep {@link #REST_GAP} plus its hit radius clear of
 * them in the bind and the default pose (a collider shrunk to nothing takes the hit radius down instead); the proxy
 * colliders are shrunk by the same rule and left out below {@link #MIN_COLLIDER_SHARE}.</li>
 * <li><b>Links</b> ("anti_clip_hem_links", after Kawaii Physics' bone constraints): the chains are ordered around
 * the body and neighbours are linked level by level (closing the ring when they go all the way round); the solver
 * keeps each link within {@link #LINK_STRETCH} over and {@link #LINK_COMPRESSION} under its rest distance, after the
 * chains' collisions, so the cloth between two chains cannot tear open (the push-out keeps the cloth out of the
 * body). With "anti_clip_hem_link_collision" {@link #LINK_POINTS} points along each link also stay out of the chains'
 * colliders, so a leg cannot pass between two chains. Linked joints without an authored angle limit swing at most
 * {@link #HEM_SWING_LIMIT} from their animated bone, so links following a raised thigh cannot fold a chain up.</li>
 * <li><b>Leg sync</b> ("anti_clip_hem_sync", after Kawaii Physics' sync bone): a hem joint whose rest tail lies
 * within {@link #SYNC_REACH} thighs of a thigh or calf has its animated tail moved out by a share of how far that leg
 * would carry it beyond it ({@link #SYNC_SHARE} at the leg's surface, fading with the distance and by
 * {@link #SYNC_LEVEL_FALLOFF} per joint down the chain), so the hem makes room before the leg reaches it.</li>
 * <li><b>Smooth push</b> ("anti_clip_push_smooth"): every garment vertex (at least {@link #GARMENT_WEIGHT} on a
 * hem's bones) is pushed; shares are smoothed over the garment mesh ({@link #PUSH_SMOOTH_PASSES} passes), each vertex
 * tests the capsules its neighbours test, and the push ramps in over {@link #PUSH_MARGIN} thighs past the
 * allowance, so pushed and unpushed neighbours do not tear apart.</li>
 * </ul>
 * Every spring chain (hair, hems, accessories) gets <b>inertia limits</b> ("anti_clip_inertia", after MagicaCloth's
 * world rotation / movement speed limits and Kawaii Physics' world damping): the solver leaves the tails behind in the
 * world for at most {@link #INERTIA_TURN_LIMIT} degrees of turn and {@link #INERTIA_MOVE_LIMIT} model units of travel
 * per second of the model's root; anything faster (a snap turn) carries them with the model, so a skirt or a braid
 * does not swing through the legs and body the moment the body spins. "anti_clip_inertia_turn_limit" and
 * "anti_clip_inertia_move_limit" set the limits (0: none), "anti_clip_world_inertia" (0..1, default 1) the share of
 * the motion within them the tails feel.
 */
final class AntiClip {
    /** Percentile of the axis distance that sets a limb capsule's radius. */
    static final float LIMB_PERCENTILE = 0.75f;
    static final float NECK_PERCENTILE = 0.6f;
    static final float HEAD_PERCENTILE = 0.6f;
    static final float TORSO_PERCENTILE = 0.7f;
    static final int TORSO_BANDS = 5;
    /** Fewer body vertices than this and a segment gets no capsule. */
    static final int MIN_FIT_VERTICES = 30;
    /** A vertex with less spring weight than this is body. */
    static final float BODY_WEIGHT = 0.05f;
    /** Rest clearance of generated colliders, share of the thigh length (about 2 mm). */
    static final float REST_GAP = 0.005f;
    /** A hair collider shrunk below this share of its capsule's radius is left out. */
    static final float MIN_COLLIDER_SHARE = 0.4f;
    /** Backstop allowance at the chain root, share of the thigh length (about 2 cm). */
    static final float BACKSTOP_BASE = 0.05f;
    /** Backstop allowance added per unit of distance down the chain (0.25 let Pelica's skirt tips in on yaw turns). */
    static final float BACKSTOP_GROWTH = 0.12f;
    /** Joints whose rest tail is farther than this (share of the thigh) from every capsule get no backstop. */
    static final float BACKSTOP_REACH = 0.6f;
    /** Spring weight from which a vertex takes the whole push; less takes a proportional share. */
    static final float PUSH_FULL_WEIGHT = 0.5f;
    /** A vertex this share of a capsule's radius or more inside it at rest is not tested against it. */
    static final float DEEP_SHARE = 0.5f;
    /** Added to every push allowance, model units: float noise never pushes a rest vertex. */
    static final float PUSH_EPSILON = 2e-4f;
    /** A vertex is tested against a capsule within REACH_FACTOR times its distance to its chain root plus margin. */
    static final float REACH_FACTOR = 1.5f;
    static final float REACH_MARGIN = 0.25f;
    /** Garment vertices: at least this weight on a hem's bones (its joints, their waistband and below). */
    static final float GARMENT_WEIGHT = 0.5f;
    /** Chains sharing this many vertices (more than {@link #BODY_WEIGHT} on each) are one piece of cloth. */
    static final int HEM_SHARED_VERTICES = 10;
    /** A hem has at least this many hem chains in one piece of cloth. */
    static final int HEM_MIN_CHAINS = 3;
    /**
     * Hem chains reach at most this many thighs sideways from the hips (bone pivots): 1.2 left out Aglina's long
     * flared overskirt (1.23 to 1.41); a tail or a cape is no hem anyway (one chain / hung higher up).
     */
    static final float HEM_SIDE = 2f;
    /** The chains go all the way round unless the widest gap between them is this many times the median gap. */
    static final float HEM_RING_GAP = 2.5f;
    /**
     * Link limits, shares of the rest distance: firm against stretching (0.08 tore Chen's skirt sitting: the thighs
     * lift the front chains and the links dragged the sides up into a disc), loose for folds.
     */
    static final float LINK_STRETCH = 0.12f;
    static final float LINK_COMPRESSION = 0.35f;
    /** Points along each link kept out of the chains' colliders. */
    static final int LINK_POINTS = 3;
    /** Leg sync: share at the leg's surface, the reach (share of the thigh) and the falloff per joint down a chain. */
    static final float SYNC_SHARE = 0.5f;
    static final float SYNC_REACH = 0.5f;
    static final float SYNC_LEVEL_FALLOFF = 0.7f;
    /** Smooth push: share smoothing passes over the garment mesh and the push ramp (share of the thigh). */
    static final int PUSH_SMOOTH_PASSES = 3;
    static final float PUSH_MARGIN = 0.02f;
    /** Proxy capsules hem chains collide with ("thigh", "calf" on both sides and the lowest torso band). */
    private static final Set<String> HEM_COLLIDERS = Set.of("thigh_l", "thigh_r", "calf_l", "calf_r", "torso0");
    /** Added to the proxy legs' radius for hem chains, share of the thigh: the cloth's own thickness. */
    static final float HEM_LEG_MARGIN = 0.03f;
    /**
     * Swing limit of linked hem joints without an authored one: cone half angle about the animated bone, radians.
     * 100 deg keeps Chen's bag out of the belly (proxy max 10.8 -> 1.8 cm); 90 and 110 deg clip more sitting.
     */
    static final float HEM_SWING_LIMIT = (float)Math.toRadians(100.0);
    /** Inertia limits: degrees of turn and model units of travel per second the tails stay behind for. */
    static final float INERTIA_TURN_LIMIT = 360f;
    static final float INERTIA_MOVE_LIMIT = 10f;

    private static final Map<String, String> SEGMENTS = Map.ofEntries(Map.entry("Head", "head"),
        Map.entry("MHead", "head"), Map.entry("AllHead", "neck"), Map.entry("LeftArm", "upperarm_l"),
        Map.entry("RightArm", "upperarm_r"), Map.entry("LeftForeArm", "forearm_l"),
        Map.entry("RightForeArm", "forearm_r"), Map.entry("LeftHand", "hand"), Map.entry("RightHand", "hand"),
        Map.entry("LeftLeg", "thigh_l"), Map.entry("RightLeg", "thigh_r"), Map.entry("LeftLowerLeg", "calf_l"),
        Map.entry("RightLowerLeg", "calf_r"), Map.entry("LeftFoot", "foot"), Map.entry("RightFoot", "foot"),
        Map.entry("AllBody", "torso"), Map.entry("MAllBody", "torso"), Map.entry("DownBody", "torso"),
        Map.entry("UpBody", "torso"), Map.entry("UpperBody", "torso"), Map.entry("Arm", "torso"));
    private static final Set<String> HAIR_COLLIDERS = Set.of("head", "neck", "upperarm_l", "upperarm_r");

    private AntiClip() {}

    /**
     * Which layers run (humanoid.json anti_clip_*); clothChains: the chains (first joints' bone names) the
     * "cloth_colliders" opt-in fitted leg colliders to already: hems made of them get no proxy leg colliders;
     * hemChains: bones of the chains that make the hem ("anti_clip_hem_chains", empty: found by rule). Inertia: turn
     * limit in degrees per second, move limit in model units per second (0: none), world inertia share (1: full).
     */
    record Options(boolean colliders, boolean backstop, boolean push, boolean hemColliders, boolean hemLinks,
        boolean hemLinkCollision, boolean hemSync, boolean pushSmooth, Set<String> clothChains, Set<String> hemChains,
        float inertiaTurnLimit, float inertiaMoveLimit, float worldInertia) {
        boolean any() {
            return colliders || backstop || push || hemColliders || hemLinks || hemSync || inertia();
        }

        boolean inertia() {
            return inertiaTurnLimit > 0f || inertiaMoveLimit > 0f || worldInertia < 1f;
        }
    }

    /** The output skeleton: names, parent indices (-1 root), pivots in pixels, rest rotations in radians. */
    record Skeleton(String[] names, int[] parents, float[] pivots, float[] rotations) {
    }

    /**
     * What was done: proxy capsules, hair chains and their colliders, backstops, pushed vertices; per hem its chains
     * (their first joints' bones, in order) and whether they close a ring, proxy colliders added to hems, authored
     * colliders and hit radii shrunk for the rest clearance, links, synced joints and garment vertices with a smooth
     * push.
     */
    record Report(List<String> capsules, int hairChains, int colliders, int backstops, int pushed, List<String> hems,
        int hemColliders, List<String> replaced, List<String> clearance, int links, int synced, int garment) {
    }

    record Result(SpringBonesProto springs, Report report) {
    }

    /** A body proxy capsule: radius r0 at a, r1 at b. */
    static final class Capsule {
        final String name;
        final int bone;
        final Vector3f a;
        final Vector3f b;
        final float r0;
        final float r1;

        Capsule(String name, int bone, Vector3f a, Vector3f b, float r0, float r1) {
            this.name = name;
            this.bone = bone;
            this.a = a;
            this.b = b;
            this.r0 = r0;
            this.r1 = r1;
        }

        /** Depth of p inside the capsule posed by m (negative outside). */
        float depth(Matrix4f m, Vector3f p) {
            var pa = m.transformPosition(new Vector3f(a));
            var pb = m.transformPosition(new Vector3f(b));
            var ab = new Vector3f(pb).sub(pa);
            var squared = ab.lengthSquared();
            var t = squared > 1e-12f ? clamp(new Vector3f(p).sub(pa).dot(ab) / squared) : 0f;
            return r0 + (r1 - r0) * t - p.distance(pa.fma(t, ab));
        }

        float minRadius() {
            return Math.min(r0, r1);
        }

        float maxRadius() {
            return Math.max(r0, r1);
        }
    }

    static Result apply(SpringBonesProto springs, Skeleton skeleton, float[] positions, int[] joints, float[] weights,
        int[] triangles, int[] outlineHulls, Options options) {
        var none = new Result(springs, new Report(List.of(), 0, 0, 0, 0, List.of(), 0, List.of(), List.of(), 0, 0, 0));
        if (springs == null || !options.any() || springs.joints().isEmpty()) {
            return none;
        }
        var bones = skeleton.names.length;
        var index = new HashMap<String, Integer>();
        for (var i = 0; i < bones; i++) {
            index.put(skeleton.names[i], i);
        }
        for (var required : List.of("Head", "AllHead", "LeftArm", "LeftForeArm", "LeftHand", "RightArm",
            "RightForeArm", "RightHand", "LeftLeg", "LeftLowerLeg", "LeftFoot", "RightLeg", "RightLowerLeg",
            "RightFoot", "AllBody")) {
            if (!index.containsKey(required)) {
                return none;
            }
        }
        var pivots = new Vector3f[bones];
        for (var i = 0; i < bones; i++) {
            pivots[i] = new Vector3f(skeleton.pivots[i * 3], skeleton.pivots[i * 3 + 1], skeleton.pivots[i * 3 + 2])
                .div(16f);
        }
        var thigh = pivots[index.get("LeftLeg")].distance(pivots[index.get("LeftLowerLeg")]);
        if (!(thigh > 1e-6f)) {
            return none;
        }
        // Spring-simulated bones: a joint's bone and everything below it.
        var jointList = springs.joints();
        var jointOfBone = new int[bones];
        Arrays.fill(jointOfBone, -1);
        for (var j = 0; j < jointList.size(); j++) {
            jointOfBone[jointList.get(j).bone()] = j;
        }
        var spring = new boolean[bones];
        for (var b = 0; b < bones; b++) {
            for (var a = b; a >= 0 && !spring[b]; a = skeleton.parents[a]) {
                spring[b] = jointOfBone[a] >= 0;
            }
        }
        var segment = segments(skeleton);
        // Default pose: every bone at its rest rotation.
        var rest = restPose(skeleton, pivots);
        var vertexCount = positions.length / 3;
        var hull = new boolean[vertexCount];
        if (outlineHulls != null) {
            for (var copy : outlineHulls) {
                if (copy >= 0 && copy < vertexCount) {
                    hull[copy] = true;
                }
            }
        }
        var springWeight = new float[vertexCount];
        var dominant = new int[vertexCount];
        var dominantSpring = new int[vertexCount];
        for (var v = 0; v < vertexCount; v++) {
            var best = -1f;
            var bestSpring = -1f;
            dominantSpring[v] = -1;
            for (var k = 0; k < 4; k++) {
                var w = weights[v * 4 + k];
                var bone = joints[v * 4 + k];
                if (w <= 0f || bone < 0 || bone >= bones) {
                    continue;
                }
                if (w > best) {
                    best = w;
                    dominant[v] = bone;
                }
                if (spring[bone]) {
                    springWeight[v] += w;
                    if (w > bestSpring) {
                        bestSpring = w;
                        dominantSpring[v] = bone;
                    }
                }
            }
        }

        var capsules = fit(skeleton, index, pivots, segment, positions, springWeight, dominant, hull, thigh);
        if (capsules.isEmpty()) {
            return none;
        }
        var out = springs.toBuilder();
        out.clearBody();
        for (var c : capsules) {
            out.addBody(BodyCapsuleProto.newBuilder().setBone(c.bone).addHead(c.a.x, c.a.y, c.a.z)
                .addTail(c.b.x, c.b.y, c.b.z).setRadius(c.r0).setTailRadius(c.r1).build());
        }

        // Chains: root joints, members, and their hair-ness.
        var chainCount = springs.chains().size();
        var members = new ArrayList<List<Integer>>();
        for (var c = 0; c < chainCount; c++) {
            members.add(new ArrayList<>());
        }
        for (var j = 0; j < jointList.size(); j++) {
            members.get(jointList.get(j).chain()).add(j);
        }
        var path = new float[jointList.size()];
        var rootPivot = new Vector3f[jointList.size()];
        for (var j = 0; j < jointList.size(); j++) {
            var length = 0f;
            var root = j;
            for (var k = j; k >= 0; ) {
                var joint = jointList.get(k);
                length += tail(joint).distance(pivots[joint.bone()]);
                root = k;
                var parent = skeleton.parents[joint.bone()];
                k = parent >= 0 && jointOfBone[parent] >= 0 && jointList.get(jointOfBone[parent]).chain()
                    == joint.chain() ? jointOfBone[parent] : -1;
            }
            path[j] = length;
            rootPivot[j] = pivots[jointList.get(root).bone()];
        }

        var hairChains = 0;
        var addedColliders = 0;
        var colliderList = new ArrayList<SpringColliderProto>();
        springs.colliders().forEach(colliderList::add);
        var chainList = new ArrayList<SpringChainProto>();
        springs.chains().forEach(chainList::add);
        if (options.colliders) {
            var colliderIndex = new HashMap<Long, Integer>();
            for (var c = 0; c < chainCount; c++) {
                if (!isHair(members.get(c), jointList, skeleton, segment, jointOfBone)) {
                    continue;
                }
                hairChains++;
                var added = new ArrayList<Integer>();
                for (var k = 0; k < capsules.size(); k++) {
                    var capsule = capsules.get(k);
                    if (!HAIR_COLLIDERS.contains(capsule.name) && !capsule.name.startsWith("torso")) {
                        continue;
                    }
                    var radius = clearRadius(capsule.bone, capsule.a, capsule.b, capsule.minRadius(), members.get(c),
                        jointList, null, pivots, rest, thigh);
                    if (radius < MIN_COLLIDER_SHARE * capsule.minRadius()) {
                        continue;
                    }
                    var key = ((long)k << 32) | Float.floatToIntBits(radius);
                    var existing = colliderIndex.get(key);
                    if (existing == null) {
                        existing = colliderList.size();
                        colliderIndex.put(key, existing);
                        colliderList.add(SpringColliderProto.newBuilder().setBone(capsule.bone)
                            .addHead(capsule.a.x, capsule.a.y, capsule.a.z).addTail(capsule.b.x, capsule.b.y,
                                capsule.b.z).setRadius(radius).build());
                        addedColliders++;
                    }
                    added.add(existing);
                }
                if (!added.isEmpty()) {
                    var chain = chainList.get(c).toBuilder();
                    added.forEach(chain::addColliders);
                    chainList.set(c, chain.build());
                }
            }
        }

        // Hems: chains of one piece of cloth around the hips (skirts, dress hems).
        var owner = new int[bones];
        var hems = options.hemColliders || options.hemLinks || options.hemSync || options.push && options.pushSmooth
            ? hems(skeleton, pivots, segment, spring, jointOfBone, jointList, members, joints, weights, index, thigh,
                owner, options.hemChains)
            : List.<Hem>of();
        var hitRadius = new float[jointList.size()];
        Arrays.fill(hitRadius, -1f);
        var clearance = new ArrayList<String>();
        var replaced = new ArrayList<String>();
        var hemColliders = options.hemColliders && !hems.isEmpty()
            ? hemColliders(hems, capsules, colliderList, chainList, jointList, members, pivots, rest, thigh, hitRadius,
                skeleton.names, segment, options.clothChains, replaced, clearance)
            : 0;
        var syncs = new int[jointList.size()];
        var syncShares = new float[jointList.size()];
        var synced = options.hemSync ? sync(hems, capsules, jointList, thigh, syncs, syncShares) : 0;
        out.clearColliders();
        colliderList.forEach(out::addColliders);
        out.clearChains();
        chainList.forEach(out::addChains);

        var backstops = 0;
        // Linked hem joints without an authored angle limit get a cone about their animated bone: links that follow
        // a raised thigh must not fold a chain (and what hangs from it, Chen's bag) up into the belly.
        var swingLimited = new boolean[jointList.size()];
        if (options.hemLinks) {
            hems.forEach(hem -> hem.paths().forEach(p -> Arrays.stream(p).forEach(j -> swingLimited[j] = true)));
        }
        out.clearJoints();
        for (var j = 0; j < jointList.size(); j++) {
            var joint = jointList.get(j);
            if (options.backstop) {
                var tailPoint = tail(joint);
                var best = -1;
                var bestDistance = BACKSTOP_REACH * thigh;
                for (var k = 0; k < capsules.size(); k++) {
                    var capsule = capsules.get(k);
                    if (isAncestor(skeleton, joint.bone(), capsule.bone)) {
                        continue;  // the joint's own swing carries it
                    }
                    var distance = -capsule.depth(IDENTITY, tailPoint);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = k;
                    }
                }
                if (best >= 0) {
                    joint = joint.toBuilder().setBackstop(best + 1)
                        .setBackstopAllowance(BACKSTOP_BASE * thigh + BACKSTOP_GROWTH * path[j]).build();
                    backstops++;
                }
            }
            if (hitRadius[j] >= 0f) {
                joint = joint.withHitRadius(hitRadius[j]);
            }
            if (syncs[j] != 0) {
                joint = joint.withSync(syncs[j]).withSyncShare(syncShares[j]);
            }
            if (swingLimited[j] && joint.limit() == 0) {
                var direction = tail(joint).sub(pivots[joint.bone()]);
                if (direction.lengthSquared() > 1e-12f) {
                    direction.normalize();
                    var frame = new org.joml.Quaternionf().rotationTo(0f, 1f, 0f, direction.x, direction.y,
                        direction.z);
                    joint = joint.toBuilder().setLimit(1).clearLimitRotation()
                        .addLimitRotation(frame.x, frame.y, frame.z, frame.w).setLimitAngle(HEM_SWING_LIMIT).build();
                }
            }
            out.addJoints(joint);
        }

        var points = options.hemLinkCollision ? LINK_POINTS : 0;
        var links = options.hemLinks
            ? links(hems, jointList, colliderList, chainList, hitRadius, rest, points, thigh)
            : List.<SpringLinkProto>of();
        out.clearLinks().clearLinkStretch().clearLinkCompression().clearLinkPoints();
        if (!links.isEmpty()) {
            links.forEach(out::addLinks);
            out.setLinkStretch(LINK_STRETCH).setLinkCompression(LINK_COMPRESSION).setLinkPoints(points);
        }
        out.clearInertiaTurnLimit().clearInertiaMoveLimit().clearInertiaDamping();
        if (options.inertiaTurnLimit > 0f) {
            out.setInertiaTurnLimit((float)Math.toRadians(options.inertiaTurnLimit));
        }
        if (options.inertiaMoveLimit > 0f) {
            out.setInertiaMoveLimit(options.inertiaMoveLimit);
        }
        if (options.worldInertia < 1f) {
            out.setInertiaDamping(1f - Math.max(options.worldInertia, 0f));
        }

        var pushed = 0;
        var garment = 0;
        out.clearPushShare().clearPushAllowance().clearPushCapsules().clearPushMargin();
        if (options.push) {
            var share = new float[vertexCount];
            var allowance = new float[vertexCount];
            var masks = new int[vertexCount];
            var bind = new Vector3f();
            var posed = new Vector3f();
            for (var v = 0; v < vertexCount; v++) {
                if (springWeight[v] < BODY_WEIGHT || dominantSpring[v] < 0) {
                    continue;
                }
                restPositions(v, positions, joints, weights, rest, bind, posed);
                var root = rootOf(dominantSpring[v], skeleton, jointOfBone, jointList, rootPivot);
                var reach = REACH_FACTOR * bind.distance(root) + REACH_MARGIN * thigh;
                var mask = 0;
                var deepest = 0f;
                for (var k = 0; k < capsules.size(); k++) {
                    var capsule = capsules.get(k);
                    var depth = Math.max(capsule.depth(IDENTITY, bind), capsule.depth(rest[capsule.bone], posed));
                    if (depth > DEEP_SHARE * capsule.maxRadius()) {
                        continue;
                    }
                    var arm = capsule.name.startsWith("upperarm") || capsule.name.startsWith("forearm");
                    if (!arm && -depth > reach) {
                        continue;
                    }
                    mask |= 1 << k;
                    deepest = Math.max(deepest, depth);
                }
                if (mask == 0) {
                    continue;
                }
                share[v] = Math.min(springWeight[v] / PUSH_FULL_WEIGHT, 1f);
                allowance[v] = deepest + PUSH_EPSILON;
                masks[v] = mask;
                pushed++;
            }
            if (options.pushSmooth && !hems.isEmpty()) {
                var margin = new float[vertexCount];
                garment = smoothPush(hems, owner, capsules, positions, joints, weights, triangles, rest, thigh, share,
                    allowance, masks, margin);
                pushed = 0;
                for (var v = 0; v < vertexCount; v++) {
                    pushed += share[v] > 0f && masks[v] != 0 ? 1 : 0;
                }
                if (garment > 0) {
                    out.addPushMargin(margin);
                }
            }
            out.addPushShare(share).addPushAllowance(allowance).addPushCapsules(masks);
        }
        var names = capsules.stream().map(c -> c.name).toList();
        var hemNames = new ArrayList<String>();
        for (var hem : hems) {
            var chainNames = hem.paths().stream().map(p -> skeleton.names[jointList.get(p[0]).bone()]).toList();
            hemNames.add(chainNames.size() + " chains " + chainNames + (hem.ring() ? " ring" : " open"));
        }
        return new Result(out.build(), new Report(names, hairChains, addedColliders, backstops, pushed,
            List.copyOf(hemNames), hemColliders, List.copyOf(replaced), List.copyOf(clearance), links.size(), synced,
            garment));
    }

    /** A vertex's rest position (bind) and its position in the default pose (posed). */
    private static void restPositions(int v, float[] positions, int[] joints, float[] weights, Matrix4f[] rest,
        Vector3f bind, Vector3f posed) {
        bind.set(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]);
        posed.zero();
        var scratch = new Vector3f();
        for (var k = 0; k < 4; k++) {
            var w = weights[v * 4 + k];
            var bone = joints[v * 4 + k];
            if (w > 0f && bone >= 0 && bone < rest.length) {
                posed.fma(w, rest[bone].transformPosition(scratch.set(bind)));
            }
        }
    }

    /** Hem chains of one piece of cloth, ordered around the body; ring: they go all the way round. */
    record Hem(List<Integer> chains, boolean ring, List<int[]> paths) {
    }

    /**
     * Hems: spring chains hung (through unsimulated, non-humanoid bones: the waistband) from the trunk, rooted from
     * 0.3 thighs below to 0.9 thighs above the hip joints, reaching 0.1 thighs below them and staying within
     * {@link #HEM_SIDE} thighs of them sideways; chains sharing {@link #HEM_SHARED_VERTICES} vertices are one piece of
     * cloth, and one with {@link #HEM_MIN_CHAINS} or more such chains is a hem. The chains are ordered around the hips
     * by their first joint's rest tail, starting after the widest gap unless they close a ring; paths: each chain's
     * joints top down. owner (out): per bone the chain whose joints or waistband it hangs from, -1 for none.
     * listed (humanoid.json "anti_clip_hem_chains"): when not empty, the chains with a joint on one of these bones are
     * the one hem instead (at least two, whatever the rule says), for cloth the rule cannot see.
     */
    static List<Hem> hems(Skeleton skeleton, Vector3f[] pivots, String[] segment, boolean[] spring, int[] jointOfBone,
        List<SpringJointProto> joints, List<List<Integer>> members, int[] vertexJoints, float[] weights,
        Map<String, Integer> index, float thigh, int[] owner, Set<String> listed) {
        var bones = skeleton.names.length;
        var parents = skeleton.parents;
        var carriesBody = new boolean[bones];
        for (var b = 0; b < bones; b++) {
            if (SEGMENTS.containsKey(skeleton.names[b])) {
                for (var a = b; a >= 0 && !carriesBody[a]; a = parents[a]) {
                    carriesBody[a] = true;
                }
            }
        }
        var hipL = pivots[index.get("LeftLeg")];
        var hipR = pivots[index.get("RightLeg")];
        var hip = new Vector3f(hipL).add(hipR).mul(0.5f);
        var chainCount = members.size();
        var direct = new int[bones];
        Arrays.fill(direct, -1);
        var hemLike = new boolean[chainCount];
        var paths = new int[chainCount][];
        for (var c = 0; c < chainCount; c++) {
            var chainJoints = members.get(c);
            if (chainJoints.isEmpty()) {
                continue;
            }
            // Each joint's nearest joint above it in the chain; one root.
            var above = new HashMap<Integer, Integer>();
            var roots = new ArrayList<Integer>();
            for (var j : chainJoints) {
                var a = parents[joints.get(j).bone()];
                while (a >= 0 && jointOfBone[a] < 0) {
                    a = parents[a];
                }
                var up = a >= 0 && joints.get(jointOfBone[a]).chain() == c ? jointOfBone[a] : -1;
                above.put(j, up);
                if (up < 0) {
                    roots.add(j);
                }
            }
            for (var j : chainJoints) {
                direct[joints.get(j).bone()] = c;
            }
            if (roots.size() != 1) {
                continue;
            }
            var path = new ArrayList<Integer>();
            for (int j = roots.get(0); j >= 0; ) {
                path.add(j);
                var next = -1;
                for (var k : chainJoints) {
                    if (above.get(k) == j && (next < 0 || joints.get(k).bone() < joints.get(next).bone())) {
                        next = k;
                    }
                }
                j = next;
            }
            paths[c] = path.stream().mapToInt(Integer::intValue).toArray();
            var rootBone = joints.get(roots.get(0)).bone();
            var a = parents[rootBone];
            while (a >= 0 && !carriesBody[a] && !spring[a]) {
                direct[a] = c;  // the waistband
                a = parents[a];
            }
            if (a < 0 || spring[a] || !"torso".equals(segment[a])) {
                continue;
            }
            // Reach over the chain's bones and the bones they carry directly (the harness's garment rule): a tail
            // past the last bone does not count, so a short ribbon ending at the hips is no hem chain.
            var lowest = Float.POSITIVE_INFINITY;
            var side = 0f;
            for (var b = 0; b < bones; b++) {
                var own = jointOfBone[b] >= 0 && joints.get(jointOfBone[b]).chain() == c;
                var p = parents[b];
                if (own || p >= 0 && jointOfBone[p] >= 0 && joints.get(jointOfBone[p]).chain() == c) {
                    lowest = Math.min(lowest, pivots[b].y);
                    side = Math.max(side, (float)Math.hypot(pivots[b].x - hip.x, pivots[b].z - hip.z));
                }
            }
            var rootY = pivots[rootBone].y;
            hemLike[c] = rootY >= hip.y - 0.3f * thigh && rootY <= hip.y + 0.9f * thigh
                && lowest <= hip.y - 0.1f * thigh && side <= HEM_SIDE * thigh;
        }
        for (var b = 0; b < bones; b++) {
            owner[b] = -1;
            for (var a = b; a >= 0; a = parents[a]) {
                if (direct[a] >= 0) {
                    owner[b] = direct[a];
                    break;
                }
            }
        }
        // Chains that share vertices carry one piece of cloth.
        var group = new int[chainCount];
        for (var c = 0; c < chainCount; c++) {
            group[c] = c;
        }
        var shared = new HashMap<Long, Integer>();
        var seen = new int[4];
        for (var v = 0; v < weights.length / 4; v++) {
            var n = 0;
            for (var k = 0; k < 4; k++) {
                var bone = vertexJoints[v * 4 + k];
                if (weights[v * 4 + k] <= BODY_WEIGHT || bone < 0 || bone >= bones || owner[bone] < 0) {
                    continue;
                }
                var known = false;
                for (var i = 0; i < n; i++) {
                    known |= seen[i] == owner[bone];
                }
                if (!known) {
                    seen[n++] = owner[bone];
                }
            }
            for (var x = 0; x < n; x++) {
                for (var y = x + 1; y < n; y++) {
                    shared.merge((long)Math.min(seen[x], seen[y]) * chainCount + Math.max(seen[x], seen[y]), 1,
                        Integer::sum);
                }
            }
        }
        for (var entry : shared.entrySet()) {
            if (entry.getValue() >= HEM_SHARED_VERTICES) {
                var x = find(group, (int)(entry.getKey() / chainCount));
                var y = find(group, (int)(entry.getKey() % chainCount));
                group[Math.max(x, y)] = Math.min(x, y);
            }
        }
        var byGroup = new java.util.TreeMap<Integer, List<Integer>>();
        for (var c = 0; c < chainCount; c++) {
            var take = listed.isEmpty() ? hemLike[c] : paths[c] != null
                && members.get(c).stream().anyMatch(j -> listed.contains(skeleton.names[joints.get(j).bone()]));
            if (take) {
                byGroup.computeIfAbsent(listed.isEmpty() ? find(group, c) : 0, g -> new ArrayList<>()).add(c);
            }
        }
        var out = new ArrayList<Hem>();
        for (var chains : byGroup.values()) {
            if (chains.size() < (listed.isEmpty() ? HEM_MIN_CHAINS : 2)) {
                continue;
            }
            var angle = new HashMap<Integer, Double>();
            for (var c : chains) {
                var t = tail(joints.get(paths[c][0]));
                angle.put(c, Math.atan2(t.x - hip.x, t.z - hip.z));
            }
            var order = new ArrayList<>(chains);
            order.sort(java.util.Comparator.comparingDouble(angle::get));
            var n = order.size();
            var gaps = new double[n];
            for (var i = 0; i < n; i++) {
                var next = angle.get(order.get((i + 1) % n));
                gaps[i] = i + 1 < n ? next - angle.get(order.get(i)) : next + 2 * Math.PI - angle.get(order.get(i));
            }
            var widest = 0;
            for (var i = 1; i < n; i++) {
                widest = gaps[i] > gaps[widest] ? i : widest;
            }
            var sorted = gaps.clone();
            Arrays.sort(sorted);
            var ring = n > 2 && gaps[widest] < HEM_RING_GAP * sorted[n / 2];
            var ordered = new ArrayList<Integer>();
            for (var i = 0; i < n; i++) {
                ordered.add(order.get(ring ? i : (widest + 1 + i) % n));
            }
            out.add(new Hem(List.copyOf(ordered), ring, ordered.stream().map(c -> paths[c]).toList()));
        }
        return out;
    }

    private static int find(int[] group, int c) {
        while (group[c] != c) {
            c = group[c];
        }
        return c;
    }

    /**
     * Hem colliders (see the class comment). For a hem the "cloth_colliders" opt-in did not fit leg colliders to
     * (clothChains: the first joints' bone names of the chains it took; all of a hem's chains must be among them), the
     * proxy's legs replace the hem chains' own sphere / capsule colliders on those legs (replaced, out: per collider
     * dropped its bone, radius and chain count). The chains' remaining colliders shrink and, where even a point
     * collider would reach a joint, its hit radius (hitRadius: per joint, -1 unchanged) for the rest clearance; a
     * collider other chains use too is copied for the hem chains. Then such hems get the proxy's legs and hip band.
     * Returns the proxy colliders added; notes what was shrunk or left out.
     */
    private static int hemColliders(List<Hem> hems, List<Capsule> capsules, List<SpringColliderProto> colliders,
        List<SpringChainProto> chains, List<SpringJointProto> joints, List<List<Integer>> members, Vector3f[] pivots,
        Matrix4f[] rest, float thigh, float[] hitRadius, String[] names, String[] segment, Set<String> clothChains,
        List<String> replaced, List<String> notes) {
        var hemChains = new java.util.TreeSet<Integer>();
        hems.forEach(hem -> hemChains.addAll(hem.chains()));
        var legHems = hems.stream().filter(hem -> !hem.paths().stream()
            .allMatch(path -> clothChains.contains(names[joints.get(path[0]).bone()]))).toList();
        var legChains = new java.util.TreeSet<Integer>();
        legHems.forEach(hem -> legChains.addAll(hem.chains()));
        var legJoints = new ArrayList<Integer>();
        legChains.forEach(c -> legJoints.addAll(members.get(c)));
        if (!legHems.isEmpty()) {
            // One hit radius per level round the hem (the median), so neighbouring chains meet the same legs; then
            // the legs the proxy covers (its collider survives the rest clearance) lose the chains' own colliders.
            var uniform = hitRadius.clone();
            for (var hem : legHems) {
                for (var level = 0; ; level++) {
                    var ring = new ArrayList<Integer>();
                    for (var path : hem.paths()) {
                        if (level < path.length) {
                            ring.add(path[level]);
                        }
                    }
                    if (ring.isEmpty()) {
                        break;
                    }
                    var radii = new ArrayList<Float>();
                    ring.forEach(j -> radii.add(hitRadius[j] >= 0f ? hitRadius[j] : joints.get(j).hitRadius()));
                    radii.sort(null);
                    var median = radii.get(radii.size() / 2);
                    ring.forEach(j -> uniform[j] = median);
                }
            }
            var legs = new java.util.HashSet<String>();
            for (var capsule : capsules) {
                if (HEM_COLLIDERS.contains(capsule.name) && !capsule.name.startsWith("torso") && hemRadius(capsule,
                    legJoints, joints, uniform, pivots, rest, thigh) >= MIN_COLLIDER_SHARE * capsule.minRadius()) {
                    legs.add(capsule.name);
                }
            }
            if (!legs.isEmpty()) {
                System.arraycopy(uniform, 0, hitRadius, 0, uniform.length);
            }
            var dropped = new java.util.TreeMap<Integer, Integer>();
            for (var c : legChains) {
                var list = chains.get(c).colliders();
                var chain = chains.get(c).toBuilder().clearColliders();
                for (var i = 0; i < list.size(); i++) {
                    var collider = colliders.get(list.getInt(i));
                    if (collider.type() == 0 && legs.contains(segment[collider.bone()])) {
                        dropped.merge(list.getInt(i), 1, Integer::sum);
                    } else {
                        chain.addColliders(list.getInt(i));
                    }
                }
                chains.set(c, chain.build());
            }
            dropped.forEach((ci, count) -> replaced.add(String.format(java.util.Locale.ROOT, "%s r %.3f x%d",
                names[colliders.get(ci).bone()], colliders.get(ci).radius(), count)));
        }
        var users = new java.util.TreeMap<Integer, List<Integer>>();
        for (var c = 0; c < chains.size(); c++) {
            var list = chains.get(c).colliders();
            for (var i = 0; i < list.size(); i++) {
                users.computeIfAbsent(list.getInt(i), k -> new ArrayList<>()).add(c);
            }
        }
        var gap = REST_GAP * thigh;
        var copies = new HashMap<Integer, Integer>();
        for (var entry : users.entrySet()) {
            var ci = entry.getKey();
            var collider = colliders.get(ci);
            var hemUsers = entry.getValue().stream().filter(hemChains::contains).toList();
            if (hemUsers.isEmpty() || collider.type() != 0) {
                continue;
            }
            var tested = new ArrayList<Integer>();
            hemUsers.forEach(c -> tested.addAll(members.get(c)));
            var head = point(collider.head());
            var tail = point(collider.tail());
            var radius = collider.radius();
            var needed = clearRadius(collider.bone(), head, tail, radius, tested, joints, hitRadius, pivots, rest,
                thigh);
            if (!(needed < radius)) {
                continue;
            }
            var shrunk = Math.max(needed, 0f);
            var note = new StringBuilder(String.format(java.util.Locale.ROOT, "%s r %.3f->%.3f",
                names[collider.bone()], radius, shrunk));
            if (needed < 0f) {
                // Even a point collider reaches these joints: their hit radii shrink instead.
                for (var j : tested) {
                    var distance = restDistance(collider.bone(), head, tail, joints.get(j), pivots, rest);
                    var hit = hitRadius[j] >= 0f ? hitRadius[j] : joints.get(j).hitRadius();
                    if (distance - hit - gap < 0f) {
                        hitRadius[j] = Math.max(distance - gap, 0f);
                        note.append(String.format(java.util.Locale.ROOT, ", %s hit %.3f->%.3f",
                            names[joints.get(j).bone()], hit, hitRadius[j]));
                    }
                }
            }
            if (hemUsers.size() < entry.getValue().size()) {
                copies.put(ci, colliders.size());
                colliders.add(collider.withRadius(shrunk));
            } else {
                colliders.set(ci, collider.withRadius(shrunk));
            }
            notes.add(note.toString());
        }
        for (var c : hemChains) {
            var list = chains.get(c).colliders();
            var chain = SpringChainProto.newBuilder().setCenter(chains.get(c).center());
            for (var i = 0; i < list.size(); i++) {
                chain.addColliders(copies.getOrDefault(list.getInt(i), list.getInt(i)));
            }
            chains.set(c, chain.build());
        }
        if (legHems.isEmpty()) {
            return 0;
        }
        var added = 0;
        for (var capsule : capsules) {
            if (!HEM_COLLIDERS.contains(capsule.name)) {
                continue;
            }
            var radius = hemRadius(capsule, legJoints, joints, hitRadius, pivots, rest, thigh);
            if (radius < MIN_COLLIDER_SHARE * capsule.minRadius()) {
                notes.add(capsule.name + " left out");
                continue;
            }
            var ci = colliders.size();
            colliders.add(SpringColliderProto.newBuilder().setBone(capsule.bone)
                .addHead(capsule.a.x, capsule.a.y, capsule.a.z).addTail(capsule.b.x, capsule.b.y, capsule.b.z)
                .setRadius(radius).build());
            for (var c : legChains) {
                chains.set(c, chains.get(c).toBuilder().addColliders(ci).build());
            }
            added++;
        }
        return added;
    }

    /** A proxy capsule's radius as a hem collider: its mean radius (legs {@link #HEM_LEG_MARGIN} fuller), clear. */
    private static float hemRadius(Capsule capsule, List<Integer> hemJoints, List<SpringJointProto> joints,
        float[] hitRadius, Vector3f[] pivots, Matrix4f[] rest, float thigh) {
        var margin = capsule.name.startsWith("torso") ? 0f : HEM_LEG_MARGIN * thigh;
        return clearRadius(capsule.bone, capsule.a, capsule.b, 0.5f * (capsule.r0 + capsule.r1) + margin, hemJoints,
            joints, hitRadius, pivots, rest, thigh);
    }

    /** The joint's tail's and bone's least distance from a collider's axis over the bind and the default pose. */
    private static float restDistance(int bone, Vector3f head, Vector3f tail, SpringJointProto joint,
        Vector3f[] pivots, Matrix4f[] rest) {
        var distance = Float.POSITIVE_INFINITY;
        for (var pose = 0; pose < 2; pose++) {
            var colliderPose = pose == 0 ? IDENTITY : rest[bone];
            var a = colliderPose.transformPosition(new Vector3f(head));
            var b = colliderPose.transformPosition(new Vector3f(tail));
            var m = pose == 0 ? IDENTITY : rest[joint.bone()];
            var jointHead = m.transformPosition(new Vector3f(pivots[joint.bone()]));
            var jointTail = m.transformPosition(tail(joint));
            distance = Math.min(distance, segmentDistance(new Vector3f(jointHead).lerp(jointTail, 0.05f), jointTail,
                a, b));
        }
        return distance;
    }

    /**
     * Leg sync for hem joints (see the class comment): per joint the thigh or calf capsule (index + 1) its rest tail
     * is nearest within {@link #SYNC_REACH} thighs of, and its share. Returns the synced joints.
     */
    private static int sync(List<Hem> hems, List<Capsule> capsules, List<SpringJointProto> joints, float thigh,
        int[] syncs, float[] shares) {
        var count = 0;
        var reach = SYNC_REACH * thigh;
        for (var hem : hems) {
            for (var path : hem.paths()) {
                for (var level = 0; level < path.length; level++) {
                    var tail = tail(joints.get(path[level]));
                    var best = -1;
                    var bestDistance = reach;
                    for (var k = 0; k < capsules.size(); k++) {
                        var name = capsules.get(k).name;
                        if (!name.startsWith("thigh") && !name.startsWith("calf")) {
                            continue;
                        }
                        var distance = -capsules.get(k).depth(IDENTITY, tail);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = k;
                        }
                    }
                    if (best < 0) {
                        continue;
                    }
                    syncs[path[level]] = best + 1;
                    shares[path[level]] = SYNC_SHARE * (1f - Math.max(bestDistance, 0f) / reach)
                        * (float)Math.pow(SYNC_LEVEL_FALLOFF, level);
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Links between neighbouring hem chains, level by level: their joints' rest tail distance and, with points along
     * them, how deep those points may come into either chain's outside colliders: their deepest rest depth (hit
     * radius blended between the two joints') over the bind and the default pose, plus REST_GAP, so the rest pose is
     * kept.
     */
    private static List<SpringLinkProto> links(List<Hem> hems, List<SpringJointProto> joints,
        List<SpringColliderProto> colliders, List<SpringChainProto> chains, float[] hitRadius, Matrix4f[] rest,
        int points, float thigh) {
        var out = new ArrayList<SpringLinkProto>();
        for (var hem : hems) {
            var n = hem.paths().size();
            for (var i = 0; i < (hem.ring() ? n : n - 1); i++) {
                var p = hem.paths().get(i);
                var q = hem.paths().get((i + 1) % n);
                for (var level = 0; level < Math.min(p.length, q.length); level++) {
                    var a = joints.get(p[level]);
                    var b = joints.get(q[level]);
                    var distance = tail(a).distance(tail(b));
                    if (!(distance > 1e-4f)) {
                        continue;
                    }
                    var hitA = hitRadius[p[level]] >= 0f ? hitRadius[p[level]] : a.hitRadius();
                    var hitB = hitRadius[q[level]] >= 0f ? hitRadius[q[level]] : b.hitRadius();
                    var tested = new java.util.TreeSet<Integer>();
                    for (var chain : new int[] {a.chain(), b.chain()}) {
                        var list = chains.get(chain).colliders();
                        for (var k = 0; k < list.size(); k++) {
                            tested.add(list.getInt(k));
                        }
                    }
                    var allowance = 0f;
                    for (var pose = 0; pose < 2 && points > 0; pose++) {
                        var tailA = (pose == 0 ? IDENTITY : rest[a.bone()]).transformPosition(tail(a));
                        var tailB = (pose == 0 ? IDENTITY : rest[b.bone()]).transformPosition(tail(b));
                        for (var ci : tested) {
                            var collider = colliders.get(ci);
                            if (collider.type() != 0) {
                                continue;
                            }
                            var m = pose == 0 ? IDENTITY : rest[collider.bone()];
                            var head = m.transformPosition(point(collider.head()));
                            var end = m.transformPosition(point(collider.tail()));
                            for (var k = 1; k <= points; k++) {
                                var t = (float)k / (points + 1);
                                var on = new Vector3f(tailA).lerp(tailB, t);
                                var depth = collider.radius() + hitA + (hitB - hitA) * t
                                    - distanceToSegment(on, head, end);
                                allowance = Math.max(allowance, depth + REST_GAP * thigh);
                            }
                        }
                    }
                    out.add(SpringLinkProto.newBuilder().setA(a.bone()).setB(b.bone()).setRest(distance)
                        .setAllowance(allowance).build());
                }
            }
        }
        return out;
    }

    /**
     * Smooth push for hem garments (see the class comment) on top of the per-vertex push (share, allowance, masks):
     * returns the garment vertices; margin (out) per vertex.
     */
    private static int smoothPush(List<Hem> hems, int[] owner, List<Capsule> capsules, float[] positions, int[] joints,
        float[] weights, int[] triangles, Matrix4f[] rest, float thigh, float[] share, float[] allowance, int[] masks,
        float[] margin) {
        var vertexCount = positions.length / 3;
        var hemChain = new boolean[owner.length];
        var inHem = new java.util.HashSet<Integer>();
        hems.forEach(hem -> inHem.addAll(hem.chains()));
        for (var b = 0; b < owner.length; b++) {
            hemChain[b] = owner[b] >= 0 && inHem.contains(owner[b]);
        }
        // Garment vertices, welded by position: copies of one point share one value.
        var garment = new boolean[vertexCount];
        var weld = new int[vertexCount];
        var keys = new HashMap<Long, Integer>();
        var count = 0;
        for (var v = 0; v < vertexCount; v++) {
            var w = 0f;
            for (var k = 0; k < 4; k++) {
                var bone = joints[v * 4 + k];
                if (bone >= 0 && bone < owner.length && hemChain[bone]) {
                    w += weights[v * 4 + k];
                }
            }
            garment[v] = w >= GARMENT_WEIGHT;
            count += garment[v] ? 1 : 0;
            var key = (Math.round(positions[v * 3] * 2e4f) & 0x1FFFFFL) << 42
                | (Math.round(positions[v * 3 + 1] * 2e4f) & 0x1FFFFFL) << 21
                | (Math.round(positions[v * 3 + 2] * 2e4f) & 0x1FFFFFL);
            weld[v] = keys.computeIfAbsent(key, k -> keys.size());
        }
        if (count == 0) {
            return 0;
        }
        var points = keys.size();
        // Neighbours of welded points along triangle edges touching the garment.
        var edges = new it.unimi.dsi.fastutil.longs.LongArrayList();
        var garmentPoint = new boolean[points];
        for (var v = 0; v < vertexCount; v++) {
            garmentPoint[weld[v]] |= garment[v];
        }
        for (var t = 0; t + 2 < triangles.length; t += 3) {
            if (!garment[triangles[t]] && !garment[triangles[t + 1]] && !garment[triangles[t + 2]]) {
                continue;
            }
            for (var e = 0; e < 3; e++) {
                var a = weld[triangles[t + e]];
                var b = weld[triangles[t + (e + 1) % 3]];
                if (a != b) {
                    edges.add((long)a << 32 | b);
                    edges.add((long)b << 32 | a);
                }
            }
        }
        edges.sort(null);
        var start = new int[points + 1];
        var neighbour = new int[edges.size()];
        var unique = 0;
        for (var i = 0; i < edges.size(); i++) {
            if (i > 0 && edges.getLong(i) == edges.getLong(i - 1)) {
                continue;
            }
            start[(int)(edges.getLong(i) >>> 32) + 1]++;
            neighbour[unique++] = (int)edges.getLong(i);
        }
        for (var p = 0; p < points; p++) {
            start[p + 1] += start[p];
        }
        // Shares: whole on the garment, kept elsewhere, then averaged with the neighbours on garment points.
        var value = new float[points];
        var masked = new int[points];
        for (var v = 0; v < vertexCount; v++) {
            var p = weld[v];
            value[p] = Math.max(value[p], garment[v] ? 1f : masks[v] != 0 ? share[v] : 0f);
            masked[p] |= masks[v];
        }
        var next = new float[points];
        for (var pass = 0; pass < PUSH_SMOOTH_PASSES; pass++) {
            for (var p = 0; p < points; p++) {
                if (!garmentPoint[p]) {
                    next[p] = value[p];
                    continue;
                }
                var sum = value[p];
                for (var i = start[p]; i < start[p + 1]; i++) {
                    sum += value[neighbour[i]];
                }
                next[p] = sum / (1 + start[p + 1] - start[p]);
            }
            System.arraycopy(next, 0, value, 0, points);
        }
        // Capsules: those of the garment points within two edges.
        var mask = new int[points];
        for (var p = 0; p < points; p++) {
            mask[p] = garmentPoint[p] ? masked[p] : 0;
        }
        for (var pass = 0; pass < 2; pass++) {
            var grown = mask.clone();
            for (var p = 0; p < points; p++) {
                if (!garmentPoint[p]) {
                    continue;
                }
                for (var i = start[p]; i < start[p + 1]; i++) {
                    grown[p] |= garmentPoint[neighbour[i]] ? mask[neighbour[i]] : 0;
                }
            }
            mask = grown;
        }
        // Garment vertices: the smoothed share, the grown mask less the capsules the vertex is deep in at rest, the
        // allowance over that mask, the margin.
        var bind = new Vector3f();
        var posed = new Vector3f();
        var ramp = PUSH_MARGIN * thigh;
        for (var v = 0; v < vertexCount; v++) {
            if (!garment[v]) {
                continue;
            }
            restPositions(v, positions, joints, weights, rest, bind, posed);
            var bits = 0;
            var deepest = 0f;
            for (var k = 0; k < capsules.size(); k++) {
                if ((mask[weld[v]] & (1 << k)) == 0) {
                    continue;
                }
                var capsule = capsules.get(k);
                var depth = Math.max(capsule.depth(IDENTITY, bind), capsule.depth(rest[capsule.bone], posed));
                if (depth > DEEP_SHARE * capsule.maxRadius()) {
                    continue;
                }
                bits |= 1 << k;
                deepest = Math.max(deepest, depth);
            }
            masks[v] = bits;
            share[v] = bits != 0 ? Math.min(value[weld[v]], 1f) : 0f;
            allowance[v] = bits != 0 ? deepest + PUSH_EPSILON : 0f;
            margin[v] = bits != 0 ? ramp : 0f;
        }
        return count;
    }

    private static final Matrix4f IDENTITY = new Matrix4f();

    /** Bone matrices of the default pose: T(pivot) Rz Ry Rx T(-pivot), parent first, as the native pose stack does. */
    private static Matrix4f[] restPose(Skeleton skeleton, Vector3f[] pivots) {
        var count = pivots.length;
        var out = new Matrix4f[count];
        // Parents may come after children in the list: resolve recursively.
        for (var b = 0; b < count; b++) {
            restPose(b, skeleton, pivots, out);
        }
        return out;
    }

    private static Matrix4f restPose(int bone, Skeleton skeleton, Vector3f[] pivots, Matrix4f[] out) {
        if (out[bone] != null) {
            return out[bone];
        }
        var parent = skeleton.parents[bone];
        var base = parent >= 0 ? new Matrix4f(restPose(parent, skeleton, pivots, out)) : new Matrix4f();
        var p = pivots[bone];
        out[bone] = base.translate(p).rotateZ(skeleton.rotations[bone * 3 + 2])
            .rotateY(skeleton.rotations[bone * 3 + 1]).rotateX(skeleton.rotations[bone * 3])
            .translate(-p.x, -p.y, -p.z);
        return out[bone];
    }

    private static List<Capsule> fit(Skeleton skeleton, Map<String, Integer> index, Vector3f[] pivots, String[] segment,
        float[] positions, float[] springWeight, int[] dominant, boolean[] hull, float thigh) {
        var vertexCount = positions.length / 3;
        var bySegment = new HashMap<String, List<Vector3f>>();
        var torsoBones = new ArrayList<Integer>();
        for (var v = 0; v < vertexCount; v++) {
            if (springWeight[v] >= BODY_WEIGHT || hull[v]) {
                continue;
            }
            var name = segment[dominant[v]];
            if (name == null) {
                continue;
            }
            var point = new Vector3f(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]);
            bySegment.computeIfAbsent(name, n -> new ArrayList<>()).add(point);
            if (name.equals("torso")) {
                torsoBones.add(trunkBone(dominant[v], skeleton, index));
            }
        }
        var capsules = new ArrayList<Capsule>();
        for (var side : new String[] {"Left", "Right"}) {
            var s = side.equals("Left") ? "_l" : "_r";
            limb(capsules, bySegment, "upperarm" + s, index.get(side + "Arm"), pivots[index.get(side + "Arm")],
                pivots[index.get(side + "ForeArm")], LIMB_PERCENTILE);
            limb(capsules, bySegment, "forearm" + s, index.get(side + "ForeArm"), pivots[index.get(side + "ForeArm")],
                pivots[index.get(side + "Hand")], LIMB_PERCENTILE);
            limb(capsules, bySegment, "thigh" + s, index.get(side + "Leg"), pivots[index.get(side + "Leg")],
                pivots[index.get(side + "LowerLeg")], LIMB_PERCENTILE);
            limb(capsules, bySegment, "calf" + s, index.get(side + "LowerLeg"), pivots[index.get(side + "LowerLeg")],
                pivots[index.get(side + "Foot")], LIMB_PERCENTILE);
        }
        limb(capsules, bySegment, "neck", index.get("AllHead"), pivots[index.get("AllHead")], pivots[index.get("Head")],
            NECK_PERCENTILE);
        var head = bySegment.getOrDefault("head", List.of());
        if (head.size() >= MIN_FIT_VERTICES) {
            var lo = quantile(head, p -> p.y, 0.02f);
            var hi = quantile(head, p -> p.y, 0.98f);
            var cx = quantile(head, p -> p.x, 0.5f);
            var cz = quantile(head, p -> p.z, 0.5f);
            var across = 0.5f * Math.min(quantile(head, p -> p.x, 0.98f) - quantile(head, p -> p.x, 0.02f),
                quantile(head, p -> p.z, 0.98f) - quantile(head, p -> p.z, 0.02f));
            var mid = 0.5f * (lo + hi);
            var half = Math.max(0.5f * (hi - lo) - across, 0f);
            var a = new Vector3f(cx, mid - half, cz);
            var b = new Vector3f(cx, mid + half, cz);
            var r = quantile(head, p -> distanceToSegment(p, a, b), HEAD_PERCENTILE);
            capsules.add(new Capsule("head", index.get("Head"), a, b, r, r));
        }
        var torso = bySegment.getOrDefault("torso", List.of());
        var y0 = pivots[index.get("LeftLeg")].y - 0.1f * thigh;
        var y1 = pivots[index.get("LeftArm")].y;
        for (var k = 0; k < TORSO_BANDS && y1 > y0; k++) {
            var low = y0 + (y1 - y0) * k / TORSO_BANDS;
            var high = y0 + (y1 - y0) * (k + 1) / TORSO_BANDS;
            var band = new ArrayList<Vector3f>();
            var votes = new HashMap<Integer, Integer>();
            for (var i = 0; i < torso.size(); i++) {
                var p = torso.get(i);
                if (p.y >= low && p.y < high) {
                    band.add(p);
                    votes.merge(torsoBones.get(i), 1, Integer::sum);
                }
            }
            if (band.size() < MIN_FIT_VERTICES) {
                continue;
            }
            var cy = 0.5f * (low + high);
            var cx = quantile(band, p -> p.x, 0.5f);
            var z0 = quantile(band, p -> p.z, 0.05f);
            var z1 = quantile(band, p -> p.z, 0.95f);
            var depth = 0.5f * (z1 - z0);
            var width = 0.5f * (quantile(band, p -> p.x, 0.95f) - quantile(band, p -> p.x, 0.05f));
            var half = Math.max(width - depth, 0f);
            var cz = 0.5f * (z0 + z1);
            var a = new Vector3f(cx - half, cy, cz);
            var b = new Vector3f(cx + half, cy, cz);
            var r = quantile(band, p -> distanceToSegment(p, a, b), TORSO_PERCENTILE);
            var bone = votes.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            capsules.add(new Capsule("torso" + k, bone, a, b, r, r));
        }
        return capsules.size() > 32 ? capsules.subList(0, 32) : capsules;
    }

    /** The trunk bone (an ancestor of the shoulder girdle, or the lower body) a torso vertex's bone hangs from. */
    private static int trunkBone(int bone, Skeleton skeleton, Map<String, Integer> index) {
        var trunk = new java.util.HashSet<Integer>();
        var girdle = index.getOrDefault("Arm", index.get("LeftArm"));
        for (var a = girdle; a >= 0; a = skeleton.parents[a]) {
            trunk.add(a);
        }
        if (index.containsKey("DownBody")) {
            trunk.add(index.get("DownBody"));
        }
        for (var a = bone; a >= 0; a = skeleton.parents[a]) {
            if (trunk.contains(a)) {
                return a;
            }
        }
        return index.get("AllBody");
    }

    private static void limb(List<Capsule> out, Map<String, List<Vector3f>> bySegment, String name, int bone,
        Vector3f a, Vector3f b, float percentile) {
        var points = bySegment.getOrDefault(name, List.of());
        var axis = new Vector3f(b).sub(a);
        var squared = axis.lengthSquared();
        if (points.size() < MIN_FIT_VERTICES || !(squared > 1e-12f)) {
            return;
        }
        var near = new ArrayList<Float>();
        var far = new ArrayList<Float>();
        for (var p : points) {
            var t = new Vector3f(p).sub(a).dot(axis) / squared;
            if (t <= 0.02f || t >= 0.98f) {
                continue;
            }
            var r = new Vector3f(a).fma(t, axis).distance(p);
            (t < 0.5f ? near : far).add(r);
        }
        if (near.size() + far.size() < MIN_FIT_VERTICES) {
            return;
        }
        var all = new ArrayList<Float>(near);
        all.addAll(far);
        var r0 = near.size() >= MIN_FIT_VERTICES / 2 ? percentile(near, percentile) : percentile(all, percentile);
        var r1 = far.size() >= MIN_FIT_VERTICES / 2 ? percentile(far, percentile) : percentile(all, percentile);
        out.add(new Capsule(name, bone, new Vector3f(a), new Vector3f(b), r0, r1));
    }

    /** Hair: the chain's root hangs from the head or the neck. */
    private static boolean isHair(List<Integer> members, List<SpringJointProto> joints,
        Skeleton skeleton, String[] segment, int[] jointOfBone) {
        for (var j : members) {
            var parent = skeleton.parents[joints.get(j).bone()];
            if (parent >= 0 && jointOfBone[parent] < 0) {
                var s = segment[parent];
                return "head".equals(s) || "neck".equals(s);
            }
        }
        return false;
    }

    /** Per bone: the body segment ({@link #SEGMENTS}) of its nearest named ancestor, itself included; else null. */
    private static String[] segments(Skeleton skeleton) {
        var segment = new String[skeleton.names.length];
        for (var b = 0; b < segment.length; b++) {
            for (var a = b; a >= 0 && segment[b] == null; a = skeleton.parents[a]) {
                segment[b] = SEGMENTS.get(skeleton.names[a]);
            }
        }
        return segment;
    }

    /** Per spring chain: whether it is hair ({@link #isHair}: the chains that get the hair colliders). */
    static boolean[] hairChains(SpringBonesProto springs, Skeleton skeleton) {
        var jointList = springs.joints();
        var jointOfBone = new int[skeleton.names.length];
        Arrays.fill(jointOfBone, -1);
        var members = new ArrayList<List<Integer>>();
        for (var c = 0; c < springs.chains().size(); c++) {
            members.add(new ArrayList<>());
        }
        for (var j = 0; j < jointList.size(); j++) {
            jointOfBone[jointList.get(j).bone()] = j;
            members.get(jointList.get(j).chain()).add(j);
        }
        var segment = segments(skeleton);
        var hair = new boolean[members.size()];
        for (var c = 0; c < hair.length; c++) {
            hair[c] = isHair(members.get(c), jointList, skeleton, segment, jointOfBone);
        }
        return hair;
    }

    /**
     * The radius, at most radius, of a collider on bone from head to tail that keeps REST_GAP plus each joint's hit
     * radius (hitRadius[j] where not negative, else the joint's own; hitRadius may be null) clear of its rest tail,
     * and of its bone where the native solver tests the bone (pivot outside the collider), in the bind and the default
     * pose; negative when even a point collider would reach a joint.
     */
    private static float clearRadius(int bone, Vector3f head, Vector3f tail, float radius, List<Integer> members,
        List<SpringJointProto> joints, float[] hitRadius, Vector3f[] pivots, Matrix4f[] rest, float thigh) {
        var gap = REST_GAP * thigh;
        for (var pose = 0; pose < 2; pose++) {
            var capsulePose = pose == 0 ? IDENTITY : rest[bone];
            var a = capsulePose.transformPosition(new Vector3f(head));
            var b = capsulePose.transformPosition(new Vector3f(tail));
            for (var iteration = 0; iteration < 3; iteration++) {
                var before = radius;
                for (var j : members) {
                    var joint = joints.get(j);
                    var m = pose == 0 ? IDENTITY : rest[joint.bone()];
                    var jointHead = m.transformPosition(new Vector3f(pivots[joint.bone()]));
                    var jointTail = m.transformPosition(tail(joint));
                    var hit = hitRadius != null && hitRadius[j] >= 0f ? hitRadius[j] : joint.hitRadius();
                    radius = Math.min(radius, distanceToSegment(jointTail, a, b) - hit - gap);
                    if (distanceToSegment(jointHead, a, b) >= radius + hit) {
                        var from = new Vector3f(jointHead).lerp(jointTail, 0.05f);
                        radius = Math.min(radius, segmentDistance(from, jointTail, a, b) - hit - gap);
                    }
                }
                if (radius == before) {
                    break;
                }
            }
        }
        return radius;
    }

    private static Vector3f point(it.unimi.dsi.fastutil.floats.FloatList xyz) {
        return new Vector3f(xyz.getFloat(0), xyz.getFloat(1), xyz.getFloat(2));
    }

    private static Vector3f rootOf(int bone, Skeleton skeleton, int[] jointOfBone,
        List<SpringJointProto> joints, Vector3f[] rootPivot) {
        for (var a = bone; a >= 0; a = skeleton.parents[a]) {
            if (jointOfBone[a] >= 0) {
                return rootPivot[jointOfBone[a]];
            }
        }
        return new Vector3f();
    }

    private static boolean isAncestor(Skeleton skeleton, int ancestor, int bone) {
        for (var a = bone; a >= 0; a = skeleton.parents[a]) {
            if (a == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static Vector3f tail(SpringJointProto joint) {
        return new Vector3f(joint.tail().getFloat(0), joint.tail().getFloat(1), joint.tail().getFloat(2));
    }

    private interface Measure {
        float of(Vector3f p);
    }

    private static float quantile(List<Vector3f> points, Measure measure, float q) {
        var values = new float[points.size()];
        for (var i = 0; i < values.length; i++) {
            values[i] = measure.of(points.get(i));
        }
        Arrays.sort(values);
        return values[Math.min(values.length - 1, (int)(q * values.length))];
    }

    private static float percentile(List<Float> values, float q) {
        var sorted = new float[values.size()];
        for (var i = 0; i < sorted.length; i++) {
            sorted[i] = values.get(i);
        }
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int)(q * sorted.length))];
    }

    static float distanceToSegment(Vector3f p, Vector3f a, Vector3f b) {
        var ab = new Vector3f(b).sub(a);
        var squared = ab.lengthSquared();
        var t = squared > 1e-12f ? clamp(new Vector3f(p).sub(a).dot(ab) / squared) : 0f;
        return p.distance(new Vector3f(a).fma(t, ab));
    }

    /** Distance between segments [p0, p1] and [q0, q1]. */
    static float segmentDistance(Vector3f p0, Vector3f p1, Vector3f q0, Vector3f q1) {
        var d1 = new Vector3f(p1).sub(p0);
        var d2 = new Vector3f(q1).sub(q0);
        var r = new Vector3f(p0).sub(q0);
        var a = d1.dot(d1);
        var e = d2.dot(d2);
        var f = d2.dot(r);
        float s;
        float t;
        if (a <= 1e-12f && e <= 1e-12f) {
            return p0.distance(q0);
        }
        if (a <= 1e-12f) {
            s = 0f;
            t = clamp(f / e);
        } else {
            var c = d1.dot(r);
            if (e <= 1e-12f) {
                t = 0f;
                s = clamp(-c / a);
            } else {
                var b = d1.dot(d2);
                var denominator = a * e - b * b;
                s = denominator > 1e-12f ? clamp((b * f - c * e) / denominator) : 0f;
                t = (b * s + f) / e;
                if (t < 0f) {
                    t = 0f;
                    s = clamp(-c / a);
                } else if (t > 1f) {
                    t = 1f;
                    s = clamp((b - c) / a);
                }
            }
        }
        return new Vector3f(p0).fma(s, d1).distance(new Vector3f(q0).fma(t, d2));
    }

    private static float clamp(float value) {
        return Math.min(Math.max(value, 0f), 1f);
    }
}
