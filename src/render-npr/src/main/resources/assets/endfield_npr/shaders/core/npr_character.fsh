#version 150
#ifdef GL_ARB_explicit_attrib_location
#extension GL_ARB_explicit_attrib_location : enable
#endif
// =====================================================================================================
// changes follow ~/ysm/npr/compare-ue5-skinface.md and compare-ue5-haircloth.md).
// view space. Material class per texel from YSM's specular atlas (Sampler3 blue: 5 eye/brow, 10 cloth,
// 115-135 hair, 150 skin, 160 face; green 255 = metal). OutlinePass = 1 when this program shades the outline
// shell (npr_outline.vsh): the albedo becomes the outline colour and goes through the same lighting.
// =====================================================================================================

uniform sampler2D Sampler0;   // YSM colour atlas
uniform sampler2D Sampler3;   // YSM specular/class atlas
uniform sampler2D Sampler4;   // ramp atlas 256x8 (rows: cloth, skin, face, hair, eye RD, cloth RS, hair RS)
uniform sampler2D Sampler5;   // skin shadow LUT 1024x32 (optional)
uniform sampler2D Sampler6;   // face _SDFLightmap (face texture space, through FaceRect)
uniform sampler2D Sampler7;   // face _SDFMask (cm_M): r edge-tint mask, g geometric weight, b rim-off select
uniform sampler2D Sampler8;   // face _HighlightMap (hl_M)
uniform sampler2D Sampler9;   // iris _MatcapTex
uniform sampler2D Sampler10;  // shader pack sun shadow depth (Iris shadowtex1), see PackBuffers.java
uniform sampler2D SelfShadowMap; // character self-shadow caster depth (R32F, +2 = face caster), see SelfShadow.java
uniform sampler2D SceneDepth;    // the pack's depthtex1 (scene depth before translucents, with her depth prepass)
#moj_import <endfield_npr:npr_maps.glsl>
#moj_import <endfield_npr:npr_normal.glsl>
uniform vec4 MapTuning;         // PropMaps: x emissionScale

uniform vec4 ColorModulator;
uniform vec3 LightDirView;
uniform vec3 LightColor;
uniform vec3 WorldUp;
flat in vec3 HeadRight;   // per character (NprDrawBlock via the vertex stage)
flat in vec3 HeadUp;
flat in vec3 HeadForward;
flat in vec3 HeadCenter;
uniform vec4 MatSkin;    // smoothness, specular, shadowColorBrightness, shadowColorSaturation
uniform vec4 MatFace;
uniform vec4 MatHair;
uniform vec4 MatCloth;
uniform vec4 MatEye;
uniform vec4 HairAniso;  // _AnisotropyValue, _AnisotropyIntensity, _AnisotropyEdgeFade, _AnisotropyDirX
uniform vec4 HairSpec;   // gloss G (primary mask), gloss A (secondary mask), _AnisotropyRange2, _AnisotropyValue2
uniform vec3 HairSpec2;  // _AnisotropyColor2
uniform vec4 SkinRim;    // _SDFRimColor.rgb, _SkinRimOffScale
uniform vec4 Globals0;   // exposure, ambientScale, lightScale(CP0.y), shadowScale(CP0.z)
uniform vec4 Globals1;   // shadowAmbScale(CP0.w), ambToWhite(CP1.y), ndotlBias, backlight(1-CP12.x)
uniform vec4 Globals2;   // specScale(CP13.w), _FaceRimOffScale, sdfBias, useShadowLut
uniform vec4 RimColor;   // CP8
uniform vec4 RimParams;  // CP9: offset xy, albedo tint, width
uniform vec3 SkyColor;   // hue of the ambient where the sky is visible
uniform vec4 OutlineBS0; // outline brightness/saturation: skin, cloth
uniform vec4 OutlineBS1; // hair, eye
uniform vec4 FaceFlags;    // per model (ModelProfiles): x SDF, y highlight map, z iris matcap, w iris rect known
uniform vec4 FaceRect;     // face_01 region in atlas uv: xy origin, zw size
uniform vec4 IrisRect;     // iris_01 region in atlas uv
uniform vec4 HighlightVec; // _HighlightMapVector.xy, highlight intensity, _MatcapNormalScale
uniform vec4 MatcapColor;  // _MatcapColor
uniform float HasClassMap;
uniform float OutlinePass;
uniform float Minimal;     // 1 = MINIMAL crowd tier (NprLod): the diffuse-only branch at the top of main()
uniform float DebugView;   // debugView: 1 normal, 2 raw N.L, 3 N.L of the flat triangle normal, 6/7 mapped N / N.L
uniform vec4 DisplayParams; // display transform: x on, z ACES_modified saturation (0.93), w post saturation
flat in float DrawExposure; // per-character exposure after eye adaptation (NprDrawBlock center.w)
#define Display vec4(DisplayParams.x, DrawExposure, DisplayParams.zw) // y = exposure
uniform vec4 Tuning;
                           // mixes, z face_01 _SkinRimOffScale, w eye-class gain (eyeBrightness x eyeNightBrightness
                           // by night; iris and brows, after the matcap/highlight terms, not in the outline pass)
uniform float EyeGlow;     // eyeNightGlow x night (NprRenderer): linear iris albedo added as a little self-light
uniform vec4 SceneShadow;   // x strength (0 = no shadow map), y self-shadow distance (blocks), z depth -> blocks, w PCF radius (texels)
uniform vec4 SceneShadow2;  // x ambient left in the pack's sun shadow under open sky
uniform vec4 ShadowDistort; // pack shadow-map distortion: k, a, b, z divisor (Bliss: xy /= log(|xy| b + a) k, z /= 6)
uniform mat4 ShadowMV;      // shadowModelView of this frame (Iris ShadowRenderer.MODELVIEW)
uniform mat4 ShadowProj;    // shadowProjection (Iris ShadowRenderer.PROJECTION)
uniform mat3 ViewToPlayer;  // captured view space -> camera-relative world (Iris player space)
uniform mat4 SelfShadowMat;    // view space -> self-shadow map [0,1]^3
uniform vec4 SelfShadowParams; // x strength (0 = off), y receiver offset toward the light, z along the normal (blocks), w tap spacing (texels)
uniform vec4 AoParams;         // x strength (0 = off), y exponent (Bliss pow(SSAO, 6) on indirect light)
uniform mat4 ProjMat;
uniform mat4 InvProjMat;
uniform vec2 ScreenSize;
uniform vec4 TranslucentCover; // x on: the passes depth-test the pack's opaque depth and SceneDepth is the full depth;
                               // y her opacity where a translucent surface lies in front (blended SrcAlpha)
uniform float FaceCrackDebug;  // debugFaceCrack: a synthetic crack of this many pixels through the face (0 = off)

in vec3 viewPos;
in vec3 viewNormal;
in vec2 texCoord0;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 skyBlock;

// fragReceiver: the receiver class for the OverlayShadow layers and the face crack fill (ReceiverTarget.java, stands in
// for the game's stencil), k/8: 2 skin/cloth (stencil 36 & 20 = 4), 3 face (also 4; FaceCrackFill), 4 hair (also 4,
// plus the hair marker), 6 iris/brow (bit 16 set), 0 nothing
#ifdef GL_ARB_explicit_attrib_location
layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 fragReceiver;
#else
out vec4 fragColor;
out vec4 fragReceiver;
#endif

#moj_import <endfield_npr:npr_maps.glsl>
#moj_import <endfield_npr:npr_eyebrow.glsl>

const vec3 LUM = vec3(0.212673, 0.715152, 0.072175);
const int CLOTH = 0;
const int SKIN = 1;
const int FACE = 2;
const int HAIR = 3;
const int EYE = 4;

float lum(vec3 c) { return dot(c, LUM); }
float max3(vec3 c) { return max(c.r, max(c.g, c.b)); }
float min3(vec3 c) { return min(c.r, min(c.g, c.b)); }
float smooth01(float x) { x = clamp(x, 0.0, 1.0); return x * x * (3.0 - 2.0 * x); }

vec4 rampRow(int row, float u) {
    return texture(Sampler4, vec2(clamp(u, 0.0, 1.0) * (255.0 / 256.0) + 0.5 / 256.0, (float(row) + 0.5) / 8.0));
}

// ---- antialiasing of shading thresholds (~1 px; NprMsaa's ssaa shades per sample on top). Derivatives are taken in
// uniform control flow only: callers pass the footprint w of their value, computed outside per-texel class branches.
float footprint(float x) {
    return length(vec2(dFdx(x), dFdy(x)));
}
// smoothstep(lo, hi, x), widened to at least the pixel footprint w of x: a transition already wider than a pixel is
// exactly the original, a hard (narrow) one spans about one pixel instead of stair-stepping
float aaSmoothstep(float lo, float hi, float x, float w) {
    float mid = 0.5 * (lo + hi);
    float halfWidth = max(0.5 * max(hi - lo, w), 1e-6);
    return smoothstep(mid - halfWidth, mid + halfWidth, x);
}
// a ramp row averaged over the pixel footprint w of u (4 taps): a hard step in an authored ramp spans about one pixel;
// below half a ramp texel it is the plain lookup (the bilinear filter already spans a pixel or more there). w is
// capped so the large derivatives at class or SDF seams cannot blur the ramp
vec4 rampRowAA(int row, float u, float w) {
    w = min(w, 0.0625);
    if (w < 0.5 / 256.0) {
        return rampRow(row, u);
    }
    return 0.25 * (rampRow(row, u - 0.375 * w) + rampRow(row, u - 0.125 * w) + rampRow(row, u + 0.125 * w)
        + rampRow(row, u + 0.375 * w));
}

vec3 srgbEncode(vec3 c) {
    c = max(c, 0.0);
    return mix(1.055 * pow(c, vec3(0.416667)) - 0.055, c * 12.92, vec3(lessThanEqual(c, vec3(0.003131))));
}
vec3 srgbDecode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92, vec3(lessThanEqual(c, vec3(0.04045))));
}
vec3 gradeDisplay(vec3 c, float b, float s) {
    vec3 l = srgbDecode(c);
    return srgbEncode(max(mix(vec3(lum(l)), l, s) * b, 0.0));
}

// TONEMAPPING_ACES_MODIFIED) with neutral grading (the grade uniforms are not captured): sRGB-linear -> AP1, the
// fitted ACES curve per channel, saturation 0.93 around AP1 luminance, AP1 -> sRGB-linear, highlights pulled toward
// their hue (lerp to c / max(c) by (lum - 0.5) * 2/3). Our pass draws after the shader pack's own tonemap, so
// without this the character skipped the game's highlight compression and desaturation.
// HLSL float3x3(rows) * v == v * mat3(rows) in GLSL.
const mat3 SRGB_TO_AP1 = mat3(0.613191, 0.339511, 0.047368, 0.070208, 0.916335, 0.013450, 0.020616, 0.109566, 0.869612);
const mat3 AP1_TO_SRGB = mat3(1.705052, -0.621791, -0.083259, -0.130257, 1.140803, -0.010548, -0.024003, -0.128969, 1.152972);
const vec3 AP1_LUM = vec3(0.272229, 0.674082, 0.053689);

vec3 acesModifiedCurve(vec3 x) {
    return clamp(x * (x * 2.785085 + 0.107772) / (x * (x * 2.936045 + 0.887122) + 0.806889), 0.0, 1.0);
}

// display-space colour in (our lighting is composed on sRGB-encoded albedo), display-space colour out
vec3 displayTransform(vec3 c) {
    vec3 lin = pow(max(c, 0.0), vec3(2.2)) * Display.y; // unclamped decode: lit colour may exceed 1
    vec3 ap1 = lin * SRGB_TO_AP1;
    vec3 t = acesModifiedCurve(ap1);
    t = mix(vec3(dot(t, AP1_LUM)), t, Display.z);
    vec3 s = t * AP1_TO_SRGB;
    float hl = clamp((dot(ap1, AP1_LUM) - 0.5) * 0.6666667, 0.0, 1.0);
    s = clamp(mix(s, clamp(s / max(max(max(s.r, s.g), s.b), 1e-5), 0.0, 1.0), hl), 0.0, 1.0);
    s = srgbEncode(s);
    return clamp(mix(vec3(lum(s)), s, Display.w), 0.0, 1.0);
}

vec3 shadowLut(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    float s = c.b * 31.0;
    float sl = floor(s);
    vec2 uv = vec2(sl / 32.0 + c.r * 0.030273 + 0.000488, 1.0 - (c.g * 0.96875 + 0.015625));
    return mix(texture(Sampler5, uv).rgb, texture(Sampler5, uv + vec2(0.03125, 0.0)).rgb, s - sl);
}

float strand(vec3 T, vec3 H, float n) {
    float th = dot(T, H);
    return exp2(log2(max(sqrt(max(1.0 - th * th, 0.0)), 1e-4)) * n);
}

// _2376/_2381): two rational fits in N.V and roughness^2, with the multiple-scattering term on f0
vec3 nprEnvBRDF(vec3 f0, float noV, float roughness) {
    float n = noV, r = roughness * roughness, n2 = n * n, n3 = n2 * n;
    float a = ((0.0365463011 + 3.327069998 * n) + (9.063199997 - 9.047559738 * n) * r)
        / max((1.0 + 3.596849918 * n2 - 1.367720008 * n3) + (9.044010163 - 16.31739998 * n2 + 9.22949028 * n3) * r
            + (5.565889835 + 19.78860092 * n2 - 20.21229935 * n3) * r * r * r, 1e-8);
    float b = ((0.990440011 - 1.285140038 * n) + (1.29677999 - 0.755907 * n) * r)
        / max((1.0 + 2.923379898 * n + 59.41880035 * n3) + (20.32250023 - 27.03019905 * n + 222.5919952 * n3) * r
            + (121.5630035 + 626.1300049 * n + 316.6270142 * n3) * r * r * r, 1e-8);
    vec3 c = f0 * a + b;
    return c + f0 * ((1.0 - (a + b)) / max(a + b, 1e-8)) * c;
}

// than SceneShadow.y blocks toward the sun are this character herself (she is a caster in the pack's shadow pass);
// they are ignored, since Endfield resolves the character's own shadow separately from the scene shadow.
float sceneShadowRaw() {
    vec4 c = ShadowProj * (ShadowMV * vec4(ViewToPlayer * viewPos, 1.0));
    vec2 xy = c.xy / (log(length(c.xy) * ShadowDistort.z + ShadowDistort.y) * ShadowDistort.x);
    vec2 uv = xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
        return 1.0;
    }
    float depth = c.z / ShadowDistort.w * 0.5 + 0.5;
    vec2 texel = SceneShadow.w / vec2(textureSize(Sampler10, 0));
    float lit = 0.0;
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            float occluder = texture(Sampler10, uv + vec2(i, j) * texel).r;
            lit += (depth - occluder) * SceneShadow.z > SceneShadow.y ? 0.0 : 1.0;
        }
    }
    return lit / 9.0;
}

// offset toward the light and along its normal by (1 - clamp(N.L, 0, 0.9)) x bias, then 16 taps (4x4 grid) compare
// against the caster depths; face receivers ignore face casters (no nose shadows on the SDF-shaded face).
float selfShadowY(vec3 N, vec3 L, bool faceReceiver) {
    float w = 1.0 - clamp(dot(N, L), 0.0, 0.9);
    vec3 p = viewPos + L * (w * SelfShadowParams.y) + N * (w * SelfShadowParams.z);
    vec3 c = (SelfShadowMat * vec4(p, 1.0)).xyz;
    if (any(lessThanEqual(c, vec3(0.0))) || any(greaterThanEqual(c, vec3(1.0)))) {
        return 1.0;
    }
    vec2 texel = SelfShadowParams.w / vec2(textureSize(SelfShadowMap, 0));
    float lit = 0.0;
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            float v = texture(SelfShadowMap, c.xy + (vec2(i, j) - 1.5) * texel).r;
            bool faceCaster = v > 1.5;
            float d = faceCaster ? v - 2.0 : v;
            lit += (d >= c.z || (faceReceiver && faceCaster)) ? 1.0 : 0.0;
        }
    }
    float y = smoothstep(0.2, 0.8, lit / 16.0);
    return mix(1.0, y, SelfShadowParams.x);
}

// The pack's SSAO evaluated for this pixel (Bliss composite.fsh SSAO(): 7 spiral taps on depthtex1, radius from
// gbufferProjection[1][1] and the view depth, occlusion = N.dir x falloff), so her AO matches the scene's. Her own depth
// is in depthtex1 through the gbuffer prepass (DepthPrepass.java). The 2x2 quad average stands in for Bliss's denoiser.
float sceneAO(vec3 N) {
    vec2 px = gl_FragCoord.xy;
    // fixed spiral phase: Bliss rotates it per pixel and frame and denoises temporally; a single frame of that noise
    // through pow(ao, curve) reads as stipple on her flat toon colours
    float noise = 0.5;
    float z = viewPos.z;
    float dist = 1.0 + clamp(z * z / 50.0, 0.0, 5.0);
    float mulfov2 = ProjMat[1][1] / (3.0 * dist);
    float maxR2 = z * z * mulfov2 * 2.0 * 5.0 / mix(4.0, 50.0, clamp(z * z - 0.1, 0.0, 1.0));
    vec2 size = vec2(textureSize(SceneDepth, 0));
    float aspect = ScreenSize.x / max(ScreenSize.y, 1.0);
    float occlusion = 0.0;
    float n = 0.0;
    for (int i = 0; i < 7; i++) {
        float varied = float(i) + noise * 0.897;
        float spiral = varied / (6.0 + noise * 0.897);
        float theta = varied * 3.14159265 * 2.26;
        vec2 offs = vec2(cos(theta), sin(theta)) * spiral * mulfov2 * 0.3;
        vec2 sp = floor(px * size / max(ScreenSize, vec2(1.0)) + offs * vec2(size.x, size.y * aspect)) + 0.5;
        if (any(lessThan(sp, vec2(0.0))) || any(greaterThanEqual(sp, size))) {
            continue;
        }
        float d = texelFetch(SceneDepth, ivec2(sp), 0).r;
        vec4 v = InvProjMat * vec4(sp / size * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
        vec3 t = v.xyz / v.w - viewPos;
        float d2 = dot(t, t);
        if (d2 > 1e-5) {
            if (d2 < maxR2) {
                occlusion += clamp(dot(t * inversesqrt(d2), N), 0.0, 1.0) * clamp(1.0 - d2 / maxR2, 0.0, 1.0);
            }
            n += 1.0;
        }
    }
    float ao = n > 0.0 ? max(1.0 - occlusion * AoParams.x / n, 0.0) : 1.0;
    ao -= dFdx(ao) * (mod(floor(px.x), 2.0) - 0.5);
    ao -= dFdy(ao) * (mod(floor(px.y), 2.0) - 0.5);
    return pow(clamp(ao, 0.0, 1.0), AoParams.y);
}

vec3 horiz(vec3 v) {
    vec3 h = v - WorldUp * dot(v, WorldUp);
    return dot(h, h) > 1e-8 ? normalize(h) : vec3(0.0);
}

int surfaceClass(vec4 spec, vec3 albedo, out float metal) {
    metal = 0.0;
    if (HasClassMap < 0.5) {
        float l = lum(albedo);
        float sat = max3(albedo) - min3(albedo);
        return (albedo.r > albedo.g && albedo.g > albedo.b && l > 0.45 && sat > 0.06 && sat < 0.45) ? SKIN : CLOTH;
    }
    float blue = spec.b * 255.0;
    metal = spec.g >= 0.995 ? 1.0 : 0.0;
    if (abs(blue - 5.0) < 2.5) return EYE;
    if (blue >= 112.0 && blue <= 138.0) return HAIR;
    if (blue >= 155.0) return FACE;
    if (blue >= 140.0) return SKIN;
    return CLOTH;
}

#moj_import <endfield_npr:npr_lights.glsl>

void main() {
    vec4 base = texture(Sampler0, texCoord0);
    if (base.a < 0.1) {
        discard;
    }
    vec3 A = base.rgb * vertexColor.rgb * ColorModulator.rgb;
    float metalCode;
    int cls = surfaceClass(texture(Sampler3, texCoord0), A, metalCode);
    // Face texels YSM's class map calls cloth: its importer classes face_01 per texel by colour (SpecularAtlas
    // FACE_TONE: skin-toned -> face, anything else -> cloth), so the painted sclera (white) got the cloth ramp and
    // cloth GGX highlight, with the half-resolution class map's stair-stepped borders. In the game the sclera is part
    // of face_01 (skin program). Inside the profile's face rectangle such texels are face.
    vec2 faceRectUV = (texCoord0 - FaceRect.xy) / max(FaceRect.zw, vec2(1e-6));
    bool inFaceRect = FaceRect.z > 0.0 && all(greaterThanEqual(faceRectUV, vec2(0.0)))
        && all(lessThanEqual(faceRectUV, vec2(1.0)));
    if (cls == CLOTH && inFaceRect) {
        cls = FACE;
        metalCode = 0.0;
    }
    // PropMaps: the texel's material (package table). A material the game draws with its hair shader is hair here too:
    // YSM's class map, or the colour fallback without one (HasClassMap = 0 classes hair as cloth), must not send it
    // down the cloth path, which reads _MetallicGlossMap R as metal; hair R is the strand tangent blend, and a
    // "metal" texel without an environment term rendered black (aglina, lastrite, liino: hair R mean 0.8-1.0)
    int propMat = nprMaterialAt(texCoord0);
    if ((cls == CLOTH || cls == SKIN) && nprMaterialIsHair(propMat)) {
        cls = HAIR;
        metalCode = 0.0;
    }
    vec4 mat = cls == SKIN ? MatSkin : cls == FACE ? MatFace : cls == HAIR ? MatHair : cls == EYE ? MatEye : MatCloth;
    int rdRow = cls == SKIN ? 1 : cls == FACE ? 2 : cls == HAIR ? 3 : cls == EYE ? 4 : 0;
    bool skinLike = cls == SKIN || cls == FACE || cls == EYE;
    float receiverClass = cls == EYE ? 0.75 : cls == HAIR ? 0.5 : cls == FACE ? 0.375 : 0.25;
    // ---- PropMaps (npr_maps.glsl): this texel's material and its _MetallicGlossMap texel (prop B = the property
    bool hasProp = nprMaterialHas(propMat, NPR_PROP);
    vec4 prop = nprAlignedMap(NPR_PROP, texCoord0);
    // MapTuning.z = propShadowWeight (1 = the game's receiver x B / lit = min(.., B); 0 = B ignored)
    float propB = hasProp && (cls == HAIR || cls == CLOTH) ? mix(1.0, prop.b, MapTuning.z) : 1.0;
    vec2 lineT = nprTiledUv(NPR_LINE, propMat, texCoord0);
    vec2 lineDx = dFdx(lineT);
    vec2 lineDy = dFdy(lineT);
    float lineX = nprMaterialUv(propMat, texCoord0).x * nprMatParam(propMat, 0).x; // u x _LineAmount
    float lineW = footprint(lineX);
    receiverClass = nprBrowReceiver(cls == HAIR, receiverClass, texCoord0); // EyeBrow: hair under _HairBrowMask
    if (nprBrowRevealSkip(cls == EYE)) { // EyeBrow: the brows-through-bangs pass keeps eye texels over masked hair
        discard;
    }
    // debugFaceCrack: a vertical cut of FaceCrackDebug pixels through the head's screen centre, on face texels only,
    // showing what lies behind the face like an unwelded seam would (test input of FaceCrackFill)
    if (FaceCrackDebug > 0.0 && cls == FACE && OutlinePass < 0.5) {
        vec4 hc = ProjMat * vec4(HeadCenter, 1.0);
        float cx = (hc.x / max(hc.w, 1e-4) * 0.5 + 0.5) * ScreenSize.x;
        if (abs(gl_FragCoord.x - cx) < 0.5 * FaceCrackDebug) {
            discard;
        }
    }

    // MINIMAL crowd tier (NprLod): the lit/shadow split of the full path below with its light colour, ambient,
    // exposure, display transform and translucent cover, but only the RD ramp on the mesh N.L (no SDF, scene-shadow
    // map, self-shadow, AO, specular, rim, back light, highlight boost or ramp chroma tint), so distant and capped
    // characters keep the same colours.
    if (Minimal > 0.5 && OutlinePass < 0.5) {
        vec3 Nm = normalize(viewNormal) * (gl_FrontFacing ? 1.0 : -1.0);
        vec3 Lm = normalize(LightDirView);
        float shadowM = smoothstep(0.5, 0.93, skyBlock.x);
        float uM = clamp(dot(Nm, Lm) + Globals1.z, -1.0, 1.0) * 0.5 + 0.5;
        float litM = rampRowAA(rdRow, uM, footprint(uM)).a;
        float oneMinusRM = 0.96 - 0.96 * metalCode;
        vec3 diffM = A * oneMinusRM;
        vec3 SM = (skinLike && Globals2.w > 0.5 ? shadowLut(A) : gradeDisplay(A, mat.z, mat.w)) * oneMinusRM
                * Globals0.w;
        vec3 ambM = lightMapColor.rgb * Globals0.y;
        float ambLumM = max(max3(ambM), 1e-4);
        vec3 skyHueM = SkyColor / max(max3(SkyColor), 1e-4);
        vec3 ambHueM = mix(ambM / ambLumM, mix(vec3(1.0), skyHueM, 0.45), clamp(skyBlock.x, 0.0, 1.0));
        vec3 ambLM = mix(ambHueM, vec3(1.0), litM * Globals1.y);
        vec3 shadowAmbM = ambLM * min(ambLumM * 0.35 + 0.65, 1.5) * Globals1.x * ambLumM;
        vec3 LcM = mix(vec3(lum(LightColor)), LightColor, litM);
        vec3 lightM = mix(shadowAmbM, (ambLM * clamp(ambLumM, 0.0, 1.5) * 0.35 + LcM) * Globals0.z, shadowM);
        vec3 albedoM = mix(gradeDisplay(diffM, 1.0, 1.2), mix(SM, diffM, litM), shadowM);
        vec3 colorM = lightM * albedoM * Globals0.x;
        if (cls == EYE) {
            colorM *= Tuning.w;
        }
        if (Display.x > 0.5) {
            colorM = displayTransform(colorM);
        }
        float opacityM = 1.0;
        if (TranslucentCover.x > 0.5) {
            vec2 sizeM = vec2(textureSize(SceneDepth, 0));
            float frontM = texelFetch(SceneDepth, ivec2(floor(gl_FragCoord.xy * sizeM / max(ScreenSize, vec2(1.0)))),
                0).r;
            opacityM = frontM < gl_FragCoord.z - 2e-5 ? TranslucentCover.y : 1.0;
        }
        fragColor = vec4(mix(overlayColor.rgb, colorM, overlayColor.a), opacityM);
        fragReceiver = vec4(vec3(receiverClass), 1.0);
        return;
    }

    if (OutlinePass > 0.5) { // 8: outline colour = Grade(BaseMap, brightness, saturation) * 0.96 (EndfieldOutlineMath.ush:66-93)
        vec2 bs = cls == HAIR ? OutlineBS1.xy : cls == EYE ? OutlineBS1.zw : cls == CLOTH ? OutlineBS0.zw : OutlineBS0.xy;
        A = gradeDisplay(A, bs.x, bs.y) * 0.96;
    }

    vec3 N = normalize(viewNormal) * (gl_FrontFacing ? 1.0 : -1.0);
    if (OutlinePass > 0.5) N = normalize(viewNormal);
    // Iris (texels inside the profile's iris rectangle): the authored iris normals all point along the head's forward
    // axis (sample.vrm iris_01: (0,0,1) at every vertex), but YSM's import bends face-part normals, the iris
    // included, 85% toward an ellipsoid around the face (HumanoidImport.smoothFaces), which fans them out by up to
    // normal; diffuseN = shadeN tilted by the iris sphere, -0.125 x the matcap sphere, inside the circle only), the
    // iris is shaded from HeadForward instead of the mesh normal. FaceFlags.w = the profile has an iris rectangle.
    vec2 irisUV = (texCoord0 - IrisRect.xy) / max(IrisRect.zw, vec2(1e-6));
    bool inIris = cls == EYE && FaceFlags.w > 0.5 && OutlinePass < 0.5 && all(greaterThanEqual(irisUV, vec2(0.0)))
        && all(lessThanEqual(irisUV, vec2(1.0)));
    vec2 irisP = fract(irisUV) * 2.0 - 1.0;
    vec2 irisSxy = irisP * -HighlightVec.w;
    float irisSz = sqrt(clamp(1.0 - dot(irisP, irisP), 0.0, 1.0));
    // iris disc coverage, ~1 px soft edge (was a hard r < 1 cut for the sphere normal and the matcap)
    float irisR2 = dot(irisP, irisP);
    float irisIn = 1.0 - aaSmoothstep(1.0, 1.0, irisR2, min(footprint(irisR2), 0.5));
    // the iris params the sphere (matcap, diffuse tilt) uses the eye's own uv frame like E's shadeT/shadeB: the irises
    // share one uv square mirrored in u, so HeadRight/HeadUp mirrored one of them (and flipped both vertically)
    vec3 irisT;
    vec3 irisB;
    vec2 irisPUV = nprIrisParallax(irisUV, viewPos, texCoord0, HeadForward, irisT, irisB);
    vec3 irisSphereR = IrisParams.w > 0.5 && dot(irisT, irisT) > 0.5 ? irisT : HeadRight;
    vec3 irisSphereU = IrisParams.w > 0.5 && dot(irisB, irisB) > 0.5 ? irisB : HeadUp;
    vec2 uvDx = dFdx(texCoord0);
    vec2 uvDy = dFdy(texCoord0);
    vec2 irisAtlasUv = texCoord0;
    if (inIris && IrisParams.w > 0.5) {
        irisAtlasUv = nprIrisAtlasUv(irisPUV, IrisRect, vec2(textureSize(Sampler0, 0)));
        base = textureGrad(Sampler0, irisAtlasUv, uvDx, uvDy);
        A = base.rgb * vertexColor.rgb * ColorModulator.rgb;
    }
    vec3 shadeN = inIris ? HeadForward : N; // sphere basis for the matcap
    if (inIris) {
        N = normalize(mix(HeadForward,
            normalize(irisSphereR * (irisSxy.x * -0.125) + irisSphereU * (irisSxy.y * -0.125) + HeadForward * irisSz),
            irisIn));
    }
    // ---- NormalOutline (npr_normal.glsl): _BumpMap (cloth, body, face) / _SplitNormalMap RG (hair) on a per-pixel
    // cotangent frame. Ng keeps the mesh normal for the self-shadow receiver offset, the AO and debugView 1-2. Frame
    // and fetches in uniform control flow; not on the outline shell or the eyes (no maps; the iris normal above) ----
    vec3 Ng = N;
    mat3 nprFrame = nprCotangentFrame(N, viewPos, texCoord0);
    vec4 nprNormalTexel = nprAlignedMap(NPR_NORMAL, texCoord0);
    vec4 nprSplitTexel = nprAlignedMap(NPR_SPLIT_NORMAL, texCoord0);
    if (OutlinePass < 0.5 && cls != EYE) {
        N = nprMappedNormal(nprFrame, propMat, cls == HAIR, nprNormalTexel, nprSplitTexel);
    }
    vec3 V = normalize(-viewPos);
    vec3 L = normalize(LightDirView);
    vec3 cam = vec3(0.0, 0.0, 1.0);
    float NoV = clamp(dot(N, V), 0.0, 1.0);

    // sky exposure (MC adaptation for indirect-only places) times the pack's sun shadow map, at SceneShadow.x strength:
    // X = lerp(1, raw, strength) (EFL_SourceSceneShadowX); X drives the lit / indirect split like the game's
    float skyX = smoothstep(0.5, 0.93, skyBlock.x);
    float mapX = SceneShadow.x > 0.0 ? mix(1.0, sceneShadowRaw(), SceneShadow.x) : 1.0;
    float shadow = skyX * mapX;
    // character self-shadow Y: the property shadow of EFL_OfficialDiffuse (receiver = Y x ao, lit = min(Y, ramp.a));
    // (the profile's face rectangle, whatever the class map says) ignore face casters: the face shading is the SDF's.
    // They take Y only while the light is in front of the face (bangs on the forehead): with a sideways or back light
    // the SDF already shades the face, and the hair shell behind the head (the only caster left on the light ray
    // once the face itself is skipped) drew rings around the cheeks at low sun (seen in game, gone with Y off).
    bool faceReceiver = cls == FACE || inFaceRect;
    float occ = SelfShadowParams.x > 0.0 && OutlinePass < 0.5 && !inIris ? selfShadowY(Ng, L, faceReceiver) : 1.0;
    if (faceReceiver) {
        occ = mix(1.0, occ, smoothstep(0.0, 0.35, dot(L, HeadForward)));
    }
    // the scene's ambient occlusion: like the pack, it only scales the ambient (indirect) light
    float envAO = AoParams.x > 0.0 && OutlinePass < 0.5 ? sceneAO(Ng) : 1.0;

    // Face texture space: YSM packs face_01 affinely into the atlas (ModelProfiles), so the SDF set is addressed
    // through FaceRect; only FACE-class texels inside the rect use it (the brows share the region).
    vec2 faceUV = (texCoord0 - FaceRect.xy) / max(FaceRect.zw, vec2(1e-6));
    bool inFace = cls == FACE && FaceFlags.x > 0.5 && all(greaterThanEqual(faceUV, vec2(0.0)))
               && all(lessThanEqual(faceUV, vec2(1.0)));
    vec4 sdfMask = inFace ? texture(Sampler7, faceUV) : vec4(1.0, 1.0, 0.0, 1.0); // g = 1: geometric N.L
    // face SDF in the head's horizontal light frame, mirrored for the other side; read here, outside the per-texel
    // class branch, so its pixel footprint (threshold AA below) is defined
    vec2 faceL = normalize(vec2(dot(L, HeadRight), dot(L, HeadForward)) + 1e-5);
    float sdfValue = 0.0;
    float sdfWidth = 0.0;
    if (FaceFlags.x > 0.5) {
        vec4 sdf = texture(Sampler6, vec2(faceL.x > 0.0 ? faceUV.x : 1.0 - faceUV.x, faceUV.y));
        sdfValue = 0.5 * (sdf.r + sdf.g);
        sdfWidth = min(footprint(sdfValue), 0.25);
    }

    // face weight = grazing * mask.r * lerp(sat(headFront + .5), 1, mask.g) * lerp(FaceScale, SkinScale, mask.b)
    if ((cls == SKIN || cls == FACE) && OutlinePass < 0.5) {
        float edge = 1.0 - (NoV * 0.85 + 0.15);
        float scale = SkinRim.w;
        if (cls == FACE) {
            vec2 vh = normalize(vec2(dot(cam, HeadRight), dot(cam, HeadForward)) + 1e-5);
            float m = inFace ? sdfMask.g : 0.0;
            scale = (inFace ? sdfMask.r : 1.0) * mix(clamp(vh.y + 0.5, 0.0, 1.0), 1.0, m)
                  * mix(Globals2.y, Tuning.z, inFace ? sdfMask.b : 0.0);
        }
        // the game mixes linear albedo with the linear _SDFRimColor (a gamma-stored Unity colour); mixing the
        // display values instead doubles the red shift at grazing angles (Tuning.y = 0 keeps that 0.3.x path)
        float w = clamp(edge * scale, 0.0, 1.0);
        A *= Tuning.y > 0.5 ? pow(mix(vec3(1.0), srgbDecode(SkinRim.rgb), w), vec3(1.0 / 2.2))
                            : mix(vec3(1.0), SkinRim.rgb, w);
    }

    float smoothness = mat.x;
    float specLevel = mat.y;
    float metal = metalCode;
    if (cls == CLOTH && hasProp && !nprMaterialIsHair(propMat)) { // PropMaps: per-texel metal, specular, smoothness
        metal = clamp(prop.r * MapTuning.y, 0.0, 1.0);
        specLevel = prop.g;
        smoothness = prop.a;
    }
    float oneMinusR = 0.96 - 0.96 * metal;
    vec3 diff = A * oneMinusR;
    if (inIris) {
        diff *= nprIrisBright(1.0 - irisIn, irisAtlasUv);
    }
    vec3 F0 = mix(vec3(0.04 * specLevel), A, metal);
    // 1/2: shadow base colour = skin LUT (V flipped) or the brightness/saturation grade, in linear space
    vec3 shadowBase = skinLike && Globals2.w > 0.5 ? shadowLut(A) : gradeDisplay(A, mat.z, mat.w);
    vec3 S = shadowBase * oneMinusR * Globals0.w;
    float rough = 1.0 - smoothness;
    float a = max(rough * rough, 0.0078125);

    vec3 Lh = horiz(L);
    vec3 camH = horiz(cam);
    float backLit = clamp(-dot(Lh, camH), 0.0, 1.0);
    float pitchGate = smoothstep(0.25, 0.75, 1.0 - abs(dot(cam, WorldUp)));
    float NdotL = clamp(dot(N, L) + Globals1.z, -1.0, 1.0);
    if (cls == HAIR || cls == CLOTH) {
        float nl = dot(N, L);
        NdotL = clamp(nl + backLit * pitchGate * Globals1.w * (0.5 - 0.5 * nl * nl) + Globals1.z, -1.0, 1.0);
    }
    if (inFace) {
        // light in the head's horizontal frame; the SDF is authored for one side and mirrored for the other
        vec2 f = faceL;
        float wrapF = backLit * clamp(-f.y, 0.0, 1.0) * Globals1.w;
        float halfLight = 0.5 * clamp(mix(f.y, -0.5 * f.y * f.y + f.y + 0.5, wrapF) + Globals2.z, -1.0, 1.0);
        float t = clamp(0.5 - halfLight, 0.001, 0.999);
        float lo = max(2.0 * t - 1.0, 0.0);
        float hi = min(2.0 * t, 1.0);
        float response = abs(-aaSmoothstep(lo, hi, sdfValue, sdfWidth) - halfLight * ceil(halfLight));
        float geometric = clamp(dot(N, L) + Globals1.z, -1.0, 1.0) * 0.5 + 0.5;
        NdotL = mix(response, geometric, sdfMask.g) * 2.0 - 1.0; // rampU = lerp(response, geometric, mask.g)
    } else if (cls == FACE) { // 7: no SDF map -> head-horizontal sphere normal (EndfieldFaceMath.ush:37-42)
        vec3 hs = horiz(viewPos - HeadCenter);
        NdotL = clamp(dot(hs, L) + Globals1.z, -1.0, 1.0);
    }
    vec3 Ne = N;
    if (cls == EYE) { // 7: eyes/brows see the light projected onto the head's horizontal plane (Brow 136-183)
        vec3 Lp = normalize(HeadRight * dot(L, HeadRight) + HeadForward * dot(L, HeadForward) + 1e-5);
        NdotL = clamp(dot(N, Lp) + Globals1.z, -1.0, 1.0);
    }

    float rampU = NdotL * 0.5 + 0.5;
    vec4 ramp = rampRowAA(rdRow, rampU, footprint(rampU));
    // ramp chroma weight from the linear ramp texel, as the game samples it (sRGB texture)
    vec3 rampLin = srgbDecode(ramp.rgb);
    float rampSat = Tuning.y > 0.5 ? max3(rampLin) - min3(rampLin) : max3(ramp.rgb) - min3(ramp.rgb);
    float viewU = dot(Ne, cam) * 0.5 + 0.5;
    float viewW = footprint(viewU);
    float viewAlpha = (cls == HAIR || cls == CLOTH || cls == EYE) ? rampRowAA(rdRow, viewU, viewW).a : 1.0;
    float aoMask = occ * propB * viewAlpha;
    float litMask = min(ramp.a, min(occ, propB));

    vec3 amb = lightMapColor.rgb * Globals0.y;
    float ambLum = max(max3(amb), 1e-4);
    vec3 Lc = LightColor;
    vec3 lightCol = nprMainLight(amb, litMask, shadow, skyX, mapX, envAO); // npr_lights.glsl
    // local block lights (npr_lights.glsl): their share of the lightmap's block light leaves the ambient here
    vec3 localGain = localLightSplit(lightCol, amb, litMask, shadow, skyX, mapX, envAO);

    vec3 deep = gradeDisplay(S * 0.65, 1.0, 1.2);
    vec3 baseCol = mix(deep, S, clamp(aoMask + ramp.a, 0.0, 1.0));
    baseCol = mix(baseCol, diff, litMask);
    vec3 aoAlb = S + aoMask * (gradeDisplay(diff, 1.0, 1.2) - S);
    vec3 ramped;
    if (Tuning.y > 0.5) { // tint and luminance restore in linear (a product, so only the tint needs converting)
        vec3 tintLin = mix(vec3(1.0), rampLin, rampSat);
        vec3 baseLin = pow(max(baseCol, 0.0), vec3(2.2));
        float restore = clamp(lum(baseLin) / max(lum(baseLin * tintLin), 1e-4), 0.0, 2.44);
        ramped = baseCol * pow(tintLin * restore, vec3(1.0 / 2.2));
    } else {
        ramped = baseCol * mix(vec3(1.0), ramp.rgb, rampSat);
        ramped *= clamp(lum(baseCol) / max(lum(ramped), 1e-3), 0.0, 1.5);
    }
    vec3 albedoF = mix(aoAlb, ramped, shadow);
    vec3 diffuse = lightCol * albedoF;

    // ---- specular ----
    float lit = mix(aoMask, litMask, shadow);
    vec3 specLight = lightCol * mix(Globals0.w, 1.0, lit) * (lit * 0.5 + 0.5);
    vec3 spec = vec3(0.0);
    // the package's hairSpecMask.x or the hairGlossFallback config; A = hairSpecMask.y)
    vec4 hairProp = hasProp ? prop : vec4(0.0, HairSpec.x, 1.0, HairSpec.y);
    vec3 specN = OutlinePass < 0.5 ? nprHairSpecNormal(nprFrame, propMat, nprSplitTexel) : N; // NPR_HAIR_SPECN
    if (cls == HAIR) {
        vec3 D = normalize(HeadUp + HairAniso.w * HeadRight);
        vec3 Tb = -normalize(D - specN * dot(specN, D) + 1e-5); // N x (N x D) // NPR_HAIR_TB
        Tb = nprHairStrandBase(Tb, specN, D, hairProp.r, nprFrame); // NormalOutline: prop R blends in the uv tangent
        vec3 W = HeadRight * dot(V, HeadRight) + HeadForward * dot(V, HeadForward)
               + HeadUp * mix(0.5, dot(L, WorldUp), shadow);
        vec3 Hh = normalize(normalize(2.0 * W + shadow * L) + V);
        vec2 nh = normalize(vec2(dot(specN, HeadRight), dot(specN, HeadForward)) + 1e-5);
        vec2 vv = normalize(vec2(dot(V, HeadRight), dot(V, HeadForward)) + 1e-5);
        float vh = exp2(log2(max(clamp(dot(nh, vv), 0.0, 1.0), 1e-5)) * HairAniso.z);
        vec3 T1 = normalize(Tb + specN * (2.0 * HairAniso.x - 1.0));
        float lutU = clamp(strand(T1, Hh, 200.0) * hairProp.g, 0.0, 1.0);
        vec3 rawSpec = rampRow(6, lutU).rgb * lutU * vh;
        float peak = max3(rawSpec);
        vec3 T2 = normalize(Tb + specN * (2.0 * HairSpec.w - 1.0));
        float sec = strand(T2, Hh, floor(200.0 * max(1.0 - HairSpec.z, 0.0))) * vh;
        spec = specLight * (rawSpec * (0.04 * hairProp.g) * HairAniso.y * 5.0
             + sec * HairSpec2 * hairProp.a * (1.0 - clamp(peak, 0.0, 1.0))) * Globals2.x;
        // _LineAmount square wave, box-filtered over the pixel) darken the diffuse by _LineIntensity inside the
        // _LineValue/_LineRange Kajiya band, except under the primary highlight, weighted by gloss G; then
        // desaturate toward _LineSaturation. Rows: (amount, value, range, intensity), (saturation, useLineMap).
        if (nprMaterialHas(propMat, NPR_LINE)) {
            vec4 lp0 = nprMatParam(propMat, 0);
            vec4 lp1 = nprMatParam(propMat, 1);
            float w = max(lineW, 1e-4);
            vec2 xe = vec2(lineX - 0.5 * w, lineX + 0.5 * w);
            vec2 F = floor(xe) * 0.5 + max(fract(xe) - 0.5, 0.0); // integral of ceil(sat(frac(x) - .5))
            float stripe = clamp((F.y - F.x) / w, 0.0, 1.0);
            if (lp1.y > 0.5 && nprMaterialHasTiled(NPR_LINE, propMat)) {
                stripe = 1.0 - nprTiledSample(NPR_LINE, propMat, lineT, lineDx, lineDy).r;
            }
            vec3 Tl = normalize(Tb + specN * (2.0 * lp0.y - 1.0));
            float band = strand(Tl, Hh, floor(200.0 * max(1.0 - lp0.z, 0.0)));
            float lf = mix(mix(1.0 - lp0.w, 1.0, stripe), 1.0, clamp(peak, 0.0, 1.0));
            float lineFactor = 1.0 + hairProp.g * band * (lf - 1.0);
            vec3 dl = diffuse * lineFactor;
            diffuse = mix(vec3(lum(dl)), dl, mix(lp1.x, 1.0, lineFactor));
        }
    } else if (cls != EYE) {
        vec3 fill = camH + WorldUp * mix(0.5, dot(L, WorldUp), shadow);
        vec3 Hs = normalize(L * shadow + 2.0 * normalize(fill + 1e-5) + V * (2.0 + shadow));
        float NoH = dot(N, Hs);
        float a2 = a * a;
        float d = NoH * NoH * (a2 - 1.0) + 1.0;
        float Dg = a2 / max(d * d, 1e-7);
        float Vis = 0.5 / (2.0 * NoV + a + 1e-4);
        vec3 F = F0;
        if (cls == CLOTH) {
            F *= rampRow(5, clamp(Dg * (a2 + 1e-4), 0.0, 1.0)).rgb;
        }
        spec = clamp(Dg * Vis - 6.1e-5, 0.0, 20.0) * F * specLight * Globals2.x;
        if (cls == CLOTH && MapTuning.w > 0.0 && (hasProp || metal > 0.0)) {
            // Without it a metal texel (diffuse 0.96 (1 - metal)) is black but for its GGX highlight. MC has no
            // reflection probe: the radiance is the light reaching the surface (lightCol: lightmap/sky ambient + sun),
            // dimmer toward the ground. Only cloth with prop data or metal: other models keep their look.
            // MapTuning.w = envReflection
            vec3 Rv = reflect(-V, N);
            vec3 envRad = lightCol * mix(0.45, 1.0, smoothstep(-0.6, 0.6, dot(Rv, WorldUp)));
            spec += envRad * nprEnvBRDF(F0, NoV, rough) * mix(Globals0.w, 1.0, occ * propB) * MapTuning.w;
        }
    }
    vec3 color = diffuse + spec;
    vec3 clothParallax = nprClothParallax(cls == CLOTH && OutlinePass < 0.5, viewPos, N, texCoord0); // EyeBrow
    if (max3(clothParallax) > 0.0) { // cloth _ParallaxTex: linear and unlit, composed on the decoded colour
        color = pow(pow(max(color, 0.0), vec3(2.2)) + clothParallax, vec3(1.0 / 2.2));
    }

    if (inFace && FaceFlags.y > 0.5 && OutlinePass < 0.5 && !nprBrowRevealPass()) { // reveal pass: Sampler8 = receiver
        vec2 hlUV = faceUV + vec2(dot(V, HeadRight), dot(V, HeadUp)) * HighlightVec.xy;
        color += texture(Sampler8, hlUV).rgb * HighlightVec.z * specLight;
    }
    // The frame: the eye's uv frame from screen derivatives with the iris params (EyeBrow), else the head's right/up.
    if (inIris && FaceFlags.z > 0.5) {
        float inside = irisIn;
        vec3 matN = normalize(irisSphereR * irisSxy.x + irisSphereU * irisSxy.y + shadeN * irisSz);
        vec4 cap = texture(Sampler9, vec2(matN.x * 0.5 + 0.5, 0.5 - matN.y * 0.5)); // EFL_MatcapUV, view space
        color += (cap.rgb * MatcapColor.a + MatcapColor.rgb * cap.a) * inside * specLight;
    }

    if (Tuning.y > 0.5) { // on linear colour, as in the game (before its tonemap)
        vec3 lc = pow(max(color, 0.0), vec3(2.2));
        float cl = lum(lc);
        float kk = clamp(cl - 0.5, 0.0, 0.5);
        color = pow(max(mix(vec3(cl), lc, 1.0 + kk * kk * Tuning.x), 0.0), vec3(1.0 / 2.2));
    } else {
        float cl = lum(color);
        float kk = clamp(cl - 0.5, 0.0, 0.5);
        color = mix(vec3(cl), color, 1.0 + kk * kk * Tuning.x);
    }

    vec3 rimDir = normalize(cross(cam, vec3(RimParams.x, RimParams.y, 0.0)) + 1e-5);
    float rimX = 1.0 - abs(dot(V, N));
    float rimMask = aaSmoothstep(0.8 - 0.6 * RimParams.w, 0.9 - 0.4 * RimParams.w, rimX, footprint(rimX));
    if (cls == HAIR && RimDepth.x > 0.5) {
        rimMask = hairRimDepth(N, RimParams.w);
    }
    float rimOcc = min(clamp(dot(horiz(viewPos - HeadCenter), rimDir) + 1.0, 0.0, 1.0), propB); // PropMaps: x prop B
    vec3 rim = rimMask * RimColor.rgb * RimColor.a * rimOcc * clamp(dot(rimDir, N), 0.0, 1.0)
             * mix(vec3(0.25), diff, RimParams.z) * (0.35 + 0.65 * max(ambLum, lum(LightColor)));
    if (cls == FACE || cls == EYE || OutlinePass > 0.5) rim = vec3(0.0);

    float x = dot(Lh, N);
    float wrap = 0.5 + x - 0.5 * x * x;
    vec3 blCol = mix(amb * envAO / max(max3(amb) * 0.5, 1.0), Lc, shadow);
    float bl = clamp(mix(dot(N, WorldUp) * 0.5 + 0.5, wrap, shadow), 0.0, 1.0) * Globals1.w * mix(1.0, backLit, shadow)
             * smooth01((0.4 - abs(dot(V, N))) * 5.0) * mix(1.0, smooth01((lum(diff) - 0.1) * -16.666666), shadow);
    if (OutlinePass > 0.5) bl = 0.0;
    color += rim + blCol * bl * max(diff, vec3(0.15));
    vec3 emission = OutlinePass < 0.5 ? nprEmission(texCoord0) * MapTuning.x : vec3(0.0);
    if (max3(emission) > 0.0) {
        color = pow(pow(max(color, 0.0), vec3(2.2)) + emission, vec3(1.0 / 2.2));
    }
    // local block lights, NPR Type0 Default (npr_lights.glsl; after rim and emission as in the game's stage order)
    color += localLights(localGain, albedoF, N, V, cls, F0, a, shadow, sdfMask.g);

    color *= Globals0.x;
    if (cls == EYE && OutlinePass < 0.5) {
        color *= Tuning.w;
        // eyeNightGlow: a little self-lit iris by night (iris disc only, not the brows), linear, after exposure, so a
        // dark night or cave adaptation does not swallow the eyes
        if (EyeGlow > 0.0 && inIris) {
            vec3 glow = pow(max(A, 0.0), vec3(2.2)) * (EyeGlow * irisIn);
            color = pow(pow(max(color, 0.0), vec3(2.2)) + glow, vec3(1.0 / 2.2));
        }
    }
    if (Display.x > 0.5) {
        color = displayTransform(color);
    }
    color = mix(overlayColor.rgb, color, overlayColor.a);
    // glass/water in front of her (full scene depth nearer than this fragment, which passed the opaque-depth test):
    // she shows through at TranslucentCover.y over the pack's translucent image instead of leaving a prepass ghost
    float opacity = 1.0;
    if (TranslucentCover.x > 0.5) {
        vec2 size = vec2(textureSize(SceneDepth, 0));
        float front = texelFetch(SceneDepth, ivec2(floor(gl_FragCoord.xy * size / max(ScreenSize, vec2(1.0)))), 0).r;
        opacity = front < gl_FragCoord.z - 2e-5 ? TranslucentCover.y : 1.0;
    }
    fragColor = vec4(color, opacity);
    fragReceiver = vec4(vec3(receiverClass), 1.0);
    if (DebugView > 0.5 && OutlinePass < 0.5) {
        vec3 flatN = normalize(cross(dFdx(viewPos), dFdy(viewPos)));
        fragColor = DebugView < 1.5 ? vec4(Ng * 0.5 + 0.5, 1.0)
                  : DebugView < 3.5 ? vec4(vec3(dot(DebugView < 2.5 ? Ng : flatN, L) * 0.5 + 0.5), 1.0)
                  : DebugView < 4.5 ? vec4(ramp.a, viewAlpha, lum(rim + blCol * bl * max(diff, vec3(0.15))), 1.0)
                  : DebugView < 5.5 ? vec4(occ, envAO, mapX, 1.0)
                  : DebugView < 6.5 ? vec4(N * 0.5 + 0.5, 1.0) // NormalOutline: the normal-mapped N
                  : vec4(vec3(dot(N, L) * 0.5 + 0.5), 1.0);   // and its raw N.L
    }
}
