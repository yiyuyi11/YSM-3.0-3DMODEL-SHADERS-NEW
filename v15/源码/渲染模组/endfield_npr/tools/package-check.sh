#!/usr/bin/env bash
# Offline round trip of the model package parsing (ModelPackageData) on serialized ModelData blobs.
# usage: tools/package-check.sh <modeldata.bin>...   (ysmgen GenNpr writes <prefix>.modeldata.bin)
set -euo pipefail
cd "$(dirname "$0")/.."
GSON=$(find "${MC_LIBRARIES:-$HOME/mc/client/libraries}" -name 'gson-2.10.1.jar' | head -1)
ANN=$(ls "${MCDEV:-$HOME/ysm/npr/mcdev}"/libs/ysm-*.jar | head -1) # carries org.jetbrains.annotations
OUT=build/package-check && rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn --release 17 -encoding UTF-8 -cp "$GSON:$ANN" -d "$OUT" \
  src/main/java/com/ysmnpr/endfield/ModelPackageData.java tools/package-check/com/ysmnpr/endfield/PackageCheck.java
java -cp "$OUT:$GSON" com.ysmnpr.endfield.PackageCheck "$@"
