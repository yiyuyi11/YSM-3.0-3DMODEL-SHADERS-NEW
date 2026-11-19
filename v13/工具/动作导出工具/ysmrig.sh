#!/usr/bin/env bash
# Exports a YSM imported 3D model as .ysmrig.json (RigExport in the YSM jar) for the animkit Blockbench plugin.
# Usage: ysmrig.sh <YSM jar> <.minecraft folder | its libraries folder> <model folder | model file> <out.ysmrig.json>
# JAVA: the java binary (default: java on PATH; Java 17+).
set -euo pipefail
if [ $# -ne 4 ]; then
    echo "usage: $0 <YSM jar> <.minecraft | libraries> <model folder | model file> <out.ysmrig.json>" >&2
    exit 2
fi
jar=$1 libraries=$2
[ -d "$libraries/libraries" ] && libraries=$libraries/libraries
classpath=$jar
for artifact in com/google/code/gson/gson it/unimi/dsi/fastutil org/joml/joml net/java/dev/jna/jna \
    org/apache/logging/log4j/log4j-api org/apache/logging/log4j/log4j-core org/apache/logging/log4j/log4j-slf4j2-impl \
    org/slf4j/slf4j-api io/netty/netty-common org/apache/commons/commons-lang3; do
    found=$(find "$libraries/$artifact" -name '*.jar' ! -name '*-sources.jar' 2>/dev/null | sort -V | tail -1)
    if [ -z "$found" ]; then
        echo "ysmrig: missing library $artifact under $libraries" >&2
        exit 3
    fi
    classpath=$classpath:$found
done
exec "${JAVA:-java}" -Xmx2G -cp "$classpath" com.elfmcys.ysm.format.parser.RigExport "$3" "$4"
