# GFX 高级能力

[返回基建总览](../infrastructure_cn.md)

主要头文件：`<gfx/bake/baked_model.h>`、`<gfx/bake/baked_serializer.h>`、`<gfx/math/pose_stack.h>`、`<gfx/renderer/model_state.h>`、`<gfx/renderer/render.h>`、`<gfx/renderer/schedule.h>`、`<gfx/renderer/color.h>`、`<gfx/renderer/bone_attribute.h>`、`<gfx/renderer/vertex/kind.h>`。

```text
model payload + RGBA texture
        -> BakeModel
        -> BakedModel
        -> SerializeBakedModel / ReadBakedModel
        -> ModelState::Extract
        -> Render
```

- `BakeModel` 是 renderer 的烘焙入口；`TryBakeModel` 仅检查可烘焙性。GeoModel 的 `SkinnedMesh` 烘焙为 `BakedModelMesh`：三角形按 owner bone 与透明分类配对成 quad，组织为只引用自身顶点的 meshlet，顶点按 joint 集合分成 SIMD lane block。`outline_triangles` 不分类、始终不透明，进入独立的 `outline` meshlet 分区（`BoneInfo::mesh_outline`），配对保持每个三角形的绕序。Morph target 按 lane 落到 block（`morph_offsets` / `morph_deltas`），驱动骨骼换算到烘焙序。
- `SerializeBakedModel` / `ReadBakedModel` 读写 baked cache。读取门面负责结构、索引、层级、有限浮点值和 SIMD 布局校验。
- `BakedModel::CapacityBytes` 统计固定结构、全部 vector capacity 与 SIMD 分区；`BakedModelSerializationBytes` 复用 serializer 的尺寸推导，输出预分配精确容量。两者只读 backing，可与运行提取并行；Java 存储域另计 metadata、codec 与 staging 验证。
- Java 运行消费者与派生存储共享不可变 geometry。消费者只撤强引用，最后引用消失后由 Cleaner 释放 wrapper；共享后的显式销毁被拒绝。native 函数的同步借用仍由调用方保活，私有构造失败保留唯一 owner 回滚。
- `PoseStack` 维护 pose/normal、uniform scale、tangent orientation 和 normal scale。`PushPose` 复制当前状态；`PopPose(depth)` 回到保存的深度。
- `EulerZYX`、`Mat4Mul` 和 `AffineMul` 提供 generic/ISA-tagged 路径，`SignBit` 提供位级符号判断。ISA overload 仅由 runtime 分派路径调用。
- `ModelState::Extract` 生成 renderer 状态并更新 schedule，同时记录本帧写入姿态的骨骼（`ModelPoseView::bone_visited`）；成功后才能交给 `Render`。Mesh joint 的蒙皮矩阵在 `RenderState::UpdateCommon` 中按 `joint_bone_indices` 计算，依赖未提取 joint 的 meshlet 输出退化 quad。
- 传入 `PhysicsFrame` 时，`Extract` 在姿态写完后按 `BakedSprings` 模拟一步弹簧骨骼（`StepSprings`，`spring_physics.cc`）：末端状态按 `ModelState` 保存，joint 按烘焙序（祖先先于后代）把子树绕 pivot 旋转到模拟末端，姿态与法线矩阵同步修改。传入空指针时清空末端状态，骨骼保持动画姿态；`ExtractSubtree` 不模拟。JNI `nExtract` 以 21 个 float（步长、重置、重力、列主序运动矩阵）传入，可为 null。
- 完整 `Extract` 还按驱动骨骼的原始动画属性计算 morph 权重（`UpdateMorphWeights`，`morph.cc`；X 平移或 1 − Y 缩放，截断到 [0, 1]）。`RenderState::UpdateCommon` 借用非零权重，mesh 输出只为有 morph 条目的 block 复制静止位置并加偏移后蒙皮。
- `Render` 按 `VertexKind`、runtime SIMD、render context 和 schedule 写入调用方 vertex buffer；容量不足或状态非法时返回 status。`RenderParameters::pass` 为 `kMain` 时输出除描边外的全部分区（`RenderSchedule::vertex_count`），为 `kOutline` 时只输出 `mesh_outline`（`RenderSchedule::outline_vertex_count`，偏移从 0 起），JNI 由 flags 第 18 位选择。需要区分恢复责任的调用者同时接收 `RenderResult`：完好 publication 的外部容量/宿主矩阵拒绝及纯组合溢出为 `kOutputRejected`，坏状态或无法证明生成完整性的失败为 `kStateInvalid`，完整生成才为 `kSuccess`；混合失败中坏状态优先。调用者负责宿主提交与对应 publication 撤销；原生函数返回前所有 worker 已结束。

`Pixel`、`Color`、`BoneAttribute` 和 `BonePose` 具有固定大小、对齐或字段布局，不能作为普通 struct 重排或 reinterpret cast。

`RenderSchedule`、`RenderState`、`VertexKindTraits`、cube buffer 和 worker-ready 协议只供 renderer 内部跨 translation unit 协作。新增 vertex layout 或调度模式仍通过 `ModelState` / `Render` 门面闭环。
