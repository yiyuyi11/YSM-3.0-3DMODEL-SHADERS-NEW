#!/usr/bin/env bash
# Offline test of the rain/wet state machine (RainWetState is plain Java; no Minecraft classes needed).
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac -J-Xmx256m --release 17 -nowarn -d "$OUT" src/main/java/com/ysmnpr/endfield/RainWetState.java \
  src/test/java/com/ysmnpr/endfield/RainWetStateTest.java
java -Xmx128m -cp "$OUT" com.ysmnpr.endfield.RainWetStateTest
