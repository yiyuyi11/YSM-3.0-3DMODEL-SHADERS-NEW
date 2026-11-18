# YSM-3.0 3D Model Fork

[YesSteveModel](https://github.com/Els-y/YesSteveModel) 3.0 的一个 3D 模型 fork：在 YSM 3.0 上加入 VRM / GLB 人形模型导入、卡通渲染、程序化动作、手持物品与枪械适配、按模型形状的碰撞箱，以及服务端模型保护。

**本仓库只含源代码和配置模板，不含任何模型、贴图或其他美术资产，模型需自备。**

## 目录

| 目录 | 内容 | 许可 |
|---|---|---|
| `src/yesstevemodel` | YSM 3.0 Java 源码 fork：人形模型导入、动作、手持物品、表情 | Apache-2.0（见目录内 LICENSE / NOTICE） |
| `src/yesstevemodel-native` | YSM 原生库 fork：几何 LOD、弹簧骨骼 LOD | Apache-2.0 |
| `src/ysm-patch` | 打在 YSM 3.0-dev jar 上的补丁类和构建脚本 `build_jar.sh` | Apache-2.0 |
| `src/render-npr` | 客户端卡通渲染模组，在光影包之后绘制角色；`config-default/` 为默认配置 | Apache-2.0 |
| `src/fitbox` | 按模型实际形状计算碰撞箱和视角高度，支持趴下、潜行、游泳 | Apache-2.0 |
| `src/model-guard` | 服务端模型加密与归属保护 | Apache-2.0 |
| `src/bfm-forge-port` | Battlefield Movement 的 Forge 1.20.1 移植（趴下、滑铲等），适配 YSM 模型 | MIT（见目录内 LICENSE） |

## 运行环境

- Minecraft 1.20.1，Forge 47.4.x
- 渲染模组需要 Oculus 1.6 及以上，可配合光影包使用
- TACZ 1.2.0 可选：枪械握把和第一人称手部适配

## 构建

- Java 模组：各目录下的 `build.sh`（JDK 17）。
- YSM jar：`src/ysm-patch/build_jar.sh`，需要 YSM 3.0-dev 原版 jar 和由 `src/yesstevemodel-native` 编译出的原生库（Windows `ysm-lod.dll`、Linux `libysm-lod-linux.so`）。
- 原生库：CMake + Conan，见 `src/yesstevemodel-native` 内说明。

## 使用

1. 把构建出的 jar 放进 `mods/`（YSM jar 服务端和客户端都要；渲染模组只放客户端）。
2. 人形模型（VRM 0.x / 1.0、GLB）放进 YSM 的自定义模型目录 `ysm/custom/<模型名>/`，在 YSM 模型列表里选用。
3. 渲染参数在 `config/endfield_npr.properties`，默认值见 `src/render-npr/config-default/`；大部分参数修改后即时生效。

## 声明

仅供学习和技术交流。本仓库不分发任何第三方模型、贴图或游戏资源；如认为内容侵犯了您的权益，请提 issue，确认后会立即删除。
