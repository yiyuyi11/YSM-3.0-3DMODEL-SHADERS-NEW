#!/usr/bin/env bash
# Switch camera: first | back (third person behind) | front (third person facing).
# F5 cycles first -> back -> front; current state is tracked in run/cam.state
# (reset to first person by up.sh when the client starts).
set -euo pipefail
source "$(dirname "$(readlink -f "$0")")/common.sh"
case "${1:-}" in first) want=0 ;; back) want=1 ;; front) want=2 ;; *) die "usage: cam.sh first|back|front" ;; esac
cur=$(cat "$RUN/cam.state" 2>/dev/null || echo 0)
n=$(( (want - cur + 3) % 3 ))
for _ in $(seq "$n"); do mc_key F5; sleep 0.3; done
echo "$want" > "$RUN/cam.state"
echo "camera: $1"
