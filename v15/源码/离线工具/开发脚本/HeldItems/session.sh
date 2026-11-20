#!/usr/bin/env bash
# Held-item clipping capture: session.sh <tag> <model> [items...]
# Assumes the game is up (lock held) and the model is set. Writes ~/mc/shots/hi_<tag>_<item>_<state>_NN.png.
set -uo pipefail
B=~/mc/bin
tag=$1; model=$2; shift 2
items=("$@")
[ ${#items[@]} -gt 0 ] || items=(diamond_sword diamond_pickaxe diamond_axe diamond_shovel bow crossbow stone apple)
X=0.5; Y=-60; Z=0.5
c() { "$B/cmd.sh" "$1" "${2:-0.4}" >/dev/null; }
tpo() { c "tp Tester $X $Y $Z $1 0" 0.8; }
burst() { "$B/burst.sh" "hi_${tag}_$1" "$2" "$3" >/dev/null; }
hold() { xdotool keydown "$@"; }
release() { xdotool keyup w a s d Control_L Shift_L; }
source "$B/common.sh"
mc_focus >/dev/null
"$B/hud.sh" off >/dev/null
for item in "${items[@]}"; do
    off=shield; [ "$item" = apple ] && off=totem_of_undying
    c "item replace entity Tester weapon.mainhand with minecraft:$item"
    c "item replace entity Tester weapon.offhand with minecraft:$off"
    "$B/cam.sh" front >/dev/null
    tpo 0; sleep 1.5
    burst "${item}_idle_front" 1 100
    # Head turns 70 degrees right: the body stays, so the front camera sees her left side... and -70 the right.
    tpo 70; sleep 1.2; burst "${item}_idle_sideL" 1 100
    tpo -70; sleep 1.2; burst "${item}_idle_sideR" 1 100
    tpo 0; sleep 0.6
    "$B/move.sh" walk >/dev/null; sleep 0.8; burst "${item}_walk_front" 5 180; release
    tpo 0; sleep 0.5
    hold d; sleep 0.8; burst "${item}_walk_sideR" 5 180; release
    tpo 0; sleep 0.5
    hold Control_L w; sleep 0.8; burst "${item}_run_front" 5 150; release
    tpo 0; sleep 0.5
    hold Control_L w a; sleep 0.8; burst "${item}_run_sideL" 5 150; release
    tpo 0; sleep 0.5
    hold Shift_L; sleep 0.8; burst "${item}_sneak_idle" 1 100; hold w; sleep 0.5; burst "${item}_sneak_walk" 4 200; release
    tpo 0; sleep 0.5
    hold s; sleep 0.8; burst "${item}_back_front" 4 180; release
    "$B/cam.sh" back >/dev/null
    tpo 0; sleep 0.8
    burst "${item}_idle_back" 1 100
    hold w; sleep 0.8; burst "${item}_walk_back" 4 180; release
    tpo 0; sleep 0.3
done
"$B/cam.sh" front >/dev/null
echo done
