#!/usr/bin/env bash
# Bow and crossbow capture for one model with the game up: test.sh <prefix> <model> [set_wait] [offhand]
set -uo pipefail
B=~/mc/bin
export DISPLAY=:99
p=$1 model=$2 wait=${3:-8} off=${4:-}
W=$(xdotool search --name '^Minecraft' | head -1)
c() { "$B/cmd.sh" "$1" "${2:-0.5}" >/dev/null; }
pose() { c "tp Tester 200.5 -60 0.5 0 $1" 0.6; }
shot() { "$B/shot.sh" "${p}_$1" >/dev/null; }
turn() { for _ in 1 2 3 4; do xdotool mousemove_relative --sync -- "$1" 0; sleep 0.05; done; }
side() { turn 100; sleep 0.8; shot "$1"; turn -100; sleep 0.4; }
walk() { xdotool keydown "$1"; "$B/burst.sh" "${p}_$2" 5 200 >/dev/null; xdotool keyup "$1"; pose 0; sleep 1; }

c "ysm model set Tester $model -" "$wait"
c "clear Tester"
if [ -z "$off" ]; then
    c "item replace entity Tester hotbar.0 with minecraft:bow"
    c "item replace entity Tester hotbar.1 with minecraft:crossbow"
    c "item replace entity Tester weapon.offhand with minecraft:arrow 64"
fi
"$B/cam.sh" front >/dev/null; "$B/hud.sh" off >/dev/null
xdotool windowfocus "$W"; xdotool mousemove --window "$W" 640 360
pose 0; sleep 1.5
[ -z "$off" ] && xdotool key 1 || { xdotool key 3; c "item replace entity Tester weapon.offhand with minecraft:bow"; }
sleep 1.2; shot bow_idle
walk s bow_carrywalk
# Bow: draw, full draw, aim up and down, release.
xdotool mousedown 3
"$B/burst.sh" "${p}_bow_draw" 12 110 >/dev/null
sleep 0.4; shot bow_full
pose -45; sleep 0.8; shot bow_up
pose 45; sleep 0.8; shot bow_down
pose 0; sleep 0.6
xdotool mouseup 3
"$B/burst.sh" "${p}_bow_release" 5 100 >/dev/null
# Back views and drawing while walking backward (toward the front camera).
"$B/cam.sh" back >/dev/null; sleep 0.5
xdotool mousedown 3; sleep 1.6; shot bow_back
pose -45; sleep 0.8; shot bow_back_up
pose 0; sleep 0.3
xdotool mouseup 3; "$B/cam.sh" front >/dev/null; sleep 0.8
xdotool mousedown 3; sleep 1.3; walk s bow_walk; xdotool mouseup 3; sleep 0.5
# Crossbow.
[ -z "$off" ] && xdotool key 2 || c "item replace entity Tester weapon.offhand with minecraft:crossbow"
sleep 1.2; shot xb_idle
walk s xb_carrywalk
xdotool mousedown 3
"$B/burst.sh" "${p}_xb_load" 12 120 >/dev/null
sleep 0.3; shot xb_loaded; side xb_loaded_side
xdotool mouseup 3; sleep 1
shot xb_charged; side xb_charged_side
pose -45; sleep 0.8; shot xb_charged_up
pose 45; sleep 0.8; shot xb_charged_down
pose 0; sleep 0.5
"$B/cam.sh" back >/dev/null; sleep 0.6; shot xb_charged_back
"$B/cam.sh" front >/dev/null; sleep 0.6
walk s xb_walk
# The shot.
xdotool mousedown 3; sleep 0.05; xdotool mouseup 3
"$B/burst.sh" "${p}_xb_shot" 6 80 >/dev/null
# Loading seen from the side (the loading does not follow the look).
sleep 0.5; turn 100; sleep 0.6
xdotool mousedown 3
"$B/burst.sh" "${p}_xb_loadside" 8 160 >/dev/null
xdotool mouseup 3; turn -100
echo done "$p"
