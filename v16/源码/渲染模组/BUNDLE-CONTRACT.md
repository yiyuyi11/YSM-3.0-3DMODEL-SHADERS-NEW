# endfield_npr model package — contract (format 1)

## 1. In the model: glTF extension `ENDFIELD_npr` (VRM only)
Top-level `extensions.ENDFIELD_npr` (+ listed in `extensionsUsed`). Images are ordinary embedded glTF `images`
(PNG, bufferView in the GLB buffer) that no material uses. Two generic markers:
- `{"material": "<glTF material name>", "uv"?: [u, v, w, h]}` — a placement in YSM's atlas;
- `{"image": <glTF image index>}` — a texture.

```json
{"format": 1,
 "shading": {"skin": [smoothness, specular, shadowBrightness, shadowSaturation], "face": [...], "hair": [...],
   "cloth": [...], "eye": [...], "skinRimOffScale": f, "faceSkinRimOffScale": f, "faceRimOffScale": f,
   "sdfRimColor": [r,g,b], "hairAniso": [4], "hairSpecMask": [2], "hairAniso2": [2], "hairAnisoColor2": [3],
   "outlineWidth": [skin,face,hair,cloth], "outlineOffsetZ": [4], "outlineColorSkinCloth": [4],
   "outlineColorHairEye": [4]},
 "ramps": {"clothRD"|"skinRD"|"faceRD"|"hairRD"|"eyeRD"|"clothRS"|"hairRS": {"image": i}},
 "skinLut": {"image": i},
 "face": {"material": "face_01", "sdf": {"image": i}, "sdfMask": {"image": i}, "highlight": {"image": i},
          "highlightVector": [x, y, z]},
 "iris": {"material": "iris_01", "matcap": {"image": i}, "matcapColor": [r,g,b,a], "matcapNormalScale": f},
 "overlays": [{"card": {"material": "eyeshadow_01", "uv": [0.25,0.25,0.5,0.5]}, "texture": {"image": i},
   "baseColor": [r,g,b,a], "useGrayAsAlpha": f, "stencilRef": int, "disableDrawUnderHair": bool,
   "angleRange": f}]}
```
Every key is optional; a missing one takes the renderer's generic default (missing face/iris/overlays = feature off).

### `materials` (per-material maps and Unity parameters; renderer: endfield_npr NprMaps, ≥ the `props` branch)
Schema owner Main (`~/ysm/handoff/npr-materials-schema.md`); written by the NprPatch data tool, never by hand.
```json
"materials": {"<glTF material>": {"material": "<same name>", "source": "m_actor_<role>_<part>",
  "maps": {"<kind>": {"image": i, "scale"?: [sx, sy], "offset"?: [ox, oy]}},
  "params": {"<Unity m_Floats/m_Colors name>": number | [r, g, b, a]}}}
```
- Kinds: atlas-aligned `prop` (_MetallicGlossMap), `emission` (_EmissionMap), `normal` (_BumpMap), `splitNormal`
  (_SplitNormalMap), `outlineMask`, `browMask` (_HairBrowMask), `baseAlpha` (grey = the material's _BaseMap alpha);
  tiled `line` (_LineMap), `parallax` (_ParallaxTex), with the Unity `_ST` as scale/offset (omit when 1,1 / 0,0).
- A map is listed only when its binding is `found` and its `_Use…` flag is not 0 (e.g. no `emission` for
  `_UseEmission = 0`). `params` are verbatim; colours in Unity's stored (gamma) space.
- Only materials used by triangles (the importer fails otherwise). Materials that YSM merges into one atlas region
  (same base texture, colour and alpha mode: face + brows, Pelica cloth_01/04, cloth_02/03) share their texels; the
  renderer merges them per value (flags and outline width/offset/enable: max; else the region's texture owner, then
  listed order; each map kind painted by the first listed material that has it) and logs the merge.
- No importer change: the entries are ordinary `material`/`image` markers (rect / file); format stays 1.
- Without `materials` a model renders as before, except the hair gloss: no prop map and no `shading.hairSpecMask`
  → the client's `hairGlossFallback` (0.6). A package that sets `hairSpecMask` keeps its x as the gloss stand-in
  (values ≤ 0.5 give no primary highlight with Endfield's hair RS ramps).

## 2. Conversion (patched YSM jar, `EndfieldNprPackage`, HumanoidImport REVISION 15)
The importer copies the extension and rewrites only the markers:
- material marker gains `"rect": [x, y, w, h]` = that material's region in the atlas it built (atlas texels, floats),
  narrowed by `uv`;
- image marker becomes `{"file": "images/<i>.png"}`;
- top level gains `"atlas": [width, height]`.
Result: a ZIP of `profile.json` + `images/<i>.png`, stored as the player render target's
`ModelData.geo_models["endfield_npr"]` (YSM reads only "main"/"arm"). It therefore travels with the model's data
blob: YSM transfer, ysmguard encryption (transit + cache), ContentPolicy/authorization, all unchanged.
A malformed extension (unknown/unused material, missing or non-PNG image) fails the import with an
`ENDFIELD_npr: …` message (`/ysm model errors`).

## 3. Client lookup (endfield_npr)
`AnimatableEntity.getModelHash()` → `ClientModelService.current().catalog().find(hash)` → content
(`RemoteModelContent.cachedOnlyView()` for server models) → `modelFile().getPlayer().descriptor().blobId()` →
`getFileView().readBlobPayload(…)` → scan ModelData field 1 for key `endfield_npr` → ZIP → package.
Loaded once per model hash on a worker thread; GPU textures created on the render thread; no package = generic look
(procedural ramps, no LUT/SDF/highlight/matcap/overlays, generic shading). The client config holds renderer-global
settings only.

## 4. Per-material data consumed by the eye/brow/cloth-parallax features (EyeBrow)
Format: `materials.<glTF material>.{material, maps, params}` (~/ysm/handoff/npr-materials-schema.md; params = the
Unity m_Floats/m_Colors verbatim). All optional; without them the look is unchanged.
- **Iris** = the material with params `_EyeHighLightColor` (HGRP/CharacterNPR_Eye) whose rect equals `iris.material`'s
  (one with `_EyeHighLight` = 1 preferred). Params: `_ParallaxScale` (uv parallax, no texture), `_EyeHighLight`,
  `_EyeHighLightColor`, `_EyeScatteringColor`, `_BaseColor` (.a). Map `baseAlpha` = alpha of its `_BaseMap` as grey
  (scattering weight; missing = 0).
- **Brows/irises for the reveal pass** = every material with params `_EyeHighLightColor` (brows included, even
  without maps): their rects select the quads redrawn through the bangs.
- **Hair** with map `browMask` (`_HairBrowMask`, scale 1) and params `_DrawUnderBrow` = 1, `_HairBrowMaskThreshold`.
- **Cloth parallax**: map `parallax` (`_ParallaxTex`, tiled, `scale`/`offset` = its `_ST`), map `baseAlpha` (the
  parallax colour is masked by `_BaseMap.a`; missing = no parallax colour), params `_UseParallax`, `_ParallaxScale`,
  `_ParallaxMarchNum`, `_ParallaxUseNormal`, `_ParallaxColor`, `_BaseColor`, `_AlphaPremultiply`.
