#!/usr/bin/env bash
# Show/hide the HUD (F1). State tracked in run/hud.state (reset to "on" by up.sh).
set -euo pipefail
source "$(dirname "$(readlink -f "$0")")/common.sh"
case "${1:-}" in on|off) ;; *) die "usage: hud.sh on|off" ;; esac
cur=$(cat "$RUN/hud.state" 2>/dev/null || echo on)
[ "$cur" = "$1" ] || mc_key F1
echo "$1" > "$RUN/hud.state"
echo "hud: $1"
