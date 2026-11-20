# YesSteveModel v14 公开交付（2026-11-20）

本目录提供 v14 的源码、构建脚本、工具、配置和演示截图。截图中的角色模型及角色贴图需自备。

带内置模型的 YSM JAR 需自备。YSM 补丁和构建入口位于 [源码/YSM补丁](源码/YSM补丁)。
v14 的细节见 `文档/佩丽卡-YSM全流程技术文档.md` 第 25 节；v13 见第 24 节。

## v14 相对 v13 的改动

| 方面 | 改动 | 涉及文件 |
|---|---|---|
| 防穿模 | 身体代理胶囊、头发自动碰撞体、关节 Backstop、蒙皮后顶点推出：头发、裙子、饰品穿进身体大幅减少 | YSM jar |
| 裙摆 | 相邻裙链之间加连接、裙摆腿部碰撞体、腿同步、平滑推出：裙摆不再被一条条推开（“撕开”），坐下时不再穿进腿里 | YSM jar、chen 的 `humanoid.json` |
| 快速转身 | 转身超过每秒 360°、移动超过每秒 10 格的部分，裙摆和头发跟身体一起走，急转身不再甩进腿里 | YSM jar |
| 更多裙摆 | aglina 的长外裙也按裙摆处理 | YSM jar |
| 浅穿模兜底 | 画衣服时深度往镜头靠近 1 cm，盖住剩下的浅穿模 | 渲染模组 0.8.12 |
| 眼睛 | 高光点变亮；眼白可单独调亮度；眼皮、眼白、刘海的叠加阴影按游戏的顺序计算，眼睛一圈不再发暗 | 渲染模组 0.8.9～0.8.11 |
| 下界、末地 | 有主光；夜晚和洞里有虚拟补光，脸不再太暗 | 渲染模组 0.8.8 |
| 雨水 | 水珠大小、密度随机；水珠沿身体往下滑 | 渲染模组 0.8.6～0.8.7 |

## 包里有什么

| 路径 | 内容 |
|---|---|
| `mods/` | `endfield_npr-0.8.12.jar`、`ysmguard-1.0.0.jar`、`ysm_fitbox-0.2.2.jar`、`battlefield-movement-1.7.24-forge-1.20.1-TACZ1.2.0.jar` |
| `config/endfield_npr/endfield_npr.properties` | 渲染模组的默认配置（可选；你已有的配置不要用它覆盖，新配置项会自动补上） |
| `config/ysm_tacz_fp/tacz/` | TACZ 第一人称手部数据，与 v13 相同 |
| `server-config/` | ysmguard、YSM 的服务端配置，与 v13 相同 |
| `工具/` | Blockbench 动画插件、动作导出工具、npr_materials、fpbench，与 v13 相同 |
| `文档/` | 全流程技术文档（第 1～25 节）、渲染模组 README 等 |
| `源码/` | 同步到这一版；新增 `原生补丁-防穿模`、`离线工具/防穿模测试` |
| `截图/` | 游戏内效果对照图，图中角色模型及角色贴图需自备（离线渲染，裙摆按拉伸着色：原色不拉伸，黄 1.25 倍，红 1.5 倍，紫红更多） |

## 安装

| 文件 | 服务端 | 客户端 | 说明 |
|---|---|---|---|
| `mods/ysm-3.0-dev-forge+mc1.20.1-pelica-motion.jar` | ✓ | ✓ | 替换旧的。缓存版本 24 → 27：所有模型第一次加载时会重新转换一次（服务端每个模型十几秒） |
| `mods/endfield_npr-0.8.12.jar` |  | ✓ | 删掉旧的 `endfield_npr-*.jar` 再放入 |
| 其余 3 个 jar | ✓ | ✓ | 与 v13 相同 |

裙摆修复在这 5 个模型上有某一项会让撕裂或摆动变差（离线对比），它们的 `humanoid.json` 关掉了那一项：

| 模型 | 关掉的项 | 原因 |
|---|---|---|
| chen | `cloth_follow`、`anti_clip_hem_link_collision` | 增加撕裂，不减少穿模 |
| ardelia、jsspsi | `anti_clip_hem_link_collision` | jsspsi 跑步时裙摆摆动加倍、最深穿模变深；ardelia 撕裂略增 |
| typhoea | `anti_clip_hem_links` | 坐下时撕裂 3.2% → 11.5%，走路时裙摆摆动加倍 |
| endminf | `anti_clip_hem_links`、`anti_clip_push_smooth` | 它被识别成“裙摆”的其实是三片外套，连成一圈后穿过腿 |

**配置**：旧的 `endfield_npr.properties` 会保留你调过的值，新配置项自动加入。全新安装使用下列测试实例参数。

### 默认参数更新

默认值来自 Forge 1.20.1 测试实例。配置模板与 NPR 模组首次生成配置时使用同一组值，已有的自定义配置继续生效。

| 参数 | 默认值 |
|---|---|
| `eyeNightBrightness` | 1 |
| `scleraNightBrightness` | 0.3 |
| `eyeNightGlow` | 0.6 |
| `eyeDayGlow` | 1.0 |
| `eyeShadow` | 0.2 |
| `eyeHighlightBoost` | 5 |
| `rainDropSpeed` | 1.5 |
| `rainFaceDripSpeed` | 2.0 |
| `rainDropDensity` | 0.2 |
| `rainDropSlide` | 0.02 |


## 新开关

### 渲染模组（`config/endfield_npr/endfield_npr.properties`，即时生效）

| 开关 | 默认 | 作用 |
|---|---|---|
| `clothDepthBias` | 0.01 | 衣服深度偏移（格，0.01 = 1 cm），盖住浅穿模；手指被衣服盖住时调小，0 = 关 |
| `overlayGameOrder` | true | 叠加阴影（眼白、眼皮、刘海）按游戏顺序计算；false = v13 的样子 |
| `eyeHighlightBoost` / `eyeHighlightOverShadow` | 5 / true | 眼睛高光点亮度 / 不被眼皮阴影压暗 |
| `scleraBrightness` / `scleraNightBrightness` | 1 / 0.3 | 眼白亮度（0.8 = 80%）/ 夜里额外系数 |
| `netherLight` / `endLight` | 1 / 1 | 下界、末地主光，0 = 关 |
| `fillLight` / `fillTarget` / `fillColor` | 0.6 / 0.5 / 0.92,0.96,1.0 | 虚拟补光强度 / 从多暗开始补 / 颜色 |
| `rainDropDensity` / `rainDropJitter` | 0.2 / 1 | 水珠密度 / 大小随机 |
| `rainDropSlide` / `rainDropSpeedJitter` | 0.02 / 0.4 | 水珠下滑速度（格/秒，0 = 游戏原样不动）/ 速度随机 |

眼睛各部分对应的开关：眼白 `scleraBrightness`、`scleraNightBrightness`；虹膜 `eyeBrightness`、`eyeNightBrightness`、
`eyeDayGlow`、`eyeNightGlow`；高光点 `eyeHighlightBoost`、`eyeHighlightOverShadow`；眼球阴影 `eyeShadow`。

### 模型（模型文件夹的 `humanoid.json`，默认全开，改完执行 `/ysm model reload`）

| 开关 | 作用 |
|---|---|
| `anti_clip` | 防穿模总开关 |
| `anti_clip_colliders` / `anti_clip_backstop` / `anti_clip_push` | 头发自动碰撞体 / 关节 Backstop / 顶点推出 |
| `anti_clip_hem_links` / `anti_clip_hem_link_collision` | 裙摆链间连接 / 连线也参与碰撞 |
| `anti_clip_hem_colliders` / `anti_clip_hem_sync` / `anti_clip_push_smooth` | 裙摆腿部碰撞体 / 腿同步 / 平滑推出 |
| `anti_clip_inertia`、`anti_clip_inertia_turn_limit`（360）、`anti_clip_inertia_move_limit`（10）、`anti_clip_world_inertia`（1） | 快速转身、移动时的惯性限制（度/秒、格/秒、0～1） |
| `anti_clip_hem_chains` | 自动识别不到时手动指定裙摆骨链（骨骼名列表） |

## 请在游戏里重点看

1. 跑、急转身、坐、趴下时，头发和裙子还穿不穿、裙摆像不像撕开；摆动有没有变僵；帧数。
2. 眼睛：虹膜、眼白、高光点的亮度，眼睛一圈的阴影。
3. 刘海在脸上的阴影变淡了（按估算游戏里就是这么淡），可以用 `overlayGameOrder=false` 对比。
4. 下界、末地、夜晚、洞里的光照。

## 已知限制

- 全部改动都没在游戏里验证（这台机器不能开游戏），只做了编译、原生测试、模型转换检查和离线测量。
- 裙摆：快跑和坐下时仍有中等拉伸；佩丽卡急转身时后腰上方有一块拉伸，轻微穿模比 v13 多一点（1.0% → 1.6%），最深的变浅
  （12 → 8.5 cm）；aglina 从全速奔跑突然停下时外裙会鼓起来；lastrite 腿间那条布仍会陷进大腿（手动指定后前面出现拉伸扇面，没启用）。
- avywen、ikut 没有“虹膜上的眼皮阴影”这一层（数据如此，无法确认游戏里有没有）。
- mifu、laevat：撕裂和最深穿模变好，但轻微穿模（陷进身体代理的顶点比例）略升（mifu 0.9% → 1.6%，laevat 3.5% → 4.4%）。
- 想恢复上面 5 个模型被关掉的某一项：删掉 `humanoid.json` 里对应的键，再 `/ysm model reload`。
- `clothDepthBias` 只在 NPR 渲染下有效；手指搭在衣服上时可能被盖住一点。
- v13 的已知限制仍然有效（积雪缺贴图、雨遮挡按整体判断、TACZ 手部数据只做了 3 个模型等）。
