# 原生补丁：防穿模（缓存版本 27，烘焙布局 4）

`native.patch` 是防穿模的原生改动：基准是 v13 发货原生库的源码（已经打了 `原生补丁-裙摆物理` 里的弹簧修正和 LOD），
`native-src/` 是改动后的文件本身。先按 `原生补丁-裙摆物理` 打好补丁，再打这个，就是 v14 包里 `ysm-lod.dll` /
`libysm-lod-linux.so` 的源码。

## 内容

- `modules/gfx/src/renderer/anti_clip.h`（新）：身体代理胶囊、头发碰撞体、关节 Backstop、蒙皮后顶点推出、
  裙摆链间连接（同层距离约束，碰撞后再算 4 轮）、链间连线碰撞点、腿同步、平滑推出、摆动上限。
- `spring_physics.cc`：Backstop、`LimitMotion`（转身 / 移动超过限速的部分随身体刚性移动，`inertia_turn_limit`
  rad/s、`inertia_move_limit` 模型单位/秒、`inertia_damping`）。
- 烘焙格式：`SpringBones` 新字段（身体胶囊、连接、惯性参数），`kBakedLayoutRevision` 4；序列化、校验同步修改。
- `modules/test/src/anti_clip_test.cc`：单元测试，以及用环境变量驱动的离线回放（`AntiClipTest.Replay`，
  `源码/离线工具/防穿模测试/harness.py` 调用）。

## 编译

与 `原生补丁-裙摆物理/README.md` 相同：Linux 用 clang-21，Windows 用 clang-cl-21 + xwin 交叉编译，静态 CRT，
在已有的 `build/native-release` 和 `build/windows-x64-release` 目录里 `ninja`。测试：`ysm-test`
（v14：251 通过，0 失败，16 跳过——本机缺 AVX2/AVX-512，以及两个需要环境变量的回放测试）。

## 对应的 Java 改动

`源码/YSM补丁/src/com/elfmcys/ysm/format/parser/AntiClip.java`、`ClothColliders.java`、`HumanoidImport.java`
（`REVISION = 27`）和 `humanoid.json` 的 `anti_clip*` 开关，见 `文档/佩丽卡-YSM全流程技术文档.md` 第 25 节。
