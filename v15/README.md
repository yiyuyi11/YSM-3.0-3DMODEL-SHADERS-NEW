# YesSteveModel v15 公开交付（2026-11-20）

本目录提供 v15 的源码、构建脚本、工具、配置和演示截图。截图中的角色模型及角色贴图需自备。

带内置模型的 YSM JAR 需自备，构建入口见 [YSM 补丁](源码/YSM补丁)。
v15 的细节见 `文档/佩丽卡-YSM全流程技术文档.md` 第 26 节；v14 见第 25 节。

当前版本：YSM jar 缓存版本 28，endfield_npr 0.8.14，ysmguard 1.0.0，ysm_fitbox 0.2.2，Battlefield Movement 1.7.24。
两个新 jar 与你认可的测试版相同（YSM jar 内容逐项一致，endfield_npr 是同一个文件）。

## v15 相对 v14 的改动

| 方面 | 改动 | 涉及文件 |
|---|---|---|
| 类别图（根本原因） | v12～v14 的模型在游戏里没有 YSM 的类别图（模型 `ysm.json` 的贴图写法所致），眼睛、脸、皮肤、金属的区分只能按颜色猜，`clothDepthBias` 也没生效；现在 YSM 自动恢复类别图，渲染模组也改按模型包的材质表判断 | YSM jar、渲染模组 0.8.14 |
| 眼睛 | 虹膜真正按眼睛着色；高光点几格外也看得见（最小 1.5 像素 + 光晕）；树荫里眼睛跟脸一起变暗；夜里虹膜更亮 | 渲染模组 0.8.13～0.8.14 |
| 头发、裙摆回弹 | 停步、落地后不再甩过头：头发越过次数中位数 0.73 → 0.34，稳定时间 0.37 → 0.33 秒；裙摆 0.28 → 0.21 秒 | YSM jar |
| 进出水 | 出水后湿线不再掉到脚底；水珠滴落、滑下约 20 秒，浅色衣服约 2 分钟内慢慢变干 | 渲染模组 0.8.13 |
| 第一人称手臂 | 用 NPR 着色画（有无光影包都是），`fpArms=false` 恢复原样 | 渲染模组 0.8.13 |

## 包里有什么

| 路径 | 内容 |
|---|---|
| `mods/` | `endfield_npr-0.8.14.jar`、`ysmguard-1.0.0.jar`、`ysm_fitbox-0.2.2.jar`、`battlefield-movement-1.7.24-forge-1.20.1-TACZ1.2.0.jar` |
| `config/endfield_npr/endfield_npr.properties` | 渲染模组的默认配置，版本 11（可选；你已有的配置不要用它覆盖，会自动升级） |
| `config/ysm_tacz_fp/tacz/` | TACZ 第一人称手部数据，与 v14 相同 |
| `server-config/` | ysmguard、YSM 的服务端配置，与 v14 相同 |
| `工具/` | Blockbench 动画插件、动作导出工具、npr_materials、fpbench，与 v14 相同 |
| `文档/` | 全流程技术文档（第 1～26 节）、渲染模组 README（0.8.14）、新增两张表：`弹簧回弹-缓存版本27对28.md`（24 个模型）、`防穿模复查-chen与佩丽卡.md` |
| `源码/` | 同步到这一版：渲染模组 0.8.14、`YSM补丁`（缓存版本 28）、`原生补丁-防穿模`（缓存版本 28）、`离线工具/防穿模测试`（加了回弹测量）、`离线工具/ysmgen`（加了类别图检查 SpecularCheck） |
| `截图/` | 历次对照图，新增「眼睛默认值」（佩丽卡、ikut、chen、zhuangfy，正午、树荫、夜晚、洞穴，虹膜 24 和 12 像素；上行 v14 的默认值，中行新值但眼睛不受太阳阴影，下行是这一版的默认值） |

## 安装

| 文件 | 服务端 | 客户端 | 说明 |
|---|---|---|---|
| `mods/ysm-3.0-dev-forge+mc1.20.1-pelica-motion.jar` | ✓ | ✓ | 替换旧的。缓存版本 27 → 28：所有模型第一次加载时会重新转换一次（服务端每个模型十几秒） |
| `mods/endfield_npr-0.8.14.jar` |  | ✓ | 删掉旧的 `endfield_npr-*.jar` 再放入；每个模型第一次加载时贴图缓存自动重建一次 |
| 其余 3 个 jar | ✓ | ✓ | 与 v14 相同 |

v14 起这 5 个模型的 `humanoid.json` 关掉了裙摆修复的某一项（离线对比会让撕裂或摆动变差）：

| 模型 | 关掉的项 | 原因 |
|---|---|---|
| chen | `cloth_follow`、`anti_clip_hem_link_collision` | 增加撕裂，不减少穿模 |
| ardelia、jsspsi | `anti_clip_hem_link_collision` | jsspsi 跑步时裙摆摆动加倍、最深穿模变深；ardelia 撕裂略增 |
| typhoea | `anti_clip_hem_links` | 坐下时撕裂 3.2% → 11.5%，走路时裙摆摆动加倍 |
| endminf | `anti_clip_hem_links`、`anti_clip_push_smooth` | 它被识别成“裙摆”的其实是三片外套，连成一圈后穿过腿 |

**配置**：旧的 `endfield_npr.properties` 会自动升级到版本 11：你调过的值保留，新配置项自动加入，仍是旧默认值的眼睛项换成新默认值
（`eyeNightBrightness` 0.8 → 1.0、`eyeNightGlow` 0.15 → 0.3、`eyeShadow` 0 → 0.3、`scleraNightBrightness` 1 → 0.9）。
**测试时改过的值请删掉那一行**（例如 `eyeBrightness=3`、`eyeDayGlow=0.2`；`debugView` 改回 0），否则新默认值不会生效；删掉的项会按默认值自动补回。

## 新开关

### 渲染模组（`config/endfield_npr/endfield_npr.properties`，即时生效）

| 开关 | 默认 | 作用 |
|---|---|---|
| `classFromMaterials` | true | 按模型包的材质表判断眼睛、皮肤、头发、布料；false = 只按类别图（没有时按颜色） |
| `classMapUpload` | true | 开光影包时类别图为空就由本模组上传 |
| `eyeHighlightMinPx` | 1.5 | 高光点最小宽度（像素），远处也看得见；0 = v14 |
| `eyeHighlightHalo` / `eyeHighlightHaloPx` | 0.5 / 1.0 | 高光点光晕强度 / 半径（像素）；0 = 无光晕 |
| `irisBrightness` | 1.1 | 只调虹膜亮度（`eyeBrightness` 还会提亮眉毛、睫毛）；越亮颜色越淡 |
| `eyeSceneShadow` | 1.0 | 眼球接受光影包的太阳阴影（树荫里眼睛不再发亮）；0 = v14 |
| `waterLineHold` | true | 湿线取这次湿身到过的最高水面，眼睛没入水后全身湿到干透；false = v14 |
| `waterDrip` / `waterDripTime` | 1.0 / 20 | 出水后滴水强度（0 = 关）/ 持续秒数 |
| `fpArms` / `fpArmsOutline` / `fpArmsSceneShadow` | true / 1.0 / true | 第一人称手臂用 NPR 着色 / 手臂描边宽度 / 手臂接受光影包的太阳阴影 |
| `debugView=8` | （0） | 诊断：按类别上色（眼睛品红、脸黄、皮肤橙、头发绿、布料蓝） |

眼睛各部分对应的开关：眼白 `scleraBrightness`、`scleraNightBrightness`；虹膜 `irisBrightness`、`eyeBrightness`、`eyeNightBrightness`、
`eyeDayGlow`、`eyeNightGlow`；高光点 `eyeHighlightMinPx`、`eyeHighlightHalo`、`eyeHighlightHaloPx`、`eyeHighlightBoost`、
`eyeHighlightOverShadow`；眼球阴影 `eyeShadow`（色带、自阴影、AO）、`eyeSceneShadow`（光影包的太阳阴影）。v14 加的开关
（`clothDepthBias`、`overlayGameOrder`、`netherLight`、`fillLight`、水珠等）照旧，见 `文档/渲染模组-README.md`。

### 模型（模型文件夹的 `humanoid.json`，改完执行 `/ysm model reload`）

| 开关 | 作用 |
|---|---|
| `spring_damping_hair` / `spring_damping_cloth`（新） | 头发 / 布料的额外阻尼，0～1，默认 0.3 / 0；越大停得越快，1 = 不摆 |
| `spring_stiffness_hair` / `spring_stiffness_cloth`（新） | 拉回动画姿态的刚度倍数，0～10，默认 1 |
| `spring_body_velocity_seconds`（新） | 弹簧跟随身体速度的时间常数（秒），默认 0.03；0 = v14 的 0.1 秒 |
| `anti_clip` | 防穿模总开关（`anti_clip*` 默认全开） |
| `anti_clip_colliders` / `anti_clip_backstop` / `anti_clip_push` | 头发自动碰撞体 / 关节 Backstop / 顶点推出 |
| `anti_clip_hem_links` / `anti_clip_hem_link_collision` | 裙摆链间连接 / 连线也参与碰撞 |
| `anti_clip_hem_colliders` / `anti_clip_hem_sync` / `anti_clip_push_smooth` | 裙摆腿部碰撞体 / 腿同步 / 平滑推出 |
| `anti_clip_inertia`、`anti_clip_inertia_turn_limit`（360）、`anti_clip_inertia_move_limit`（10）、`anti_clip_world_inertia`（1） | 快速转身、移动时的惯性限制（度/秒、格/秒、0～1） |
| `anti_clip_hem_chains` | 自动识别不到时手动指定裙摆骨链（骨骼名列表） |

头发 = 从头、颈垂下的骨链，其余都算布料（裙摆、外套下摆、飘带、尾巴）。佩丽卡的头发停得比 v14 慢一点（0.39 → 0.43 秒），
想要更快可以在她的 `humanoid.json` 写 `{"spring_damping_hair": 0}`（离线回弹指标更好，穿模没测）。

## 请在游戏里重点看

1. 眼睛：虹膜和高光点（几格外能不能看到白点）、树荫、夜晚、洞里的眼睛亮度。眼睛还是没变化的话，开 `debugView=8` 截图并附上日志里
   `Endfield NPR eyes` 那几行。
2. 类别图恢复后第一次真正起作用的：脸部明暗（SDF）、金属、皮肤和布料的区分、`clothDepthBias`（手指搭在衣服上时可能被盖住一点）。
3. 头发、裙摆在停步、落地、急转身后的回弹，摆动有没有变僵；佩丽卡头发的穿模。
4. 进出水：出水后的湿线、滴水、慢慢变干。
5. 第一人称手臂：空手、TACZ 持枪、瞄准、换弹，有无光影包；日志里应有一行 `Endfield NPR: drawing the first-person arms`。

## 已知限制

- 本机不能开游戏，这一版的数字都来自编译、原生测试、模型转换检查和离线测量；两个新 jar 与你认可的测试版相同。
- 头发：佩丽卡（0.39 → 0.43 秒）和 whiten（0.32 → 0.44 秒）停得比 v14 慢；佩丽卡头发轻微穿模略增（0.57% → 0.68%）；azrila、
  jsspsi、lastrite、seraph、zhuangfy 走、跑时头发摆动幅度小了 10～13%。
- 第一人称手臂：手里的玻璃、冰等半透明方块挡在手前时会把后面的手遮住；光影包的泛光、TAA、雾、水下色调不作用于手臂。
- v14 的限制仍然有效：裙摆快跑和坐下时仍有中等拉伸，佩丽卡急转身时后腰上方有一块拉伸；aglina 从全速奔跑突然停下时外裙会鼓起来；
  lastrite 腿间那条布仍会陷进大腿；avywen、ikut 没有“虹膜上的眼皮阴影”这一层；mifu、laevat 轻微穿模略升；`clothDepthBias`
  只在 NPR 渲染下有效。想恢复上面 5 个模型被关掉的某一项：删掉 `humanoid.json` 里对应的键，再 `/ysm model reload`。
- v13 的已知限制仍然有效（积雪缺贴图、雨遮挡按整体判断、TACZ 手部数据只做了 3 个模型等）。
