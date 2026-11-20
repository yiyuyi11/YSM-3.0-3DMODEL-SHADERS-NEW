#!/usr/bin/env bash
# One-time: copy the Forge 1.20.1-47.4.3 client libraries and make Mojang-named compile jars in $MCDEV.
set -euo pipefail
LIB=${1:-$HOME/mc/client/libraries}
DEV=${MCDEV:-$HOME/ysm/npr/mcdev}
T=$(dirname "$0")
mkdir -p "$DEV/libs"
python3 "$T/genmap.py"
find "$LIB" -name '*.jar' | grep -v -E 'client-1.20.1-[0-9.]+-(srg|slim|extra)|forge-1.20.1-47.4.3-client.jar|natives' \
  | xargs -I{} cp -n {} "$DEV/libs/"
FART=$(find "$LIB" -name 'ForgeAutoRenamingTool-*-all.jar' | head -1); cp -n "$FART" "$DEV/"
java -Xmx512m -jar "$FART" --input "$LIB"/net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-srg.jar \
  --output "$DEV/client-moj.jar" --map "$T/moj2srg.tsrg" --reverse --threads 1 > /dev/null
java -Xmx512m -jar "$FART" --input "$LIB"/net/minecraftforge/forge/1.20.1-47.4.3/forge-1.20.1-47.4.3-client.jar \
  --output "$DEV/forge-client-moj.jar" --map "$T/moj2srg.tsrg" --reverse --threads 1 \
  --lib "$LIB"/net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-srg.jar > /dev/null
echo "compile deps in $DEV (also copy the YSM jar into $DEV/libs)"
