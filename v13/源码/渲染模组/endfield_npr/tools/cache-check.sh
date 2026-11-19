#!/usr/bin/env bash
# Offline test and timing of the per-material map disk cache (NprMapCache) on serialized ModelData blobs (ysmgen
# GenNpr writes <prefix>.modeldata.bin), with YSM's native zstd.
# usage: tools/cache-check.sh <cacheDir> [--test] <modeldata.bin>...   (run ./build.sh first)
# env: YSM_JAR (YSM jar whose natives match YSM_NATIVE_PATH), YSM_NATIVE_PATH (Linux libysm-core .so)
set -euo pipefail
ARGS=()
for a in "$@"; do
  if [ "$a" = --test ]; then ARGS+=("$a"); else ARGS+=("$(realpath -m "$a")"); fi
done
cd "$(dirname "$0")/.."
ROOT=$(pwd)
DEV=${MCDEV:-$HOME/ysm/npr/mcdev}
YSM_JAR=${YSM_JAR:-$HOME/ysm/pelica-fix/deliver-v11/mods/ysm-3.0-dev-forge+mc1.20.1-pelica-motion.jar}
export YSM_NATIVE_PATH=${YSM_NATIVE_PATH:-$HOME/mc/client/home/.ysm/unstable/libysm-core-3.0-dev-forge+mc1.20.1.so}
LIBS=$(find "${MC_LIBRARIES:-$HOME/mc/client/libraries}" -name '*.jar' | grep -v -i '/ysm' | tr '\n' ':')
CP="$ROOT/build/classes:$YSM_JAR:$DEV/forge-client-moj.jar:$DEV/client-moj.jar:$(ls "$DEV"/libs/*.jar | grep -v '/ysm-' | tr '\n' ':')$LIBS"
OUT=$ROOT/build/cache-check && rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn --release 17 -encoding UTF-8 -cp "$CP" -d "$OUT" tools/package-check/com/ysmnpr/endfield/CacheCheck.java
# YSM's native runtime writes logs/ into the working directory: run inside build/
cd "$OUT" && java -Xmx1g -cp "$OUT:$CP" com.ysmnpr.endfield.CacheCheck "${ARGS[@]}"
