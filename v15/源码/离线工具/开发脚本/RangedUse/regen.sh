#!/usr/bin/env bash
# Builds the jar, regenerates both models' clips offline and scans pitch continuity.
set -e
cd ~/ysm/pelica-fix/work-RangedUse
mod/build_jar.sh ~/ysm/pelica-full/06-YSM模组/mods/ysm-v18-win.jar ~/.cache/mc/ysm-3.0-dev-forge+mc1.20.1.jar ~/mc/client/libraries ~/ysm/pelica-anim/动作源数据 $PWD/out.jar 2>&1 | grep -E "error|\.java:[0-9]+|wrote"
LIBS=$(find ~/mc/client/libraries -name '*.jar' | tr '\n' ':')
cd gen
for m in pelica:/home/user/mc/server/ysm/custom/pelica/pelica.vrm vrm1:/home/user/mc/server/ysm/custom/vrm1_sample/vrm1.vrm; do
  n=${m%%:*}; f=${m#*:}
  YSM_NATIVE_PATH=$HOME/mc/server/home/.ysm/unstable/libysm-core-3.0-dev-forge+mc1.20.1.so java -cp "$HOME/ysm/pelica-fix/tools/ysmgen/out2:../out.jar:$LIBS" com.elfmcys.ysm.format.parser.GenAnim $f $n.main.json $n.arm.json $n.skel.json 2>&1 | grep -E "Exception|Error|at com" | head -5
done
ls -la *.main.json | awk '{print $5, $9}'
python3 scan.py pelica | grep -v "^  " ; python3 scan.py vrm1 | grep -v "^  "
