#!/usr/bin/env bash
# Regenerates the spring bone message classes (Spring*Proto) in mod/src from mod/proto, the v18 schema with the
# spring fields this patch adds (joint angle limits, collider types, chain centers). Same generator and options as
# YSM's gradle build (protoc + protoc-gen-quickbuf 1.4.1-ysm, quickbuf relocated to com.elfmcys.quickbuf); from the
# unmodified schema it reproduces the v18 jar's classes instruction for instruction.
#
# Usage: gen_springs.sh <YesSteveModel checkout (libs/protoc-gen-quickbuf-1.4.1-ysm-shaded.jar)> [protoc]
# protoc defaults to `python3 -m grpc_tools.protoc` (pip install grpcio-tools).
set -euo pipefail
ysm=$1
protoc=${2:-}
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
printf '#!/bin/sh\nexec java -jar "%s" "$@"\n' "$ysm/libs/protoc-gen-quickbuf-1.4.1-ysm-shaded.jar" > "$work/plugin"
chmod +x "$work/plugin"
mkdir "$work/out"
options="replace_package=ysm=com.elfmcys.ysm.proto,replace_package=^mixel=com.elfmcys.ysm.proto.mixel,indent=4"
if [ -n "$protoc" ]; then
    "$protoc" --plugin=protoc-gen-quickbuf="$work/plugin" -I "$here" --quickbuf_out="$options:$work/out" \
        asset/model/data/geo_model.proto
else
    python3 -m grpc_tools.protoc --plugin=protoc-gen-quickbuf="$work/plugin" -I "$here" \
        --quickbuf_out="$options:$work/out" asset/model/data/geo_model.proto
fi
package=com/elfmcys/ysm/proto/mixel/asset/model/data
target="$here/../src/$package"
mkdir -p "$target"
for message in SpringJointProto SpringColliderProto SpringChainProto SpringBonesProto; do
    sed 's/us\.hebi\.quickbuf/com.elfmcys.quickbuf/g' "$work/out/$package/$message.java" > "$target/$message.java"
done
echo "wrote $target/Spring{Joint,Collider,Chain,Bones}Proto.java"
