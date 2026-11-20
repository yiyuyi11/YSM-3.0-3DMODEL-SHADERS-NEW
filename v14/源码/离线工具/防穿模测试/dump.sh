#!/usr/bin/env bash
# Imports models with this checkout's importer (mod/src compiled against the v18 jar) and writes AntiClipDump output.
# Usage: dump.sh <classes dir> <out root> <model file>...   -> <out root>/<model name>/{geo.pb,meta.json,mesh.bin}
# Env: YSM_V18_JAR (default ~/ysm/pelica-full/06-YSM模组/mods/ysm-v18-win.jar), YSM_MC_LIBS (~/mc/client/libraries),
# YSM_NATIVE_PATH (Linux native, default ~/ysm/nativebuild/out-win/libysm-lod-linux.so).
set -euo pipefail
classes=$1 out=$2
shift 2
here=$(cd "$(dirname "$0")" && pwd)
src=$(cd "$here/../../mod/src" && pwd)
v18=${YSM_V18_JAR:-$HOME/ysm/pelica-full/06-YSM模组/mods/ysm-v18-win.jar}
libs=${YSM_MC_LIBS:-$HOME/mc/client/libraries}
export YSM_NATIVE_PATH=${YSM_NATIVE_PATH:-$HOME/ysm/nativebuild/out-win/libysm-lod-linux.so}
cp="$v18:$(find "$libs" -name '*.jar' | tr '\n' ':')"
if [ ! -f "$classes/com/elfmcys/ysm/format/parser/AntiClipDump.class" ]; then
    javac --release 17 -nowarn -encoding UTF-8 -d "$classes" -cp "$cp" $(find "$src" -name '*.java') \
        "$here/java/com/elfmcys/ysm/format/parser/AntiClipDump.java"
fi
for model in "$@"; do
    name=$(basename "$(dirname "$model")")
    java -Xmx2g -cp "$classes:$cp" com.elfmcys.ysm.format.parser.AntiClipDump "$model" "$out/$name"
done
