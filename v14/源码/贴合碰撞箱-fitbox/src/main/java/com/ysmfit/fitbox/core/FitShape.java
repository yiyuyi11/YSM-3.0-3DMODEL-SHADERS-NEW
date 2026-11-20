package com.ysmfit.fitbox.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Everything fitted for one model: per {@link FitPose} the box (width, height, eye height, in blocks, before the
 * server's min/max clamps) and the posed hit shapes; plus bone-attached hit templates for live poses.
 *
 * <p>Posed shapes are in the entity's model-local frame: YSM's model space times the render scale (blocks, model facing
 * -Z, origin at the feet), plus the pose's render offset (crouching) and turn (sleeping). The world transform is
 * {@code T(entity position) * rotY(180 - bodyYaw)} (sleeping: the bed's rotation instead, see {@link #SLEEP_ROOT}).
 *
 * <p>Templates are in zero-rotation model space (unscaled blocks), each carried by one bone: the client maps them
 * with YSM's live bone pose matrix, the draw's model-to-world matrix (which includes the render scale) and nothing
 * else.
 */
public final class FitShape {
    public static final int FORMAT = 2;

    public final float widthScale;
    public final float heightScale;
    public final String[] templateBones;
    public final Shape[] templates;
    public final PoseFit[] poses;
    /** Human-readable fit notes (segments, sample counts, clip sources) for logs and the offline report. */
    public final String notes;

    public FitShape(float widthScale, float heightScale, String[] templateBones, Shape[] templates, PoseFit[] poses,
        String notes) {
        this.widthScale = widthScale;
        this.heightScale = heightScale;
        this.templateBones = templateBones;
        this.templates = templates;
        this.poses = poses;
        this.notes = notes;
    }

    public PoseFit pose(FitPose pose) {
        return poses[pose.ordinal()];
    }

    /** One pose's fit. */
    public static final class PoseFit {
        public final float width;
        public final float height;
        public final float eye;
        public final Shape[] shapes;
        /** Posed pivot of the head part, for turning the head shapes by the look direction at runtime. */
        public final float[] headPivot;
        /**
         * The eye point (between the eye bones, else up the head) in the model-local frame: x, y (= {@link #eye}), z;
         * the model faces -Z, so -z is how far ahead of the entity's origin the eyes are.
         */
        public final float[] eyePoint;
        public final String clip;

        public PoseFit(float width, float height, float eye, Shape[] shapes, float[] headPivot, float[] eyePoint,
            String clip) {
            this.width = width;
            this.height = height;
            this.eye = eye;
            this.shapes = shapes;
            this.headPivot = headPivot;
            this.eyePoint = eyePoint;
            this.clip = clip;
        }
    }

    public byte[] toBytes() {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(FORMAT);
            out.writeFloat(widthScale);
            out.writeFloat(heightScale);
            out.writeShort(templates.length);
            for (var i = 0; i < templates.length; i++) {
                out.writeUTF(templateBones[i]);
                templates[i].write(out);
            }
            out.writeByte(poses.length);
            for (var p : poses) {
                out.writeFloat(p.width);
                out.writeFloat(p.height);
                out.writeFloat(p.eye);
                out.writeUTF(p.clip);
                for (var f : p.headPivot) {
                    out.writeFloat(f);
                }
                for (var f : p.eyePoint) {
                    out.writeFloat(f);
                }
                out.writeShort(p.shapes.length);
                for (var s : p.shapes) {
                    s.write(out);
                }
            }
            out.writeUTF(notes.length() > 20000 ? notes.substring(0, 20000) : notes);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static FitShape fromBytes(byte[] data) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readInt() != FORMAT) {
            throw new IOException("unknown fit format");
        }
        var ws = in.readFloat();
        var hs = in.readFloat();
        var n = in.readShort();
        var bones = new String[n];
        var templates = new Shape[n];
        for (var i = 0; i < n; i++) {
            bones[i] = in.readUTF();
            templates[i] = Shape.read(in);
        }
        var pc = in.readByte();
        var poses = new PoseFit[pc];
        for (var i = 0; i < pc; i++) {
            var w = in.readFloat();
            var h = in.readFloat();
            var e = in.readFloat();
            var clip = in.readUTF();
            var pivot = new float[] {in.readFloat(), in.readFloat(), in.readFloat()};
            var eyePoint = new float[] {in.readFloat(), in.readFloat(), in.readFloat()};
            var sc = in.readShort();
            var shapes = new Shape[sc];
            for (var k = 0; k < sc; k++) {
                shapes[k] = Shape.read(in);
            }
            poses[i] = new PoseFit(w, h, e, shapes, pivot, eyePoint, clip);
        }
        return new FitShape(ws, hs, bones, templates, poses, in.readUTF());
    }

    /** Sleeping is drawn lying by the renderer itself (vanilla setupRotations): rotZ(90) then rotY(270). */
    public static final float[] SLEEP_ROOT = {90f, 270f};

    public String summary() {
        var b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "render scale w=%.4f h=%.4f, %d hit templates%n", widthScale, heightScale,
            templates.length));
        for (var p : FitPose.values()) {
            var f = poses[p.ordinal()];
            b.append(String.format(Locale.ROOT,
                "  %-12s clip=%-16s width=%.3f height=%.3f eye=%.3f (ahead %.3f, side %.3f) shapes=%d%n", p, f.clip,
                f.width, f.height, f.eye, -f.eyePoint[2], f.eyePoint[0], f.shapes.length));
        }
        return b.toString();
    }
}
