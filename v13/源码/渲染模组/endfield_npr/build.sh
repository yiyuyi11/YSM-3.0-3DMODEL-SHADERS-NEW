#!/usr/bin/env bash
# Builds build/endfield_npr-<version>.jar (version from META-INF/mods.toml) without Gradle:
#  1. javac against Mojang-named MC/Forge classes (mcdev/client-moj.jar, forge-client-moj.jar, made by tools/remap-mc.sh)
#  2. jar with resources + MixinConfigs manifest
#  3. ForgeAutoRenamingTool: Mojang member names -> SRG (tools/moj2srg.tsrg), as Forge 1.20.1 runs in production
set -euo pipefail
cd "$(dirname "$0")"
DEV=${MCDEV:-$HOME/ysm/npr/mcdev}
VERSION=$(sed -n 's/^version="\(.*\)"/\1/p' src/main/resources/META-INF/mods.toml)
CP="$DEV/forge-client-moj.jar:$DEV/client-moj.jar:$(ls "$DEV"/libs/*.jar | tr '\n' ':')"
rm -rf build/classes build/stage && mkdir -p build/classes build/stage
javac -J-Xmx512m --release 17 -nowarn -encoding UTF-8 -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
cp -r build/classes/. build/stage/
cp -r src/main/resources/. build/stage/
(cd build/stage && jar --create --file ../endfield_npr-moj.jar --manifest META-INF/MANIFEST.MF $(ls -A | grep -v '^META-INF$') META-INF/mods.toml)
java -Xmx512m -jar "$DEV/ForgeAutoRenamingTool-0.1.22-all.jar" --input build/endfield_npr-moj.jar \
  --output "build/endfield_npr-$VERSION.jar" --map tools/moj2srg.tsrg --lib "$DEV/client-moj.jar" \
  --lib "$DEV/forge-client-moj.jar" --threads 1 --log build/fart.log > /dev/null
echo "built $(pwd)/build/endfield_npr-$VERSION.jar"
