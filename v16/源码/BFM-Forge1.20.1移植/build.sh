#!/usr/bin/env bash
# Builds build/libs/battlefield-movement-<version>-forge-1.20.1.jar without Gradle (Forge 1.20.1-47.4.x, Java 17).
#  1. javac against Mojang-named MC/Forge classes (MCDEV: client-moj.jar, forge-client-moj.jar and libs/, made by
#     ~/ysm/npr/mod/tools/remap-mc.sh), with TACZ 1.2.0 and PlayerAnimator 1.0.2 from libs/ as compile-only jars.
#     The Mixin annotation processor writes the refmap (Mojang -> SRG) as MixinGradle does, and the mixin classes'
#     own @Shadow member mappings (outTsrgFile).
#  2. jar with resources, the refmap and the MixinConfigs manifest.
#  3. ForgeAutoRenamingTool: Mojang member names -> SRG, as Forge 1.20.1 runs in production (reobf), with the
#     mixin shadow mappings appended so shadowed members get their SRG names too (MixinGradle's extraMappings).
# The Mojang -> SRG map is build/moj2srg-full.tsrg, made once by tools/fullmap.py from client-moj.jar and the SRG
# client jar (SRG_JAR): the shared moj2srg.tsrg misses members of unobfuscated classes such as MinecraftServer.
set -euo pipefail
cd "$(dirname "$0")"
DEV=${MCDEV:-$HOME/ysm/npr/mcdev}
SRG_JAR=${SRG_JAR:-$HOME/mc/client/libraries/net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-srg.jar}
MAP=build/moj2srg-full.tsrg
mkdir -p build
[ -s "$MAP" ] || python3 tools/fullmap.py "$DEV/client-moj.jar" "$SRG_JAR" "$MAP"
VERSION=$(sed -n 's/^version *= *"\(.*\)"/\1/p' src/main/resources/META-INF/mods.toml | head -1)
OUT="build/libs/battlefield-movement-$VERSION-forge-1.20.1.jar"
MC_CP="$DEV/forge-client-moj.jar:$DEV/client-moj.jar:$(ls "$DEV"/libs/*.jar | grep -v ysm- | tr '\n' ':')"
CP="$MC_CP$(ls libs/tacz-*.jar | head -1):$(ls libs/player-animation-lib-forge-*.jar | head -1)"
rm -rf build/classes build/stage && mkdir -p build/classes build/stage build/libs
javac -J-Xmx1g --release 17 -nowarn -encoding UTF-8 -cp "$CP" -d build/classes \
  -processorpath "$MC_CP" \
  -processor org.spongepowered.tools.obfuscation.MixinObfuscationProcessorInjection,org.spongepowered.tools.obfuscation.MixinObfuscationProcessorTargets \
  -AreobfTsrgFile="$MAP" -AoutRefMapFile="$(pwd)/build/stage/battlefield_movement.refmap.json" \
  -AoutTsrgFile="$(pwd)/build/mixin-shadows.tsrg" -AmappingTypes=tsrg -AdefaultObfuscationEnv=searge \
  $(find src/main/java -name '*.java')
# Shadow mappings, one per line ("owner name srg" for fields, "owner name desc srg" for methods), regrouped as
# TSRG v1 class blocks and appended to the Mojang->SRG map for the mixin classes.
cp "$MAP" build/reobf.tsrg
awk 'NF == 3 { m[$1] = m[$1] "\t" $2 " " $3 "\n" } NF == 4 { m[$1] = m[$1] "\t" $2 " " $3 " " $4 "\n" }
     END { for (o in m) printf "%s %s\n%s", o, o, m[o] }' build/mixin-shadows.tsrg >> build/reobf.tsrg
cp -r build/classes/. build/stage/
cp -r src/main/resources/. build/stage/
(cd build/stage && jar --create --file ../battlefield-movement-moj.jar --manifest META-INF/MANIFEST.MF \
  $(ls -A | grep -v '^META-INF$') META-INF/mods.toml)
java -Xmx1g -jar "$DEV/ForgeAutoRenamingTool-0.1.22-all.jar" --input build/battlefield-movement-moj.jar \
  --output "$OUT" --map build/reobf.tsrg --lib "$DEV/client-moj.jar" --lib "$DEV/forge-client-moj.jar" \
  --threads 1 --log build/fart.log > /dev/null
# Every Minecraft member the jar uses must exist under its SRG name at runtime (catches gaps in the map).
FORGE_LIB=$(dirname "$SRG_JAR")/../../../minecraftforge/forge/1.20.1-47.4.3
python3 tools/checkrefs.py "$OUT" "$FORGE_LIB/forge-1.20.1-47.4.3-client.jar" "$SRG_JAR" \
  "$FORGE_LIB/forge-1.20.1-47.4.3-universal.jar"
echo "built $(pwd)/$OUT"
