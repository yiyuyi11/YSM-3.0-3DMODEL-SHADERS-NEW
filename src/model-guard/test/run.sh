#!/usr/bin/env bash
# Offline checks after ./build.sh:
#  1. CryptoTest: handshake + record protection, encrypted cache channel, cache path policy (pure Java).
#  2. RefCheck: every Minecraft member the built jar references exists in the production (SRG) client classes.
set -euo pipefail
cd "$(dirname "$0")/.."
DEV=${MCDEV:-$HOME/ysm/npr/mcdev}
LIB=${MCLIB:-$HOME/mc/client/libraries}
SLF=$(ls "$DEV"/libs/slf4j-api-*.jar | head -1)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac -proc:none -d "$OUT" -cp "build/classes:$SLF" test/CryptoTest.java test/RefCheck.java
java -cp "$OUT:build/classes:$SLF" CryptoTest 2>&1 | grep -v SLF4J
JAR="$PWD/build/ysmguard-$(sed -n 's/^version="\(.*\)"/\1/p' src/main/resources/META-INF/mods.toml).jar"
mkdir "$OUT/jar" && (cd "$OUT/jar" && unzip -q "$JAR")
(cd "$OUT/jar" && for c in $(find com -name '*.class'); do javap -c -p "$c" | grep -oE "(Method|Field|InterfaceMethod) net/minecraft[^ ]*" || true; done) | sort -u > "$OUT/refs.txt"
RT="$LIB/net/minecraftforge/forge/1.20.1-47.4.3/forge-1.20.1-47.4.3-client.jar:$(find "$LIB" -name 'client-1.20.1-*-srg.jar'):$(find "$LIB" -name '*.jar' | grep -v -E 'client-1.20.1|forge-1.20.1-47.4.3-client' | tr '\n' ':')"
java -cp "$OUT" RefCheck "$OUT/refs.txt" "$RT"
