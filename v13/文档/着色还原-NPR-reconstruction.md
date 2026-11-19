# 终末地 CharacterNPR 角色着色 —— 字节码级还原（Skin / Face-SDF / Hair / Cloth / Eye / OverlayShadow）

> 只用于本地研究。所有结论都来自 D3D11 字节码本身（`.asm` / 本机用 vkd3d→SPIR-V→spirv-cross 反编译的 GLSL）
> 与 Unity 着色器容器里的**参数表**（cbuffer 名 + 参数名 + 字节偏移）。旧文档（`06-文档/`）只作为"待核对"的输入，
> 其主公式判断是错的（见 §8）。标 **[推断]** 的是从数据流推出的语义，没有名字/常量可直接佐证。

## 0. 证据来源与缩写

| 缩写 | 文件 | 说明 |
|---|---|---|
| **S** | `~/ysm/pelica/03-渲染管线/Skin_ps_完整反汇编.asm`（= `全部反汇编/HGRP_CharacterNPR_Skin_lod0_p0_s1_6815700.asm`） | Skin 身体变体（含雨/雪/溶解/菲涅尔叠加，最长）|
| **F** | `全部反汇编/HGRP_CharacterNPR_Skin_lod0_p0_s1_8824968.asm` | Skin **脸**变体：`_SDFLightmap`/`_SDFMask`/`_ShadowLutTex`/`_HighlightMap`/`_FaceDecalMap` |
| **H** | `~/ysm/pelica/03-渲染管线/Hair_ps_完整反汇编.asm`（= `..._Hair_lod0_p0_s1_14303792.asm`） | Hair 最全变体（Kajiya 高光 + LineMap）|
| **H2** | `全部反汇编/HGRP_CharacterNPR_Hair_lod0_p0_s1_15471456.asm` | Hair 带 `_SpecRampMap`（2D RS）的变体 |
| **C** | `管线反汇编/HGRP_CharacterNPR_lod0_p0_s4_3061300.asm` | `HGRP/CharacterNPR`（**布料用的就是这个**）|
| **C2** | `~/ysm/npr/decompiled/Gens2_9372380.glsl` | CharacterNPR 带 `_SpecRampMap` 的变体（本机反编译）|
| **E** | `~/ysm/npr/decompiled/Eye_950424.glsl` | `HGRP/CharacterNPR_Eye` 最大 PS（本机反编译）|
| **O** | `~/ysm/npr/decompiled/OverlayShadow_70036.glsl` | `HGRP/CharacterNPR_OverlayShadow` PS（本机反编译）|

行号都指上述文件。`exp`/`log` 为以 2 为底。

### 0.1 shader ↔ 材质 ↔ 变体族（已核实）

`全部shader清单.tsv` 给出 bundle→shader 路径；材质 JSON 给出 shader pathID：

| 材质 | shader pathID | shader | 本包里的字节码 |
|---|---|---|---|
| body_01, face_01 | 4484747192473637154 | `HGRP/CharacterNPR_Skin` | `全部反汇编/*Skin*`（258 个前向 PS：135 身体型 + 123 SDF 脸型）|
| cloth_01..04 | -7822190029627442914 | `HGRP/CharacterNPR`（通用，266 个属性：MetallicGloss/SpecRamp/ClearCoat/Anisotropy/丝袜…）| `管线反汇编/`（8 个）+ 我从 `pelica-full/03-渲染管线/角色NPR原始字节码/HGRP_CharacterNPR_lod0_p0_s1..s6.dxbc` 切出并反编译的 2319 个程序（`~/ysm/npr/work/Gens*/`）|
| hair_01, hairt_01 | -8095970123935614097 | `HGRP/CharacterNPR_Hair` | `全部反汇编/*Hair*`（s1 全是 Kajiya 型；s2 有 120 个无 Kajiya 的低配变体）|
| brow_01, iris_01 | -1706220712117210762 | `HGRP/CharacterNPR_Eye` | `Eye_sub0.lz4dec.bin` → 110 个程序，全部本机反编译成功（`~/ysm/npr/work/Eye/`）|

`着色器二进制/*.bin` 是 **LZ4 block** 流（无帧头，第一个 block 我能独立解出 2.1 MB/110 个 DXBC）；`pelica-full` 里已有完整 `*.lz4dec.bin`。
本机 vkd3d 1.2 不支持 `d3d-asm` 输出，所以我用 `vkd3d-compiler -b spirv-binary` + `spirv-cross --version 450` 得到可读 GLSL（Eye 110/110、OverlayShadow 16/16、ProxyLod 12/12、CharacterNPR 2319/2508 成功）。

### 0.2 参数名是怎么拿到的（不是猜的）

Unity 容器（`Unity容器/*.dxbc`、`pelica-full/.../角色NPR原始字节码/*.dxbc`、`*.lz4dec.bin`）的前部是每个子程序的参数表：
`[u32 len][name][pad4] [u32 size] [u32 nVec] { [u32 len][name][pad] i32×6(…, dim, …, byteOffset) } [u32 nMat] …`。
解析 `UnityPerMaterial` 记录（按 cbuffer 大小分组，避免不同关键字布局混淆）得到 **cb6 = UnityPerMaterial**（PS 中）、**cb3 = UnityPerMaterial**（VS 中，`cb3[10] = _BaseMap_ST`）。
`ShaderVariablesGlobal`（3328 B）只有少数参数带名：**cb1[5]=_ScreenParams、cb1[105]=_VFXParams0（.w=时间）、cb1[123]=_CharacterParams13、cb1[124]=_CharacterParams14、cb1[126]=_CharacterParams16** ⇒ cb1 = ShaderVariablesGlobal，`cb1[110..126] = _CharacterParams0..16`（全局"角色灯光"参数，由游戏逐场景设置，材质里没有，数值未知）。
采样器表在所有 122 个 Skin 程序与 592 个 CharacterNPR 程序里一致：**s0 = s_linear_clamp_sampler，s1 = s_linear_repeat_sampler，s2 = s_linear_clamp_compare_sampler**（s3 为溶解图的独立采样器）。

**Skin（CB6[40] 布局，S）已确认的 cb6：**
`cb6[0] = (_Smoothness, _Specular, _Metallic, _BumpScale)`、`cb6[1].y=_BackFaceNormalFlip`、`cb6[1].z=_AlphaPremultiply`、`cb6[1].w=_EmissionBrightness`、`cb6[2].x=_SurfaceType`、`cb6[3..4].xy/cb6[8]/cb6[9]` = ColorAdjustment(启用位/亮度/饱和/对比/RimWidth/RimIntensity/ColorBlend/RimColor)、`cb6[4].zw=(_ShadowColorBrightness,_ShadowColorSaturation)`、`cb6[5].x=_DisableRainEffectOnMaterial`、`cb6[6]=_BaseColor`、`cb6[7]=_EmissionColor`、`cb6[12].x=_SkinRimOffScale`[由同类推断]、`cb6[12].y=_FaceRimOffScale`、`cb6[13]=_SDFRimColor`[排除法推断]、`cb6[14..18]=_StylizedFresnel*`、`cb6[22..31]=_VFX*`、`cb6[32].xy=_BaseMapUVSpeed`、`cb6[34..39]=Dissolve/CutOff`。
（Skin 的 0..13 号槽在容器里只出现部分名字，`_Smoothness/_Specular/_Metallic` 的位置由 CharacterNPR 的同一 CBUFFER 前缀直接命名：`cb[0]=(_Smoothness,_Specular,_Metallic,_BumpScale)`、`cb[1].x=_SpecRampIridescentMode`、`cb[2].y=_AlphaClipThreshold`、`cb[5].x=_DisableRainEffectOnMaterial`、`cb[6]=_BaseColor`、`cb[10]=_BaseMap_ST`，与 S 中的用法逐一吻合。）

**脸（F，CB6[30]）**：`cb6[19]=(_FaceDecalSize,_FaceDecalRotation,_FaceDecalMirrorMode,_FaceDecalMirrorSplit)`、`cb6[20]=(_FaceDecalCenterX/Y,_FaceDecalInvertX/Y)`、`cb6[21].x=_FaceDecalBrightnessMask`、`cb6[22]=_FaceDecalTintColor`、`cb6[23]=_HighlightMapVector`。
**Hair（H，CB6[33]）**：`cb6[0].yzw=(_Specular,_Metallic,_BumpScale)`、`cb6[12].y=_SpecBumpScale`、`cb6[13]=(_AnisotropyValue, –, _AnisotropyIntensity, _AnisotropyEdgeFade)`、`cb6[14].y=_AnisotropyDirX`、`cb6[16]=(_LineAmount,_LineValue,_LineRange,_LineIntensity)`、`cb6[17]=(_LineSaturation,_UseLineMap)`、`cb6[18]=_LineMap_ST`、`cb6[25]/[26]=_HairBaseTintColor/_HairAddTintColor`（528B 布局）。
**Eye（E，CB6[25]）**：`cb6[12].y=_ParallaxScale`、`cb6[15]=_EyeScatteringColor`、`cb6[16]=_EyeHighLightColor`、`cb6[17].y=_MatcapNormalScale`、`cb6[18]=_MatcapColor`。

### 0.3 贴图槽（脸变体 F，按用法 + 容器纹理表交叉确认）

| t# | 名字 | 采样 | 用途 |
|---|---|---|---|
| t11 | `_DiffRampMap` | s0, (NdotL·½+½, 0.5) | 漫反射 ramp（RD 256×1）|
| t12 | `_HighlightMap` | s1, uv+视差 | 脸部高光（随视角滑动）|
| t13 | `_FaceDecalMap` | s1 | 脸贴花（腮红等）|
| t14 | `_ShadowLutTex` | s0, 1024×32 2D-LUT | 暗部颜色 LUT（= skincolor_lut）|
| t15–t17 | `_CharacterRain*Tex` | s1 | 全局雨水效果 |
| t18 | `_BaseMap` | s1 | 基色（**.a 是遮蔽/阴影遮罩，不是透明度**）|
| t19 | `_BumpMap` | s1 | 法线（x = R·A，y = G，DXT5nm 式）|
| t20 | `_SDFLightmap` | s0 | 脸 SDF 阈值图 |
| t21 | `_SDFMask` | s0 | 脸区域/菲涅尔/AO 遮罩 |
| t22 | `_DissolveTex` | s3 | 溶解 |
| t3/t4 | 点光阴影图集 / `_ScreenSpaceShadowMask` | s2 / ld | |
| t5–t10 | `_IrradianceVolumeClipmapTexture{A,B}Lod{0,1,3}` | 3D | 3 级 clipmap 探针（L1 SH）——旧文档误称"3D LUT" |
| t20(S)/t24(F) | `_IntegratedLightScattering` | 3D | 体积雾 froxel |

身体变体 S：t11 RD，t12 `_StylizedFresnelNoiseMap`，t13 雪（三平面），t14 `_BaseMap`，t15 `_BumpMap`，t16/t17 VFX 贴图，t18 `_DissolveTex`，t19 cookie。

## 1. 公共框架（Skin / Hair / Cloth / Eye 完全同构）

输入签名（S 行 5-21）：`v1.xy` uv0（VS 已乘 `_BaseMap_ST`，VS 行 `mad o1.xy, v1.xy, cb3[10].xy, cb3[10].zw`）、`v1.zw` uv1、`v2` **相机相对**世界坐标（世界 = v2 + cb0[44]，S 114）、`v3.x` 实例 id（×16 索引 cb2 的每物体数据，S 98）、`v4` 法线、`v5` 切线(w=符号)、`v6/v7` 当前/上一帧裁剪坐标（运动矢量）、`v8/v9` 物体空间法线/切线（雨效三平面用）、`v10` 物体空间位置。输出 `o0` 颜色、`o1` = 运动矢量(xy, sqrt-sqrt 编码, S 1325-1339) + 标志（o1.w 0.7/0.4，o1.z=1）——**不是延迟渲染 GBuffer**，这是前向 + per-object MV。

- 视线 `V = normalize(-v2)`（S 94-102；正交时取相机轴）。
- 相机朝向向量 `cb0[6].xyz`：用于背光判定、rim 方向、脸 SDF 视角判断。从所有用法看它是**指向相机的方向（相机 back 轴）**[推断]。
- 每物体 cb2[id+0..2] = **头骨（或角色根）旋转矩阵行**（right/up/forward），cb2[id+3].xz = 头部中心 xz（F 103-125）。

### 1.1 主光
```hlsl
L      = lerp(-cb3[0].xyz, CP11.xyz /*cb1[121]*/, CP1.w /*cb1[111].w*/);   // 场景太阳 ↔ 角色专用光方向 (S 1342-1343)
Lxz    = normalize(L.x, ~0, L.z);                                        // S 1344-1347
Lcol   = lerp(cb3[3].rgb, CP4.rgb /*cb1[114]*/, CP12.y);                 // S 1348-1349
Lint   = lerp(cb3[3].w,   CP5.w  /*cb1[115].w*/, CP12.w);                // S 1350-1352
shadow = lerp(1, ssShadow.x /*t4*/, cb5[34].x); shadow = lerp(shadow, 1, CP1.z);  // S 1354-1358
ssao   = t4.y                                                             // S 1354, 1374
```
（`CPk` = `_CharacterParams k` = cb1[110+k]。）

### 1.2 环境光（与 NPR 强相关，不是雾）
S 252-477：三级 clipmap 探针（以相机为中心 ±464/±116/±29 m 半宽）取 L1 SH，叠加全局 SH `cb1[138..140]`；
然后把环境光转成 HSV（S 427-467）、**只保留色相、饱和度上限 0.7**（`(0.7-0.35·smoothstep(|h−0.5|…))`），亮度另取 `max3(SH)`：
```hlsl
ambHue  = hsv2rgb(h, min(s, 0.7-…), 1)          // r11.xzw, S 462-467；无探针时 = cb1[113].rgb (S 475)
ambLum  = lerp(cb1[29].x,1,CP12.w)*cb1[27].x * max3(SH(dominantDir))   // r4.w, S 244-246, 468-470
ambDir  = saturate(dot(N_horiz, CP6.xyz /*cb1[116]*/) + CP7.x) * CP7.y + CP7.z   // S 1377-1379
```
移植建议：在 MC 里用 `ambHue = 天空/方块光色的归一化色相`，`ambLum = 光照等级亮度`。

## 2. Skin（身体，S）——NPR 主管线（S 1308-1581）

```hlsl
// --- 材质准备 ---
A        = tex(_BaseMap, uv + _BaseMapUVSpeed*time).rgb * _BaseColor.rgb;     // S 110-112
occ      = tex(_BaseMap).a;                       // r5.w：贴图 Alpha = 烘焙遮蔽/阴影遮罩
N        = TBN(normalmap.xy * _BumpScale) ;  背面: N *= (frontFacing ? 1 : 2*_BackFaceNormalFlip-1)   // S 121-129,186-196,221-224
// 风格化菲涅尔叠加（_StylizedFresnel*，默认关）S 130-216；ShadowColor 基色：
Sbase    = saturation(A * _ShadowColorBrightness, _ShadowColorSaturation);   // r12, S 217-220
// 皮肤"边缘变色"（_SkinRimOff/_SDFRimColor）S 478-485：
edge     = 1 - (saturate(dot(N,V))*0.85 + 0.15);
A       *= lerp(1, _SDFRimColor.rgb, saturate(edge * _SkinRimOffScale));
metal    = _Metallic; oneMinusR = 0.96*(1-metal);                              // S 1317
diff     = A * oneMinusR;                                                     // r19
F0       = lerp(0.04*_Specular, A, metal);                                    // r22, S 1319-1321
S        = Sbase * oneMinusR * CP0.z;                                         // r29.xzw, S 1322,1359
rough    = 1-_Smoothness;  a = max(rough*rough, 0.0078125);                  // S 113,1323-1324

// --- 漫反射 ramp ---
NdotL    = clamp(dot(N,L) + CP11.w*CP12.x, -1, 1);                            // S 1362-1365
ramp     = tex(_DiffRampMap, float2(NdotL*0.5+0.5, 0.5));                     // S 1366-1368  (s0 clamp)
rampSat  = max3(ramp.rgb) - min3(ramp.rgb);                                   // S 1369-1373
aoMask   = occ * ssao;                                                        // r14.w, S 1374
litMask  = min(ramp.a, min(occ, ssao));                                       // r16.w, S 1375-1376

// --- 光的颜色 ---
ambL     = lerp(ambHue, 1, litMask*CP1.y) * ambDir;                           // S 1380-1383
kShadow  = lerp(clamp(ambLum*0.35+0.65, ,1.5), clamp(ambLum,1.25,1.75), CP1.x);   // S 1384-1388
shadowAmb= ambL * kShadow * CP0.w;                                            // r32, S 1389-1390
LcDesat  = lerp(lum(Lcol*Lint), Lcol*Lint, litMask);                          // S 1391-1393
lightCol = lerp(shadowAmb,
               (ambL*clamp(ambLum,0,1.5)*(Lcol*CP12.y + 1-CP12.y) + LcDesat) * CP0.y,
               shadow);                                                       // r11.xzw, S 1394-1399

// --- 选"亮/暗/深暗"基色，再乘 ramp 颜色 ---
deep     = sat12(S*0.65);                       // lerp(lum,c,1.2) 饱和度 ×1.2, S 1400-1402
base     = lerp(deep, S, saturate(aoMask + ramp.a));                          // S 1403-1405
base     = lerp(base, diff, litMask);                                         // S 1406-1407
ramped   = base * lerp(1, ramp.rgb, rampSat);                                 // S 1408-1410（只用 ramp 的"色度"）
aoAlb    = S + aoMask*(sat12(diff) - S);                                      // S 1411-1414
ramped  *= clamp(lum(base)/max(lum(ramped),1e-3), 0, 1.5);                    // 保亮度, S 1415-1422
albedoF  = lerp(aoAlb, ramped, shadow);                                       // S 1423
Diffuse  = lightCol * albedoF;                                                // S 1424

// --- 高光（风格化 GGX，视角主导的"半角"）---
lit      = lerp(aoMask, litMask, shadow);                                     // S 1425-1426
specLight= lightCol * lerp(CP0.z,1,lit) * (lit*0.5+0.5);                      // S 1427-1431
Hs       = normalize(L*shadow + 2*normalize(float3(cam.x, lerp(0.5,L.y,shadow), cam.z)) + V*(2+shadow)); // S 1432-1445
NoH=dot(N,Hs); a2=a*a; D=a2/((NoH*NoH*(a2-1)+1)^2);  Vis=0.5/(2*saturate(dot(N,V))+a+1e-4);
Spec     = clamp(D*Vis-6e-5, 0, 20) * F0 * specLight * CP13.w;                 // S 1446-1462
color    = Diffuse + Spec;                                                    // S 1491-1494（无雨时）
// 高亮区饱和度提升: k=clamp(lum-0.5,0,0.5); color = lerp(lum, color, 1+k*k)   S 1495-1522
```

### 2.1 Rim 与"暗边背光"（全局角色参数驱动）S 1499-1580
```hlsl
rimDir  = normalize(cross(lerp((CP9.y,0,CP9.x), camRight*CP9.x+camUp*CP9.y, CP15.w), cam));  // S 1499-1515
edgeT   = 1-|dot(V,N)|;
rimMask = smoothstep(0.8-0.6*CP9.w, 0.9-0.4*CP9.w, edgeT);                   // S 1517,1523-1530
rimCol  = rimMask * CP8.rgb*CP8.a * min(occ, ssao, saturate(dot(dirFromObjCenterXZ, rimDir)+1)); // S 1531-1537
rim     = rimCol * saturate(dot(rimDir,N)) * lerp(0.25, diff, CP9.z);         // S 1538-1541
// 背光/暗色描边补光：
wrap    = 0.5 + x - 0.5x², x = dot(Lxz, N);
bl      = normalize-ambient-or-light(lerp(ambNorm, Lcol*Lint, shadow))
        * saturate(lerp(dot(SHdir,N), wrap, shadow))
        * (1-CP12.x) * lerp(1, saturate(-dot(Lxz, cam.xz)), shadow)           // 光从角色背后来
        * smoothstep(saturate((0.4-|NoV|)*5))                                // 只在轮廓
        * min(occ, ssao)
        * lerp(1, smoothstep(saturate((lum(diff)-0.1)*-16.67)), shadow)      // 亮部只给近黑材质
        * max(diff, 0.15);                                                   // S 1542-1579
color  += rim + bl;                                                            // S 1580-1581
```
### 2.2 之后
多光源（tile/cluster，`t0` 位掩码 + cb3 灯表 + cb4 分块常量，S 1582-2132）：每盏灯 `cb3[..].w` 是**NPR 灯类型**：0 = 半兰伯特 `saturate(NdotL+0.5)` 包裹（带 `cb3.y` 饱和度压缩）、1 = NdotL+偏移、2 = 只高光（按粗糙度门限）、3 = **rim 型灯**（用 `1-|NoV|` 的 smoothstep 和相机叉积方向，S 2028-2051）、4 = 区域染色（lerp 到灯色，S 1844-1856）；点光阴影 PCF 9 tap（t3）、cookie（t19）。
材质级 ColorAdjustment（仅 `cb6[3].x>0.5` 时；含 `_ColorAdjustmentRim*` 的材质 rim，S 2133-2155）——**Pelica 材质默认不开**。
`o0.rgb = color * cb1[27].y`（曝光，S 2156），`o0.a = (_SurfaceType==1)? alpha : 1`（S 2157-2158），然后进入 §7 的雾。

## 3. Face（F）——SDF 脸部阴影

与 S 相同的骨架，额外：
1. **ShadowLut**（F 184-200）：`c = sRGBencode(albedo)`；`slice=floor(c.b*31)`；`u = slice/32 + c.r*31/1024 + 0.5/1024`；`v = c.g*31/32 + 0.5/32`；两次采样相邻 slice 按 `frac(c.b*31)` 插值；**读 PNG 时 V 要翻转**（`uv.y = 1-uv.y`：导出 PNG 自上而下，Unity GPU 行自下而上；不翻转时肤色查到品红格子） → 作为 **暗部基色**（替代 S 的 `_ShadowColorBrightness/Saturation` 计算；之后 ×oneMinusR，F 683）。即 `femaleskincolor*_lut_D.png`（1024×32）。
2. **_SDFMask**（t21，F 201）：`.x` 乘到边缘变色强度（F 476）；`.y` = **非脸区域权重**（1 = 用普通法线/NdotL，0 = 用 SDF，F 247, 754, 806）；`.z` 选择 `_FaceRimOffScale`/`_SkinRimOffScale`（F 480-481）并控制 AO 抑制（F 815-823）。
3. **SDF 阴影**（F 715-809）：
```hlsl
// 头骨轴: R=cb2[id+0], U=cb2[id+1], Fw=cb2[id+2]
f      = normalize(float2(dot(L,R), dot(L,Fw)));          // 光在脸平面(右,前)上的方向  F 715-720
flip   = f.x > 0;                                          // F 721-722
sdf    = tex(_SDFLightmap, float2(flip ? uv.x : 1-uv.x, uv.y));   // 光从另一侧时镜像U  F 723-727
s      = sdf.r + sdf.g;                                    // F 728（阈值值 = (R+G)/2）
// 面部"伪法线"（来自 sdf.b，用于 rim/背光）F 729-757
back   = saturate(-dot(Lxz, normalize(cam.xz))) * saturate(-f.y) * (1-CP12.x);  // F 760-772
a      = f.y + back*(0.5 - 0.5*f.y*f.y);                   // 背光时把阈值往"亮"推  F 758-759,773-774
a      = clamp(a + CP16.w*CP1.w, -1, 1);                   // F 775-777
t      = clamp(0.5-0.5*a, 0.001, 0.999);                   // 阈值：正面光 t→0（全亮），侧光 t=0.5   F 779-781
lo=max(2t-1,0); hi=min(2t,1);
k      = smoothstep01(saturate((s*0.5 - lo)/(hi-lo)));      // F 786-796
faceNL = 2*|k + (a>0 ? a*0.5 : 0)| - 1;                    // F 797-800
NdotL  = lerp(faceNL, clamp(dot(N,L)+bias,-1,1), mask.y);  // F 801-806
ramp   = tex(_DiffRampMap, float2(NdotL*0.5+0.5, 0.5));    // F 807-809 —— 与身体共用同一 ramp
```
4. 脸部 AO：正面看脸时屏幕 AO 被抑制（`smoothstep(saturate((dot(cam,Fw)/|..xz| -0.75)*-2))*mask.z`，F 815-823）。
5. `_HighlightMap`（t12）：`uv + (dot(V,R), dot(V,U))*_HighlightMapVector.xy`，结果 × 环境光加到颜色（F 903-906, 938）。
6. **SDF 面部 rim**（`_CharacterParams14` = cb1[124]）：同样的 lo/hi 窗口，阈值来自 `CP15.z`，只在脸区域（1-mask.y），乘 diffuse 色（F 1001-1024）；普通 rim 在脸上被 `1-CP16.z` 抑制。
7. 头部中心球面法线 `normalize(worldPos.xz - headCenter.xz)` 用于脸的水平环境项（F 130-134, 246-250）。

## 4. Hair（H / H2）

漫反射与 Skin 相同（H 1626-1717），差别：
- 背光包裹（H 1630-1646）：`back = saturate(-dot(Lxz,cam.xz)) * smoothstep(saturate(2*(0.75-|cam.y|))) * (1-CP12.x)`；`a = NdotL + back*(0.5-0.5*NdotL²)` 再查 RD。
- "遮蔽"项不是 baseAlpha·ssao，而是 **角色自阴影**（`_CharacterShadowmapTex`，cb5[188/203/218+] 级联，H 1288-1621）× `RD.a(0.5+0.5·dot(N,cam))`（H 1657-1663）。
- 法线：`t19.xy` = 普通法线，`t19.zw` = `_SplitNormalMap`（×`_SpecBumpScale`）专供高光（H 1208-1220）。
- 发丝方向 `D = normalize(headUp + _AnisotropyDirX*headRight)`（H 1224-1232），`T = N×(N×D)`，可按 `cb6[0].z` 混入几何切线（H 1233-1241）。**[推断：符号无关紧要，只用 (T·H)²；H2 的 RS 变体用 `T·H>0` 作半球门限]**
- 高光半角（视角锁定的"天使环"）：`W = R*dot(V,R) + Fw*dot(V,Fw) + U*lerp(0.5, L.y, shadow)`；`Hh = normalize(normalize(2W + shadow*L) + V)`（H 1718-1739）。
- 主高光：`T1 = normalize(T + Nspec*(2*_AnisotropyValue-1))`；`s1 = pow(sqrt(1-(T1·Hh)²), 200) * pow(saturate(dot(Nxz_head, Vxz_head)), _AnisotropyEdgeFade)`；
  `Spec = specLight * s1 * 0.04*_Specular * _AnisotropyIntensity * 5 * CP13.w`（H 1727-1747, 1780-1785）。
  RS 变体 H2：`u = saturate(glossMask * pow(...,200))`，`v = facing² * (T1·Hh>0)`，`Spec *= tex(_SpecRampMap(2D 256×256), (u,v)).rgb`（H2 1682-1696；点光同理 2349-2372）。
- **LineMap 发丝线**（乘到漫反射上，不是加法）：`stripe = ceil(max(frac(uv.x*_LineAmount)-0.5,0))`；`m = lerp(stripe, 1-tex(_LineMap, uv*ST).r - stripe, _UseLineMap)`；
  `band = pow(sqrt(1-(T2·Hh)²), round(200*(1-_LineRange)))`，`T2` 用 `_LineValue` 偏移；`mul = 1 + _Specular*(lerp(1-_LineIntensity,1,m … )-1)*band`；`Diffuse = lerp(lum, Diffuse*mul, …_LineSaturation)`（H 1750-1792）。
- 头发没有 SDF、没有 ShadowLut；`_HairBrowMask` 用于"刘海下透出眉毛"的模板/深度处理（PS 中未见采样）[推断]。

## 5. Cloth = `HGRP/CharacterNPR`（C / C2）

- 贴图：`_BaseMap`、`_MetallicGlossMap`（`.r`=金属度，`.g`=镜面强度（×0.04 进 F0），`.b`=遮蔽（替代 Skin 的 baseAlpha），`.a`=光滑度，C 135, 1624-1625, 1695 附近）、`_BumpMap`、`_DiffRampMap`（同 Skin 的 RD 公式与"暗/深暗/亮"三基色选择，C 1595-1674）、`_SpecRampMap`、`_EmissionMap`、可选 ShadowLut。
- **UV**：`uv0 = v1.xy*_BaseMap_ST`，无 frac/floor/纹理数组；采样器 s1 = **linear repeat**。u∈[1,2] 的镜像 UV 壳就是**同一张图重复采样**（Pelica cloth_01/02/04 的 `u-=1` 折叠完全等价）。（对比：Eye 着色器显式 `fract(v1.xy)`，E 137。）
- 高光（C2 1681-1716）：同一套 GGX `D`，`u = lerp(D*a², NoV², _SpecRampIridescentMode)`，`v = roughness*(1-wet)`；`F0ramp = F0 * tex(_SpecRampMap, (u,v)).rgb`；`Spec = clamp(D*Vis)*F0ramp*specLight*CP13.w`。即 RS 的 **U = 归一化高光形状（峰值=1），V = 粗糙度**。256×1 的 RS 等价于 V 不变。
- 额外分支（材质默认关）：ClearCoat（C 1709-1742，`t16(NoV²)` 染色 + Schlick），各向异性/丝袜/毛发/侵蚀/Puppet。

## 6. Eye（E）与 OverlayShadow（O）——状态

**Eye（已反编译、已读核心）**：
- `p = fract(uv)-0.5`；`inside = dot(p,p)<0.25`；视差 `uv -= Vtangent.xy*_ParallaxScale*(1,0.25)*smoothstep(...)`（E 137-184）。
- 球面"眼球法线" `n = (fract(uv)*2-1)*(-_MatcapNormalScale)`（E 189-213）。
- 主光：与 Skin 相同的 RD ramp / 暗部基色（`_ShadowColorBrightness/Saturation`）/ lightCol 管线（E 682-833）。
- Matcap：`mc = tex(_MatcapTex, viewNormal.xy*0.5+0.5)`；`(mc.a*_MatcapColor.rgb + mc.rgb*_MatcapColor.a) * outsideMask * lightCol`（E 834-872）。
- `_EyeScatteringColor × base.a`、`_EyeHighLightColor × 内圈`，再按 `CP13.xyz` 混合加到自发光（E 722-733, 952-961）。
**OverlayShadow（已反编译）**：独立的透明网格（刘海/帽檐投影卡片）：`tex=_BaseMap`（`_UseGrayAsAlpha` 时 a=r），`o0 = (lerp(1, tint, a), a)`，**乘法混合**；可选场景深度裁剪（`_SceneDepthClipThreshold`）、被点光照亮时减弱、雾中衰减（O 70-93, 375-643）。Pelica 原始模型里有 `eyeshadow_01` / `hairshadow_01` 两块网格，就是这种卡片（其材质与贴图未随包提供）→ **需要**；mod 的 `npr_overlay` pass 实现它（specular-atlas 蓝 200 识别，乘法混合，随光左右滑动）。
**ShadowReceiver / ProxyLod**：地面接收角色阴影/胶囊 AO 与远景代理，非角色本体着色。

## 7. 雾与大气（与光照严格分开）—— S 2159-2338

只有 `cb1[122].w < 0.5` 才做（角色展示模式关闭雾）。
- 指数高度雾，两层：`density_i * exp2(-(camY-h_i)*falloff_i)`（cb1[77]、cb1[80]），沿视线积分 `(1-2^-x)/x`（小 x 用泰勒 `0.693147-0.240227x`），S 2196-2226。
- 起止距离/最大不透明度（cb1[78]、cb1[79].w），方向性内散射 `pow(saturate(dot(V,cb1[81].xyz)), cb1[82].w)*cb1[82].rgb`（S 2251-2262），体积雾 froxel `t20`（S 2236-2266）。
- 大气：Rayleigh `(1+μ²)·3/(16π)`（0.059683）·cb1[74] + Mie HG `(1-g²)/(4π(1+g²-2gμ)^1.5)`·cb1[75] + cb1[76]，×255 再 ×(1-透射)（S 2320-2333）。
- `o0.rgb = color*transmittance + inscatter`（S 2335）。

## 8. 对旧文档（`06-文档/终末地Skin光照模型-反汇编还原.md`、`Skin着色器-结构提取.md`）的更正

| 旧说法 | 实际 |
|---|---|
| "光照主公式（行 2255-2310）"：半球环境 + Blinn-Phong + `lerp(shadowTint, albedo, shading)` | 那段是 **§7 的高度雾 + 大气散射**；`0.059683` 是 Rayleigh 相位 3/(16π)，`12.566371` 是 HG 相位里的 4π，`×255` 是雾颜色编码。真正的 NPR 在 S 1308-1581 |
| t5–t10 = "三层 3D LUT" | 是 **irradiance volume clipmap（L1 SH 探针）**，1/3、2/3 偏移是三张 SH 系数打包在一张 3D 纹理里（`_IrradianceVolumeClipmapTexture*`）|
| t17 = ramp，t11 = 自发光，t19 = AO，t20 = 描边/MatCap，t16 高光/金属度 | S 中 **t11 = `_DiffRampMap`**，t17/t16 = VFX 贴图，t19 = 灯光 cookie，t20 = 体积雾 3D，t18 = 溶解 |
| t13 = 三平面基色 | t13 = `_CharacterSnowEffectTex` 三平面雪 |
| `cb1[136]` = LUT 尺寸 | 探针 clipmap 纹理尺寸倒数 |
| `cb1[77]/cb1[80]` = 两个光源衰减 | 两层高度雾密度/高度衰减 |
| "o1 说明是延迟渲染" | o1 = 运动矢量 + 标志，前向渲染 |
| cb1 = 材质参数（45 个槽） | cb1 = `ShaderVariablesGlobal`；材质参数在 **cb6 = UnityPerMaterial** |
| 33 次采样都是材质贴图 | 大半是全局资源（探针、阴影、雨、雪、雾、cookie）|
| SDF/ShadowLut "★★☆，_SDFLightmap 混合" | SDF 是阈值图 + 左右镜像 + 背光修正 + 伪法线（§3）；ShadowLut 是 1024×32 的暗部色 LUT（§3.1）|

## 9. 07-贴图素材 与着色器期望的对照

| 期望 | 包里情况 |
|---|---|
| `_DiffRampMap` 256×1 RGBA（.a = 亮部遮罩）| ✓ `*_RD.png` 256×1 RGBA。**Pelica 用哪张由 pathID 决定（body/face 5848563174712869001、cloth 2595675294308502756/cloth_04 7616793060396148846、hair -6227453272663763460、brow/iris 9015086203897338725），包内无 pathID→文件名映射**，只能按名字猜 body_01/face_01/cloth_0x/hair_0x |
| `_SpecRampMap` cloth 4523718382697154697、hair 8975600794099224062 | `*_RS.png` 有 256×256 与 256×1 两种，与 §4/§5 的 (u,v) 一致；同样无映射 |
| `_ShadowLutTex` 4951544993205285082（body/face/brow）| ✓ `*skincolor*_lut_D.png` 1024×32（32 片×32），female01/02/03 之一 |
| `_BaseMap` | ✓；**body_01_d 的 Alpha 均值 0.48、最小 0.22 = 遮蔽遮罩**，不可当透明度；iris_01_d 的 alpha 是眼白/虹膜遮罩 |
| `_BumpMap` | 07 里**缺**；`pelica-full/01-模型/textures/.../t_actor_pelica_{body,face,cloth_01,cloth_02}_n.tga.png` 有（RG 存法线，B=0）|
| `_MetallicGlossMap` | 07 缺；pelica-full 有 `*_p.tga.png`（cloth：G≈1、B≈0.56 遮蔽、A≈0.42 光滑度；hair：G = 稀疏高光遮罩）|
| `_EmissionMap` | pelica-full `cloth_0{1,2}_e` |
| `_LineMap` / `_OutlineMask` / `_HairBrowMask` / `_SplitNormalMap` | pelica-full `hair_01_st`(512,疑为 LineMap)、`cloth_01_st`(疑为 OutlineMask)、`hair_01_sw_m`(疑为 HairBrowMask)、`hair_01_hn`(SplitNormal) [按尺寸/名字推断] |
| **`_SDFLightmap` (-8871778439397823920)、`_SDFMask` (5056732087567382100)、`_HighlightMap`、`_EmotionMap`、`_MatcapTex`(iris)** | **两个包里都没有**。脸 SDF 必须自制（或退化为 NdotL）|

## 10. 移植到 Minecraft 的映射（见 `endfield_npr.glsl`）

- 雾、探针、屏幕阴影、雨雪、溶解、多光源 tile 循环全部丢弃；`shadow` 由光影包阴影图或 1 提供；`ssao`=1。
- `ambHue/ambLum` 用天空颜色 + 光照贴图亮度近似；`CPk` 给出可调默认值（真实游戏值未知）。
- 头骨矩阵：YSM 侧需要把头骨的 right/up/forward 与头中心作为 uniform 传入（脸 SDF、头发高光必需）。

## 11. 最大的不确定性

1. `_CharacterParams0..16`（cb1[110..126]）的**实际数值**：材质里没有，决定暗部亮度、rim 宽度/颜色、高光倍率等整体观感。只能按截图调。
2. `cb0[6]` 被判为"指向相机方向"——由背光/rim/脸 AO 三处用法一致推出，未有名字佐证。
3. SDF 图 R+G 两通道相加的含义（两张阈值图？上下半脸？），B 通道伪法线；Pelica 的 SDF 图缺失，形状要自制。
4. pathID→RD/RS/LUT 文件名映射缺失，ramp 选择靠名字推测。
5. ShadowLut 贴图的 sRGB 标志未知（着色器先对 albedo 做 sRGB 编码再查表，输出直接当线性用）。
6. Hair 的 Line 效果中 `_LineIntensity/_LineSaturation` 的精确组合只核对到指令级，语义是推断。

## 12. 运行时实现：独立渲染 mod（`~/ysm/npr/mod`）

按用户要求做成**依赖 Oculus 的独立 Forge mod**（`endfield_npr`），不改光影包：
- 挂 YSM 的 `RenderModelEvent`（YSM 自己的 mod 事件总线），对关卡内不透明的人形导入网格，用 `NativeRenderer.render/renderOutline` 把蒙皮后的顶点录进自定义 `VertexConsumer`（YSM fallback 路径，视空间），然后取消事件——光影包的 gbuffer 程序和 YSM toon 都不再画本体；**阴影 pass 不拦截**，角色仍向世界投影。
- Mixin 在 `GameRenderer#renderLevel` 中 `LevelRenderer#renderLevel` 返回之后（Oculus 的 composite/final 已完成、手部 pass 尚未清深度）用自己的 core shader（`npr_character`/`npr_outline`）画，LEQUAL 深度测试遮挡。
- 材质类别取自 YSM 的 specular atlas 蓝通道（5 眼、10 布、115-135 发、150 皮、160 脸）；参数默认值 = Pelica 的 Unity 材质值；ramp/LUT/SDF 从 `config/endfield_npr/` 读取（不打包游戏资源），缺省用测得曲线的程序化近似。
- MC 适配（游戏里没有的）：光照贴图当环境光、天空可见度代替阴影图、太阳/月亮方向取自世界时间、亮部环境光缩小（无 HDR tonemap）。

附：`07-贴图素材` 的 RD PNG 在 alpha→0 处 rgb 也→0（像预乘/导出伪影）；着色器只用 ramp 的"色度"（max−min）加权，因此 rgb 被压黑的区域不会引入额外染色，但真实贴图 rgb 是否被导出工具改动无法确认。
