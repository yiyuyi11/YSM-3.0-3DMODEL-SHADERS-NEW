// Endfield NPR outline: per-material width / z offset and the _OutlineMask (vertex stage; import after npr_maps.glsl).
// Owner: NormalOutline.
//
// EndfieldOutline.ush:39-53 (EFL_OutlinePlaneShift), Stages/Outline.ush:59-72, EndfieldOutlineDecode.ush:19-23:
//   mask  = lerp(1, OutlineMask texel (SampleLevel 0), _EnableOutlineMask)
//   shift = (|raw| < min ? min x sign(raw) : raw) x mask.r       (after the one-pixel minimum)
//   depth = _OutlineOffsetZ x mask.g x 0.1 m
// The per-material _OutlineWidth / _OutlineOffsetZ / _EnableOutline (NPR_ROW_OUTLINE) replace the per-class values
// where the vertex's atlas uv lies in a material of the model's `materials` data that has them.

// classWidth / classOffsetZ: the per-class values (width already x outlineScale); scale: outlineScale.
// Returns (width, offsetZ, mask.r, mask.g).
vec4 nprOutlineParams(vec2 atlasUv, float classWidth, float classOffsetZ, float scale) {
    vec4 r = vec4(classWidth, classOffsetZ, 1.0, 1.0);
    int mat = nprMaterialAt(atlasUv);
    if (mat < 0) {
        return r;
    }
    vec4 p = nprMatParam(mat, NPR_ROW_OUTLINE); // _OutlineWidth (-1 absent), _OutlineOffsetZ (-1), _EnableOutline, _EnableOutlineMask
    if (p.x >= 0.0) {
        r.x = p.x * scale;
    }
    if (p.y >= 0.0) {
        r.y = p.y;
    }
    if (p.z < 0.5) {
        r.x = 0.0;
    }
    if (p.w > 0.5 && nprMaterialHas(mat, NPR_OUTLINE_MASK)) {
        r.zw = nprAlignedMapLod(NPR_OUTLINE_MASK, atlasUv).rg;
    }
    return r;
}
