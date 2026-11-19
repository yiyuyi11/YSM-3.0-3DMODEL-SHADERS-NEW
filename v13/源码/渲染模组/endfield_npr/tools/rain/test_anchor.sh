#!/usr/bin/env bash
# Offline test of the rain anchors (RainAnchors is plain Java + JOML; no Minecraft, no GL needed).
set -euo pipefail
cd "$(dirname "$0")/../.."
JOML=$(ls "${MCDEV:-$HOME/ysm/npr/mcdev}"/libs/joml-*.jar | head -1)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac -J-Xmx256m --release 17 -nowarn -cp "$JOML" -d "$OUT" src/main/java/com/ysmnpr/endfield/RainAnchors.java \
  src/test/java/com/ysmnpr/endfield/RainAnchorsTest.java
java -Xmx128m -cp "$OUT:$JOML" com.ysmnpr.endfield.RainAnchorsTest
