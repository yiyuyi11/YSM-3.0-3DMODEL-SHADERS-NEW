# UE5 参考 vs 我们：Hair / Cloth / 共享光照模块 / Outline 对照（仅分析）

范围：头发（Kajiya 高光、Line、头发 ramp、发→脸阴影）、通用 CharacterNPR 衣服（漫反射 ramp、高光 ramp、金属/光泽图通道、自发光）、共享光照（ramp 查表与 lit-mask、暗/深暗/亮基色、高光模型与视向偏置、rim、暗轮廓背光、亮区饱和提升、局部灯）、描边（宽度/透视补偿、颜色、OutlineMask、深度偏移）、ramp 贴图的选择。
皮肤/脸/眼/眉/OverlayShadow 由 `compare-ue5-skinface.md` 覆盖，本文只在共享项上交叉引用，不重复。

记号：**R** = `~/ysm/ue5ref/`；**fsh** = `mod/.../shaders/core/npr_character.fsh`；**ofsh/ovsh** = `npr_outline.fsh/.vsh`；**H** = `pelica/03-渲染管线/Hair_ps_完整反汇编.asm`（无 `_MetallicGlossMap` 关键字的变体）；**H2** = `全部反汇编/HGRP_CharacterNPR_Hair_lod0_p0_s1_15471456.asm`（带 MetallicGloss + 2D SpecRamp 的变体）；**C2** = `npr/decompiled/Gens2_9372380.glsl`（CharacterNPR 带 SpecRamp）；**S** = Skin asm。

## 0. 先说结论

1. **我们的头发高光永远是 0。** fsh:225 用 `0.04*specLevel`，而 `hair` 配置的 specLevel = Pelica 的 `_Specular=0`。字节码 H2:1200 用的是 `0.04*_MetallicGlossMap.g`，Pelica 的头发材质 `_UseMetallicGlossMap=1`；H:1260 的 `0.04*cb6[0].y` 只属于“没有贴图”的变体，Pelica 不用这个变体。参考 `Stages/Hair.ush:253`、`PunctualLighting.ush:793` 同样用 `prop.g`。
2. 头发 RS 应该是 2D 查表：`u = sat(pow(sinTH,200)*G)`，`v = vh²*(T·H>0)`，最后再乘一次 `vh`（H2:1689-1696，参考 `Hair.ush:207-211,249`）。我们把 vh 乘进了 u，v 又固定在 0.5（fsh:223-224，NprTextures:190）。
3. 衣服漫反射缺 **view ramp**：`receiver*viewAlpha`，其中 `viewAlpha=RD(N·cam*.5+.5).a`（C2:1568-1573，参考 `Cloth.ush:405-407`）。我们只给头发做了这一项（fsh:180-182）。
4. 头发和衣服的背光 wrap 缺少**相机俯仰门控** `smoothstep(.25,.75,1-|cam·up|)`（H:1636-1642、C2:1546-1553），而且水平投影用的是视空间而不是世界空间（fsh:156-161）。头发高光的竖直分量也错用了视空间 `L.y`：字节码 H:1718 用的是世界 `L.y`（fsh:217）。
5. 衣服高光 ramp：字节码乘的是 **RGB 颜色** `F0*RS.rgb`（C2:1695-1697），我们用的是亮度（fsh:236，有意为之）。另外 RS 的 V 轴没做 PNG 翻转（NprTextures:225）。
6. 描边：我们直接用 YSM 自己的 inverted hull，宽度不可控，颜色也只是“底色 × 亮度近似”（ofsh:25-30）。参考的做法（`EndfieldCharacterPass.usf:39-73`）是：裁剪空间按屏幕方向外扩，带透视补偿；深度往后推 `_OutlineOffsetZ*0.1 m`；颜色先按每个材质的 `_OutlineColorBrightness/Saturation` 分级，再走一遍与本体相同的 ramp 光照（`EndfieldOutlineMath.ush:66-166`）。
7. 参考包里**没有** pathID → RD/RS 文件名的映射（Content/ 已剔除）。ramp 只能继续靠名字猜。但能从材质推出：cloth_04 的 `_UseDiffRampMap=0`，所以 cloth_04 的 RD（7616793060396148846）**根本不参与计算**。

---

## 1. Hair

### 1.1 Kajiya-Kay 主高光：F0 / 强度来源
1. 参考：`R/Shaders/Stages/Hair.ush:253`：`spec = rawSpec*(0.04*prop.g)*AnisotropyIntensity*5 + secondary…`；局部灯 `PunctualLighting.ush:793` `local.F0=.04*prop.g`。prop = `_MetallicGlossMap`（`Inputs/EndfieldTextureDecode.ush:15-18`；没有贴图时参考用默认值 (0.75,0.5,1,0.7)，这组默认值是参考自己定的）。
2. 我们：fsh:225 `spec = specLight*ring*(0.04*specLevel)*HairAniso.y*5*Globals2.x`，`specLevel=MatHair.y=0`（NprConfig:47）。
3. 判定：**我们错，字节码可以定案**。H2:150 采样 t20 = MetallicGloss；H2:1200 `mul r4.z, r10.y, l(0.04)`；H2:1689 `mul_sat r28.x, r10.y, r2.z`（G 同时缩放 u）。H:1260 `mul r7.y, l(0.04), cb6[0].y` 属于无贴图变体：cb6[0].y=_Specular 在这里顶替 prop.g（H:1779 的 line 混合也用它，见 §1.5）。Pelica 头发 `_UseMetallicGlossMap=1`、`_UseSpecRampMap=1`，走的是 H2。
   Pelica 的 `t_actor_pelica_hair_01_p`（2048²）实测：R≡0（无切线混合）、B≡1（无遮蔽）、A≡1（二级高光权重 1）；G 是稀疏的高光带遮罩，约 9% 的像素非零，非零处均值 0.39、中位 0.31、p90 0.88。
4. 修改：YSM 的 UV 取不到这张图，只能用常数。在 NprConfig 加 `d("hairGloss","0.4","_MetallicGlossMap.g stand-in (Pelica hair_01_p nonzero mean 0.39)")`，通过 `HairAniso2.x` 之类的 uniform 传入，fsh 中用 `gloss` 替换 `specLevel`（完整代码见 §1.2）。[推断] 常数会让高光出现在所有发丝上，而不只是原图的高光带；可以考虑在 YSM specular atlas 的红通道里手绘 G 遮罩，如果作者烘了的话。
5. 影响：**高**（目前头发完全没有高光环）。贴图 Pelica 有（`01-模型/textures/.../t_actor_pelica_hair_01_p.tga.png`），但 YSM 侧无法使用。

### 1.2 发丝切线、半角向量、2D RS、二级高光
1. 参考 `Hair.ush:193-211, 249-254`：
   - 轴 `objectAxis = normalize(objRight*_AnisotropyDirX + objUp)`
   - `strand = lerp(cross(N,axis), T, prop.r)`，`hairBase = cross(N,strand)*lerp(1,sign,prop.r)`；当 prop.r=0 时为 `-(axis 的切平面分量)`
   - `hairBLut = normalize(hairBase + N*(2*_AnisotropyValue-1))`，`secondaryAxis` 同理用 `_AnisotropyValue2`
   - `biasedV = objR*V.x + objUp*lerp(.5, L·worldUp, X) + objF*V.z`，`H = normalize(normalize(L*X + 2*biasedV) + V)`
   - `lutU = sat(pow(sinTH,200)*G)`，`vh = pow(sat(dot(V.xz,N.xz)),EdgeFade)`（对象空间），`lutV = vh²*(T·H>0)`
   - `rawSpec = RS(u,1-v).rgb*u*vh`
   - `secondary = pow(sinTH2, floor(200*max(1-_AnisotropyRange2,0)))*vh * _AnisotropyColor2 * prop.a * (1-peak)`
2. 我们 fsh:214-225：`T = normalize(D - N(N·D))`（符号与参考相反）；`T1 = T + N*(2v-1)`；`W` 用 `HeadUp*mix(.5, L.y, shadow)`，其中 L.y 是**视空间**；`s1 = strand*facing`，把 vh 乘进了 u；`ring = rampRow(6, s1)*s1`（1D，固定 v=0.5）；没有二级高光。
3. 判定：**我们错**（字节码佐证如下）。
   - H:1718 `add r4.w, r28.y, -0.5` 中 r28 是世界空间 L（参考注释 “Light Y is WORLD Unity-up”）。我们用视空间 L.y，相机俯仰时高光带会漂移。
   - H2:1689-1696：u 不含 vh；`lt r2.x, 0, r2.x` 是 `T·H>0` 门限；v = vh²·门限；`mul r28.xyz, r3.wwww, r28` 最后再乘一次 vh。
   - 切线符号：只用 `(T·H)²` 时无所谓，但 2D RS 的 `T·H>0` 门限会因符号相反而取反。另外 `T + N*(2v-1)` 只在 `_AnisotropyValue=0.5` 时与参考等价。Pelica 恰好是 0.5，所以目前无害。
   - 二级高光：H2:1700-1704（`cb6[13].y` 即 `_AnisotropyValue2` 构造第二个轴）证明字节码中存在，我们缺失。
   - 坐标帧：参考用对象（角色根）帧（`EndfieldNativeGetObjectFrame`），我们用头部帧（含俯仰，CharacterCapture:276-287）。[推断] 原作的头发 mesh 挂在根对象下，因此低头时高光应当相对头发移动。
4. 修改：
   - Java（CharacterCapture）：增加 `worldUp = worldToView.transform(0,1,0)`，以及只用 `yBodyRot` 的 `bodyRight/bodyForward`，作为 uniform `WorldUpView`、`BodyRight`、`BodyForward` 传入。
   - Java（NprTextures）：新增独立的 2D `Sampler7 = hair_RS.png`（原图尺寸，CLAMP，线性），不再塞进 ramp atlas 的第 6 行。
   - NprConfig：新增 `hairAniso2 = "0.5,-0.02,0.2264,0.2233,0.1141"`（_AnisotropyValue2、_AnisotropyRange2、_AnisotropyColor2）和 `hairGloss = 0.4`。
   - fsh 替换 214-225：
   ```glsl
   } else if (cls == HAIR) {
       vec3 U = WorldUpView;
       vec3 axis = normalize(BodyRight * HairAniso.w + U);
       vec3 hairBase = cross(N, cross(N, axis));                     // prop.r = 0 (Pelica _p.R = 0)
       vec3 T1 = normalize(hairBase + N * (2.0 * HairAniso.x - 1.0));
       vec3 T2 = normalize(hairBase + N * (2.0 * HairAniso2.x - 1.0));
       vec3 oV = vec3(dot(V, BodyRight), dot(V, U), dot(V, BodyForward));
       vec3 bV = BodyRight * oV.x + U * mix(0.5, dot(L, U), shadow) + BodyForward * oV.z;
       vec3 Hh = normalize(normalize(L * shadow + 2.0 * bV) + V);
       float th = dot(T1, Hh);
       float u = clamp(exp2(200.0 * log2(max(sqrt(clamp(1.0 - th * th, 0.0, 1.0)), 1e-4))) * HairGloss, 0.0, 1.0);
       vec2 nh = normalize(vec2(dot(N, BodyRight), dot(N, BodyForward)) + 1e-5);
       float vh = exp2(log2(max(clamp(dot(normalize(oV.xz + 1e-5), nh), 0.0, 1.0), 1e-5)) * HairAniso.z);
       float v = vh * vh * (th > 0.0 ? 1.0 : 0.0);
       vec3 raw = texture(Sampler7, vec2(u, 1.0 - v)).rgb * u * vh;     // PNG is top-down, Unity v is bottom-up
       float th2 = dot(T2, Hh);
       float sec = exp2(log2(max(sqrt(clamp(1.0 - th2 * th2, 0.0, 1.0)), 1e-4)) * floor(200.0 * max(1.0 - HairAniso2.y, 0.0))) * vh;
       spec = (raw * (0.04 * HairGloss) * HairAniso.y * 5.0 + sec * HairAniso2Color * (1.0 - clamp(max3(raw), 0.0, 1.0)))
            * specLight * Globals2.x;                                    // prop.a = 1 for Pelica
   }
   ```
5. 影响：**高**（与 §1.1 一起才能让头发出现高光）。Pelica 的 RS 用哪一张未知（pathID 8975600794099224062，见 §5）；当前配置目录的 hair_RS 是 hair_01_RS 256×1，此时 v 不起作用，同样可用。

### 1.3 头发漫反射 ramp、wrap 与 view ramp
1. 参考 `Hair.ush:411-422`：`wrap = lightViewFacing(世界水平 L 与世界水平相机后向) * smoothstep(.25,.75,1-|camBack·up|) * (1-RangeEnabled)`；`light = clamp(lerp(nl, -.5nl²+nl+.5, wrap)+RangeBias,-1,1)`；`rd = RD(light*.5+.5,.5)`；`viewAlpha = RD(N·camBack*.5+.5).a`。
2. 我们 fsh:155-161：wrap 形式相同，但 `Lxz=(L.x,L.z)` 取自视空间，且**没有俯仰门控**；view ramp 做了（fsh:180-182）。
3. 判定：**我们错**。H:1630-1642：`dp2 r13.x, cb0[6].xz…`（世界水平相机）；`add r14.y, 0.75, -|cb0[6].y|; add_sat ×2; smoothstep` 就是俯仰门控；最后 `mul r14.y, r34.x, r14.y`（1-RangeEnabled）。我们的重建（NPR-reconstruction §4 的 H 1630-1649）漏掉了这一门控。
4. 修改：替换 fsh:156-161（衣服共用，见 §2.2）。
   ```glsl
   vec3 U = WorldUpView;
   vec3 Lh = normalize(L - U * dot(L, U) + U * 6.1035e-5);
   vec3 Ch = cam - U * dot(cam, U);
   Ch = dot(Ch, Ch) > 1e-8 ? normalize(Ch) : vec3(0.0);
   float backLit = clamp(-dot(Lh, Ch), 0.0, 1.0);
   float pitchGate = smoothstep(0.25, 0.75, 1.0 - abs(dot(cam, U)));
   if (cls == HAIR || cls == CLOTH) {
       float nl = dot(N, L);
       NdotL = clamp(nl + backLit * pitchGate * Globals1.w * (0.5 - 0.5 * nl * nl) + Globals1.z, -1.0, 1.0);
   }
   ```
   注意：后面 FACE 分支（fsh:167）和背光（fsh:256-259）里的 `backLit`、`Lxz` 也要改用 `Lh`（背光见 §3.5）。
5. 影响：中（MC 第三人称常带 20-40° 俯角，从上往下看背光角色时，ramp 被抬亮的幅度应该减小）。

### 1.4 头发 CP8 rim（深度偏移 rim）
1. 参考 `PunctualLighting.ush:600-657` 与 `EndfieldOutlineMath.ush:172`：**不透明头发**的 CP8 rim 门控是 `smoothstep(.1,.2, sceneDepth(screenUV + normalize(N_view.xy)*(h/w,1)*rimWidth*.006) - pixelDepth)`，即屏幕空间深度差 rim；只有半透明刘海 HairT 才用 `1-|N·V|` 角度门控（`PunctualLighting.ush:636-641`）。
2. 我们 fsh:249：所有类别都用角度门控。
3. 判定：**我们近似**（参考注释引 Frame1681 Hair731 采样 `_CameraDepthTexture`；我们的 H/H2 是否含该采样未核对 [未核])。
4. 修改（可选）：需要一张只含角色的深度图。在 `NprRenderer.draw` 中先把 `c.body` 做一次 depth-only pass 写入一个 R32F/depth FBO，绑定到 `Sampler8`，然后头发分支改为：
   ```glsl
   vec2 dir = normalize(viewNormal.xy + 1e-5) * vec2(ScreenSize.y / ScreenSize.x, 1.0);
   vec2 suv = clamp(gl_FragCoord.xy / ScreenSize + dir * RimParams.w * 0.006, 1.0 / ScreenSize, 1.0 - 1.0 / ScreenSize);
   float rimMask = smoothstep(0.1, 0.2, linearDepth(texture(Sampler8, suv).r) - (-viewPos.z));
   ```
5. 影响：低-中（头发外缘 rim 更稳，不会在发束内部的掠射面上乱亮）。

### 1.5 Line（发丝条纹）
1. 参考 `Hair.ush:632-638, 657-658`：
   - `stripe = lerp(ceil(sat(frac(uv.x*_LineAmount)-.5)), 1-LineMap.r, _UseLineMap)`
   - `band = pow(sinTH(_LineValue 轴), floor(200*(1-_LineRange)))`
   - `lineFactor = lerp(1, lerp(lerp(1-_LineIntensity,1,stripe),1,peak), band)`，再 `lerp(1, lineFactor, prop.g)`
   - `diffuse *= lineFactor`；饱和度 `lerp(_LineSaturation,1,lineFactor)`
2. 我们：未实现。
3. 判定：**参考与字节码一致**（H:1749-1790：cb6[16].x=LineAmount、cb6[16].y=LineValue 轴、`round_z(200*(1-LineRange))`、cb6[17].y=UseLineMap，以及 `mad r6.x, cb6[0].y, r6.x, 1`）。我们缺失。
4. 修改：不建议现在做。Pelica 的 `_LineMap`（pathID 3012176911137855631，ST=(6,1,-0.23,0)，疑为 `hair_01_st`）依赖原始 UV，YSM atlas UV 下会错位。程序化条纹 `frac(uv.x*300)` 同样依赖 UV。且 `prop.g` 只在约 9% 的像素非零，影响范围很小。
5. 影响：低。

### 1.6 发→脸阴影 / HairBrowMask
1. 参考：没有单独的“头发投影到脸”算法。角色自阴影来自自阴影图集（`EndfieldCharacterSelfShadow.ush`，16 次 GatherRed），作为 `selfShadowY` 进入 receiver（`Hair.ush:457-463`、`Cloth.ush:37-39`）。`_HairBrowMask` 只用于眉毛透过刘海：`clip(mask.r - threshold)`（`EndfieldCharacterPass.usf:472-477`、`Hair.ush:858`）。OverlayShadow 归 skinface 报告。
2. 我们：occ=1、ssao=1（fsh:122-123），没有角色自阴影，没有 HairBrowMask。
3. 判定：**都缺**。MC 下没有角色阴影图（Oculus 的 shadow map 不对我们这个 pass 开放）。
4. 修改：暂不。后续可选方案：把 YSM 的 shadow pass 渲染结果，或者自建一张角色专用 shadow map，接到 `occ`。
5. 影响：中（刘海下的额头不会变暗），但被基础设施阻塞。

---

## 2. Cloth（HGRP/CharacterNPR 通用）

### 2.1 属性图通道（_MetallicGlossMap）
1. 参考 `Inputs/EndfieldTextureDecode.ush:8-11`：`property = lerp((_Metallic,_Specular,1,_Smoothness), texel, UseMap)`，通道为 R=金属，G=镜面强度，B=遮蔽/阴影权重，A=光滑度；`rough = 1-property.a`（`Cloth.ush:165`）；`F0 = lerp(.04*G, bright, R)`（`Cloth.ush:474`）；`receiver = B*AO`，`lit = min(AO, B, rd.a)`（`SurfaceCommon.ush:131-132`、`Cloth.ush:483,539`）。
2. 我们：metal 取自 YSM 的 green==255（fsh:95）；F0 = `0.04*_Specular(1.0)`；smoothness 用材质的 `_Smoothness` 0.327（NprConfig:48）；B 视为 1。
3. 判定：**我们近似**（被贴图阻塞）。C2:1460-1473 证实 `F0 = lerp(0.04*G, albedo, metal)`、`.96*(1-metal)`。Pelica 衣服 `_UseMetallicGlossMap=1`，实测 `cloth_01_p`：G≈0.97（≈`_Specular=1`，可接受），**A 均值 0.42**（≠ 0.327），**B 均值 0.57**（p10 0.14，p90 0.97，相当于 AO）。
4. 修改：`cloth` 默认值的 smoothness 改为 0.42（cloth_02 为 0.45）。B 无法在 YSM 侧取得；如需模拟整体压暗，可增加 `clothOcclusion`（默认 1.0），在 CLOTH 分支设 `occ = clothOcclusion`。[推断] 均值 0.57 是 UV 空间平均，作为常数会整体过暗，不建议直接用。
5. 影响：中（A 影响高光宽度；B 影响褶皱暗部）。

### 2.2 漫反射 ramp + view ramp + 俯仰门控
1. 参考 `Cloth.ush:393-407`：wrap 与头发相同，包括 `smoothstep(.25,.75,1-|camF.z|)`；`rd = lerp(smoothstep(.25,1,wrapped).xxxx, RD, _UseDiffRampMap)`；`viewAlpha = lerp(smoothstep(.25,1,N·cam), RD(N·cam*.5+.5).a, _UseDiffRampMap)`；`EFL_OfficialDiffuse(…, viewAlpha, property.b, AO, …)`。
2. 我们 fsh:176-179：衣服 `aoMask = occ*ssao`，没有 viewAlpha，没有俯仰门控，也没有 `_UseDiffRampMap=0` 分支。
3. 判定：**我们错**。C2:1568-1573：`r13.w = RD(dot(N,cb0[6])*.5+.5).a; r16.w = r21.y*r13.w`，即 `receiver*viewAlpha` 随后进入 `_6574 = fma(r13.w, r21.y, r25.w)`；俯仰门控见 C2:1546-1553。
4. 修改：fsh:180 改为对 HAIR 与 CLOTH 都做 view ramp：
   ```glsl
   if (cls == HAIR || cls == CLOTH) {
       aoMask = occ * rampRow(rdRow, dot(N, cam) * 0.5 + 0.5).a;
   }
   ```
   俯仰门控与世界水平投影见 §1.3。
   cloth_04（`_UseDiffRampMap=0`，看参数是丝袜/裤袜）应使用 `rd = vec4(smoothstep(.25,1,NdotL))`、`viewAlpha = smoothstep(.25,1,N·cam)`。但 YSM 的类别图分不出 cloth_04，需要新的 class code（例如 blue=20）才能做；先记下。
5. 影响：**中-高**（衣服边缘与背向相机的面会多一档深暗色 `Grade(S*.65,1,1.2)`，这是 Endfield 衣服“边缘发暗”的主要来源）。cloth RD 已在配置目录（cloth_05_RD）。

### 2.3 高光 ramp：形状还是颜色
1. 参考 `Cloth.ush:472-475`：
   - `u = lerp(D/min(1/(a2+1e-4),65504), NoV², _SpecRampIridescentMode)`
   - `v = rough*(1-metal)`，UE 中取 `1-v`
   - `authoredSpec = F0*lerp(1, RS.rgb, _UseSpecRampMap)`，用的是**RGB**
2. 我们 fsh:234-237：`F *= lum(RS(Dg*a2))`；NprTextures:189、225 用 `v = 1-smoothness` 直接从 PNG 顶部数行，没有翻转。
3. 判定：**我们错**（字节码 C2:1688-1697：`r22.x = lerp(D*(a2+1e-4), NoV², cb6[1].x)`，`r22.y = r3.y*(1-r3.x)`，`r23 = r14.xyz*RS.xyz` 即 RGB 相乘）。用亮度是我们为了避免白衣被染色做的偏离；参考与字节码都表明 Endfield 衣服高光**就是带颜色的**（RS 实测：cloth_02_RS 偏蓝紫，cloth_04_RS 偏橙到蓝）。V 方向：Unity 的 v 自下而上，PNG 自上而下，NativeImage 第 0 行在 t=0，所以应取 PNG 第 `(1-v)*(h-1)` 行。
4. 修改：
   - NprTextures:225：`var sy = … Math.round((1f - v) * (image.getHeight() - 1))`，只对 RS 行生效；RD 是 256×1，不受影响。
   - fsh:236：`F *= rampRow(5, clamp(Dg * (a2 + 1e-4), 0.0, 1.0)).rgb;`
   - 配置目录目前**没有 cloth_RS.png**（实际在用的是 WHITE 回退）。候选是 cloth_02/04 的 256×256 或 cloth_05 的 256×1，Pelica 用哪一张未知（pathID 4523718382697154697）。
5. 影响：中（Pelica 衣服 `_Specular=1`、rough≈0.58，高光可见；颜色错误会很明显）。

### 2.4 GGX 与视向偏置半角
- 参考 `SurfaceSpecular.ush:39-53`：GGX 与我们一致（match）。半角 fill 用**世界 up**：`fill = cam - U(cam·U) + U*lerp(.5, L·U, X)`；我们 fsh:227 用视空间。skinface §1.4 已给出修改，**同一行代码对衣服同样适用**，C2:1673-1687 也同此：`r22 = (cb0[6].x, lerp(.5,L.y,X), cb0[6].z)`。在衣服上的影响为中（衣服 `_Specular=1`，比皮肤明显）。
- 高光的光照因子 `(recv*.5+.5)*lerp(DarkScale,1,recv)`，其中 `recv = lerp(B*AO*viewAlpha, min(AO,B,rd.a), X)`（`Cloth.ush:483-484`）。我们 fsh:211-212 公式相同；做完 §2.2 后衣服的 aoMask 会自动带上 viewAlpha，形成 match。

### 2.5 自发光
1. 参考 `Cloth.ush:677`：`c += Emission.rgb * _EmissionColor * _EmissionBrightness * _UseEmission * premul`，加在 rim 之后，不受光照影响。
2. 我们：无。
3. 判定：缺失。Pelica 只有 **cloth_02** 的 `_UseEmission=1`（`_EmissionColor=(1,.72,0)`、`_EmissionBrightness=8`，贴图 `t_actor_pelica_cloth_02_e.tga.png`）；cloth_01/04 虽有贴图，但 `_UseEmission=0`。
4. 修改：只有 YSM 模型带自发光层时才有意义。[推断] YSM 的 `CustomPBRTextureSet` 可能有 emissive 层，需查 YSM API；否则在类别图中为发光区域指定一个 class，并配合 `emission` 颜色配置项。
5. 影响：低-中（局部发光条）。

### 2.6 环境反射（cubemap IBL）
参考 `Cloth.ush:594-604`（`EFL_OfficialEnvironmentBRDF` 有理多项式）。我们无，MC 也没有反射探针。不做。影响：低（Pelica 衣服非金属）。

### 2.7 主光与暗轮廓背光（衣服）
参考 Cloth.ush 中**没有** thin backlight，但字节码 C2:1866-1880 **有**：`smoothstep(.1,.04,lum)`、`max(albedo,.15)`。判定：**参考对衣服是近似**，我们 fsh:255-261 对衣服保留背光是对的。公式本身的修正见 §3.5。

---

## 3. 共享光照模块

### 3.1 ramp 查表与 lit-mask
参考 `SurfaceCommon.ush:127-140`、`Cloth.ush:403`、`Hair.ush:418`：`RD(NdotL*.5+.5, .5)`，`lit = min(AO, B, rd.a)`。我们 fsh:62-64、176-179 一致：**match**，唯一缺口是 §2.2 的 viewAlpha。

### 3.2 暗/深暗/亮基色选择
参考 `SurfaceCommon.ush:127-140 EFL_OfficialDiffuse`；我们 fsh:200-207。**match**（skinface §1.2 已逐项核对）。暗色 `dark = Grade(bright, _ShadowColorBrightness, _ShadowColorSaturation)`（`Hair.ush:274`、`Cloth.ush:168`）与我们 fsh:147-148 一致。

### 3.3 HG 主光/环境光合成
见 skinface §1.3（参考 `LightingGlobals.ush:300-314` 与 S 1377-1399 一致；我们的 `*0.35` 与 `*ambLum` 是 MC 适配）。不重复。

### 3.4 Rim（CP8/CP9）
1. 参考 `LightingGlobals.ush:323-334`：`axis = normalize(cross(cameraBackward, lerp(worldOffset, cameraOffset, RimCameraRelative)))`；`smoothstep(lerp(.8,.2,w), lerp(.9,.5,w), 1-|V·N|)`；`gate = min(sat(hemi·axis+1), ao)*sat(axis·N)`；`*lerp(.25, albedo, CP9.z)`。头发见 §1.4。
2. 我们 fsh:248-253。
3. 判定：smoothstep、gate、albedo 混合 **match**；亮度系数 `(0.35+0.65*…)` 是我们自加的（同 skinface）。
   关于叉积顺序我**不同意 skinface §1.5 的“我们反了”**：Unity 世界与 UE 世界都是左手系（UE=(UnityZ,UnityX,UnityY) 是循环置换，保持手性），而 MC/GL 视空间是右手系。左手系公式 `cross(a,b)` 在右手系中对应的几何向量是 `cross(b,a)`。因此我们的 `cross(offset, cam)` 恰好就是 `cross_LH(cam, offset)`，与参考同义。[推断] 只有当 CP9 偏移按世界轴解释（`RimCameraRelative=0`、cb1[125].w=0）时，我们用视空间才算错；改动前建议先用 `rimScreenDir=-1,…` 在游戏里目测确认。
4. 修改：不改符号；HemiNormal 应改为世界水平：`fromCenter = (viewPos - HeadCenter) - U*dot(viewPos - HeadCenter, U)`（参考 `LightingGlobals.ush:293-298` 置 `p.z=2^-14`，即只取水平分量；我们 fsh:250 用的是视空间 xz）。
5. 影响：低-中。

### 3.5 暗轮廓背光（thin backlight）
1. 参考 `EyeMath.ush:37-70`：
   - `horizontalMain = normalize(L.x, 2^-14, L.z)`（世界）
   - `wrap = -noL(noL*.5-1)+.5`
   - `cameraGate = (1-X) + X*sat(-dot(Lh.xz, camBack.xz))`
   - `viewGate = smoothstep(.6,.8,1-|N·V|)`
   - `darkGate = (1-X) + X*smoothstep(.1,.04,lum)`
   - `color = lerp(radiance/max(max3*.5,1), mainColor, X)`
   - `*max(.15, bright)*ReceiverAO`
2. 我们 fsh:255-261：`x = dot((Lxz.x,0,Lxz.y), N)` 用视空间水平；归一化放在主光一侧（skinface §1.6 已指出，并给出修正）。
3. 判定：**我们错**，有两处：(a) 归一化方向（见 skinface）；(b) 用视空间水平代替世界水平，H 字节码中 `cb0[6].xz` 与 r29.xz 是世界坐标。
4. 修改：`float x = dot(Lh, N);` 和 `backLit` 改用 §1.3 中的 `Lh/Ch`（不乘 pitchGate）。参考头发版（`HairMath.ush:19-29`、`Hair.ush:589`）的 ReceiverAO = `min(prop.b, selfShadow)`，在我们这里等于 1。
5. 影响：低-中。

### 3.6 亮区饱和提升
参考 `ClothMath.ush:22-31`、`Hair.ush:729-730`：`lerp(luma, c, 1+clamp(luma-.5,0,.5)²)`，在 rim 之前施加，作用于 (diffuse+spec)。我们 fsh:242-245：**match**（顺序也是先 boost、后 rim 与背光）。

### 3.7 局部灯（点光/聚光）
参考 `PunctualLighting.ush:138-349`：NPRType 0=Default、1=Toon、2=SpecOnly、3=RimOnly、4=Fog，按 8×float4 记录逐灯累加。我们：无，方块光只当环境光。判定：缺失，但 MC 没有对应的光源数据。[推断] 可以把附近光源方块当作 Type0 灯：`color*weight*DefaultDiffuse*lerp(back*P4.x, 1, sat(noL+.5))`，衣服 back=.25，皮肤 .5。影响：低（夜间火把旁会有更好的方向感），工作量中，先不做。

---

## 4. Outline

### 4.1 几何：宽度、透视补偿、深度偏移
1. 参考 `Plugins/.../EndfieldCharacterPass.usf:39-73`（以及 `Modules/EndfieldOutline.ush:28-66`）：
   - `dir = normalize((ViewToClip * ViewN).xy)`
   - `halfAngle = atan(1/P[1][1])`，用多项式 `(1+(-.30189+.08729y²)y²)y` 近似
   - `raw = dir*(h/w,1)*Width*(π/8/halfAngle)*clamp(depth_m*fovDeg*.04, 0, 1)*.005`
   - `min = (1/viewSize)*clamp(depth_m, 0, (π/2)/halfAngle)`
   - `shift = (|raw|<min ? min*sign : raw) * OutlineMask.r`
   - **`clip.xy += shift`**（以米计，不乘 w）
   - `DZ = _OutlineOffsetZ*OutlineMask.g*0.1 m`，`clip.z = (P22*(w+DZ)+P32)*w/(w+DZ)`，保持 w 不变
   - 法线：`_OutlineAverageNormal=1` 时用 TEXCOORD2 存的平滑法线（`Inputs/EndfieldOutlineDecode.ush:7-17`，切线空间 XY）
   - **不用顶点色**
   
   推算结果 [推断，按公式代数推出]：近处 NDC 偏移为常数；`depth > 1/(fovDeg*.04)`（fov 70°时约 0.36 m）之后，世界厚度恒定，约 `Width*0.002*tan(h)/h` m（Width 0.5 时约 1 mm）；同时每侧至少约 0.5 像素，直到 `π/2/halfAngle` 米（约 2.6 m）为止。即 **Endfield 描边很细**，常态 1-2 px。
2. 我们：用 YSM 的 inverted hull 顶点（CharacterCapture:263-266），ovsh 只做变换（ovsh:20-30），宽度由 YSM 决定、不可配置，没有深度偏移，没有 mask。并且只有 `humanoid.json` 中 outline:true 时才有几何。
3. 判定：**我们与参考不同**。本包没有 Outline 字节码可交叉验证（全部反汇编中无 CharacterOutline），参考注释称来自 “Captured CharacterOutline VS”。[推断] YSM hull 的宽度通常远大于 1-2 px。
4. 修改：不再使用 YSM 的 hull。把 `c.body` 用正面剔除（`glCullFace(GL_FRONT)`）再画一次，在 ovsh 中自行外扩：
   ```glsl
   uniform vec2 ScreenSize;
   uniform vec4 OutlineWidth;   // per class: cloth, skin/face, hair, eye (0 = none)
   uniform vec4 OutlineOffsetZ; // per class
   uniform sampler2D Sampler3;
   void main() {
       vec4 view = ModelViewMat * vec4(Position, 1.0);
       vec4 clip = ProjMat * view;
       float b = textureLod(Sampler3, UV0, 0.0).b * 255.0;
       int k = abs(b - 5.0) < 2.5 ? 3 : (b >= 112.0 && b <= 138.0) ? 2 : b >= 140.0 ? 1 : 0;
       float width = OutlineWidth[k], dzMeters = OutlineOffsetZ[k] * 0.1;
       vec3 n = normalize(mat3(ModelViewMat) * Normal);
       vec2 pn = (ProjMat * vec4(n, 0.0)).xy;
       vec2 dir = dot(pn, pn) > 0.0 ? normalize(pn) : vec2(0.0);
       float halfA = atan(1.0 / ProjMat[1][1]);
       float d = clip.w;                                  // blocks = metres
       vec2 raw = dir * vec2(ScreenSize.y / ScreenSize.x, 1.0) * (width * 0.3926991 / halfA)
                * clamp(d * halfA * 114.59157 * 0.04, 0.0, 1.0) * 0.005;
       vec2 mn = clamp(d, 0.0, 1.5707961 / halfA) / ScreenSize;
       vec2 shift = vec2(abs(raw.x) < mn.x ? mn.x * sign(raw.x) : raw.x,
                         abs(raw.y) < mn.y ? mn.y * sign(raw.y) : raw.y);
       clip.xy += width > 0.0 ? shift : vec2(0.0);
       float sd = clip.w + dzMeters;                      // GL: z_view = -w
       clip.z = (-sd * ProjMat[2][2] + ProjMat[3][2]) * clip.w / sd;
       gl_Position = clip;
       // … other outputs unchanged
   }
   ```
   Java 默认值（来自 Pelica 材质）：
   - `outlineWidth = 0.65,0.6,0.5,0`（cloth_01/03/04 为 .65、cloth_02 为 .5；body .6、face .5；hair .5）
   - `outlineOffsetZ = 0.02,0,0.01,0`（cloth_01 .02、其余衣服 0；face 0.75 无法与 body 0 区分，可借 FACE class 单独一项）
   
   注意：[推断] YSM 的顶点法线可能在硬边处分裂，外扩会在接缝处开裂。必要时在 Java 侧按位置哈希平均法线，对应参考的 `_OutlineAverageNormal=1`。
5. 影响：**高**（描边粗细是“像不像终末地”的第一观感）。`_OutlineMask` 贴图 Pelica 有：hair/cloth_01/face；`*_st.tga.png` 近乎全白，均值约 0.98，因此常数 1 是很好的近似。

### 4.2 颜色
1. 参考 `EndfieldOutlineMath.ush:66-166`、`Stages/Outline.ush:329,374-376,463-526`：
   - `bright = Grade(base*tint, _OutlineColorBrightness, _OutlineColorSaturation)*.96`
   - `dark = Grade(bright, _ShadowColorBrightness, _ShadowColorSaturation)*.96`；皮肤/脸用 ShadowLUT(bright)
   - 走与本体相同的流程：wrap/俯仰门控、RD ramp、`OfficialDiffuse`（viewAlpha 只用于 hair/cloth）、HG 光照、chroma boost，再加 CP8 rim（头发用深度门控）和局部灯；**没有高光**
   - 法线取 G-buffer 中未外扩位置处的表面法线
2. 我们 ofsh:25-30：`grade(base, OutlineColor.x, OutlineColor.y) * clamp(max(lightmap, sun*sky), .15, 1)`；全身共用一组 (0.5,1.5)。
3. 判定：**我们近似且参数错**。Pelica 每个材质的 (Brightness, Saturation)：hair (0.8,1.0)，cloth_01-04 (0.35,1.5)，body/face (0.5,1.5)。我们给衣服用 0.5（偏亮 43%），给头发用饱和度 1.5（过饱和）。
4. 修改：
   - **最低限度**：ofsh 也读 `Sampler3` 区分类别；`OutlineColor` 改为每类一组，默认 `outlineColorCloth=0.35,1.5`、`outlineColorSkin=0.5,1.5`、`outlineColorHair=0.8,1.0`。
   - **完整版**：在 npr_character.fsh 中加 `#ifdef OUTLINE` 编译出 outline 变体，入口改为 `A = grade(base, OB[k], OS[k])`，跳过 skin rim-off 之外的 spec 分支，其余（ramp、OfficialDiffuse、lightCol、boost、rim）共用。
5. 影响：中-高（暗部描边应该更深更饱和，亮部更浅）。

### 4.3 YSM hull 与 `outline:true`
我们的 `renderOutline` 依赖模型自带的 hull（CharacterCapture:263-266）。按 §4.1 改为自绘后，与 humanoid.json 无关，可删除 `Captured.outline` Recorder 与 `NativeRenderer.renderOutline` 调用（干净切换）。

---

## 5. ramp 贴图选择（RD/RS）

- 参考：`Source/**` 中没有任何 RD/RS 路径或映射，只有 `MI_Perlica_*` 资产名（`ShowcaseNPRInputTests.cpp:316,324,911-912`），Content/ 已剔除。**无法从参考得到 pathID → 文件名。**`01-模型/textures/_index.jsonl` 只有 2 行，材质 JSON 只有 file_id/path_id，没有 externals 表，也无法反查。
- 能确定的事实（来自 `materials/*.mat.Material.json`）：
  - body/face 共用 RD 5848563174712869001（配置目录中 skin_RD=face_RD=body_01_RD，md5 739dffdf，合理）。
  - cloth_01/02/03 共用 RD 2595675294308502756。cloth_04 的 RD 7616793060396148846，但 **`_UseDiffRampMap=0`，不采样**，走 `smoothstep(.25,1,·)`。
  - cloth 系共用 RS 4523718382697154697，`_UseSpecRampMap=1`，`_SpecRampIridescentMode=0`。
  - hair/hairt 共用 RD -6227453272663763460、RS 8975600794099224062，`_UseSpecRampMap=1`。
  - 现用文件：cloth_RD = cloth_05_RD，hair_RD = hair_03_RD，hair_RS = hair_01_RS（256×1），cloth_RS 缺失。
- 建议 [推断]：hair_RS 选 256×256 的 hair_02/03（字节码 H2 是 2D 采样，Pelica 用 2D 变体）；cloth_RS 用 cloth_02_RS 或 cloth_04_RS（256×256）。这些仍是猜测，需要游戏截图目测比对。

---

## 6. 可直接采用的数值

| 项 | 值 | 出处 |
|---|---|---|
| hair `_AnisotropyValue / Value2 / Range2 / Intensity / EdgeFade / DirX` | 0.5 / 0.5 / -0.02 / 3 / 1 / 0 | `materials/m_actor_pelica_hair_01` |
| hair `_AnisotropyColor2` | (0.2264, 0.2233, 0.1141) | 同上 |
| hair 高光遮罩 G 常数（替代 `_MetallicGlossMap.g`） | 0.4（实测非零均值 0.39；参考无图默认 0.5） | `hair_01_p` 实测 / `TextureDecode.ush:17` |
| hair `_p` 其余通道 | R=0（不混合几何切线）、B=1、A=1 | 实测 |
| hair `_Line*` | Amount 300、Value .55、Range .85（指数 floor(30)）、Intensity .2、Saturation .1、UseLineMap 1、LineMap ST (6,1,-.23,0) | hair_01 |
| cloth smoothness（贴图 A 均值） | cloth_01 0.42、cloth_02 0.45（材质值 0.327/0.5 在 UseMap=1 时不生效） | `cloth_0x_p` 实测 |
| cloth `_p.G` / `_p.B` | ≈0.97–1.0 / 均值 0.57（AO） | 实测 |
| cloth_04 `_UseDiffRampMap` | 0（用 smoothstep(.25,1,NdotL)） | cloth_04 |
| cloth_02 自发光 | `_UseEmission=1`、颜色 (1,.72,0)、亮度 8 | cloth_02 |
| Outline Width | hair .5、cloth_01/03/04 .65、cloth_02 .5、body .6、face .5 | 各材质 |
| Outline Brightness/Saturation | hair .8/1、cloth .35/1.5、body/face .5/1.5 | 各材质 |
| Outline OffsetZ（×0.1 m） | hair .01、cloth_01 .02、cloth_02-04 0、body 0、face .75 | 各材质 |
| `_OutlineAverageNormal` | 全部 1（平滑法线外扩） | 各材质 |
| 描边公式常数 | atan 多项式 -.30189499/.08729290；π/8；fovDeg*.04；*.005；min=(1/size)*clamp(d,0,π/2/halfA) | `EndfieldCharacterPass.usf:46-61` |
| 头发深度 rim | 偏移 `rimWidth*.006` 屏幕 UV，`smoothstep(.1,.2,Δdepth_m)` | `PunctualLighting.ush:249-252,608-611` |
| NPR 灯 rim 宽度（参考场景） | 0.35（Spot rim 0.3），RimMaterialBlend 默认 .5 | `ShowcaseNPRLightComponent.h:36-37`、`ShowcaseDirector.cpp:291-482` |
| 局部 Default 灯背面对比 | 衣服/头发/眼 .25，皮肤/脸 .5 | `PunctualLighting.ush:309,265,374` |

CP（_CharacterParams）的映射与默认值见 skinface 报告；本次没有在 Source 中找到实际场景数值。

---

## 7. 优先修复清单（≤10）

1. **头发高光复活**：F0 与 u 改用 `hairGloss`（0.4），不再用 `_Specular=0`（§1.1；H2:1200,1689）。高。
2. **描边自绘**：正面剔除重画本体，在 ovsh 中实现参考的屏幕空间宽度、透视补偿与 OffsetZ；按类别取 Width/OffsetZ；弃用 YSM hull（§4.1）。高。
3. **头发 2D RS**：u 不含 vh，v=vh²·(T·H>0)，`texture(Sampler7, vec2(u,1-v))*u*vh`；切线取 `cross(N,cross(N,axis))`；竖直分量用世界 `dot(L,U)`（§1.2；H:1718，H2:1689-1696）。高。
4. **衣服 view ramp**：CLOTH 的 `aoMask = RD(N·cam).a`（§2.2；C2:1568-1573）。中-高。
5. **描边颜色**：按类别设 Brightness/Saturation（cloth .35/1.5、hair .8/1、skin .5/1.5）；后续再与本体共用光照路径（§4.2）。中-高。
6. **背光 wrap 俯仰门控 + 世界水平投影**：传入 `WorldUpView`，`pitchGate = smoothstep(.25,.75,1-|cam·U|)`（§1.3；H:1636-1642，C2:1546-1553）。中。
7. **衣服高光 ramp**：PNG 的 V 翻转 `(1-v)`，乘 RGB 而非亮度，并放入一张 cloth_RS（§2.3；C2:1688-1697）。中。
8. **半角 fill 用世界 up**（与 skinface §1.4 同一处修改，衣服受益更大）+ 背光 `x=dot(Lh,N)`（§2.4、§3.5）。中。
9. **头发二级高光**：`_AnisotropyColor2`、`Range2`（§1.2）。低-中。
10. cloth smoothness 默认改为 0.42；头发 CP8 rim 改用深度门控（需要角色深度 prepass）（§2.1、§1.4）。低-中。
