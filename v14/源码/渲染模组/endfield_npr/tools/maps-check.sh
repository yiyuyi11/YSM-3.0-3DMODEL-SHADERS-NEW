#!/usr/bin/env bash
# Offline run of the per-material map compositor (NprMaps.build) on a serialized ModelData blob (ysmgen GenNpr writes
# <prefix>.modeldata.bin): prints layers/table and writes the layers as PNG + raw RGBA into <outDir>.
# usage: tools/maps-check.sh <modeldata.bin> <outDir> [resolution] [budgetMB]   (run ./build.sh first)
set -euo pipefail
cd "$(dirname "$0")/.."
DEV=${MCDEV:-$HOME/ysm/npr/mcdev}
LIBS=$(find "${MC_LIBRARIES:-$HOME/mc/client/libraries}" -name '*.jar' | grep -v -i '/ysm' | tr '\n' ':')
CP="build/classes:$DEV/forge-client-moj.jar:$DEV/client-moj.jar:$(ls "$DEV"/libs/*.jar | tr '\n' ':')$LIBS"
OUT=build/maps-check && rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn --release 17 -encoding UTF-8 -cp "$CP" -d "$OUT" tools/package-check/com/ysmnpr/endfield/MapsCheck.java
java -Xmx1g -cp "$OUT:$CP" com.ysmnpr.endfield.MapsCheck "$@"
