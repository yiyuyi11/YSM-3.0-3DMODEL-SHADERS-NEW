// =====================================================================================================
// Endfield character rain and wetness (fragment stage; import after npr_maps.glsl / npr_normal.glsl and the colour
// helpers of npr_character.fsh). The shading is the game's own: npr_rain_asm.glsl holds the rain/wet blocks of
// HGRP/CharacterNPR, _Skin and _Hair translated one statement per asm line (tools/rain/rain_gen.py, checked against
// the asm by tools/rain/rain_check.py). This file only fills the program registers those blocks read from our surface
// values and reads their results back; every register is cited with the asm line that defines it in the game.
//   face (Skin variant A, Skin_10029588): drips 389-452, wet matcap 770-789
//   body skin (Skin_1018472): procedural drops + wet darkening 378-952, GGX sheen 1168-1184, drop glint 1185-1214
//   hair (Hair_1000084): procedural drops + wet darkening 411-1010, primary spec x r6.w (1258), glint 1273-1304
//   cloth (CharacterNPR_10131804): wet patches, streaks, drops 473-1032, env roughness 1782-1783
//   ours, not the game's: rainWetFilm, a water-film reflection on wet skin and hair standing in for the game's separate
//   reflection pass (its programs only flag wet texels in the gbuffer; SSR / probes reflect them later)
// The second block of each program (gated by byte 3) is the SNOW layer (_CharacterSnowEffectTex): ported and checked,
// compiled only with NPR_RAIN_SNOW, which needs T_actor_common_snow_01_M in RainTex layer 4 (not shipped).
//
// Per draw (NprRainBlock, read in the vertex stage): the game's word cb2[draw+13].x, four bytes: x instant rain,
// y water-line wetness, z accumulated wetness, w snow; and the water line cb2[draw+13].y (here camera-relative world
// y, -1000 = none). RainWet.java computes them for Minecraft. The game's world and object space are Unity's
// (left-handed, y up): world = Minecraft's with z mirrored, object = the body frame (feet origin, body yaw; Unity's
// right/up/forward, blocks). The object position and normal come from the vertex stage (RainObjP/RainObjN): per-vertex
// anchors of the character's reference skin (RainAnchors.java), so the patterns stick to the deforming surface, or
// without them this frame's rigid body frame applied to the skinned vertex (0.8.2's mapping).
// =====================================================================================================

uniform sampler2DArray RainTex;  // T_actor_common_rain_01..04_M, linear RGBA, repeat (RainTextures.java)
uniform vec4 RainParams;         // x 1 = on, y time in s (cb1[20].x), z texture tiling (cb1[120].z), w |cb0[9].y|
uniform vec3 RainSpec;           // x rainSheen (cb1[123].w on the skin sheen r25 / hair primary), y rainWetReflection,
                                 // z rainFaceDripSpeed (on top of rainDropSpeed)
flat in vec4 RainBytes;          // the 4 bytes, 0..255
flat in float RainLine;          // water line, camera-relative world y; -1000 = none
in vec3 RainObjP;                // object-space position and normal (unnormalized) of the pixel, see above
in vec3 RainObjN;
vec3 rainObjT;                   // object-space tangent (grad u), unturned, from rainPrepare
vec3 rainSkinF0;                 // skin F0 after the blocks (Skin 1042-1044), from rainSurface for rainColor's sheen
float rainWet;                   // wetness of the pixel, max(wade, byte 2 / 255), from rainSurface (rainWetFilm)

const int RAIN_LAYER_EFFECT = 0;       // _CharacterRainEffectTex (rain_01): B drops, A wet patches
const int RAIN_LAYER_STREAK = 1;       // _CharacterRainStreakTex (rain_02): RG normal, B/A streaks
const int RAIN_LAYER_FACE_DRIP = 2;    // _CharacterRainFaceDripTex (rain_03): RG normal, B lines, A drop heads
const int RAIN_LAYER_FACE_DROPLET = 3; // _CharacterRainFaceDropletTex (rain_04): B face mask, A wet matcap
const int RAIN_LAYER_SNOW = 4;         // _CharacterSnowEffectTex (not shipped)

const int RAIN_NONE = 0;
const int RAIN_FACE = 1;
const int RAIN_SKIN = 2;
const int RAIN_HAIR = 3;
const int RAIN_CLOTH = 4;

int rainFamily = RAIN_NONE;  // the block that ran for this pixel; its registers stay in rainR for the post terms
float rainLodObject;         // texture lod of the object-space (triplanar, streak) fetches, from rainPrepare
float rainLodFace;           // of the face-uv fetches
float rainLod;               // the one rainTex uses

// texture fetch of the blocks: Unity uv (v up) -> our layers (PNG rows top-down at t = 0). sample_b's implicit lod is
// taken in uniform control flow by rainPrepare instead (the fetches sit in the blocks' branches)
vec4 rainTex(int layer, vec2 uv) {
    return textureLod(RainTex, vec3(uv.x, 1.0 - uv.y, float(layer)), rainLod);
}

#moj_import <endfield_npr:npr_rain_asm.glsl>

// view space -> the game's world (Unity, left-handed: Minecraft's world with z mirrored) and back
vec3 rainW(vec3 v) {
    vec3 w = ViewToPlayer * v;
    return vec3(w.x, w.y, -w.z);
}
vec3 rainV(vec3 w) {
    return transpose(ViewToPlayer) * vec3(w.x, w.y, -w.z);
}
// lods in uniform control flow: 1024-texel layers, object position x tiling / face uv; and the object-space tangent,
// the cotangent frame of the object position as nprFrame's (npr_normal.glsl) on the view position. The object axes are
// left-handed (right x up = -forward), which flips the frame's cross products: the object image of the view-space
// grad-u tangent is -T. With the rigid frame this equals 0.8.2's rainObjVec(tu) after normalization.
void rainPrepare(vec2 faceUv, vec2 uv) {
    vec3 o = RainObjP * RainParams.z;
    float fo = max(length(dFdx(o)), length(dFdy(o))) * 1024.0;
    vec2 f = max(abs(dFdx(faceUv)), abs(dFdy(faceUv))) * 1024.0;
    rainLodObject = log2(max(fo, 1e-6));
    rainLodFace = log2(max(max(f.x, f.y), 1e-6));
    rainObjT = -nprCotangentFrame(RainObjN, RainObjP, uv)[0];
}

bool rainActive() {
    return RainParams.x > 0.5 && max(max(RainBytes.x, RainBytes.y), max(RainBytes.z, RainBytes.w)) > 0.0;
}

// byte decode and wetness (Skin_10029588 129-140; the same in every program: Skin_1018472 121-136, Hair 165-180,
// CharacterNPR 228-243): n = bytes / 255; wade = n.y x smoothstep(sat((line - y + 0.2) / 0.35)); wet = max(wade, n.z)
vec4 rainBytesN() {
    return RainBytes * 0.003921569;
}
float rainWade(vec3 p) {
    float h = clamp(((RainLine - (ViewToPlayer * p).y) + 0.2) * 2.857143, 0.0, 1.0);
    return rainBytesN().y * (h * h * (3.0 - 2.0 * h));
}

// sRGB <-> linear for the blocks (our albedo and shadow colour are display values; the game's are linear)
vec3 rainLin(vec3 c) {
    return srgbDecode(c);
}
vec3 rainEnc(vec3 c) {
    return srgbEncode(c);
}

// The surface: albedo A and shadow colour S (display), shading normal N (view space, facing the viewer), smoothness,
// specular level, metal, and the receiver weight (face: _BaseMap.a, the game's lit = min(ramp, a)). family: RAIN_*.
// frame: nprCotangentFrame (T, B, N, turned with the facing); Nv: the interpolated vertex normal (view, unturned).
void rainSurface(int family, vec3 p, vec3 Nv, mat3 frame, bool front, vec3 V, vec2 faceUv, float hairU,
                 float disable, inout vec3 A, inout vec3 S, inout vec3 N, inout float smoothness,
                 inout float specLevel, inout float metal, inout float receiver) {
    rainFamily = family;
    rainLod = rainLodObject;
    float facing = front ? 1.0 : -1.0;
    vec4 raw = RainBytes;
    vec4 n = rainBytesN();
    float wade = rainWade(p);
    float wet = max(wade, n.z);
    rainWet = wet;
    // the game's per-vertex frame: v3 normal, v4 tangent + handedness (bitangent = cross(v3, v4) x v4.w), unturned
    vec3 nu = normalize(Nv);
    vec3 tu = frame[0] * facing;
    tu = dot(tu, tu) > 1e-12 ? normalize(tu - nu * dot(nu, tu))
       : normalize(cross(nu, abs(nu.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3 nw = rainW(nu);
    vec3 tw = rainW(tu);
    float hand = dot(cross(nw, tw), rainW(frame[1] * facing)) < 0.0 ? -1.0 : 1.0;
    vec4 v3 = vec4(nw, 0.0);
    vec4 v4 = vec4(tw, hand);
    // object space (RainObjP/RainObjN: anchored to the surface, see the header); world inputs above stay this frame's
    vec3 on = normalize(RainObjN);
    vec3 ot = rainObjT;
    ot = dot(ot, ot) > 1e-12 ? normalize(ot - on * dot(on, ot))
       : normalize(cross(on, abs(on.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec4 objN = vec4(on, 0.0);
    vec4 objT = vec4(ot, hand);
    vec4 objP = vec4(RainObjP, 1.0);
    // cb1[20] = Unity's _Time = (t/20, t, 2t, 3t): ShaderVariablesGlobal byte offset 320 in the serialized cbuffer table
    // (the user's dump). The blocks read only .x, so the drips and drops run at t/20, not at t in seconds.
    vec4 time = vec4(RainParams.y / 20.0, RainParams.y, RainParams.y * 2.0, RainParams.y * 3.0);
    vec4 dropTime = time * NprTweak.w; // rainDropSpeed: body, hair and cloth drops/streaks (1 = the game's pace)
    vec4 tiling = vec4(0.0, 0.0, RainParams.z, 0.0); // cb1[120].z
    vec4 off = vec4(disable, 0.0, 0.0, 0.0);         // cb6[5].x = _DisableRainEffectOnMaterial
    vec3 Nw = rainW(N);
    if (family == RAIN_FACE) {
        rainLod = rainLodFace;
        rainR[14] = raw;                   // 129 (bytes)
        rainR[15] = n;                     // 130
        rainR[11].x = wet;                 // 140
        rainR[4].w = 1.0 - smoothness;     // 94: 1 - cb6[0].x
        rainR[5].x = receiver;             // 92: _BaseMap.a
        rainR[7].w = facing;               // 107
        rainR[10].y = 1.0;                 // 105: t17.g (no such mask here)
        rainR[10].x = specLevel;           // 388: t17.g x cb6[0].y (_Specular)
        rainR[12].xyz = Nw;                // 111: the facing normal
        // face drips at rainDropSpeed x rainFaceDripSpeed (1 x 1 = the game's _Time.x pace)
        rainFaceDrip(vec4(faceUv.x, 1.0 - faceUv.y, 0.0, 0.0), v3, v4, dropTime * RainSpec.z,
            vec4(0.0, specLevel, 0.0, 0.0), off);
        N = normalize(rainV(rainR[20].xyz));
        smoothness = 1.0 - rainR[4].w;
        specLevel = rainR[10].x;
        receiver = rainR[5].x;
    } else if (family == RAIN_SKIN) {
        rainR[12] = raw;                   // 121
        rainR[13] = n;                     // 126
        rainR[2].w = wet;                  // 136
        rainR[3] = vec4(0.0, rainLin(A));  // 69: no Z-up mesh; 76/377: albedo (rim tint included)
        rainR[6].xyz = cross(nw, tw) * hand; // 86-88
        rainR[8].xyz = rainLin(S);         // 99-101: shadow colour
        rainR[10] = vec4(Nw, clamp(dot(N, V), 0.0, 1.0)); // 112, 371
        rainSkinDrops(v3, v4, objN, objT, objP, dropTime, tiling, off);
        A = rainEnc(rainR[3].yzw);
        S = rainEnc(rainR[8].xyz);
        N = normalize(rainV(vec3(rainR[15].y, rainR[12].x, rainR[12].y)));
    } else if (family == RAIN_HAIR) {
        rainR[20] = raw;                   // 165-169
        rainR[21] = n;                     // 170
        rainR[3].xy = vec2(wet, 0.0);      // 180; 70: no Z-up mesh
        rainR[2] = vec4(rainW(V), length(p)); // 67-68: toward the camera, distance
        rainR[9].xyz = rainLin(A);         // 92
        rainR[11].xyz = cross(nw, tw) * hand; // 99-101
        rainR[14].xyz = rainLin(S);        // 122-124
        rainR[15].xyz = Nw;                // 134
        rainHairDrops(vec4(hairU, 0.0, 0.0, 0.0), v3, v4, objN, objT, objP, vec4(0.0, RainParams.w, 0.0, 0.0), dropTime,
            tiling, off);
        A = rainEnc(rainR[9].xyz);
        S = rainEnc(rainR[14].xyz);
        N = normalize(rainV(vec3(rainR[11].y, rainR[20].x, rainR[20].y)));
    } else if (family == RAIN_CLOTH) {
        rainLod = rainLodObject;
        rainR[14] = raw;                   // 228-232
        rainR[15] = n;                     // 233
        rainR[8].w = wet;                  // 243
        rainR[3].yzw = vec3(0.0, 1.0 - smoothness, wade); // 93: no Z-up mesh; 105; 242
        rainR[5].xyz = rainLin(S);         // 218
        rainR[6].xw = vec2(metal, smoothness); // 104: _MetallicGlossMap r, a
        rainR[10].xyz = Nw;                // 222
        rainR[12].xyz = rainLin(A);        // 212
        rainClothWet(objN, objP, dropTime, tiling, off);
        A = rainEnc(rainR[12].xyz);
        S = rainEnc(rainR[5].xyz);
        N = normalize(rainV(rainR[14].xyz));
        smoothness = 1.0 - rainR[3].z;
    }
#ifdef NPR_RAIN_SNOW
    // snow (block 2, byte 3) on the result of the rain block
    rainLod = rainLodObject;
    vec4 front4 = vec4(front ? 1.0 : 0.0);
    float ny = rainW(N * facing).y;
    Nw = rainW(N);
    if (family == RAIN_FACE) {
        rainR[14] = raw;
        rainR[15] = n;
        rainR[3] = vec4(0.0, rainLin(A));  // 70; 93
        rainR[17].xyz = vec3(1.0);         // 386: rim tint (already in A)
        rainR[19].xyz = rainR[3].yzw;      // 387
        rainR[4].w = 1.0 - smoothness;
        rainR[5].x = receiver;
        rainR[7].w = facing;
        rainR[8].w = 1.0;                  // 377: edge weight (no t17 mask here)
        rainR[9].xyz = rainLin(S);         // 104
        rainR[10].y = 1.0;
        rainR[11].y = rainW(nu).y;         // 110
        rainR[12].xyz = Nw;
        rainFaceSnow(objN, objP, front4, tiling, vec4(0.0, specLevel, metal, 0.0), off);
        metal = rainR[3].x;
        A = rainEnc(rainR[19].xyz);
        S = rainEnc(rainR[9].xyz);
        smoothness = 1.0 - rainR[4].w;
        N = normalize(rainV(rainR[14].xyw));
    } else if (family == RAIN_SKIN) {
        rainR[12] = raw;
        rainR[13] = n;
        rainR[3] = vec4(0.0, rainLin(A));
        rainR[4].z = 1.0 - smoothness;     // 79
        rainR[5].w = 1.0;                  // 77: _BaseMap.a
        rainR[6].w = facing;               // 107
        rainR[7].y = ny;                   // 111
        rainR[8].xyz = rainLin(S);
        rainR[10].xyz = Nw;
        rainSkinSnow(objN, objP, front4, tiling, vec4(0.0, specLevel, metal, 0.0), off);
        metal = rainR[3].x;
        A = rainEnc(rainR[3].yzw);
        S = rainEnc(rainR[8].xyz);
        smoothness = 1.0 - rainR[4].z;
        N = normalize(rainV(rainR[13].yzw));
    } else if (family == RAIN_HAIR) {
        rainR[20] = raw;
        rainR[21] = n;
        rainR[3].yz = vec2(0.0, facing);   // 70; 129
        rainR[9].xyz = rainLin(A);
        rainR[12].y = ny;                  // 133
        rainR[14].xyz = rainLin(S);
        rainR[15].xyz = Nw;
        rainHairSnow(objN, objP, front4, tiling, off);
        A = rainEnc(rainR[9].xyz);
        S = rainEnc(rainR[14].xyz);
        N = normalize(rainV(rainR[21].xyz));
    } else if (family == RAIN_CLOTH) {
        rainR[14] = raw;
        rainR[15] = n;
        rainR[3].yz = vec2(0.0, 1.0 - smoothness);
        rainR[5].xyz = rainLin(S);
        rainR[6].xz = vec2(metal, receiver); // 104: _MetallicGlossMap r, b
        rainR[7].w = facing;               // 181
        rainR[9].y = ny;                   // 221
        rainR[10].xyz = Nw;
        rainR[12].xyz = rainLin(A);
        rainClothSnow(objN, objP, front4, tiling, off);
        smoothness = 1.0 - rainR[3].z;
        S = rainEnc(rainR[5].xyz);
        metal = rainR[6].x;
        A = rainEnc(rainR[12].xyz);
        N = normalize(rainV(rainR[15].xyz));
    }
#endif
    if (family == RAIN_SKIN) {
        // the sheen's inputs after the blocks (Skin 1038-1047): roughness^2 r6.w = max((1 - _Smoothness)^2, 1/128)
        // (r4.z = 1 - cb6[0].x at 79, only the snow block changes it) and F0 r18 = lerp(0.04 _Specular, albedo,
        // metal) on the wet albedo; r10 keeps the normal from before the drops (112, 371)
        float rough = 1.0 - smoothness;
        rainR[6].w = max(rough * rough, 0.0078125); // 1046-1047 (the drop block neither reads nor writes r6.w)
        rainSkinF0 = mix(vec3(0.04 * specLevel), rainLin(A), metal); // 1042-1044 (r18 is drop-block scratch)
    }
}

// Hair primary (Kajiya) highlight multiplier of the drops (Hair 1258: r4.w x r6.w); 1 without rain
float rainHairSpec() {
    return rainFamily == RAIN_HAIR ? rainR[6].w : 1.0;
}

// Cloth environment-reflection roughness (CharacterNPR 1782-1783: lerp(rough, r13.w, r3.w) after the wet block)
float rainEnvRough(float rough) {
    if (rainFamily != RAIN_CLOTH) {
        return rough;
    }
    rainR[3].z = rough;
    rainClothEnv();
    return rainR[3].w;
}

// The lit colour with the post terms of the blocks. color = diffuse + spec on entry. Skin and hair (Skin 1185-1214,
// Hair 1273-1304): diffuse + sheen x k + (diffuse + sheen) x b + light x drop glint, with the half vector of the skin
// GGX (L x shadow + 2 fill + V (2 + shadow), as npr_character.fsh's Hs). The sheen: skin the game's GGX r25 (1168-1184,
// rainSkinSheen: D x Vis x F0 x light x cb1[123].w on the normal from before the drops), hair the primary highlight r6
// (1240-1261, our spec); cb1[123].w = _CharacterParams13.w = specScale (Globals2.x) x rainSheen (RainSpec.x).
// rainGlint scales only the drop glint (the light the glint line reads: skin r16 at 1211, hair r30 at 1301).
// Face (770-789): the FaceDroplet A matcap at the view-space drip normal x wetness^2, scaled by specLight (the game:
// its lit term x light intensity x cb1[110].w).
vec3 rainColor(vec3 color, vec3 diffuse, vec3 spec, vec3 specLight, vec3 L, vec3 V, vec3 fill, float shadow) {
    if (rainFamily == RAIN_SKIN || rainFamily == RAIN_HAIR) {
        vec3 Hs = rainW(normalize(L * shadow + 2.0 * normalize(fill + 1e-5) + V * (2.0 + shadow)));
        if (rainFamily == RAIN_SKIN) {
            rainR[14].xyz = diffuse;       // 1121: r14 x r24 = the lit albedo
            rainR[24].xyz = vec3(1.0);
            rainR[16].xzw = specLight;     // 1153
            rainR[21].xyz = Hs;            // 1162-1167
            rainR[18].xyz = rainSkinF0;    // 1042-1044
            rainSkinSheen(vec4(0.0, 0.0, 0.0, Globals2.x * RainSpec.x)); // r25 = the sheen
            rainR[16].xzw = specLight * NprTweak.y; // rainGlint: r16 is read again only by the glint line (1211)
            rainSkinGlint();
            return rainR[12].xyz;
        }
        rainR[4].xyz = Hs;                 // 1264-1272
        rainR[6].xyz = spec * RainSpec.x;  // 1260-1261: x cb1[123].w (our spec carries specScale already)
        rainR[9].w = 1.0;                  // 92: _BaseMap.a (with cb6[1].z = 0: no alpha term)
        rainR[15].xyz = diffuse;           // 1184 x 1204 = 1205
        rainR[23].xyz = vec3(1.0);
        rainR[29].xyz = diffuse;           // 1205
        rainR[30].xzw = specLight * NprTweak.y; // 1301: the glint's light only (rainGlint)
        rainHairGlint(vec4(0.0));
        return rainR[4].xyz;
    }
    if (rainFamily == RAIN_FACE) {
        mat3 toView = transpose(ViewToPlayer) * mat3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, -1.0);
        rainLod = rainLodFace;
        rainR[8].y = 1.0;                  // 694: the face's lit term -> specLight below
        rainR[11].w = 1.0;                 // 140-142: light intensity, clamped to [0.5, 1.5]
        rainFaceMatcap(vec4(toView[0], 0.0), vec4(toView[1], 0.0), vec4(toView[2], 0.0), vec4(0.0, 0.0, 0.0, 1.0));
        return color + rainR[18].xyz * specLight * NprTweak.z; // rainMatcap
    }
    return color;
}

// Ours (rainWetReflection, RainSpec.y): the sheet-like sheen of wet skin and hair. The game's programs only mark wet
// texels for its later reflection pass (SSR / probes); here the cloth path's environment stand-in (npr_character.fsh:
// the light reaching the surface, dimmer toward the ground by the reflection vector) x EnvBRDF of a water film (F0 =
// 0.02, roughness 0.15) on the shading normal after the drops, x the pixel's wetness. Added after the game's terms.
vec3 rainWetFilm(vec3 N, vec3 V, vec3 lightCol) {
    if (rainFamily != RAIN_SKIN && rainFamily != RAIN_HAIR || RainSpec.y <= 0.0) {
        return vec3(0.0);
    }
    vec3 Rv = reflect(-V, N);
    vec3 envRad = lightCol * mix(0.45, 1.0, smoothstep(-0.6, 0.6, dot(Rv, WorldUp)));
    return envRad * nprEnvBRDF(vec3(0.02), clamp(dot(N, V), 0.0, 1.0), 0.15) * rainWet * RainSpec.y;
}
