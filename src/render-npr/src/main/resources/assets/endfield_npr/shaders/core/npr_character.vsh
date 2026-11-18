#version 150
// Endfield NPR: positions/normals arrive in camera-relative view space (captured from YSM's draw).
// Per-character data comes from NprDrawBlock[NprDrawIndex] (NprRenderer: one record per character of the frame;
// NprDrawIndex is an instanced stream read through baseInstance in the multi-draw, a constant attribute otherwise).
// gl_Position is invariant and computed exactly as in raw/npr_depth.vsh (ModelViewMat * (model * position)), so the
// body's LEQUAL depth test passes on the gbuffer depth prepass of the same vertices.
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
in float NprDrawIndex;

uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform float BrowRevealPull; // EyeBrow: the brows-through-bangs pass pulls every vertex this far toward the camera

// model: pose_now * pose_at_skin^-1 (identity for a fresh skin); right/up/forward/center: head basis and centre in
// view space; center.w: exposure after eye adaptation
struct NprDraw { mat4 model; vec4 right; vec4 up; vec4 forward; vec4 center; };
layout(std140) uniform NprDrawBlock { NprDraw nprDraws[128]; };

out vec3 viewPos;
out vec3 viewNormal;
out vec2 texCoord0;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 skyBlock;
flat out vec3 HeadRight;
flat out vec3 HeadUp;
flat out vec3 HeadForward;
flat out vec3 HeadCenter;
flat out float DrawExposure;

invariant gl_Position;

// Vanilla's OverlayTexture (16x16, fixed content) evaluated instead of sampled, which frees its texture unit: rows
// v < 8 are the hurt tint 0xB2 red, the others white with alpha (1 - u/15 * 0.75) (the white flash).
vec4 overlayTexel(ivec2 uv) {
    return uv.y < 8 ? vec4(1.0, 0.0, 0.0, 178.0 / 255.0)
                    : vec4(1.0, 1.0, 1.0, floor((1.0 - float(uv.x) / 15.0 * 0.75) * 255.0) / 255.0);
}

void main() {
    NprDraw d = nprDraws[int(NprDrawIndex + 0.5)];
    vec4 view = ModelViewMat * (d.model * vec4(Position, 1.0));
    gl_Position = ProjMat * view;
    if (BrowRevealPull > 0.0) { // only that pass; the body keeps the depth prepass's exact expression above
        gl_Position = ProjMat * vec4(view.xyz - normalize(view.xyz) * BrowRevealPull, 1.0);
    }
    viewPos = view.xyz;
    viewNormal = mat3(ModelViewMat) * (mat3(d.model) * Normal);
    texCoord0 = UV0;
    vertexColor = Color;
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    overlayColor = overlayTexel(clamp(UV1, ivec2(0), ivec2(15)));
    skyBlock = vec2(UV2.y, UV2.x) / 240.0;
    HeadRight = d.right.xyz;
    HeadUp = d.up.xyz;
    HeadForward = d.forward.xyz;
    HeadCenter = d.center.xyz;
    DrawExposure = d.center.w;
}
