#!/usr/bin/env bash
# ToolSwings in-game session: run after up.sh with the test jar installed. Arg: model id, tag
set -u
source ~/ysm/pelica-fix/work-ToolSwings/test/lib.sh
M=${1:-pelica}; T=${2:-a}
c "ysm model reload" 3; c "ysm model set Tester $M -" 30
c "gamemode survival Tester" 0.5
c "effect give Tester minecraft:resistance infinite 4 true"
c "effect give Tester minecraft:saturation infinite 4 true"
c "fill 196 -60 -8 205 -55 -1 air" 0.5
$B/hud.sh off >/dev/null
# 1 pickaxe on stone at eye level, 3 blocks ahead (wooden pick: slow, continuous)
for tool in hoe:wooden_hoe pickaxe:wooden_pickaxe; do n=${tool%%:*}; item=${tool#*:}
  hand $item; face 180 0; c "setblock 200 -59 -3 stone"; c "setblock 200 -60 -3 stone"
  for cam in back front; do $B/cam.sh $cam >/dev/null; sleep 0.6
    refill stone "200 -59 -3" 4; mine "${M}_${T}_${n}_stone_$cam" 14 70; wait; done
done
# 2 pickaxe looking down at the ground block ahead
hand wooden_pickaxe; face 180 55; c "setblock 200 -61 -1 stone"
$B/cam.sh back >/dev/null; sleep 0.6; refill stone "200 -61 -1" 4; mine "${M}_${T}_pickaxe_down_back" 12 70; wait
c "setblock 200 -61 -1 grass_block"
# 3 axe on an oak log trunk
hand wooden_axe; face 180 0; c "fill 200 -60 -3 200 -57 -3 oak_log"
for cam in back front; do $B/cam.sh $cam >/dev/null; sleep 0.6
  refill oak_log "200 -59 -3" 4; mine "${M}_${T}_axe_log_$cam" 14 70; wait; done
c "fill 200 -60 -3 200 -57 -3 air"
# 4 shovel on dirt at the feet, looking down
hand wooden_shovel; face 180 45; c "setblock 200 -61 -2 dirt"
for cam in back front; do $B/cam.sh $cam >/dev/null; sleep 0.6
  refill dirt "200 -61 -2" 4; mine "${M}_${T}_shovel_dirt_$cam" 14 70; wait; done
c "setblock 200 -61 -2 grass_block"
# 5 sword on a NoAI husk
hand diamond_sword; face 180 10
c "kill @e[type=husk]"; c 'summon minecraft:husk 200.5 -60 -2 {NoAI:1b,PersistenceRequired:1b,Silent:1b,Health:1000f,Attributes:[{Name:"generic.max_health",Base:1000d}]}' 1
for cam in front back; do $B/cam.sh $cam >/dev/null; sleep 0.6
  clicks "${M}_${T}_sword_$cam" 22 60 4 0.7; done
c "kill @e[type=husk]"

# 6 swings while walking (sword and pickaxe on air), front camera
for item in diamond_sword:sword iron_pickaxe:pickaxe; do n=${item#*:}
  hand ${item%%:*}; face 0 0; $B/cam.sh front >/dev/null; sleep 0.5
  xdotool keydown w; sleep 0.6; clicks "${M}_${T}_walk_${n}_front" 18 60 3 0.7; xdotool keyup w
done

$B/cam.sh first >/dev/null
