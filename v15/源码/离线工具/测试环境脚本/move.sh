#!/usr/bin/env bash
# Hold movement keys on the game window.
# usage: move.sh walk|back|left|right|sprint|sneak|jump|stop [seconds]
#   with seconds: hold, then release; without: keep holding until "move.sh stop".
set -euo pipefail
source "$(dirname "$(readlink -f "$0")")/common.sh"
case "${1:-}" in
    walk) keys=(w) ;; back) keys=(s) ;; left) keys=(a) ;; right) keys=(d) ;;
    sprint) keys=(Control_L w) ;; sneak) keys=(Shift_L) ;; jump) keys=(space) ;;
    stop) mc_focus >/dev/null; xdotool keyup w a s d Control_L Shift_L space; echo "stopped"; exit 0 ;;
    *) die "usage: move.sh walk|back|left|right|sprint|sneak|jump|stop [seconds]" ;;
esac
mc_focus >/dev/null
xdotool keydown "${keys[@]}"
if [ -n "${2:-}" ]; then
    sleep "$2"; xdotool keyup "${keys[@]}"; echo "$1 for ${2}s"
else
    echo "$1 (holding; run move.sh stop)"
fi
