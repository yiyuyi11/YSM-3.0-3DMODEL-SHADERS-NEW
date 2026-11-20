// Endfield NPR normal maps (fragment stage; import after npr_maps.glsl). Owner: NormalOutline.
//
// YSM has no vertex tangents, so the tangent frame is rebuilt per pixel from the screen-space derivatives of the
// view position and of the atlas uv: the cotangent frame of Schueler ("Followup: Normal Mapping Without Precomputed
// Tangents", 2013). YSM's colour atlas maps each material's uv linearly (TextureAtlas.remap: offset + uv x size, same
// scale on both axes), so the atlas uv has the material's tangent directions. The frame follows the uv orientation of
// each triangle, so mirrored uv islands need no special case. The maps are Unity's (+G = +V, OpenGL style; checked on
// Pelica's _n maps: d(R)/d(row) and d(G)/d(column) anticorrelate, as for a height field with +G pointing image-up),
// while the glTF/atlas v grows down the image, hence B = -dP/dv.
//
// Texel decode (EndfieldNormalDecode.ush:37-43 EFL_DecodePackedNormal, Skin S 121-129, Hair H2 155-169): xy = (R*A, G)
// x 2 - 1, z = sqrt(1 - |xy|^2) from the unscaled xy, then xy *= _BumpScale x _UseBumpMap. Hair reads its one normal
// texture, _SplitNormalMap: RG the diffuse normal (x _BumpScale), BA the specular normal (x _SpecBumpScale; H2
// 1142-1151, Stages/Hair.ush:84-135).

uniform float NormalMapStrength; // normalMapStrength: x every _BumpScale / _SpecBumpScale (0 = mesh normals)

// N: the shading normal, already turned toward the viewer (gl_FrontFacing). Columns: T (= grad u), B (= grad of
// Unity's v), N; T and B share one scale (Schueler's invmax), so a tangent-space normal maps without shear. Zero
// T and B where the uv has no derivatives (the caller then gets N back). Call in uniform control flow.
mat3 nprCotangentFrame(vec3 N, vec3 p, vec2 uv) {
    vec3 dp1 = dFdx(p);
    vec3 dp2 = dFdy(p);
    vec2 duv1 = dFdx(uv);
    vec2 duv2 = dFdy(uv);
    vec3 dp2perp = cross(dp2, N);
    vec3 dp1perp = cross(N, dp1);
    vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;
    vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;
    float m = max(dot(T, T), dot(B, B));
    float invmax = m > 1e-30 ? inversesqrt(m) : 0.0;
    return mat3(T * invmax, -B * invmax, N);
}

// tangent-space normal from an encoded XY texel pair (packed R*A for _BumpMap), strength after z (EFL_DecodePackedNormal)
vec3 nprDecodeNormalXY(vec2 enc, float strength) {
    vec2 xy = enc * 2.0 - 1.0;
    float z = sqrt(clamp(1.0 - dot(xy, xy), 0.0, 1.0));
    return vec3(xy * strength, z);
}

// tangent space -> view space.
// On a back face the game flips the whole mapped normal (mapped x facing, Skin S 189-196, Stages/Cloth.ush:103). The
// frame is built from the already flipped N: nprCotangentFrame's T and B are cross products with N, so they flip with
// it, and T ts.x + B ts.y + N ts.z is already the flipped mapped normal. (Multiplying the tangent part by the facing
// again un-flipped it: back faces got the perturbation mirrored.)
vec3 nprApplyTangentNormal(mat3 frame, vec3 ts) {
    vec3 n = frame[0] * ts.x + frame[1] * ts.y + frame[2] * ts.z;
    return dot(n, n) > 1e-12 ? normalize(n) : frame[2];
}

// The diffuse/shading normal of this texel: _BumpMap (cloth, body, face) or the split map's RG (hair), per material
// (nprMaterialAt / NPR_ROW_NORMAL = (_BumpScale x _UseBumpMap, _SpecBumpScale x _UseSpecBumpMap, has normal, has
// splitNormal)). Returns frame.N where the texel's material has no such map. hair: the texel is hair class.
vec3 nprMappedNormal(mat3 frame, int mat, bool hair, vec4 normalTexel, vec4 splitTexel) {
    if (mat < 0) {
        return frame[2];
    }
    vec4 p = nprMatParam(mat, NPR_ROW_NORMAL) * vec4(NormalMapStrength, NormalMapStrength, 1.0, 1.0);
    if (hair) {
        return p.w > 0.5 && p.x != 0.0 ? nprApplyTangentNormal(frame, nprDecodeNormalXY(splitTexel.rg, p.x)) : frame[2];
    }
    return p.z > 0.5 && p.x != 0.0
        ? nprApplyTangentNormal(frame, nprDecodeNormalXY(vec2(normalTexel.r * normalTexel.a, normalTexel.g), p.x))
        : frame[2];
}

// Hair specular normal (split map BA x _SpecBumpScale x _UseSpecBumpMap); N where there is none.
// [deviation] H2 1142-1151 leaves the specular normal unflipped on back faces; here it takes the same facing as the
// diffuse normal, so a hair texel without the map keeps exactly today's N.
vec3 nprHairSpecNormal(mat3 frame, int mat, vec4 splitTexel) {
    if (mat < 0) {
        return frame[2];
    }
    vec4 p = nprMatParam(mat, NPR_ROW_NORMAL) * vec4(NormalMapStrength, NormalMapStrength, 1.0, 1.0);
    return p.w > 0.5 && p.y != 0.0 ? nprApplyTangentNormal(frame, nprDecodeNormalXY(splitTexel.ba, p.y)) : frame[2];
}

// Kajiya-Kay strand base with the property map's R blending the uv tangent in (H2 1176-1186, Stages/Hair.ush:193-195):
// strand = lerp(cross(specN, axis), T, prop.r); base = cross(specN, strand) x lerp(1, handedness, prop.r).
// Tb: the existing base, normalize(cross(specN, cross(specN, axis))) (= the game's at prop.r = 0), kept as is there.
vec3 nprHairStrandBase(vec3 Tb, vec3 specN, vec3 axis, float propR, mat3 frame) {
    float r = clamp(propR, 0.0, 1.0);
    if (r <= 0.0 || dot(frame[0], frame[0]) < 1e-12) {
        return Tb;
    }
    vec3 T = normalize(frame[0]);
    float handedness = dot(cross(frame[2], frame[0]), frame[1]) < 0.0 ? -1.0 : 1.0;
    vec3 strand = mix(cross(specN, axis), T, r);
    vec3 base = cross(specN, strand) * mix(1.0, handedness, r);
    return dot(base, base) > 1e-10 ? normalize(base) : Tb;
}
