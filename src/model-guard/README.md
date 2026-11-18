# ysmguard：YSM 模型加密与归属（服务器管理员说明）

适用：Forge 1.20.1-47.4.3 + YesSteveModel 3.0-dev（含本项目补丁版 `ysm-3.0-dev-forge+mc1.20.1-ysm3d.jar`）。
**服务端和每个客户端都要装** `build/ysmguard-1.0.0.jar`（默认服务器会拒绝没装它的客户端）。

## 一、它做了什么

### 1. 加密
| 位置 | 原版 YSM 3.0 | 装了 ysmguard 以后 |
|---|---|---|
| 网络传输（模型元数据、网格、贴图、动画、上传） | YSM 帧明文走 Forge 自定义包；离线模式服务器完全不加密 | 每个连接先做 X25519 密钥交换，YSM 的所有数据包都用 AES-256-GCM 封装（每个方向各一把密钥，计数器防重放、防篡改）。对方发来的明文 YSM 包直接丢弃 |
| 客户端缓存 `ysm/cache/remote`（服务器模型的全部内容）、`ysm/cache/bake`（烘焙网格，内含原始网格；编译后的动画）、`ysm/cache/previews` | 明文：贴图是 WebP，网格是 zstd，拷走就能拆 | AES-256-CTR 加密（文件头 `YGC1`）。密钥由服务器按玩家下发（HMAC(服务器密钥, 玩家 UUID)），**只在内存里**，不写盘。不连服务器就读不了缓存；换台机器、换个账号也读不了 |
| 装模组之前留下的明文缓存 | — | 客户端启动时自动删掉 `<游戏目录>/ysm/cache` 下的明文缓存文件 |

单人游戏和没装 ysmguard 的服务器：缓存用本进程的随机密钥加密，游戏重启后要重新烘焙（只是慢一点）。

### 2. 归属：普通玩家只能用自己上传的模型
YSM 3.0 自带"玩家上传"：玩家上传的模型存在 `ysm/auth/uploads/<上传者UUID>/<名字>.zip`，**文件夹就是所有权记录**，换名、重传都跟着走，不需要另外的数据库。ysmguard 在这个基础上补了下面几点：

- **管理员**（权限等级 ≥ 2，也就是 op；和 YSM 自己的管理员判定 `CommandUtil.hasPermission(…, 2)` 一致）自动拥有全部模型。`/op`、`/deop` 立即生效：被 deop 的玩家马上失去别人的模型，正在穿的会退回默认模型。
- `ysm/custom` 里的模型和 YSM 内置模型（内置 `default` 除外）也要授权，和 `ysm/auth` 一样。所以普通玩家能用的只有：内置 `default`、自己上传的、管理员用 `/ysm auth` 明确授权给他的。
- 所有判定都在服务器上：GUI 选模型、改过的客户端直接发选择包、`/ysm model set`，走的都是 YSM 服务端同一个检查（会话里的授权集合），客户端说了不算。
- 内容下载（默认 `ContentPolicy = WORN`）：一个受保护模型的网格/贴图/动画，只发给管理员、所有者、被授权的玩家；其他人只有在**有在线玩家正穿着它**的时候才能下载（这样别人仍然看得到你穿的模型）。没人穿的模型，普通玩家下载不到。
- 修复了 YSM 客户端的一个 bug：撤销授权时（`/ysm auth … remove`、删除上传、deop），原版客户端会报"服务器模型列表更新无效"，并禁用所有远程模型。

## 二、安装

```bash
# 服务端
cp build/ysmguard-1.0.0.jar <服务器>/mods/
cp server-config/ysm-server.toml <服务器>/world/serverconfig/ysm-server.toml        # 打开了玩家上传
cp server-config/ysmguard-server.toml <服务器>/world/serverconfig/                  # 可省略，首次启动会生成默认值
# 建议：把服务器自己的模型放进 auth（即使以后卸了 ysmguard 也不会公开）
mkdir -p <服务器>/ysm/auth && mv <服务器>/ysm/custom/sample <服务器>/ysm/auth/
chmod -R go-rwx <服务器>/ysm <服务器>/config/ysmguard                                # 服务器上的模型文件只能靠文件权限保护
# 客户端（每个玩家）
cp build/ysmguard-1.0.0.jar <客户端>/mods/
```
sample 移到 `ysm/auth/sample/` 以后地址变成 `local:auth/sample`。渲染模组 endfield_npr 0.6.0 起按模型哈希读取模型自带的 NPR 包（VRM 扩展 `ENDFIELD_npr`），它和网格一起经 YSM 下发，同样受 ysmguard 加密和 `ContentPolicy` 约束，移动目录不用改任何配置。YSM jar 不用改。

服务器第一次启动会生成 `config/ysmguard/server-secret.bin`（32 字节，只有属主可读）。**不要泄露，也不要删**：删了以后所有玩家的缓存都会失效，要重新下载。

## 三、配置

`world/serverconfig/ysmguard-server.toml`：

| 键 | 默认 | 含义 |
|---|---|---|
| `RequireClientMod` | true | 拒绝没装 ysmguard 的客户端。设为 false 时这些客户端也能进，但发给它们的 YSM 数据**不加密**（服务器日志会警告） |
| `AdminsUseAllModels` | true | op 自动拥有全部模型 |
| `ProtectCustomAndBuiltinModels` | true | `custom/` 和内置模型（`default` 除外）也需要授权 |
| `ContentPolicy` | WORN | `WORN`：见上文；`OPEN`：YSM 原规则（谁都能下载任何模型的内容） |

`world/serverconfig/ysm-server.toml`（YSM 自己的配置）中相关的项：
- `[player_uploads] Enabled = true`：允许上传；`PermissionLevel = 0`：所有人都能上传；`MaxMegabytes`、`MaxModelsPerPlayer`：大小和数量上限。
- `RestrictedAuth`：设为 true 时，没有授权的玩家完全下载不到别人的受保护模型（他们看到的是默认模型）。保密最严，代价是普通玩家看不到别人穿的上传模型。
- `CanSwitchModel = false`：禁止所有玩家自己换模型。

## 四、命令

玩家：
- `/ysmclient upload <名字>`：把自己客户端 `ysm/custom/<名字>`（文件夹、zip 或 ysm）上传到服务器。上传后只有自己能用。和服务器上已有模型完全相同的文件会被 YSM 拒绝。
- `/ysm upload list`、`/ysm upload remove <名字>`：查看和删除自己的上传。
- 按 Alt+Y 打开模型界面。"授权模型"页只显示自己能用的模型，别人的模型显示为灰色，点了也不生效。

管理员（权限 ≥ 2）：
- `/ysmguard status`：在线玩家列表，每人显示是否管理员、YSM 通道是否已加密、可用模型数（其中自己上传的数量）、当前模型。
- `/ysmguard list [玩家]`：所有模型的地址、是否需授权、所有者、当前谁在穿；加玩家名则只列他上传的。
- `/ysmguard owner "<模型地址>"`：所有者、在线玩家里谁能用、谁在穿。
- `/ysmguard transfer "<模型地址>" player <玩家>`：转给某个玩家，文件会移到 `auth/uploads/<他的UUID>/`。
- `/ysmguard transfer "<模型地址>" admin`：收归管理员，文件移到 `auth/`。转移后会自动重新加载模型列表。
- YSM 自带的：`/ysm model set <玩家> <模型> <贴图|-> [忽略授权]`（不加 `true` 时目标玩家必须有权使用该模型；加 `true` 就是管理员强制指定）、`/ysm auth <玩家> add|remove|all|clear`、`/ysm upload list <玩家>`、`/ysm upload remove <玩家> <名字>`、`/ysm model reload`。
- 地址里含 `:` 或 `/` 时要加英文双引号，例如 `"local:auth/uploads/<uuid>/fox.zip"`。短名（如 `sample`）可以不加。

## 五、做不到的事（请了解）

- **客户端要渲染，就必须在内存和显卡里有解密后的网格和贴图。** 改过的客户端、内存转储、RenderDoc 这类 GL 抓帧工具，都能拿到它正在显示的模型。加密只保护网络和磁盘，挡不住一个被恶意修改的游戏进程。`WORN` 把能拿到的范围缩小到"有人正穿着的模型"；`RestrictedAuth = true` 再缩小到"自己有权用的模型"。
- 所有玩家都能拿到每个模型的缩略图/图标，以及元数据（名字、作者、骨骼数/面数、贴图名、动画名）。这是 YSM 模型界面的设计。`ClientNotDisplayModelPaths` 只能在界面上隐藏，不能阻止下载。
- 密钥交换不验证服务器身份。它能防被动窃听，但离线模式下有人在中间主动拦截（中间人攻击）就防不住。需要的话请开 `online-mode=true`，原版会再加一层带正版验证的加密。
- 改过的客户端可以记下服务器发来的缓存密钥，解开它**自己的**缓存。别人的缓存、别的机器上的缓存它解不开。
- 服务器上的模型文件（`ysm/auth`、`ysm/custom`，以及 YSM 转换出的 `ysm/cache/converted/*.mxc`）是明文。YSM 3.0 没有服务器端加密：它只能**导入**旧版加密的 `.ysm` v3，而这种格式的 ChaCha 解密方法就在公开的 native 源码里，导入后同样转成明文缓存；而且服务器要用这个密钥，密钥就得和文件放在同一台机器上。所以服务器端只能靠文件权限保护（上面的 `chmod`；最好用专用系统账号运行服务器）。`/ysm export` 只在单人或局域网主机上能用，且只有管理员能用。
- YSM 的全局模型目录（设置了 `XDG_DATA_HOME`、`LOCALAPPDATA` 等环境变量时启用）里的旧明文缓存不会被自动删除。它们不会再被读取，下次下载时会被覆盖；介意的话可以手动删掉那个目录下的 `cache/remote`、`cache/bake`、`cache/previews`。

## 六、构建与离线检查

```bash
./build.sh        # 需要 ~/ysm/npr/mcdev（先跑一次 ~/ysm/npr/mod/tools/remap-mc.sh），以及 YSM jar（默认 ~/ysm/sample-fix/mod/out/…）
test/run.sh       # 加解密/握手/缓存测试 + 检查 jar 里引用的 Minecraft 成员在生产环境（SRG）里都存在
```
`tools/moj2srg.tsrg` 是 endfield_npr 那份映射的副本，补了一行 `MinecraftServer.getPlayerList → m_6846_`（原表缺 MinecraftServer 的成员）。
