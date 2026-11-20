#!/usr/bin/env bash
# animkit offline tests (no Minecraft): byte identity without overlays, the overlay merge, overlay errors, the rig
# export and GenCheck. Runs one JVM at a time (about 20 s and 1.3 GB each).
#
# Usage: run_tests.sh <master jar> <animkit jar> <libraries dir> <models dir> <scratch dir>
#   master jar:  mod/build_jar.sh output of master (421fedd), the reference for byte identity
#   animkit jar: mod/build_jar.sh output of this branch
#   models dir:  folder with <role>/<role>.vrm model folders (pelica and aurora are used)
set -uo pipefail
base=$1 jar=$2 libraries=$3 models=$4 scratch=$5
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$scratch"
scratch=$(cd "$scratch" && pwd)
libs=$(find "$libraries" -name '*.jar' | tr '\n' ':')
failed=0
fail() { echo "FAIL: $*"; failed=1; }

unzip -o -q -j "$jar" META-INF/native/libysm.so -d "$scratch/native"
export YSM_NATIVE_PATH=$scratch/native/libysm.so
javac --release 17 -nowarn -encoding UTF-8 -d "$scratch/classes" -cp "$jar:$libs" "$here/ImportDump.java" \
    "$here/../../ysmgen/src/com/elfmcys/ysm/format/parser/GenCheck.java" 2>&1 | grep -v '^Note:'
dump() { java -Xmx3G -cp "$scratch/classes:$1:$libs" com.elfmcys.ysm.format.parser.ImportDump "$2" "$3" 2>&1 \
    | grep -v 'Simd type\|mesh LOD\|cloth colliders\|supplies its own'; }

echo "== 1. byte identity without overlays (master jar vs animkit jar)"
for model in pelica aurora; do
    dump "$base" "$models/$model" "$scratch/$model-master"
    dump "$jar" "$models/$model" "$scratch/$model-animkit"
    python3 "$here/compare_modeldata.py" "$scratch/$model-master.modeldata.bin" "$scratch/$model-animkit.modeldata.bin" \
        || fail "$model ModelData differs"
    python3 - "$scratch/$model-master.json" "$scratch/$model-animkit.json" <<'EOF' || fail "$model container differs"
import json, sys
a, b = (json.load(open(p)) for p in sys.argv[1:3])
same = {k: a[k] == b[k] for k in ("modelHash", "manifestSha256", "animationSets")}
differing = sorted(k for k in set(a["chunks"]) | set(b["chunks"]) if a["chunks"].get(k) != b["chunks"].get(k))
print(f"  model hash, manifest, animation sets equal: {same}; {len(a['chunks'])} container chunks, differing: {differing}")
sys.exit(0 if all(same.values()) and all(k.startswith("player ") for k in differing) else 1)
EOF
done
echo "   control: the master jar against itself (pelica): the endfield_npr ZIP timestamps differ there too"
dump "$base" "$models/pelica" "$scratch/pelica-master2"
python3 "$here/compare_modeldata.py" "$scratch/pelica-master.modeldata.bin" "$scratch/pelica-master2.modeldata.bin"
rm -f "$scratch"/*-master.mxc "$scratch"/*-master.modeldata.bin "$scratch"/*-master2.*

echo "== 2. overlay: idle replaced, extra_wave added (main), parallel2 replaced (fp_arm)"
work=$scratch/overlay/pelica
rm -rf "$scratch/overlay" && mkdir -p "$work/animations"
cp "$models/pelica/"* "$work/"
cp "$here/fixtures/main.animation.json" "$here/fixtures/fp_arm.animation.json" "$work/animations/"
dump "$jar" "$work" "$scratch/overlay-pelica"
python3 "$here/check_overlay.py" "$scratch/pelica-animkit.json" "$scratch/overlay-pelica.json" \
    "$here/fixtures/main.animation.json" "$here/fixtures/fp_arm.animation.json" || fail "overlay merge"
java -Xmx2G -cp "$jar:$libs" com.elfmcys.ysm.format.parser.RigExport "$work" "$scratch/overlay-pelica.ysmrig.json" \
    2>&1 | grep -v 'Simd type\|supplies its own' || fail "RigExport on the overlay folder"
python3 - "$scratch/overlay-pelica.ysmrig.json" <<'EOF' || fail "RigExport overlay fields"
import json, sys
rig = json.load(open(sys.argv[1]))
ov = rig["overlay"]
print("  rig overlay:", {s: (v["file"], v["clips"], sorted(v["replaced"])) for s, v in ov.items()})
ok = (ov["main"]["clips"] == ["idle", "extra_wave"] and sorted(ov["main"]["replaced"]) == ["idle"]
      and ov["fp_arm"]["clips"] == ["parallel2"] and "extra_wave" in rig["animations"]["main"]["animations"]
      and rig["animations"]["main"]["animations"]["idle"]["animation_length"] == 2)
sys.exit(0 if ok else 1)
EOF

echo "== 3. humanoid.json \"animations\" path override"
mkdir -p "$work/custom" && mv "$work/animations/main.animation.json" "$work/custom/wave.json" && rm -rf "$work/animations"
echo '{"animations": {"main": "custom/wave.json"}}' > "$work/humanoid.json"
dump "$jar" "$work" "$scratch/override-pelica"
python3 - "$scratch/override-pelica.json" "$scratch/overlay-pelica.json" <<'EOF' || fail "humanoid.json override"
import json, sys
a, b = (json.load(open(p))["animationSets"] for p in sys.argv[1:3])
ok = a["main"] == b["main"] and "parallel2" in a["fp_arm"]
print("  main set equal to the default-path overlay:", a["main"] == b["main"])
sys.exit(0 if ok else 1)
EOF
echo '{"animations": {"main": "custom/missing.json"}}' > "$work/humanoid.json"
out=$(dump "$jar" "$work" "$scratch/missing")
echo "  game: $out"
grep -q "humanoid.json: animations.main: file 'custom/missing.json' not found" <<<"$out" \
    || fail "missing named overlay not rejected"
rm -f "$work/humanoid.json" && rm -rf "$work/custom" && mkdir -p "$work/animations"

echo "== 4. broken overlays fail with file and clip"
cp "$here/fixtures/broken_molang.animation.json" "$work/animations/main.animation.json"
out=$(dump "$jar" "$work" "$scratch/broken")
echo "  game: $out"
grep -q "animations/main.animation.json: clip 'extra_wave', RightArm rotation" <<<"$out" || fail "broken Molang message"
out=$(java -cp "$jar:$libs" com.elfmcys.ysm.format.parser.RigExport "$work" "$scratch/broken.json" 2>&1)
code=$?
echo "  RigExport (exit $code): $(grep '^RigExport' <<<"$out")"
[ $code -eq 1 ] && grep -q "clip 'extra_wave'" <<<"$out" || fail "RigExport broken Molang"
cp "$here/fixtures/broken_json.animation.json" "$work/animations/main.animation.json"
out=$(dump "$jar" "$work" "$scratch/broken")
echo "  game: $out"
grep -q "animations/main.animation.json: not a valid animation JSON object" <<<"$out" || fail "broken JSON message"

echo "== 5. rig export and GenCheck"
for model in pelica aurora; do
    java -Xmx2G -cp "$jar:$libs" com.elfmcys.ysm.format.parser.RigExport "$models/$model" "$scratch/$model.ysmrig.json" \
        2>&1 | grep -v 'Simd type\|supplies its own' || fail "RigExport $model"
    python3 "$here/../check_rig.py" "$scratch/$model.ysmrig.json" > "$scratch/$model.check.txt" \
        && echo "  check_rig $model: ok" || fail "check_rig $model: $(grep ERROR "$scratch/$model.check.txt")"
    java -Xmx3G -cp "$scratch/classes:$jar:$libs" com.elfmcys.ysm.format.parser.GenCheck "$models/$model/$model.vrm" \
        2>&1 | grep -v 'Simd type' | tee "$scratch/$model.gencheck.txt" | grep "Molang strings"
    grep -q " 0 compile failures" "$scratch/$model.gencheck.txt" || fail "GenCheck $model"
done

rm -rf "$scratch/overlay" "$scratch"/*.mxc "$scratch"/*.modeldata.bin "$scratch/classes" "$scratch/native"
[ $failed -eq 0 ] && echo "ALL PASSED" || echo "SOME TESTS FAILED"
exit $failed
