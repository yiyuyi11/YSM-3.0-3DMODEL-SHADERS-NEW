#!/usr/bin/env bash
# Builds build/ysm_fitbox-<version>.jar without Gradle, like the endfield_npr render mod:
#  1. javac against Mojang-named MC/Forge classes (MCDEV: client-moj.jar, forge-client-moj.jar and libs/, made by
#     ~/ysm/npr/mod/tools/remap-mc.sh; libs/ also holds the YSM jar)
#  2. jar with resources + MixinConfigs manifest
#  3. ForgeAutoRenamingTool: Mojang member names -> SRG (moj2srg.tsrg), as Forge 1.20.1 runs in production.
#     Mixin targets are written as SRG names with remap = false, so no refmap is needed.
set -euo pipefail
cd "$(dirname "$0")"
DEV=${MCDEV:-$HOME/ysm/npr/mcdev}
MAP=${MOJ2SRG:-$HOME/ysm/npr/mod/tools/moj2srg.tsrg}
VERSION=$(sed -n 's/^version="\(.*\)"/\1/p' src/main/resources/META-INF/mods.toml)
CP="$DEV/forge-client-moj.jar:$DEV/client-moj.jar:$(ls "$DEV"/libs/*.jar | tr '\n' ':')"
# Compile-only: TACZ for the optional bullet mixin (applied at runtime only when TACZ is installed).
CP="$CP:$(ls deps/tacz-*.jar | head -1)"
rm -rf build/classes build/stage && mkdir -p build/classes build/stage
javac -J-Xmx512m --release 17 -proc:none -nowarn -encoding UTF-8 -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
cp -r build/classes/. build/stage/
cp -r src/main/resources/. build/stage/
(cd build/stage && jar --create --file ../ysm_fitbox-moj.jar --manifest META-INF/MANIFEST.MF $(ls -A | grep -v '^META-INF$') META-INF/mods.toml)
java -Xmx512m -jar "$DEV/ForgeAutoRenamingTool-0.1.22-all.jar" --input build/ysm_fitbox-moj.jar \
  --output "build/ysm_fitbox-$VERSION.jar" --map "$MAP" --lib "$DEV/client-moj.jar" \
  --lib "$DEV/forge-client-moj.jar" --threads 1 --log build/fart.log > /dev/null
echo "built $(pwd)/build/ysm_fitbox-$VERSION.jar"
