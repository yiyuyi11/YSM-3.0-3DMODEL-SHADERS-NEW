// =====================================================================================================
// npr_lights.glsl (imported by npr_character.fsh after its helpers): the main light colour, local block lights and the
// hair depth rim.
//
// lights can evaluate it twice: with the full lightmap ambient and with the block-light share taken out.
//
// Local lights (LocalLights.java): up to 4 light-emitting blocks near a FULL character, shaded like the game's NPR
// 300-349 surface / 249-269 hair / 362-384 eye / 208-215 face; added after rim and emission, Stages/Cloth.ush stage 16):
//   color * weight * DefaultDiffuse * lerp(back * BackContrast, 1, sat(N.L + 0.5)), back = 0.25 cloth/hair/eye,
//   0.5 skin/face, limited by 1 / max(1, max3(color * weight) * lerp(0.75, 0.5, 1 - mainShadow)); BackContrast and
//   BrightLimit at their defaults (1, 1); plus the additional GGX on cloth/skin/face (hair and eye: diffuse only here).
// MC has no light entities and its block light is already in the lightmap ambient, so the lights do not add energy:
// the lightmap's block-light share at her eyes, LM(block, sky) - LM(0, sky), times LocalParams.y (re-aimed share) times
// the part of the block level the found lights explain, is taken out of the ambient (through nprMainLight, so its
// non-linear MC adaptation is honoured) and handed to the lights, each with its share of the brightness at her eyes,
// MC's block-light falloff (level = emission - Manhattan distance in world blocks, brightness f / (4 - 3f)) relative to
// her eyes, and divided by the sphere mean of the Type0 profile, 0.5 (1 + back): averaged over all normals, a light
// returns exactly the ambient it took (LocalParams.z = 1).
//
// EndfieldPunctualLighting.ush:600-657, EndfieldOutlineMath.ush:172): the CP8 rim gate is
// smoothstep(0.1, 0.2, depth(screenUV + normalize(N_view.xy) * (h/w, 1) * rimWidth * 0.006) - pixel depth), the scene
// depth at a point offset along the screen normal, against this pixel's view depth (m = blocks). The depth must hold
// her own depth (the pack depth with her gbuffer prepass), so the rim lights the silhouette against what lies behind
// it, not the inside of the hair.
// =====================================================================================================

uniform sampler2D Sampler2;  // vanilla lightmap (also read by the vertex stage): x block level, y sky level
uniform vec4 LocalParams;    // x light count (0 = off), y block-light share re-aimed (localLightShare x the part of
                             // her block level the lights explain, LocalLights.java), z intensity, w specular scale
uniform mat4 LocalPos;       // column i: light i (block centre) in view space, w = its emission level
uniform mat4 LocalTint;      // column i: rgb tint (1,1,1 = the lightmap's own colour), w = its level at her eyes
uniform vec4 RimDepth;       // x 1 = hair rim gated by SceneDepth, yz smoothstep range (blocks)

vec3 nprMainLight(vec3 amb, float litMask, float shadow, float skyX, float mapX, float envAO) {
    float ambLum = max(max3(amb), 1e-4);
    vec3 skyHue = SkyColor / max(max3(SkyColor), 1e-4);
    vec3 ambHue = mix(amb / ambLum, mix(vec3(1.0), skyHue, 0.45), clamp(skyBlock.x, 0.0, 1.0));
    vec3 ambL = mix(ambHue, vec3(1.0), litMask * Globals1.y);
    float kShadow = min(ambLum * 0.35 + 0.65, 1.5);
    // * ambLum: MC adaptation (dark at night/indoors); in the pack's sun shadow under open sky the sky-light lightmap
    // overstates the ambient (it does not see the canopy), so it keeps SceneShadow2.x of it
    vec3 shadowAmb = ambL * kShadow * Globals1.x * ambLum * envAO * mix(1.0, mix(SceneShadow2.x, 1.0, mapX), skyX);
    vec3 LcDesat = mix(vec3(lum(LightColor)), LightColor, litMask);
    return mix(shadowAmb, (ambL * clamp(ambLum, 0.0, 1.5) * 0.35 * envAO + LcDesat) * Globals0.z, shadow);
}

// vanilla LightTexture.getBrightness (overworld): f / (4 - 3f), f = level / 15
float mcBrightness(float level) {
    float f = clamp(level / 15.0, 0.0, 1.0);
    return f / (4.0 - 3.0 * f);
}

int localCount() {
    return OutlinePass > 0.5 ? 0 : min(int(LocalParams.x + 0.5), 4);
}

// Takes the local lights' share out of lightCol (computed from the full ambient amb) and returns it: the light colour
// the local lights re-aim. vec3(0) without local lights, so lightCol stays exactly as before.
vec3 localLightSplit(inout vec3 lightCol, vec3 amb, float litMask, float shadow, float skyX, float mapX, float envAO) {
    int n = localCount();
    if (n == 0) {
        return vec3(0.0);
    }
    vec3 noBlock = texelFetch(Sampler2, ivec2(0, int(skyBlock.x * 15.0 + 0.5)), 0).rgb;
    vec3 blockShare = max(lightMapColor.rgb - noBlock, 0.0) * Globals0.y;
    vec3 rest = nprMainLight(amb - blockShare * clamp(LocalParams.y, 0.0, 1.0), litMask, shadow, skyX, mapX, envAO);
    vec3 gain = max(lightCol - rest, 0.0);
    lightCol = rest;
    return gain;
}

// The local lights' colour for this texel (added after rim and emission, before exposure). gain: localLightSplit;
// albedo: the prepared diffuse before the main light (the game's DefaultDiffuse); maskG: the face SDF mask's geometric
// weight (1 off the face).
vec3 localLights(vec3 gain, vec3 albedo, vec3 N, vec3 V, int cls, vec3 F0, float alpha, float shadow, float maskG) {
    int n = localCount();
    if (n == 0 || max3(gain) <= 0.0) {
        return vec3(0.0);
    }
    float back = (cls == SKIN || cls == FACE) ? 0.5 : 0.25;
    float meanF = 0.5 * (1.0 + back); // sphere mean of lerp(back, 1, sat(N.L + 0.5))
    // face: the back-contrast normal is the head's horizontal hemisphere normal blended to N by the mask's G
    // (EndfieldPunctualLighting.ush:211-213)
    vec3 Nb = cls == FACE ? normalize(mix(horiz(viewPos - HeadCenter), N, maskG) + 1e-5) : N;
    bool specOn = cls != HAIR && cls != EYE;
    float total = 0.0;
    for (int i = 0; i < 4; i++) {
        if (i < n) {
            total += mcBrightness(LocalTint[i].w);
        }
    }
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        if (i >= n) {
            break;
        }
        vec4 P = LocalPos[i];
        vec4 T = LocalTint[i];
        vec3 toL = P.xyz - viewPos;
        vec3 w = abs(ViewToPlayer * toL); // world axes: MC light falls off by Manhattan block steps
        float atEyes = mcBrightness(T.w);
        float falloff = min(mcBrightness(P.w - (w.x + w.y + w.z)) / max(atEyes, 1e-4), 2.0);
        vec3 c = gain * T.rgb * (atEyes / max(total, 1e-4) * falloff / meanF);
        c /= max(1.0, max3(c) * mix(0.75, 0.5, 1.0 - shadow)); // BrightLimit 1
        float d2 = dot(toL, toL);
        vec3 L = d2 > 1e-8 ? toL * inversesqrt(d2) : vec3(0.0);
        sum += c * albedo * mix(back, 1.0, clamp(dot(Nb, L) + 0.5, 0.0, 1.0));
        if (specOn) { // EFL_SourceGGXAdditional, roughness lerp 0, times N.L (mixWeight)
            vec3 H = normalize(L + V + 1e-5);
            float noH = dot(N, H);
            float a2 = alpha * alpha;
            float den = noH * noH * (a2 - 1.0) + 1.0;
            float noV = clamp(dot(N, V), 0.0, 1.0);
            float brdf = clamp(a2 / max(den * den, 1e-7) * (0.5 / (2.0 * noV + alpha + 1e-4)) - 6.1e-5, 0.0, 20.0);
            sum += c * F0 * (brdf * LocalParams.w * Globals2.x * clamp(dot(N, L), 0.0, 1.0));
        }
    }
    return sum * LocalParams.z;
}

// Opaque hair's CP8 rim gate from the scene depth (SceneDepth holds her depth prepass when RimDepth.x is set).
float hairRimDepth(vec3 N, float width) {
    float l2 = dot(N.xy, N.xy);
    vec2 dir = l2 > 0.0 ? N.xy * inversesqrt(l2) : vec2(0.0); // view space and GL window space: x right, y up
    vec2 inv = 1.0 / max(ScreenSize, vec2(1.0));
    vec2 uv = clamp(gl_FragCoord.xy * inv + dir * vec2(ScreenSize.y * inv.x, 1.0) * (width * 0.006), inv, 1.0 - inv);
    vec2 size = vec2(textureSize(SceneDepth, 0));
    float d = texelFetch(SceneDepth, ivec2(min(uv * size, size - 1.0)), 0).r;
    float sceneDepth = ProjMat[3][2] / (d * 2.0 - 1.0 + ProjMat[2][2]); // view depth (blocks) of the sampled point
    return smoothstep(RimDepth.y, RimDepth.z, sceneDepth + viewPos.z);
}
