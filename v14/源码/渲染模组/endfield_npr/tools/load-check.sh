#!/usr/bin/env bash
# Offline timing of the package loader's worker-thread half (package parse, PNG decodes, NprMaps.build) on serialized
# ModelData blobs (ysmgen GenNpr writes <prefix>.modeldata.bin).
# usage: tools/load-check.sh <resolution> <budgetMB> <repeat> <modeldata.bin>...   (run ./build.sh first)
set -euo pipefail
cd "$(dirname "$0")/.."
DEV=${MCDEV:-$HOME/ysm/npr/mcdev}
LIBS=$(find "${MC_LIBRARIES:-$HOME/mc/client/libraries}" -name '*.jar' | grep -v -i '/ysm' | tr '\n' ':')
CP="build/classes:$DEV/forge-client-moj.jar:$DEV/client-moj.jar:$(ls "$DEV"/libs/*.jar | tr '\n' ':')$LIBS"
OUT=build/load-check && rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn --release 17 -encoding UTF-8 -cp "$CP" -d "$OUT" tools/package-check/com/ysmnpr/endfield/LoadCheck.java
java -Xmx1g ${LOAD_CHECK_JVM:-} -cp "$OUT:$CP" com.ysmnpr.endfield.LoadCheck "$@"
