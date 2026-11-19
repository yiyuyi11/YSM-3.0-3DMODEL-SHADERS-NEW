#version 150
// =====================================================================================
//  Endfield (HGRP/CharacterNPR*) character lighting -- GLSL 150 port of the D3D11 bytecode
//  reconstruction in NPR-reconstruction.md.  Lighting only: no fog, no probes, no rain/snow,
//  no dissolve, no tiled punctual-light loop.  Line refs: S=Skin_ps_完整反汇编.asm,
//  F=..._Skin_..._8824968.asm (face/SDF), H=Hair_ps_完整反汇编.asm, H2=..._Hair_..._15471456.asm,
//  C2=decompiled/Gens2_9372380.glsl (cloth spec ramp).
//  All vectors are WORLD space (camera-relative positions are fine: only differences are used).
// =====================================================================================

in vec3 vPos;        // world (or camera-relative) position
in vec3 vNormal;     // world normal
in vec4 vTangent;    // world tangent, w = bitangent sign
in vec2 vUV;
in vec4 vColor;      // vertex tint (multiplied into base color)

out vec4 fragColor;

// ---- textures -----------------------------------------------------------------------
uniform sampler2D uBaseMap;        // _BaseMap   (Skin: .a = baked occlusion mask, NOT alpha)
uniform sampler2D uBumpMap;        // _BumpMap   (x = r*a, y = g)
uniform sampler2D uDiffRamp;       // _DiffRampMap 256x1  (rgb = colour, a = lit mask)   use CLAMP
uniform sampler2D uSpecRamp;       // _SpecRampMap 256x256 or 256x1                     use CLAMP
uniform sampler2D uShadowLut;      // _ShadowLutTex 1024x32 (32 slices of 32x32)        use CLAMP, linear
uniform sampler2D uSDFLightmap;    // _SDFLightmap  (r+g = threshold, b = pseudo normal) use CLAMP
uniform sampler2D uSDFMask;        // _SDFMask (x fresnel-tint mask, y non-face weight, z rim/AO select)
uniform sampler2D uMetallicGloss;  // _MetallicGlossMap (cloth: r metal, g spec, b occlusion, a smooth; hair: g spec mask)
uniform sampler2D uLineMap;        // hair _LineMap
uniform sampler2D uSplitNormal;    // hair _SplitNormalMap (spec normal), x = r*a, y = g

// ---- material type / feature switches ------------------------------------------------
const int MAT_BODY = 0;
const int MAT_FACE = 1;
const int MAT_HAIR = 2;
const int MAT_CLOTH = 3;
uniform int  uMaterial;
uniform bool uUseBumpMap;
uniform bool uUseShadowLut;
uniform bool uUseSpecRamp;
uniform bool uUseMetallicGloss;
uniform bool uUseSDF;
uniform bool uUseLineMap;
uniform bool uUseSplitNormal;
uniform bool uAlphaIsOcclusion;    // true for Skin (body/face) base maps

// ---- UnityPerMaterial (cb6) -----------------------------------------------------------
uniform vec4  uBaseColor;              // cb6[6]
uniform float uSmoothness;             // cb6[0].x
uniform float uSpecular;               // cb6[0].y
uniform float uMetallic;               // cb6[0].z
uniform float uBumpScale;              // cb6[0].w
uniform float uBackFaceNormalFlip;     // cb6[1].y
uniform float uShadowColorBrightness;  // cb6[4].z
uniform float uShadowColorSaturation;  // cb6[4].w
uniform float uSkinRimOffScale;        // cb6[12].x
uniform float uFaceRimOffScale;        // cb6[12].y
uniform vec3  uSDFRimColor;            // cb6[13]
uniform float uSpecRampIridescentMode; // cloth cb[1].x
// hair (cb6[12..18])
uniform float uSpecBumpScale;
uniform float uAnisotropyValue;
uniform float uAnisotropyIntensity;
uniform float uAnisotropyEdgeFade;
uniform float uAnisotropyDirX;
uniform float uLineAmount;
uniform float uLineValue;
uniform float uLineRange;
uniform float uLineIntensity;
uniform float uLineSaturation;
uniform vec4  uLineMapST;
uniform vec4  uEmissionColor;           // cb6[7] (rgb, brightness folded in)

// ---- scene / camera / head ------------------------------------------------------------
uniform vec3  uCamPos;          // same space as vPos
uniform vec3  uCamToward;       // cb0[6]: unit vector pointing TOWARD the camera (camera back axis)
uniform vec3  uCamRight;
uniform vec3  uCamUp;
uniform vec3  uLightDir;        // unit vector toward the main light (sun/moon)
uniform vec3  uLightColor;      // linear colour * intensity
uniform float uShadow;          // main-light visibility (shadow map) 0..1, 1 if none
uniform vec3  uAmbientColor;    // linear ambient (sky light * lightmap)
uniform vec3  uHeadRight;       // head bone axes (cb2[id+0..2]) -- required for SDF + hair
uniform vec3  uHeadUp;
uniform vec3  uHeadForward;
uniform vec3  uHeadCenter;      // head (or character) centre, same space as vPos

// ---- _CharacterParams (cb1[110..126]); real game values unknown -> tunables ------------
uniform float uCPLightScale;      // CP0.y  direct-light multiplier          (default 1.0)
uniform float uCPShadowScale;     // CP0.z  shadow albedo / min-lit factor   (default 1.0)
uniform float uCPShadowAmbScale;  // CP0.w  shadow-side ambient multiplier   (default 1.0)
uniform float uCPAmbBoostMode;    // CP1.x                                   (default 0.0)
uniform float uCPAmbToWhite;      // CP1.y  lit areas: ambient hue -> white  (default 1.0)
uniform float uCPNdotLBias;       // CP11.w*CP12.x                           (default 0.0)
uniform float uCPBacklight;       // 1-CP12.x  backlight wrap / edge light   (default 1.0)
uniform vec4  uCPRimColor;        // CP8  rgb, a = intensity                 (default (1,1,1,0.5))
uniform vec4  uCPRim;             // CP9  xy screen dir, z albedo tint, w width (default (1,0.3,0.5,0.5))
uniform float uCPSpecScale;       // CP13.w                                  (default 1.0)
uniform vec4  uCPFaceRim;         // CP14 rgb, a = intensity (SDF face rim)   (default (1,0.8,0.7,0))
uniform float uCPFaceRimThresh;   // CP15.z                                  (default 0.5)
uniform float uCPFaceRimSuppress; // CP16.z  normal-rim suppression on face  (default 1.0)
uniform float uCPSDFBias;         // CP16.w*CP1.w                             (default 0.0)
uniform float uExposure;          // cb1[27].y                               (default 1.0)

// =====================================================================================
const vec3 LUM = vec3(0.212673, 0.715152, 0.072175);

float lum(vec3 c)               { return dot(c, LUM); }
float max3(vec3 c)              { return max(c.r, max(c.g, c.b)); }
float min3(vec3 c)              { return min(c.r, min(c.g, c.b)); }
vec3  sat12(vec3 c)             { float l = lum(c); return mix(vec3(l), c, 1.2); }     // S 1400-1402
float smooth01(float x)         { x = clamp(x, 0.0, 1.0); return x * x * (3.0 - 2.0 * x); }
vec3  srgbEncode(vec3 c) {
    vec3 lo = c * 12.92;
    vec3 hi = 1.055 * pow(abs(c), vec3(0.416667)) - 0.055;
    return clamp(mix(hi, lo, vec3(lessThanEqual(c, vec3(0.003131)))), 0.0, 1.0);
}

// F 184-200: Unity 2D colour LUT, 32 slices along U.
vec3 shadowLut(vec3 albedo) {
    vec3 c = srgbEncode(albedo);
    float s = c.b * 31.0;
    float sl = floor(s);
    vec2 uv = vec2(sl / 32.0 + c.r * 0.030273 + 0.000488, c.g * 0.96875 + 0.015625);
    vec3 a = texture(uShadowLut, uv).rgb;
    vec3 b = texture(uShadowLut, uv + vec2(0.03125, 0.0)).rgb;
    return mix(a, b, s - sl);
}

vec3 decodeNormalXY(vec4 t, float scale) {
    vec2 xy = vec2(t.r * t.a, t.g) * 2.0 - 1.0;
    float z = sqrt(max(1.0 - min(dot(xy, xy), 1.0), 0.0));
    return vec3(xy * scale, z);
}

// Kajiya-Kay style sin(T,H)^n with the bytecode's clamps (H 1740-1746)
float strand(vec3 T, vec3 H, float n) {
    float th = dot(T, H);
    return exp2(log2(max(sqrt(max(1.0 - th * th, 0.0)), 1e-4)) * n);
}

// =====================================================================================
void main() {
    vec4  baseTex = texture(uBaseMap, vUV);
    vec3  A = baseTex.rgb * uBaseColor.rgb * vColor.rgb;
    float occ = uAlphaIsOcclusion ? baseTex.a : 1.0;
    float alpha = uAlphaIsOcclusion ? uBaseColor.a * vColor.a : baseTex.a * uBaseColor.a * vColor.a;

    float metal = uMetallic;
    float specLevel = uSpecular;
    float smoothness = uSmoothness;
    float glossMask = 1.0;
    if (uUseMetallicGloss) {
        vec4 mg = texture(uMetallicGloss, vUV);
        if (uMaterial == MAT_CLOTH) {           // C 135, 1624-1625
            metal *= mg.r; specLevel *= mg.g; occ *= mg.b; smoothness *= mg.a;
        } else {
            glossMask = mg.g;                   // hair gloss mask [inferred]
        }
    }

    // ---- normals (S 115-129, 186-196) --------------------------------------------------
    vec3 Ng = normalize(vNormal);
    float faceSign = gl_FrontFacing ? 1.0 : (uBackFaceNormalFlip * 2.0 - 1.0);
    vec3 T0 = normalize(vTangent.xyz - Ng * dot(Ng, vTangent.xyz));
    vec3 B0 = cross(Ng, T0) * (vTangent.w < 0.0 ? -1.0 : 1.0);
    vec3 N = Ng;
    if (uUseBumpMap) {
        vec3 tn = decodeNormalXY(texture(uBumpMap, vUV), uBumpScale);
        N = normalize(T0 * tn.x + B0 * tn.y + Ng * tn.z);
    }
    N *= faceSign;
    vec3 Nspec = N;
    if (uUseSplitNormal) {                       // H 1208-1220
        vec3 sn = decodeNormalXY(texture(uSplitNormal, vUV), uSpecBumpScale);
        Nspec = normalize(T0 * sn.x + B0 * sn.y + Ng * sn.z) * faceSign;
    }

    vec3 V = normalize(uCamPos - vPos);
    vec3 L = normalize(uLightDir);
    vec3 cam = normalize(uCamToward);
    float NoV = clamp(dot(N, V), 0.0, 1.0);
    float shadow = clamp(uShadow, 0.0, 1.0);
    float ssao = 1.0;

    // ---- SDF mask (F 201) ----------------------------------------------------------------
    vec4 sdfMask = vec4(1.0, 1.0, 0.0, 1.0);    // y = 1 -> "not face" (normal lambert)
    if (uMaterial == MAT_FACE && uUseSDF) sdfMask = texture(uSDFMask, vUV);
    float nonFace = sdfMask.y;

    // ---- skin edge tint (_SkinRimOff / _SDFRimColor) S 478-485, F 473-486 ------------------
    if (uMaterial == MAT_BODY || uMaterial == MAT_FACE) {
        float edge = 1.0 - (NoV * 0.85 + 0.15);
        float scale = (uMaterial == MAT_FACE) ? mix(uFaceRimOffScale, uSkinRimOffScale, sdfMask.z) * sdfMask.x
                                              : uSkinRimOffScale;
        if (uMaterial == MAT_FACE) {            // stronger when seen frontally (F 473-476)
            vec2 vh = normalize(vec2(dot(cam, uHeadRight), dot(cam, uHeadForward)) + 1e-5);
            scale *= mix(clamp(vh.y + 0.5, 0.0, 1.0), 1.0, nonFace);
        }
        float k = clamp(edge * scale, 0.0, 1.0);
        A *= mix(vec3(1.0), uSDFRimColor, k);
    }

    // ---- PBR-ish parameters (S 1317-1324) ------------------------------------------------
    float oneMinusR = 0.96 - 0.96 * metal;
    vec3  diff = A * oneMinusR;
    vec3  F0 = mix(vec3(0.04 * specLevel), A, metal);
    vec3  shadowBase;
    if (uUseShadowLut) shadowBase = shadowLut(A);                                         // F 184-200
    else { vec3 c = A * uShadowColorBrightness; float l = lum(c); shadowBase = mix(vec3(l), c, uShadowColorSaturation); } // S 217-220
    vec3  S = shadowBase * oneMinusR * uCPShadowScale;
    float rough = 1.0 - smoothness;
    float a = max(rough * rough, 0.0078125);

    // ---- main N.L (+ face SDF, hair/cloth back-light wrap) ---------------------------------
    float NdotL = clamp(dot(N, L) + uCPNdotLBias, -1.0, 1.0);
    vec2  Lxz = normalize(vec2(L.x, L.z) + 1e-5);
    vec2  camXZ = normalize(vec2(cam.x, cam.z) + 1e-5);
    float backLit = clamp(-dot(Lxz, camXZ), 0.0, 1.0);

    if (uMaterial == MAT_HAIR || uMaterial == MAT_CLOTH) {                                // H 1630-1649
        float nl = dot(N, L);
        float vert = smooth01(2.0 * (0.75 - abs(cam.y)));
        float b = backLit * vert * uCPBacklight;
        NdotL = clamp(nl + b * (0.5 - 0.5 * nl * nl) + uCPNdotLBias, -1.0, 1.0);
    }

    float sdfSum = 0.0;
    if (uMaterial == MAT_FACE && uUseSDF) {                                               // F 715-806
        vec2 f = normalize(vec2(dot(L, uHeadRight), dot(L, uHeadForward)) + 1e-5);
        bool flip = f.x > 0.0;
        vec4 sdf = texture(uSDFLightmap, vec2(flip ? vUV.x : 1.0 - vUV.x, vUV.y));
        sdfSum = sdf.r + sdf.g;
        float back = backLit * clamp(-f.y, 0.0, 1.0) * uCPBacklight;
        float ang = clamp(f.y + back * (0.5 - 0.5 * f.y * f.y) + uCPSDFBias, -1.0, 1.0);
        float t = clamp(0.5 - 0.5 * ang, 0.001, 0.999);
        float lo = max(2.0 * t - 1.0, 0.0);
        float hi = min(2.0 * t, 1.0);
        float k = smooth01((sdfSum * 0.5 - lo) / max(hi - lo, 1e-4));
        float faceNL = 2.0 * abs(k + (ang > 0.0 ? ang * 0.5 : 0.0)) - 1.0;
        NdotL = mix(faceNL, NdotL, nonFace);
    }

    vec4  ramp = texture(uDiffRamp, vec2(NdotL * 0.5 + 0.5, 0.5));                         // S 1366-1368
    float rampSat = max3(ramp.rgb) - min3(ramp.rgb);
    float aoMask = occ * ssao;
    float litMask = min(ramp.a, min(occ, ssao));
    if (uMaterial == MAT_HAIR) {                                                          // H 1657-1663
        float viewA = texture(uDiffRamp, vec2(dot(N, cam) * 0.5 + 0.5, 0.5)).a;
        aoMask = occ * viewA;
        litMask = min(ramp.a, min(occ, 1.0));
    }

    // ---- light colour (S 1377-1399) -----------------------------------------------------------
    float ambLum = max3(uAmbientColor);
    vec3  ambHue = uAmbientColor / max(ambLum, 1e-4);
    vec3  ambL = mix(ambHue, vec3(1.0), litMask * uCPAmbToWhite);
    float kShadow = mix(min(ambLum * 0.35 + 0.65, 1.5), clamp(ambLum, 1.25, 1.75), uCPAmbBoostMode);
    vec3  shadowAmb = ambL * kShadow * uCPShadowAmbScale;
    vec3  Lc = uLightColor;
    vec3  LcDesat = mix(vec3(lum(Lc)), Lc, litMask);
    vec3  lightCol = mix(shadowAmb, (ambL * clamp(ambLum, 0.0, 1.5) + LcDesat) * uCPLightScale, shadow);

    // ---- albedo selection + ramp tint (S 1400-1424) ---------------------------------------------
    vec3 deep   = sat12(S * 0.65);
    vec3 base   = mix(deep, S, clamp(aoMask + ramp.a, 0.0, 1.0));
    base        = mix(base, diff, litMask);
    vec3 ramped = base * mix(vec3(1.0), ramp.rgb, rampSat);
    vec3 aoAlb  = S + aoMask * (sat12(diff) - S);
    ramped     *= clamp(lum(base) / max(lum(ramped), 1e-3), 0.0, 1.5);
    vec3 albedoF = mix(aoAlb, ramped, shadow);
    vec3 diffuse = lightCol * albedoF;

    // ---- specular light (S 1425-1431) -----------------------------------------------------------
    float lit = mix(aoMask, litMask, shadow);
    vec3  specLight = lightCol * mix(uCPShadowScale, 1.0, lit) * (lit * 0.5 + 0.5);

    vec3 spec = vec3(0.0);
    if (uMaterial == MAT_HAIR) {
        // ---- hair: view-locked anisotropic highlight (H 1718-1792, H2 1667-1696) ---------------
        vec3 D = normalize(uHeadUp + uAnisotropyDirX * uHeadRight);
        vec3 T = normalize(D - Nspec * dot(Nspec, D));
        vec3 W = uHeadRight * dot(V, uHeadRight) + uHeadForward * dot(V, uHeadForward)
               + uHeadUp * mix(0.5, L.y, shadow);
        vec3 Hh = normalize(normalize(2.0 * W + shadow * L) + V);
        vec2 nh = normalize(vec2(dot(N, uHeadRight), dot(N, uHeadForward)) + 1e-5);
        vec2 vh = normalize(vec2(dot(V, uHeadRight), dot(V, uHeadForward)) + 1e-5);
        float facing = exp2(log2(max(clamp(dot(nh, vh), 0.0, 1.0), 1e-5)) * uAnisotropyEdgeFade);
        vec3 T1 = normalize(T + Nspec * (2.0 * uAnisotropyValue - 1.0));
        float s1 = strand(T1, Hh, 200.0) * facing;
        vec3 ring = vec3(s1);
        if (uUseSpecRamp) {
            float u = clamp(glossMask * s1, 0.0, 1.0);
            float v = facing * facing * (dot(T1, Hh) > 0.0 ? 1.0 : 0.0);
            ring = texture(uSpecRamp, vec2(u, v)).rgb * u * facing;
        }
        spec = specLight * ring * (0.04 * uSpecular) * uAnisotropyIntensity * 5.0 * uCPSpecScale;

        // hair strand lines: multiplicative band on the diffuse (H 1750-1792)
        float stripe = ceil(max(fract(vUV.x * uLineAmount) - 0.5, 0.0));
        float lineTex = uUseLineMap ? 1.0 - texture(uLineMap, vUV * uLineMapST.xy + uLineMapST.zw).r : stripe;
        float m = uUseLineMap ? lineTex : stripe;
        vec3 T2 = normalize(T + Nspec * (2.0 * uLineValue - 1.0));
        float band = strand(T2, Hh, floor(200.0 * (1.0 - uLineRange)));
        float lineMul = 1.0 + uSpecular * (mix(1.0 - uLineIntensity, 1.0, m) - 1.0) * band;
        vec3 dl = diffuse * lineMul;
        diffuse = mix(vec3(lum(dl)), dl, mix(uLineSaturation, 1.0, lineMul));
    } else {
        // ---- stylised GGX with view-dominant half vector (S 1432-1462, C2 1681-1716) ------------
        vec3 Hs = normalize(L * shadow
                            + 2.0 * normalize(vec3(cam.x, mix(0.5, L.y, shadow), cam.z))
                            + V * (2.0 + shadow));
        float NoH = dot(N, Hs);
        float a2 = a * a;
        float d = NoH * NoH * (a2 - 1.0) + 1.0;
        float Dg = a2 / max(d * d, 1e-7);
        float Vis = 0.5 / (2.0 * NoV + a + 1e-4);
        float DV = clamp(Dg * Vis - 6.1e-5, 0.0, 20.0);
        vec3 F = F0;
        if (uUseSpecRamp) {
            float u = mix(clamp(Dg * a2, 0.0, 1.0), NoV * NoV, uSpecRampIridescentMode);
            F *= texture(uSpecRamp, vec2(u, rough)).rgb;
        }
        spec = DV * F * specLight * uCPSpecScale;
    }

    vec3 color = diffuse + spec;

    // ---- highlight saturation boost (S 1495-1522) --------------------------------------------------
    float cl = lum(color);
    float kk = clamp(cl - 0.5, 0.0, 0.5);
    color = mix(vec3(cl), color, 1.0 + kk * kk);

    // ---- rim (S 1499-1541) ---------------------------------------------------------------------------
    vec3 screenDir = uCamRight * uCPRim.x + uCamUp * uCPRim.y;
    vec3 rimDir = normalize(cross(screenDir, cam) + 1e-5);
    float rimMask = smoothstep(0.8 - 0.6 * uCPRim.w, 0.9 - 0.4 * uCPRim.w, 1.0 - abs(dot(V, N)));
    vec2 fromCenter = normalize(vec2(vPos.x - uHeadCenter.x, vPos.z - uHeadCenter.z) + 1e-5);
    float rimOcc = min(min(occ, ssao), clamp(dot(vec3(fromCenter.x, 0.0, fromCenter.y), rimDir) + 1.0, 0.0, 1.0));
    vec3 rim = rimMask * uCPRimColor.rgb * uCPRimColor.a * rimOcc
             * clamp(dot(rimDir, N), 0.0, 1.0) * mix(vec3(0.25), diff, uCPRim.z);
    if (uMaterial == MAT_FACE) rim *= 1.0 - uCPFaceRimSuppress * (1.0 - nonFace);         // F 1001

    // ---- back-light / dark-silhouette lift (S 1542-1579) -----------------------------------------------
    float x = dot(vec3(Lxz.x, 0.0, Lxz.y), N);
    float wrap = 0.5 + x - 0.5 * x * x;
    vec3  blCol = mix(ambHue * min(ambLum, 1.0), Lc / max(max3(Lc) * 0.5, 1.0), shadow);
    float bl = clamp(mix(dot(N, vec3(0.0, 1.0, 0.0)), wrap, shadow), 0.0, 1.0)
             * uCPBacklight * mix(1.0, backLit, shadow)
             * smooth01((0.4 - abs(dot(V, N))) * 5.0)
             * min(occ, ssao)
             * mix(1.0, smooth01((lum(diff) - 0.1) * -16.666666), shadow);
    color += rim + blCol * bl * max(diff, vec3(0.15));

    // ---- SDF face rim (F 1001-1024) -------------------------------------------------------------------
    if (uMaterial == MAT_FACE && uUseSDF) {
        float t = clamp(0.5 - 0.5 * uCPFaceRimThresh, 0.001, 0.999);
        float lo = max(2.0 * t - 1.0, 0.0);
        float hi = min(2.0 * t, 1.0);
        float k = smooth01((sdfSum * 0.5 - lo) / max(hi - lo, 1e-4));
        float h = 0.5 * uCPFaceRimThresh;
        float r = smooth01(2.0 * abs(k + (h > 0.0 ? h : 0.0)) - 0.5);
        color += r * (1.0 - nonFace) * uCPFaceRim.rgb * uCPFaceRim.a * diff;
    }

    color += uEmissionColor.rgb;
    fragColor = vec4(color * uExposure, alpha);
}
