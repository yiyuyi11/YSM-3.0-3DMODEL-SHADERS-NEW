#!/usr/bin/env bash
# Offline mixin application check: run.sh <fitbox.jar> <battlefield-movement.jar> [class#method,...]
# Applies both mods' mixin configs with Mixin 0.8.5 to Forge 1.20.1's production classes (no game launch) and prints,
# per target method, the merged mixin handlers in execution order with the return points, then every mixin applied.
set -euo pipefail
T=$(cd "$(dirname "$0")" && pwd)
DEV=${MCDEV:-$HOME/ysm/npr/mcdev}
SRG_JAR=${SRG_JAR:-$HOME/mc/client/libraries/net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-srg.jar}
FORGE_LIB=$(dirname "$SRG_JAR")/../../../minecraftforge/forge/1.20.1-47.4.3
TACZ=${TACZ_JAR:-$(ls "$HOME"/ysm/bfm-port/libs/tacz-*.jar | head -1)}
PAL=$(ls "$HOME"/ysm/bfm-port/libs/player-animation-lib-*.jar | head -1)
FIT=$1 BFM=$2
TARGETS=${3:-net/minecraft/world/entity/Entity#m_20299_,net/minecraft/world/entity/Entity#m_146892_,net/minecraft/world/entity/Entity#m_20192_,net/minecraft/world/entity/Entity#m_20188_,net/minecraft/world/entity/Entity#m_20011_,net/minecraft/world/entity/Entity#m_142242_,net/minecraft/client/Camera#m_90575_,cn/bfm/ActionEyeHeight#target,cn/bfm/ProneGeometry#headPosition,cn/bfm/ProneGeometry#reach}
LIBS="$DEV/libs/mixin-0.8.5.jar:$DEV/libs/asm-9.7.1.jar:$DEV/libs/asm-tree-9.7.1.jar:$DEV/libs/asm-commons-9.7.1.jar:$DEV/libs/asm-analysis-9.7.1.jar:$DEV/libs/asm-util-9.7.1.jar:$DEV/libs/guava-31.1-jre.jar:$DEV/libs/gson-2.10.1.jar:$DEV/libs/log4j-api-2.19.0.jar:$DEV/libs/log4j-core-2.19.0.jar"
OUT="$T/build"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn --release 17 -cp "$LIBS:$DEV/libs/fmlloader-1.20.1-47.4.3.jar" -d "$OUT" $(find "$T/src" -name '*.java')
cp -r "$T/src/META-INF" "$OUT/"
CLASSES="$FIT:$BFM:$TACZ:$PAL:$FORGE_LIB/forge-1.20.1-47.4.3-client.jar:$SRG_JAR:$FORGE_LIB/forge-1.20.1-47.4.3-universal.jar:$DEV/libs/mixin-0.8.5.jar"
java -Dlog4j2.configurationFile="$T/src/log4j2-mixincheck.xml" -Dmixincheck.mods=battlefield_movement,tacz -cp "$OUT:$LIBS:$DEV/libs/fmlloader-1.20.1-47.4.3.jar:$(ls $DEV/libs/forgespi-*.jar | head -1):$(ls $DEV/libs/logging-*.jar | head -1):$DEV/libs/slf4j-api-2.0.1.jar:$DEV/libs/log4j-slf4j2-impl-2.19.0.jar:$FIT:$BFM:$TACZ" \
  mixincheck.MixinCheck "$CLASSES" ysm_fitbox.mixins.json,battlefield_movement.mixins.json "$TARGETS"
