package com.elfmcys.ysm.format.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Reads VRM 0.x / 1.0 (binary glTF) into a {@link HumanoidSource}; glTF space is already meters and Y up. */
final class VrmReader {
    private final GlbDocument document;
    private final HumanoidSource source = new HumanoidSource();
    private final Matrix4f[] world;
    private final Matrix4f[] local;
    private final int nodeCount;

    private VrmReader(GlbDocument document) throws IOException {
        this.document = document;
        var nodes = document.array("nodes");
        nodeCount = nodes.size();
        var parent = new int[nodeCount];
        java.util.Arrays.fill(parent, -1);
        local = new Matrix4f[nodeCount];
        for (var i = 0; i < nodeCount; i++) {
            var node = nodes.get(i).getAsJsonObject();
            local[i] = localMatrix(node);
            if (node.has("children")) {
                for (var child : node.getAsJsonArray("children")) {
                    var index = child.getAsInt();
                    if (index < 0 || index >= nodeCount || parent[index] != -1 || index == i) {
                        throw new IOException("Invalid glTF node hierarchy");
                    }
                    parent[index] = i;
                }
            }
        }
        world = new Matrix4f[nodeCount];
        for (var i = 0; i < nodeCount; i++) {
            worldMatrix(i, parent, local, 0);
        }
        for (var i = 0; i < nodeCount; i++) {
            var node = nodes.get(i).getAsJsonObject();
            var name = node.has("name") ? node.get("name").getAsString() : "";
            var position = world[i].getTranslation(new Vector3f());
            source.addNode(name, parent[i], position.x, position.y, position.z);
        }
        applyConstraints(nodes, parent);
    }

    static HumanoidSource read(ByteBuffer glb) throws IOException {
        var reader = new VrmReader(GlbDocument.parse(glb));
        try {
            reader.readHumanoid();
            reader.readMetadata();
            reader.readExpressionBinds();
            reader.readMeshes();
            reader.readSprings();
            return reader.source;
        } catch (IOException | RuntimeException error) {
            reader.source.close();
            throw error;
        }
    }

    /**
     * VRMC_springBone (1.0): each spring lists its joints in order and every joint's tail is the next joint; colliders
     * may carry VRMC_springBone_extended_collider shapes (inside spheres and capsules, planes) and joints
     * VRMC_springBone_limit angle limits. VRM 0.x secondaryAnimation: every node below a group's root bones is a joint
     * whose tail is its first child, or 7 cm along the bone for leaves; collider offsets are stored with z negated.
     * Springs of both versions may name a center node whose space carries their inertia. Defaults follow three-vrm.
     */
    private void readSprings() throws IOException {
        var extensions = extensions();
        if (extensions.has("VRMC_springBone")) {
            var spring = extensions.getAsJsonObject("VRMC_springBone");
            var colliders = new ArrayList<Integer>();
            for (var element : array(spring, "colliders")) {
                var collider = element.getAsJsonObject();
                var node = nodeIndex(collider, "node");
                colliders.add(node < 0 ? -1 : addCollider(node, collider));
            }
            var groups = new ArrayList<int[]>();
            for (var element : array(spring, "colliderGroups")) {
                groups.add(array(element.getAsJsonObject(), "colliders").asList().stream()
                    .mapToInt(index -> index.getAsInt() >= 0 && index.getAsInt() < colliders.size()
                        ? colliders.get(index.getAsInt()) : -1)
                    .filter(index -> index >= 0).toArray());
            }
            for (var element : array(spring, "springs")) {
                var chainSpec = element.getAsJsonObject();
                var chain = addChain(groups, array(chainSpec, "colliderGroups"), nodeIndex(chainSpec, "center"));
                var joints = array(chainSpec, "joints");
                for (var i = 0; i + 1 < joints.size(); i++) {
                    var joint = joints.get(i).getAsJsonObject();
                    var node = nodeIndex(joint, "node");
                    var tailNode = nodeIndex(joints.get(i + 1).getAsJsonObject(), "node");
                    if (node < 0 || tailNode < 0) {
                        continue;
                    }
                    var gravity = floats(joint, "gravityDir", 0f, -1f, 0f);
                    var tail = world[tailNode].getTranslation(new Vector3f());
                    addJoint(node, tail, number(joint, "hitRadius", 0f), number(joint, "stiffness", 1f),
                        number(joint, "dragForce", 0.4f), number(joint, "gravityPower", 0f), new Vector3f(gravity),
                        chain, limit(joint, node, tail));
                }
            }
        } else if (extensions.has("VRM") && extensions.getAsJsonObject("VRM").has("secondaryAnimation")) {
            var secondary = extensions.getAsJsonObject("VRM").getAsJsonObject("secondaryAnimation");
            var groups = new ArrayList<int[]>();
            for (var element : array(secondary, "colliderGroups")) {
                var group = element.getAsJsonObject();
                var node = nodeIndex(group, "node");
                var members = new ArrayList<Integer>();
                for (var colliderElement : array(group, "colliders")) {
                    var collider = colliderElement.getAsJsonObject();
                    var offset = vrm0Vector(collider, "offset", 0f, 0f, 0f);
                    offset.z = -offset.z;
                    if (node >= 0) {
                        // a sphere: both ends are the one offset
                        members.add(addCollider(node, offset, offset, number(collider, "radius", 0f),
                            HumanoidSource.SpringCollider.OUTSIDE));
                    }
                }
                groups.add(members.stream().mapToInt(Integer::intValue).toArray());
            }
            var nodes = document.array("nodes");
            for (var element : array(secondary, "boneGroups")) {
                var group = element.getAsJsonObject();
                var chain = addChain(groups, array(group, "colliderGroups"), nodeIndex(group, "center"));
                var gravity = vrm0Vector(group, "gravityDir", 0f, -1f, 0f);
                var stack = new java.util.ArrayDeque<Integer>();
                for (var root : array(group, "bones")) {
                    if (root.getAsInt() >= 0 && root.getAsInt() < nodeCount) {
                        stack.push(root.getAsInt());
                    }
                }
                var visited = new boolean[nodeCount];
                while (!stack.isEmpty()) {
                    var node = stack.pop();
                    if (visited[node]) {
                        continue;
                    }
                    visited[node] = true;
                    var children = array(nodes.get(node).getAsJsonObject(), "children");
                    Vector3f tail;
                    if (!children.isEmpty()) {
                        tail = world[children.get(0).getAsInt()].getTranslation(new Vector3f());
                    } else {
                        var bone = local[node].getTranslation(new Vector3f());
                        if (bone.lengthSquared() < 1e-12f) {
                            continue;
                        }
                        tail = world[node].transformPosition(bone.normalize(0.07f));
                    }
                    addJoint(node, tail, number(group, "hitRadius", 0f), number(group, "stiffiness", 1f),
                        number(group, "dragForce", 0.4f), number(group, "gravityPower", 0f), new Vector3f(gravity),
                        chain, null);
                    for (var child : children) {
                        stack.push(child.getAsInt());
                    }
                }
            }
        }
    }

    /**
     * A VRMC_springBone collider; a VRMC_springBone_extended_collider shape replaces its own (the base shape is then
     * the exporter's fallback). -1 when neither defines a known shape.
     */
    private int addCollider(int node, JsonObject collider) {
        var shape = object(collider, "shape");
        var extended = object(object(object(collider, "extensions"), "VRMC_springBone_extended_collider"), "shape");
        if (extended.has("sphere") || extended.has("capsule") || extended.has("plane")) {
            shape = extended;
        }
        if (shape.has("sphere") || shape.has("capsule")) {
            var body = shape.has("sphere") ? object(shape, "sphere") : object(shape, "capsule");
            var offset = new Vector3f(floats(body, "offset", 0f, 0f, 0f));
            var tail = shape.has("sphere") ? offset : new Vector3f(floats(body, "tail", 0f, 0f, 0f));
            var inside = body.has("inside") && body.get("inside").isJsonPrimitive()
                && body.getAsJsonPrimitive("inside").isBoolean() && body.get("inside").getAsBoolean();
            return addCollider(node, offset, tail, number(body, "radius", 0f),
                inside ? HumanoidSource.SpringCollider.INSIDE : HumanoidSource.SpringCollider.OUTSIDE);
        }
        if (shape.has("plane")) {
            var body = object(shape, "plane");
            var offset = new Vector3f(floats(body, "offset", 0f, 0f, 0f));
            var normal = new Vector3f(floats(body, "normal", 0f, 0f, 1f));
            if (!(normal.lengthSquared() > 1e-12f)) {
                return -1;
            }
            return addCollider(node, offset, normal.normalize().add(offset), 0f, HumanoidSource.SpringCollider.PLANE);
        }
        return -1;
    }

    private int addCollider(int node, Vector3f head, Vector3f tail, float radius, int type) {
        source.springColliders.add(collider(world[node], node, head, tail, radius, type));
        return source.springColliders.size() - 1;
    }

    /**
     * A collider whose ends are given in its node's space, moved to rest space by the node's world transform. Each end
     * is transformed once, into a new vector: VRM 0.x passes the same offset vector for both ends of a sphere.
     */
    static HumanoidSource.SpringCollider collider(Matrix4f nodeWorld, int node, Vector3f head, Vector3f tail,
        float radius, int type) {
        return new HumanoidSource.SpringCollider(node, nodeWorld.transformPosition(head, new Vector3f()),
            nodeWorld.transformPosition(tail, new Vector3f()), Math.max(radius, 0f), type);
    }

    /**
     * A joint's VRMC_springBone_limit in rest space, or null. The limit space is the node's rest rotation, turned the
     * shortest way so its +Y points along the bone toward the tail (180° about X when the bone points along -Y), then
     * by the limit's own rotation. Angles are clamped to the ranges the extension defines.
     */
    @org.jetbrains.annotations.Nullable
    private HumanoidSource.SpringLimit limit(JsonObject joint, int node, Vector3f tail) {
        var limit = object(object(object(joint, "extensions"), "VRMC_springBone_limit"), "limit");
        int type;
        JsonObject body;
        if (limit.has("cone")) {
            type = HumanoidSource.SpringLimit.CONE;
            body = object(limit, "cone");
        } else if (limit.has("hinge")) {
            type = HumanoidSource.SpringLimit.HINGE;
            body = object(limit, "hinge");
        } else if (limit.has("spherical")) {
            type = HumanoidSource.SpringLimit.SPHERICAL;
            body = object(limit, "spherical");
        } else {
            return null;
        }
        var pi = (float)Math.PI;
        var spherical = type == HumanoidSource.SpringLimit.SPHERICAL;
        var angle = Math.min(Math.max(number(body, spherical ? "pitch" : "angle", 0f), 0f), pi);
        var yaw = spherical ? Math.min(Math.max(number(body, "yaw", 0f), 0f), 0.5f * pi) : 0f;
        var values = array(body, "rotation");
        var rotation = new Quaternionf();
        if (values.size() == 4) {
            var q = new Quaternionf(values.get(0).getAsFloat(), values.get(1).getAsFloat(),
                values.get(2).getAsFloat(), values.get(3).getAsFloat());
            if (Float.isFinite(q.x) && Float.isFinite(q.y) && Float.isFinite(q.z) && Float.isFinite(q.w)
                && q.lengthSquared() > 1e-12f) {
                rotation.set(q).normalize();
            }
        }
        var nodeRotation = world[node].getNormalizedRotation(new Quaternionf());
        var boneAxis = nodeRotation.transformInverse(tail.sub(world[node].getTranslation(new Vector3f()),
            new Vector3f()));
        if (!(boneAxis.lengthSquared() > 1e-12f)) {
            return null;
        }
        boneAxis.normalize();
        var axisRotation = boneAxis.y < -1f + 1e-6f ? new Quaternionf(1f, 0f, 0f, 0f)
            : new Quaternionf(boneAxis.z, 0f, -boneAxis.x, boneAxis.y + 1f).normalize();
        return new HumanoidSource.SpringLimit(type, nodeRotation.mul(axisRotation).mul(rotation).normalize(), angle,
            yaw);
    }

    private int addChain(List<int[]> groups, JsonArray groupIndices, int center) {
        var colliders = new java.util.TreeSet<Integer>();
        for (var index : groupIndices) {
            if (index.getAsInt() >= 0 && index.getAsInt() < groups.size()) {
                for (var collider : groups.get(index.getAsInt())) {
                    colliders.add(collider);
                }
            }
        }
        source.springChains.add(
            new HumanoidSource.SpringChain(colliders.stream().mapToInt(Integer::intValue).toArray(), center));
        return source.springChains.size() - 1;
    }

    private void addJoint(int node, Vector3f tail, float hitRadius, float stiffness, float drag, float gravityPower,
        Vector3f gravity, int chain, @org.jetbrains.annotations.Nullable HumanoidSource.SpringLimit limit) {
        // Joints shared between springs keep their first definition.
        for (var existing : source.springJoints) {
            if (existing.node() == node) {
                return;
            }
        }
        // Negative gravity power is a reversed direction.
        if (gravityPower < 0f) {
            gravity.negate();
            gravityPower = -gravityPower;
        }
        source.springJoints.add(new HumanoidSource.SpringJoint(node, tail, Math.max(hitRadius, 0f),
            Math.max(stiffness, 0f), Math.min(Math.max(drag, 0f), 1f), gravityPower, gravity, chain, limit));
    }

    /** A member object, or an empty one when it is missing or not an object. */
    private static JsonObject object(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonObject() ? object.getAsJsonObject(name) : new JsonObject();
    }

    private int nodeIndex(JsonObject object, String name) {
        if (!object.has(name)) {
            return -1;
        }
        var index = object.get(name).getAsInt();
        return index >= 0 && index < nodeCount ? index : -1;
    }

    private static JsonArray array(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : new JsonArray();
    }

    private static float number(JsonObject object, String name, float fallback) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()) {
            return fallback;
        }
        var value = object.get(name).getAsFloat();
        return Float.isFinite(value) ? value : fallback;
    }

    private static float[] floats(JsonObject object, String name, float x, float y, float z) {
        var values = array(object, name);
        if (values.size() != 3) {
            return new float[] {x, y, z};
        }
        var result = new float[] {values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat()};
        for (var value : result) {
            if (!Float.isFinite(value)) {
                return new float[] {x, y, z};
            }
        }
        return result;
    }

    /** VRM 0.x {x, y, z} object. */
    private static Vector3f vrm0Vector(JsonObject object, String name, float x, float y, float z) {
        if (!object.has(name) || !object.get(name).isJsonObject()) {
            return new Vector3f(x, y, z);
        }
        var value = object.getAsJsonObject(name);
        var result = new Vector3f(number(value, "x", 0f), number(value, "y", 0f), number(value, "z", 0f));
        return result.isFinite() ? result : new Vector3f(x, y, z);
    }

    private static Matrix4f localMatrix(JsonObject node) {
        if (node.has("matrix")) {
            var array = node.getAsJsonArray("matrix");
            var values = new float[16];
            for (var i = 0; i < 16; i++) {
                values[i] = array.get(i).getAsFloat();
            }
            return new Matrix4f().set(values);
        }
        var t = vector(node, "translation", 0);
        var s = vector(node, "scale", 1);
        var q = new Quaternionf();
        if (node.has("rotation")) {
            var r = node.getAsJsonArray("rotation");
            q.set(r.get(0).getAsFloat(), r.get(1).getAsFloat(), r.get(2).getAsFloat(), r.get(3).getAsFloat());
        }
        return new Matrix4f().translationRotateScale(t, q, s);
    }

    private static Vector3f vector(JsonObject node, String name, float fallback) {
        if (!node.has(name)) {
            return new Vector3f(fallback);
        }
        var array = node.getAsJsonArray(name);
        return new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
    }

    private Matrix4f worldMatrix(int index, int[] parent, Matrix4f[] local, int depth) throws IOException {
        if (world[index] != null) {
            return world[index];
        }
        if (depth > nodeCount) {
            throw new IOException("Cyclic glTF node hierarchy");
        }
        var result = parent[index] < 0 ? new Matrix4f(local[index])
            : new Matrix4f(worldMatrix(parent[index], parent, local, depth + 1)).mul(local[index]);
        world[index] = result;
        return result;
    }

    /**
     * VRMC_node_constraint nodes follow the bone that drives them in the YSM skeleton. Aim targets move with their
     * parent (an upper arm aiming at the lower arm follows the upper arm); rotation constraints follow their source;
     * roll keeps the glTF parent, which already follows.
     */
    private void applyConstraints(JsonArray nodes, int[] parent) {
        var redirected = parent.clone();
        for (var i = 0; i < nodeCount; i++) {
            var node = nodes.get(i).getAsJsonObject();
            if (!node.has("extensions") || !node.getAsJsonObject("extensions").has("VRMC_node_constraint")) {
                continue;
            }
            var constraint = node.getAsJsonObject("extensions").getAsJsonObject("VRMC_node_constraint")
                .getAsJsonObject("constraint");
            if (constraint == null) {
                continue;
            }
            var driver = -1;
            if (constraint.has("aim")) {
                var target = GlbDocument.intOr(constraint.getAsJsonObject("aim"), "source", -1);
                driver = target >= 0 && target < nodeCount ? parent[target] : -1;
            } else if (constraint.has("rotation")) {
                driver = GlbDocument.intOr(constraint.getAsJsonObject("rotation"), "source", -1);
            }
            if (driver >= 0 && driver < nodeCount && driver != i) {
                redirected[i] = driver;
            }
        }
        // Reject redirections that would form a cycle; those nodes keep their glTF parent.
        for (var i = 0; i < nodeCount; i++) {
            var steps = 0;
            for (var current = redirected[i]; current >= 0; current = redirected[current]) {
                if (current == i || ++steps > nodeCount) {
                    redirected[i] = parent[i];
                    break;
                }
            }
        }
        for (var i = 0; i < nodeCount; i++) {
            source.skeletonParents.set(i, redirected[i]);
        }
    }

    private JsonObject extensions() {
        return document.json.has("extensions") ? document.json.getAsJsonObject("extensions") : new JsonObject();
    }

    private void readHumanoid() throws IOException {
        var extensions = extensions();
        if (extensions.has("VRMC_vrm")) {
            var bones =
                extensions.getAsJsonObject("VRMC_vrm").getAsJsonObject("humanoid").getAsJsonObject("humanBones");
            for (var entry : bones.entrySet()) {
                source.humanoid.put(entry.getKey(),
                    GlbDocument.requiredInt(entry.getValue().getAsJsonObject(), "node"));
            }
        } else if (extensions.has("VRM")) {
            for (var element : extensions.getAsJsonObject("VRM").getAsJsonObject("humanoid")
                .getAsJsonArray("humanBones")) {
                var bone = element.getAsJsonObject();
                source.humanoid.put(bone.get("bone").getAsString(), GlbDocument.requiredInt(bone, "node"));
            }
        } else {
            throw new IOException("Not a VRM file: missing VRM or VRMC_vrm extension");
        }
        source.humanoid.values().removeIf(node -> node < 0 || node >= nodeCount);
    }

    private void readMetadata() {
        var extensions = extensions();
        var authors = new ArrayList<String>();
        if (extensions.has("VRMC_vrm")) {
            var meta = extensions.getAsJsonObject("VRMC_vrm").getAsJsonObject("meta");
            if (meta == null) {
                return;
            }
            if (meta.has("authors")) {
                meta.getAsJsonArray("authors").forEach(author -> authors.add(author.getAsString()));
            }
            source.metadata = new HumanoidSource.Metadata(string(meta, "name"), authors, string(meta, "licenseUrl"));
            return;
        }
        var meta = extensions.getAsJsonObject("VRM").getAsJsonObject("meta");
        if (meta == null) {
            return;
        }
        if (!string(meta, "author").isBlank()) {
            authors.add(string(meta, "author"));
        }
        source.metadata = new HumanoidSource.Metadata(string(meta, "title"), authors, string(meta, "licenseName"));
    }

    private static String string(JsonObject object, String name) {
        var value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private int material(int gltfMaterial, Map<Integer, Integer> materials, Map<Integer, HumanoidTexture> images)
        throws IOException {
        var existing = materials.get(gltfMaterial);
        if (existing != null) {
            return existing;
        }
        HumanoidSource.Material material;
        if (gltfMaterial < 0) {
            material = new HumanoidSource.Material("", null, 1, 1, 1, 1, HumanoidSource.OPAQUE, 0.5f, null);
        } else {
            var object = document.element("materials", gltfMaterial);
            var pbr =
                object.has("pbrMetallicRoughness") ? object.getAsJsonObject("pbrMetallicRoughness") : new JsonObject();
            var factor = new float[] {1, 1, 1, 1};
            if (pbr.has("baseColorFactor")) {
                var array = pbr.getAsJsonArray("baseColorFactor");
                for (var i = 0; i < 4 && i < array.size(); i++) {
                    factor[i] = array.get(i).getAsFloat();
                }
            }
            HumanoidTexture texture = null;
            if (pbr.has("baseColorTexture")) {
                var gltfTexture = document.element("textures",
                    GlbDocument.requiredInt(pbr.getAsJsonObject("baseColorTexture"), "index"));
                var image = GlbDocument.intOr(gltfTexture, "source", -1);
                if (image >= 0) {
                    texture = images.get(image);
                    if (texture == null) {
                        texture = image(image);
                        images.put(image, texture);
                    }
                }
            }
            var mode = object.has("alphaMode") ? object.get("alphaMode").getAsString() : "OPAQUE";
            var alphaMode = switch (mode) {
                case "MASK" -> HumanoidSource.MASK;
                case "BLEND" -> HumanoidSource.BLEND;
                default -> HumanoidSource.OPAQUE;
            };
            var name = string(object, "name");
            material = new HumanoidSource.Material(name, texture, factor[0], factor[1], factor[2], factor[3], alphaMode,
                GlbDocument.floatOr(object.get("alphaCutoff"), 0.5f), outline(object, name));
        }
        source.materials.add(material);
        materials.put(gltfMaterial, source.materials.size() - 1);
        return source.materials.size() - 1;
    }

    /**
     * The MToon outline of a material: VRMC_materials_mtoon on the material (VRM 1.0, width in meters) or the VRM 0.x
     * material property of the same name (width in centimeters). Screen-relative widths have no size in the world and
     * get the narrowest visible one.
     */
    @org.jetbrains.annotations.Nullable
    private HumanoidSource.Outline outline(JsonObject material, String name) {
        var extensions = material.has("extensions") ? material.getAsJsonObject("extensions") : new JsonObject();
        if (extensions.has("VRMC_materials_mtoon")) {
            var mtoon = extensions.getAsJsonObject("VRMC_materials_mtoon");
            var mode = string(mtoon, "outlineWidthMode");
            var width = GlbDocument.floatOr(mtoon.get("outlineWidthFactor"), 0f);
            if (mode.isEmpty() || mode.equals("none") || !(width > 0f)) {
                return null;
            }
            var color = vector(mtoon.get("outlineColorFactor"));
            return HumanoidSource.Outline.of(color[0], color[1], color[2],
                mode.equals("worldCoordinates") ? width : 0f);
        }
        if (!extensions().has("VRM") || !extensions().getAsJsonObject("VRM").has("materialProperties")) {
            return null;
        }
        for (var element : extensions().getAsJsonObject("VRM").getAsJsonArray("materialProperties")) {
            var properties = element.getAsJsonObject();
            if (!string(properties, "name").equals(name)) {
                continue;
            }
            var floats =
                properties.has("floatProperties") ? properties.getAsJsonObject("floatProperties") : new JsonObject();
            var mode = Math.round(GlbDocument.floatOr(floats.get("_OutlineWidthMode"), 0f));
            var width = GlbDocument.floatOr(floats.get("_OutlineWidth"), 0f);
            if (mode == 0 || !(width > 0f)) {
                return null;
            }
            var vectors =
                properties.has("vectorProperties") ? properties.getAsJsonObject("vectorProperties") : new JsonObject();
            var color = vector(vectors.get("_OutlineColor"));
            return HumanoidSource.Outline.of(color[0], color[1], color[2], mode == 1 ? width / 100f : 0f);
        }
        return null;
    }

    /** The first three numbers of a JSON array, black when absent or malformed. */
    private static float[] vector(@org.jetbrains.annotations.Nullable com.google.gson.JsonElement element) {
        var out = new float[3];
        if (element != null && element.isJsonArray()) {
            var array = element.getAsJsonArray();
            for (var i = 0; i < 3 && i < array.size(); i++) {
                var value = array.get(i);
                out[i] = value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsFloat() : 0f;
            }
        }
        return out;
    }

    private HumanoidTexture image(int index) throws IOException {
        var image = document.element("images", index);
        if (!image.has("bufferView")) {
            throw new IOException("VRM image " + index + " is not embedded in the GLB file");
        }
        return HumanoidTexture.encoded(document.bufferView(GlbDocument.requiredInt(image, "bufferView")));
    }

    private void readMeshes() throws IOException {
        var materials = new HashMap<Integer, Integer>();
        var images = new HashMap<Integer, HumanoidTexture>();
        var skins = new HashMap<Integer, Skin>();
        var nodes = document.array("nodes");
        for (var node = 0; node < nodeCount; node++) {
            var nodeObject = nodes.get(node).getAsJsonObject();
            if (!nodeObject.has("mesh")) {
                continue;
            }
            var skinIndex = GlbDocument.intOr(nodeObject, "skin", -1);
            Skin skin = null;
            if (skinIndex >= 0) {
                skin = skins.get(skinIndex);
                if (skin == null) {
                    skin = new Skin(skinIndex);
                    skins.put(skinIndex, skin);
                }
            }
            var meshIndex = nodeObject.get("mesh").getAsInt();
            var mesh = document.element("meshes", meshIndex);
            for (var element : mesh.getAsJsonArray("primitives")) {
                var primitive = element.getAsJsonObject();
                if (GlbDocument.intOr(primitive, "mode", 4) != 4) {
                    continue;
                }
                var material = material(GlbDocument.intOr(primitive, "material", -1), materials, images);
                readPrimitive(node, meshIndex, primitive, skin, material);
            }
        }
    }

    private void readPrimitive(int node, int meshIndex, JsonObject primitive, Skin skin, int material)
        throws IOException {
        var attributes = primitive.getAsJsonObject("attributes");
        var position = document.readFloats(GlbDocument.requiredInt(attributes, "POSITION"), 3);
        var count = position.length / 3;
        float[] normal =
            attributes.has("NORMAL") ? document.readFloats(GlbDocument.requiredInt(attributes, "NORMAL"), 3) : null;
        float[] uv = attributes.has("TEXCOORD_0")
            ? document.readFloats(GlbDocument.requiredInt(attributes, "TEXCOORD_0"), 2) : new float[count * 2];
        int[] indices;
        if (primitive.has("indices")) {
            indices = document.readInts(GlbDocument.requiredInt(primitive, "indices"), 1);
        } else {
            indices = new int[count];
            for (var i = 0; i < count; i++) {
                indices[i] = i;
            }
        }
        for (var index : indices) {
            if (index < 0 || index >= count) {
                throw new IOException("glTF primitive index out of range");
            }
        }
        if (normal == null || normal.length != count * 3 || uv.length != count * 2) {
            normal = faceNormals(position, indices);
        }
        int[] jointIds = null;
        float[] jointWeights = null;
        if (skin != null && attributes.has("JOINTS_0") && attributes.has("WEIGHTS_0")) {
            jointIds = document.readInts(GlbDocument.requiredInt(attributes, "JOINTS_0"), 4);
            jointWeights = document.readFloats(GlbDocument.requiredInt(attributes, "WEIGHTS_0"), 4);
            if (jointIds.length != count * 4 || jointWeights.length != count * 4) {
                throw new IOException("glTF skin attribute count mismatch");
            }
        }
        var rigid = world[node];
        var rigidNormal = rigid.normal(new Matrix3f());
        // Morph targets that expressions use: glTF offsets, then the same skinning as positions.
        var targets = morphTargets(meshIndex, primitive, count);
        var restTargets = new HashMap<Integer, float[]>();
        targets.keySet().forEach(target -> restTargets.put(target, new float[count * 3]));
        var delta = new Vector3f();
        var moved = new Vector3f();

        var base = source.vertexCount();
        var p = new Vector3f();
        var n = new Vector3f();
        var accumulated = new Vector3f();
        var accumulatedNormal = new Vector3f();
        for (var v = 0; v < count; v++) {
            var px = position[v * 3];
            var py = position[v * 3 + 1];
            var pz = position[v * 3 + 2];
            var nx = normal[v * 3];
            var ny = normal[v * 3 + 1];
            var nz = normal[v * 3 + 2];
            var weightSum = 0f;
            accumulated.zero();
            accumulatedNormal.zero();
            if (jointIds != null) {
                for (var k = 0; k < 4; k++) {
                    var weight = jointWeights[v * 4 + k];
                    if (weight <= 0f) {
                        continue;
                    }
                    var joint = jointIds[v * 4 + k];
                    if (joint >= skin.matrices.length) {
                        throw new IOException("glTF joint index out of range");
                    }
                    skin.matrices[joint].transformPosition(p.set(px, py, pz));
                    accumulated.fma(weight, p);
                    skin.normals[joint].transform(n.set(nx, ny, nz));
                    accumulatedNormal.fma(weight, n);
                    weightSum += weight;
                }
            }
            if (weightSum > 0f) {
                accumulated.div(weightSum);
                for (var k = 0; k < 4; k++) {
                    var weight = jointWeights[v * 4 + k];
                    source.joints.add(weight > 0f ? skin.nodes[jointIds[v * 4 + k]] : HumanoidSource.ROOT_JOINT);
                    source.weights.add(Math.max(weight, 0f) / weightSum);
                }
            } else {
                rigid.transformPosition(accumulated.set(px, py, pz));
                rigidNormal.transform(accumulatedNormal.set(nx, ny, nz));
                source.joints.add(node);
                source.weights.add(1f);
                for (var k = 1; k < 4; k++) {
                    source.joints.add(HumanoidSource.ROOT_JOINT);
                    source.weights.add(0f);
                }
            }
            if (accumulatedNormal.lengthSquared() > 0f) {
                accumulatedNormal.normalize();
            }
            source.positions.add(accumulated.x);
            source.positions.add(accumulated.y);
            source.positions.add(accumulated.z);
            source.normals.add(accumulatedNormal.x);
            source.normals.add(accumulatedNormal.y);
            source.normals.add(accumulatedNormal.z);
            source.uvs.add(uv[v * 2]);
            source.uvs.add(uv[v * 2 + 1]);
            for (var target : targets.entrySet()) {
                var offsets = target.getValue();
                delta.set(offsets[v * 3], offsets[v * 3 + 1], offsets[v * 3 + 2]);
                if (delta.lengthSquared() == 0f) {
                    continue;
                }
                if (weightSum > 0f) {
                    var blended = new Vector3f();
                    for (var k = 0; k < 4; k++) {
                        var weight = jointWeights[v * 4 + k];
                        if (weight > 0f) {
                            skin.matrices[jointIds[v * 4 + k]].transformDirection(moved.set(delta));
                            blended.fma(weight / weightSum, moved);
                        }
                    }
                    moved.set(blended);
                } else {
                    rigid.transformDirection(moved.set(delta));
                }
                var rest = restTargets.get(target.getKey());
                rest[v * 3] = moved.x;
                rest[v * 3 + 1] = moved.y;
                rest[v * 3 + 2] = moved.z;
            }
        }
        for (var i = 0; i + 2 < indices.length; i += 3) {
            source.triangles.add(base + indices[i]);
            source.triangles.add(base + indices[i + 1]);
            source.triangles.add(base + indices[i + 2]);
            source.triangleMaterials.add(material);
        }
        for (var entry : expressionBinds.entrySet()) {
            for (var bind : entry.getValue()) {
                var offsets = bind.mesh() == meshIndex ? restTargets.get(bind.target()) : null;
                if (offsets == null) {
                    continue;
                }
                var expression = source.expression(entry.getKey());
                for (var v = 0; v < count; v++) {
                    expression.add(base + v, offsets[v * 3] * bind.weight(), offsets[v * 3 + 1] * bind.weight(),
                        offsets[v * 3 + 2] * bind.weight());
                }
            }
        }
    }

    /** A morph target of a glTF mesh contributing to an expression with weight in [0, 1]. */
    private record Bind(int mesh, int target, float weight) {
    }

    /** Expression name to the morph targets it blends. */
    private final Map<String, List<Bind>> expressionBinds = new java.util.LinkedHashMap<>();

    /** VRM 0.x preset names to their VRM 1.0 equivalents. */
    private static final Map<String,
        String> VRM0_PRESETS = Map.ofEntries(Map.entry("a", "aa"), Map.entry("i", "ih"), Map.entry("u", "ou"),
            Map.entry("e", "ee"), Map.entry("o", "oh"), Map.entry("blink", "blink"), Map.entry("joy", "happy"),
            Map.entry("angry", "angry"), Map.entry("sorrow", "sad"), Map.entry("fun", "relaxed"),
            Map.entry("blink_l", "blinkLeft"), Map.entry("blink_r", "blinkRight"), Map.entry("lookup", "lookUp"),
            Map.entry("lookdown", "lookDown"), Map.entry("lookleft", "lookLeft"), Map.entry("lookright", "lookRight"),
            Map.entry("neutral", "neutral"));

    /**
     * VRM 1.0 expressions (preset and custom) bind morph targets by node with weights in [0, 1]; VRM 0.x blend shape
     * groups bind them by mesh with weights in [0, 100]. Material and texture binds are not converted.
     */
    private void readExpressionBinds() {
        var extensions = extensions();
        if (extensions.has("VRMC_vrm")) {
            var vrm = extensions.getAsJsonObject("VRMC_vrm");
            var expressions = vrm.has("expressions") ? vrm.getAsJsonObject("expressions") : new JsonObject();
            var nodes = document.array("nodes");
            for (var group : List.of("preset", "custom")) {
                if (!expressions.has(group) || !expressions.get(group).isJsonObject()) {
                    continue;
                }
                for (var entry : expressions.getAsJsonObject(group).entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    var binds = new ArrayList<Bind>();
                    for (var element : array(entry.getValue().getAsJsonObject(), "morphTargetBinds")) {
                        var bind = element.getAsJsonObject();
                        var node = nodeIndex(bind, "node");
                        if (node < 0 || !nodes.get(node).getAsJsonObject().has("mesh")) {
                            continue;
                        }
                        binds.add(new Bind(nodes.get(node).getAsJsonObject().get("mesh").getAsInt(),
                            GlbDocument.intOr(bind, "index", -1), number(bind, "weight", 1f)));
                    }
                    expressionBinds.merge(entry.getKey(), binds, (a, b) -> {
                        a.addAll(b);
                        return a;
                    });
                }
            }
        } else if (extensions.has("VRM") && extensions.getAsJsonObject("VRM").has("blendShapeMaster")) {
            var master = extensions.getAsJsonObject("VRM").getAsJsonObject("blendShapeMaster");
            for (var element : array(master, "blendShapeGroups")) {
                var group = element.getAsJsonObject();
                var preset = group.has("presetName") ? group.get("presetName").getAsString().toLowerCase(Locale.ROOT)
                    : "unknown";
                var name = VRM0_PRESETS.getOrDefault(preset, group.has("name") ? group.get("name").getAsString() : "");
                if (name.isBlank()) {
                    continue;
                }
                var binds = new ArrayList<Bind>();
                for (var bindElement : array(group, "binds")) {
                    var bind = bindElement.getAsJsonObject();
                    binds.add(new Bind(GlbDocument.intOr(bind, "mesh", -1), GlbDocument.intOr(bind, "index", -1),
                        number(bind, "weight", 100f) / 100f));
                }
                expressionBinds.merge(name, binds, (a, b) -> {
                    a.addAll(b);
                    return a;
                });
            }
        }
    }

    /** POSITION offsets of this primitive's morph targets that some expression binds, by target index. */
    private Map<Integer, float[]> morphTargets(int meshIndex, JsonObject primitive, int count) throws IOException {
        var result = new HashMap<Integer, float[]>();
        if (!primitive.has("targets")) {
            return result;
        }
        var targets = primitive.getAsJsonArray("targets");
        for (var binds : expressionBinds.values()) {
            for (var bind : binds) {
                if (bind.mesh() != meshIndex || bind.target() < 0 || bind.target() >= targets.size()
                    || result.containsKey(bind.target())) {
                    continue;
                }
                var target = targets.get(bind.target()).getAsJsonObject();
                if (!target.has("POSITION")) {
                    continue;
                }
                var offsets = document.readFloats(GlbDocument.requiredInt(target, "POSITION"), 3);
                if (offsets.length != count * 3) {
                    throw new IOException("glTF morph target count mismatch");
                }
                result.put(bind.target(), offsets);
            }
        }
        return result;
    }

    static float[] faceNormals(float[] position, int[] indices) {
        var normal = new float[position.length];
        var a = new Vector3f();
        var b = new Vector3f();
        var c = new Vector3f();
        for (var i = 0; i + 2 < indices.length; i += 3) {
            a.set(position[indices[i] * 3], position[indices[i] * 3 + 1], position[indices[i] * 3 + 2]);
            b.set(position[indices[i + 1] * 3], position[indices[i + 1] * 3 + 1], position[indices[i + 1] * 3 + 2]);
            c.set(position[indices[i + 2] * 3], position[indices[i + 2] * 3 + 1], position[indices[i + 2] * 3 + 2]);
            b.sub(a);
            c.sub(a);
            b.cross(c);
            for (var k = 0; k < 3; k++) {
                var vertex = indices[i + k] * 3;
                normal[vertex] += b.x;
                normal[vertex + 1] += b.y;
                normal[vertex + 2] += b.z;
            }
        }
        return normal;
    }

    /** Per-joint rest skin matrices (joint world * inverse bind) and their normal matrices. */
    private final class Skin {
        final int[] nodes;
        final Matrix4f[] matrices;
        final Matrix3f[] normals;

        Skin(int skinIndex) throws IOException {
            var skin = document.element("skins", skinIndex);
            var jointArray = skin.getAsJsonArray("joints");
            nodes = new int[jointArray.size()];
            matrices = new Matrix4f[nodes.length];
            normals = new Matrix3f[nodes.length];
            float[] inverseBind = skin.has("inverseBindMatrices")
                ? document.readFloats(GlbDocument.requiredInt(skin, "inverseBindMatrices"), 16) : null;
            if (inverseBind != null && inverseBind.length != nodes.length * 16) {
                throw new IOException("glTF inverse bind matrix count mismatch");
            }
            for (var k = 0; k < nodes.length; k++) {
                nodes[k] = jointArray.get(k).getAsInt();
                if (nodes[k] < 0 || nodes[k] >= nodeCount) {
                    throw new IOException("Invalid glTF skin joint");
                }
                var bind = new Matrix4f();
                if (inverseBind != null) {
                    bind.set(inverseBind, k * 16);
                }
                matrices[k] = new Matrix4f(world[nodes[k]]).mul(bind);
                normals[k] = matrices[k].normal(new Matrix3f());
            }
        }
    }
}
