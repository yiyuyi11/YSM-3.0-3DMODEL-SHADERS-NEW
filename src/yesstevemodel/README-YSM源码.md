# YesSteveModel 源码（上游分支）

本目录是 [YesSteveModel](https://github.com/Els-y/YesSteveModel) 的 v18 开发版源码，作为本项目补丁的基础。

上游采用 **Apache License 2.0**，与本仓库一致。上游的 `LICENSE`、`NOTICE.md` 和 `README.md` 原样保留在 `yesstevemodel/` 目录下。

---

## 为什么包含源码

本项目要改 YSM 的 parser 类（`src/YSM补丁/`），改动的对象是这里的类。把源码放在一起，才能：

- 对照原始实现，看清改了什么
- 自行编译出带补丁的 jar，不必依赖预编译产物
- 上游更新时做 diff

---

## 排除了什么

| 排除项 | 大小 | 原因 |
|---|---|---|
| `src/main/resources/assets/ysm/builtin/` | 57.6 MB | 内置模型资产。**许可不是 Apache 2.0**，每个模型在 `ysm.json` 里用自己的 `metadata.license` 声明。不属于本项目，也不在本仓库授权范围内 |
| `src/main/resources/META-INF/native/ysm.dll` | 19.2 MB | 预编译的 Windows 原生库。二进制，不适合进源码仓库 |
| 内置模型里的音频（`.ogg`） | 11.5 MB | 属于上面那批内置模型 |

保留 1532 个文件、10.7 MB：全部 Java 源码（937 个）、shader、molang 脚本、协议定义、verification 测试，以及构建所需的 Gradle 配置。

---

## 编译

```bash
cd src/yesstevemodel
./gradlew shadowJar
```

产物在 `build/libs/`。

**注意**：`local.properties` 没有包含（属于本机配置）。需要自己创建：

```properties
ysm.native_path=<你的 ysm.dll 路径>
```

---

## 目录

```
src/main/java/            937 个 Java 源文件
src/main/resources/       shader、molang、协议定义
src/declaration/          声明式构建工具
src/buildTool/
src/verification/         480 个验证测试
buildSrc/                 构建逻辑
gradle/                   wrapper
```

---

## 本项目在这个基础上的改动

见 `../YSM补丁/`：

| 类 | 改动 |
|---|---|
| `HumanoidMotion` | 16 个默认动作，步态参数按真实数据标定 |
| `HumanoidImport` | 动作数据注入、面部层接入 |
| `RawModelAssembler` | 跳过 NaN/无限顶点 |
| `EndfieldLocomotion` | **新增**，动作数据驱动与 IK 重定向 |
| `FaceLayer` | **新增**，程序化面部表情 |
| `HeldItems` | **新增**，按物品类型握持 |
| `RangedUse` | **新增**，弓弩姿态 |
| `ToolSwings` | **新增**，工具挥动 |
| `SpecularAtlas` | **新增**，高光图集 |
| `patch/OffhandItemPatch` | **新增**，副手物品第三人称显示修复 |

---

## 上游许可

```
Apache License, Version 2.0
Copyright (c) YesSteveModel contributors
```

完整条款见 `LICENSE`。

内置模型资产的许可声明在各自的 `ysm.json` 的 `metadata.license` 字段，`metadata.authors` 保留作者归属。**这些资产没有包含在本仓库。**
