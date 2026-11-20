#!/usr/bin/env bash
# Builds build/ysmguard-<version>.jar without Gradle (same scheme as endfield_npr):
#  1. javac against Mojang-named MC/Forge (made once by ~/ysm/npr/mod/tools/remap-mc.sh) + the patched YSM jar
#  2. jar with resources + MixinConfigs manifest
#  3. ForgeAutoRenamingTool: Mojang member names -> SRG (tools/moj2srg.tsrg); YSM and mixin target strings are untouched
set -euo pipefail
cd "$(dirname "$0")"
DEV=${MCDEV:-$HOME/ysm/npr/mcdev}
YSM=${YSM_JAR:-$HOME/ysm/pelica-fix/mod/out/ysm-3.0-dev-forge+mc1.20.1-pelica-motion.jar}
VERSION=$(sed -n 's/^version="\(.*\)"/\1/p' src/main/resources/META-INF/mods.toml)
CP="$YSM:$DEV/forge-client-moj.jar:$DEV/client-moj.jar:$(ls "$DEV"/libs/*.jar | grep -v '/ysm-' | tr '\n' ':')"
rm -rf build/classes build/stage && mkdir -p build/classes build/stage
javac -J-Xmx512m --release 17 -proc:none -Xlint:-options -Xlint:-removal -encoding UTF-8 -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
cp -r build/classes/. build/stage/
cp -r src/main/resources/. build/stage/
(cd build/stage && jar --create --file ../ysmguard-moj.jar --manifest META-INF/MANIFEST.MF $(ls -A | grep -v '^META-INF$') META-INF/mods.toml)
java -Xmx512m -jar "$DEV/ForgeAutoRenamingTool-0.1.22-all.jar" --input build/ysmguard-moj.jar \
  --output "build/ysmguard-$VERSION.jar" --map tools/moj2srg.tsrg --lib "$DEV/client-moj.jar" \
  --lib "$DEV/forge-client-moj.jar" --threads 1 --log build/fart.log > /dev/null
echo "built $(pwd)/build/ysmguard-$VERSION.jar"
