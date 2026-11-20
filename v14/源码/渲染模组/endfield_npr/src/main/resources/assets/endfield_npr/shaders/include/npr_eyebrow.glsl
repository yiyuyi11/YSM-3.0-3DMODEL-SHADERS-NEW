// Endfield NPR: iris parallax and colour terms, brows through the bangs, cloth parallax (EyeBrow.java).
// Imported by npr_character.fsh after its uniforms and npr_maps.glsl (PropMaps' per-material maps and parameters).
//
// Iris (HGRP/CharacterNPR_Eye; decompiled ~/ysm/npr/decompiled/Eye_950424.glsl, "E"; ue5ref Shaders/Stages/Eye.ush):
//   parallax  E 137-184 / Eye.ush 65-80: c = fract(uv) - .5, w = smoothstep(sat((.25 - c.c) * 5)),
//             irisUV = uv - normalize(V_tangent).xy * _ParallaxScale * (1, .25) * w. No texture: the eye programs bind
//             no _ParallaxTex (every _ParallaxTex in the game data is on CharacterNPR cloth, see below).
//   colour    E 720-733 / Eye.ush 185-190, H = sat(_EyeHighLight) (1 on irises, 0 on brows):
//             bright = iris * .96 * ((1 - o H) + _EyeHighLightColor o H) * lerp(1, (1 - a) + _EyeScatteringColor a, H)
//             o = c.c >= .25 (outside the iris circle), a = _BaseMap.a * _BaseColor.a (E 620-621: r15.w). The dark
//             base (Grade of the unmultiplied iris, Eye.ush 193) is not multiplied.
//   The eye's tangent frame: YSM has no tangents; it is rebuilt per pixel from the screen derivatives of the view
//   position and the atlas uv (the cotangent frame, as ue5ref EFL_UVBasis does without an authored basis). Pelica's
//   two irises share one uv square mirrored in u (left eye u along +x, right eye along -x), which a fixed head frame
//   gets wrong for one eye.
//
// Brows through the bangs (_HairBrowMask; EndfieldCharacterPass.usf:472-477, Stages/Hair.ush:858): the game clips the
// hair where the brow/iris PreZ wrote its stencil (_PreZStencilRefOption 52) and _HairBrowMask.r < threshold, on
// hair materials with _DrawUnderBrow = 1. Here: the body pass writes a "masked hair" receiver code for such hair texels
// (RECEIVER_MASKED_HAIR, decodes as k = 4 everywhere else); a second pass (BrowReveal.z = 1, FULL tier) redraws the
// eye-material quads pulled toward the camera (npr_character.vsh, BrowRevealPull) with LEQUAL and no depth write, and
// keeps only fragments of eye texels over masked hair.
//
// Cloth parallax (HGRP/CharacterNPR, _UseParallax = 1; ~/ysm/npr/work/Gens2/Gens2_14753404.glsl): a parallax-occlusion
// march of _ParallaxTex.x in its tiled uv (586-636: n = min(_ParallaxMarchNum, 20) layers, per-layer step
// -V_t.xy / max(V_t.z, .001) * _ParallaxScale / n, first hit where layer depth < h, n + 1 tries), the linear
// interpolation between the last two layers and a final sample (1597-1607), then an unlit addition
// c += h * _ParallaxColor.rgb * a * premul (2061-2069; a = _BaseMap.a * _BaseColor.a, premul = 1 - p + a p with
// p = _AlphaPremultiply). The tangent view uses the vertex normal (_ParallaxUseNormal needs a normal map).

uniform vec4 IrisParams;     // x _ParallaxScale, y sat(_EyeHighLight), z _BaseColor.a, w 1 = the iris has params
uniform vec3 IrisHighlight;  // _EyeHighLightColor.rgb
uniform vec3 IrisScatter;    // _EyeScatteringColor.rgb
uniform vec4 BrowReveal;     // x 1 = mark masked hair, y _HairBrowMaskThreshold, z 1 = reveal pass, w receiver samples
uniform sampler2DMS BrowReceiverMS; // multisampled receiver of the reveal pass; not a json sampler (EyeBrow.java)

// receiver value of hair under _HairBrowMask (R8 135/255 = 0.529; round(r * 8) = 4 like plain hair)
const float RECEIVER_MASKED_HAIR = 135.0 / 255.0;

// dP/du, dP/dv (unit length) of the surface at this pixel around normal n: derivatives in uniform control flow only
void nprUvFrame(vec3 pos, vec2 uv, vec3 n, out vec3 t, out vec3 b) {
    vec3 dp1 = dFdx(pos);
    vec3 dp2 = dFdy(pos);
    vec2 du1 = dFdx(uv);
    vec2 du2 = dFdy(uv);
    vec3 dp2perp = cross(dp2, n);
    vec3 dp1perp = cross(n, dp1);
    t = dp2perp * du1.x + dp1perp * du2.x;
    b = dp2perp * du1.y + dp1perp * du2.y;
    t = dot(t, t) > 1e-20 ? normalize(t) : vec3(0.0);
    b = dot(b, b) > 1e-20 ? normalize(b) : vec3(0.0);
}

// The parallaxed iris uv (0..1 in the iris rectangle), E 137-184, and the eye's frame t = dP/du, b = dP/dv (atlas uv,
// v top-down; E's shadeB is -b), 0 where the derivatives degenerate. Call unconditionally (derivatives).
vec2 nprIrisParallax(vec2 irisUv, vec3 pos, vec2 atlasUv, vec3 n, out vec3 t, out vec3 b) {
    nprUvFrame(pos, atlasUv, n, t, b);
    vec3 v = normalize(-pos);
    vec3 vt = normalize(vec3(dot(v, t), dot(v, b), max(dot(v, n), 1e-4)));
    vec2 c = fract(irisUv) - 0.5;
    float w = clamp((0.25 - dot(c, c)) * 5.0, 0.0, 1.0);
    w = w * w * (3.0 - 2.0 * w);
    return irisUv - vt.xy * IrisParams.x * vec2(1.0, 0.25) * w;
}

// The iris rectangle uv -> atlas uv, kept half an atlas texel inside the rectangle (never samples a neighbour)
vec2 nprIrisAtlasUv(vec2 irisUv, vec4 rect, vec2 atlasSize) {
    vec2 inset = 0.5 / (atlasSize * max(rect.zw, vec2(1e-6)));
    return rect.xy + clamp(irisUv, inset, 1.0 - inset) * rect.zw;
}

// The bright-albedo multiplier of an iris texel, E 720-733; outside = 1 - the iris disc coverage (aa'd c.c >= .25)
vec3 nprIrisBright(float outside, vec2 atlasUv) {
    if (IrisParams.w < 0.5) {
        return vec3(1.0);
    }
    float h = IrisParams.y;
    float o = outside * h;
    float a = nprAlignedMap(NPR_BASE_ALPHA, atlasUv).r * IrisParams.z; // 0 without the baseAlpha map
    return (vec3(1.0 - o) + IrisHighlight * o) * mix(vec3(1.0), vec3(1.0 - a) + IrisScatter * a, h);
}

// The receiver value of a hair texel: masked hair (_HairBrowMask.r < threshold) for the reveal pass
float nprBrowReceiver(bool hair, float receiver, vec2 atlasUv) {
    if (!hair || BrowReveal.x < 0.5) {
        return receiver;
    }
    int mat = nprMaterialAt(atlasUv);
    if (mat < 0 || !nprMaterialHas(mat, NPR_BROW_MASK)) {
        return receiver;
    }
    return nprAlignedMap(NPR_BROW_MASK, atlasUv).r < BrowReveal.y ? RECEIVER_MASKED_HAIR : receiver;
}

bool nprMaskedHair(float r) {
    return abs(r * 255.0 - 135.0) < 2.5;
}

// eye/brow receiver (k = 6)
bool nprEyeReceiver(float r) {
    return abs(r * 8.0 - 6.0) < 0.5;
}

// Reveal pass: true = discard this fragment. Kept: eye-class texels whose pixel has masked hair as its front surface
// (all samples masked hair or eye, at least one masked hair). The single-sample receiver is bound on Sampler8's unit
// for this pass (the face highlight map is not read in it, see nprBrowRevealPass).
bool nprBrowRevealSkip(bool eye) {
    if (BrowReveal.z < 0.5) {
        return false;
    }
    if (!eye) {
        return true;
    }
    ivec2 px = ivec2(floor(gl_FragCoord.xy));
    if (BrowReveal.w > 0.5) {
        int n = int(BrowReveal.w + 0.5);
        bool any = false;
        for (int s = 0; s < n; s++) {
            float r = texelFetch(BrowReceiverMS, px, s).r;
            if (nprMaskedHair(r)) {
                any = true;
            } else if (!nprEyeReceiver(r)) {
                return true;
            }
        }
        return !any;
    }
    return !nprMaskedHair(texelFetch(Sampler8, px, 0).r);
}

bool nprBrowRevealPass() {
    return BrowReveal.z > 0.5;
}

// Cloth parallax: the unlit colour to add (0 where the material has no _ParallaxTex). Call unconditionally
// (derivatives); `cloth` gates the march.
vec3 nprClothParallax(bool cloth, vec3 pos, vec3 n, vec2 atlasUv) {
    if (!nprHasMap(NPR_PARALLAX)) {
        return vec3(0.0);
    }
    int mat = nprMaterialAt(atlasUv);
    vec2 tuv = nprTiledUv(NPR_PARALLAX, max(mat, 0), atlasUv);
    vec2 dx = dFdx(tuv);
    vec2 dy = dFdy(tuv);
    // Unity uv: u along dP/du, v_unity = 1 - v (glTF), so dP/dv_unity = -dP/dv_atlas
    vec3 t;
    vec3 b;
    nprUvFrame(pos, atlasUv, n, t, b);
    if (!cloth || mat < 0 || !nprMaterialHas(mat, NPR_PARALLAX)) {
        return vec3(0.0);
    }
    vec4 p0 = nprMatParam(mat, 0); // _ParallaxScale, _ParallaxMarchNum, _ParallaxUseNormal, _AlphaPremultiply
    vec4 p1 = nprMatParam(mat, 1); // _ParallaxColor.rgb, _BaseColor.a
    vec3 v = normalize(-pos);
    vec3 vt = normalize(vec3(dot(v, t), -dot(v, b), dot(v, n)));
    float layers = max(min(p0.y, 20.0), 1.0);
    float step1 = 1.0 / layers;
    vec2 delta = vt.xy / max(vt.z, 0.001) * -p0.x * step1;
    vec2 offset = delta;
    vec2 prevOffset = vec2(0.0);
    float depth = 1.0 - step1;
    float prevH = 0.0;
    float prevDepth = 1.0;
    float h = 0.0;
    bool hit = false;
    for (int i = 0; i < 21; i++) {
        if (float(i) >= layers + 1.0) {
            break;
        }
        float s = nprTiledSample(NPR_PARALLAX, mat, tuv + offset, dx, dy).x;
        if (depth < s) {
            h = s;
            hit = true;
            break;
        }
        prevOffset = offset;
        offset += delta;
        prevH = s;
        prevDepth = depth;
        depth -= step1;
    }
    if (!hit) {
        h = prevH;
    }
    float before = prevH - prevDepth;
    float k = before / min(before + depth - h, -1e-6); // both terms <= 0 at a hit (1597-1603, no clamp)
    float hf = nprTiledSample(NPR_PARALLAX, mat, tuv + prevOffset + delta * k, dx, dy).x;
    float a = nprAlignedMap(NPR_BASE_ALPHA, atlasUv).r * p1.w; // 0 without the baseAlpha map: no parallax colour
    float premul = 1.0 - p0.w + a * p0.w;
    return hf * p1.rgb * a * premul;
}
