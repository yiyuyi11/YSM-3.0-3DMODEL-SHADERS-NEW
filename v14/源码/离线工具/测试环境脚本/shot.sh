#!/usr/bin/env bash
# Capture the whole X display to ~/mc/shots/<name>.png and print the path.
# usage: shot.sh <name>
set -euo pipefail
source "$(dirname "$(readlink -f "$0")")/common.sh"
[ $# -eq 1 ] || die "usage: shot.sh <name>"
out="$SHOTS/${1%.png}.png"
timeout 20 import -display "$DISPLAY" -window root "$out"
echo "$out"
