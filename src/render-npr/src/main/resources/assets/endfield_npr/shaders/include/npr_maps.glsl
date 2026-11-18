// =====================================================================================================
// Per-material maps of the model package (NprMaps.java; contract ~/ysm/handoff/npr-materials-schema.md).
// Sampler11: atlas-aligned layers (one per kind the model has, YSM atlas uv); Sampler12: sheet of the tiled maps;
// NprMat*: per-material table (materials sharing one atlas rectangle merged). Import with
//   #moj_import <endfield_npr:npr_maps.glsl>
// Usable in the fragment and the vertex stage (nprAlignedMapLod).
// =====================================================================================================

const int NPR_PROP = 0;          // _MetallicGlossMap: cloth r metal, g specular, b shadow weight, a smoothness;
                                 // hair r tangent blend, g primary gloss, b shadow weight, a secondary weight
const int NPR_EMISSION = 1;      // baked _EmissionMap x _EmissionColor x _EmissionBrightness (use nprEmission)
const int NPR_NORMAL = 2;        // _BumpMap (raw texel)
const int NPR_SPLIT_NORMAL = 3;  // _SplitNormalMap (raw texel)
const int NPR_OUTLINE_MASK = 4;  // _OutlineMask (raw texel)
const int NPR_BROW_MASK = 5;     // _HairBrowMask (raw texel)
const int NPR_BASE_ALPHA = 6;    // the material's own _BaseMap alpha (grey)
const int NPR_LINE = 8;          // _LineMap (tiled)
const int NPR_PARALLAX = 9;      // _ParallaxTex (tiled)
const int NPR_ROW_NORMAL = 2;    // nprMatParam rows owned by NormalOutline
const int NPR_ROW_OUTLINE = 3;
const int NPR_MAX_MATERIALS = 24;

uniform sampler2DArray Sampler11;
uniform sampler2D Sampler12;
uniform vec4 NprMapLayers0;      // layer of kinds 0..3, -1 = the model has no such map
uniform vec4 NprMapLayers1;      // layer of kinds 4..7
uniform int NprMatCount;
uniform vec4 NprMatRect[NPR_MAX_MATERIALS];    // atlas uv: xy origin, zw size
uniform vec4 NprMatInfo[NPR_MAX_MATERIALS];    // x kind flags (bit = kind id), y tiled kind id or -1, zw tiled map texels
uniform vec4 NprMatTile[NPR_MAX_MATERIALS];    // tiled map Unity _ST: xy scale, zw offset
uniform vec4 NprMatSub[NPR_MAX_MATERIALS];     // tiled map rectangle in the Sampler12 sheet (uv), zw = 0: no map
uniform vec4 NprMatParam[NPR_MAX_MATERIALS * 4]; // 4 parameter rows per material (NprMaps.TILED_PARAMS / ROWS)

float nprLayer(int kind) {
    return kind < 4 ? NprMapLayers0[kind] : kind < 8 ? NprMapLayers1[kind - 4] : -1.0;
}

vec4 nprNeutral(int kind) {
    return kind == NPR_PROP ? vec4(0.0, 0.0, 1.0, 0.0)
         : kind == NPR_NORMAL ? vec4(0.5, 0.5, 0.0, 1.0)
         : kind == NPR_SPLIT_NORMAL ? vec4(0.5)
         : kind == NPR_OUTLINE_MASK ? vec4(1.0)
         : vec4(0.0);
}

int nprMaterialAt(vec2 atlasUv) {
    for (int i = 0; i < NPR_MAX_MATERIALS; i++) {
        if (i >= NprMatCount) {
            break;
        }
        vec4 r = NprMatRect[i];
        if (all(greaterThanEqual(atlasUv, r.xy)) && all(lessThanEqual(atlasUv, r.xy + r.zw))) {
            return i;
        }
    }
    return -1;
}

bool nprMaterialHas(int mat, int kind) {
    return mat >= 0 && ((int(NprMatInfo[mat].x + 0.5) >> kind) & 1) != 0;
}

// the texel's material is drawn by the game's hair shader (NprMaps.HAIR_SHADER_FLAG: _AnisotropyIntensity in params)
const int NPR_HAIR_SHADER_FLAG = 10;
bool nprMaterialIsHair(int mat) {
    return nprMaterialHas(mat, NPR_HAIR_SHADER_FLAG);
}

bool nprHasMap(int kind) {
    if (kind < 8) {
        return nprLayer(kind) >= 0.0;
    }
    for (int i = 0; i < NPR_MAX_MATERIALS; i++) {
        if (i >= NprMatCount) {
            break;
        }
        if (nprMaterialHas(i, kind)) {
            return true;
        }
    }
    return false;
}

vec4 nprMatParam(int mat, int row) {
    return mat >= 0 ? NprMatParam[mat * 4 + row] : vec4(0.0);
}

// the masks layer holds outlineMask (rg), browMask (b) and baseAlpha (a) (NprMaps.PHYSICAL): unpack one kind
vec4 nprUnpack(int kind, vec4 t) {
    return kind == NPR_OUTLINE_MASK ? vec4(t.rg, 1.0, 1.0)
         : kind == NPR_BROW_MASK ? vec4(t.bbb, 1.0)
         : kind == NPR_BASE_ALPHA ? vec4(t.aaa, 1.0)
         : t;
}

// the composited texel, or the kind's neutral value (no such layer)
vec4 nprAlignedMap(int kind, vec2 atlasUv) {
    float layer = nprLayer(kind);
    return layer >= 0.0 ? nprUnpack(kind, texture(Sampler11, vec3(atlasUv, layer))) : nprNeutral(kind);
}

vec4 nprAlignedMapLod(int kind, vec2 atlasUv) {
    float layer = nprLayer(kind);
    return layer >= 0.0 ? nprUnpack(kind, textureLod(Sampler11, vec3(atlasUv, layer), 0.0)) : nprNeutral(kind);
}

// linear emission (colour x brightness x map) at this atlas uv, 0 without an emission layer
vec3 nprEmission(vec2 atlasUv) {
    if (nprLayer(NPR_EMISSION) < 0.0) {
        return vec3(0.0);
    }
    vec4 e = nprAlignedMap(NPR_EMISSION, atlasUv);
    vec3 c = clamp(e.rgb, 0.0, 1.0);
    vec3 lin = mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92, vec3(lessThanEqual(c, vec3(0.04045))));
    return lin * (exp2(e.a * 8.0) - 1.0);
}

// the material's own glTF uv (0..1, v top-down) at this atlas uv
vec2 nprMaterialUv(int mat, vec2 atlasUv) {
    vec4 r = NprMatRect[max(mat, 0)];
    return (atlasUv - r.xy) / max(r.zw, vec2(1e-9));
}

// Unity uv (u, 1 - v) of the material, tiled by its _ST; no fract
vec2 nprTiledUv(int kind, int mat, vec2 atlasUv) {
    vec2 uv = nprMaterialUv(mat, atlasUv);
    vec4 st = NprMatTile[max(mat, 0)];
    return vec2(uv.x, 1.0 - uv.y) * st.xy + st.zw;
}

bool nprMaterialHasTiled(int kind, int mat) {
    return mat >= 0 && int(NprMatInfo[mat].y + 0.5) == kind && NprMatSub[mat].z > 0.0;
}

// the sheet uv of a tiled uv: fract, half-texel guard, the map's sheet rectangle (PNG rows top-down: row 1 - fract(t.y))
vec2 nprTiledSheetUv(int mat, vec2 tiledUv) {
    vec4 sub = NprMatSub[mat];
    vec2 halfTexel = 0.5 / max(NprMatInfo[mat].zw, vec2(1.0));
    vec2 p = clamp(vec2(fract(tiledUv.x), 1.0 - fract(tiledUv.y)), halfTexel, 1.0 - halfTexel);
    return sub.xy + p * sub.zw;
}

// the tiled map at a tiled uv; dx/dy are the tiled uv's screen derivatives (textureGrad, mapped into the sheet).
// vec4(0) when the material has no such map.
vec4 nprTiledSample(int kind, int mat, vec2 tiledUv, vec2 dx, vec2 dy) {
    if (!nprMaterialHasTiled(kind, mat)) {
        return vec4(0.0);
    }
    vec2 scale = NprMatSub[mat].zw * vec2(1.0, -1.0);
    return textureGrad(Sampler12, nprTiledSheetUv(mat, tiledUv), dx * scale, dy * scale);
}

// the tiled map at this atlas uv (base level: the sheet has no mipmaps; usable in the vertex stage too)
vec4 nprTiledMap(int kind, int mat, vec2 atlasUv) {
    if (!nprMaterialHasTiled(kind, mat)) {
        return vec4(0.0);
    }
    return textureLod(Sampler12, nprTiledSheetUv(mat, nprTiledUv(kind, mat, atlasUv)), 0.0);
}
