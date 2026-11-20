# 几何 LOD 与骨骼 LOD 设计（评审建议「优化」第 3、4 条）

2026-11-18。本文只做设计，不改代码。凡标「实测」的数字都是本次在构建机上跑出来的（方法见附录 A），标【推断】的是根据代码或资料推出、没有实测的结论。

引用路径的缩写：

| 缩写 | 实际路径 |
|---|---|
| `YSM/` | `~/ysm/src/YesSteveModel/src/main/java/com/elfmcys/ysm/`（YSM 原版 Java，只读） |
| `NAT/` | `~/ysm/src/YesSteveModel-Native/modules/`（YSM 原版 native，只读）；弹簧相关的行号取自打过补丁的 `~/ysm/nativebuild/src/YesSteveModel-Native/modules/`，即发货 DLL 的源码 |
| `FIX/` | `~/ysm/pelica-fix/mod/src/com/elfmcys/ysm/`（本项目的 YSM 补丁类） |
| `NPR/` | `~/ysm/npr/work-merged/src/main/java/com/ysmnpr/endfield/`（endfield_npr） |
| `FB/` | `~/ysm/fitbox/src/main/java/com/ysmfit/fitbox/` |

---

## 0. 结论

**几何 LOD（优化 3）：做，用方案 A。**

- **导入时**：用 Java 实现的属性感知 QEM 简化器（移植 meshoptimizer 的 `simplifyWithAttributes`，再加上我们自己的约束）生成 3 级 LOD。
- **只存三角形索引**：各级 LOD 只是指向原始顶点的三角形索引表，放进一个旁挂条目 `geo_models["mesh_lod"]`。
  - 这样权重、UV、形态键增量在保留下来的顶点上一个字节都不变。
  - 原版 YSM 只读 `main`/`arm`，会忽略这个条目。
- **客户端加载时**：把 `main` 的 GeoModelProto 换成 LOD 的三角形表，烘焙成几个兄弟 GeoModel。它们的骨骼、弹簧完全相同。
- **每帧**：同一个 `NativeModelState` 每次 extract 时传入当前档的 baked model。
  - native 本来就允许每次 extract 换 baked model。
  - native 只需补一处：骨骼相同的模型之间切换时，弹簧状态不重置，约 30 行。
- **选档**：在 native 蒙皮之前，按屏幕空间误差选档；投影矩阵取主画面这一帧的，含倍镜缩放。
- **预期收益**：20 人的人群中，蒙皮加写顶点的 CPU 时间预计降 80–87%。上传字节和 GPU 顶点量按同样比例下降。假设见 2.10。

**骨骼 LOD（优化 4）：做，但收益比几何 LOD 小一个数量级。** 实测一个佩丽卡：

| 操作 | 时间 |
|---|---|
| 不带弹簧的 extract | 12–21 µs |
| 60 fps 时带弹簧的 extract | 64–83 µs |
| 蒙皮 | 3.96 ms |

所以骨骼 LOD 的价值在弹簧链：

- **主要手段**：按链分级的「保持（hold）」模式。导入时给链分类（主发束 / 次级发束、衣摆 / 饰品）。远处角色和屏幕外角色的链不积分，保持上次的摆动形状，跟着父骨骼刚性移动。
- **挂点**：`NativeModelState` 上新增一个按链设置模式的 JNI 调用。不改 `nExtract` 签名。
- **本地玩家**：永远全量。
- **fitbox 不受影响**：fitbox 只用本地玩家自己上报的核心骨骼，弹簧骨骼本来就被排除在拟合之外。
- **endfield_npr 的动画 LOD 省不掉弹簧积分量**：它只是把若干帧的固定步攒到一次 extract 里做（见 3.1）。骨骼 LOD 正好补上这一块，两者相乘。

**不推荐：**

- 方案 B：native 单模型多级网格。可行，但改动面是 A 的 3–4 倍。只有 A 暴露出问题时才做。
- 方案 C：渲染模组里蒙皮后再简化。省不了蒙皮和上传。
- 方案 D：用骨骼隐藏来切档。不可行。
- 在 Java 动画求值里按骨骼跳过。有 Molang 副作用的风险，收益也未测。

**分期**（第 4 节）：

| 阶段 | 内容 | 工作量 |
|---|---|---|
| P0 | native 小补丁 | 1–1.5 人日 |
| P1 | 几何 LOD | 8–10 人日 |
| P2 | 弹簧 LOD | 3–4 人日 |
| P3 | 可选项 | 视需要 |

---

## 1. 现状：YSM 怎样烘焙、蒙皮、选几何

### 1.1 数据：`ModelData.geo_models`

- **模型数据**：一个 `ModelData`（`~/ysm/src/YesSteveModel/src/main/proto/asset/model/ModelData.proto:11`，`map<string, bytes> geo_models`）里，每个几何是一个序列化的 `GeoModel`。
  - 字段：`bones`、`properties`、`cubes`、`mesh`、`springs`。
  - `mesh` 是 `SkinnedMesh`：每顶点的 positions/normals/uvs，4 个 joints，4 个 weights，triangles，morphs（`MorphTarget`：驱动骨、驱动方式、顶点表、增量），outline_triangles。
  - 定义在 `~/ysm/src/YesSteveModel/src/main/proto/asset/model/data/geo_model.proto:35-94`。
- **导入器写的条目**：`FIX/format/parser/RawModelAssembler.java:298-310` 写 `main`、`arm`，VRM 带包时再写 `endfield_npr`。
  - `endfield_npr` 是一个 ZIP，不是几何。`FIX/format/parser/EndfieldNprPackage.java:39-40` 的注释写着「YSM only reads "main" and "arm"」。
- **YSM 客户端怎么读**：只按名字取，`ModelRenderTargetLoader.geoModel(definition, name)`（`YSM/model/resource/client/render/ModelRenderTargetLoader.java:769-780`）。
  - 玩家目标：每个贴图变体烘焙一次 `main`，有 arm 时再烘焙 `arm`（同文件 `:589-606`）。
  - 结果放进 `PlayerModelVariant(main, arm)`。
  - **未知 key 不会被读，也不会报错。**
- **全量遍历 geo_models 的地方只有两处：**
  - `YSM/model/catalog/builtin/BuiltinModelMaterializer.java:99-105`：对**内置模型**的每个条目做 `tryBake`。`endfield_npr` 已经有这个限制，旁挂条目也一样，即不能做成内置模型。
  - `YSM/expression/inventory/ModelProgramInventory.java:520`：把所有条目算进程序指纹。只影响缓存键，无害。

### 1.2 烘焙：GeoModelProto → native `BakedModel`

**调用链：**

1. `bakeModel`（`ModelRenderTargetLoader.java:701-716`）
2. → `BakedModelCache.loadOrBake`（有磁盘缓存，键为容器、资源名、贴图哈希）
3. → `NativeBakedModel.bake`（`YSM/natives/render/NativeBakedModel.java:322-361`）
4. → JNI `nBake`（`:343`）
5. → native `gfx::bake::BakeModel`

**native 侧（`NAT/gfx/src/bake/baked_model.cc`）：**

- **`AddMesh`（`:1539-1594`）**：把每个顶点的权重转成排序后骨骼的 influence。
- **`GroupMeshTriangles`（`:1215-1270`）分组**：
  - 每个三角形按贴图透明度归入不透明或半透明，按「权重和最大的关节」归属一根 owner 骨（`MeshTriangleOwner`，`:1184-1213`）。
  - 贴图全透明的三角形被丢掉。
- **`PairTriangles` + `MeshBaker::AddGroup`（`:1131-1170`）切 meshlet**：
  - 两个三角形拼成一个 quad。
  - 每个 meshlet 最多 128 个 quad、256 个槽位。
  - 顶点按 SIMD 宽度打成 SoA 块 `MeshVertexBlock`：一块内的顶点共享同一组 ≤4 个关节，每条 lane 有自己的权重（`NAT/gfx/include/gfx/bake/baked_model.h:94-129`）。
- **形态键**：增量按块存在 `morph_offsets/morph_deltas`（`baked_model.h:131-168`）。权重来自驱动骨的动画属性（`NAT/gfx/src/renderer/morph.cc:16-31`：X 平移，或 1−Y 缩放）。
- **骨骼和弹簧表**：
  - 每根骨骼记下自己拥有的 meshlet：`mesh_opaque / mesh_translucent / mesh_outline`（`baked_model.h:196-219`）。
  - 弹簧表 `BakedSprings`：joints、colliders、链到碰撞体的映射（`:230-259`）。

**结果**：一个不可变的 `BakedModel`。Java 侧是 `GeoModel`（`YSM/geckolib3/geo/render/built/GeoModel.java`），被所有用同一模型和贴图的实体共享（`share()`）。

### 1.3 每帧：extract → 蒙皮

1. **调度**：`LevelRendererMixin` 在 `LevelRenderer#renderLevel` 头部调 `AnimationParallelTicker.scheduleAll`（`YSM/mixin/client/LevelRendererMixin.java:20-29`）。这个注入点的参数里就有这一帧的投影矩阵 `Matrix4f`。
2. **worker 线程**：对每个已加载的 animatable 跑 `computePose`（`YSM/client/animation/AnimationParallelTicker.java:76-108`）。
3. **`computePose`（`YSM/client/entity/AnimatableEntity.java:448-475`）**：
   - 先 `tickAnimation`，即 Molang 和控制器。它受 `getFrameRateLimit` 限频（`:381-403`）：未渲染 10/s，>64 格 30/s，>40 格 60/s。**只看距离，不看 FOV。**
   - 再 `data.modelState.extract(installation.geoModel)`。
4. **`GeoModelState.extract`（`YSM/geckolib3/geo/animated/GeoModelState.java:44-85`）**：
   - 调 `nativeState.extract(model.bakedModel(), animatedModel.getBoneAttributes())`。
   - 每根骨 14 个 float：旋转、位置、缩放、隐藏、定位器、颜色。
5. **`NativeModelState.extract`（`YSM/natives/render/NativeModelState.java:176-220`）**：
   - 附带一帧物理参数（`nextPhysicsFrame`，`:125-161`）。
   - 超过 0.5 s 没步进就重置（`:46`）。
6. **native `ModelState::Extract`（`NAT/gfx/src/renderer/model_state_impl.inc`）**：
   - **`:89-99`**：每次调用都接受传进来的 baked model，换了就换。schedule 只在模型相同时复用。**每次 extract 换模型在 native 层是合法的。**
   - **`:135-207`**：沿骨骼树走一遍，写每根骨的 `BonePose`（128 B/骨），收集有几何的「渲染骨」。缩放为 0 或 `children_hidden` 会跳过整棵子树。
   - **`:211-222`**：`StepSprings`（弹簧）和 `UpdateMorphWeights`。
   - **`:223-228`**：渲染骨列表变了就重建 schedule（`schedule.cc:170` `RenderSchedule::Update`）。
7. **绘制**：`NativeRenderer.render`（`YSM/natives/render/NativeRenderer.java:56-63`）。
   - 先把这次绘制的世界变换交给弹簧（`observeDraw`，`:158-177`）。
   - 再 `nRender`，到 native `PerformMeshRender`（`NAT/gfx/src/renderer/render_impl.inc:213-320`）。
   - 逐 meshlet、逐块蒙皮（`SkinMeshBlock`，每个关节广播一个矩阵）。被形态键触及的块先加增量。
   - 按 quad 写出顶点，格式为 NEW_ENTITY 或 Iris 格式。
   - **蒙皮和写顶点都在这一步，在渲染线程上，用 native 的并行执行器。**

### 1.4 模型状态怎样选几何

- **选哪个几何**：
  - 安装模型时，`CustomHumanoidEntity.resolveGeoModel`（`YSM/client/entity/CustomHumanoidEntity.java:216-220`）按贴图名取 `PlayerModelVariant.mainModel()`。
  - 包成 `AnimatedGeoModel`（`AnimatableEntity.java:858`）。
  - 之后每次 extract 都用 `animatedModel.getModel()`。
- **YSM 已经支持「骨骼相同的几何原地替换」**：`AnimatedGeoModel.prepareModelBinding`（`YSM/geckolib3/model/AnimatedGeoModel.java:71-95`）只检查排序后骨骼的数量、名字顺序和定位器结构一致。换贴图变体就走这条路（`AnimatableEntity.java:826-856`）。
- **骨骼顺序是确定的**：GeoModelProto 的骨骼表相同，烘焙出的排序骨骼顺序就相同（排序在 native 烘焙里，只依赖骨骼表）。
- **第一人称**：用 `arm` 几何，走 `FIX/client/entity/FirstPersonModelState.java:104-111`，与本设计无关。
- **每个实体的状态**：level / 阴影这类不可变上下文共用 `mainRenderData` 里的同一个 `GeoModelState`，GUI、纸娃娃等用各自的状态池（`AnimatableEntity.java:514-527`）。
  - **这意味着 Iris 阴影 pass 和主 pass 用同一个 native 状态，也就是同一份弹簧状态。**

### 1.5 endfield_npr 在这条链上的位置

- **`CharacterCapture`**：在 YSM 的 `RenderModelEvent` 里，用 `RenderSystem.getProjectionMatrix().m11()` 算投影高度 `h·m11/(2d)`（`NPR/CharacterCapture.java:84`、`:138-141`）。然后把这次蒙皮写进 GPU 页的一个 cell。
  - FULL 档先写 `CpuMesh` 再拷进去（`:172-230`）。
  - slot 用 (model, nativeState, vertexCount, texture) 判断蒙皮是否还属于当前模型（`NPR/NprRenderer.java:168-171`，`CharacterCapture.java:164-170`）。
  - cell 不够大就重分（`NprRenderer.java:182-191`）。
- **`CpuMesh.cardRuns`**：按 UV 矩形扫出叠加贴片的 quad 区间，按顶点数缓存，数量变了就重扫（`NPR/CpuMesh.java:51-79`）。
- **动画 LOD（`NPR/mixin/GeoModelStateMixin.java`）**：在 `GeoModelState.extract` 头部拦截。同一帧重复、屏幕外、按间隔的 extract 不再做（`:101-115`）。前提条件之一是 `getModel() == model.getModel()`（`:102`）。
  - 本地玩家只去重，不降频（`CharacterCapture.java:85`）。
- **README「GPU skinning: evaluated, not implemented」**（`~/ysm/npr/work-merged/README.md:358-367`）：网格、权重、形态键只在 native 内存里，没有 JNI 能拿出绑定姿态的顶点。
  - 所以 LOD 不能在渲染模组里另起一套蒙皮，**只能在 YSM 烘焙这一侧做。**

### 1.6 实测成本（佩丽卡，附录 A）

**测试条件**：8 vCPU QEMU 虚拟机，SSE4.1。用发货 native 的同一源码树（`nativebuild` 的 Release 静态库，含两份弹簧补丁）直接调 `ModelState::Extract` 和 `Render`。

**佩丽卡的 `main` 几何：**

| 项目 | 数量 |
|---|---:|
| 顶点（导入后，含材质副本） | 77,948 |
| 三角形 | 110,213 |
| 形态键 | 0（脸用骨骼驱动） |
| 骨骼（其中蒙皮关节） | 294（276） |
| 弹簧链 / 关节 / 碰撞体 | 25 / 63 / 12 |
| SIMD 块 / quad / meshlet | 27,384 / 58,633 / 653 |
| native 内存 | 8.5 MB |
| 烘焙时间 | 83 ms |
| 每帧输出顶点 | 234,532（NEW_ENTITY 8.1 MB） |

（这次跑的是 `~/mc/client/mods` 里装的 jar；PhysicsFix 之后碰撞体会多几个。）

**每次操作的耗时：**

| 操作（单角色，每次） | 耗时 |
|---|---:|
| extract，不带弹簧（走骨骼树 + 形态键 + schedule） | 12–21 µs |
| extract + 弹簧，1 个固定步（≈120 fps） | 40–66 µs |
| extract + 弹簧，2 个固定步（≈60 fps） | 64–83 µs |
| extract + 弹簧，8 个固定步（≈15 fps 或动画 LOD 间隔 4） | 236–246 µs |
| 每个固定步的边际成本 | 22–28 µs |
| 蒙皮 + 写顶点（`Render`，并行执行器墙钟时间） | **3,957 µs** |

**蒙皮成本随输出顶点数近似线性。** 按三角形下采样后再测（下采样不是真 LOD，只用来看成本曲线）：

| 三角形比例 | 输出顶点 | 蒙皮 + 写顶点 |
|---|---:|---:|
| 全部 | 234,532 | 3.96 ms |
| 1/2 | 155,232 | 2.67 ms |
| 1/4 | 96,852 | 2.20 ms |
| 1/8 | 52,740 | 1.01 ms |

约合每个输出顶点 17–23 ns。

**结论：一个角色的蒙皮比它的整个 extract（含弹簧）贵 50 倍。** 几何 LOD 是主要收益，骨骼 LOD 是次要收益。但屏幕外的角色不蒙皮却照样 extract，在人多的服务器上弹簧就成了主要成本（3.6）。

---

## 2. 优化 3：导入时生成几何 LOD

### 2.1 方案 A（推荐）：旁挂索引 LOD → 客户端烘焙兄弟 GeoModel → 同一状态逐次换 baked model

**可行性：可行。** 依据：

1. **原版 YSM 会忽略新条目**：额外的 `geo_models` 条目不会被原版 YSM 读取（1.1）。用这个 jar 的服务器发给任何客户端都安全，旧客户端只是多下载一点。
2. **兄弟 GeoModel 骨骼一致**：LOD 的 GeoModelProto 除 `mesh.triangles/outline_triangles` 外与 `main` 完全相同。烘焙出的排序骨骼、定位器、弹簧表都一致，`AnimatedGeoModel.prepareModelBinding` 的一致性检查天然成立（1.4）。
3. **未引用的顶点不占内存**：`MeshBaker` 只把 quad 引用到的顶点放进块（`baked_model.cc:1137-1163`）。所以 LOD 只需要三角形表，不必压缩顶点表。
4. **形态键增量原样有效**：增量按源顶点编号，只为进了块的顶点发出（`baked_model.cc:1058-1069`）。简化只把边塌到已有顶点上，不新造顶点，留下的顶点的增量、权重、UV 原样有效。
5. **native 允许每次 extract 换 baked model**（`model_state_impl.inc:89-99`）：换模型的代价只是重建 schedule（`schedule.cc:170`，O(渲染骨)）。
6. **唯一的障碍：换模型会重置弹簧。** `StepSprings` 里 `reset = … || spring_model_ != baked_model_.get() || …`（`NAT/gfx/src/renderer/spring_physics.cc:262-266`）按指针判断。
   - 不修的话，每次切档发束都会从动画姿态重新起摆，远处是几像素的下垂动作。
   - 修法：判断条件改成「弹簧表内容相同」。在 `BakedModel` 构造或读缓存时，对 joints/colliders/链表算一个 64 位指纹并存起来，`StepSprings` 里比指纹。
   - 约 30 行加一个 native 测试，不改 JNI。

「每档一个 geo_models 条目，每档一个独立的 model state」这种字面上的做法也可行，但不推荐：

- 每个 `NativeModelState` 有自己的弹簧尾点。切到另一个状态时，那边的弹簧要么是旧的，要么要重置。
- 要让几个状态都保持热，就得每帧对每档都 extract，成本翻倍。
- 外部读姿态的代码（fitbox、渲染模组）也得知道该读哪个状态。

**同一个状态、逐次换 baked model 就没有这些问题。**

**具体设计：**

- **导入（`HumanoidImport`）**：
  - 在 `emitMesh/emitMorphs` 之后（`FIX/format/parser/HumanoidImport.java:276-277`）生成 LOD1–3。
  - `RawModelAssembler` 写 `geo_models["mesh_lod"]`。
  - 格式沿用 `endfield_npr` 的先例，用 ZIP：
    - `lod.json`：版本，每档的几何误差 ε 和轮廓误差 σ（都是模型高度的比例）、三角形数，链分类（第 3 节用）。
    - `lodN.mesh`：只填了 `triangles`、`outline_triangles` 的 `SkinnedMeshProto` 字节，复用现有的 packed varint 编码。
- **加载**：`ModelRenderTargetLoader.buildNow` 读 `mesh_lod`。
  - 对每档复制 `main` 的 proto，替换两个三角形表，再走 `bakeModel`。资源名 `…/main#lodN`，所以磁盘烘焙缓存自动分开。
  - `GeoModel` 增加 `GeoModel[] lods` 和每档误差。
  - 没有 `mesh_lod` 的模型行为完全不变。
- **选档**（2.7）：结果写进 `GeoModelState.lodLevel`。
- **extract**：`GeoModelState.extract` 改为 `nativeState.extract(model.lod(lodLevel).bakedModel(), attrs)`。
  - 对外发布的 `getModel()` 仍是基模型，不让下游的身份判断频繁失效。
  - 另加 `getGeometry()` / `getLodLevel()` 给需要的人。
  - 顶点数照常从 `getVertexCount()` 拿，自然是当前档的。

**工作量（人日）：**

| 项 | 工作量 | 说明 |
|---|---:|---|
| Java 简化器 | 4–5 | 2.5 节 |
| 导入器约束与旁挂写出 | 1.5 | 含描边映射、链分类、REVISION 升级 |
| 客户端加载与 `GeoModel.lods` | 1 | |
| 选档器 | 1 | 投影、迟滞、切换预算、配置 |
| native 弹簧连续性补丁 | 0.5 | 加 Windows / Linux 交叉构建 |
| endfield_npr 适配 | 0.5 | 2.8 节 |
| 验证 | 1–1.5 | 轮廓对比、四个模型、`debugClones` 计时 |
| **合计** | **9.5–11** | 其中 native 只有 0.5 |

**风险：**

- 每个模型 × 贴图变体要多烘焙 3 次。按下采样实测，烘焙时间是全模的 20–35%，佩丽卡约 +60 ms，在 worker 上，有磁盘缓存。
- 多出的 native 内存见 2.9。
- 切档那一帧的 schedule 重建和 NPR slot 重分是一次性成本，用切换预算限流（2.7）。
- 弹簧连续性补丁必须先于本方案上线。否则切档时发束会抖一下，近处可见。
- 需要和 NprLook 等其他改导入器的 agent 协调 REVISION 号（现在是 22）。

### 2.2 方案 B：native 单个 BakedModel 带多级网格，每次 extract 带 LOD 下标

**可行性：可行，但改动大。** 需要改的地方：

- **proto**：`SkinnedMesh` 加 `repeated Lod lods`。Java 生成的 proto 类和 native 的 `struct_pb` 头（`NAT/proto/include/proto/asset/model/data/geo_model.proto.h`）都要改。
  - 未打补丁的 native 遇到未知字段会怎样没有验证过。用 `ModelData` 整体解析带 ZIP 条目的数据时，本次在 `struct_pb` 里实测抛过 `unknown key`。混装环境有风险。
- **烘焙**：`AddMesh` 按档生成 meshlet。
- **骨骼分区**：`BoneInfo` 的三个 mesh 分区改成按档（`baked_model.h:196-219`）。
- **调度**：`schedule.cc` 按档取任务。
- **状态和 JNI**：`ModelState` 存当前档，JNI `nExtract` 签名加参数（Java `NativeModelState` 跟着改）。
- **缓存**：序列化格式（`baked_serializer.cc`）和缓存 ABI 升级。
- **测试**：bake、schedule、render、cache 都要补测试。

**工作量：** native 5–8 人日加交叉构建。Java 侧的选档和导入器部分与 A 相同。

**好处：**

- 只有一个 GeoModel，弹簧天然连续。
- 换档不必重新指向别的 baked model。

**代价：**

- 内存和 A 一样：每档的 meshlet 块都要单独存，因为块里的顶点集合不同。
- 传输量和 A 一样，也只存索引。

**结论：只在 A 出现问题时再做。** 例如 schedule 重建在实际场景里显著，或 Iris 阴影 pass 要用不同的档。

### 2.3 方案 C：在渲染模组里，蒙皮之后再简化

**可行性：只能部分做到，不推荐。**

- **省不掉主要成本**：蒙皮和写顶点（3.96 ms/角色）在 `NativeRenderer.render` 里已经发生，蒙皮后再减三角形省不掉这部分。上传也省不掉：FULL 档要拷 `CpuMesh`，其余档是零拷贝写进映射页，都按 YSM 输出的顶点数写。
- **只能省 GPU 顶点着色和光栅**：为每个模型预计算一个「LOD 索引表」，指向 YSM 输出流里幸存角点的槽位，NPR 只画这些。
- **依赖 YSM 的输出顺序**：输出顺序随隐藏骨骼和 schedule 变化。native 也不提供「输出顶点 → 源顶点」的映射，只能靠位置匹配。`CpuMesh.cardRuns` 已经在吃这个顺序依赖，它的做法是每帧复查、不对就重扫。
- **YSM 自己的绘制路径**（无 NPR 或 `YSM` 档）完全拿不到好处。

### 2.4 方案 D：所有档放进同一个网格，用骨骼隐藏切换

**不可行。**

- 三角形的 owner 骨由权重和最大的关节决定（`baked_model.cc:1184-1213`）。不改权重就没法让某一档的三角形归到一根专用的「LOD 骨」上，所以 `cubes_hidden` 没法按档开关。
- 改权重就会改变形。

### 2.5 简化器选型

| 选项 | 依据 | 结论 |
|---|---|---|
| meshoptimizer（C++）编进 YSM native，加 JNI | conan 依赖里没有（`~/ysm/src/YesSteveModel-Native/conanfile.py:20-46`），本机也没有 conan 配方。导入在**服务器**上跑，服务器用的是 jar 解出的 Linux `.so`，所以 Windows DLL 和 Linux so 都得带新代码。YSM 编译开了 fast-math（`YSM_FAST_MATH_BEGIN`），跨平台结果是否逐位一致无保证。 | 质量最好、最快。但要加配方和 JNI，还牵扯两个平台的 native 发布，约 2–3 人日，并且把导入结果绑在 native 上。备选。 |
| LWJGL 的 `lwjgl-meshoptimizer` | MC 1.20.1 带的是 LWJGL 3.3.1，**不含**这个模块（`~/mc/client/libraries/org/lwjgl/` 只有 lwjgl、glfw、jemalloc、openal、opengl、stb、tinyfd），得自带 jar 和各平台 natives。3.3.1 绑定的是 2022 年初的 meshoptimizer，【推断】还没有 `simplifyWithAttributes`（2023 年加入）和 `vertex_lock`。 | 否 |
| **Java 移植 meshoptimizer 的简化器**（MIT），加我们的扩展 | 导入器本来就是 Java（`HumanoidImport`），服务器任何平台都能跑，结果确定，可以离线对拍。 | **推荐**，4–5 人日 |
| 从零写 Java QEM | 边界、接缝、拓扑这些坑 meshoptimizer 都已经踩过 | 否 |

**移植的范围：** meshoptimizer 1.3 的 `meshopt_simplifyWithAttributes` 路径（`src/simplifier.cpp`）：

- 顶点种类：manifold、border、seam、complex、locked。
- 位置二次误差加属性二次误差。
- 只塌到已有顶点上的边塌缩、误差上限。
- `SimplifyErrorAbsolute`、`SimplifyPrune`、`SimplifyPermissive`，以及 `vertex_lock` 的 `Lock / Protect / Priority` 标志。这些都在当前头文件 `meshoptimizer.h:470-547` 里。

**只塌到已有顶点上**正是我们需要的性质：权重、UV、形态键增量都不用插值。

**我们自己的扩展：**

1. **区域误差权重**：按顶点乘一个二次误差系数，用来给头发、脸、身体分别设预算。meshoptimizer 1.x 的 `Vertex_Priority` 只是一个二值标志，Java 里直接做成浮点系数。
2. **多姿态属性**：把每个顶点在 K 个测试姿态下的蒙皮后位置作为属性加进二次误差（K = 4：走路两极、蹲、举枪或抬手），每个姿态 3 个分量，权重 1/K。这样「看起来静止时一样，但一动就穿帮」的塌缩会被惩罚，**蒙皮权重的差异在它实际造成形变的地方被保住**。姿态取导入器已经生成的片段（`HumanoidMotion`、`TaczRetarget`），`HeldItems` 也已经在用骨架和权重做同类求解（`HumanoidImport.java:287-288`）。
3. **材质内允许跨 UV 接缝**：材质间的接缝用 Protect，同一材质内的 UV 接缝在 LOD2 以上允许跨（Permissive）。原因见下面 PMX 的实验。

**离线对拍：** `pip install meshoptimizer`（第三方 Python 封装，本次装上的是 0.2.30a0，内核较旧，没有 Permissive 和 `simplifyWithUpdate`）。在四个测试模型上对比 Java 版和 C++ 版的三角形数、误差，目标是同样误差下三角形数相差不超过 10%。

**本次实测**（C++ 内核，四个模型的导入后 `main`，误差上限取模型尺寸的比例，属性为法线和 UV）：

| 模型（三角形） | 误差上限 | 锁整张脸 | 只锁脸部接缝 | 不锁 | 锁脸部接缝 + 形态键顶点 |
|---|---:|---:|---:|---:|---:|
| 佩丽卡（110,213） | 0.1% | 45.3% | 36.3% | 34.8% | **36.3%** |
|  | 0.5% | 26.3% | 15.2% | 13.1% | **15.2%** |
|  | 2% | 21.9% | 10.2% | 7.9% | **10.2%** |
| vrm1_sample（71,676，14 个形态键，2,648 个被移动的顶点） | 0.1% | 48.4% | 28.8% | 23.6% | **33.9%** |
|  | 0.5% | 36.0% | 13.0% | 6.5% | **18.3%** |
|  | 2% | 33.4% | 9.8% | 2.7% | **15.2%** |
| 薇薇安 PMX（62,958） | 0.1% | 52.0% | 45.4% | 43.8% | **47.2%** |
|  | 0.5% | 27.0% | 17.6% | 14.6% | **19.6%** |
|  | 2% | 21.8% | 11.9% | 8.4% | **14.0%** |
| 柚叶 PMX（87,255） | 0.1% | 72.1% | 67.8% | 66.2% | **69.5%** |
|  | 0.5% | 55.2% | 49.5% | 47.4% | **51.5%** |
|  | 2% | 51.4% | 45.5% | 43.3% | **47.6%** |

读法：

- 表里是保留下来的三角形比例。
- 「锁整张脸」：头部子树里、非头发骨骼主导的顶点全部锁定，佩丽卡约 1.1 万个。
- 「只锁脸部接缝」：只锁头部区域的材质边和 UV 接缝顶点。
- 简化本身很快：C++ 每档 80–100 ms（11 万三角形）。【推断】Java 移植慢 3–5 倍，每次导入多 1–1.5 s。

结论：

- **锁整张脸代价太大**（佩丽卡在 22% 处见底）。脸只在近处重要，所以按档放宽（2.6）。
- **柚叶在 45% 左右见底**：它的 5.9 万个顶点里有 3.9 万个落在接缝上（位置重合的副本），旧版内核不跨接缝。要靠 Permissive 加 Protect 只守材质边，这正是移植时要带上 1.x 的这部分逻辑的原因。
- 误差大到 2% 时，即使不锁，也会出现整块塌掉的情况。不锁、目标比例 6.25% 时实测误差 57%，即整个零件被吃掉。所以**每档一律按误差上限截断，不按目标三角形数硬压**，并且一定要开 Prune 去掉孤立的小零件。

### 2.6 保住脸、发型外轮廓、材质边界和蒙皮权重

一个顶点可以带三种标记：

- **Lock**：不动。
- **Protect**：不跨属性边界。
- **区域系数 k**：误差 × k，k 大的区域更难被塌。

| 约束 | LOD1（误差 ≤0.1%，约 35–50%） | LOD2（≤0.5%，约 15–20%） | LOD3（≤2%，约 10–15%） |
|---|---|---|---|
| 脸（眼、眉、嘴、虹膜材质，脸部 SDF 用到的面片） | Lock | Protect 材质边，k = 4 | Protect 材质边 |
| 形态键会移动的顶点（增量 > 1e-4） | Lock | Protect，k = 4 | k = 2 |
| endfield_npr 叠加贴片材质（`overlays[].card`） | Lock（整个材质） | Lock | Lock（贴片很小，锁住几乎不花三角形） |
| 头发（`SpecularAtlas` 的 HAIR 类，或弹簧链骨骼主导的顶点） | k = 4，开口边界 Protect | k = 4，开口边界 Protect | k = 2 |
| 材质边界（`Layout` 每个材质一份顶点副本，所以就是属性接缝） | Protect | Protect | Protect |
| 同一材质内的 UV 接缝 | 不跨 | 允许跨（Permissive） | 允许跨 |
| 蒙皮权重 | 多姿态属性（2.5 第 2 条） | 同左 | 同左 |
| 描边（`outline_triangles`） | 由本档三角形表经「源顶点 → 外壳副本」映射生成，绕序反向 | 同左 | 丢弃（远处 1 像素的线） |

几点说明：

- **脸**：佩丽卡的面部 SDF 和高光由头部基底驱动（`NPR` 的 `headForward/headRight/headUp`），不依赖面部三角形。但脸上的着色边界很敏感，所以 LOD1 锁住。
- **到 LOD3 时角色只有约 50 像素高**（2.7），整张脸不过几像素，不再锁脸。
- **头发的外轮廓**：
  - 二次误差衡量的是几何偏差。几何偏差是轮廓偏差的上界，但对卡片发束偏保守。
  - 导入时再做一次**轮廓验证**：原模和每一档在 2 个姿态 × 26 个方向下做 256² 软件光栅，算剪影边界的最大距离，得到 σ，存进 `lod.json` 给选档用。
  - 某档的 σ 超过 2×ε 时，在超标的区域把 k 翻倍，再简化一次。【推断】Java 光栅这一步约 1 s。
- **三角形顺序**：每档输出前按邻接重排三角形，让 YSM 的 `PairTriangles` 尽量配成 quad。佩丽卡原模的配对率是 94%（58,633 个 quad 对 110,213 个三角形），LOD 也要保持这个水平，不然输出顶点会多出一倍。

### 2.7 选档：屏幕空间误差、倍镜、迟滞、切换预算

**选档公式。** 每档存的是相对模型高度的误差 e_i = max(ε_i, σ_i)。角色在屏幕上的高度：

  `h_px = H_bb · P11 · H_viewport / (2 · d)`

  - `H_bb`：实体包围盒高。
  - `d`：相机到包围盒中心的距离。
  - `P11`：**这一帧主画面的投影矩阵**的 [1][1] 元，即 1/tan(FOV/2)。
  - `H_viewport`：视口高度（像素）。

选满足 `e_i · h_px ≤ τ` 的最粗一档，τ 默认 1 像素（可配置）。

**换算成距离**（1080p、FOV 70°、1.8 格高的角色，取佩丽卡的 e = 0.1% / 0.5% / 2%）：

| 档 | 允许的最大屏幕高度 | 起用距离 | 4 倍镜下 |
|---|---:|---:|---:|
| LOD1 | h_px ≤ 1000 | > 1.4 格 | > 5.6 格 |
| LOD2 | h_px ≤ 200 | > 6.9 格 | > 28 格 |
| LOD3 | h_px ≤ 50 | > 28 格 | > 112 格 |

几何误差不等于着色误差，卡通渐变对法线敏感，所以初始配置偏保守：

- τ = 0.5 像素，距离阈值相应翻倍。
- 与 endfield_npr 的 FULL 档（屏高 ≥ 10%）对齐时，FULL 档最多用 LOD1。

**投影矩阵从哪里取：**

- 选档放在 YSM 自己的 `LevelRendererMixin` 头部，`scheduleAll` 之前（`YSM/mixin/client/LevelRendererMixin.java:20-29`）。那里拿得到 `renderLevel` 的投影矩阵参数。
- 这是 `GameRenderer` 用 `getFov(…, true)` 算出来的世界投影，包含 Forge `ViewportEvent.ComputeFov` 的结果。
- TACZ 1.2.0 的倍镜正是在 `ComputeFov` 里按 `aimingZoom × 瞄准进度` 改世界 FOV（GitHub `MCModderAnchor/TACZ` 1.20.1 分支 `client/event/CameraSetupEvent.java`，与 `~/ysm/bfm-port/libs/tacz-forge-1.20.1-1.2.0-hotfix.jar` 的类名和 refmap 对得上）。
- 镜片和热成像是对主画面的屏幕空间后处理，不是另一台相机。【推断】据源码判断，没有对 jar 做字节码比对。
- 所以**开镜后 P11 自动放大，远处角色自动升档**，不需要专门的 TACZ 接口。望远镜同理。

**禁止使用的投影：**

- Iris 阴影 pass 的正交投影。
- GUI 和纸娃娃的投影。这些状态不参与选档，永远用 LOD0。
- endfield_npr 在主 pass 的事件里读 `RenderSystem.getProjectionMatrix()`（`NPR/CharacterCapture.java:84`），同样已经含缩放，两边一致。

**迟滞与切换预算：**

- **迟滞**：与 `NprLod` 相同的做法。在某一档时，边界移到 τ·(1−h)，否则在 τ·(1+h)，h = 0.15。
- **最短停留**：变粗之前至少停 15 帧，变细立即生效。
- **切换预算**：每帧最多 4 个角色变细，按屏幕高度从大到小排。变粗不限。
  - 开镜 0.2 s 内 FOV 变到 1/4，很多角色会同时要求升档。每次升档都要立刻重新蒙皮一次（NPR 的复用失效、slot 重分），预算防止这一帧出尖峰。
  - 窄视场下大多数角色在视锥外，不蒙皮，所以实际总成本有上限。
- **时机**：选档在 `scheduleAll` 之前做，当帧生效，没有 NprLod「本帧决定、下帧生效」的一帧延迟。
- **本地玩家永远用 LOD0**：第一人称身体、F5、镜子（若有）都是近景。
- **同一帧所有 pass 用同一档**：阴影 pass 和主 pass 共用同一个原生状态（1.4）。若两个 pass 用不同的档，每帧要切两次模型，重建两次 schedule。

### 2.8 与弹簧、形态键、表情、fitbox、endfield_npr 的交互

| 对象 | 交互 | 处理 |
|---|---|---|
| 弹簧 | 各档弹簧表相同；切档时按指针判断会重置 | P0 的弹簧连续性补丁（2.1 第 6 条）。到 LOD3 时，被冻结的链的骨骼可以把权重并入父骨，见 3.3。默认不做，保留轮廓。 |
| 形态键 | 驱动骨与骨骼表不变，`UpdateMorphWeights` 照常工作（`morph.cc:16-31`）；幸存顶点的增量原样有效 | LOD1 锁住受形态键影响的顶点，LOD2/3 用系数保护（2.6）。形态键增量是否进了二次误差：多姿态属性里加入「最大幅度的 2 个表情」作为额外姿态。 |
| 表情（FaceLayer、眨眼、视线） | 全是骨骼属性或形态键，与网格档无关 | 无 |
| fitbox 服务端 | 只读 `main`（`FB/ysm/YsmServer.java:70-92`），用静止几何和片段第 0 帧拟合 | 无影响（`main` 不变） |
| fitbox 客户端上报 | 只读本地玩家 `mainRenderData` 的 `BonePoseView`（`FB/client/ClientFits.java:110-186`） | 各档骨骼、姿态逐位相同；本地玩家又永远用 LOD0。无影响 |
| fitbox 本地拾取（远程玩家） | 读每个被画玩家的骨骼姿态（`ClientFits.java:247-299`） | 姿态与档无关。无影响 |
| NPR `CharacterCapture` slot | 切档时顶点数变化，`slot.valid` 失效，重新蒙皮；cell 不够就重分（`NprRenderer.java:168-191`） | 本身正确。切换预算限制每帧的次数。可以优化成「cell 按 LOD0 的大小分配」，避免拉近时重分 |
| NPR 动画 LOD mixin | 条件 `getModel() != model.getModel()`（`GeoModelStateMixin.java:102`） | 方案 A 对外发布的仍是基模型，条件不变。切档那一帧要强制 extract：mixin 再比一下 `getLodLevel()` |
| NPR 复用蒙皮（`pose_now·pose_at_skin⁻¹`） | 复用的是旧档的蒙皮 | 切档就让复用失效，即上一行的强制 extract |
| `CpuMesh.cardRuns`、自阴影包围盒 | 按 UV 扫描，按顶点数缓存（`CpuMesh.java:51-79`） | 自动重扫。贴片材质每档都锁住，扫得到 |
| 深度预通道、自阴影、描边 | 都画同一个 cell | 一致 |
| Iris 阴影 | 同一档 | 远处的阴影本来就粗，不单独降档（P3 可选） |
| 第一人称手臂 | `arm` 几何，不参与 | 无 |
| `getFrameRateLimit` | 只看距离，开镜后 64 格外的目标仍按 30 fps 播动画（`AnimatableEntity.java:381-403`） | P3：改成按屏幕高度，与选档共用同一个 `h_px` |

### 2.9 成本：内存、传输、烘焙、导入

- **native 内存（每个模型 × 贴图变体）**：
  - 佩丽卡全模 8.5 MB（实测 `CapacityBytes`），由 SIMD 块（约 208 B/块，SSE 宽度）和 quad（24 B）主导。
  - 三档约占全模的 0.36 + 0.15 + 0.10 ≈ 0.61 倍，**约 +5 MB**。【推断】块数随顶点数下降，按实验里各档用到的顶点数估算。
  - 这份内存由所有用这个模型的玩家共享，不随玩家人数增加。
- **烘焙缓存**（磁盘 `bake/`）：同比例增加。
- **传输**：
  - 旁挂条目只有索引：三档合计约 0.61 × 110k ≈ 6.8 万个三角形，约 20 万个 varint，原始 ≈ 0.6 MB，压缩后【推断】约 0.3–0.4 MB。
  - 对比 `main` 的 5.1 MB，增加 6–12%。ysmguard 加密整块数据，旁挂条目跟着一起加密。
- **加载时烘焙**：每个模型 × 贴图变体约 +60 ms（下采样实测，每档 17–28 ms），在 worker 线程上，之后走缓存。
- **导入**：【推断】Java 简化每档 0.3–0.5 s，加轮廓验证约 1 s，每个模型 +2–3 s。现在 GenNpr 跑一次佩丽卡约 10 s。
- **GPU**：NPR 的 cell 按顶点数分配，远处角色的 cell 变小，页可以装下更多角色。

### 2.10 预期收益

**假设：**

- 场景沿用 endfield_npr README 的 20 人基准：4 个 FULL（屏高约 0.15）、8 个 REDUCED（约 0.05）、8 个 MINIMAL（约 0.02）。
- 全是佩丽卡级模型。
- τ = 0.5 像素：0.15 → LOD1，0.05 → LOD2，0.02 → LOD3。
- 蒙皮成本正比于输出顶点，输出顶点正比于三角形（配对率不变）。
- 以本机实测的 3.96 ms/全模为单位，不计 NPR 的蒙皮复用（复用与 LOD 相乘，比例不变）。

| | 每帧蒙皮 + 写顶点 |
|---|---:|
| 现在 | 20 × 3.96 = 79 ms |
| LOD 后 | 4 × 0.363 × 3.96 + 8 × 0.152 × 3.96 + 8 × 0.102 × 3.96 = 13.8 ms（**−83%**） |
| τ = 1 像素（FULL 档也落到 LOD2） | 10.5 ms（**−87%**） |

- **上传**（FULL 档的 `CpuMesh` 拷贝、映射页写入）和 **GPU 顶点量**按同一比例下降。
- **GPU 光栅**：远处的小三角形少了，2×2 像素块的浪费也少了。【推断】未测。
- **薇薇安类模型**：比例接近佩丽卡。**柚叶**要等 Permissive 落地，否则 LOD2/3 只能到 50% 左右，收益约减半。

---

## 3. 优化 4：人群的骨骼 LOD

### 3.1 现状与成本

每次 extract 的骨骼成本分三块：

- **走骨骼树**：294 根骨，含形态键和 schedule，12–21 µs。
- **弹簧**：固定步 1/120 s（`spring_physics.cc:29`）。每步对每个关节做积分、链碰撞（最多 3 遍，`:45`），再对关节的子树做 aim。另外每步把所有骨骼的动画姿态插值一遍（`:515-539`），最后再整体 aim 一次（`:561-573`）。每步的边际成本 22–28 µs。
- **Java 的 `tickAnimation`**：Molang、控制器、逐骨插值（`YSM/geckolib3/core/processor/AnimationProcessor.java:84-219`）。已经被 YSM 自己按距离限频。没有测。

**endfield_npr 的动画 LOD 省不掉弹簧积分。**

- 被跳过的帧，时间会累进下一次 extract：`dt` 截到 0.1 s（`spring_physics.cc:22`），按 1/120 s 一步全部补做（`:503-507`）。
- 间隔 2 或 4 时，每秒的固定步数不变。间隔 8（60 fps 下 0.133 s）时被截掉 25%，代价是那部分时间被「吃掉」。
- 实测：一次 8 步的 extract 是 236–246 µs，四次 2 步的 extract 是 4 × 64–83 µs。动画 LOD 在弹簧上只省约 20%，省的是每次 extract 的固定开销。
- 它省下的是 Molang 之外的骨骼树、蒙皮和上传。**弹簧要靠骨骼 LOD 本身来省。**

**屏幕外的角色更明显。**

- `scheduleAll` 对每个已加载的 animatable 都 extract（`AnimationParallelTicker.java:76-108`），没有距离或可见性过滤。
- 这些角色不蒙皮，弹簧就是它们的主要成本：50 个在屏幕外的玩家 × 64–83 µs ≈ 3–4 ms worker CPU/帧。
- NPR 的屏幕外间隔 8 只能把这个数压掉约 25%。

### 3.2 可以挂在哪里

| 挂点 | 可行性与依据 | 结论 |
|---|---|---|
| 骨骼属性：缩放 0 或 `children_hidden` | 会跳过整棵子树（`model_state_impl.inc:152-156`、`:202-204`），但网格也一起消失。被跳过的关节，其 meshlet 会塌成空 quad（`render_impl.inc:261-266`）。弹簧也会跳过未访问的关节（`spring_physics.cc:383`） | 不能用来降级，只能隐藏 |
| `nExtract` 带骨骼掩码 | 需要改 JNI 签名，且「哪些骨骼不算」的语义不清。走骨骼树本身只有 12–21 µs，可省的很少 | 否 |
| **`NativeModelState` 上按链设置模式**（新增 JNI `nSetSpringModes(long, byte[])`，存在 native `ModelState` 里，extract 时读取） | native 侧只动 `StepSprings`。`SpringJoint` 已经带 `chain` 编号（`baked_model.h:232-242`），链的数量来自 `chain_offsets`。不改 `nExtract` 签名，只新增一个 JNI 注册，jar 和 native 同时发布 | **推荐** |
| `PhysicsFrame` 加字段 | 长度在 JNI 里校验成 21（`lib-jni/src/render/model_state.cc` 的 `kFloatCount` 检查），改它就是改 ABI；而且是逐次传的，模式变化其实很少 | 否 |
| Java `AnimationProcessor` 按骨骼跳过通道求值 | 通道表达式可能有 Molang 副作用（变量赋值）；`getBone` 和 Molang 查询会读到过期值。收益未测，YSM 已经按距离限频 | 不在本期，P3 测过再说 |
| proto 加每链字段 | 和 2.2 一样要改两侧生成的代码 | 否。链分类放进旁挂条目 `lod.json` |

### 3.3 设计

**导入时给链分类，写进 `lod.json`，按 `SpringBones.chains` 的下标：**

- **一级（主体）**：主发束、裙摆主体。按「链长 × 受它驱动的蒙皮表面积」排序，取累计覆盖 80% 受驱动面积的那些链。
- **二级（次要）**：其余的发束、衣摆。
- **三级（饰品）**：缎带、挂件、耳饰。按名字启发（ribbon、acc、飾り、リボン 等）加上「面积小于 1%」。

MmdPhysics 转换来的链、ClothColliders 识别出的裙摆链，都在导入器里有名字和骨骼，能直接分类。

**每条链的模式：**

| 模式 | 行为 | 每步成本 |
|---|---|---|
| FULL | 现状（1/120 s，3 遍碰撞） | 1 |
| HOLD | 不积分、不碰撞。进入时记下每个关节的尾点在**关节动画姿态局部系**里的方向；之后每次 extract 只做一次变换加 aim，形状保持，跟着父骨刚性移动。退出时 `current = previous = 当前尾点`，速度为 0，**两个方向都不跳变** | 0（每次 extract 只付一次 aim） |
| COARSE（可选） | 每 2 个固定步积分一次，步长加倍，刚度、重力、阻尼按步长换算（代码里已经是按步长写的），碰撞 1 遍 | 约 0.5 |

**不能把冻结的尾点留在世界系。** 尾点每次 extract 都会按 `frame.motion` 当作世界固定点变换（`spring_physics.cc:273-276`）。若 HOLD 把尾点留在世界系，角色走开时发束会被拉向原地。所以必须存在关节的局部系里。

**按屏幕高度 s（与 2.7 同一个 `h_px`，即同一个含缩放的 P11）分级：**

| 级别 | 条件 | 一级链 | 二级链 | 三级链 |
|---|---|---|---|---|
| S0 | 本地玩家；或 s ≥ 0.12 | FULL | FULL | FULL |
| S1 | 0.06 ≤ s < 0.12 | FULL | FULL | HOLD |
| S2 | 0.03 ≤ s < 0.06 | FULL | HOLD | HOLD |
| S3 | s < 0.03，或上一帧没有被画（屏幕外） | HOLD | HOLD | HOLD |

- 阈值与 endfield_npr 的 `animLodSizes`（0.12/0.06/0.03）对齐，同样用 h = 0.15 的迟滞。
- 有 COARSE 时，S2 的一级链用 COARSE。
- 只在模式改变时才调 `nSetSpringModes`。

**P0 先做的一个与 LOD 无关的小优化：** 每个固定步只插值和还原弹簧会读到的骨骼，即关节骨和碰撞体骨。

- 佩丽卡是 294 根里的 69 根。
- aim 写进非关节子孙的值在步与步之间不会被读，步结束后整体还原（`:556-558`），所以结果逐位不变。
- 本次用原型实测：2 步 −16%（64 对 76–83 µs），8 步 −19%（192–194 对 236–243 µs）。每个人都受益，本地玩家也一样。
- 注意：插值不是每步的大头（只占约 20%）。积分、碰撞和 aim 才是，所以按链降级才是主要手段。

**LOD3 与网格的联动（可选）：**

- 被 HOLD 的二、三级链所驱动的顶点，在 LOD3 网格里可以把权重并入链根的父骨。这些链就连 aim 都不用做，它们的骨骼也不再是蒙皮关节。
- 代价是切到 LOD3 时，链会从被冻结的形状回到静止形状，约几个像素的跳变。
- 默认关闭。

### 3.4 本地玩家与 fitbox

- **本地玩家永远是 S0，几何永远是 LOD0。** 和 NPR 的 `throttle = entity != mc.player` 一致，也和 YSM 的限频规则一致（`AnimatableEntity.java:382-383`）。
- **fitbox 服务端**：拟合时排除了松散和弹簧骨骼（`FB/core/Fitter.java:81-112`，`FB/core/Rig.java:19-58`）；上报的是本地玩家**自己客户端**的核心骨骼姿态（`ClientFits.java:110-186`，20 Hz，只读 `mc.player`）。所以**显示端对远程玩家的任何降级，都不会改变 fitbox 上报和服务器使用的姿态。**
- **fitbox 的本地拾取**：读远程玩家的姿态（`ClientFits.java:247-299`），只用核心骨骼。HOLD 只影响弹簧骨，所以也不变。
- **约束**：骨骼 LOD 不许动核心骨骼和定位器骨骼，也就是本设计不碰骨骼树本身（3.2）。

### 3.5 与 endfield_npr 动画 LOD（`GeoModelStateMixin`）的关系

- 两者正交，可以叠加：
  - 动画 LOD 决定**多久** extract 一次。
  - 骨骼 LOD 决定每次 extract **算多少条链**。
- 动画 LOD 间隔为 k 时，FULL 链要补 k 帧的固定步，HOLD 链一步都不用补。远处角色的这部分节省正好是动画 LOD 漏掉的那块（3.1）。
- **屏幕外**：NPR 每 8 帧 extract 一次，YSM 侧的骨骼 LOD 把这些角色设成 S3。合起来，屏幕外角色的 extract 只剩走骨骼树的 12–21 µs，每 8 帧一次。
- **分级来源**：屏幕高度由 YSM 侧统一算（2.7 的选档器），NPR 可以直接读，不必各算各的。没有 NPR 时 YSM 自己也能工作。
- **mixin 要改的地方**：`skip()` 的条件里加上「LOD 档变了就不跳过」（2.8）。

### 3.6 倍镜和缩放视角

- **倍镜**：P11 取自这一帧主画面的投影，含 `ComputeFov` 的缩放（2.7）。所以开镜后，远处目标的屏幕高度按倍率放大，几何档和弹簧档都自动升上去。
- **动画 LOD**：NPR 的同样用 `m11`，开镜后的远处目标也会升到每帧更新。
- **剩下的缺口：YSM 自己的 `getFrameRateLimit`**。它只看距离（>64 格 30 fps），狙击镜里 100 格外的目标仍按 30 fps 播 Molang 动画。P3 改成用同一个 `h_px` 分级（约 0.5 人日）。
- **升档尖峰**：靠 2.7 的切换预算处理。

### 3.7 工作量、风险、收益

**工作量**（人日）：

| 项 | 工作量 |
|---|---:|
| native HOLD 模式加 `nSetSpringModes` 加测试 | 1.5 |
| P0 插值范围收窄 | 0.5（含在 P0 里） |
| Java 选档与调用 | 0.5 |
| 导入器链分类 | 0.5（与 P1 的旁挂条目一起） |
| 验证 | 1 |
| COARSE（可选） | +1.5 |

**风险：**

- HOLD 进出时姿态是连续的，但运动不连续：从 HOLD 回到 FULL 时速度为 0，发束会「醒来」。只发生在远处，加迟滞。
- 链分类可能误判，例如把主发束当成饰品。好在误判的后果只是远处的链少摆动。`humanoid.json` 可以按链覆盖分类，沿用 `cloth_colliders` 开关的写法。
- 需要 native 发布，Windows DLL 和 Linux so 都要带。

**收益（假设：佩丽卡级，60 fps 每帧 2 步，实测数字）：**

| 情形 | 每次 extract |
|---|---:|
| 现在 | 64–83 µs |
| S3（全部 HOLD） | 【推断】约 15–25 µs（走骨骼树 12–21 µs，加 63 个关节各一次 aim），−70% 左右 |
| S2 | 介于两者之间，取决于一级链占多少关节 |

- 50 个屏幕外玩家：每帧约 3–4 ms 的 worker CPU 降到约 1 ms。
- 人群中可见的远处角色：每人每帧省约 50 µs。
- 这些都在 worker 线程上，不在渲染线程上，所以对帧时间的影响要看 worker 是否是瓶颈。【推断】在 8 个以上 worker 的机器上，几十个角色时通常不是瓶颈。所以**这一项排在几何 LOD 之后**。

---

## 4. 推荐计划

| 阶段 | 内容 | 工作量 | 依赖 | 验收 |
|---|---|---:|---|---|
| **P0** native 小补丁（随下一个 DLL 和 so 一起发布） | ① 弹簧连续性：按弹簧表指纹判断是否重置，替代按指针判断（`spring_physics.cc:262-266`）。② 每步只插值和还原关节骨与碰撞体骨（实测 −16% 到 −19%）。③ 测试：两个弹簧表相同的模型交替 extract，尾点连续；插值收窄前后逐位一致 | 1–1.5 | 无 | `ysm-test` 通过；交叉构建，导出符号与 JNI 注册数不变 |
| **P1** 几何 LOD | ① Java 简化器（移植 meshoptimizer 1.3 的 attributes / permissive / lock / protect / prune，加区域系数和多姿态属性），对 Python 版 meshoptimizer 对拍。② 导入器：2.6 的约束、三档按误差截断、轮廓验证、描边映射、写 `mesh_lod`，REVISION 升到 23，并与其他 agent 协调。③ 客户端：加载旁挂条目，烘焙兄弟 GeoModel，`GeoModel.lods`，`GeoModelState.lodLevel` / `getGeometry()`。④ 选档器：`LevelRendererMixin` 头部取投影；τ、迟滞、停留、切换预算；本地玩家和 GUI 用 LOD0；配置项。⑤ endfield_npr：mixin 加档位判断，cell 按 LOD0 分配，README 加一节 | 8–10 | P0 ① | 四个模型导入成功；每档三角形比例与 2.5 的表相差 ±10%；轮廓误差 σ ≤ 2ε；`debugClones=20` 下 capture 时间下降 ≥ 70%；在 1080p 下，距离 1.4、7、28 格处各截一张图，切档前后逐像素差异 ≤ 1 像素宽的边 |
| **P2** 骨骼（弹簧）LOD | ① 导入器链分类，写进 `lod.json`。② native HOLD 模式加 `nSetSpringModes`。③ Java 分级（S0–S3，与 P1 共用 `h_px`）。④ 屏幕外 → S3 | 3–4 | P0、P1 的旁挂条目 | 50 个屏幕外克隆：worker extract 的 CPU 时间下降 ≥ 60%；HOLD 进出前后各一帧的尾点无跳变（native 测试）；本地玩家与 fitbox 上报逐位不变 |
| **P3** 可选 | ① `getFrameRateLimit` 按屏幕高度（倍镜）。② COARSE 模式。③ 如果 A 的 schedule 重建或阴影 pass 成问题，改走方案 B。④ 阴影 pass 单独降一档 | 视需要 | P1、P2 | 实测再定 |

**顺序理由：**

- P0 风险最小，而且是 P1 的前提。
- P1 的收益最大：蒙皮 −80% 以上。
- P2 的收益在人多、屏幕外角色多的服务器上才显著，而且它的链分类和 P1 共用同一个旁挂条目。

---

## 附录 A：实验方法

- **native 计时**：
  - `/tmp/lodbench/bench.cc` 是临时代码，用完即删，不进仓库。
  - 用 `nativebuild/build/native-release` 的编译参数（clang 21、-O3、ThinLTO、libc++）链接同一套静态库（`libysm-gfx.a` 等）。
  - 读 `GenNpr` 产出的 `pelica.modeldata.bin` 里的 `main` 条目，用 2×2 不透明白贴图 `BakeModel`。
  - 根骨按正弦摆动；`PhysicsFrame` 的 dt 分别取 1/2/8 个固定步，并带一个向前平移。
  - 每项预热 50 次，计时 2000 次。`Render` 写 Vanilla 顶点格式，单位矩阵，使用默认的并行执行器。
  - 机器：8 vCPU QEMU，SSE4.1，没有 AVX2。两轮结果的范围已写在 1.6。
- **插值收窄原型**：把 `spring_physics.cc` 复制一份，只改步内插值和还原的循环，链接时替换库里的同名目标文件。同一台机器上原版和原型交替各跑两轮。
- **简化实验**：
  - `/tmp/lodbench/simplify_exp.py`，`pip install meshoptimizer`（0.2.30a0，内核较旧），用 ctypes 直接调 `meshopt_simplifyWithAttributes`。
  - 属性为法线（权重 0.5）和 UV（权重 1）。
  - 目标比例 1%，误差上限取 `simplify_scale` 的比例，所以「误差」是相对模型最大尺寸（站立人形即身高）。
  - 脸：头部子树里、非头发骨骼主导的顶点。接缝：位置重合的顶点副本。形态键顶点：任一形态键增量 > 1e-4。
  - 四个模型的 `main` 由临时工具 `GenGeo`（调 `HumanoidImport.load(...).main()`）从装好的 jar 导出：
    - `~/mc/server/ysm/custom/vrm1_sample/vrm1.vrm`
    - `~/ysm/models/vivian/薇薇安皮肤.pmx`
    - `~/ysm/models/yuzuha/柚叶泳装.pmx`
    - 佩丽卡 `~/ysm/pelica-fix/pelica_springs/pelica.vrm`
- **源模型统计**（导入前，供参考）：

| 模型 | 顶点 / 三角形 / 材质 / 骨骼 / 形态键 | 弹簧链 / 关节 |
|---|---|---|
| 佩丽卡 VRM | 57,204 / 74,144 / 12 / 313 节点 / 0 | 25 / 88（含 25 个尾节点） |
| vrm1_sample | 24,951 / 36,470 / 13 / 171 节点 / 57 个形态目标、18 个表情 | 22 / 79 |
| 薇薇安 PMX | 30,121 / 36,019 / 17 / 315 / 49 | 刚体模式 0/1/2 = 47/122/12，160 个关节 |
| 柚叶 PMX | 32,451 / 41,257 / 19 / 347 / 43 | 47/98/48，195 个关节 |

  - 导入后顶点变多（材质副本、描边外壳）：佩丽卡 77,948，vrm1 44,791，薇薇安 46,097，柚叶 59,449。
