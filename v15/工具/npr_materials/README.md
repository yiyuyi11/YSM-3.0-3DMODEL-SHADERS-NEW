# npr_materials

Writes the `ENDFIELD_npr.materials` block (per-material maps and the original Unity parameters, see the endfield_npr
contract `npr-materials-schema.md`) into VRMs from the material export (`bindings.json`, `textures/<path_id>/`,
`materials/<role>/`, `ysm-materials.json`). Needs Python 3.9+ with numpy and Pillow.

## What it writes
- One entry per glTF material that triangles use and that `ysm-materials.json` maps to a Unity material with a JSON in
  the export: `material` (marker), `source` (Unity material), `params` (every `m_Floats`/`m_Colors`, colours as
  `[r, g, b, a]`) and `maps`.
- `maps.<kind>` for every binding with status `found` and `use_flag` ≠ 0: `prop`, `emission`, `line`, `normal`,
  `splitNormal`, `outlineMask`, `browMask`, `parallax`, with `scale`/`offset` when the Unity tiling is not 1,1 / 0,0.
- `maps.baseAlpha` for iris/eye materials and every material with `_UseParallax` = 1: the alpha of the material's own
  base colour image in the VRM, as a grey PNG.
- A Unity material missing from the role's export folder is borrowed from another role that has it (bounda's
  `body_01` is `M_actor_chen_body_01`). OverlayShadow cards (`eyeshadow_01`, `hairshadow_01`) have no material JSON
  in the export and are left out.
- Images are downscaled to `--cap` texels per side (default 1024) with an area filter applied per channel, so colours
  under alpha 0 are kept (Pillow's own RGBA resize premultiplies and would zero them), PNG-encoded and checked
  pixel-exact. Each texture is stored once, at the largest size any material using it needs (a hair `prop` user
  keeps a shared texture at full size).
- Running it again replaces its previous output (everything it adds is tagged `extras.endfield_npr_materials`): the
  file does not grow, and the same input gives the same bytes. The rest of the VRM is kept byte for byte.
- `--fix-format1` also checks the format-1 keys against the export and logs every finding: the iris matcap is
  replaced when it differs from the material's exported `_MatcapTex`; a missing `ramps.clothRS` is added from the
  ramp folder (`--ramps`, default `~/ysm/pelica/07-贴图素材`) when the cloth materials' `_SpecRampMap` path id is a
  known one; skin LUT and overlay textures are checked and reported (the export has no LUTs, ramps or OverlayShadow
  materials). Running without the flag removes what an earlier `--fix-format1` run added.

## Usage
```bash
# one model (the role defaults to the file name)
python3 tools/npr_materials/npr_materials.py IN.vrm OUT.vrm --role aglina --export ~/efdata/props
# every <role>/<role>.vrm under a folder; side files (ysm.json, *.animation.json, humanoid.json) are copied
python3 tools/npr_materials/npr_materials.py --batch models/ models-patched/ --export ~/efdata/props \
    --fix-format1 --report report.json
# offline import check with YSM's importer (GenNpr); see the env variables in verify.py
python3 tools/npr_materials/verify.py models/ models-patched/
```
Options: `--cap N` (max side, default 1024), `--cap-hair-prop N` (hair `prop` maps, i.e. materials with
`_AnisotropyIntensity`; default 0 = original size, the renderer's gloss-preserving mean needs the full-size texels),
`--filter box|lanczos` (default box), `--report FILE`,
`--ramps DIR` (env `NPR_RAMPS`), `--only ROLE ...` (with `--batch`),
`--oxipng LEVEL` (default 0 = off; needs `pip install pyoxipng`): recompresses the added PNGs losslessly (filters,
deflate, fewer channels where the texels allow, e.g. RGB for an opaque map); each is checked to decode to the same
RGBA texels, which is how the renderer and YSM read them. On the 24 models, level 2 takes the added images from
220.6 to 201.6 MB (-8.6 %, about 13 s per model); level 4 gains ~0.5 % more, zopfli ~2 % more at ~40x the time.
Decode time is unchanged (stb, ±3 %), so this only shrinks downloads and disk; re-runs stay byte-identical.
The export path can also come from the env variable `NPR_EXPORT`. `fix_pelica.py` calls the same library at the end
(`--npr-export DIR`), so its output equals this tool run on the VRM it wrote before.

## 中文用法
本工具把导出的属性图与原材质参数写进 VRM 的 `ENDFIELD_npr.materials`。需要 Python 3.9 以上、numpy 和 Pillow。

```bash
# 单个模型（角色名默认取文件名）
python3 tools/npr_materials/npr_materials.py 输入.vrm 输出.vrm --role aglina --export 导出目录/props
# 批量：目录下每个 <角色>/<角色>.vrm，ysm.json、动画、humanoid.json 原样复制
python3 tools/npr_materials/npr_materials.py --batch 模型目录/ 输出目录/ --export 导出目录/props \
    --fix-format1 --report report.json
# 离线导入检查（YSM 自己的导入器，不启动游戏）
python3 tools/npr_materials/verify.py 模型目录/ 输出目录/
```
- `--cap` 是贴图最大边长（默认 1024），超过的按通道单独做面积平均缩小，透明处的颜色不会丢。头发材质（含 `_AnisotropyIntensity`）的 `prop` 图默认保持原尺寸，可用 `--cap-hair-prop N` 改。
- `--oxipng 2`（需要 `pip install pyoxipng`）对新增的 PNG 做无损再压缩，24 个模型的新增贴图从 220.6 MB 降到 201.6 MB（约 -8.6%），解码出的像素逐一核对完全相同；只减少下载量和磁盘占用，不影响解码速度。默认关闭。
- 可以重复运行：上次写入的贴图和数据会先删掉再写，文件不会越来越大，同样的输入得到同样的字节。
- `--fix-format1` 会用导出数据核对旧的 format 1 字段（虹膜 matcap、皮肤 LUT、布料高光色阶、叠加阴影贴图），能修的就修，并逐条打印结果。缺少 `clothRS` 时从 `--ramps` 目录（默认 `~/ysm/pelica/07-贴图素材`，环境变量 `NPR_RAMPS`）补上。
- `--batch` 时可用 `--only 角色名 ...` 只处理部分角色。
- 导出目录也可以用环境变量 `NPR_EXPORT` 指定。
