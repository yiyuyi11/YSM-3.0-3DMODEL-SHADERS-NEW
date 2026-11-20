#!/usr/bin/env bash
# Teleport the player to a fixed spot/orientation for reproducible shots.
# usage: pose.sh [x y z yaw pitch]   (default: $POSE from common.sh = 0.5 -60 0.5 0 0)
set -euo pipefail
source "$(dirname "$(readlink -f "$0")")/common.sh"
p=${*:-$POSE}
"$MC/bin/cmd.sh" "tp $PLAYER $p" 1
