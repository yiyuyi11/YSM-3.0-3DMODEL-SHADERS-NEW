#!/usr/bin/env bash
# sheets.sh <tag> [items...]: one contact sheet per item: rows = states, frames cropped around the player.
set -uo pipefail
S=~/mc/shots
W=$(cd "$(dirname "$0")" && pwd)
tag=$1; shift
items=("$@")
[ ${#items[@]} -gt 0 ] || items=(diamond_sword diamond_pickaxe diamond_axe diamond_shovel bow crossbow stone apple)
out=$W/sheets/$tag; mkdir -p "$out"
for item in "${items[@]}"; do
    rows=()
    for state in idle_front idle_sideL idle_sideR walk_front walk_sideR run_front run_sideL sneak_idle sneak_walk back_front idle_back walk_back; do
        files=( $S/hi_${tag}_${item}_${state}_*.png )
        [ -e "${files[0]}" ] || continue
        row=$out/.row_${item}_${state}.png
        montage "${files[@]}" -crop 380x400+450+200 +repage -tile x1 -geometry +2+2 -label '' "$row" 2>/dev/null
        convert "$row" -gravity NorthWest -background white -splice 0x18 -pointsize 14 -annotate +4+2 "$state" "$row"
        rows+=("$row")
    done
    [ ${#rows[@]} -gt 0 ] && convert "${rows[@]}" -background white -gravity West -append "$out/${item}.jpg" && echo "$out/${item}.jpg"
    rm -f "${rows[@]}"
done
