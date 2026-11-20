# fpbench: offline first-person TACZ hand/finger workbench

fpbench renders a YSM model's first-person arms holding a TACZ gun, measures how the hands sit on the gun, solves
grip IK for the fingers and compares renders with in-game screenshots. Minecraft isn't needed. A frame takes about
0.13 s and metrics about 0.8 s, so an agent can render, measure, patch and re-render in seconds.

- **Arm model:** YSM's own importer converts the model (`java/.../FpDump.java` calls `HumanoidImport.arm()` from the
  deployed YSM jar). The output is cached in `~/.cache/fpbench/arm-<hash>`, keyed by the model, the jar and FpDump.
  The first run takes about 25 s.
- **Gun:** comes from the TACZ 1.2.0 jar's default pack (`~/ysm/bfm-port/libs/tacz-forge-1.20.1-1.2.0-hotfix.jar`),
  or from any gun-pack zip or folder given with `--pack`. fpbench reads the index, display, geo, texture and
  animations. Default animations (`use_default_animation`) are merged in the same way as in TACZ.
- **Renderer:** a numpy rasterizer with a C inner loop (`fpb/raster.c`, compiled on first use and cached) and a
  pure numpy fallback. Lighting is simple Lambert shading, not Minecraft's item lighting.
- **Environment overrides** (defaults are this server's paths): `FPBENCH_YSM_JAR`, `YSM_NATIVE_PATH` (the Linux
  `libysm.so`, e.g. unzipped from the jar's `META-INF/native/`), `FPBENCH_MC_LIBS` (a Forge 1.20.1 `libraries`
  folder), `FPBENCH_TACZ_JAR`, `FPBENCH_CACHE`. Linux or WSL only. Chinese user guide: `说明文档.md`.

## Commands

```bash
F=~/ysm/pelica-fix/tools/fpbench/fpbench          # wrapper (python3 + numpy + PIL; javac/java for the first import)
$F guns                                            # gun ids (default pack + --pack)
$F clips --gun tacz:ak47                           # clip names and lengths
$F render  --model pelica.vrm --gun tacz:ak47 --clip reload --t 0.4 --out x.png [--closeups] [--highlight]
$F sheet   --gun tacz:m4a1 --clip reload --frames 12 --metrics --highlight --out sheet.png
$F metrics --gun tacz:m4a1 [--clip reload --frames 8] [--json m.json|-]
$F ik      --gun tacz:m4a1 --out grip.json [--render ik.png] [--clearance 0.1] [--fingers LeftIndex,RightThumb]
$F metrics --gun tacz:m4a1 --pose grip.json        # apply a patch, re-measure
$F compare --gun tacz:ak47 --no-bob --shot shot.png --fit --out overlay.png [--json c.json]
```

Common options:

- `--model`: default `~/ysm/pelica-fix/pelica_springs/pelica.vrm`.
- `--gun ns:id` and `--pack` (repeatable).
- `--clip`: `idle` (default), `draw`, `reload` (= `reload_tactical`), `reload_empty`, `inspect`, `shoot`, or any clip
  name. `--t` is the time in that clip.
- `--idle-t`: the phase of the looping movement `idle` sway. `--no-sway` leaves the sway out.
- `--no-bob`: View Bobbing off. TACZ then skips the camera animation on the hands.
- `--fov`: defaults to the gun's `model_fov`.
- `--size`: default 960x540.
- `--empty`: no ammo.
- `--pose` (repeatable): a patch JSON or an `ik` output file.
- `--ysm-jar`: the YSM jar whose importer converts the model.

Outputs:

- `render` writes the game-camera PNG. `--closeups` adds `<out>_hands.png`: orthographic camera and side views of
  each hand around the palm, labelled with per-finger penetration and gap. `--highlight` paints hand vertices inside
  the gun red.
- `sheet` writes a timeline contact sheet. With `--metrics`, each tile is labelled with the in-gun vertex count per
  hand.
- `metrics` prints a text summary and writes JSON with `--json`. One frame, or a list for `--frames N` / `--times`.
- `ik` writes a YSM/Bedrock animation JSON and prints metrics before and after (see below).
- `compare` writes an overlay (render outline red, game outline green) and prints per-group residuals.

### Patch JSON (`--pose`)

```json
{
  "curl":  {"LeftIndex": [40, 60, 30], "RightThumb": [20, 30, 25]},
  "bones": {"LeftHand": [10, 0, -5]},
  "gun":   {"lefthand_pos": {"position": [0, 1, 0], "rotation": [0, 0, 10]}},
  "grips": {"tacz:ak47": {"left": [5, 18, 1, 80, -60, -50], "right": [-7, 13.8, -1.3, 38, 0, 9]}}
}
```

- `curl`: degrees per finger segment about the importer's curl axis. This is the same parametrisation as the
  `FITTED_*` tables in `HumanoidImport`.
- `bones`: arm keyframe rotations in degrees, in the importer's convention (added to the rest pose, X and Y negated).
  These replace the generated value.
- `gun`: Bedrock keyframe values for gun parts, applied as a final non-blended layer.
- `grips`: overrides the TaczGrips fitted-grip table (`[x, y, z, rx, ry, rz]` per hand; `null` removes an entry). A
  gun listed here is treated as fitted (`ctrl.tac_grip('fitted')` = 1).
- An `ik` output file also works as a patch: its `animations.parallel2.bones` are read as `bones`.

### Metrics

Units are model px (1/16 block, the unit of both the gun and the arm model), in view space. The gun is the union of
its drawn cubes (oriented boxes, signed distance). The hand is the skinned vertex set of the arm mesh.

Per hand:

- **Per finger and palm:** `penetration_px` (deepest vertex inside the gun), `inside_vertices`, `gap_px` (nearest
  distance when nothing is inside), `tip_gap_px` and `nearest_part`.
- **Grip:** the gun boxes within 2.5 px of the palm centre, or the parts given with `--grip-parts`.
  - `palm_to_axis_px`: palm centre to the grip's principal axis.
  - `knuckle_line_vs_axis_deg` and `finger_dir_vs_axis_deg`.
  - `palm_surface_gap_px`.
  - `thumb_opposition_deg`: the angle between the thumb tip and the middle tip around the grip axis.
- **Thumb:** tip gap, distance from the tip to the index tip, and to the palm.
- **Wrist:** flexion, deviation and twist of the hand relative to the forearm, plus any violations of the limits
  (flexion -70..80, deviation -30..20, twist ±90).

Collisions: the minimum hand-to-hand and arm-to-arm distances, and the hand and forearm vertices inside the gun. The
magazine is part of the gun, so a hand holding the magazine during a reload counts as "in gun".

The gun is solid: besides its cubes, every drawn part whose convex hull is at least 60 % inside or enclosed by its own
cubes adds that hull (a k-DOP), so a handguard drawn as a hollow tube of thin plates (m4a1 `octagon`) counts as
solid. Trigger parts and open shapes stay open (`metrics.solid_parts`, decided once per gun). Box-only metrics had
missed about 1 px of knuckle penetration into the m4a1 handguard.

### Hand profiles (`profile`)

```bash
$F profile --models ~/ysm/efdata/models-patched-20261118 --only pelica,aglina,aurora \
  --guns tacz:m4a1,tacz:ak47 --out profiles [--clips reload_tactical] [--fps 15] [--workers 6]
```

Writes `<out>/<ns>/<path>.json` (format 1 of `~/ysm/handoff/tacz-fp-profile-contract.md`, copy to
`config/ysm_tacz_fp/`) and `<out>/report.json`, and prints before/after penetration per model. The model id is the
model's folder name; `--fallback` (default `pelica`) becomes `*`. Per model and gun (`fpb/profile.py`):

- **grip:** the gun's placement in `profile.GRIPS` (m4a1 only so far). When the open hand's palm or finger roots
  sit in the gun for a model, its palm slides out and the model gets its own `grip`.
- **idle:** grip IK on the idle pose.
- **clips:** grip IK every 1/fps s against the whole gun, moving magazine included. Fingers with nothing within
  1 px relax instead of making a fist. Curls are median-filtered, rate-limited to 12° per frame and simplified to the
  keyframes needed within `--tolerance` degrees.

### Grip IK (`ik`)

The IK uses the importer's finger-layer parametrisation (`HumanoidImport.java:1215-1283`): one curl angle per
segment about the finger's axis (finger direction × (±1, 0, 0)), with keyframe = rot(axis, curl) − rest and X, Y
negated. The fitted gun hand's index finger keeps its −25° trigger spread.

The solver closes each finger GraspIt-style:

1. It starts from the open finger. All joints close together at fixed rates.
2. When a link would come within `--clearance` px of the gun, that joint and every proximal joint stop. Distal joints
   keep closing. Joints also stop at their limits.
3. If a link is already inside the gun with the finger open, the hand itself sits in the gun. That link may stay as
   deep as it is but may not go deeper, and the rest of the finger still wraps around the gun. The least-penetrating
   pose along the closing path is used instead when it is clearly better.
4. If a finger's current (importer) pose penetrates less than the solution, the current pose is kept.

Default fingers: all five on the support (left) hand, and all but the trigger index on the gun hand. The solve takes
1 to 4 s.

The output file contains:

- `animations.parallel2.bones.<Finger><n>.rotation`: numeric keyframes in the importer's arm-animation layout.
- `fpbench.importer_layer`: the importer's own Molang rotation strings for those bones, with the gun branch replaced
  by the solved values. For fitted guns that is `ctrl.tac_grip('fitted') ? v`, otherwise `ctrl.tac_hold_gun ? v`.
  These are drop-in replacements for the generated `parallel2` entries.
- `fpbench.importer_tables`: `FITTED_SUPPORT_HAND` / `FITTED_GUN_HAND` curl arrays to paste into `HumanoidImport`.
- `fpbench.curl`: the solved curls per finger.
- `metrics_before` and `metrics_after`.

### Agent workflow loop

```bash
F=~/ysm/pelica-fix/tools/fpbench/fpbench; G=tacz:m4a1
$F render  --gun $G --out /tmp/a.png --closeups --highlight         # 1. look (hands red where inside the gun)
$F metrics --gun $G --json /tmp/m.json                              # 2. read numbers (pen/gap/wrist/grip)
$F ik      --gun $G --out /tmp/grip.json --render /tmp/ik.png       # 3. let IK propose finger curls
#    or hand-edit a patch: {"curl": {"LeftRing": [10, 60, 45]}, "bones": {"LeftHand": [..]}}
$F metrics --gun $G --pose /tmp/grip.json                           # 4. re-measure with the patch
$F sheet   --gun $G --clip reload --frames 12 --metrics --pose /tmp/grip.json --out /tmp/s.png   # 5. whole clip
# repeat 3-5 until penetration is ~0 and gaps are <= clearance, then port curls/tables into HumanoidImport,
# rebuild YSM, and check once in game (optionally: fpbench compare against an in-game screenshot).
```

Rules of thumb:

- If a finger reports `inside gun when open: links [0]`, its knuckle already sits in the gun. Fix the hand placement
  (the TaczGrips table, or `grips` in a patch) rather than the fingers.
- Wrist values outside their limits also point to the grip table.

## Transform chain

These steps reproduce the game and were verified against the game (see Validation). TACZ line numbers refer to
Vineflower output of the 1.2.0-hotfix jar; YSM paths are under `mod/src/com/elfmcys/ysm`.

1. **Projection.** `GameRenderer.renderItemInHand` uses FOV 70 (`useFovSetting=false`). TACZ
   `CameraSetupEvent.applyGunModelFovModifying` replaces it with the display's `model_fov` (`GunDisplay` default 70).
   `Matrix4f.setPerspective(fov, w/h, 0.05, far)`. Code: `scene.projection`.
2. **Hand pose stack at RenderHandEvent.** Vanilla hand sway is `XP/YP((viewRot - bob) * 0.1)`. TACZ cancels it in
   `GunItemRendererWrapper.renderFirstPerson` (`GunItemRendererWrapper.java:167-168`), and it is zero when standing
   still.
3. **Camera animation.** `BeforeRenderHandEvent` multiplies in the camera-animation quaternion `quat(x, y, -z)` of the
   `camera` bone (`CameraRotateListener`, `GunItemRendererWrapper.java:129-143`). This happens only when View Bobbing
   is on (`CameraSetupEvent.java:80`, `options.bobView()` = `Options.f_92080_` "options.viewBobbing"). Code:
   `scene.Frame(bob_view=...)`, `--no-bob`.
4. **Gun placement.** `GunItemRendererWrapper.renderFirstPerson` does `translate(0, 1.5, 0)` and `ZP 180`
   (`GunItemRendererWrapper.java:180`). Then `FirstPersonRenderGunEvent.applyFirstPersonPositioningTransform`
   (`FirstPersonRenderGunEvent.java:129-230`) does `translate(0, 1.5, 0)`, multiplies by `idleViewInverse`
   (`getPositioningNodeInverse`, `:232`) and does `translate(0, -1.5, 0)`. The constraint transform's weight is the
   aiming progress, which is 0 at the hip. Code: `Gun.view_base`, `Gun.idle_view_inverse`.
5. **Bedrock gun geometry.** `BedrockModel` loading: `pos = pivot - parentPivot` (y negated, root `24 - py`), degrees
   to radians. `BedrockPart.translateAndRotateAndScale` (`BedrockPart.java:101`) applies
   `T(offset) T(xyz/16) ZP YP XP Q(additional) S`. Face UV mapping: east→west, west→east, up→down, down→up. The
   magazine is drawn under `additional_magazine` (`BedrockGunModel$1`). Code: `tacz_gun.load_geo`, `Gun.local`,
   `Gun.triangles`.
6. **Animations.** `Animations.createAnimationFromBedrock` (`Animations.java:122`): position /16 becomes offset
   (x, −y, z); rotation becomes `MathUtil.toQuaternion` (Rz·Ry·Rx). `CustomInterpolator` uses linear or Catmull-Rom
   interpolation (`MathUtil.splineCurve`). The state machine (`default_state_machine_v2.lua`) puts `static_idle` on the
   base track, draw/reload/inspect on the main track (overriding per bone), `shoot` blended, and `idle` blended on the
   movement track. The 0.2 s transition is ignored (it matters only for t < 0.2 s). Code: `Channel`, `Gun.pose`.
7. **Hands.** `lefthand_pos` / `righthand_pos` → `LeftHandRender` / `RightHandRender` (`ZP 180`) →
   `RenderHelper.renderFirstPersonArm` (`RenderHelper.java:144`) → Forge `RenderArmEvent` → YSM
   `CustomFirstPersonArmRenderer.render` (`client/renderer/CustomFirstPersonArmRenderer.java:48-62`):
   1. For fitted guns, `TaczGrips.place` (`client/compat/tacz/TaczGrips.java:73-98`): `scale(1, |col0|/|col1|, 1)`,
      then `translate(palmShift/16)`.
   2. `translate(±0.25, 1.8, 0)`, then `scale(-1, -1, 1)`.
   3. The native skinned draw.

   Code: `scene.place_on_gun`, `scene.palm_shift`, `Frame.hand`.
8. **YSM bone.** `T((pivot ± pos)/16) RotZYX(rest + key with X, Y negated) S T(-pivot/16)`. The skin matrix is the
   pose (the mesh is in zero-rotation bind space). Only `parallel2` drives the first-person arm. With a gun,
   `ctrl.tac_hold_gun` = 1, and `ctrl.tac_grip('fitted')` = 1 for guns in the TaczGrips table
   (`HumanoidImport.java:1175-1191`). Code: `ysm_arm.ArmModel.world`, `ysm_arm.fp_env`.

## Validation (2026-11-18, Pelica, YSM jar `ysm-3.0-dev-forge+mc1.20.1-pelica-motion.jar`, TACZ 1.2.0-hotfix)

The in-game shots came from the `~/mc` harness:

- 960x540, GUI scale 2, View Bobbing off.
- Pitch −45 so the sky is behind the hands. The HUD stays on, because hands aren't drawn with F1.
- Idle shots, plus bursts at 200 ms after pressing R for the reloads.

`compare --fit` fitted the unknown sway phase and reload time, then matched the rendered outline of each group (gun,
left arm, right arm) to the in-game outline. The hand-on-grip residual is |hand offset − gun offset|.

| gun / state | fitted t, idle_t (s) | IoU | gun offset px | L / R hand-on-grip residual px |
|---|---|---|---|---|
| ak47 idle | -, 2.45 | 0.9999 | 0.01 | 0.01 / 0.01 |
| m4a1 idle (fitted grip) | -, 5.01 | 0.9968 | 0.01 | 0.01 / (right hand hidden) |
| glock_17 idle | -, 0.85 | 0.9997 | 0.01 | 0.01 / 0.00 |
| ak47 reload_tactical | 1.20, 5.13 | 0.986 | 0.39 | 0.23 / 0.54 |
| m4a1 reload_tactical | 2.01, 0.15 | 0.992 | 0.24 | 0.26 / - |
| glock_17 reload_tactical | 0.80, 6.14 | 0.998 | 0.07 | 0.25 / 0.03 |

- **Idle:** the median outline distance is 0 px, so the outlines are pixel-identical.
- **Reload:** the residuals are at most 0.6 px. They come from frame timing: the fit grid is 10 ms, and at the fitted
  times a palm moves 0.5–15 px per 10 ms (ak47 left hand 15, m4a1 right 6.8, glock_17 left 4.1).
- **Before View Bobbing was modelled:** the ak47 reload shot was off by 5 px (median outline distance). That is how
  step 3 was found.

Speed (llvmpipe box, 8 cores, warm cache):

| step | time |
|---|---|
| frame evaluation | 0.05 s |
| 960x540 render | 0.08 s |
| metrics | 0.8 s |
| IK | 1–4 s |
| 12-frame sheet | 2.6 s (12 s with `--metrics`) |
| `compare --fit` | 7–20 s |
| first model import | ~25 s (cached) |

**IK on tacz:m4a1 idle** (fitted grip, clearance 0.1 px):

| | before | after |
|---|---|---|
| left hand in-gun vertices | 379 | 55 |
| right hand in-gun vertices | 56 | 0 |
| left index / middle / ring penetration px | 0.30 / 0.09 / 0.09 | 0 / 0 / 0.05 |
| right fingers penetration px | ≤ 0.18 | 0, every finger at the 0.1 px clearance |

The remaining left-hand contacts are the knuckle and thumb roots, which are already inside the handguard with the hand
open. Fixing them needs a change to the grip table, not the fingers.

## Limits

- Lighting, shading and texture filtering are approximate. Silhouettes and geometry are exact.
- The renderer draws no item-glint, muzzle flash, shell ejection, attachments, scopes or ADS. Aiming progress is 0
  (hip fire only).
- Gun state-machine transitions (0.2 s cross-fade at a clip start), run/walk clips and the shoot kick timing are
  approximated: `shoot` is a clip of its own.
- Metrics treat the gun as the union of its cubes, magazine included. Hand vertices are tested, not hand faces, so a
  gun part thinner than the hand's vertex spacing can pass between vertices undetected.
- The arm is the importer's first-person arm (`HumanoidImport.arm()`). Third-person isn't covered.
- `compare` needs a sky background and the default GUI scale 2 HUD layout. Mask other HUD mods yourself.

---

## 中文摘要

fpbench 是离线的第一人称 TACZ 持枪工作台，不需要启动 Minecraft。

- 用 YSM 自带的导入器离线转换模型（结果缓存）。
- 读取 TACZ 枪包：jar 默认包或任意 zip/文件夹。
- 按 TACZ 1.2.0 与 YSM 的变换链摆放枪和手臂，链上每一步都对应到源码行号，见上文 Transform chain。
- `render` 生成第一人称图和手部特写，`sheet` 生成时间轴拼图，`metrics` 输出指标 JSON 与文字摘要。指标包括手指
  穿模深度、贴合间隙、掌心到握把轴的距离与夹角、拇指位置、腕部角度是否超出关节限位，以及手与手、手与枪的碰撞。
- `ik` 是握把 IK：手指沿导入器的弯曲轴合拢，贴住枪面但不穿入。输出导入器 finger layer 格式的关键帧，以及可直接
  替换的 Molang 字符串和 `FITTED_*` 表。
- `compare` 与游戏截图对比误差。

验证：三把默认枪（AK47、M4A1、Glock17）的待机和换弹中途，与游戏截图逐像素比对，手在握把上的残差不超过
0.6 px（待机为 0 px）。

速度：每帧约 0.13 s。

工作流：渲染 → 看指标 → IK 或手写补丁 → `--pose` 重新测量 → 拼图检查整段动画 → 移植到 HumanoidImport → 进游戏确认。
