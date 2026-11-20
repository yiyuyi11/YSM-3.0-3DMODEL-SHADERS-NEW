# Helpers for ToolSwings in-game tests. Source after the game is up.
export DISPLAY=:99
B=~/mc/bin
c() { $B/cmd.sh "$1" "${2:-0.3}" >/dev/null; }
hand() { c "item replace entity Tester weapon.mainhand with minecraft:$1" 0.5; }
face() { c "tp Tester 200.5 -60 0.5 ${1:-180} ${2:-0}" 0.8; }
# refill <block> <x y z> <seconds>: keeps re-placing a block (background) so mining never runs out
refill() { ( end=$(( $(date +%s) + $3 )); while [ $(date +%s) -lt $end ]; do $B/cmd.sh "setblock $2 $1" 0.35 >/dev/null 2>&1; done ) & }
# mine <name> <frames> <interval>: hold attack and burst
mine() { xdotool mousedown 1; sleep 0.4; $B/burst.sh "ts_$1" "$2" "$3" >/dev/null; xdotool mouseup 1; }
# clicks <name> <frames> <interval> <clicks> <gap>: attack clicks while bursting
clicks() { ( for i in $(seq $4); do xdotool click 1; sleep $5; done ) & sleep 0.05; $B/burst.sh "ts_$1" "$2" "$3" >/dev/null; wait; }
