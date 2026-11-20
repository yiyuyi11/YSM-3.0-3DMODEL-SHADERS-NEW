# 步态动作工具

`make_resource.py` 把一份挂点动作（`anchors.json`）转成 YSM 导入器使用的 `endfield_locomotion.json`。

- 打进 YSM jar 后，导入器（`EndfieldLocomotion`）会在导入每个人形模型时，用 IK 把这份动作套到模型自己的骨架上，生成待机、走路、后退、奔跑动画，以及 TACZ 持枪时只动腿的版本。
- 模型自带的同名动作不会被替换。
- 没有这份数据时，导入器使用程序生成的步态。

本目录不附带任何动作数据，需要自己提供，例如在 Blender 里从自己的动画逐帧导出下面这几个挂点。

## 用法

```bash
# 单独检查并生成
python3 make_resource.py anchors.json endfield_locomotion.json
# 构建 YSM jar 时打进去；第 4 个参数写 - 则不带动作数据
src/ysm-patch/build_jar.sh <v18 jar> <v11 jar> <libraries 目录> anchors.json out.jar
```

## anchors.json 格式

坐标系是 YSM 模型空间：
- 单位为像素，16 像素 = 1 格；
- 面朝 -Z，左侧在 -X，Y 向上；
- 朝向用四元数 `[qx, qy, qz, qw]`。

```json
{
  "source": {
    "upBody": [0, 21.82, -0.6], "chest": [0, 25.21, -0.6],
    "leftShoulder": [-2.21, 28.01, 0.04], "rightShoulder": [2.21, 28.01, 0.04],
    "leg": 16.72, "arm": 9.46, "palm": 1.1
  },
  "clips": {
    "jog": {"frames": [
      {"head": [0, 0, 0, 0, 0, 0, 1], "chest": [0, 0, 0, 0, 0, 0, 1],
       "leftFoot": [0, 0, 0, 0, 0, 0, 1], "rightFoot": [0, 0, 0, 0, 0, 0, 1],
       "leftHand": [0, 0, 0, 0, 0, 0, 1], "rightHand": [0, 0, 0, 0, 0, 0, 1]}
    ]},
    "sprint": {"frames": []},
    "idle": {"frames": []}
  }
}
```

`frames` 里每帧都是 `jog` 中那种对象，上例的 `sprint`、`idle` 只是占位，实际不能为空。

| 键 | 含义 |
|---|---|
| `source.upBody`、`source.chest` | 采集动作所用骨架的上身、胸部骨骼位置 |
| `source.leftShoulder`、`source.rightShoulder` | 左右肩关节位置 |
| `source.leg`、`source.arm` | 该骨架的腿长、手臂长，用来按目标模型的比例缩放位移 |
| `source.palm` | 手掌挂点到手腕关节的距离 |
| 每帧 `head`、`chest` | 头部、胸部（上脊柱）挂点 |
| 每帧 `leftFoot`、`rightFoot` | 左右脚踝 |
| 每帧 `leftHand`、`rightHand` | 左右手掌 |
| `clips.jog` | 慢跑循环，必需；走路和后退都用它 |
| `clips.sprint` | 冲刺循环，必需；奔跑用它 |
| `clips.idle` | 站姿，必需；取第一帧作为重定向的参考姿势，自动加上 3 秒一次的呼吸起伏，时长可用 `seconds` 改 |
| `clips.<名>.stride` | 可选，每个循环身体前进的距离（像素）；不给就按着地脚的后滑距离测量 |

- **帧的排布**：一个循环内的帧均匀分布，末尾不要重复第一帧。
- **播放速度**：按 `stride` 和 Minecraft 的走路、疾跑速度匹配，着地的脚不会打滑。
