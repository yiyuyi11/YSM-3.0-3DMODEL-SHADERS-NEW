#!/usr/bin/env bash
# Offline fit check: run.sh <out dir> <model file|default>...
# Runs YSM's humanoid import (needs YSM's native library) and the mod's fitter; writes <name>.report.txt,
# <name>.overlay.png and <name>.fit.bin into <out dir>.
set -euo pipefail
T=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$T/../.." && pwd)
YSM_JAR=${YSM_JAR:-$HOME/ysm/pelica-fix/mod/out/ysm-3.0-dev-forge+mc1.20.1-pelica-motion.jar}
LIBS=$(find "${MC_LIBS:-$HOME/mc/client/libraries}" -name '*.jar' | tr '\n' ':')
export YSM_NATIVE_PATH=${YSM_NATIVE_PATH:-$HOME/mc/server/home/.ysm/unstable/libysm-core-3.0-dev-forge+mc1.20.1.so}
OUT="$T/build"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 --release 17 -cp "$YSM_JAR:$LIBS" -d "$OUT" \
  $(find "$ROOT/src/main/java/com/ysmfit/fitbox/core" -name '*.java') "$ROOT/src/main/java/com/ysmfit/fitbox/ysm/YsmGeometry.java" "$ROOT/src/main/java/com/ysmfit/fitbox/ysm/ProgramEval.java" \
  $(find "$T/src" -name "*.java")
MAIN=${MAIN:-FitOffline}
java -Xmx3g -Djava.awt.headless=true -cp "$OUT:$YSM_JAR:$LIBS" com.elfmcys.ysm.format.parser.$MAIN "$@"
