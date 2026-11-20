#version 150
#ifdef GL_ARB_explicit_attrib_location
#extension GL_ARB_explicit_attrib_location : enable
#endif
// Endfield NPR: positions/normals arrive in camera-relative view space (captured from YSM's draw).
// Per-character data comes from NprDrawBlock[NprDrawIndex] (NprRenderer: one record per character of the frame;
// NprDrawIndex is an instanced stream read through baseInstance in the multi-draw, a constant attribute otherwise).
// gl_Position is invariant and computed exactly as in raw/npr_depth.vsh (ModelViewMat * (model * position)), so the
// body's LEQUAL depth test passes on the gbuffer depth prepass of the same vertices (cloth with clothDepthBias lies
// nearer than its prepass depth, which passes too).
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
in float NprDrawIndex;
// rain anchors (RainAnchors.java, GpuArena: a second buffer per page, same vertex order as the skin): the vertex in the
// game's object space of the character's reference skin; fixed locations 14/15 where the driver allows (GpuArena
// checks them against the draw-index locations and turns anchoring off on a clash)
#ifdef GL_ARB_explicit_attrib_location
layout(location = 14) in vec3 RainAnchorP;
layout(location = 15) in vec3 RainAnchorN;
#else
in vec3 RainAnchorP;
in vec3 RainAnchorN;
#endif

uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform float BrowRevealPull; // EyeBrow: the brows-through-bangs pass pulls every vertex this far toward the camera
uniform float ClothDepthBias; // clothDepthBias (blocks): cloth vertices are depth-tested this much nearer the camera
uniform sampler2D Sampler3;   // YSM class atlas (npr_character.fsh surfaceClass), read here for clothDepthBias
uniform float HasClassMap;
uniform vec4 FaceRect;        // face_01 region in atlas uv: its non-skin texels are the sclera and lid lines (face)

// model: pose_now * pose_at_skin^-1 (identity for a fresh skin); right/up/forward/center: head basis and centre in
// view space; center.w: exposure after eye adaptation
struct NprDraw { mat4 model; vec4 right; vec4 up; vec4 forward; vec4 center; };
layout(std140) uniform NprDrawBlock { NprDraw nprDraws[128]; };
// rain/wet per character (RainWet.java, npr_rain.glsl): the body frame in view space (right, up, forward: Unity's
// object axes; origin = the feet, origin.w = the water line as camera-relative world y, -1000 = none), the game's
// packed word cb2[draw + 13].x in word.x and word.y = 1 when the draw's cell holds rain anchors for this skin
struct NprRain { vec4 right; vec4 up; vec4 forward; vec4 origin; uvec4 word; };
layout(std140) uniform NprRainBlock { NprRain nprRains[128]; };

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
flat out vec4 RainBytes;
flat out float RainLine;
out vec3 RainObjP; // the game's object space (npr_rain.glsl): anchored position and normal, or the rigid body frame
out vec3 RainObjN;

invariant gl_Position;

// Vanilla's OverlayTexture (16x16, fixed content) evaluated instead of sampled, which frees its texture unit: rows
// v < 8 are the hurt tint 0xB2 red, the others white with alpha (1 - u/15 * 0.75) (the white flash).
vec4 overlayTexel(ivec2 uv) {
    return uv.y < 8 ? vec4(1.0, 0.0, 0.0, 178.0 / 255.0)
                    : vec4(1.0, 1.0, 1.0, floor((1.0 - float(uv.x) / 15.0 * 0.75) * 255.0) / 255.0);
}

// clothDepthBias: the class of the vertex's own texel as npr_character.fsh surfaceClass calls it (CLOTH: blue below
// 112 or 138..140, not the eye code 5), outside the face texture. Without a class map the class comes from the texel
// colour, which is not evaluated here: no bias then.
bool clothVertex(vec2 uv) {
    if (HasClassMap < 0.5) {
        return false;
    }
    vec2 f = (uv - FaceRect.xy) / max(FaceRect.zw, vec2(1e-6));
    if (FaceRect.z > 0.0 && all(greaterThanEqual(f, vec2(0.0))) && all(lessThanEqual(f, vec2(1.0)))) {
        return false;
    }
    ivec2 size = textureSize(Sampler3, 0);
    float blue = texelFetch(Sampler3, clamp(ivec2(uv * vec2(size)), ivec2(0), size - ivec2(1)), 0).b * 255.0;
    return (blue < 112.0 || (blue > 138.0 && blue < 140.0)) && abs(blue - 5.0) >= 2.5;
}

void main() {
    NprDraw d = nprDraws[int(NprDrawIndex + 0.5)];
    vec4 view = ModelViewMat * (d.model * vec4(Position, 1.0));
    gl_Position = ProjMat * view;
    if (BrowRevealPull > 0.0) { // only that pass; the body keeps the depth prepass's exact expression above
        gl_Position = ProjMat * vec4(view.xyz - normalize(view.xyz) * BrowRevealPull, 1.0);
    } else if (ClothDepthBias > 0.0 && clothVertex(UV0)) {
        // clothDepthBias: cloth is depth-tested nearer the camera along its own view ray (same pixel), so skin poking
        // up to that far through the clothes stays hidden (shallow clipping the anti-clip layer leaves)
        gl_Position = ProjMat * vec4(view.xyz - normalize(view.xyz) * ClothDepthBias, 1.0);
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
    NprRain r = nprRains[int(NprDrawIndex + 0.5)];
    uint w = r.word.x; // unpacked as the game does (Skin_10029588 122-128: and 255, ubfe 8@8, 8@16, ushr 24)
    RainBytes = vec4(float(w & 255u), float((w >> 8u) & 255u), float((w >> 16u) & 255u), float(w >> 24u));
    RainLine = r.origin.w;
    if (r.word.y != 0u) {
        RainObjP = RainAnchorP;
        RainObjN = RainAnchorN;
    } else { // 0.8.2's rigid body frame on this frame's skinned vertex
        mat3 toObject = transpose(mat3(r.right.xyz, r.up.xyz, r.forward.xyz));
        RainObjP = toObject * (view.xyz - r.origin.xyz);
        RainObjN = toObject * viewNormal;
    }
}
