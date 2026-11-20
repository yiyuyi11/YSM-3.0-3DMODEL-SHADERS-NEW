#version 150
#ifdef GL_ARB_explicit_attrib_location
#extension GL_ARB_explicit_attrib_location : enable
#endif
// Endfield outline shell (CharacterOutline VS, ue5ref EndfieldCharacterPass.usf:39-73 / EndfieldOutline.ush:28-66):
// the captured body is drawn again with front faces culled, each vertex pushed along its screen-space normal by a
// width that is constant in NDC near the camera, thins with distance and never drops below one pixel (a thinner
// shell aliases into dotted pixels); the depth is pushed back by _OutlineOffsetZ * 0.1 m plus 6 cm, so the shell only
// shows beyond silhouettes. Width/OffsetZ come per material class from Sampler3, or per material with the _OutlineMask
// (npr_outline_mask.glsl) when the model has `materials` data.
// inputs as npr_character.vsh: fixed locations, UV1 / UV2 as floats used as ivec2
#ifdef GL_ARB_explicit_attrib_location
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in vec2 UV1;
layout(location = 4) in vec2 UV2;
layout(location = 5) in vec3 Normal;
layout(location = 6) in float NprDrawIndex;
#else
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec2 UV1;
in vec2 UV2;
in vec3 Normal;
in float NprDrawIndex;
#endif

uniform sampler2D Sampler2;
uniform sampler2D Sampler3;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec2 ScreenSize;
uniform vec4 OutlineWidth;   // skin, face, hair, cloth (_OutlineWidth * outlineScale)
uniform vec4 OutlineOffsetZ; // skin, face, hair, cloth (_OutlineOffsetZ)
uniform float HasClassMap;
uniform float OutlineScale; // outlineScale (the per-material widths of npr_outline_mask.glsl)

#moj_import <endfield_npr:npr_maps.glsl>
#moj_import <endfield_npr:npr_outline_mask.glsl>

// per character, as npr_character.vsh
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
// npr_rain.glsl inputs: the shell takes no rain (bytes 0)
flat out vec4 RainBytes;
flat out float RainLine;
flat out float RainDrip;
out vec3 RainObjP;
out vec3 RainObjN;

void main() {
    NprDraw d = nprDraws[int(NprDrawIndex + 0.5)];
    vec4 view = ModelViewMat * (d.model * vec4(Position, 1.0));
    vec3 nV = normalize(mat3(ModelViewMat) * (mat3(d.model) * Normal));
    float width = OutlineWidth.w;
    float offsetZ = OutlineOffsetZ.w;
    if (HasClassMap > 0.5) {
        float blue = textureLod(Sampler3, UV0, 0.0).b * 255.0;
        if (abs(blue - 5.0) < 2.5) { width = 0.0; }                                      // eyes/brows: none
        else if (blue >= 112.0 && blue <= 138.0) { width = OutlineWidth.z; offsetZ = OutlineOffsetZ.z; }
        else if (blue >= 155.0) { width = OutlineWidth.y; offsetZ = OutlineOffsetZ.y; }
        else if (blue >= 140.0) { width = OutlineWidth.x; offsetZ = OutlineOffsetZ.x; }
    }
    // NPR_OUTLINE_PARAMS: per-material width / z offset and the _OutlineMask (r width, g z offset); eyes stay without
    vec4 outlineP = width > 0.0 ? nprOutlineParams(UV0, width, offsetZ, OutlineScale) : vec4(0.0, offsetZ, 1.0, 1.0);
    width = outlineP.x;
    offsetZ = outlineP.y;
    vec4 clip = ProjMat * view;
    // Classes are looked up per vertex, so never throw a vertex out of the clip volume (a triangle that mixes
    // classes would stretch across the screen): a zero-width shell is just pushed behind the surface.
    if (width <= 0.0) {
        float sd = clip.w + 0.2;
        clip.z = (ProjMat[2][2] * -sd + ProjMat[3][2]) * clip.w / sd;
        gl_Position = clip;
    } else {
        vec2 pn = (ProjMat * vec4(nV, 0.0)).xy;
        vec2 dir = dot(pn, pn) > 1e-12 ? normalize(pn) : vec2(0.0);
        float halfAngle = atan(1.0 / ProjMat[1][1]);
        float depth = clip.w;
        vec2 raw = dir * vec2(ScreenSize.y / ScreenSize.x, 1.0) * (width * 0.39269908 / halfAngle)
                 * clamp(depth * halfAngle * 114.591568 * 0.04, 0.0, 1.0) * 0.005;
        vec2 mn = 2.0 * clamp(depth, 0.0, 1.5707963 / halfAngle) / ScreenSize;
        vec2 shift = vec2(abs(raw.x) < mn.x ? mn.x * sign(raw.x) : raw.x, abs(raw.y) < mn.y ? mn.y * sign(raw.y) : raw.y);
        clip.xy += shift * outlineP.z; // mask.r after the one-pixel minimum (EndfieldOutline.ush:50-51)
        // 6 cm: the shell must stay behind the body except at silhouettes; mask.g scales _OutlineOffsetZ (ush:52)
        float sd = clip.w + offsetZ * outlineP.w * 0.1 + 0.06;
        clip.z = (ProjMat[2][2] * -sd + ProjMat[3][2]) * clip.w / sd;
        gl_Position = clip;
    }
    viewPos = view.xyz;
    viewNormal = nV;
    texCoord0 = UV0;
    vertexColor = Color;
    ivec2 uv1 = ivec2(UV1);
    ivec2 uv2 = ivec2(UV2);
    lightMapColor = texelFetch(Sampler2, clamp(uv2 / 16, ivec2(0), ivec2(15)), 0);
    // vanilla OverlayTexture evaluated, as in npr_character.vsh (hurt tint rows v < 8, white flash alpha by u)
    overlayColor = uv1.y < 8 ? vec4(1.0, 0.0, 0.0, 178.0 / 255.0)
        : vec4(1.0, 1.0, 1.0, floor((1.0 - float(clamp(uv1.x, 0, 15)) / 15.0 * 0.75) * 255.0) / 255.0);
    skyBlock = clamp(vec2(uv2.yx) / 240.0, 0.0, 1.0);
    HeadRight = d.right.xyz;
    HeadUp = d.up.xyz;
    HeadForward = d.forward.xyz;
    HeadCenter = d.center.xyz;
    DrawExposure = d.center.w;
    RainBytes = vec4(0.0);
    RainLine = -1000.0;
    RainDrip = -1.0;
    RainObjP = vec3(0.0);
    RainObjN = vec3(0.0, 1.0, 0.0);
}
