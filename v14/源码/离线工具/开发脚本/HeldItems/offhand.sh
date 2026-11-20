#!/usr/bin/env bash
# offhand.sh <tag>: off-hand visibility (game up, model set). Writes ~/mc/shots/hi_<tag>_<main>+<off>_<state>_NN.png.
set -uo pipefail
B=~/mc/bin
source "$B/common.sh"
tag=$1
c() { "$B/cmd.sh" "$1" "${2:-0.4}" >/dev/null; }
tpo() { c "tp Tester 0.5 -60 0.5 $1 0" 0.8; }
burst() { "$B/burst.sh" "hi_${tag}_$1" "$2" "$3" >/dev/null; }
mc_focus >/dev/null
"$B/hud.sh" off >/dev/null
for pair in air+shield air+totem_of_undying diamond_sword+shield stone+totem_of_undying; do
    main=${pair%+*}; off=${pair#*+}
    c "item replace entity Tester weapon.mainhand with minecraft:$main"
    c "item replace entity Tester weapon.offhand with minecraft:$off"
    "$B/cam.sh" front >/dev/null
    tpo 0; sleep 1.5; burst "${pair}_idle_front" 1 100
    tpo 70; sleep 1.2; burst "${pair}_idle_sideL" 1 100
    tpo -70; sleep 1.2; burst "${pair}_idle_sideR" 1 100
    tpo 0; sleep 0.5
    xdotool keydown w; sleep 0.8; burst "${pair}_walk_front" 4 180; xdotool keyup w
    tpo 0; sleep 0.5
    xdotool keydown a; sleep 0.8; burst "${pair}_walk_sideL" 4 180; xdotool keyup a
    tpo 0; sleep 0.5
    xdotool keydown Shift_L; sleep 0.8; burst "${pair}_sneak_idle" 1 100
    xdotool keydown w; sleep 0.5; burst "${pair}_sneak_walk" 3 200; xdotool keyup w Shift_L
    "$B/cam.sh" back >/dev/null
    tpo 0; sleep 0.8; burst "${pair}_idle_back" 1 100
    "$B/cam.sh" first >/dev/null
    tpo 0; sleep 1.0; burst "${pair}_first" 1 100
done
"$B/cam.sh" front >/dev/null
echo done
