# YesSteveModel v13 公开交付（2026-11-19）

本目录提供 v13 的源码、构建脚本、工具、配置和演示截图。截图中的角色模型及角色贴图需自备。

带内置模型的 YSM JAR 需自备。YSM 补丁和构建入口位于 [源码/YSM补丁](源码/YSM补丁)。
v13 的细节见 `文档/佩丽卡-YSM全流程技术文档.md` 第 24 节；v11、v12 的细节见第 21～23 节。

## v13 相对 v12 的改动

| 方面 | 改动 | 涉及文件 |
|---|---|---|
| 雨水与湿身 | 按游戏角色 shader 逐行还原：脸上往下流的水痕、皮肤和头发上的水珠、衣服湿斑和水痕、湿身变暗与高光。图案贴住身体，跟着动作走。淋雨、躲雨后变干、蹚水湿到水面、下水全湿 | 渲染模组 0.8.5 |
| 皮肤 | 明暗交界的红色层减半（`skinRampTint` 0.5） | 渲染模组 |
| 眼睛 | 眼球只保留眼皮阴影（`eyeShadow` 0），虹膜下不再有团状阴影；白天也有一点虹膜自发光（`eyeDayGlow` 0.05） | 渲染模组 |
| 自定义动作 | 模型文件夹里放 `animations/main.animation.json`（第三人称）、`animations/fp_arm.animation.json`（第一人称），导入时同名覆盖生成的动作、新名字追加 | YSM jar |
| Blockbench 动画插件 | 在 Blockbench 里给 3D 模型 K 第三人称和第一人称动画、画模型贴图和渲染贴图，导出到上面两个文件 | `工具/Blockbench动画插件/`、`工具/动作导出工具/`、YSM jar |
| TACZ 第一人称手部 | 按枪修正握把位置和手指（待机和战术换弹逐帧）；目前有 M4A1、AK47 | YSM jar、`config/ysm_tacz_fp/`、fpbench |

## 包里有什么

| 路径 | 内容 |
|---|---|
| `mods/` | `endfield_npr-0.8.5.jar`、`ysmguard-1.0.0.jar`、`ysm_fitbox-0.2.2.jar`、`battlefield-movement-1.7.24-forge-1.20.1-TACZ1.2.0.jar` |
| `config/endfield_npr/endfield_npr.properties` | 渲染模组的默认配置（可选；你已有的配置不要用它覆盖，新配置项会自动补上） |
| `config/ysm_tacz_fp/tacz/` | TACZ 第一人称手部数据：`m4a1.json`、`ak47.json` |
| `server-config/` | ysmguard、YSM 的服务端配置，与 v12 相同 |
| `工具/Blockbench动画插件/` | `ysm3d_animator.js`（Blockbench 插件）和「说明文档.md」 |
| `工具/动作导出工具/` | `ysmrig.bat` / `ysmrig.sh`：把模型导出成插件用的 `.ysmrig.json`（插件桌面版会自动调用） |
| `工具/npr_materials/` | 把导出的原版材质数据写进 VRM 的工具 |
| `工具/fpbench-第一人称K帧工作台/` | 第一人称 TACZ 手部离线工作台；新增 `profile` 命令，批量生成手部数据 |
| `文档/` | 全流程技术文档（第 1～24 节）等 |
| `源码/` | YSM 补丁、渲染模组（含雨水工具）、Blockbench 插件、动作导出、fitbox、BFM 移植、ysmguard、原生补丁、动作数据工具等，都已同步到这一版 |
| `截图/` | 游戏内效果对照图，图中角色模型及角色贴图需自备 |

## 安装

| 文件 | 服务端 | 客户端 | 说明 |
|---|---|---|---|
| `mods/ysm-3.0-dev-forge+mc1.20.1-pelica-motion.jar` | ✓ | ✓ | 需自备带动作叠加、骨架导出及 TACZ 手部修正的 YSM JAR |
| `mods/endfield_npr-0.8.5.jar` |  | ✓ | 删掉旧的 `endfield_npr-*.jar` 再放入 |
| 其余 3 个 jar | ✓ | ✓ | 与 v12 相同 |
| `config/ysm_tacz_fp/` |  | ✓ | 放进客户端 `config/`（用 TACZ 时） |
| Blockbench 插件 | — | — | Blockbench（建议 5.x 桌面版）→ 文件 → 插件 → 从文件加载 → `工具/Blockbench动画插件/ysm3d_animator.js` |

**配置**：旧的 `endfield_npr.properties` 会就地升级：只在 `rainTiling` 还是旧默认 2.25 时改成 5，你调过的其他值全部保留，新配置项自动加入。

## 新开关（`config/endfield_npr/endfield_npr.properties`，即时生效）

| 开关 | 默认 | 作用 |
|---|---|---|
| `rainEffect` | true | 雨水与湿身总开关 |
| `rainTiling` | 5 | 水珠密度（越大越小越密） |
| `rainDropSpeed` | 1.0 | 所有水珠、水痕的流速（含脸）；1 = 游戏原速（几乎不动），3～5 明显流动 |
| `rainFaceDripSpeed` | 1.0 | 脸上水痕在上面基础上的额外倍数 |
| `rainStrength` / `wetStrength` / `wadeStrength` | 1.0 | 即时雨量、累积湿度、水线湿度的倍数 |
| `rainSheen` | 1.0 | 湿身高光强度 |
| `rainWetReflection` | 1.0 | 湿皮肤、头发的水膜反光（我们自己加的近似） |
| `rainGlint` / `rainMatcap` | 1.0 | 水珠闪光、脸上湿反光 |
| `wetDrenchTime` / `wetDryDelay` / `wetDrySmoothTime` | 30 / 20 / 60 秒 | 变湿、开始变干、变干的时间 |
| `rainDebug` | -1,0,0,0,-1 | 测试用：`255,0,255,0,-1` = 强制大雨全湿 |
| `skinRampTint` | 0.5 | 皮肤明暗交界红色层，1 = 游戏原样 |
| `eyeShadow` | 0.0 | 眼球阴影，1 = 恢复明暗和遮蔽 |
| `eyeDayGlow` / `eyeNightGlow` | 0.05 / 0.15 | 白天 / 夜里虹膜自发光 |

YSM jar 的开关：JVM 参数 `-Dysm.taczFp=false` 关掉 TACZ 第一人称手部修正。

## 请在游戏里重点看

1. 雨水：流速、水珠大小、跟随身体；湿身反光强弱；进屋后变干、蹚水、潜水。
2. 眼睛：眼白和虹膜的亮度是否协调（夜里眼白可能显得偏亮）。
3. TACZ：M4A1、AK47 第一人称握把和换弹时的手指。
4. Blockbench 插件：桌面版能否导入模型、导出的动作在游戏里是否生效（`/ysm model reload`）。

## 已知限制

- 已在游戏里试过的只有雨水的流速、水珠大小、跟随身体和眼睛阴影。湿身反光、TACZ 手部数据、动作叠加、Blockbench 插件（只在网页版测过）都还没在游戏里验证。
- 雨水：雨遮挡只按头顶是否露天整体判断（游戏按像素）；眼睛、丝袜的湿身效果没有还原；积雪已移植但关着，缺积雪贴图 `T_actor_common_snow_01_M`。
- `rainWetReflection` 不是游戏原有的：游戏的湿身反光来自角色着色器之外的反射步骤。
- TACZ 手部数据只做了 3 个模型（pelica、aglina、aurora），其余模型用通用数据；换弹时仍有约 0.5 像素穿模。
- v12 的已知限制仍然有效（hairt 部件、叠加阴影占位、毛绒帽、趴下侧头、裙摆）。

## 回滚

- 只关某一项：用上面的开关。
- 整体回滚：换回更新前备份的模组文件。

## 版权

模型、贴图、动作、着色器数据归鹰角网络所有，仅供本地研究，请勿分发。
