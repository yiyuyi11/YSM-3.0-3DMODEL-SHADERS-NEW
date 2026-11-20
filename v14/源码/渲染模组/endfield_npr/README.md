# endfield_npr — Endfield-style character shading for YSM under Oculus/Iris

Forge 1.20.1 (47.x) client mod. Requires **YesSteveModel** (`ysm`) and **Oculus** (≥1.6, which in turn needs
Embeddium). Local research use only: no Hypergryph asset is bundled.

## What it does

1. Listens to YSM's `RenderModelEvent` (on YSM's mod event bus). Every YSM character drawn in the level's main pass
   is registered with the crowd LOD (`NprLod`, see *Crowds*), which picks its tier. For an opaque humanoid-import
   mesh in an NPR tier (not GUI, first person, the shader pack's shadow pass) it calls `NativeRenderer.render` with
   `MeshBuffer`, which implements YSM's `VertexBufferAccessor` with the NEW_ENTITY layout: YSM's native library
   skins the mesh and writes the finished vertices (view space) straight into the character's cell of a persistently
   mapped vertex page (FULL characters through a CPU copy, see *Crowds*; YSM's *Vanilla direct* path, no Java
   replay). The event is then cancelled — so neither YSM's toon program nor the shader pack's gbuffer program draws
   the body. The shadow pass is untouched: the character still casts the pack's shadows.
2. A mixin on `GameRenderer#renderLevel` (right after `LevelRenderer#renderLevel` returns — Oculus has finished
   composite + final by then, the hand pass has not cleared the depth yet) draws the body (`npr_character`), overlay
   (`npr_overlay`) and outline (`npr_outline`) passes of all captured characters from the shared vertex pages,
   depth-tested (LEQUAL) against the scene depth.
3. `npr_character.fsh` is the runtime port of the bytecode reconstruction (`../NPR-reconstruction.md`,
   `../endfield_npr.glsl`): ramp lookup with lit-mask alpha, shadow/deep-shadow/lit base-colour selection,
   ramp chroma tint with luminance preservation, view-dominant stylised GGX, cloth spec-ramp, hair view-locked
   Kajiya highlight, skin edge tint (`_SDFRimColor`), face SDF (if a map is supplied), screen-space rim,
   dark-silhouette back light, highlight saturation boost. Per-texel material class comes from YSM's specular atlas
   (blue: 5 eye, 10 cloth, 115-135 hair, 150 skin, 160 face).

MC adaptations (not in the game shader): the lightmap is the ambient, sky exposure replaces the shadow map, the
sun/moon direction comes from the level time, and the ambient on the lit side is scaled down (no HDR tonemapper).

**0.7.0** adds the game's per-material data, read from the model package's `materials` block (written by the
`npr_materials` data tool from the original Unity materials; models without it look as in 0.6.3 except the hair gloss
fallback): property maps (hair gloss, cloth metal/specular/smoothness/AO), emission, the hair line map, normal maps and
the hair split normal, the outline mask with per-material outline values, the iris parallax/highlight/scattering
terms, brows through the bangs, the cloth parallax effect, local block lights and the hair depth rim, plus a disk cache
of the composited maps (`mapCacheMB`). Each feature has a live switch for A/B (`materialMaps`, `irisTerms`,
`browThroughHair`, `normalMapStrength`, `emissionScale`, `localLights`, `rimDepth`). 0.6.3 (mouth-ring fix, sunset /
rain exposure) is included.

**0.7.1** fixes two looks of 0.7.0 seen in game: materials with the hair shader (params with `_AnisotropyIntensity`)
are always shaded as hair, so their prop R (the strand-tangent blend) can no longer act as cloth metal (black hair on
aglina, lastrite, liino, yvonne, zhuangfy, chen…); and metal cloth gets the game's environment reflection × EnvBRDF
(Gens2_11058832 1895-1927), approximated from the light reaching the surface, instead of going black (Pelica's white
panels). It also corrects the tangent sign of normal maps on back faces and adds `eyeNightGlow` (a little self-lit iris
by night). New A/B keys: `propMetal`, `propShadowWeight`, `envReflection`, `eyeNightGlow`.

**0.8.0** adds the game's character rain and wetness (`RainWet`, `npr_rain.glsl`; Chinese section «雨水与湿身» below):
the wet/drop blocks of HGRP/CharacterNPR, _Skin and _Hair translated from the disassembly line by line and checked
against it numerically, the four shared rain textures in the jar, and a per-character rain/wet state for Minecraft
(rain on the head, wading, swimming, drying). New keys: `rainEffect`, `rainStrength`, `wetStrength`, `wadeStrength`,
`rainSmoothTime`, `wetDrenchTime`, `wetMax`, `wetDryDelay`, `wetDrySmoothTime`, `rainTiling`, `rainDebug`.

**0.8.1** fixes the rain running 20× too fast in game: the blocks read the game's `cb1[20].x`, which is Unity's
`_Time.x` = t/20 (ShaderVariablesGlobal byte offset 320 = cb1[20] in the serialized cbuffer table), not t in seconds.
The face drips now scroll 0.04 of the face uv per second and the drop clock runs at the game's pace.

**0.8.2** tuning keys from the first rain test: `skinRampTint` (0.5: the red band at the skin's light/shadow edge, the
ramp chroma tint of skin and face at half strength; 1 = the game's ramp), `rainGlint` / `rainMatcap` (strength of the
view-dependent rain terms: skin/hair drop glints and the face's wet matcap) and `rainDropSpeed` (body, hair and cloth
drops relative to the game's pace; the face drips keep it). All live.

**0.8.3** makes the rain stick to her. The drops, wet patches and streaks used to be placed in one rigid body frame
(feet, body yaw) applied to the skinned vertices, so every sway, breath or hair swing slid the pattern over the
surface. Now the game's object-space inputs come per vertex from a reference skin (`RainAnchors`): the first skin of a
character at a vertex count while it rains on her is converted once into the body frame (the same units, axes and
feet origin as before, so tiling and height ramps keep their meaning) and stored in a second buffer per arena page in
the skin's vertex order (16 bytes per vertex and copy, created only when needed); every later frame, reused skin and
multi-draw reads it as a vertex attribute. A new vertex count (YSM's mesh LOD) or model takes a new reference; MINIMAL
draws no rain. `rainTiling` defaults to 5 (2.2x smaller drops, the size chosen in game; a config still at the old
default 2.25 is moved to 5, any other value is kept, nothing else is touched). The skin's sheen in rain is the game's
GGX r25 (Skin 1168-1184, generated and checked like the other blocks), `rainGlint` scales only the drop glints, and
two new live keys: `rainSheen` (the game's `_CharacterParams13.w` on the skin/hair sheen in rain, value unknown, 1)
and `rainWetReflection` (ours: a water-film reflection on wet skin and hair standing in for the game's separate
reflection pass, which its character programs only feed a wet flag). `eyeDayGlow` (0.05) adds the self-lit iris by
day, blended with the sun into `eyeNightGlow`.

**0.8.4** `eyeShadow` (0.0, live): the eyeball keeps only the eyelid shadow. The sclera (face_01 texels the class map
calls non-skin, inside the profile's face rectangle; the painted lid line counts too) and the iris (EYE class inside
the profile's iris rectangle; the brows, EYE class outside it, are unchanged) move from the body pass's own shading —
toon ramp N·L and view alpha, the pack's shadow map, self-shadow and scene AO — toward fully lit by 1 − `eyeShadow`;
the sky term (under open sky or not) stays. 1 = the 0.8.2/0.8.3 look with the blob-like shadow under the iris. The
eyelid shadow is not body shading: it is the OverlayShadow eye-white card (`M_eyewhiteshadow_common_01`, stencil 4,
multiplied over the finished body, FULL tier only), which every shipped package carries with real data (all 24 roles
and Pelica: `t_actor_common_eyeshadow_01`; the `unity_default_white` placeholders are only hair-shadow cards of
avywen, bounda, karin, seraph, yvonne and the cloth cards of endminf and ikut). Eyes are fully lit without an eyelid
shadow at REDUCED/MINIMAL distance, with `overlay=false`, and in models without a package. Without a package's face
and iris rectangles there is no eyeball discriminator, so `eyeShadow` changes nothing there.

**0.8.5** every drop and streak follows `rainDropSpeed` now, the face drips included (before they kept the game's
pace); new `rainFaceDripSpeed` (1.0, live) is an extra factor for the face drips on top of it.

**0.8.6** fewer, less regular drops that visibly flow (`npr_rain_drops.glsl`, ours). The procedural drops of skin,
hair and cloth (six grid lookups per block: 3 planes × 2 scales) take randoms from their own cell id, so each drop is
stable and handled whole: `rainDropDensity` (0.5) keeps only that share of them on top of the game's wetness threshold;
`rainDropJitter` (1.0) moves each drop anywhere it still fits whole in its cell (the game: ±0.25 cell, and its larger
cloth/hair drops could be cut at the cell border) and scales it 0.7–1.3×; `rainDropSpeedJitter` (0.4) gives each drop
its own appear/fade speed, 0.6–1.4× (falling rain only). The cloth's texture drops and streaks (rain_01 B + normal,
rain_02 B + normal) are thinned by a low-frequency noise mask off the tile grid in `rainTex`, so the 0.2-block tile no
longer repeats visibly. `rainDropSpeed` now defaults to 3 (a body drop appears and fades in about 2 s; 1 = the
game's pace); a config still at 1.0 is moved to 3, any other value is kept (config version 10, in place). All three
new keys are live; density 1, jitter 0, speed jitter 0 give the game's drops exactly (`rain_check.py` runs the hooks at
those values). Offline renders: `tools/rain/drops_render.py`. Not yet checked in game.

**0.8.7** the drops slide (ours, `npr_rain_drops.glsl`). The game's procedural drops never move: their grid coordinate
has no time term, time only runs each drop's appear/fade cycle, and the fade flattens the drop's bump, so the glint
drops out abruptly while the drop is still half visible. Now, in falling rain, the cell grid of the two vertical planes
moves down the anchored body frame at `rainDropSlide` (new, live, 0.05 blocks/s) × a random factor per column
(`rainDropSpeedJitter`), so each drop rides in its own cell (never crosses or is cut by a border) and columns differ;
each drop grows from a point over the first 12 % of the game's visible window (phase 0.2–0.85) and shrinks back to
nothing over its last half, so bump, darkening and glint fade with its area instead of popping. Top faces keep still
drops. Slide 0 plus 0.8.6's neutral values reproduce the game's math (`rain_check.py`: 0 differences, the known hair
case aside). Offline: `drops_render.py` writes `<family>_slide.gif` and `<family>_tracks.png`. Not yet checked in game.

**0.8.8** key light in the Nether and the End, and a fill light for dark scenes (`NprRenderer.frameLight/dimLight/adapt`,
`LocalLights.fill`, `npr_character.fsh` `dimGate`/`keyColor`, `npr_lights.glsl`). Sky-less dimensions (sky type
NONE/END or no sky light) had sky light 0 everywhere, so the shader's key gate `smoothstep(0.5, 0.93, sky light)` shut
the key and only the lightmap ambient was left (flat), and their fixed times made the Nether "night" (eyeNight*,
`adaptNightMax`, moonlight) and the End "noon" (with the adaptation reading no ambient: gain 8, washed out). Now, with
`netherLight` / `endLight` > 0, the gate is open there (`SceneShadow2.y`; the pack's shadow map still applies), the key
is strength × the local lightmap ambient max3 (`SceneShadow2.z`; it follows the local light level, 1 = the overworld
noon key-to-ambient ratio), the eye settings and the exposure ceiling sit halfway between night and day, and the
adaptation measures key + 0.35 × the vanilla lightmap ambient at sky 0 (a port of `LightTexture` for that row).
Nether: the camera-follow key, coloured by vanilla's gaussian blend of the biome fog colours at the camera (60 % hue,
40 % white; `netherLightColor` overrides, `0,0,0` = biome). End: `endLightColor` (cool purple) from the shader pack's
light direction (third row of its shadow modelview, read like the scene shadows), without a pack from a fixed oblique
direction 53° up. New live keys (defaults): `netherLight` 1.0, `netherLightColor` 0,0,0, `endLight` 1.0,
`endLightColor` 0.72,0.62,1.0. Fill light: one virtual point light per FULL character, 1.75 blocks from the head
centre on the camera side (yaw + 12°, at least 30° up), shaded as a local light (Type0, face SDF normal, GGX), not
shadowed, lit-side luminance `fillLight × clamp((fillTarget − L)/fillTarget, 0, 1) × fillTarget` with L her light
level (the adaptation measure); the adaptation counts the fill at the viewer. It takes the slot after the found block
lights only when one of the 4 is free (four lit emitters in reach mean a bright spot anyway); REDUCED/MINIMAL
characters get none. Keys: `fillLight` 0.6, `fillTarget` 0.5, `fillColor` 0.92,0.96,1.0. Unchanged elsewhere: in the
overworld `SceneShadow2.yz` are 0 (gate `max(skyX, 0)` = skyX, `keyColor()` = `LightColor`), the overworld light,
adaptation and exposure code paths compute the same values in the same order, and by day L ≥ fillTarget so no fill
slot is written; `netherLight = endLight = 0` gives the 0.8.7 path in those dimensions, `fillLight = 0` no fill.
Offline numbers: `python3 tools/dim_light_eval.py`. Not yet checked in game.

**0.8.8（中文）** 下界与末地的主光、暗处补光。无天空的维度（天空类型 NONE/END 或没有天空光）各处天空光都是 0，
shader 的主光门 `smoothstep(0.5, 0.93, 天空光)` 把主光关掉，只剩光照图环境光（显得扁平）；固定时间又让下界一直
是"夜晚"（夜间眼睛设置、`adaptNightMax`、月光），末地一直是"正午"（曝光适应量不到环境光，增益 8，发白）。现在
`netherLight` / `endLight` 大于 0 时这些维度的主光门打开（光影包的阴影图照常生效），主光强度 = 强度 × 当地光照图环境光
（随当地亮度变化，1 = 主世界正午的主光/环境光比例），眼睛设置与曝光上限取昼夜中间值，曝光适应按主光 + 0.35 ×
原版天空光为 0 时的光照图环境光计算。下界：跟随相机的主光，颜色取相机处原版群系雾色的高斯混合（60% 色相 +
40% 白，`netherLightColor` 可覆盖，`0,0,0` = 群系）。末地：冷紫色 `endLightColor`，方向取光影包阴影矩阵的光照方向，
没有光影包时用固定的 53° 斜上方。补光：每个 FULL 角色一个虚拟点光，在相机一侧、头部中心 1.75 格处（偏航 +12°，
至少仰 30°），按局部光源着色（Type0、脸部 SDF、GGX），不受阴影；亮面亮度 `fillLight × clamp((fillTarget − L)/fillTarget,
0, 1) × fillTarget`，L 为角色所受的光（曝光适应用的同一量），白天为 0；只在 4 个局部光槽位有空时占用。新增即时生效
的键：`netherLight` 1.0、`netherLightColor` 0,0,0、`endLight` 1.0、`endLightColor` 0.72,0.62,1.0、`fillLight` 0.6、`fillTarget`
0.5、`fillColor` 0.92,0.96,1.0。主世界不变；`netherLight = endLight = 0` 即 0.8.7 的效果，`fillLight = 0` 关闭补光。
离线数值：`python3 tools/dim_light_eval.py`。未进游戏验证。

**0.8.9** bright eye catchlights. The white dots of the eyes are the iris texture's bright texels outside the iris
circle (the game's highlight part, × the HDR `_EyeHighLightColor`, 1.0–4.2 across the 24 models, e.g. (2.24, 1.53,
1.74)). In the game the eyelid shadow over the iris (OverlayShadow stencil 20, × (0.60, 0.67, 0.71)) multiplies before
the tonemap, so the dots stay white; here it multiplied the finished display colour and turned them grey (0.64 instead
of 0.98 at noon). `eyeHighlightOverShadow` (true, live) gives those texels their own receiver class (7) that no overlay
layer draws over. `eyeHighlightBoost` (0.4, live) adds texel × `_EyeHighLightColor` × this, linear, after exposure,
scaled by how lit the catchlight already is (smoothstep 0.02–0.5 of its lit colour) and by the texel's whiteness: the
HDR headroom and bloom the display colour cannot hold. It follows the light (near white in shade and at night, with
the fill light in caves; dark in complete darkness), so it is not a self-glow. 0 and false = 0.8.8.

**0.8.9（中文）** 眼睛高光点变亮。眼睛里的白色高光点是虹膜贴图上虹膜圆之外的亮像素（游戏的 highlight 部分，乘 HDR 的
`_EyeHighLightColor`，24 个模型在 1.0～4.2 之间，例如 (2.24, 1.53, 1.74)）。游戏里虹膜上的眼皮阴影（OverlayShadow，
stencil 20，× (0.60, 0.67, 0.71)）在色调映射之前相乘，高光点仍然是白的；我们这里是乘在最终显示色上，把它压成了灰色
（正午 0.64，本应 0.98）。`eyeHighlightOverShadow`（true，即时生效）让这些像素用单独的接收类别（7），任何叠加层都不画在
它上面。`eyeHighlightBoost`（0.4，即时生效）在曝光之后加“像素 × `_EyeHighLightColor` × 该值”的线性增量，并按高光点
已受到的光（它自身受光颜色的 smoothstep 0.02–0.5）和像素的白度加权，补上显示色装不下的 HDR 余量和泛光。它跟着光走
（阴影里、夜里、洞里有补光时都接近白色；完全无光时是暗的），不是自发光。设 0 和 false 即 0.8.8。未进游戏验证。

**0.8.10** eye-white brightness keys. The eye whites (sclera) are shaded as face (face-texture texels the class map
calls non-skin), so none of the eye keys (`eyeBrightness`, `eyeNightBrightness`, `eyeDayGlow`, `eyeNightGlow`) reach
them. `scleraBrightness` (1.0, live) scales their displayed colour (after the display transform, so 0.8 = 80 %);
`scleraNightBrightness` (1.0, live) is an extra night factor, blended with the sun like `eyeNightBrightness`. Both also
act on the lid line painted in the face texture (the same texels). Defaults 1 = 0.8.9.

**0.8.10（中文）** 眼白亮度配置项。眼白（巩膜）按脸部着色（脸部贴图里类别图判为非皮肤的像素），所以
`eyeBrightness`、`eyeNightBrightness`、`eyeDayGlow`、`eyeNightGlow` 都管不到它。`scleraBrightness`（1.0，即时生效）
乘眼白的显示颜色（在显示变换之后，0.8 就是 80% 亮度）；`scleraNightBrightness`（1.0，即时生效）是夜间的额外系数，
和 `eyeNightBrightness` 一样随太阳过渡。两者同样作用于脸部贴图上画的眼线（同一类像素）。默认 1 即 0.8.9。未进游戏验证。

**0.8.11** overlay layers before the tonemap, as in the game. The OverlayShadow layers (eyeshadow_01's eye-white
shadow and eyelid shadow over the iris, hairshadow_01's bangs shadow) multiply the game's HDR scene colour before its
tonemap; ours multiplied the displayed colour, which made them far darker in bright light. Offline at noon (Pelica):
the upper eye white under its shadow 0.33 instead of the game's 0.75, the iris under the eyelid shadow 0.37 instead of
0.43, the skin under the bangs shadow 0.75 instead of 0.93. The body pass now writes its pre-tonemap luminance (log2,
8 bits) into the receiver target's green channel (RG8), and each layer multiplies the displayed colour by
tone(Y × layer) / tone(Y) per channel, tone being the display transform of a grey: 0.75, 0.43 and 0.94 at noon; in the
dark both orders agree. `overlayGameOrder` (true, live); false = 0.8.10.

**0.8.11（中文）** 叠加阴影层改在色调映射之前相乘，和游戏一致。OverlayShadow 层（eyeshadow_01 的眼白阴影和虹膜上的
眼皮阴影、hairshadow_01 的刘海阴影）在游戏里乘在色调映射之前的 HDR 颜色上；我们乘在最终显示色上，亮处因此暗很多。
离线估算正午（佩丽卡）：眼白上半部 0.33（游戏 0.75），虹膜被眼皮阴影盖住的部分 0.37（游戏 0.43），刘海阴影下的皮肤
0.75（游戏 0.93）。现在身体 pass 把色调映射前的亮度（log2，8 位）写进接收目标的绿色通道（RG8），每层按
tone(Y × 层) / tone(Y) 逐通道乘到显示色上（tone 为灰色经过显示变换），正午分别为 0.75、0.43、0.94；暗处两种顺序
结果一样。`overlayGameOrder`（true，即时生效）；false 即 0.8.10。未进游戏验证。

**0.8.12** cloth depth bias, a render-side fallback for shallow clipping. Cloth texels (the class map's cloth,
accessories included; not skin, face, hair or eyes, nor the face texture) are depth-tested `clothDepthBias` blocks
nearer the camera (0.01 = 1 cm, live; 0 = off): the vertex moves along its own view ray, so it covers the same pixels,
and skin that pokes up to that far through the clothes stays hidden. In the YSM anti-clip harness 99% of the garment's
vertex-frames lie less than 0.8 cm inside the body (p99 depth: chen 0.77 cm, Pelica 0.83 cm), so 1 cm leaves the deepest
1%. The class is read per vertex from the class
atlas (Sampler3, texelFetch in the vertex shader); models without a class map get no bias. The gbuffer depth prepass
keeps the true depth, which the biased cloth passes under LEQUAL. Too large a bias lets clothes cover fingers resting
on them.

**0.8.12（中文）** 衣服深度偏移，渲染层兜底浅穿模。衣服像素（类别图的布料，含饰品；不含皮肤、脸、头发、眼睛和脸部贴图）
在深度测试时往镜头方向靠近 `clothDepthBias` 格（0.01 = 1 cm，即时生效；0 = 关）：顶点沿自身视线移动，屏幕上覆盖的
像素不变，穿出衣服不超过这个距离的皮肤就被盖住。YSM 防穿模离线测试里，衣服顶点 99% 的帧陷进身体都不到 0.8 cm（p99：
chen 0.77 cm，佩丽卡 0.83 cm），1 cm 只剩最深的 1%。类别在顶点着色器里按顶点从类别图读取；没有类别图的模型不偏移。gbuffer 深度预写保留真实
深度，偏移后的衣服在 LEQUAL 下照样通过。偏移太大时，搭在衣服上的手指会被衣服盖住。未进游戏验证。

## Configuration

`config/endfield_npr/endfield_npr.properties` (written on first start) holds renderer-global settings only. Everything
per model — ramps, skin LUT, face SDF/mask/highlight map, iris matcap, OverlayShadow textures and layers, material
values (smoothness/specular/shadow colour per class, rim tint, hair anisotropy, outline widths and colours) — ships
inside the model as its NPR package (see *Model packages* below). A model without one draws with the generic look.

Runtime keys (the file is re-read when its mtime changes, checked once a second):
`enabled`, `outline`, `overlay`, `antiAliasing` / `aaSamples` (see *Antialiasing*), `lodYsmDistance` (blocks; beyond
it YSM draws the character, 0 = never), the crowd
keys (`lodTiers`, `lodFullSize`, `lodReducedSize`, `lodMaxFull`, `lodMaxNpr`, `lodHysteresis`, `animLod`,
`animLodSizes`, `animLodMaxFullRate`, `animLodOffscreenInterval`, `gpuPersistentBuffers`, `gpuCoherentMapping`,
`gpuMultiDraw`, see *Crowds*), `debugTiming` (logs `Endfield NPR timing over N frames: frame … | capture … |
upload … | draw cpu … | draw gpu … | ysm clones … | extract …` every `debugTimingFrames` frames),
`debugClones`/`debugCloneSpacing` (a debug crowd of copies of the local player's character), `debugView`
(1 view-space normal, 2 smooth N·L, 3 N·L of the flat triangle normal, 4 r = ramp lit alpha, g = view alpha,
b = rim + back light, 5 r = self-shadow Y, g = AO, b = scene shadow X — for checking shading artefacts),
`eyeBrightness` (1.0, multiplier on the final colour of eye-class texels — the iris and the brows, which share the
class; the sclera is face class — after the iris matcap, before the display transform, never on the outline),
`eyeNightBrightness` (0.8, an extra eye multiplier blended in by night with the same sun factor as `adaptNightMax`:
the iris takes no AO or scene shadow and the night adaptation lifts it, so the eyes looked self-lit),
and `scleraBrightness` / `scleraNightBrightness` (1.0 / 1.0, the eye whites' displayed brightness and its extra night
factor; the eye keys above never reach the sclera). Keys missing from an existing file are added with their defaults
on load.

The file carries `configVersion` (10). Version-8 and version-9 files are updated in place without a backup: every value
is kept, only `rainDropSpeed` moves to 3 if it still holds the old default 1.0 and, from 8, `rainTiling` to 5 if it
still holds 2.25. Files of version 7, 6, 5 and 4 are migrated in place: backed up as
`endfield_npr.properties.v<old>.bak`, the user's values kept, new keys added with their defaults and keys the code no
longer knows dropped (`lodDistance`, `overlayTint`, `overlayAngleRange`, and from 7 on the per-model material keys
`skin` … `outlineColorHairEye`, which now live in the model package); a version-4 file also takes the 0.5.0
`sceneShadowStrength` (0.5, retuned with the new `sceneShadowAmbient`); every one of them takes the 0.6.3
`adaptStrength` (0.9). Any other version (or no key) is moved to the
backup and replaced by the current defaults, so a stale file from an older package cannot override tested defaults.
The canonical file (every key, with its comment) is `config-default/endfield_npr.properties`.

## Rendering path and performance (0.3.0)

YSM (`NativeRenderer.draw`) picks its output path from the consumer: a `VertexBufferAccessor` whose format is
`DefaultVertexFormat.NEW_ENTITY` gets the direct path (`ysm$reserve` → native write → `ysm$advance`); anything else
gets the fallback, where native code fills a shared `int[]` and `FallbackVertexWriter` replays every vertex through
`VertexConsumer.vertex(...)`. Up to 0.2.1 the mod used such a plain consumer, then re-submitted the recorded vertices
through `Tesselator`'s `BufferBuilder` once per pass (body, overlay, outline). With a shader pack active Oculus extends
every `BufferBuilder` begun with NEW_ENTITY to its 56-byte entity format and, per quad, computes tangents and
**overwrites the vertex normals with the flat face normal** (`MixinBufferBuilder.fillExtendedData`): three Java
passes over ~234k vertices per frame, and the faceted triangles on the dress, hair and skin. 0.3.0:

- `MeshBuffer` is the accessor itself (direct native write, no `int[]` carrier, no replay, no BufferBuilder, so
  Oculus never touches the vertices: YSM's smooth normals reach the shader unchanged).
- One `glBufferData` upload per character per frame into a VAO/VBO owned by the mesh; every pass draws from it with
  a shared QUADS index buffer. The attribute pointers are set directly, because Oculus also redirects
  `VertexFormat.setupBufferState` for NEW_ENTITY to its extended layout.
- No model-view stack / projection juggling per draw (the captured matrices go straight to the uniforms), no
  per-frame `Vector3f`/`Matrix4f`/lambda allocations, config values parsed once per (re)load instead of
  `split`/`parseFloat` for ~40 uniforms per pass, model-profile atlas rectangles precomputed.
- Distance LOD (`lodDistance`, default 48 blocks). Off-screen entities never reach the mod: YSM's renderer only runs
  for entities that pass vanilla frustum culling.

Measured on the harness (llvmpipe software GL, 8 cores, Bliss, 1280×720, FOV 50, plaza close-up, 120-frame windows
of the `debugTiming` log; 0.2.1 measured with the same timer patched into its source):

| build (same pose) | frame ms (fps), per window | mean | capture | upload | draw CPU | draw GPU |
|---|---|---|---|---|---|---|
| Bliss, character drawn by YSM itself (`enabled=false`) | 490, 514, 522 | 509 ms (1.97 fps) | – | – | – | – |
| 0.2.1 (fallback replay + 3× BufferBuilder) | 632, 646 | 639 ms (1.56 fps) | 10.2 ms | in draw | 182 ms | 151 ms |
| 0.3.0 (direct native write + one VBO) | 501, 523, 546, 546, 544 | 532 ms (1.88 fps) | 7.7 ms | 1.06 ms | 57 ms | 92 ms |

The machine was fully loaded (load average ≈ 10 on 8 cores), so absolute frame times drift by ±20 ms between
sessions; within one session the NPR pass costs +22 ms (session 1: 490 vs 512) and +27 ms (session 2: 518 vs 545)
over YSM's own path, against ≈ +130 ms for 0.2.1.

"draw cpu" is the CPU time of issuing the three passes; on llvmpipe it also contains the vertex processing, which
Mesa's draw module does on the calling thread [not measured separately]. On a hardware GPU the 0.2.1 overhead should
be dominated by the Java replay/re-submission that 0.3.0 removes [inference, not measured here].

### The faceted triangles (fixed in 0.3.0)

Evidence chain, same pose and light: YSM's importer output is smooth (offline `HumanoidImport` dump of
`pelica.vrm`: 77,948 shared vertices, mean corner-vs-face normal angle 15.0°, 3.4 % of corners within 0.5° of
the face normal); the native output captured in game is smooth too (234k NEW_ENTITY vertices: mean 14.8°, 2.9 %
within 0.5°); `debugView=1/2` (vertex normal, smooth N·L) show no facets while `debugView=3` (flat
`cross(dFdx, dFdy)` normal) shows the same faceted pattern as 0.2.1. The flat normals came from Oculus rewriting the
normals of every QUADS primitive submitted through a `BufferBuilder` (see above), so they affected the dress, skin,
hair and the outline extrusion; byte quantisation (1/127) and the ramps were not the cause. Bypassing the
`BufferBuilder` removes them at the source, without any smoothing filter.

### Iris shading (0.3.1)

With the real normals restored (0.3.0) the irises went dull and darker, the right one most. The cause is again the
normals, now YSM's: the authored iris normals in `pelica.vrm` are all exactly (0,0,1), the head's forward axis.
YSM's import (`HumanoidImport.smoothFaces`) bends every face-part normal (its `FACE_PART` pattern also matches
`iris`/`eye`/`brow`) 85 % toward an ellipsoid fitted around the face. That fans the iris normals sideways by up to
±0.57 and tilts them down (offline import dump), which `debugView=1` shows as the two irises in different colours.
0.2.1 hid this because Oculus replaced the normals with flat face normals of the iris dome.

0.3.1 follows the reference Eye stage (`ue5ref Shaders/Stages/Eye.ush` stages 06-08), where the shading normal is
the authored normal. For texels inside the profile's iris rectangle (`FaceFlags.w`), the shader uses `HeadForward`
as `shadeN` (the matcap sphere basis). The diffuse normal is `shadeN` tilted by the iris sphere,
`normalize(T·sx·(-1/8) + B·sy·(-1/8) + shadeN·sz)` inside the circle and `shadeN` outside. That normal drives the
ramp, the view alpha and the back light. The body rim is also no longer applied to the eye class, because the
Eye/Brow programs have none. Brows keep their mesh normals; their authored ones already follow the forehead.
Models without an iris rectangle in their profile are unchanged.

## Colour, exposure and shadows (0.4.0)

**Display transform.** Endfield's lit colour goes through its post LUT before display:
`ue5ref Shaders/EndfieldPostProcess.ush` `EFP_LutBuilderModifiedFromLogColor` (TONEMAPPING_ACES_MODIFIED):
sRGB-linear → AP1, the fitted ACES curve `x(2.785x+0.108)/(x(2.936x+0.887)+0.807)` per channel, saturation 0.93
around AP1 luminance, AP1 → sRGB, highlights pulled toward their hue. This pass draws after the shader pack's own
tonemap, so up to 0.3.1 the character skipped all of it and kept or raised the albedo saturation while clipping
(cheek sat 0.19-0.25 at val 1.00, forearm clipped). `npr_character.fsh displayTransform()` applies the same LUT
(neutral grading; the game's grade uniforms are not captured) after `tonemapExposure` (1.3). `postSaturation`
(0.85) stands in for the uncaptured grade and is the only value tuned purely by eye, against ref 07's measured
saturations. Offline the curve alone maps Pelica's cheek albedo (sat 0.23, val 0.81) to sat 0.13 / val 0.90 at
exposure 2, which shows the curve accounts for most of the gap to the game's pale skin.

**Linear-space mixes.** The game mixes linear colours. The 0.3.x port mixed display values, which doubles the red
shift of the `_SDFRimColor` edge tint (a gamma-stored Unity colour). The ramp chroma tint and the highlight
saturation boost had the same issue. With `linearColorMath` (on), all three are mixed in linear space.

**Eye adaptation.** The pack adapts its world (Bliss: `exposure = 0.18 / log2(2.5 L + 1.045)` of the scene's
log-average luminance, `deferred.vsh`), but this pass came after it with light from Minecraft's sun, sky and rain
values, so dusk and overcast left the character 2-3x too dark. The exposure is multiplied by
`(adaptReference / ownLight)^adaptStrength`, where ownLight is this model's own light level (key luminance plus
0.35 x ambient under open sky; the lightmap ambient in the shade). It is 1.337 at clear noon. I also tried
measuring the finished frame (blit plus PBO readback of its log-average luminance) and dropped it: the same light
gave 1.45x different values for two framings, which would make her pump with the camera. With the sun below the
horizon the gain is capped at `adaptNightMax` (2.5, blending to the day ceiling over sunrise and sunset), so
nights stay darker than dusk.

0.6.3 retuned it against the Bliss frames (`tools/light_eval.py`, fed by the harness shots of 2026-11-17 with
adaptation on and off at time 6000, 12500, 18000 and in thunder; it reproduces the character's mean screen
luminance within 3%). With `adaptStrength` 1.3 and the light level taking the full rain/thunder dimming, the
character came out against the grass, relative to clear noon, 1.5x too bright at sunset, 2.8x in thunder and
about 3.7x in rain: Bliss keeps rainy scenes dark on screen (grass 0.2-0.3 of noon) while the gain lifted her
2.5-2.9x. Now `adaptStrength` is 0.9 (sunset 1.0x) and the level she adapts to leaves out the weather
(`adaptWeather` 0: the clear-sky level, with the rain factor of the key light and the 5/16 rain and thunder terms
of `getSkyDarken` removed; 1 = the old full dimming). She still darkens with the rain-dimmed key light and lightmap
(thunder 0.7-0.8x, rain about 1.3x of the scene's ratio, within the shots' rain-noise). Night is unchanged (cap
2.5). `adaptLegacy=true` restores the 0.6.2 adaptation for an A/B comparison; with `debugTiming` the log prints
the adapted level, this frame's level with the full weather dimming and the exposure.

**Scene shadows.** The pack's sun shadow map (Iris shadowtex1 and `ShadowRenderer.MODELVIEW/PROJECTION`, reached
by reflection in `PackBuffers.java`; Bliss distortion constants in `sceneShadowDistort`) feeds Endfield's scene
shadow X. Per `ue5ref EndfieldSceneShadowMath.ush`, `X = lerp(1, raw, sceneShadowStrength)` takes the
lit → indirect split (indirect colour, shadow ambient, specular receiver) instead of a black multiply. 3x3 PCF
softens the edge. Occluders closer than `sceneShadowSelfDistance` (1 block) along the light are the character
herself (she is a caster in the pack's shadow pass); they are ignored here, her own shadow comes from the self-shadow
map (0.5.0). Trade-offs: the distortion is pack-specific (other packs need their own constants, or
`sceneShadow=false`), and the reflective access depends on Oculus internals (any failure just turns the input off
with one warning).

## Overlay shadows, self-shadow and ambient occlusion (0.5.0)

**OverlayShadow cards (eye corners, upper eyelids, bangs on the forehead).** In the game the eye area is finished by
three multiplicative HGRP/CharacterNPR_OverlayShadow materials on two helper meshes (prefab SkinnedMeshRenderer
slots: `eyeshadow_01` slot 0 `M_eyewhiteshadow_common_01`, slot 1 `M_eyeshadow_common_03`; `hairshadow_01`
`M_hairshadow_common_01`; data in `~/ysm/overlay`). Up to 0.4.0 the cards were dropped from the VRM, so the eye
corners lacked them.

- Model (`fix_pelica.py`): each card keeps its geometry and gets a MASK material over its own fully transparent
  64x64 stand-in texture, its uvs moved into the middle half (`0.25 + 0.5 uv`). YSM's own shader and shader packs
  discard every card texel, and no filter tap reaches a neighbouring atlas region (the earlier 3x3 untextured tile
  bled into white jagged rims with shaders off). The other atlas regions did not move (GenRect before/after).
- Identification: by atlas rectangle, not by the specular class map (its half-resolution class of a tiny tile was
  unreliable). The model package carries one `overlays` entry per material, in draw order, each with its card's
  rectangle (the importer resolves `card.material` + `card.uv` to the atlas texels the card's own 0..1 uvs cover).
  `MeshBuffer.cardRuns` finds the card's quads once (YSM writes a model's vertices in a fixed order) and
  re-checks them per frame, so each layer draws only its card's quads; `eyeshadow_01` is drawn twice, once per slot.
- Shading (`npr_overlay.*`, decompiled `OverlayShadow_CharacterNPR_OverlayShadow.glsl` 89-94 / 629-644, ue5ref
  `ShadowOverlay.ush`): `shadow = lerp(t, (1,1,1,t.r), _UseGrayAsAlpha) * _BaseColor`, output
  `lerp(1, shadow.rgb, shadow.a)` blended DstColor x Src over the finished body, depth-tested without depth writes;
  the card slides by `-cameraRight * light.x * _ShadowAngleRange`. The 32x32 `_BaseMap` textures come with the model
  package (grey in red, so `_UseGrayAsAlpha=1`; `eyeshadow_03` uses `eyeshadow_02_M`). A layer whose card has no
  quads is skipped.
- Receivers: `_ShadowOverIris` is not read by any shipped OverlayShadow program variant (checked in the DXBC of all 16
  programs); it is the stencil reference, compared under read mask 20 (`EFL_ShadowOverlayStage_Receiver`; skin, face
  and hair write stencil 36, the eye/brow programs set bit 16). So the eye-white shadow (4) darkens the skin/face
  texels under the card (the painted sclera and lid line) and the eyeshadow (20) the iris and brow texels. Minecraft's
  target has no stencil to spare: the body pass writes its receiver class into a second colour attachment
  (`ReceiverTarget`, R8) and the overlay pass compares it per pixel. `_DisableDrawUnderHair = 0` (the bangs' shadow)
  suppresses the layer where hair is the receiver, as the bytecode does (the property's label reads the other way).

**Self-shadow Y (hair on the face, arms on the body).** Endfield resolves the character's own shadow separately from
the scene shadow, from a character-only shadow atlas along the character's main light (ue5ref
`EndfieldMainSelfShadowMask.usf`, `EndfieldCharacterSelfShadow.ush`). `SelfShadow.java` draws the captured mesh into
an R32F caster map (`selfShadowResolution`, 1536) with an orthographic projection fitted to the mesh in the frame of
the NPR shading light (exactly on a fresh skin's frame, from its box's corners on reused frames; see *Render-thread
costs*), so the shadows agree with the toon terminator. Iris and brow texels do not cast;
face casters are tagged (+2), and face receivers ignore them (no nose or cheek shadows across the SDF-shaded face,
while the bangs still shade the forehead and the chin the neck). The receiver is offset toward the light and along
its normal by `(1 - clamp(N.L, 0, 0.9)) * selfShadowBias` as in the reference, then filtered with 4x4 taps. Y enters
like the reference's property shadow (`EFL_OfficialDiffuse`: receiver = Y x ao, lit = min(Y, ramp.a)): shadowed
texels take the material's own toon shadow colour, which reads clearly without going black. The iris takes none.

**Shader-pack AO.** Up to 0.4.0 she was cancelled from the gbuffer, so the pack's SSAO and contact shading never saw
her. With a pack, `DepthPrepass` now draws her depth into the pack's gbuffer depth at capture time (raw GL: Oculus
locks depth/colour writes for non-Iris ShaderInstances in the gbuffer phase; the GL state is saved and restored
exactly). Colour buffers are not touched; the NPR pass covers her pixels later. Both depth draws compute
`gl_Position` identically and declare it `invariant`, so the NPR body's LEQUAL test passes on the prepass. Bliss's
SSAO then darkens the ground around her feet (measured: ground strip at her feet 124.5 vs 130.0 grey without the
prepass, Bliss noon). For her own AO, `sceneAO()` evaluates the pack's SSAO (Bliss `composite.fsh` `SSAO()`: 7 spiral
taps on depthtex1 with the same radius terms) at her pixels and scales her ambient light only, as Bliss does to its
indirect light. Bliss rotates the spiral per pixel and frame, denoises it and then applies `pow(ao, 6)`; one frame of
that noise through pow 6 showed as stipple over her flat colours and darkened the whole figure (seen in game), so the
spiral phase is fixed, the result 2x2-averaged and `aoCurve` is 2.

**Glass and water in front of her.** With the prepass her depth is also in the pack's depth, and the pack lights the
gbuffer data behind her (terrain or sky drawn earlier) at her depth. Where a translucent surface lies in front of
her, the old main-depth test hid her and that silhouette showed through as a ghost (seen behind glass in game). After
a prepass the NPR passes therefore depth-test against the pack's opaque-only depth (Iris depthtex1, which holds her
prepass, without depth writes) through `ReceiverTarget`'s framebuffers, and read the full depth: where it is nearer
than her fragment, glass or water covers her and she is blended at `translucentSeeThrough` (0.8) over the pack's
translucent image (the pack's translucent layer cannot be re-applied on top of her). Without a prepass the passes
keep Minecraft's main depth as before. Water is the exception: Bliss refracts what lies behind the water surface
sideways, so the pack-lit gbuffer behind her legs showed through the water beside them (seen in game). While she
touches water (`Entity.isInWater`) there is no prepass, and the submerged part is hidden by the water surface as in
0.4.0.

**Scene shadow retune.** With `sceneShadowStrength=0.6` and the sky-light ambient unchanged in the shade, a canopy
darkened her by only ~7% in display value (offline: lit light 1.35 vs 0.6-shadowed 1.25 through the display
transform). The lightmap ambient does not see the canopy, so inside the pack's sun shadow under open sky the ambient
keeps `sceneShadowAmbient` of itself. Measured in Bliss at noon, mean grey of her body, under a 7x7 stone roof 5
blocks up (open sky: 148.7): strength 0 = 116.6 (-22%, the roof's lower sky light alone), 0.85/0.55 = 56.4 (-62%),
0.6/0.8 = 82.6 (-44%), **0.5/0.85 = 89.5 (-40%, the defaults)**. A leaf checkerboard 5 blocks up draws a clear,
soft dapple on the ground around her. Indoors (no sky light) the ambient term changes nothing.

All three inputs switch off on their own when the pack lacks the buffer (no Oculus, no pack, no shadow map, a changed
Oculus): one warning, and the term is 1. `debugView=5` shows them (r self-shadow Y, g AO, b scene shadow X).

## Crowds: LOD tiers, animation LOD, persistent buffers, multi-draw (0.5.0, merged)

### Research notes

| Topic | Source | What this mod takes from it |
|---|---|---|
| Driver overhead (AZDO) | Everitt, McDonald, Sellers, Foley, *Approaching Zero Driver Overhead in OpenGL*, GDC 2014 ([GDC Vault](https://gdcvault.com/play/1020791), [Khronos slides](https://www.khronos.org/assets/uploads/developers/library/2014-gdc/Khronos-OpenGL-Efficiency-GDC-Mar14.pdf)); NVIDIA, *Beyond Porting* (Steam Dev Days 2014, [PDF](https://ozone3d.net/dl/201401/NVIDIA_OpenGL_beyond_porting.pdf)) | stream through a persistently mapped buffer guarded by fences instead of per-frame `glBufferData`; put per-draw data in buffers and issue one multi-draw per state group instead of per-object uniforms + draws |
| Persistent / coherent mapping | [ARB_buffer_storage](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_buffer_storage.txt) (core 4.4) | immutable storage mapped once with `MAP_PERSISTENT`; with `MAP_COHERENT` "if the client performs a write, then in subsequent commands the server will see the writes", without it the write must be followed by `glFlushMappedBufferRange` (`gpuCoherentMapping=false`) |
| Fenced ring buffers | Khronos wiki [Buffer Object Streaming](https://wikis.khronos.org/opengl/Buffer_Object_Streaming), [Sync Objects](https://wikis.khronos.org/opengl/Sync_Objects); [glClientWaitSync](http://opengl.org/sdk/docs/man4/html/glClientWaitSync.xhtml) | a region may be rewritten only after the fence placed after its last use has signalled; one fence per frame, a ring of 8 |
| Multi-draw indirect | [ARB_multi_draw_indirect](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_multi_draw_indirect.txt) (core 4.3), [ARB_base_instance](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_base_instance.txt) (core 4.2) | `DrawElementsIndirectCommand {count, instanceCount, firstIndex, baseVertex, baseInstance}`; `baseInstance` offsets instanced attributes, so an instanced 0..N stream gives every draw its record index without `gl_DrawID` (ARB_shader_draw_parameters is GL 4.6) |
| GPU-driven pipelines | Haar & Aaltonen, *GPU-Driven Rendering Pipelines*, SIGGRAPH 2015 ([PDF](https://advances.realtimerendering.com/s2015/aaltonenhaar_siggraph2015_combined_final_footer_220dpi.pdf)) | shared vertex pages + per-draw records + indirect commands. Their GPU cluster/occlusion culling is not used: at most 128 characters, already frustum-culled per entity by vanilla before YSM renders them |
| GPU skinning | NVIDIA, *Skinned Instancing* ([whitepaper](https://developer.download.nvidia.com/SDK/10/direct3d/Source/SkinnedInstancing/doc/SkinnedInstancingWhitePaper.pdf), 2007); *GPU Gems 3* ch. 2 [Animated Crowd Rendering](https://developer.nvidia.com/gpugems/gpugems3/part-i-geometry/chapter-2-animated-crowd-rendering); Remedy, *Large Scale GPU-Based Skinning for Vegetation in Alan Wake 2*, GDC 2024 ([PDF](https://media.gdcvault.com/gdc2024/Slides/GDC+slide+presentations/Kandar_Kiyavash_Large_Scale_GPU.pdf)) | bone palette per instance, skinning in the vertex shader or once per frame in a compute pass whose output every later pass reads; evaluated below, not implemented |
| Animation update rate | Unreal [Animation Budget Allocator](https://dev.epicgames.com/documentation/en-us/unreal-engine/animation-budget-allocator-in-unreal-engine) (significance-ranked throttling to a ms budget: lower update rate, optional interpolation) and [Update Rate Optimizations](https://dev.epicgames.com/documentation/en-us/unreal-engine/animation-optimization-in-unreal-engine) (update rate by screen size); Unity [SkinnedMeshRenderer.updateWhenOffscreen](https://docs.unity3d.com/ScriptReference/SkinnedMeshRenderer-updateWhenOffscreen.html) ("If disabled, this also disables updating animations") and [AnimatorCullingMode](https://docs.unity3d.com/ScriptReference/AnimatorCullingMode.html) (CullUpdateTransforms / CullCompletely when off screen) | intervals 1/2/4/8 by projected size, a cap on full-rate characters ranked by size, staggered phases, little pose work off screen, reuse of the last skin between updates |

### What Minecraft 1.20.1 and Oculus allow

Minecraft asks GLFW for a 3.2 core, forward-compatible context; drivers return their highest core version, and
Forge's early window already asks for 4.6 down (harness: `Requested GL version 4.5 got version 4.5`, llvmpipe / Mesa
25.2 with ARB_buffer_storage, ARB_multi_draw_indirect, ARB_base_instance). Everything beyond 3.2 is decided at runtime
from LWJGL's `GLCapabilities` function pointers:

| Feature | Needs | Fallback |
|---|---|---|
| Persistent vertex pages | `glBufferStorage` (GL 4.4 / ARB_buffer_storage) | `glBufferData(null)` pages filled by one `glBufferSubData` per capture |
| Per-draw records | uniform block (GL 3.1 core) | — |
| One draw per group | `glMultiDrawElementsIndirect` (4.3) + base instance (4.2) + `glVertexAttribDivisor` (3.3) | `glDrawElementsBaseVertex` (3.2 core) per character, record index as a constant `glVertexAttrib1f` |
| Fences | `glFenceSync` (3.2 core) | — |

Oculus does not see these programs (plain `ShaderInstance`s drawn after the pack's final pass, plus the raw depth
program of the prepass and self-shadow), and nothing goes through `BufferBuilder` or `VertexFormat.setupBufferState`.
Buffers are created and filled through `GL_COPY_WRITE_BUFFER`, which neither vanilla nor Oculus caches; the per-draw
block uses binding point 13.

### Multiplayer LOD tiers (`NprLod`)

Every YSM character of the main level pass is ranked by its projected height `h * P[1][1] / (2 d)` (fraction of the
screen height):

| Tier | Condition (defaults) | Drawn as |
|---|---|---|
| FULL | height ≥ `lodFullSize` 0.10 (13 blocks at FOV 70), at most `lodMaxFull` 4 | everything: depth prepass, self-shadow map, scene AO, scene-shadow PCF, face SDF/highlight/matcap, OverlayShadow layers, outline |
| REDUCED | height ≥ `lodReducedSize` 0.025, at most `lodMaxNpr` 12 including FULL | body only, with the depth prepass; no self-shadow, AO, scene-shadow PCF, face SDF/highlight/matcap, overlay layers or outline (uniform switches only) |
| MINIMAL | everything smaller or over the caps | body only, the `Minimal` branch of `npr_character.fsh` (same lit/shadow split, RD-ramp N·L, light colour, ambient, eye-adapted exposure, display transform and translucent cover as the full path; nothing else), no prepass |
| YSM | beyond `lodYsmDistance` (default 0 = never), `enabled=false`, not an opaque humanoid mesh, or over 128 NPR characters per frame (the per-draw block) | YSM's own toon path |

A character never drops from the Endfield look to YSM's path because of a cap: YSM's path under Bliss is nearly
black for these models. `lodYsmDistance` replaces the old `lodDistance` (48 blocks), which the config migration
drops. Hysteresis: a boundary moves to `t·(1−h)` while the character is on its larger side and to `t·(1+h)` otherwise
(`lodHysteresis` 0.15), and the caps rank characters by size with a bonus for the tier they already hold. Decisions
made at the end of a frame apply to the next; a new character gets a provisional tier within the capacity the
established ones leave. Offline check of the logic: 20 sizes gave 4 FULL, 8 REDUCED, 8 MINIMAL; FULL→REDUCED below
0.085 and back only above 0.115; REDUCED→MINIMAL below 0.02125 and back only above 0.02875; 20 characters with
interval 4 update exactly 5 per frame.

### How the 0.5.0 passes work with the tiers and the batched draws

- **Depth prepass** (`DepthPrepass`, at capture time in the gbuffer phase): FULL and REDUCED, not MINIMAL, not while
  touching water. It draws the character's arena cell (`RawGl.drawSkin`) with the same `ModelViewMat * (model *
  position)` as the body (`npr_depth.vsh` got the `Model` uniform), so the depth stays bit-identical on reused skins.
- **Depth test against the pack's opaque depth (depthtex1) with translucent cover, all tiers**: groups carry a depth
  mode — prepassed characters test depthtex1 without depth writes (their depth is already there), MINIMAL characters
  test depthtex1 with depth writes (no prepass), characters touching water test the main depth as in 0.5.0. Glass in
  front blends every tier at `translucentSeeThrough` (the MINIMAL branch applies the same cover test).
- **Self-shadow, scene AO, scene-shadow PCF, overlay layers, outline**: FULL only. Each FULL character is its own
  group: its self-shadow map is rendered from its arena cell, its body writes the receiver class when it has overlay
  layers, its overlay card runs are drawn from the cell (`glDrawElementsBaseVertex` with the run's index offset), then
  its outline. These features read the vertices on the CPU (card runs, the self-shadow fit), so FULL skins are written
  into a per-character `CpuMesh` and copied into the cell; REDUCED/MINIMAL skins are written straight into the mapped
  cell. A FULL character may only reuse a skin that went through its CPU copy.
- **Uniforms**: the per-character values (model delta, head basis, exposure) are the `NprDrawBlock` record; 0.5.0's
  new frame-wide uniforms (`SceneShadow2`, `SelfShadowMat`, `InvProjMat`, `ScreenSize`) are set once per program and
  frame, the per-group ones (`SelfShadowParams`/`SelfShadowMap`, `AoParams`, `SceneDepth`, `TranslucentCover`,
  `Minimal`, textures, profile) per group.

### Animation LOD (`mixin/GeoModelStateMixin`, `NprLod`)

YSM's client update path (YSM 3.0-dev sources, matched against the jar's bytecode): YSM's `LevelRendererMixin` calls
`AnimationParallelTicker.scheduleAll` at the head of `LevelRenderer#renderLevel`; it runs
`AnimatableEntity.computePose` for **every** loaded animatable on worker threads: `tickAnimation` (Molang; YSM itself
rate-limits it through `getFrameRateLimit`: 10/s when not rendered last frame, 30/s beyond 64 blocks, 60/s beyond 40)
and `GeoModelState.extract` → `NativeModelState.extract` (native bone pose, morph weights, spring step), which is not
rate-limited. The render passes call `update()` → `computePose` again and re-extract whenever the render context
differs; with a shader pack the shadow and main passes differ, so the pose is extracted up to three times per
character and frame.

The mixin targets `GeoModelState.extract` (HEAD, cancellable) and keeps the previous, still valid publication when
1. the same state was already extracted this frame (shadow/main repeats; every character, the local player included);
2. the character was not rendered in any pass last frame and this is not a render-thread call: it is then extracted
   only every `animLodOffscreenInterval` (8) frames, which bounds how old its pose can be when it comes back into view;
3. `NprLod` gave it an interval > 1 and this frame is not its update frame: `(frame + phase) mod interval == 0`, the
   phase hashed from the entity id so a crowd spreads evenly.

Intervals 1/2/4/8 come from `animLodSizes` (0.12 / 0.06 / 0.03 of the screen height) with the same hysteresis; at
most `animLodMaxFullRate` (8) characters update every frame. Only registered states are touched (the owner's main
render data, seen in an immutable level or shadow context); GUI, inventory, paper-doll and first-person states always
extract; the local player is only de-duplicated. The Molang tick is left to YSM's own rate limiter (keyframe events and
sounds unchanged). AnimatableEntity itself cannot be mixed into: YSM's mixin plugin (`MixinTweaker` → `ParCoolCompat`)
loads its hierarchy before mixin configs are prepared (checked with `-verbose:class`; Mixin: "target was loaded too
early"). Spring bones stay stable: native springs integrate in fixed 1/120 s steps with the elapsed time clamped to
0.1 s (`spring_physics.cc`) and `NativeModelState` restarts them after gaps over 0.5 s. NPR characters skip the
native skinning while their pose has not been re-extracted since their last skin; the stored skin is drawn with
`pose_now · pose_at_skin⁻¹` as its model matrix (record, prepass, self-shadow and overlays alike), and
`CharacterCapture.observeDraw` still records the draw transform for the springs.

### Persistent mapped vertex pages (`GpuArena`, `MeshBuffer`, `CpuMesh`)

Pages of 4 cells; a cell is one character with two copies of its mesh (1/8 headroom) and one shared QUADS index
buffer. With ARB_buffer_storage a page is immutable storage mapped once (`MAP_WRITE | MAP_PERSISTENT |
MAP_COHERENT`, or `MAP_FLUSH_EXPLICIT`); `MeshBuffer.ysm$reserve` hands YSM's native skinning a slice of the mapped
copy (REDUCED/MINIMAL) or of the character's `CpuMesh` (FULL, then copied in). A copy is written only after the fence
of the last frame that drew it has signalled; a fresh skin goes to the copy not drawn last. Without the extension, or
with `gpuPersistentBuffers=false`, the pages use `glBufferSubData`. Immutable buffers are deleted with
`glDeleteBuffers` directly (Minecraft's `_glDeleteBuffers` first calls `glBufferData(0)` on Linux, an error on
immutable storage).

### GPU skinning: evaluated, not implemented

YSM exposes the bone palette (`BonePoseView`, 128 bytes per bone) but not the skinned mesh: vertices live in native
memory as SoA meshlet blocks (`gfx/bake/baked_model.h` `MeshVertexBlock`: up to 4 joints per block, per-lane weights)
with morph targets driven by bone attributes (`renderer/morph.cc`), per-meshlet owner bones for light, colour and
visibility, and cube geometry for non-mesh bones. No JNI call returns the bind-pose vertices, joints, weights or
morph deltas. A clean GPU path would need an export of the baked mesh and the per-frame morph weights from YSM, then
a static vertex buffer per model and skinning in the vertex stage or a compute pass into the same pages here;
re-implementing YSM's importer in Java to get the data would duplicate it, so the animation LOD removes the skinning
on held-back frames instead.

### YSM mesh LOD (0.6.2)

The patched YSM (LOD build, `docs/LOD设计.md` in pelica-fix) bakes index-only LOD levels of an imported mesh and
hands native the level's baked model per extraction; the state keeps publishing the main `GeoModel`, so a level
switch changes only `getVertexCount()`. This mod treats it as the same model at another level:

- `CharacterCapture` keys the slot on model, native state and texture (`Slot.sameModel`); a vertex-count change
  re-skins into the same slot. The cell only grows (`Slot.reserve`), so crossing a level boundary back and forth never
  reallocates; the CPU copy stays and `CpuMesh.cardRuns` rescans on the new count. The overlay cards are locked in
  the simplifier at every level, so their runs still resolve.
- `GeoModelStateMixin` lets an extraction through when YSM has a level switch pending (`YsmMeshLod`, looked up by
  reflection: the mod still runs on a YSM without LOD), so the animation interval does not delay a switch.
- FULL characters are normally at level 0: YSM keeps characters at a tenth of the screen height and up at level 0
  (`-Dysm.lod.lod0Size`, matching `lodFullSize`), and the local player always.

### Measurements (`debugClones`)

`debugClones=N` draws N copies of the local player's character in 5-wide rows ahead of it, each with its own LOD
entry, tier, slot, skin and draw; copies share the player's YSM model state, so YSM's tick and pose extraction are not
multiplied by N. Harness: llvmpipe (Mesa 25.2, software GL), Bliss, 1280×720, FOV 50, noon, camera behind the player,
copies 3 blocks apart, `debugTimingFrames=6`, two windows per row. Before the merge (crowd work alone, 0.4.0 shading),
20 characters: 2063 ms per frame with every crowd feature off (all FULL, `glBufferSubData`, one draw per character and
pass) against 1261 ms with the defaults (4 FULL, 8 REDUCED, 8 MINIMAL; upload 20 ms → 0.02 ms, skinning 44 ms → 24 ms
with 6 skins reused per frame, pose extract calls 4 → 2 per frame). The merged build:

| characters (tiers) | frame (fps) | capture (skinned / reused) | upload | draw CPU (draws, groups) | draw GPU | pose extract (calls / frame) |
|---|---|---|---|---|---|---|
| 1 (FULL) | 548.9 (1.82) | 5.82 (1 / 0) | 1.72 | 113.3 (1, 1) | 116.9 | 0.24 (2, 2 skipped) |
| 5 (2 FULL, 2 REDUCED, 1 MINIMAL; `lodMaxFull=2 lodMaxNpr=4`) | 776.9 (1.29) | 11.04 (5 / 0) | 3.27 | 298.4 (5, 4) | 303.5 | 0.23 (2, 2 skipped) |
| 20 (4 FULL, 8 REDUCED, 8 MINIMAL; defaults) | 1543.7 (0.65) | 24.05 (14 / 6) | 6.44 | 948.4 (20, 11) | 953.6 | 0.18 (2, 2 skipped) |

The upload column is now the FULL characters' CPU copies (`CpuMesh` → mapped cell, ≈1.6 ms each on llvmpipe; the
REDUCED/MINIMAL skins are still zero-copy). 20 characters cost 283 ms more per frame than before the merge (1261 ms):
the four FULL characters now also render a self-shadow map, the scene AO and the overlay layers, and FULL and REDUCED
characters add the gbuffer depth prepass (one more pass over their vertices, which llvmpipe processes on the CPU), and
the 20 characters fill 5 pages, so the batched groups split per page (11 groups). Smoke test of the merge (two short
sessions: the first had the glass behind the characters and no overlay textures staged; `~/mc/shots/merged2-*.png`
are from the second): config migrated 4 → 6 on load (`sceneShadowStrength` reset,
backup written), no GL errors in the log, every tier drawn (5 draws in 4 groups with the 2/4 caps), Pelica behind a
glass wall shows through it at `translucentSeeThrough` (`merged2-glass.png`, crop `merged2-glass-crop.png`), the eye
close-up with the OverlayShadow layers staged (`merged2-front.png`, crop `merged2-eyes-crop.png`), and 20 characters
with the same colours near and far (`merged2-A20.png`).

### Render-thread costs (0.6.0 perf pass)

Baseline of d22989d on the harness (llvmpipe, Bliss, 1280×720, FOV 50, camera behind, `debugTimingFrames=60`, three
windows each):

| characters (tiers) | frame | capture (skinned / reused) | upload | draw CPU |
|---|---|---|---|---|
| 1 (FULL) | 529–560 ms | 5.9–6.2 ms (1 / 0) | 1.68–1.73 ms | 120–131 ms |
| 20 (4 FULL, 8 REDUCED, 8 MINIMAL) | 1526–1543 ms | 22.4–23.3 ms (14 / 6) | 6.45–6.69 ms | 963–967 ms |

On llvmpipe "draw CPU" is the software rasteriser running the passes (draw GPU ≈ draw CPU); the Java-side costs are
what carries over to a real GPU. JFR (`settings=profile`, 120 s, 1 FULL character): 37 % of the render thread's Java
samples were `CpuMesh.bounds`, the self-shadow fit, which transformed all ~230k vertices through the model-view and
projected them on the light axes every frame (≈3 ms per FULL character and frame on this CPU, from the sample count).
The render thread's allocation samples were vanilla, Oculus and YSM; none came from this mod. Native samples: the NPR
multi-draw 20 %, the raw prepass/self-shadow draws 7 %, Bliss's composites 42 % (llvmpipe rasterising).

Changes (not re-measured: compile-checked, plus an offline check of the fit — the box equals the per-vertex fit in its
own frame, and the corner fit contained the per-vertex fit for 200 random light/pose changes):

- **Self-shadow fit from a box.** A fresh FULL skin's extent along the light frame of its capture
  (`CpuMesh.box`, light × model-view) is measured while the skin is copied from its `CpuMesh` into the mapped cell, in
  72 KB chunks, so the skin is read once for both. The light is computed once per frame (`NprRenderer.frameLight`,
  by the first FULL capture or by the draw). On the skin's own frame the box *is* the per-vertex fit (same map as
  before); a reused skin fits the box's 8 corners through `light_now · model-view · model · boxFrame⁻¹`, conservative
  and only as much looser as the light and pose turned since the capture. A skin published while the self-shadow
  was off is measured once on first use. Per frame and FULL character: one pass of 9 multiply-adds per vertex on
  hot data instead of a 4×4 transform, three dot products and `Math.min/max` through `ByteBuffer` reads.
- **Depth prepass:** the pack depth's lookup (reflection), attachment and size query (two `glGetTexLevelParameteri`)
  once per frame instead of once per FULL/REDUCED character.
- **Fences:** retired oldest first, stopping at the first pending one (fences signal in submission order).
- **`NprLod.level`:** no array per call.
- Checked and left alone: `NprConfig` getters are a hash lookup of values parsed once per reload; captures, records,
  groups and the per-group uniforms allocate nothing per frame.

Remaining ideas, by expected gain on a real GPU:

1. YSM's native skinning (capture ≈6 ms per fresh Pelica skin here) is the largest CPU item; the local player is
   re-skinned every frame. Only the animation LOD reduces it; GPU skinning needs YSM to export the baked mesh.
2. `RawGl.save` reads ~17 GL values (`glGet*`) per depth prepass and per self-shadow map. Threaded drivers (NVIDIA
   "threaded optimization", Mesa glthread) make every `glGet` wait for the driver thread. Doing all prepasses of a
   frame in one bracket would need a hook at the end of the gbuffer entity phase.
3. A third copy per cell, so a fresh skin never waits for the fence of the copy drawn two frames ago (waits are
   counted in `upload`); costs another copy of the mesh per character.
4. FULL vertices are read by 4–5 passes per frame from persistently mapped (host-visible) memory; copying a fresh
   FULL skin once into device-local storage would keep those reads in VRAM.
5. Skipping the self-shadow map when light, pose and skin are unchanged needs one map per FULL character.
6. `Uniform.set` always marks dirty: ~30 `glUniform*` per group even when unchanged (tens of µs per frame).

FPS settings for players (qualitative where not measured): `lodMaxFull` (4) is the strongest lever — every FULL
character adds its self-shadow map, outline, overlay layers, scene AO/PCF and the CPU copy + fit; 1–2 keep the look
on the nearest characters. `outline=false` saves one full-mesh pass per FULL character; `selfShadowResolution=1024`
fills 2.25× fewer map texels (`selfShadow=false` drops the map pass entirely); `depthPrepass=false` saves one
full-mesh pass per FULL/REDUCED character but the pack's SSAO no longer sees her; `lodMaxNpr` (12) lower or
`lodFullSize`/`lodReducedSize` higher move characters to cheaper tiers; lower `animLodMaxFullRate` or larger
`animLodSizes` re-skin fewer characters per frame (CPU). Measured on llvmpipe before the merge (see above): 20
characters at 2063 ms per frame with every crowd feature off against 1261 ms with the tier defaults.

## Antialiasing (0.6.0, per character in 0.6.1): SSAA / MSAA of the NPR passes (`NprMsaa`)

The characters are drawn after the shader pack's composite and final passes, so the pack's TAA (Bliss `#define TAA`)
never sees them: they were the only aliased geometry on screen. Our passes are forward passes, so multisampling fits
(no blur, no ghosting; Godot's antialiasing guide recommends MSAA for cartoon styles).

| Key | Default | |
|---|---|---|
| `antiAliasing` | `ssaa` | antialiasing of the close-ups ("heroes"): `ssaa` = shaded per sample (`GL_SAMPLE_SHADING`, `glMinSampleShading(1.0)`; GL 4.0 or ARB_sample_shading, else msaa with a log line), a rotated-grid supersampling that also smooths the SDF line, the toon terminator, highlights and the matcap; `msaa` = shaded once per pixel (geometry edges only); `off` = the single-sample path for everyone, and the targets are freed |
| `aaSamples` | `4` | 2, 4 or 8, rounded down to a power of two and clamped to `GL_MAX_SAMPLES`, `GL_MAX_COLOR_TEXTURE_SAMPLES` and `GL_MAX_DEPTH_TEXTURE_SAMPLES` (4 on llvmpipe) |
| `aaHeroSize` | `0.25` | a FULL character at least this tall on screen (fraction of the screen height) is a close-up; others within half the largest close-up's height join it (similar close-ups, e.g. a group photo); the local player qualifies on this size alone |
| `aaOthers` | `msaa` | everyone else: `msaa` (per pixel, same target and depth as the heroes) or `off` (drawn single-sample straight into the main target, before the antialiased characters) |

All keys are hot-reloadable. The projected height is the one the LOD tiers use, computed at capture from
`RenderSystem.getProjectionMatrix()` (`CharacterCapture` `m11`), i.e. this frame's projection with every FOV
modifier applied: a spyglass or a gun scope that zooms by narrowing the FOV (TACZ's aim zoom does) makes a distant
character a close-up for both the tiers and the antialiasing.

**One shared target, the shading rate per group.** Every antialiased character draws into the same multisampled
colour + depth; `NprMsaa.shading` turns per-sample shading on for hero groups and off for the others (only state
changes reach GL). Sharing the depth keeps occlusion right when a passer-by crosses in front of the hero (its arm or
hair still covers the hero). The groups are sorted with the antialiasing class first: `aaOthers=off` characters
draw first, single-sample into the main target (and NprDepth's copy of depthtex1, which the antialiased depth then
starts from), so they do not widen the antialiased rectangle and stay correctly ordered against the heroes in both
directions. Trade-offs: `msaa` others cost `aaSamples`× coverage/depth/colour writes over their pixels and widen the
rectangle (copy-in and resolve area) but have smooth edges; `off` others cost nothing extra and have aliased edges.
With `debugTiming=true` every frame logs `Endfield NPR AA frame N: ssaa [0.32* 0.25] msaa [0.18] none [] rect
x,y wxh, 4 samples` (projected heights, `*` = the local player).

**Per frame**, when antialiased characters draw:

1. *Rectangle*: the union of the antialiased characters on screen. FULL characters project the 8 corners of their
   skin's box (`CpuMesh.box`, measured during the CPU copy whenever the self-shadow or antialiasing is on; every
   vertex is inside), REDUCED/MINIMAL characters their hit box grown by its largest dimension plus one block
   (`CharacterCapture.reach`), plus the outline shell's widest push (`npr_outline.vsh`) and 2 px. A corner behind
   the camera takes the whole screen.
2. *Copy in*, scissored to the rectangle: a fullscreen triangle copies the main colour into every sample and writes
   the depth the groups test as `gl_FragDepth` (a fragment's outputs go to all covered samples). Groups that test the
   pack's opaque depth get a depth initialised from depthtex1 (or from NprDepth's copy when single-sample characters
   drew into it); groups on the main depth (no pack, touching water) get a second depth initialised from the main
   depth. Each depth is only made when a group needs it.
3. *Draw*: every antialiased group (body, overlay layers, outline, crack fill, all tiers, MDI batches unchanged) into
   the multisampled target with the same blend/depth state as before. The receiver class (`ReceiverTarget`'s
   stand-in for the game's stencil) is a multisampled R8 attachment while the body draws and is detached while the
   overlay pass samples it (`sampler2DMS ReceiverMS`, bound by hand on unit 12: `ShaderInstance` binds 2D textures
   only). With ARB_sample_shading the overlay reads `gl_SampleID`, so every sample is gated by its own receiver
   class; without it, the fraction of the pixel's samples that pass scales the layer.
4. *Resolve*: one `glBlitFramebuffer` of the rectangle into the main target. Pixels without an antialiased character
   resolve to exactly what the main target held.

Sample shading, `GL_MULTISAMPLE` and the scissor are global state the pack and vanilla do not expect: they are put
back right after the passes, and `NprRenderer.renderAll`'s `finally` restores them (and binds the main target) if a
pass failed. All attachments are multisample textures (matching sample counts guaranteed); four framebuffers ({pack
depth, main depth} × {plain, with receiver}) are re-attached on every bind and re-checked when an attachment or the
size changes, and an incomplete one turns antialiasing off for that frame only. The depth prepass, the self-shadow map
(single-sample, so sample shading does not touch it), `SelfShadowMat` per group and the LOD/MDI batching are unchanged.

**Shading thresholds** (`npr_character.fsh`, both modes; they matter most for msaa): the main ramp lookup and the
view-alpha ramp are averaged over the pixel's footprint in `u` (4 taps across `|∇u|`, plain lookup below half a ramp
texel, footprint capped so class/SDF seams do not blur), the face SDF threshold, the rim band and the iris disc
(matcap and sphere normal; was a hard `r < 1`) use `aaSmoothstep`: a transition already wider than the pixel
footprint is exactly the original `smoothstep`, a narrower one spans about one pixel. Derivatives are taken in
uniform control flow (the face SDF is now read outside the per-texel class branch). Screen-space lookups floor
`gl_FragCoord` (per-sample positions under sample shading).

**Cost** (inside the rectangle only; not measured — llvmpipe numbers would not carry over):

- ssaa ≈ `aaSamples`× the characters' fragment shading (the body shader is the expensive part; with 4 samples a
  close-up FULL character costs about 4× its current fragment time), plus `aaSamples`× depth/colour bandwidth;
- msaa ≈ 1× shading plus `aaSamples`× coverage, depth and colour writes;
- both: the copy-in (one fullscreen-triangle pass, scissored: colour + depth writes to every sample, a second depth
  pass when water/no-pack groups exist) and the resolve blit over the rectangle;
- memory at 1920×1080 and 4 samples: colour 33 MB (RGBA8), one depth 33 MB (32F, a second only for water/no-pack
  groups), receiver 8 MB;
- threshold AA, every mode including `off`: one face-SDF fetch on every pixel of a FULL model with an SDF (it was read
  on face texels only) and 3 more ramp taps where a ramp lookup's footprint exceeds half a ramp texel.

**Limits**: alpha-tested texels (`base.a < 0.1` discard) keep a hard edge in msaa (ssaa evaluates the test per
sample); class-map boundaries are per texel; a REDUCED/MINIMAL model reaching more than its hit box plus its largest
dimension plus one block beyond it would be clipped at the rectangle, and that conservative reach makes the
rectangle wide (often the whole screen) when an msaa crowd character stands close to the camera; mode-0 groups (water,
no pack) no longer write the main depth (nothing after the NPR pass reads it).

### Face crack fill (0.6.1, `FaceCrackFill`)

An unwelded seam in a face shows what lies behind it (hair, the head's inside, the outline shell, the sky) as specks
or thin lines. `faceCrackFill=true` (default) runs, after each FULL character's body, overlay and outline passes, a
fullscreen pass over that character's rectangle that fills such pixels with the mean colour of the face around them.
The body pass writes its receiver class with face as its own code (k/8: 2 skin/cloth, 3 face, 4 hair, 6 iris/brow;
hair under `_HairBrowMask` writes 135/255 = 4.235/8, which every reader rounds to 4 = hair, see "Eyes and brows";
the overlay gate decodes k/8 and treats face like skin). A pixel — per sample in the multisampled target when
ARB_sample_shading exists — is filled when its class is neither face nor iris/brow, the first face pixel within 2 px
is reached (without crossing an iris/brow pixel) in at least 6 of the 8 directions (an isolated speck has 8, a 1-2 px
line crack 6, a silhouette edge at most 5), and each of those face pixels is nearer than it by more than
`faceCrackDepth` (0.003 blocks = 3 mm of view-space depth, from the depth the pass drew against): lashes, the lip
line and highlights lie in front of or level with the face and are never touched. The pass reads the colour it
writes into (only face texels, which it never writes) after a texture barrier (GL 4.5 / ARB_texture_barrier /
NV_texture_barrier; without one the fill is off) through a framebuffer that holds just the colour, so the depth it
samples is not attached. FULL characters now always write the receiver when the fill is on (it was written only for
characters with overlay layers).

`debugFaceCrack=N` cuts a synthetic N-pixel vertical crack through every FULL face (face texels discarded in the body
pass) as the test input. With the depth prepass on, the cut is not in the prepassed depth, so the "crack" shows
the face's own prepass depth and is (correctly) not a crack for the fill; the harness test runs with
`depthPrepass=false`, where it shows what is behind like a real seam.

### Review fixes (0.6.1)

- **Uniform block size.** `NprDrawBlock` declares `nprDraws[128]` (16 KB); the buffer was re-specified at `n × 128`
  bytes and bound whole, smaller than the block. It is now orphaned at the full block size every frame (the larger
  of 16 KB and `GL_UNIFORM_BLOCK_DATA_SIZE` of each program) and the frame's `n` records are written into it. It is
  the only uniform/storage block the mod declares.
- **Colour state per draw buffer.** `RawGl` kept one colour mask and one blend flag and set them for every draw
  buffer. The depth-only passes (prepass, NprDepth: framebuffers without colour, draw buffer NONE) no longer touch
  colour state at all; the passes that write colour into their own single-attachment framebuffer (self-shadow
  caster, the antialiasing copy-in, the crack fill) save, set and restore draw buffer 0's write mask and blend
  enable only (`glGetBooleani_v`, `glIsEnabledi`, `glColorMaski`, `glEnablei`/`glDisablei`). In the receiver
  passes the receiver attachment (draw buffer 1) is no longer blended: under translucent cover the body's opacity
  mixed the class codes (an iris behind glass read as hair); it is set back to the program's blend right after the
  draw, matching GlStateManager's view.
- **Direct state access** (`GlObjects`, `gpuDirectStateAccess=true`): with GL 4.5 / ARB_direct_state_access the mod's
  own framebuffers, textures, renderbuffers, buffers and vertex arrays are created and edited by name
  (`glCreate*`, `glTextureStorage*`, `glNamedFramebuffer*`, `glNamedBuffer*`, `glVertexArray*`), so nothing is bound
  to be edited; the page VAOs read the per-draw index stream through their own binding point 15. Without it (or with
  the key off) the bind-to-edit path runs, putting back every binding it touched. Textures are immutable under DSA:
  a size change makes a new one. Minecraft's and the pack's textures are only attached to the mod's framebuffers.

### Mouth ring: no polygon offset on her own surfaces (0.6.3)

Under a shader pack, a character with the depth prepass is in the depth her NPR passes start from (NprDepth's copy of
depthtex1, or NprMsaa's per-sample copy of it). Up to 0.6.2 her body, overlay and outline passes drew with
`glPolygonOffset(-1, -2)` to pass that copy, which is sampled once per pixel and rounded to the pack's depth format.
The offset is per triangle: `max(|dz/dx|, |dz/dy|)` of that triangle. Her hidden surfaces moved forward by their own
slope too. The mouth cavity (in `face_01`, 1.5–13 mm behind the lips and cheeks, texel colour 143,87,71) is a sack
whose walls are seen edge-on from the front. At the user's scale, about 1 mm per pixel, those grazing triangles moved
forward by more than their gap and beat the skin at isolated pixels. The result was a dotted, dark-brown ring around
the mouth and a tick under the nose, the cavity's top wall. It appeared with shaders only (no prepass without a
pack), it was worse with antialiasing (four samples per pixel), and it survived the seam welds and turning the
outline off. `faceCrackFill` could not catch it: the cavity fragment won because its offset depth was not deeper than
the face around it.

0.6.3 drops the offset from her body and outline passes; her own surfaces now occlude each other at true depth
through the depth writes. The copy moves back instead (`NprDepth.PUSHED`, used by NprDepth and NprMsaa): by the
largest one-sided depth difference in x plus that in y, which covers every sample of the pixel on a plane or a
crease, at most 4 cm + 2 texels of view depth so a scene object in front of her keeps its edge, plus two steps of
24-bit depth. The pushed copy only decides what passes against the scene, so the push size cannot let a hidden
surface of hers through. The overlay cards keep the offset: they write no depth and have nothing hidden behind them,
and parts of `eyeshadow_01` sit up to 0.3 mm under the face.

Offline (`tools/seam_depth_sim.py`: software rasterization of `face_01` with the prepass rounded to 24 bits, GL's
polygon-offset rule and the shader's float32 push; 110×90-pixel face crops at 15° elevation):

| scale | samples | pixels showing a hidden surface ≥ 0.5 mm behind, 0.6.2 | 0.6.3 |
|---|---|---|---|
| 1.2 mm/px | 1 | 97 | 0 |
| 1.2 mm/px | 4 | 218 | 0 |
| 2.0 mm/px | 1 | 78 | 0 |
| 2.0 mm/px | 4 | 201 | 0 |

The pixels with a sample that fails the copy (it shows the colour behind her) drop from 52–61 to 10–12. The
remaining ones are on the ears and the sides of the head: there the next surface of `face_01` lies more than 4 cm
behind, and the hair and body, which are not in the sim, would fill it.

`debugDepthOffset=true` brings back the 0.6.2 handling: offset on, exact copies. Use it to compare in game.

## Local block lights and the hair depth rim (`LocalLights`, `HairRim`, `shaders/include/npr_lights.glsl`)

### Hair depth rim (`rimDepth`, `rimDepthRange`)

The game's opaque hair does not gate its CP8 rim by the view angle. It reads the camera depth texture, which holds
the character, at a point offset along the screen normal (`HGRP_CharacterNPR_Hair_lod0_p0_s1_1000084.asm:1353-1377`;
the NPRType-3 local rim uses the same test at 1849-1863; ue5ref `EndfieldPunctualLighting.ush:600-657`):
`smoothstep(0.1, 0.2, depth(uv + normalize(N_view.xy) * (h/w, 1) * rimWidth * 0.006) - pixel depth)`, with depths in
metres (blocks). The skin and cloth programs have no such sample: they keep the `1 - |N·V|` gate. Only translucent
bangs (HairT) use the angle gate on hair.

Here the depth is `SceneDepth`, the pack's depth with her gbuffer depth prepass (`DepthPrepass`). It is the full depth
in a cover group and depthtex1 otherwise. It is not attached to any target the NPR passes draw into (single-sample:
`NprDepth`'s copy; MSAA/SSAA: `NprMsaa`'s own depth), so sampling it is safe on every path. `NprDepth`'s copy cannot
be used: the pass writes it while drawing. The gate is on for a group whose `SceneDepth` holds every member's own
depth: a cover group (all prepassed), or a prepassed FULL character. With no shader pack, no prepass (touching water,
`depthPrepass=false`), MINIMAL tier or `rimDepth=false`, the hair keeps the 0.6.3 angle gate. No sampler unit is added.

The rim is then a band of `rimWidth × 0.006 × screen height` pixels (3.24 px at 1080p, rimWidth 0.5) wherever the
surface behind lies 0.2 blocks or more back, whatever the distance. Internal edges stay dark: hair less than 0.1 block
in front of the face or body gets no rim. `tools/lights_rim_eval.py` uses a hair sphere of radius 0.12 at 1920×1080,
FOV 70, and counts pixels with mask > 0.5 on one silhouette:

| distance | old angle gate | new, background 4 blocks behind | new, body 0.08 behind the centre | new, 0.03 behind |
|---|---|---|---|---|
| 2 | 4 px | 3 px (1, 1, 1) | 0 (0.03-0.19) | 0 |
| 4 | 2 px | 3 px | 0 (0.04-0.33) | 0 |
| 8 | 1 px | 3 px | 0 (≤ 0.50) | 0 |
| 16 | 1 px | 3 px | 2 px | 0 |

The old gate lit every grazing hair surface, inside strands included. Its width shrank to 1 px with distance.

### Local lights (`localLights`, `localLightShare`, `localLightIntensity`, `localLightSpecular`, `localLightCount`, `localLightRadius`, `localLightRefresh`)

Endfield adds punctual NPR lights per character (ue5ref `EndfieldPunctualLighting.ush:138-349`: 8 float4 per light,
NPRType 0 Default, 1 Toon, 2 SpecOnly, 3 RimOnly, 4 Fog). Minecraft has no light entities, so for each FULL character,
the light-emitting blocks near her eyes stand in as Type0 Default lights. These are blocks with
`BlockState.getLightEmission() > 0`: torches, lanterns, glowstone, lava, fire and the like.

- **Scan (CPU, `LocalLights`).** The box is `localLightRadius` (8) blocks around her eye block. Only chunk sections
  whose palette may hold an emitter are read (`LevelChunkSection.maybeHas`). Candidates must still light her eye
  block: MC level = emission − Manhattan distance > 0. The 8 strongest are kept. Then the first `localLightCount` (4)
  in sight are taken, by a voxel walk from her eyes with no full opaque block in between. A light behind a wall reaches
  her around it; its share stays in the ambient. The result is cached per character. It is rescanned every
  `localLightRefresh` (4) ticks, or at once when her eyes move more than a block. At most 2 scans run per frame. Each
  frame only moves the cached blocks into view space.
- **Uniforms.** `LocalParams` holds the count, share, intensity and specular. `LocalPos` is a mat4 whose columns are
  the light block centres in view space, with the emission level in w. `LocalTint` is a mat4 whose columns hold the
  tint in rgb (luminance 1, relative to the lightmap's own warm block colour: soul fire blue, redstone red, the rest
  neutral) and the light's level at her eyes in w. There is no new sampler and no NprDrawBlock change.
- **Shading (`npr_lights.glsl`).** This is the game's Type0:
  `color · weight · DefaultDiffuse · lerp(back · BackContrast, 1, sat(N·L + 0.5))`. back is 0.25 for cloth, hair and
  eye, and 0.5 for skin and face; on the face the back-contrast normal is the head's horizontal hemisphere normal,
  blended to N by the SDF mask G. The colour is limited by `1 / max(1, max3(color · weight) · lerp(0.75, 0.5, 1 − X))`.
  BackContrast and BrightLimit are at their defaults of 1. DefaultDiffuse is our `albedoF`: the prepared diffuse before
  the main-light factor. Cloth, skin and face also get the additional GGX times N·L, scaled by `localLightSpecular`;
  hair and eye get diffuse only. The term is added after rim, back light and emission, before exposure, as in the
  game's stage order (`Stages/Cloth.ush` stage 16).
- **Ambient vs directional: no double counting.** MC's block light is already in her lightmap ambient. This share of
  it is taken out:
  `LM(block, sky) − LM(0, sky)` × `localLightShare` × the part the found lights explain,
  `min(1, brightness(best level at her eye block) / brightness(her lightmap block level))`, computed on the CPU.
  It is removed through the main-light function (`nprMainLight`), which honours its non-linear MC adaptation, and given
  to the lights. Each light gets its share of the brightness at her eyes and MC's falloff relative to her eyes:
  brightness(emission − world Manhattan distance), with brightness = f/(4−3f), capped at 2×. Each is divided by the
  sphere mean of the Type0 profile, 0.5 (1 + back). Averaged over all normals, the lights return exactly the ambient
  they took. In daylight under open sky the block-light share of the lightmap is about 0, so the lights do nothing,
  as in vanilla. Without lights (`LocalParams.x = 0`: off, not FULL, nothing near) the shader keeps the 0.6.3 colour
  exactly.

`tools/lights_rim_eval.py` puts a torch (emission 14) at 1, 3 and 6 blocks. It uses the vanilla lightmap (gamma 0.5),
Pelica's real median albedo (`t_actor_pelica_{cloth_01,body_01,hair_01}_d`) and her shadow grades. The values below
are the luminance of `lightCol × albedoF` before exposure, at `localLightShare` 0.75. The mean over 4000 normals ÷ the
0.6.3 value is 1.000 in every row:

| scene | d | class | 0.6.3 (ambient only) | lit side | dark side | lit/dark |
|---|---|---|---|---|---|---|
| night, open sky | 1 | cloth | 0.352 | 0.397 | 0.306 | 1.30 |
| night, open sky | 1 | skin | 0.446 | 0.478 | 0.413 | 1.16 |
| night, open sky | 6 | cloth | 0.291 | 0.310 | 0.271 | 1.14 |
| cave (sky 0) | 1 | cloth | 0.538 | 0.779 | 0.298 | 2.62 |
| cave (sky 0) | 1 | skin | 0.683 | 0.853 | 0.513 | 1.66 |
| cave (sky 0) | 3 | cloth | 0.404 | 0.578 | 0.230 | 2.52 |
| cave (sky 0) | 6 | cloth | 0.258 | 0.360 | 0.155 | 2.33 |

Hair matches cloth (same back contrast). At share 1.0 the cave cloth contrast is 3.5, and 1.9 on skin. Her feet, 1.6
blocks below the eyes, get 0.71-0.73 of the light at her eyes.

**CPU cost.** A scan reads at most 8 sections (17³ = 4913 blocks at radius 8), and only those whose palette holds an
emitter. A synthetic JVM model of the loop (4-bit palettes, cached emission) measured 3.8 µs per scan with emitters in
a third of the sections (650 blocks read) and 17.5 µs with emitters in all of them. Add MC's `PalettedContainer`
indirection (estimated ×2) and ≤ 8 sight rays of ≤ 48 block lookups. The estimate is ≲ 50 µs per scan, at 5 scans/s
per FULL character: ≤ 0.25 ms per second each, at most 2 scans (≲ 0.1 ms) in one frame. With `debugTiming` the mod
logs the measured µs and blocks per scan every 200 scans.

## Eyes and brows from the material data (`EyeBrow`, `npr_eyebrow.glsl`)

Driven by the package's per-material data (`materials.<glTF material>.{maps, params}`,
`~/ysm/handoff/npr-materials-schema.md`): the Unity values of each material and its maps, read through PropMaps'
`npr_maps.glsl` / `ModelPackage.param`/`color`/`materials()`. A model without them looks exactly as before.

**Iris** (HGRP/CharacterNPR_Eye, decompiled `~/ysm/npr/decompiled/Eye_950424.glsl` = E; ue5ref `Stages/Eye.ush`).
The iris material is the Eye-shader material (params with `_EyeHighLightColor`) whose atlas rectangle is the profile's
iris rectangle (`_EyeHighLight` = 1 preferred). `irisTerms=true`:
- Parallax (E 137-184, Eye.ush 65-80): `uv -= normalize(V_tangent).xy * _ParallaxScale * (1, 0.25) * w`,
  `w = smoothstep(sat((0.25 - c.c) * 5))`, `c = fract(uv) - 0.5`: the iris centre shifts against the view, the rim
  stays. No texture: no eye material in the game binds `_ParallaxTex`. The tangent frame comes from the screen
  derivatives of the view position and the atlas uv (YSM has no tangents; Pelica's two irises share one uv square
  mirrored in u, so a fixed head frame would move one of them the wrong way). The shifted uv is clamped half an
  atlas texel inside the iris rectangle. Pelica (`_ParallaxScale` 0.03): 7.7 iris texels at 30° off the eye axis,
  13.3 at 60°; on screen 0.6 / 1.0 px at 0.5 blocks, 0.3 / 0.5 px at 1 block (`tools/iris_eval.py`).
- Colour (E 720-733, Eye.ush 185-190), `H = sat(_EyeHighLight)`: the lit albedo is multiplied by
  `((1 - oH) + _EyeHighLightColor oH) * lerp(1, (1 - a) + _EyeScatteringColor a, H)`, `o` = outside the iris circle,
  `a` = the iris `_BaseMap` alpha (map `baseAlpha`; YSM's atlas is opaque) × `_BaseColor.a`; the shadow base is not.
  Without `baseAlpha`, `a` = 0. Pelica: ×1.10 linear luminance over the iris disc (×1.34 where alpha > 0.25), ×1.76
  in the corners outside the circle. Not ported: the eye emission tuple (weighted by the global `CP123`, unknown
  here) and `_AlphaPremultiply` (0 on every iris).
- Sphere frame: with the iris params the matcap sphere and the diffuse tilt (Iris shading, 0.3.1) also use that uv
  frame, as E's `shadeT`/`shadeB` (E 189-213): `HeadRight`/`HeadUp` mirrored the matcap on one eye and flipped both
  vertically. Checked on Pelica's iris triangles: t = +x on the left eye, -x on the right, b = -y on both.

**Brows through the bangs** (`browThroughHair=true`, `browRevealDepth` 0.04 blocks; FULL tier). The game clips the
hair where the brow/iris PreZ wrote its stencil (`_PreZStencilRefOption` 52) and `_HairBrowMask.r` <
`_HairBrowMaskThreshold`, on hair with `_DrawUnderBrow` = 1 (`EndfieldCharacterPass.usf:472-477`,
`Stages/Hair.ush:858`). Here, with the receiver class standing in for the stencil:
1. The body pass writes hair texels of a material with a `browMask` map and mask.r < threshold as receiver 135/255
   ("masked hair"; rounds to hair, 4, for the overlay gate and the face crack fill).
2. Right after the body (before the overlays and the outline), the quads of every Eye-shader material (brows and
   irises; found by their atlas rectangles on the CPU copy, like the overlay cards) are drawn again with the body
   program: vertices pulled `browRevealDepth` toward the camera, depth LEQUAL without depth writes, colour only. A
   fragment survives only on an eye-class texel whose pixel is masked hair in the receiver (multisampled: every
   sample masked hair or eye, one at least masked hair). The single-sample receiver is bound on Sampler8's unit
   for that pass (`BrowReveal.z` skips the face highlight map); the multisampled one is `sampler2DMS BrowReceiverMS`
   on unit 14, bound by hand around the pass and restored (json samplers use units 0-13; logged and off for
   antialiased characters if `GL_MAX_TEXTURE_IMAGE_UNITS` ≤ 14).

So a hat, a hand or anything else in front of the bangs keeps the brows hidden (its receiver is not masked hair), and
from behind the head the back hair is in front (mask white). Offline on Pelica's mesh (`tools/eyebrow_eval.py`, her
`_HairBrowMask` covers the whole front of the bangs): from the front the game reveals 624 brow/iris pixels and so do
we (0 false); 20° up/down, 30° side: equal; 60° side: 383 of 450 (bangs 70-80 mm in front of the far brow);
back and back 30° up: 0; a hand 4 cm in front: 0. A hat brim lying between the bangs and the brows is the one case
that shows through (37 px): it is behind the hair, so the receiver cannot see it.

**Cloth parallax** (HGRP/CharacterNPR `_UseParallax` = 1, map `parallax` = `_ParallaxTex`, tiled; decompiled
`~/ysm/npr/work/Gens2/Gens2_14753404.glsl`): parallax-occlusion march of `_ParallaxTex.x` in the tiled uv
(586-636: `min(_ParallaxMarchNum, 20)` layers, step `-V_t.xy / max(V_t.z, .001) * _ParallaxScale / n`), linear
interpolation between the last two layers and a final sample (1597-1607), then an unlit
`color += h * _ParallaxColor.rgb * a * premul` (2061-2069; `a` = `baseAlpha` × `_BaseColor.a`, so no `baseAlpha` =
no parallax colour; `premul = 1 - p + a p`, `p = _AlphaPremultiply`). The tangent view uses the vertex normal
(`_ParallaxUseNormal` would need the normal map).

## 雨水与湿身（0.8.0，`RainWet`、`npr_rain.glsl`）

**做什么。** 角色淋雨、涉水、落水后的湿身外观。着色完全来自游戏角色 shader 的反汇编；只有“现在有多湿”这一运行时状态是
我们为 Minecraft 设计的。

- 着色（`shaders/include/npr_rain_asm.glsl` 由 `tools/rain/rain_gen.py` 从 `/tmp/dxdis/out` 的反汇编逐行生成，每行注明
  asm 行号；`npr_rain.glsl` 只负责把我们的表面值填进游戏的寄存器、再读回结果，每个寄存器同样注明 asm 定义行）：
  - 脸（Skin 变体 A，`Skin_10029588` 389-452）：`_CharacterRainFaceDripTex`（rain_03）按 `frac(0.8 t)` 竖向滚动的水痕和
    水珠头，RG 为法线；`_CharacterRainFaceDropletTex`（rain_04）B 通道限定脸部区域；水痕压暗受光权重、粗糙度拉向 0.5、
    高光强度推向 3；770-789：湿时按视空间法线采样 rain_04 的 A 做 matcap 高光（× 湿度²）。
  - 身体皮肤（`Skin_1018472` 378-952）与头发（`Hair_1000084` 411-1010）：程序化水珠（物体空间网格，沿重力方向流动，
    雨停后定格并随湿度淡出），水珠法线、压暗与反光；1168-1184：皮肤 GGX 高光 r25（滴前法线、材质粗糙度，湿块不改
    粗糙度和 `_Specular`，只按湿度把它的权重压到 0.4–0.8，937-940）；1185-1214 / 1273-1304：水珠闪光、高光和亮度
    权重；头发主高光 × r6.w（1258）。皮肤与头发程序都不采样 cubemap：游戏里湿身的大片反光来自之后的反射 pass（见下）。
  - 布料（`CharacterNPR_10131804` 473-1032）：`_CharacterRainEffectTex`（rain_01）A 通道的湿斑随湿度扩散、B 通道水珠，
    `_CharacterRainStreakTex`（rain_02）随时间下流的水痕，尺度 20 与 34.3456 的程序化水珠；湿处压暗、粗糙度降低；
    1782-1783：环境反射用湿后的粗糙度。
  - 字节：`cb2[draw+13].x` 四个字节 = 0 即时雨量、1 水线湿度、2 累积湿度、3 雪（恒 0）；`.y` = 水线高度（这里是相对
    相机的世界 y，-1000 = 无）。`wade = byte1 × smoothstep(sat((水线 - y + 0.2) / 0.35))`，`wet = max(wade, byte2)`。
  - 材质的 `_DisableRainEffectOnMaterial`（`materials.<m>.params`，缺省 0）按游戏方式从门限里减去（`NprMatRain`）。
  - 每个程序的第二块（byte 3 门控）其实是**雪**：序列化绑定表（`pelica-full/03-渲染管线/角色NPR原始字节码/*_s1.dxbc`）
    中头发只用 `_CharacterSnowEffectTex`，布料用 {RainEffect, RainStreak, SnowEffect}；其数学把颜色推向 0.88 的白、
    粗糙度 0.9、去金属，偏向朝上的面和脚部。雪的四段也已移植并通过数值核对，但只在定义 `NPR_RAIN_SNOW` 时编译，
    且需要把 `T_actor_common_snow_01_M` 放进 RainTex 第 5 层（本包没有这张图）。
- 运行时状态（`RainWet` + `RainWetState`，每个被绘制的角色一份，按游戏 tick 步进，全部常数是我们的）：
  - byte0 即时雨量 = 头部正在淋雨（`Level.isRainingAt`：下雨、能看到天空、上方无遮挡、该处降水是雨不是雪）× 雨量，
    以 `rainSmoothTime` 为时间常数平滑；低于 1/255 时游戏 shader 自己把水珠定格。
  - byte2 累积湿度：淋雨时以 `wetDrenchTime / 雨量` 为时间常数升向 `wetMax × 雨量`；躲雨即停止增长；离开雨
    `wetDryDelay` 秒后以 `wetDrySmoothTime` 为时间常数变干。眼睛没入水中立即 = 1。
  - byte1 + 水线：在水中 = 1，水线 = 水面相对脚底的高度（`getFluidHeight`）；出水后水线留在身上，按同样的延迟和时间
    常数变干，干透后取消水线。
  - 物体空间 = 身体坐标系（脚底为原点，身体朝向，Unity 轴向 右/上/前）；世界空间 = Minecraft 世界把 z 镜像（Unity
    左手系）。0.8.3 起物体空间坐标逐顶点固定在表面上（`RainAnchors`）：某角色在某顶点数下、正在淋雨时的第一张蒙皮
    作为参考，用当时的身体坐标系换算一次，存进竞技场每页的第二个缓冲（与蒙皮同序，每顶点每副本 16 字节，按需创建），
    此后每帧（含复用蒙皮、multi-draw）作为顶点属性读取；顶点数变化（YSM 网格 LOD）或换模型时重新取参考。切线由物体
    坐标的屏幕导数重建（与 nprFrame 相同）；法线、切线、重力等世界空间输入仍是当前帧的。槽位尚无参考时退回旧的刚体
    映射（参考帧上两者完全相同）。
- 贴图：四张共享雨图（1024²，线性 RGBA8，重复寻址，mipmap）作为 `GL_TEXTURE_2D_ARRAY` 放在 15 号纹理单元
  （`RainTex`；json 采样器占 0-13，眉毛的 MS 接收图占 14）。

**配置键**（`endfield_npr.properties`，全部即时生效）：`rainEffect`（总开关）、`rainStrength` / `wetStrength` /
`wadeStrength`（三个字节的倍数）、`rainSmoothTime`（0.5 s）、`wetDrenchTime`（30 s）、`wetMax`（1.0）、`wetDryDelay`
（20 s）、`wetDrySmoothTime`（60 s）、`rainTiling`（5：0.8.3 起按游戏内测试缩小水珠；游戏 HGRainPresettingAsset 的
characterRainTextureTiling 为 2.25；旧配置仍为 2.25 时自动改成 5，其他值保留）、
`rainDebug`（同游戏的全局覆盖 cb1[120]：`b0,b1,b2,b3,水线`，首项 < 0 为关；例如 `255,0,255,0,-1` 强制大雨全湿）、
`rainGlint`（只缩放水珠闪光）、`rainMatcap`、`rainDropSpeed`（3：水珠出现、消退约 2 秒一轮；1 = 游戏的节奏，几乎不动；
旧配置仍为 1.0 时自动改成 3，其他值保留）、`rainFaceDripSpeed`、`rainDropDensity` / `rainDropJitter` /
`rainDropSpeedJitter`（0.8.6，见下）、`rainSheen`（游戏 `_CharacterParams13.w` 在雨中皮肤
GGX r25 与头发主高光上的倍数，值未知取 1）、`rainWetReflection`（我们的水膜反射，见下）。

**水珠的稀疏与不规则（0.8.6，我们的，`npr_rain_drops.glsl`）**：皮肤、头发、布料的程序化水珠每块有 6 次网格查找
（3 个投影面 × 2 个尺度），每颗水珠占一个网格格子。`rain_gen.py` 在生成的代码里插入标注 `// hook` 的行：取格子编号
的哈希作为这颗水珠固定的随机数（不闪烁，整颗处理）。`rainDropDensity`（0.5）：只保留随机数 < 该值的水珠（在游戏
本身按湿度的门限之上再筛一次）；`rainDropJitter`（1.0）：位置从游戏的 ±0.25 格移到水珠在格内仍完整的任意位置（按
半径算余量，游戏里布料/头发的大水珠原本可能被格子边界切掉一块），大小 × 0.7–1.3；`rainDropSpeedJitter`（0.4）：
每颗水珠的出现/消退速度 × 0.6–1.4（只在下雨时；雨停后的定格水珠仍按湿度）。布料的贴图水珠和水痕（rain_01 B 与
法线、rain_02 B 与法线）在 `rainTex` 里乘一张低频噪声遮罩（两层 value noise，斜着避开贴图网格，按密度取阈值、边缘
柔和），每个 0.2 格的贴图重复都显示不同的一部分，不再能看出平铺；A 通道（湿斑、水痕里的流水）不变。三项都即时生效；
密度 1、抖动 0、速度抖动 0 时与游戏的数学完全一致（`rain_check.py` 以这组值运行这些 hook）。脸部水痕是整张脸 uv
上的一份贴图，没有网格平铺，不做处理。离线对比图：`python3 tools/rain/drops_render.py`（输出到
`~/ysm/handoff/raindrops/`：法线倾角的水珠分布图和着色近景，游戏值与 0.8.6 默认值对比）。未进游戏验证。

**水珠滑落（0.8.7，我们的）**：游戏的程序化水珠本身不移动（网格坐标里没有时间项，时间只驱动每颗水珠的出现/消退，
消退时把凸起压平，所以高光会在水珠还半可见时突然消失）。现在下雨时，两个竖直投影面的格子网格沿锚定的身体坐标向下
移动，速度 `rainDropSlide`（新，即时生效，默认 0.05 格/秒）× 每列的随机系数（`rainDropSpeedJitter`），每颗水珠在自己的
格子里移动，不会越过或被格子边界切掉；每颗水珠在游戏的可见窗口（相位 0.2–0.85）前 12% 从一个点长大、后一半缩小到
没有，凸起、压暗和高光随面积一起淡出，不再突然消失。朝上的面上的水珠不动。`rainDropSlide` 为 0 且 0.8.6 各项为中性
值时与游戏的数学一致。离线：`drops_render.py` 生成滑落动图和轨迹图。未进游戏验证。

**推断 / 近似的部分**
- 运行时状态机及其全部常数（游戏的 rainIntensityToMaxWetnessCurve 等曲线值不在任何已有资源里）；淋雨中湿度不会下降；
  涉水不增加累积湿度；水线取当前水面。
- 我们的反照率和阴影色是显示空间：进入游戏代码前 sRGB→线性，出来再编码回去；水珠闪光与亮度权重合成在我们（显示空间）
  的光照上。
- 脸部 matcap 的光照项（游戏的受光项 × 光强 × cb1[110].w）用我们的 `specLight` 代替；脸的 t17 遮罩和 `_BaseMap.a`
  取 1；头发的 `|cb0[9].y|` 取投影矩阵的 y 缩放；头发水珠的 uv.x 用材质 uv；顶点切线由屏幕导数重建（YSM 没有切线）。
- 纹理 mip 在统一控制流里按物体坐标 / 脸 uv 的屏幕导数统一计算（游戏是每次采样的隐式导数），不加全局 mip 偏移
  cb1[26].x；时间用游戏时间（1 小时循环）。
- 不在 face_01 区域的脸类像素按身体皮肤处理；MINIMAL 档、描边壳和眼睛不下雨。
- 物体空间的参考姿势是该角色淋雨时的第一张蒙皮（不是绑定姿势）：高度渐变按那一刻的姿势；之后同一顶点数下不变。
- `rainWetReflection`（我们的，不是游戏的）：游戏角色程序只在 GBuffer 写湿标记，大片水膜反光由之后的反射 pass
  （SSR / 反射探针）完成；这里用布料环境反射的替代（到达表面的光 × 按反射向量的天地渐变）× 水的 EnvBRDF（F0 0.02、
  粗糙度 0.15）× 该像素湿度，加在游戏各项之后，只用于皮肤和头发。

**未还原**：逐像素的雨遮挡图（`_RainOcclusionSampleNoise` / ShouldRequestRainOcclusionMap；这里只在头部判断一次是否
淋雨）；眼睛 shader 的雨；丝袜变体（`cb6[10..17]` 的三段布料程序）；皮肤水珠在局部光源里的项（`r20.w`，Skin 1428）；
GBuffer 的湿标记 `o1.w`；雪（需要雪贴图，见上）；镜头雨滴、远景雨、水面雨纹等场景效果。

**验证（离线，未进游戏）**：`python3 tools/rain/rain_check.py`（每段 asm 与生成的 GLSL 在相同随机输入上比对：
dxasm.py 解释原 asm，Mesa llvmpipe 运行 GLSL，全部寄存器在容差内一致，并报告 asm 行覆盖率）；
`tools/rain/test_state.sh`（状态机：干 → 下雨 → 躲雨 → 变干、涉水、落水）；`python3 tools/rain/link_check.py`
（真实驱动上编译链接两套程序，含 `NPR_RAIN_SNOW`）；`python3 tools/rain/surface_smoke.py`（用真实雨图跑寄存器映射：
干时不变、结果有限、法线单位长）；`tools/rain/test_anchor.sh`（锚定坐标与相机、身体朝向、世界位置无关；参考帧上等于
旧刚体映射；另一姿势下旧映射在手臂/胸口漂移；物体切线符号）；`tools/glsl_check.sh`；`tools/rain/drops_render.py`
（0.8.6 水珠分布与着色的离线对比图）。

## Build (no Gradle)

```bash
# one time: copy the Forge client libraries and make Mojang-named compile jars (~1 min, JVM -Xmx512m)
cp ~/.cache/mc/ysm-3.0-dev-forge+mc1.20.1.jar ~/ysm/npr/mcdev/libs/   # after remap-mc.sh created the dir
~/ysm/npr/mod/tools/remap-mc.sh ~/mc/client/libraries
# build -> build/endfield_npr-<version>.jar (version from mods.toml; javac, jar, ForgeAutoRenamingTool Mojang->SRG)
~/ysm/npr/mod/build.sh
```
`tools/genmap.py` derives `tools/moj2srg.tsrg` from the client's ProGuard mappings + MCP tsrg2;
`tools/srg.py <mojang.Class> [member…]` looks up single SRG names (the mixin targets are written in SRG).

## Model packages (0.6.0): the NPR data lives in the model

Contract: `~/ysm/npr/BUNDLE-CONTRACT.md`. A VRM carries the glTF extension `ENDFIELD_npr` (format 1): `shading`
(material values), `ramps` (`clothRD skinRD faceRD hairRD eyeRD clothRS hairRS`), `skinLut`, `face` (`material`,
`sdf`, `sdfMask`, `highlight`, `highlightVector`), `iris` (`material`, `matcap`, `matcapColor`,
`matcapNormalScale`) and `overlays` (`card {material, uv}`, `texture`, `baseColor`, `useGrayAsAlpha`, `stencilRef`,
`disableDrawUnderHair`, `angleRange`). Textures are extra embedded PNG glTF images referenced as `{"image": i}`;
placements name glTF materials. Every key is optional (missing = generic value / feature off).

- **Conversion** (patched YSM jar, `EndfieldNprPackage`, humanoid REVISION 15): the importer resolves every
  `material` to `"rect": [x, y, w, h]`, that material's region in the colour atlas it just built (narrowed by `uv`),
  replaces images by files and adds `"atlas": [w, h]`. The ZIP (`profile.json` + `images/*.png`) is stored in the
  player render target's data blob as `ModelData.geo_models["endfield_npr"]` (YSM itself reads only `main`/`arm`).
  So the rectangles always match the atlas YSM actually built, and the package travels with the model: YSM's
  download, ysmguard's AES-GCM channel and encrypted client cache, the WORN/ownership policy, all unchanged.
  A malformed extension fails the import with `ENDFIELD_npr: …` (`/ysm model errors`).
- **Client** (`ModelPackages`): keyed by `AnimatableEntity.getModelHash()`; read once per model from YSM's own
  client store (`ClientModelService` catalog → content → player target data blob; server models through
  `RemoteModelContent.cachedOnlyView()`, i.e. what YSM already downloaded to draw the model), scanned for the
  `endfield_npr` entry without parsing the animations (`ModelPackageData`), decoded on a worker thread, uploaded on
  the render thread, freed on logout. Until it is read (a fraction of a second), and for models without a package,
  the generic look is used: procedural ramps, generic material values, no LUT/SDF/highlight/matcap/overlays. Logs:
  `Endfield NPR package of model <hash>: SDF …` or `… carries no NPR package …`.
- **Making a package**: `~/ysm/pelica-fix/fix_pelica.py` embeds Pelica's (`NPR_PROFILE`, `NPR_IMAGES`); any
  VRM tool can write the same extension. Offline checks: `~/ysm/pelica-fix/tools/ysmgen` `GenNpr <model.vrm> <out>`
  (runs YSM's importer, prints the resolved rectangles, writes `<out>.zip` and `<out>.modeldata.bin`) and
  `tools/package-check.sh <out>.modeldata.bin` (this mod's parser on that data blob).

Migration from 0.5.x: delete `config/endfield_npr/*.png` and `config/endfield_npr/models/` on every client (no
longer read; the mod logs a warning listing them), install the patched YSM jar (REVISION 15) on the server and
clients, and replace the server's model with a VRM that carries `ENDFIELD_npr`. Material values tuned in the old
`endfield_npr.properties` must be moved into the model's `shading` block.

### Per-material maps (`materials`, `NprMaps`, `npr_maps.glsl`)

The package may list `materials.<glTF material>` = `{material (marker), source, maps.<kind> {image, scale?, offset?},
params}` (shared schema `~/ysm/handoff/npr-materials-schema.md`, written by the NprPatch data tool from the user's
export; `params` = the Unity material's m_Floats/m_Colors verbatim). Kinds: `prop` (_MetallicGlossMap), `emission`
(_EmissionMap), `normal`, `splitNormal`, `outlineMask`, `browMask`, `baseAlpha` (atlas-aligned) and `line`
(_LineMap), `parallax` (_ParallaxTex) (tiled, Unity `_ST` in scale/offset). A model without `materials` renders as
before, except the hair gloss fallback below.

- **Compositing** (worker thread, `NprMaps.build`): YSM puts each material's base texture whole into one atlas
  rectangle (uv clamped, remapped linearly), so each atlas-aligned map is box-filtered into that rectangle of a
  `GL_TEXTURE_2D_ARRAY` aligned with the colour atlas (Sampler11), plus one texel of edge padding. Physical layers:
  prop, emission, normal, splitNormal, and one "masks" layer (outlineMask rg, browMask b, baseAlpha a); only those a
  model has are allocated. Texels no material covers hold neutral values (prop (0,0,1,0) and unused, emission 0,
  normal (.5,.5,0,1), splitNormal .5, outlineMask 1, browMask 0, baseAlpha 0). Resolution: the atlas scaled to
  `mapResolution` (largest side) and further until layers (with mipmaps) + tiled sheet fit `mapBudgetMB` (Pelica:
  5 layers of 1344² + 512² sheet, ~47 MB). Mipmaps: linear-mipmap-linear, `GL_TEXTURE_MAX_LEVEL` 4 (deeper levels
  would average neighbouring atlas rectangles; there is 1 texel of padding at layer resolution). The tiled maps go
  once each into a 2D sheet (Sampler12); the shader tiles them per material (`fract`, half-texel guard, Unity v flip).
- **Emission** is baked per texel: map (sRGB→linear) × `_EmissionColor` (linearised) × `_EmissionBrightness` ×
  `_UseEmission`, stored as rgb = sRGB(colour/max), a = log2(1+max)/8 (`nprEmission` decodes it).
- **Material table** (uniform arrays, ≤ 24 entries): atlas rect, map flags, the tiled map's `_ST` and sheet rect,
  4 parameter rows: rows 0-1 the tiled kind's parameters (`NprMaps.TILED_PARAMS`: line `_LineAmount _LineValue
  _LineRange _LineIntensity _LineSaturation _UseLineMap`; parallax `_ParallaxScale _ParallaxMarchNum
  _ParallaxUseNormal _AlphaPremultiply _ParallaxColor.rgb _BaseColor.a`), row 2 normal (`_BumpScale*_UseBumpMap`,
  `_SpecBumpScale*_UseSpecBumpMap`, has normal, has splitNormal), row 3 outline (`_OutlineWidth`, `_OutlineOffsetZ`,
  `_EnableOutline`, `_EnableOutlineMask`; -1/-1/1/1 when absent).
- **Materials sharing one atlas rectangle** (YSM merges equal base texture + colour + alpha mode: face_01 with the
  brows, Pelica cloth_01/04 and cloth_02/03) cannot be told apart per texel. They become one table entry, merged
  per value: map flags, `_OutlineWidth`, `_OutlineOffsetZ`, `_EnableOutline`, `_EnableOutlineMask` = maximum; other
  values from the first material that has the property, the region texture's owner (most maps) first, then listed
  order; each kind's layer texels from the first listed material with that map (so emission colour/brightness come
  from it). The load logs each merge (`materials [...] share one atlas region`).
- **Shading** (`npr_character.fsh`, behind the model having the data):
  - hair: gloss G scales the primary F0 (`0.04 G`, H2 1200) and the RS lookup (`u = sat(sinTH^200 G)`, H2 1689);
    A weights the secondary band (H2 1748); B is the property shadow weight (receiver × B, lit = min(.., B),
    H2 1601-1602).
  - hair line (H2 1155-1159, 1711-1756; ue5ref Hair.ush:632-638): stripes = 1 - _LineMap.r (tiled by `_ST`) when
    `_UseLineMap`, else the box-filtered square wave `ceil(sat(frac(u·_LineAmount) - .5))`; inside the
    `_LineValue`/`_LineRange` Kajiya band and outside the primary highlight they scale the diffuse by
    `lerp(1 - _LineIntensity, 1, stripe)`, weighted by G, then desaturate toward `_LineSaturation`.
  - cloth: metal = R, specular = G, smoothness = A (F0 = lerp(0.04 G, albedo, R), Gens2_11058832 122-123,
    1527-1531), B = property shadow weight (1631-1632) and rim gate.
  - emission is added after the rim and before the final exposure multiplier (ue5ref Cloth.ush:655-683; the cloth
    bytecode's last step is `× cb1[27].y`, Gens2_11058832 2907), in linear; `emissionScale` scales it.
  - material shader beats the class map (0.7.1 hairfix): a texel whose material the game draws with its hair shader
    (`_AnisotropyIntensity` in its params, table flag `NprMaps.HAIR_SHADER_FLAG`) is shaded as hair even where YSM's
    class map, or the colour fallback without one (`HasClassMap = 0`, which classes hair as cloth), says cloth/skin,
    and the cloth prop use (metal = R, specular = G, smoothness = A) only applies to non-hair-shader materials. Hair R
    is the strand tangent blend (0.8-1.0 on aglina, lastrite, liino, yvonne, zhuangfy): read as metal it left
    0.96 (1 - R) of the diffuse, i.e. black hair.
  - cloth environment reflection (`envReflection`): the game adds a cube-map IBL × EnvBRDF(F0, N·V, roughness)
    (Gens2_11058832 1895-1927, ue5ref Cloth.ush:594-604, EndfieldSurfaceCommon.ush:26-40); MC has no probe, so the
    light reaching the surface (`lightCol`) dimmed toward the ground stands in. Without it metal texels (R > 0.5 on
    11 % of Pelica's cloth_01) were black except for their GGX highlight. Applied to cloth with prop data or metal.
  - live A/B: `propMetal` (× R), `propShadowWeight` (B weight, 1 = game), `envReflection`.
  - back faces: the cotangent frame is built from the already flipped normal, so its T/B flip with it; the tangent
    perturbation is no longer multiplied by the facing a second time (npr_normal.glsl).
- **Hair gloss fallback** (models without a prop map): G = the package's `hairSpecMask.x` if it sets one, else the
  `hairGlossFallback` config (0.6, live). Endfield's hair RS ramps are black below u ≈ 0.52, so the old 0.5 meant no
  primary highlight at all. `tools/hair_gloss_eval.py` renders Pelica's real hair mesh in software (15 views ×
  12 suns, 1 mm/px): with her real `hair_01_p` the primary energy is 34.9 (mean peak 0.171); constant G 0.5 gives 0,
  0.55 0.42×/0.12× (energy/peak), 0.58 1.01×/0.24×, **0.6 1.53×/0.33×**, 0.65 3.3×/0.59×, 0.7 5.75×/0.93×. No
  constant matches both: the real map is a sparse band of strands (9 % of texels non-zero, mean 0.37), a constant
  lights the whole Kajiya ring. 0.6 keeps the summed log error near its minimum (0.58) with margin above the RS
  cliff.
- **Hair gloss reduction**: box-averaging the strand-thin hair G into the layer drops it under the RS ramp's black
  zone, so hair prop G/A are reduced with a power mean `(mean(x^8))^(1/8)` (`NprMaps.HAIR_GLOSS_POWER`; materials
  with `_AnisotropyIntensity`). `tools/hair_gloss_eval.py --composited` on Pelica's real composited layers (primary
  energy / mean peak relative to her full-resolution map, 1 mm/px):

  | source of the hair prop | budget | box (p 1) | p 4 | p 8 | p 16 |
  |---|---|---|---|---|---|
  | package 1024 px (patcher area filter) | 48 MB (1344²) | 0.48 / 0.91 | 0.70 / 0.92 | **0.86 / 0.93** | 1.00 / 0.95 |
  | package 1024 px | 96 MB (1843²) | 0.60 / 0.95 | 0.77 / 0.95 | **0.88 / 0.96** | 0.97 / 0.97 |
  | original 2048 px | 48 MB | 0.53 / 0.92 | 0.80 / 0.94 | **0.98 / 0.95** | |
  | original 2048 px | 96 MB | 0.63 / 0.94 | 0.83 / 0.94 | **0.95 / 0.95** | |
  | patcher p-mean 8 at 1024 | 48 MB | 0.59 / 0.93 | 0.87 / 0.94 | 1.04 / 0.94 | |

  With the hair prop shipped at its original size, p 8 lands within 5 % of the full map's energy at both budgets
  (p 16 is only right for the double-filtered 1024 source). At a distance the GL mipmaps average G again, as the
  game's own mipmaps do.
- Offline checks: `tools/maps-check.sh <modeldata.bin> <dir>` runs `NprMaps.build` on GenNpr output and writes the
  layers; `tools/hair_gloss_eval.py`; `tools/load-check.sh <res> <budgetMB> <repeat> <modeldata.bin>...` times the
  loader's worker half (package parse, PNG decodes, composite); `tools/cache-check.sh <dir> [--test]
  <modeldata.bin>...` tests the map cache (below) and times build / miss / hit.
- A/B: `materialMaps=false` (live) draws every model as if its package had no per-material maps: no Sampler11
  layers and an empty material table, so per-class constants, per-class outline widths, no hair line, no emission,
  no normal maps and no brow reveal. The iris terms keep their own switch (`irisTerms`).

### Loading speed (map cache, parallel compositing)

A model's package is read on the loader thread after YSM installed the model; the per-material maps are the costly
part: every map PNG decoded (stb) and box/power-mean filtered into the layers. Measured offline on the 24 patched
models (QEMU VM, SSE4.1 only, no AVX2/SHA-NI, so a desktop CPU is faster throughout):

- Before: map decode 130-500 ms + compositing 140-300 ms per model, `NprMaps.build` 276-790 ms (sum of 24: 12.5 s),
  every time a model is loaded (each join, each model switch back).
- **Parallel first load**: the map PNGs (one task per file) and the physical layers (one task per layer; the masks
  layer's three kinds stay in order on one task) run on a pool of `min(3, cores - 2)` low-priority threads. Output
  is byte-identical to the sequential code (all 7 layer dumps and the sheet compared). 115-316 ms per model (sum
  5.2 s, -59 %). Memory: every map PNG was already held decoded until the end of the build; that peak is unchanged.
- **Map cache** (`NprMapCache`, `mapCacheMB`, default 256, 0 = off): the composite (layer texels, tiled sheet,
  layer map, material table) is stored in `.ysm/endfield_npr/cache` in the game directory, keyed by SHA-256 of the
  package ZIP + `mapResolution` + `mapBudgetMB` + `HAIR_GLOSS_POWER` + `NprMaps.COMPOSITOR_VERSION` (bump it with
  any change to the compositing). Two zstd frames (YSM's native zstd, level 3) with their content hash; written to a
  temporary name and moved into place; a short, mismatched or damaged file is deleted and rebuilt; least recently
  used entries go beyond the cap (a hit refreshes the file time). 2.4-5.4 MB per model (88 MB for all 24). A hit
  takes 79-149 ms (sum 2.6 s, -79 % against the old sequential build), about half of it the SHA-256 of the package,
  which SHA-NI CPUs do several times faster. The log says per model whether the maps were built or read and how long
  it took (`per-material maps of <hash> read from the map cache in N ms`).
- `tools/cache-check.sh --test` (42 checks): miss/hit with an exact comparison to a fresh build; a change of
  resolution, budget, gloss power or package bytes misses; truncated, header-only, flipped payload byte, flipped
  key, trailing bytes, empty and random files are rejected, deleted and rebuilt; stale temporaries are removed;
  LRU eviction under the cap; cache off writes nothing; a broken map PNG fails the build with an IOException naming
  the file and caches nothing.

## Normal maps and the outline mask (`materials` kinds `normal`, `splitNormal`, `outlineMask`)

Data: the model package's per-material maps (contract `~/ysm/handoff/npr-materials-schema.md`, sampler and table
from `npr_maps.glsl`). Code: `shaders/include/npr_normal.glsl` (fragment), `shaders/include/npr_outline_mask.glsl`
(outline vertex stage). Models without these maps render exactly as before.

- **Tangent frame.** YSM has no vertex tangents, so `nprCotangentFrame` rebuilds it per pixel from
  `dFdx/dFdy(viewPos)` and of the atlas uv (Schüler 2013, "Normal Mapping Without Precomputed Tangents"). The atlas
  uv is each material's uv times one scale plus an offset (`TextureAtlas.remap`), so its directions are the
  material's. The frame follows each triangle's uv orientation, so mirrored islands need nothing special. The maps
  are Unity's (+G = +V): on Pelica's `_n` maps d(R)/d(row) and d(G)/d(column) anticorrelate (-0.16 to -0.37), as a
  height field does with +G image-up, and the glTF/atlas v grows down the image, hence B = -dP/dv. On a back face the
  tangent-plane part takes the facing sign, as the game flips the whole mapped normal (Skin S 189-196).
- **Decode** (EndfieldNormalDecode.ush:37-43, S 121-129): xy = (R·A, G)·2-1, z from the unscaled xy, then
  xy ·= `_BumpScale·_UseBumpMap` (table row `NPR_ROW_NORMAL`) · `normalMapStrength`.
- **Which terms use it.** Cloth and skin/body: the mapped normal replaces N everywhere the game uses its shading
  normal: diffuse ramp N·L and the view alpha, GGX, CP8 rim, back light, skin edge tint (ue5ref Cloth.ush 397/405/646,
  Skin.ush 68-95 + 284; S 189-196 selects the mapped normal when `_UseBumpMap`). The mesh normal stays for the
  self-shadow receiver offset and the scene AO. Face: same normal, but the SDF response does not read N at all; the
  geometric N·L only enters through the SDF mask's G (`rampU = mix(response, geometric, G)`, Face.ush:445-457), so the
  SDF look is unchanged; the importer's ellipsoid-bent face normals (`smoothFaces`) are the base the map tilts. Eyes,
  the outline shell and the MINIMAL tier: no maps. SSAA shades per sample, so the frame and the fetch run per sample
  there with nothing extra.
- **Hair** (`_SplitNormalMap`, H2 `HGRP_CharacterNPR_Hair_lod0_p0_s1_15471456.asm` 155-169 and 1142-1151,
  Stages/Hair.ush:84-135): RG is the diffuse normal (· `_BumpScale·_UseBumpMap`), used like the cloth one; BA is the
  specular normal (· `_SpecBumpScale·_UseSpecBumpMap`) and replaces N in the Kajiya block: strand base, primary and
  secondary shifts, the line band and the horizontal view fade (`specN`, line `NPR_HAIR_SPECN`). The property map's R
  blends the strand toward the uv tangent, `strand = lerp(cross(specN, axis), T, prop.r)`, `base = cross(specN,
  strand)·lerp(1, handedness, prop.r)` (H2 1176-1186; `nprHairStrandBase` after `NPR_HAIR_TB`). Deviation: the game
  leaves the specular normal unflipped on back faces; here it shares the diffuse normal's facing.
- **Outline** (`npr_outline.vsh`, EndfieldOutline.ush:39-53, Stages/Outline.ush:59-72, EndfieldOutlineDecode.ush:19-23):
  the mask is read per vertex at level 0; `shift ·= mask.r` after the one-pixel minimum, and the depth push is
  `_OutlineOffsetZ·mask.g·0.1 m` (+ the existing 6 cm). Where the vertex's material has them, the material's
  `_OutlineWidth` (· `outlineScale`), `_OutlineOffsetZ` and `_EnableOutline` replace the per-class values (the
  data has 0-0.69 widths and 0-1 offsets per material, and 25 cloth materials with `_EnableOutline = 0`, which a
  per-class value cannot express). The mask applies only with `_EnableOutlineMask`. Materials sharing one atlas
  rectangle share one table entry (the first one's parameters).
- **Keys**: `normalMapStrength` (1.0; 0 = mesh normals), `debugView=6` (the normal-mapped N as colour; 1 stays the
  mesh normal), `debugView=7` (its raw N·L).

Offline (`tools/normal_eval.py <vrm> ~/ysm/efdata/props <role> [res]`: rasterises the mesh in each material's
texture space, the same frame and decode, 48 random light directions; Pelica, maps at 1024², the package cap):

| material | tilt p50/p90/p99 | \|ΔN·L\| mean/p90/p99 | \|Δ ramp alpha\| mean/p90 | texels crossing the ramp midpoint | terminator shift p50/p90, px at 3 m (1.5 m) |
|---|---|---|---|---|---|
| body_01 | 2.6/6.7/14.7° | 0.028/0.067/0.160 | 0.016/0.048 | 1.8% | 0.4/2.0 (0.7/4.1) |
| cloth_01 | 6.1/24.4/62.9° | 0.089/0.222/0.677 | 0.045/0.129 | 5.1% | 1.5/13.1 (2.9/26.2) |
| cloth_02 | 6.0/22.4/51.8° | 0.083/0.211/0.571 | 0.042/0.124 | 4.6% | 0.9/8.5 (1.8/17.0) |
| cloth_03 | 4.2/11.3/32.0° | 0.047/0.116/0.268 | 0.024/0.071 | 2.8% | 1.7/7.7 (3.5/15.5) |
| cloth_04 | 2.0/6.1/13.8° | 0.024/0.058/0.152 | 0.012/0.035 | 1.3% | 0.4/1.9 (0.8/3.8) |
| face_01 | 0.3/0.4/23.5° | 0.010/0.005/0.219 | 0.006/0.004 | 0.6% | 0.1/0.2 (0.1/0.4) |

Terminator shift = \|ΔN·L\| / \|surface gradient of the mesh N·L\| on texels at the ramp midpoint, curved parts only
(7-20% of the cloth terminator texels lie on flat panels, where the terminator has no position); px at 1080p, FOV 70.
So the body barely moves; on the cloth the terminator stays put in the median and frays by up to ~13 px at 3 m along
the weave and folds. Face: G > 0.5 on 17% of its texels, and the G-weighted ramp-u change is 0.0002 mean (0.002 p99):
the face's light/shadow split does not move. Hair split normal: diffuse RG tilt mean 9.5° (p90 21°), specular BA
8.1° (p90 19°).

Outline mask on Pelica, per vertex (where the outline disappears, R < 0.05):
- `cloth_01` (width 0.65, offset 0.02): 2.7% of vertices, the forearm cuffs (`Bip001_*_ForeTwist`, `ForeTwist1`)
  and the two front hem flaps (`yibai01/02`); mean R 0.97.
- `face_01` (width 0.5, offset 0.75): 13.3% (both eye openings, `eyeLf/eyeRt`, and the lip line, `lipMdn`); 24.8%
  thinned (R 0.05-0.95). G is 0 on 69% of the face vertices, so the 7.5 cm push only acts around the contour
  (mean offset·G 0.147 instead of 0.75).
- `hair_01` (width 0.5, offset 0.01): 2.2%, inner layers against the head (`Bip001_Head`, `hair_base_Back_*`) and
  one strip of the front bangs (`hair_base_Front_c_01`).
- body: no mask; width 0.6, offset 0. cloth_02/03 and cloth_01/04 each share one atlas rectangle (YSM merges
  materials with the same base texture, factor and alpha mode), and the table merges such groups per value with the
  maximum (widths, offsets, `_EnableOutline`, `_EnableOutlineMask`; absent = -1 never wins): cloth_02/03 draw width
  0.65 (cloth_02 alone: 0.5), cloth_04 takes cloth_01's offset 0.02 and mask. Across the 24 models every face shares
  its rectangle with the brows (no outline params, so the face's values win), and 6 models have cloth groups whose
  widths, offsets or `_EnableOutline` disagree.
