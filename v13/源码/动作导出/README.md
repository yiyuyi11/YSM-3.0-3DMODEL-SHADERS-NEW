# animkit：YSM 3D 模型动画工具（导出端与游戏端）

这里是 animkit 中 YSM 模组一侧的工具。Blockbench 插件本身在 `tools/blockbench-plugin`。

- **RigExport**（在 YSM 模组 jar 里）：把导入型 3D 模型（VRM/PMX 等）导出成 `.ysmrig.json`，Blockbench 插件读取它，
  生成可以打关键帧的骨骼和蒙皮预览。
- **动画叠加**（在模组导入器里）：插件导出的用户动画，进游戏后叠加在自动生成的动作之上。

文件格式的完整说明在 `~/ysm/handoff/ysmrig-format.md`（英文）。

## 1. 导出 `.ysmrig.json`

一般由 Blockbench 插件自动调用。也可以手动运行（例如在网页版 Blockbench 里用，或者排查问题时）：

Windows：

```bat
ysmrig.bat "C:\...\.minecraft\mods\ysm-xxx.jar" "C:\...\.minecraft" "C:\...\模型文件夹" "C:\...\模型.ysmrig.json"
```

Linux / WSL：

```bash
./ysmrig.sh ~/.minecraft/mods/ysm-xxx.jar ~/.minecraft ~/模型文件夹 ~/模型.ysmrig.json
```

- 第 1 个参数：带 RigExport 的 YSM 模组 jar（本分支 `mod/build_jar.sh` 构建的 jar）。
- 第 2 个参数：`.minecraft` 文件夹，或者它下面的 `libraries` 文件夹。
- 第 3 个参数：模型文件夹，或者文件夹里的模型文件（`.vrm .pmx .pmd .xps .mesh`；游戏不导入 `.glb`，所以这里也不支持）。
  导出的内容总是游戏实际加载的那一份：
  - 有 `ysm.json` 时按它；
  - 没有 `ysm.json` 时取文件夹里最大的模型文件；
  - 和你选的文件不一致时，会打印一行 `note` 提示。
- 第 4 个参数：输出文件。
- Java：需要 Java 17 以上。`ysmrig.bat` 先找 PATH 里的 java，找不到就用 Minecraft 自带的
  `runtime\java-runtime-gamma`；也可以设置环境变量 `JAVA` 指定。
- 本地库：不需要任何设置，会自动从 jar 里解出 `ysm.dll` / `libysm.so`。如果要用别的本地库，设置环境变量
  `YSM_NATIVE_PATH`（绝对路径）。
- 速度：每个模型约 10 秒，内存约 0.75 GB。
- 退出码：
  - 0：成功；
  - 1：导入出错，原因打印在 stderr 的 `RigExport: ...` 行；
  - 2：参数不对；
  - 3：缺少库或找不到 java。

需要的库只有下面 10 个（在 `.minecraft/libraries` 下，版本不限）。直接用 `java -cp` 调用时，把它们和模组 jar 放进
classpath（Windows 用 `;` 分隔）：

```
com/google/code/gson/gson                    it/unimi/dsi/fastutil
org/joml/joml                                net/java/dev/jna/jna
org/apache/logging/log4j/log4j-api           org/apache/logging/log4j/log4j-core
org/apache/logging/log4j/log4j-slf4j2-impl   org/slf4j/slf4j-api
io/netty/netty-common                        org/apache/commons/commons-lang3
```

主类：`com.elfmcys.ysm.format.parser.RigExport <模型文件夹或模型文件> <输出.ysmrig.json>`

检查导出结果，并渲染一张正面预览图：

```bash
python3 check_rig.py 模型.ysmrig.json --render /tmp/预览
```

它会检查数组长度、权重、绘制分组和材质矩形，生成 `/tmp/预览_main.png` 和 `/tmp/预览_arm.png`。

## 2. 用户动画叠加（游戏端）

插件导出的动画放在**模型文件所在的文件夹**里：

| 文件 | 动画集 | 用途 |
|---|---|---|
| `animations/main.animation.json` | 第三人称 `main` | 待机、走路、轮盘动作、额外动作等 |
| `animations/fp_arm.animation.json` | 第一人称手臂 `fp_arm` | 第一人称下的手臂动作 |

- 规则：
  - 和生成动作**同名**的片段替换生成的那一个；
  - **新名字**的片段会加进动画集；
  - 其余生成动作保持不变。
- 想用别的路径，可以在 `humanoid.json` 里写（路径相对模型文件夹，写了就必须存在）：

  ```json
  {"animations": {"main": "我的动作/main.json", "fp_arm": "我的动作/fp.json"}}
  ```

- 生效：在游戏里执行 `/ysm model reload`，或者重启游戏。叠加文件算模型自身的文件：改了它，模型就会重新导入，同步给
  其他玩家时也会一起带上。
- 出错：YSM 原本会把写错的 Molang 悄悄当成 0 处理。叠加文件不会这样：只要有错，就整个模型导入失败，错误信息会写明文件、
  片段和表达式，例如：

  ```
  animations/main.animation.json: clip 'extra_wave', RightArm rotation at tick 0.0: Molang does not compile: ... in `math.sin(query.anim_time * 90`
  ```

  JSON 格式错误、缺少 `"animations"`、片段不是对象，也都会这样报错。
- 只对导入型模型生效（主模型是 `.vrm .pmx .pmd .xps .mesh`）。普通 Bedrock 模型本来就有自己的
  `animations/*.animation.json`，不受影响。
- 没有叠加文件的模型，导入结果和改动之前逐字节相同（见第 3 节的测试）。

## 3. 测试（离线，不启动 Minecraft）

```bash
test/run_tests.sh <master 构建的 jar> <本分支构建的 jar> ~/mc/client/libraries ~/ysm/efdata/models-patched-20261118 /tmp/animkit-test
```

每次只运行一个 JVM，每步约 20 秒，最大约 1.3 GB 内存。测试内容：

1. **无叠加时逐字节相同**：用 master 的 jar 和本分支的 jar，对 pelica、aurora 两个模型各做一次真实转换
   （`test/ImportDump.java`：RawModelImporter 采集加转换，和游戏导入一样），然后比较：
   - 模型哈希；
   - 清单；
   - 全部非玩家数据块；
   - 玩家 ModelData 的每个字节。
   唯一允许的差异在 endfield_npr 包内的 ZIP 时间戳：master 自己每次导入都会写入当前时间，用同一个 jar 导入两次
   也不一样。`test/compare_modeldata.py` 只放过这些字段。
2. **叠加**：复制一份 pelica，放入 `test/fixtures` 里的两个叠加文件：
   - main 集：覆盖 `idle`，新增 `extra_wave`；
   - fp_arm 集：覆盖 `parallel2`。
   `test/check_overlay.py` 检查：
   - 覆盖的片段换成了用户的骨骼；
   - 新片段已加入；
   - 其它片段和无叠加时的 SHA-256 完全相同；
   - 模型哈希变了（叠加文件属于模型的一部分）。
   同时检查 RigExport 输出的 `overlay` 字段。
3. **`humanoid.json` 指定路径**：结果和默认路径相同；指定的文件不存在时报错。
4. **写错的叠加文件**：Molang 写错和 JSON 写错时，游戏导入和 RigExport 都报错，并且报错里有文件名和片段名。
5. **RigExport 和 GenCheck**：导出 pelica、aurora 并用 `check_rig.py` 检查；用 `tools/ysmgen` 的 GenCheck
   编译全部生成的 Molang，要求 0 个失败。

## 文件

| 文件 | 说明 |
|---|---|
| `ysmrig.bat` / `ysmrig.sh` | 拼好 classpath，再运行 RigExport |
| `check_rig.py` | 检查 `.ysmrig.json`，渲染正面预览 |
| `test/run_tests.sh` | 全部离线测试 |
| `test/ImportDump.java` | 真实转换一个模型文件夹，输出模型哈希、各数据块和每个动画片段的哈希 |
| `test/compare_modeldata.py` | 逐字节比较两份 ModelData（只忽略 ZIP 时间戳） |
| `test/check_overlay.py` | 检查叠加结果 |
| `test/fixtures/` | 测试用的叠加文件（包括写错的） |

源码：
- `mod/src/com/elfmcys/ysm/format/parser/RigExport.java`：导出器；
- `AnimationOverlay.java`：叠加，供 `RawModelAssembler` 调用。
