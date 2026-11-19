#!/usr/bin/env bash
# run_session.sh <tag> <model> [items...]: game must be up with the wanted jar; sets the model, captures every item.
set -uo pipefail
B=~/mc/bin
W=$(cd "$(dirname "$0")" && pwd)
tag=$1; model=$2; shift 2
"$B/cmd.sh" "ysm model reload" 3 >/dev/null
"$B/cmd.sh" "ysm model set Tester $model -" 2 >/dev/null
# A first set after a revision change converts the model again.
sleep 25
"$B/cmd.sh" "gamemode creative Tester" 1 >/dev/null
"$B/cmd.sh" "effect give Tester minecraft:night_vision 99999 0 true" 1 >/dev/null
"$W/session.sh" "$tag" "$model" "$@"
