#!/usr/bin/env bash
# Grab a sequence of frames: ~/mc/shots/<name>_NN.png
# usage: burst.sh <name> <count> <interval_ms>
set -euo pipefail
source "$(dirname "$(readlink -f "$0")")/common.sh"
[ $# -eq 3 ] || die "usage: burst.sh <name> <count> <interval_ms>"
for i in $(seq -w 1 "$2"); do
    t=$(date +%s%N)
    out="$SHOTS/${1}_$i.png"
    # xwd is much faster than import; convert after the burst.
    xwd -root -silent -display "$DISPLAY" > "$out.xwd"
    left=$(( $3 - ($(date +%s%N) - t) / 1000000 ))
    if [ "$left" -gt 0 ]; then sleep "$(awk -v l="$left" 'BEGIN { printf "%.3f", l / 1000 }')"; fi
done
for f in "$SHOTS/${1}"_*.png.xwd; do convert "xwd:$f" "${f%.xwd}" && rm -f "$f"; echo "${f%.xwd}"; done
