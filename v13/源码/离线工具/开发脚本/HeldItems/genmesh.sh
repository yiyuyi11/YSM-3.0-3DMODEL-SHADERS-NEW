#!/usr/bin/env bash
# genmesh.sh <jar> <model> <prefix>
W=$(cd "$(dirname "$0")" && pwd)
YSM_NATIVE_PATH=$HOME/mc/server/home/.ysm/unstable/libysm-core-3.0-dev-forge+mc1.20.1.so java -cp "$W/gen/classes:$1:$(find ~/mc/client/libraries -name '*.jar' | tr '\n' ':')" com.elfmcys.ysm.format.parser.GenMesh "$2" "$3" 2>&1 | tail -2
