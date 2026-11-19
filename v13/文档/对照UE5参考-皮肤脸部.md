# UE5 参考实现 vs 我们的实现：皮肤 / 脸 / 眉眼 / 叠加阴影 / 光照与调色

范围：Skin（身体）、Face（SDF、ShadowLut、HighlightMap、脸 AO、头部坐标系）、Brow、Eye、ShadowOverlay（eyeshadow/hairshadow）、主光/环境光/辐照度、颜色调整/色调映射、参考里所有 `_CharacterParams` 类场景值。
仅分析，没有改动任何代码。

缩写
- **R:** `~/ysm/ue5ref/`（参考实现）。`R:Skin` = `Shaders/Stages/Skin.ush`，`R:Face` = `Shaders/Stages/Face.ush`，`R:SC` = `Shaders/Modules/EndfieldSurfaceCommon.ush`，`R:LG` = `Shaders/Modules/EndfieldLightingGlobals.ush`，`R:SS` = `Shaders/Modules/EndfieldSurfaceSpecular.ush`。
- **ours** = `~/ysm/npr/mod/src/main/resources/assets/endfield_npr/shaders/core/npr_character.fsh`（行号指当前文件）。
- **S / F / H / E / O** = 我们的字节码证据（定义见 `NPR-reconstruction.md` §0）。S = `~/ysm/pelica/03-渲染管线/Skin_ps_完整反汇编.asm`，F = `全部反汇编/HGRP_CharacterNPR_Skin_lod0_p0_s1_8824968.asm`。
- 参考里的数学分三档可信度：(a) 写明 "Original … _NNNN / Frame1681 / SHA256" 的，是从原始 HLSL 逐行搬来的，可信度高；(b) "host approximation" 指 HG 管线拼装部分；(c) 声明改编自 chris0214 MIT 代码的部分（Kajiya、LUT、虹膜视差）。下面写"参考"时，会标出它属于哪一档。
- 标 **[推断]** 的结论没有被字节码或参考代码直接证实。

---

## 0. README 的说法："`femaleskincolorXX_lut_D.png` 是辐照度探针，不是肤色/暗部 LUT" —— **错误**

1. **参考代码自己就把它当作 2D 暗部颜色 LUT 来用。**
   - `R:SC:49-60` `EFL_ShadowLUT`：`color = saturate(LinearToSRGB(albedo))`，`useBRG=1` 时 B 通道选 slice，R 通道选 tile 内的 X，G 通道选 tile 内的 Y。`uv = (floor(slice)/32 + g*31/1024 + 0.5/1024, b*31/32 + 0.5/32)`，`uv.y = 1-uv.y`（第 56 行），两次采样后用 `frac(slice)` 插值。注释写的是 "Verified official Skin ForwardLit: 1024x32 / 32 horizontal tiles"。
   - 使用位置：
     - `R:Skin:129` `dark = lerp(Grade(raw,ShadowBrightness,ShadowSaturation), EFL_ShadowLUT(...), UseLUT)`
     - `R:Face:299-300`
     - `R:Brow:111`
     - `R:Outline.ush:331-336`（皮肤描边也走同一张 LUT）
   - 每一处都在算**暗部基色 `dark`**，没有一处用在环境光上。
   - 测试里也有 `_UseShadowLutTex` 开关：`Source/.../ShowcaseViewerCorrectionVerification.cpp:178,358`。
   - 整个 ue5ref 里没有出现 `skincolor` 这个文件名，因为 Content 没有随包提供。但 Pelica 材质的 `_ShadowLutTex` 槽（pathID 4951544993205285082）绑在 body、face、brow 三个材质上，`_UseShadowLutTex` 分别为 1、1、1；iris 为 0。
2. **README 引用的 "3D、A/B 两组、`*4-2`、464/29/116 三级 LOD" 是另一套资源。** 那是 `_IrradianceVolumeClipmapTexture{A,B}Lod{0,1,3}`（L1 SH clipmap），见：
   - `Plugins/.../EndfieldIrradianceReference.ush`
   - `Shaders/EndfieldIrradianceMath.ush:44-141`
   - 我们 S 252-477、`NPR-reconstruction.md` §1.2 和 §8 的 t5–t10

   这些贴图都是 `Texture3D`，和 1024×32 的 PNG 无关。
3. **我们的字节码结论（F 184-200）与参考的 `EFL_ShadowLUT` 逐项一致。**
   - F 184-190：对 albedo 做 sRGB 编码。
   - F 191：`r12.x = c.b*31`，`r12.w = c.g*0.96875`。
   - F 192-194：`u = floor(b*31)/32 + r*0.030273 + 0.000488`。
   - F 195、197：两次 `sample_l`，第二次 u 加 `+0.03125`。
   - F 198-200：按 `frac` 插值。

   唯一差别是 V 方向，见 §1.1。
4. **数值反证。** 用 Pelica 典型肤色（sRGB 0.96, 0.84, 0.78）去查 `femaleskincolor01`：
   - 按参考做 V 翻转，得到 **(195,160,153)**，是暖色的肤色暗部。
   - 在线性空间做 `Grade(0.55, 1.4)` 再 sRGB 编码，得到 **(193,163,145)**，几乎完全吻合（我本机算的）。

   可见这张 LUT 就是"线性空间暗部调色"烘焙出来的结果。它不可能是辐照度：一个辐照度量不会以 albedo 的 RGB 作为索引。

**结论：README 这一条是错的。我们的 F 184-200 判断正确；但我们采样时少了 V 翻转，这正是"洋红色"问题的根源，见 §1.1。**

---

## 1. Skin / Body

### 1.1 ShadowLut（暗部基色 LUT）

**(1) 参考**
- `R:SC:49-60`，重点是第 56 行 `uv.y = 1.0-uv.y`。
- 注释原文："AnimeStudio exports bottom-left Unity GPU data as top-left PNG, so procedural LUT V must be flipped"。

**(2) 我们**
- `ours:73-79` `shadowLut()`：`v = c.g*0.96875 + 0.015625`，没有翻转。
- `NprConfig.java:55` 把 `useShadowLut=false` 设为默认，理由是"looked magenta"。

**(3) 判定：我们错了。字节码和本机实测都能证实。**
- F 191-197 的 V 在 D3D 里不翻转，是因为 Unity 上传的纹理第 0 行是图像底部。PNG 是按自上而下导出的；MC 的 `NativeImage` 会把 PNG 第一行放在 v=0。
- 实测 `~/mc/client/config/endfield_npr/skin_LUT.png`（md5 = femaleskincolor01）：
  - x=0 那一列，第 0 行是 (49,148,49)，第 31 行是 (1,1,1)。也就是 G 从图像**下往上**增大。
  - 不翻转时，肤色查到的是 (166,70,126)，**正是洋红色**；翻转后是 (195,160,153)。
- 四张 skincolor LUT 的结果相同：翻转前全是洋红，翻转后全是肤色暗部。

**(4) 修改**

```glsl
// npr_character.fsh shadowLut(): R:SC:56 / F 191-197 —— PNG 行序与 Unity GPU 行序相反
vec2 uv = vec2(sl / 32.0 + c.r * 0.030273 + 0.000488, 1.0 - (c.g * 0.96875 + 0.015625));
```

- `NprConfig.java:55` 默认值改为 `useShadowLut=true`。
- 同时更新注释：洋红色的原因是 V 方向没翻，与 sRGB 标志无关。
- `~/mc/client/config/endfield_npr/endfield_npr.properties` 里的 `useShadowLut=false` 需要用户手动改。
- 输入的颜色空间不用改：MC 的 albedo 本来就是显示编码，直接当 sRGB 索引即可，和 F 184-190 的 `LinearToSRGB` 等价。

**(5) 影响与贴图**
- 影响：**高**。皮肤、脸、眉毛的暗部都会从"0.55 灰调"变成游戏里的暖肤色暗部。
- 贴图：已有，femaleskincolor01/02/03 都在。Pelica 用的是哪一张未知（pathID 4951544993205285082），三张差异很小。

### 1.2 暗部基色 Grade（`_ShadowColorBrightness/Saturation`）的颜色空间

**(1) 参考**
- `R:LG:40-44` `EFL_Grade(c,b,s) = max(lerp(luma(c), c, s) * b, 0)`。
- 输入是**线性** albedo：`R:Skin:128-129`，UE 的纹理是 sRGB 采样的；LUT 那条路径要先 `LinearToSRGB`，说明 albedo 在 shader 里是线性值。
- 深暗部 `Grade(dark*.65, 1, 1.2)` 也在线性空间：`R:SC:133`。

**(2) 我们**
- `ours:147-149`：`c = A*mat.z; shadowBase = mix(lum(c), c, mat.w)`，A 是**显示编码**值。
- `ours:201`：`deep = sat12(S*0.65)`。

**(3) 判定：我们错了。字节码给出公式，参考和 LUT 数值证明了颜色空间。**
- S 217-220 是同一个公式。但游戏是线性工作流：Unity Linear color space，F 184-190 先编码再查表也说明了这一点。
- 同一肤色下：
  - 显示空间乘 0.55 得到 **(148,117,105)**。
  - 线性空间得到 **(193,163,145)**，与 LUT 吻合。

  我们的暗部明显过暗、过饱和。0.55 在线性空间约等于显示空间的 0.76。
- 布料和头发也用这个 Grade，影响全身。属于共享代码，需要告知 `UE5HairClothOutline`。

**(4) 修改（最小改动：只把"暗部族"放到线性空间计算）**

```glsl
vec3 srgbDecode(vec3 c) {
    return mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92, vec3(lessThanEqual(c, vec3(0.04045))));
}
...
} else { // S 217-220 / R:LG:40-44，线性空间
    vec3 c = srgbDecode(A) * mat.z;
    shadowBase = srgbEncode(max(mix(vec3(dot(c, LUM)), c, mat.w), 0.0));
}
...
vec3 deep = srgbEncode(sat12(srgbDecode(S) * 0.65)); // R:SC:133
```

更彻底的做法是整条管线线性化：进入时 decode，输出时 encode。见 §8。

**(5) 影响与贴图**
- 影响：**高**。全身的阴影明度都会改变。
- 贴图：不需要。

### 1.3 漫反射三色选择 + ramp 着色（`EFL_OfficialDiffuse`）

**(1) 参考**
- `R:SC:127-140`：
  - `shaded = lerp(Grade(dark*.65,1,1.2), dark, sat(receiver*viewAlpha + ramp.a))`
  - `shaded = lerp(shaded, bright, lit)`
  - 只取 ramp 的色度
  - 保亮度修正，上限 1.5
  - `unlit = lerp(dark, Grade(bright,1,1.2), receiver)`
  - `lerp(unlit, colored, X)`
- 参考里 ramp 取样的位置：`R:Skin:285-287`。

**(2) 我们**
- `ours:176-208`。

**(3) 判定：一致**，与 S 1400-1424 一致。

差异只在输入：
- 我们的 `occ = 1`（`ours:122`）。游戏里是 `_BaseMap.a`，也就是烘焙的遮蔽/阴影遮罩（body_01_d 的 α 均值 0.48）。
- `ssao = 1`。参考里身体会额外取角色自阴影 Y：`R:Skin:30-33` `AmbientOcclusion = min(AO, bodyScreenY)`。

**(4) 修改** **[推断]**
- 如果 YSM 的 specular 图集 R 通道没有被占用，可以在建模工具里把 `_BaseMap.a` 写进 spec.r，然后令 `occ = spec.r`。
- 不能直接用 base.a：iris 的 α≈0.01，会被 `discard`；而且当前 YSM 贴图大概率已经被压成不透明，`fix_pelica.py` 第 20-21 行有说明。
- 这需要先改模型管线，属于另一项工作。

**(5) 影响与贴图**
- 影响：中。腋下、颈下、衣缝的固定暗部会丢失。
- 贴图：Pelica 有 `t_actor_pelica_body_01_d` 的 α，但目前没有进入 YSM。

### 1.4 边缘变色（`_SkinRimOff` / `_SDFRimColor`）

**(1) 参考**
- `Shaders/EndfieldBodyMath.ush:5-17`：`w = sat((1 - sat(sat(N·V)*0.85+0.15)) * SkinScale)`，`A *= lerp(1, SDFRimColor, w)`。
- 脸部：`Shaders/EndfieldFaceMath.ush:44-52`，加上 `mask.r * lerp(sat(headCameraFront+.5), 1, mask.g)`，并用 `lerp(FaceScale, SkinScale, mask.b)` 选择缩放。

**(2) 我们**
- `ours:127-135`。

**(3) 判定：一致。** 我们没有 `_SDFMask`，相当于 mask=(1,0,0)，也就是脸用 FaceScale 并带正面门限。

**(4) 修改：无。** 材质值核对：
- 脸的 `_SkinRimOffScale=1`，身体是 0.7。
- 我们对 FACE 用 `faceRimOffScale=1`，与游戏 mask.b=0 的情况一致。

**(5) 影响：** 无。

### 1.5 高光（GGX + 视角主导的半角）

**(1) 参考**
- `R:SS:39-43` `fill = CamBack - Up*dot(CamBack,Up) + Up*lerp(.5, L·Up, X)`，`H = normalize(L*X + normalize(fill)*2 + V*(2+X))`。
- GGX：`R:SS:45-53`。
- 衰减：`R:Skin:324-329`。
- `Up` 是**世界上方**（`float3(0,0,1)`，UE 的 Z 轴）。

**(2) 我们**
- `ours:227`：`normalize(vec3(cam.x, mix(0.5, L.y, shadow), cam.z))`。
- 其中 `cam=(0,0,1)`、`L.y` 都在**视空间**里，用的是相机的 up，而不是世界 up。

**(3) 判定：公式一致，坐标系不一致，我们错了。** S 1432-1445 用的也是世界 Y。

MC 第三人称相机常有 ±20–40° 的俯仰。那时我们用"相机上方"代替"世界上方"：
- 高光位置会跟着俯仰漂移；
- 背光的水平投影（`ours:156` 的 `Lxz`、`ours:256` 的 `x`、`ours:259` 的 `backLit`）同样会偏。

**(4) 修改**
- Java 传入 `UpView = mat3(worldToView)*(0,1,0)`，`NprRenderer.java:112` 里已经有 `c.worldToView`。
- shader：

```glsl
uniform vec3 UpView;
vec3 horiz(vec3 v) { return normalize(v - UpView * dot(v, UpView) + 1e-5); }
...
vec3 fill = cam - UpView * dot(cam, UpView) + UpView * mix(0.5, dot(L, UpView), shadow); // R:SS:41
vec3 Hs = normalize(L * shadow + 2.0 * normalize(fill) + V * (2.0 + shadow));
// 同理：
vec3 Lh = horiz(L);
float backLit = clamp(-dot(Lh, horiz(cam)), 0.0, 1.0);
```

`ours:156-157`、`ours:256` 也改成同样的水平投影。

**(5) 影响与贴图**
- 影响：中。相机俯仰越大越明显。
- 贴图：不需要。

### 1.6 主光颜色 / 环境光合成

**(1) 参考**
- `R:LG:156-170` `EFL_HGLightingFromIrradiance`：
  - `ambient = (sat(dot(hemi,CP6)+CP7.x)*CP7.y+CP7.z) * lerp(Tint, 1, ShadowTintMode*receiver)`
  - `direct = (lerp(lum(main), main, receiver) + ambient*clamp(Gain,0,1.5)*…) * MainMultiplier`
  - `indirect = ambient * lerp(min(.65+.35*Gain,1.5), clamp(Gain,1.25,1.75), LightDialogMode) * EnvMultiplier`
  - 最后 `lerp(indirect, direct, X)`
- 这部分属于 (b) 档 host 拼装，但与 S 1377-1399 逐项一致。

**(2) 我们**
- `ours:188-198`，有三处 MC 适配：
  - 亮部的环境光项多乘了 `*0.35`（`ours:198`）。
  - 暗部多乘了 `*ambLum`（`ours:195`）。
  - 用 `ambDir` 替代 CP6/7 的方向重映射，直接取 1。

**(3) 判定：公式一致，三处偏差都是有意的 MC 适配。**
- 字节码 S 1391-1399 里**没有** 0.35。游戏亮部 ≈ 主光 + ambient·min(Gain,1.5)，之后靠曝光和 ACES 压回，见 §8。
- 参考捕获到的 DefaultSH 对应 Gain≈1.57（§9），所以游戏亮部约是 1 + 1.5 倍环境光。
- 我们没有 tonemap，取 0.35 是为了防止过曝。在显示空间管线里这样做合理，保留。

**(4) 修改：** 线性化之后（§8）可以去掉 0.35，改由 tonemap 处理；在那之前保持不变。

**(5) 影响：** 低到中，取决于 §8 是否实施。

### 1.7 全局 Rim（CP8/CP9）

**(1) 参考**
- `R:LG:179-190` `axis = normalize(cross(cameraBackward, offset))`（第 185 行）。
- `rim = smoothstep(lerp(.8,.2,W), lerp(.9,.5,W), 1-|V·N|)`。
- `gate = min(sat(hemi·axis+1), ao) * sat(axis·N)`，结果乘以 `lerp(.25, albedo, CP9.z)`。

**(2) 我们**
- `ours:248`：`rimDir = normalize(cross(vec3(RimParams.xy,0), cam))`，这是**反过来的叉乘顺序**。

**(3) 判定：我们错了，符号反了。字节码可以证明。**
- 对 S 1505-1513 的寄存器展开：先 `mul r14, r11.xzw, cb0[6].zxy`，再 `mad r11.xzw, cb0[6].yyzx, r11.zzwx, -r14`。
- 把世界偏移 `(CP9.x, CP9.y, 0)` 的存储顺序 `(y,0,x)` 换算回来之后，得到的是 `cross(cb0[6], offset)`，与参考第 185 行一致。
- 我们的结果是它的负值，rim 会出现在相反一侧：默认参数 `rimScreenDir=1,0.3` 时轴是 (0.3,−1)，rim 被放到了**下边**。
- `NPR-reconstruction.md` §2.1 写的 `cross(lerp(...), cam)` 是笔误。

另外，原版的 CP9.xy 是**世界** right/up 偏移；CP15.w 才在世界偏移和相机偏移之间切换（S 1501-1504）。我们把它当成屏幕偏移，相当于 CP15.w=1。数值本来就未知，这一点可以接受。

**(4) 修改**

```glsl
vec3 rimDir = normalize(cross(cam, vec3(RimParams.x, RimParams.y, 0.0)) + 1e-5); // S 1505-1513, R:LG:185
```

改正之后，偏移 (ox,oy) 对应的视空间 rim 轴是 (−oy, ox, 0)。现在的默认值 `1.0,0.3` 会把 rim 放到左上（轴 (−0.3,1)）；改正前实际是在右下（轴 (0.3,−1)）。如果想要右上，把 `NprConfig.java:40` 的默认值改为 `0.3,-1.0`，轴为 (1,0.3)。由于 CP9 的真实值未知，具体方向按截图选。

**(5) 影响与贴图**
- 影响：中。rim 方向会变。
- 贴图：不需要。

### 1.8 背光 / 暗轮廓补光（ThinBacklight）

**(1) 参考**
- `Shaders/EndfieldEyeMath.ush:37-71`，这是 Iris 的原版代码，与身体 S 1542-1579 是同一个表达式。
- `color = lerp(radiance / max(max3(radiance)*.5, 1), MainColor, X)`：**归一化作用在环境辐射上**，主光颜色不做归一化。
- 参考的 `Skin.ush` 没有调用它，只有 Eye、Brow、Hair 调用。但我们的身体字节码 S 1542-1579 里明确有这一项，所以参考在身体上**漏了**。

**(2) 我们**
- `ours:258`：`mix(ambHue*min(ambLum,1), Lc / max(max3(Lc)*0.5,1), shadow)`。

**(3) 判定：我们小错。**
- S 1560-1567：`r20` 是 SH radiance，`r20 /= max(max3(r20)*.5, 1)`，然后 `lerp(r20n, Lcol*Lint, shadow)`。
- 我们把归一化放到了主光上。主光 ≤2 时这个归一化不起作用，所以实际效果差别很小。

**(4) 修改**

```glsl
vec3 blCol = mix(amb / max(max3(amb) * 0.5, 1.0), Lc, shadow);
```

**(5) 影响：** 低。

### 1.9 高亮区饱和提升（MainChroma）

**(1) 参考**
- `EndfieldBodyMath.ush:28-37` 和 `EndfieldFaceMath.ush:21-27`：`k = clamp(luma-.5, 0, .5)`，`lerp(luma, c, 1+k²)`，在 rim **之前**应用。

**(2) 我们**
- `ours:243-245`，同样在 rim 之前。

**(3) 判定：一致。** 但注意 luma 阈值 0.5 是针对**线性 HDR** 值定的；我们在显示空间算，触发得更早。全线性化之后自然会正确。

**(4) 修改：** 随 §8 一起处理。

**(5) 影响：** 低。

---

## 2. Face

### 2.1 头部坐标系

- **参考：** `R:Face:42-70`，`HeadForward/Right/Up` 单独传入。头部中心通过 `EFL_HemiNormal` 使用 **ObjectPosition**（`R:LG:149-154`），即角色根节点，epsilon 为 2^-14 m。
  - `Source/.../ShowcaseHeadFrameVerification.cpp` 验证的是头骨的帧。
- **我们：** `HeadRight/Up/Forward/HeadCenter` 由 Java 传入（`NprRenderer.java:162-165`）。
- **判定：** 一致。F 103-125 用的是 cb2 的每物体矩阵行。rim 的水平门限我们用的是 HeadCenter，参考用的是根节点，差别可以忽略。
- **修改：** 无。

### 2.2 SDF 脸部阴影

**(1) 参考**
- `R:Face:225-238`：
  - `localLight = normalize(L·R, ε, L·F)`
  - `wrap = sat(-Lh·Ch)*sat(-front)*(1-CP12.x)`
  - `halfLight = .5*lerp(front, front+.5-.5front², wrap)`
  - `t = clamp(.5-halfLight, .001, .999)`
  - `response = |smoothstep(max(2t-1,0), min(2t,1), (r+g)/2) + (halfLight>0 ? halfLight : 0)|`
- `R:Face:456-458`：`rampU = lerp(response, geometric, mask.g)`。
- 虚拟法线：`R:LG:192-201`，用 sdf.b。

**(2) 我们**
- `ours:163-174`。

**(3) 判定：一致。** 我们的 `NdotL = 2*resp-1` 再 `*0.5+0.5`，等价于直接用 response。

还有两个细节：
- 参考**没有** CP16.w·CP1.w 偏置；我们有（`Globals2.z`），这符合 F 775-777，**以字节码为准，我们是对的**。
- `Lh/Ch` 在参考和字节码里都是世界水平分量；我们用的是视空间 xz。

**(4) 修改**
- `backLit` 换成 §1.5 的水平投影。
- 缺 SDF 贴图时的降级方案 **[推断]**：目前回退到普通 N·L，鼻子和嘴唇的法线会产生脏阴影。建议对 FACE 用"头部球面法线"代替几何法线，类似参考里 face 环境法线的做法 `EFL_SourceFaceAmbientNormal`（`EndfieldFaceMath.ush:37-42`）：

```glsl
if (cls == FACE && UseSDF < 0.5) {
    vec3 hs = normalize(viewPos - HeadCenter);
    hs = normalize(hs - UpView * dot(hs, UpView));       // 只取水平方向，与 SDF 只看水平光一致
    NdotL = clamp(dot(hs, L) + Globals1.z, -1.0, 1.0);   // 得到干净的左右分界
}
```

**(5) 影响与贴图**
- 影响：**高**，但前提是有 SDF。Pelica 的 `_SDFLightmap`（-8871778439397823920）和 `_SDFMask`（5056732087567382100）**两个包里都没有**，pathID 索引也查不到。
- 降级方案的影响为中。

### 2.3 脸 ShadowLut

- **参考：** `R:Face:299-300`。
- **我们：** 与身体共用 `shadowLut`。
- **判定：** 同 §1.1，需要做 V 翻转。face 材质 `_UseShadowLutTex=1`。
- **影响：** 高。

### 2.4 脸 AO / 自阴影接收

- **参考：** `R:Face:485` `receiverMask = max(mask.g, mask.b*smoothstep(.75,.25,headCameraFront))`。场景项见 `EndfieldSceneShadowMath.ush:21-40`：
  - `Lit = min(Y, baseA, ramp.a)`
  - `receiver = baseA*Y`
  - `clamp(baseA*((1-maskG)+Y*maskG) + ramp.a)`
- **我们：** 没有自阴影，Y=1，相当于 `occ = ssao = 1`。
- **判定：** 结构一致，F 815-823。缺的是输入，属于 MC 的限制。
- **修改：** 无，或参考 §5，用叠加阴影补回刘海投影。
- **影响：** 中，主要表现为刘海没有投影。

### 2.5 `_HighlightMap`（脸部高光贴图）

- **参考：** `R:Face:632-634`：`uv + (V·R, V·U)*_HighlightMapVector.xy`，`c += hl.rgb * intensity * highlightFactor`；`highlightFactor = lighting * (spec*.5+.5) * lerp(DarkScale, 1, spec)`（`R:Face:544`）。
- **我们：** 没有实现。
- **判定：** 与 F 903-906、938 一致，我们缺这一项。
- **修改：** 等有贴图之后再加，代码约 3 行。Pelica 的 `_HighlightMapVector` = (0.03, 0)，`_FaceHighlightMap=1`。
- **影响与贴图：** 低到中。贴图缺失（pathID -2443816471313249430）。

### 2.6 脸高光（GGX）与 rim

- **参考：**
  - 脸高光：`R:Face:676-684`，半角向量与身体相同，`_Specular=0` 时为 0。
  - 脸只走 `EFL_OfficialFaceRim`（`R:LG:217-232`，使用虚拟法线 + 头部正面门限）。注释原文为 "source AutoRimEnable0 leaves this disabled"。
  - `R:Face:727` 的 `EFL_Rim` 是 UE 的附加功能，默认强度为 0。
- **我们：** 对 FACE 也施加了身体的全局 rim（`ours:247-253`）。
- **判定：我们偏离原版。**
  - 字节码里普通 rim 在脸上会乘 `1-CP16.z`。
  - 参考在 Pelica 上脸部 rim 是关闭的。
  - 结果是我们的脸颊轮廓会出现身体用的 rim。
- **修改**

```glsl
if (cls == FACE) rim = vec3(0.0); // R:LG:221 AutoRimEnable0；F 1001-1024 只在有 SDF 时给脸 rim
```

- **影响：** 低到中。

---

## 3. Brow（眉毛）

**(1) 参考**
- `R:Brow:84-118`：暗部 = `lerp(Grade(...), ShadowLUT, UseLUT) * 0.96`，Pelica brow 的 `_UseShadowLutTex=1`。
- `R:Brow:136-139`：光投影到头部水平面，`projectedLight = normalize(R*l.x + F*l.z)`。
- `R:Brow:180-183`：`lightU = N·projectedLight`，同时用 `viewU = N·CamBack` 去取 RD 的 α，作为 viewAlpha。
- `R:Brow:224`：`EFL_OfficialDiffuse(bright, dark, lightRamp, viewRamp.a, …)`。
- 之后还有 MainChroma 和 ThinBacklight（`R:Brow:253-289`）。

**(2) 我们**
- 眉毛和眼睛都归入 EYE 类（MatEye，ramp 第 4 行）。
- `ours:183-186` 强制 `litMask >= 0.75`、`aoMask = 1`，不用 LUT。

**(3) 判定：我们的近似过于粗糙。**
- 在原版里，眉毛会随头部水平光方向明暗变化，而且走 LUT。
- 0.75 下限会让背光时的眉毛依然很亮。
- 参考的 Brow 属于 (a) 档，注释引用了 Iris/Brow 的原始编号 `_1414`。

**(4) 修改**（与 §4 共用）

```glsl
if (cls == EYE) { // R:Eye.ush:242-246 / R:Brow:136-183
    vec3 Lh = normalize(HeadRight * dot(L, HeadRight) + HeadForward * dot(L, HeadForward) + 1e-5);
    NdotL = clamp(dot(N, Lh) + Globals1.z, -1.0, 1.0);
    ramp = rampRow(4, NdotL * 0.5 + 0.5);                 // 需把 ramp 采样移到这之后
    litMask = ramp.a;
    aoMask = rampRow(4, dot(N, cam) * 0.5 + 0.5).a;       // viewAlpha
}
```

眉毛要不要走 LUT 取决于它在 YSM 类别图里的编码：眉毛的 `_BaseMap` 就是脸的 `_BaseMap`（pathID −2024261820607097841）。**[推断]** 如果编成 160，也就是 FACE，就会走 LUT 和 SDF，SDF 部分不对；最好单独给眉毛一个类别码。

**(5) 影响与贴图**
- 影响：低到中。
- 贴图：RD 是 9015086203897338725，brow 和 iris 共用；文件名映射未知。

---

## 4. Eye（虹膜）

**(1) 参考**
- 虹膜视差：`R:Eye.ush:65-80`，`centered = frac(uv)-.5`，`irisUV = uv - V_t.xy*Parallax*(1,.25)*(1-smoothstep(.05,.25,r²))`。这部分改编自 MIT 代码，属于 (c) 档，但与 E 137-184 一致。
- 球面法线 + MatCap：`R:Eye.ush:145-148`，`EFL_MatcapUV`；`specCap = (cap.rgb*MatcapColor.a + MatcapColor.rgb*cap.a)`，见 `R:Eye.ush:191`。
- 圈外高光与散射：`R:Eye.ush:185-195`。
- 漫反射：`R:Eye.ush:242-246`，光投影到头部水平面，取 ramp，再用 `viewAlpha = RD(N_sphere·CamBack).a`。
- 场景漫反射：`EndfieldSceneShadowMath.ush:40-51`（原版 `_1351.._1366`）。
- 主色：`EndfieldEyeMath.ush:20-26`，MatCap 不做预乘。
- 自发光：`EndfieldEyeMath.ush:28-35`。

**(2) 我们**
- 只有平面漫反射，外加 0.75 下限（`ours:183-186`）。

**(3) 判定**
- 漫反射部分：我们偏离原版（同 §3）。
- 视差、MatCap、圈外高光都依赖**每只眼独立的 0..1 UV**（`frac(uv)`）。YSM 图集会重新打包 UV，**无法移植**，除非把原始的眼睛 UV 塞进第二套 UV 或顶点色 **[推断]**。

**(4) 修改**
- 漫反射按 §3 的代码段修改。
- MatCap、视差：暂不做。

**(5) 影响与贴图**
- 影响：低。
- 贴图：`_MatcapTex`（9171294409212959332）缺失。
- 可用的材质值见 §9。

---

## 5. ShadowOverlay（eyeshadow_01 / hairshadow_01）

**(1) 参考**
- `Shaders/Stages/ShadowOverlay.ush`：
  - `shadow = lerp(tex, (1,1,1,tex.r), UseGray) * (Tint, TintAlpha)`（第 57 行）。
  - 局部灯光会削弱 α。
  - VFX 混合。
  - 输出 `finalColor = lerp(1, color, α)`（第 100 行）。
- 混合方式为 `ModulateSourceColor`，即**乘法混合**：`ShowcaseVerificationEvidence.cpp:76`、`ShowcaseRenderEffectVerification.cpp:128`。
- 接收区域用模板测试：`ReadMask=20`，只画在皮肤和眼睛上（`ShowcaseNPRInputTests.cpp:411`、`ShadowOverlay.ush:8-28`）。可选 `_DisableDrawUnderHair`。
- 顶点偏移：`-CameraRight * LightDir.x * AngleRange`（第 34 行），叠加阴影随光的左右方向平移。
- Pelica 有 3 个 overlay：`M_hairshadow_common_01`、`M_eyeshadow_common_03`、`M_eyewhiteshadow_common_01`（`ShowcaseViewerCorrectionVerification.cpp:288-289`；`ShowcaseNPRInputTests.cpp:438` 断言恰好 3 个）。
- 我们的反编译 O 与之一致：`o0.rgb = 1 + a*(c-1)`，`o0.a = a`（OverlayShadow_70036.glsl 630-643）。

**(2) 我们**
- `fix_pelica.py:18-19,33`、`drop_helper_meshes.py` 把这两个网格**删掉了**。
- `NPR-reconstruction.md` §6 的结论"Pelica 材质里没有 OverlayShadow → 不需要"**是错的**。这些材质是 common 共享材质，不在 Pelica 自己的材质包里。

**(3) 判定：我们错了。**
- 之所以"盖住眼睛"，是因为把它们当作不透明几何画了。它们本应是乘法混合的半透明暗化层。
- 原版的作用是：没有角色自阴影时，用它伪造刘海投在额头上的影子，以及眼窝和上眼睑的影子。
- 我们没有自阴影（§2.4），所以**更需要**它们。

**(4) 修改** **[推断：实现方案；贴图缺失部分需要自制]**

1. 模型端：保留 `eyeshadow_01`（36 个顶点，UV 0..1）和 `hairshadow_01`（145 个顶点，UV u 0.015–0.98、v 0.018–0.62）。数据来自 `~/ysm/pelica/01-模型/佩丽卡-原始素材.glb` 实测。
   - 在 YSM 的 spec 图集里给它们一个专用类别码，例如蓝通道 200；基色可以用灰度渐变当 α。
2. 渲染端：同一份捕获顶点画两遍。
   - 第一遍是不透明主 pass，`discard` 掉 overlay 类别。
   - 第二遍只画 overlay 类别：

```java
RenderSystem.enableBlend();
RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
RenderSystem.depthMask(false);   // LEQUAL；必要时 glPolygonOffset(-1,-1)
```

```glsl
// overlay 分支（npr_character.fsh 或独立 npr_overlay.fsh）
float a = base.r * OverlayTint.a;                              // _UseGrayAsAlpha=1 默认；ShadowOverlay.ush:57
fragColor = vec4(mix(vec3(1.0), OverlayTint.rgb * base.r, a), 1.0); // ShadowOverlay.ush:100
```

3. 没有原版贴图时，可以用 UV 边缘羽化 `a = TintAlpha * smoothstep(0.0, 0.25, min(min(uv.x,1-uv.x), min(uv.y,1-uv.y)))`，这需要局部 UV。tint 可以取 LUT 暗部色与亮部色之比，约 (0.80,0.72,0.75)，α 取 0.5 左右；这些数值都要按截图调。
4. 模板门限暂不实现。hairshadow 在头发后面，靠深度就会被刘海遮住；eyeshadow 本来就应该压暗眼白和虹膜上缘。

**(5) 影响与贴图**
- 影响：**中到高**。额头和眼窝的立体感主要来自这两层。
- 贴图：`M_hairshadow_common_01`、`M_eyeshadow_common_03`、`M_eyewhiteshadow_common_01` 的贴图都**缺失**，需要自制灰度渐变。

---

## 6. 主光：参考里的"角色跟随光"

**(1) 参考**
- `Source/EndfieldCharacterLab/Private/Rendering/ShowcaseNPRInputs.cpp:195-208`，注释为 "recovered CharInfo camera pitch MAX clamp and yaw offset"：
  - `pitch = max(32°, 相机俯角)`，`yaw = 相机 yaw + 12°`。
  - `L = (-cos(yaw)cosP, -sin(yaw)cosP, sinP)`，即光从相机背后、偏 12°、至少 32° 高处打来。
- 默认值：`ShowcaseNPRSettings.h:41` `CameraFollowBiasDegrees=(32,12)`。
- 主光颜色倍率 = `Intensity/8.631674`（第 210-213 行）。

**(2) 我们**
- `NprRenderer.java:115-118`：视空间 key 为 `(0.45,0.55,0.7)`，即右偏约 33°、仰角约 33°（相对相机，不是世界）。
- 与太阳方向按 `characterLight=0.6` 混合。

**(3) 判定：参考是 (b) 档 host 代码，但数值标明为 "recovered"，比我们凭眼睛调的值可信。**
- 我们的偏航应为 12° 而不是 33°；俯仰应相对世界上方，且有 32° 下限。
- **[推断]** 侧向：UE(Y,Z,X)=Unity(X,Y,Z) 是循环置换，不产生镜像；按 UE 正 yaw 向右转推算，光应在**相机左侧** 12°。MC 的 yRot 增大也是向右转。

**(4) 修改**

```java
// NprRenderer: CharInfo CameraFollow (ue5ref ShowcaseNPRInputs.cpp:195-208)
var cam = mc.gameRenderer.getMainCamera();
float p = (float)Math.toRadians(Math.max(32f, cam.getXRot()));   // MC xRot>0 = 低头
float y = (float)Math.toRadians(cam.getYRot() + 12f);
var keyWorld = new Vector3f(Mth.sin(y) * Mth.cos(p), Mth.sin(p), -Mth.cos(y) * Mth.cos(p));
var key = c.worldToView.transform(keyWorld, new Vector3f()).normalize();
lightView.lerp(key, Mth.clamp(NprConfig.f("characterLight"), 0f, 1f)).normalize();
```

`characterLight` 的取值：
- 想要"展示模式"的观感，用 1.0。
- 想让角色在世界里跟随太阳，保留 0.6；也可以改成 0。

**(5) 影响：** 中。所有明暗分界都会受影响。

## 7. 环境光、辐照度，以及 MC 里该用什么

**(1) 参考**
- 色调与增益：`Shaders/EndfieldIrradianceMath.ush:118-141`。
  - `Radiance = SH(N)`，`Gain = max3(SH(峰值方向))*EnvGain`。
  - `Tint` 取 HSV 色相，饱和度上限 `lerp(.7,.35,smoothstep(.45,.35,|h-.5|))*sat(V)`，明度补偿 `2/(2-s)`。
  - 原版 Hair `_1162.._1165`，属于 (a) 档。
- 体积不可用时：`R:LG:129-147`，`Radiance=Tint*Exposure`，即**材质声明的 ShadowTint**（CP2/3 AutoTintColor），不额外合成天空 SH。

**(2) 我们**
- `ours:189-195`：
  - `amb = lightmap*ambientScale`，`ambLum = max3(amb)`。
  - `ambHue = mix(amb/ambLum, mix(1, skyHue, 0.45), skyLight)`。

**(3) 判定：结构一致**，都是"色相 + 亮度"两部分。差异有两点：
- 色相没有做饱和度上限和明度补偿。`amb/max3` 对饱和颜色会降低亮度，例如蓝天色的 luma≈0.67。
- MC 没有 SH。

**(4) 修改**：移植 tint 函数，作用于 MC 的环境色。

```glsl
vec3 hgTint(vec3 c) { // EndfieldIrradianceMath.ush:129-139
    vec4 p = mix(vec4(c.b, c.g, -1.0, 2.0/3.0), vec4(c.g, c.b, 0.0, -1.0/3.0), step(c.b, c.g));
    vec4 q = mix(vec4(p.x, p.y, p.w, c.r), vec4(c.r, p.y, p.z, p.x), step(p.x, c.r));
    float ch = q.x - min(q.w, q.y);
    float h = fract(abs(q.z + (q.w - q.y) / (6.0 * ch + 1e-4)));
    float s = min(ch / (q.x + 1e-4), mix(0.7, 0.35, smoothstep(0.45, 0.35, abs(h - 0.5))) * clamp(q.x, 0.0, 1.0));
    return mix(vec3(1.0), clamp(abs(fract(h + vec3(1.0, 2.0/3.0, 1.0/3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0), s) * (2.0 / (2.0 - s));
}
// ambHue = hgTint(mix(amb, SkyColor*ambLum, skyBlock.x*0.45)); ambLum 保持 max3(amb)
```

MC 里的输入对应关系：
- `Gain`：lightmap 亮度乘 `ambientScale`。
- 色相：天空色加方块光色。
- 背光所需的 `IrradianceDirection`：可以直接用世界上方，SH 的 L1 主方向在室外就是向上。参考捕获的 DefaultSH 的 L1 也几乎全在 +Y 上，见 §9。

**(5) 影响：** 低到中，主要影响暗部色调。

## 8. 颜色调整 / 色调映射 / 后处理

**(1) 参考**
- VFX 颜色调整：`EndfieldColorAdjustmentMath.ush:5-16`，在局部灯光之后、曝光之前。**Pelica 的 `_EnableVFXColorAdjustment=0`**，不启用。
- 材质 ColorAdjustment 为默认值：亮度、对比度、饱和度都是 1。
- 输出：`o0 = color*cb1[27].y`，即曝光（S 2156）。
- 后处理：`Shaders/EndfieldPostProcess.ush:73-215`，`TONEMAPPING_ACES_MODIFIED` 的 LutBuilder 包含：
  - ACEScc 对数编码。
  - 0.93 去饱和。
  - 亮度 >0.5 时的保色相 max-normalize 混合（第 213 行）。
  - 拟合 `(x*(2.785x+0.1078)) / (x*(2.936x+0.8871)+0.8069)`（第 211 行）。
- UE 展示台另有设置：Bloom 0.25、阈值 1、Vignette 0.15、手动曝光（`ShowcaseStage.cpp:28-33`）。注意这是 host 舞台的设置，**不是原版**。

**(2) 我们**
- 显示空间管线，`fragColor = color*exposure`（`ours:265`）。
- 不做 tonemap，绘制发生在 Oculus composite 之后。

**(3) 判定：两边都有不确定性。**
- 游戏的曝光值和 Gain 都没有被捕获（参考里写着 "Runtime setters remain uncaptured"）。
- 能确定的只有：游戏在线性 HDR 下着色，再经 ACES-modified 压缩。

**(4) 修改** **[推断]**，分两步：
1. 先做 §1.2 的"暗部族线性化"，收益大、风险小。
2. 之后可选：整条管线线性化 + 角色专用 tonemap，用 config 项 `linearWorkflow` 开关。

```glsl
vec3 A = srgbDecode(base.rgb) * vertexColor.rgb * ColorModulator.rgb;   // 线性 albedo
// shadowLut 输入改为 srgbEncode(A)，输出 srgbDecode(lut)（R:SC:286 + 注释"UE LUT texture has sRGB enabled"）
// ……全部光照，去掉 ours:198 的 *0.35 ……
vec3 x = max(color * Globals0.x, 0.0);
vec3 t = clamp(x * (2.785085 * x + 0.107772) / (x * (2.936045 * x + 0.887122) + 0.806889), 0.0, 1.0); // PostProcess.ush:211 的曲线（不含 ACEScc/色域矩阵）
fragColor = vec4(srgbEncode(t), 1.0);
```

风险：只对角色做 tonemap，角色与世界的明度会脱节；ramp 贴图的颜色空间也未知。所以第 2 步只作为可选项。

**(5) 影响：** 第 1 步为高（见 §1.2）；第 2 步为中。

---

## 9. 可以直接采用的数值

| 项 | 值 | 来源 / 可信度 |
|---|---|---|
| CharInfo 跟随光 | pitch = max(**32°**, 相机俯角)，yaw = 相机 yaw **+12°** | `ShowcaseNPRSettings.h:41`，`ShowcaseNPRInputs.cpp:195-208`（"recovered"） |
| 主光颜色倍率 | `Intensity / 8.631674` | `ShowcaseNPRInputs.cpp:212` |
| DefaultSH（EID731，R=G=B） | `(-0.007551, 0.472237, 0.012171, 1.096306)`（L1 x,y,z + 常数）→ 向上 ≈1.568，水平 ≈1.096，向下 ≈0.624；Gain ≈1.568×EnvGain；无色相 | `ShowcaseIrradianceVolume.cpp:28-37`（原版捕获） |
| IV 参数 | Param0 (-531.86, 92.68, -582.68, -1)、Param1 (1/128, 1/64, 1/128)、Param2 (18, 13, 52, 144)；clipmap 半宽 29/13、116/52、464/208 | 同上；`EndfieldIrradianceMath.ush:76-99` |
| HG 全局 ↔ CP 映射 | MainMultiplier=CP0.y，DarkScale=CP0.z，EnvMultiplier=CP0.w，LightDialogMode=CP1.x，ShadowTintMode=CP1.y，SceneShadowBypass=CP1.z，CharLight 混合=CP1.w，ShadowTint=CP2/3（AutoTintColor），MainOverrideColor=CP4，AmbientDirection=CP6，AmbientRemap=CP7.xyz，Rim=CP8(rgb,a)/CP9(xy 方向, z 反照率混合, w 宽度)，RangeBias=CP11.w，RangeEnabled=CP12.x，MainOverrideEnabled=CP12.y，IgnoreSceneEnv=CP12.w，SpecularScale=CP13.w，RimCameraRelative=CP15.w | `R:LG:46-65` 注释 + 我们 S 1342-1399、1499-1541 对照（**只有映射，没有数值**；参考的材质资产不在包里） |
| VFX 颜色调整默认 | RimWidth 0.35，RimIntensity 4，Blend (1,1,1,0)，**关闭** | `R:LG:85-87` = Pelica `_ColorAdjustmentRim*` |
| 脸 | `_SkinRimOffScale=1`，`_FaceRimOffScale=1`，`_HighlightMapVector=(0.03,0)`，`_FaceHighlightMap=1`，`_UseSDFLightmap=1`，`_UseShadowLutTex=1`，`_Smoothness=1`，`_Specular=0` | `m_actor_pelica_face_01.mat` |
| 身体 | `_SkinRimOffScale=0.7`，`_UseShadowLutTex=1`，`_HighlightMapVector=(0,-0.05)`（未启用） | `m_actor_pelica_body_01.mat` |
| 眉毛 | `_UseShadowLutTex=1`，`_ShadowColor*=(0.55,1.4)`，与脸共用 `_BaseMap`，RD = 9015086203897338725 | `m_actor_pelica_brow_01.mat` |
| 虹膜 | `_UseShadowLutTex=0`，`_ParallaxScale=0.03`，`_MatcapNormalScale=0.9`，`_EyeHighLightColor=(2.240,1.534,1.738)`，`_EyeScatteringColor=(1.642,1.767,1.844)`，`_MatcapColor=(0.239,0.360,0.819,0.698)`，`_EmissionColor=(0,0.038,0.031)`，`_UseMatcap=1`，RD 与眉毛相同 | `m_actor_pelica_iris_01.mat` |
| RD 映射 | body 与 face 共用 RD 5848563174712869001；brow 与 iris 共用 9015086203897338725 | 材质 JSON。参考包里同样没有文件名映射 |
| Overlay | `M_hairshadow_common_01`、`M_eyeshadow_common_03`、`M_eyewhiteshadow_common_01`；混合 = DstColor×Src；模板 ReadMask 20；`_UseGrayAsAlpha` 默认 1 | `ShowcaseViewerCorrectionVerification.cpp:288-289`，`ShowcaseNPRInputs` 测试 `:411,438`，`ShowcaseNPRPassBuilder.cpp:58-63` |
| 后处理 | ACES_modified（第 211-213 行），曝光未捕获；舞台 Bloom 0.25/阈值 1、Vignette 0.15 | `EndfieldPostProcess.ush`；`ShowcaseStage.cpp:28-33`（host） |
| EFL_HemiNormal | `p = pos - root`，竖直分量取 2^-14 m 后归一化 | `R:LG:149-154` |

**参考里没有任何 `_CharacterParams` 的实际数值**：CP0..16 的数值存在 UE 材质资产（`Content/`）中，没有随包提供。因此我们的 `exposure / lightScale / shadowScale / shadowAmbScale / ambToWhite / backlight / rim*` 仍然只能凭截图调。

---

## 10. 修复优先级（最多 10 项）

1. **ShadowLut 做 V 翻转，并默认开启 `useShadowLut=true`。** 位置：`ours:77`，`NprConfig.java:55`；本机 properties 需要用户手动改。依据：F 191-197、`R:SC:56`、本机实测（未翻转查到 (166,70,126) 洋红，翻转后为 (195,160,153)）。影响：高，贴图已有。
2. **暗部 Grade 和深暗部放到线性空间计算**（§1.2）。位置：`ours:147-149, 201`。依据：线性 Grade 与 LUT 吻合（193,163,145）。影响：高，全身。需要通知 `UE5HairClothOutline`，因为 cloth/hair 共用这段代码。
3. **恢复 eyeshadow_01 / hairshadow_01，作为乘法叠加 pass**（§5）。模型侧加专用类别码，渲染侧再画一遍并用 DST_COLOR 乘法混合。贴图需要自制灰度渐变。影响：中到高。
4. **改正 rim 轴叉乘顺序为 `cross(cam, offset)`，并关闭 FACE 上的身体 rim**（§1.7、§2.6）。位置：`ours:248, 252`，同时调整 `rimScreenDir` 默认值。依据：S 1505-1513、`R:LG:185`。影响：中。
5. **把"上方"统一为世界上方 `UpView`**（§1.5）。涉及半角向量和所有水平投影：`ours:156-157, 227, 256-259`、SDF 的 backLit `ours:167`。影响：中。
6. **主光改为 CharInfo 跟随光**：pitch≥32°，yaw+12°，在世界坐标中计算（§6）。位置：`NprRenderer.java:115-118`。影响：中。
7. **EYE 类（眉毛、虹膜）改用头部水平投影光 + viewAlpha，去掉 0.75 下限**；眉毛单独设类别码并走 LUT（§3、§4）。位置：`ours:183-186`。影响：低到中。
8. **脸缺 SDF 时，用头部水平球面法线做 N·L 降级**（§2.2）。位置：`ours:155` 附近。影响：中。这是降级方案，真正的 SDF 贴图缺失。
9. **环境色相改用参考的 `hgTint`（饱和度上限 + `2/(2-s)` 明度补偿）**；背光颜色的归一化改为作用在环境光上（§7、§1.8）。位置：`ours:192, 258`。影响：低到中。
10. **可选：全线性管线 + 角色 ACES-modified 曲线**，并去掉 `*0.35`（§8、§1.6）。另一个可选项：如果 spec 图集有空闲通道，把 `_BaseMap.a` 写进去作为 occ（§1.3）。影响：中。整体风险较高，需要截图对比。
