package com.elfmcys.ysm.format.parser;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the import steps up to {@link ClothColliders} on a VRM and writes what it changed as JSON, in the VRM's own rest
 * space and node names, so tools/springsim can apply it to the VRM: added colliders, the chains (by root joint node)
 * they were added to, clamped hit radii (by joint node), and the reweighted original vertices (index in VrmReader's
 * order, joint node names, weights). Also prints each skirt chain's minimum rest clearance.
 *
 * <p>Usage: GenCloth &lt;model.vrm&gt; &lt;out.json&gt;
 */
public final class GenCloth {
    public static void main(String[] args) throws Exception {
        var data = Files.readAllBytes(Path.of(args[0]));
        var buffer = ByteBuffer.allocateDirect(data.length).put(data).flip();
        var source = VrmReader.read(buffer);
        // The pass is opt-in on import; this tool inspects it, so it runs unless humanoid.json turns it off.
        source.clothColliders = true;
        source.clothFollow = true;
        HumanoidMapping.guessFromNames(source);
        var override = Path.of(args[0]).resolveSibling(HumanoidMapping.OVERRIDE_FILE);
        if (Files.exists(override)) {
            HumanoidMapping.applyOverride(source, ByteBuffer.wrap(Files.readAllBytes(override)));
        }
        HumanoidMapping.complete(source);
        HumanoidMapping.validate(source);
        PhongRefinement.refine(source);
        var colliders = source.springColliders.size();
        var chains = source.springChains.stream().map(chain -> chain.colliders().clone()).toList();
        var joints = java.util.List.copyOf(source.springJoints);
        var vertices = source.positions.size() / 3;
        var oldJoints = source.joints.toIntArray();
        var oldWeights = source.weights.toFloatArray();
        var report = ClothColliders.apply(source);
        System.out.println(report);

        var out = new JsonObject();
        var added = new JsonArray();
        for (var i = colliders; i < source.springColliders.size(); i++) {
            var c = source.springColliders.get(i);
            var o = new JsonObject();
            o.addProperty("node", source.nodeNames.get(c.node()));
            o.add("head", vec(c.head().x, c.head().y, c.head().z));
            o.add("tail", vec(c.tail().x, c.tail().y, c.tail().z));
            o.addProperty("radius", c.radius());
            added.add(o);
        }
        out.add("colliders", added);
        var chainOut = new JsonObject();
        var hit = new JsonObject();
        for (var i = 0; i < joints.size(); i++) {
            var before = joints.get(i);
            var after = source.springJoints.get(i);
            if (after.hitRadius() != before.hitRadius()) {
                hit.addProperty(source.nodeNames.get(after.node()), after.hitRadius());
            }
        }
        for (var c = 0; c < source.springChains.size(); c++) {
            if (source.springChains.get(c).colliders().length == chains.get(c).length) {
                continue;
            }
            for (var joint : source.springJoints) {
                if (joint.chain() == c) {
                    var extra = new JsonArray();
                    for (var k = chains.get(c).length; k < source.springChains.get(c).colliders().length; k++) {
                        extra.add(source.springChains.get(c).colliders()[k] - colliders);
                    }
                    chainOut.add(source.nodeNames.get(joint.node()), extra);
                }
            }
        }
        for (var c = 0; c < source.springChains.size(); c++) {
            if (source.springChains.get(c).colliders().length == chains.get(c).length) {
                continue;
            }
            var min = Float.POSITIVE_INFINITY;
            String name = null;
            for (var joint : source.springJoints) {
                if (joint.chain() != c) {
                    continue;
                }
                name = name == null ? source.nodeNames.get(joint.node()) : name;
                for (var index : source.springChains.get(c).colliders()) {
                    var col = source.springColliders.get(index);
                    min = Math.min(min, distance(joint.tail(), col.head(), col.tail()) - col.radius()
                        - joint.hitRadius());
                }
            }
            System.out.printf("rest clearance %-32s %7.4f%n", name, min);
        }
        out.add("chains", chainOut);
        out.add("hitRadius", hit);
        var weights = new JsonArray();
        for (var v = 0; v < vertices; v++) {
            var changed = false;
            for (var k = 0; k < 4; k++) {
                changed |= oldJoints[v * 4 + k] != source.joints.getInt(v * 4 + k)
                    || oldWeights[v * 4 + k] != source.weights.getFloat(v * 4 + k);
            }
            if (!changed) {
                continue;
            }
            var entry = new JsonArray();
            entry.add(v);
            var names = new JsonArray();
            var ws = new JsonArray();
            for (var k = 0; k < 4; k++) {
                var j = source.joints.getInt(v * 4 + k);
                names.add(j >= 0 ? source.nodeNames.get(j) : "");
                ws.add(source.weights.getFloat(v * 4 + k));
            }
            entry.add(names);
            entry.add(ws);
            entry.add(vec(source.positions.getFloat(v * 3), source.positions.getFloat(v * 3 + 1),
                source.positions.getFloat(v * 3 + 2)));
            weights.add(entry);
        }
        out.add("weights", weights);
        Files.writeString(Path.of(args[1]), new GsonBuilder().create().toJson(out));
    }

    private static JsonArray vec(float x, float y, float z) {
        var a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }

    private static float distance(org.joml.Vector3f p, org.joml.Vector3f a, org.joml.Vector3f b) {
        var s = new org.joml.Vector3f(b).sub(a);
        var o = new org.joml.Vector3f(p).sub(a);
        var l = s.lengthSquared();
        var t = l < 1e-12f ? 0f : Math.min(Math.max(o.dot(s) / l, 0f), 1f);
        return o.fma(-t, s).length();
    }
}
