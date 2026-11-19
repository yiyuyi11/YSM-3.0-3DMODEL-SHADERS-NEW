#!/usr/bin/env bash
# Start Xvfb, the Forge server and the Forge client (Tester), then op/tp Tester.
# Idempotent: already-running pieces are reused.
set -euo pipefail
source "$(dirname "$(readlink -f "$0")")/common.sh"
T0=$(date +%s)

# Client options forced on every start (other keys in options.txt are kept).
# MC_FOV=<30..110> sets the field of view in degrees (default 70; e.g. 40 makes the model ~2x larger).
FOV_DEG="${MC_FOV:-70}"
# MC_RES=<w>x<h> sets the screen and window size (default 1280x720); only applies when Xvfb/client start.
RES="${MC_RES:-1280x720}"
OPTIONS=(
    "version:3465" "renderDistance:4" "simulationDistance:5" "graphicsMode:0" "maxFps:30"
    "enableVsync:false" "entityShadows:false" "renderClouds:\"false\"" "particles:2"
    "pauseOnLostFocus:false" "onboardAccessibility:false" "tutorialStep:none" "narrator:0"
    "joinedFirstServer:true" "skipMultiplayerWarning:true" "bobView:false" "fullscreen:false"
    "soundCategory_master:0.0" "soundCategory_music:0.0" "chatVisibility:0" "guiScale:2"
    "fov:$(awk -v d="$FOV_DEG" 'BEGIN { printf "%.4f", (d - 70) / 40 }')" "autoJump:false"
    "realmsNotifications:false" "darkMojangStudiosBackground:true"
)

write_options() {
    local f="$CLIENT_DIR/options.txt" tmp kv
    touch "$f"; tmp=$(mktemp)
    printf '%s\n' "${OPTIONS[@]}" > "$tmp.new"
    awk -F: 'NR==FNR { k=$1; sub(/^[^:]*:/, ""); want[k]=$0; order[++n]=k; next }
             { k=$1; if (k in want) { print k ":" want[k]; done[k]=1 } else print }
             END { for (i=1;i<=n;i++) if (!(order[i] in done)) print order[i] ":" want[order[i]] }' \
        "$tmp.new" "$f" > "$tmp"
    mv "$tmp" "$f"; rm -f "$tmp.new"
}

# 1. Xvfb
if [ -z "$(xvfb_pid)" ]; then
    nohup Xvfb "$DISPLAY" -screen 0 "${RES}x24" -nolisten tcp -noreset > "$RUN/xvfb.log" 2>&1 &
    for _ in $(seq 20); do xdpyinfo >/dev/null 2>&1 && break; sleep 0.5; done
    xdpyinfo >/dev/null 2>&1 || die "Xvfb did not start (see $RUN/xvfb.log)"
    echo "[up] Xvfb $DISPLAY started"
fi

# 2. Server with stdin from a FIFO (opened read-write so it never sees EOF).
if [ -z "$(server_pid)" ]; then
    [ -p "$FIFO" ] || { rm -f "$FIFO"; mkfifo "$FIFO"; }
    : > "$SLOG"
    (cd "$SERVER_DIR" && exec setsid nohup java @user_jvm_args.txt \
        @libraries/net/minecraftforge/forge/$FORGE_VER/unix_args.txt nogui <> "$FIFO" >> "$SLOG" 2>&1) &
    echo "[up] server starting (log: $SLOG)"
    wait_log "$SLOG" 'Done \([0-9.]+s\)! For help' 0 300 || die "server did not finish starting in 300s"
    echo "[up] server ready after $(( $(date +%s) - T0 ))s"
    # World settings (persist in level.dat; cheap to re-apply).
    for c in "gamerule doDaylightCycle false" "gamerule doWeatherCycle false" "gamerule doMobSpawning false" \
             "gamerule doInsomnia false" "gamerule doPatrolSpawning false" "gamerule doTraderSpawning false" \
             "gamerule announceAdvancements false" "gamerule sendCommandFeedback true" \
             "time set noon" "weather clear 1000000" "setworldspawn 0 -60 0"; do
        send_cmd "$c"
    done
fi

# 3. Client
if [ -z "$(client_pid)" ]; then
    write_options
    echo 0 > "$RUN/cam.state"; echo on > "$RUN/hud.state"
    off=$(stat -c %s "$SLOG")
    # user.home redirect: YSM extracts its native lib to ${user.home}/.ysm/unstable.
    mkdir -p "$CLIENT_DIR/home"
    (exec setsid nohup "$MC/venv/bin/portablemc" --main-dir "$CLIENT_DIR" --output machine start \
        --jvm-policy system --fetch-exclude-all \
        --jvm-arg="-Xmx2500M,-Xms512M,-XX:+UseG1GC,-Duser.home=$CLIENT_DIR/home" \
        --resolution "$RES" -u "$PLAYER" --join-server localhost \
        "forge::$FORGE_VER" > "$CLOG" 2>&1) &
    echo "[up] client starting (log: $CLOG)"
    wait_log "$SLOG" "$PLAYER joined the game" "$off" 600 || die "client did not join within 600s (see $CLOG)"
    echo "[up] $PLAYER joined after $(( $(date +%s) - T0 ))s"
    sleep 5 # let chunks/models arrive before posing
fi

send_cmd "op $PLAYER"
send_cmd "gamemode creative $PLAYER"
send_cmd "time set noon"
send_cmd "weather clear 1000000"
"$MC/bin/pose.sh" >/dev/null
# Give the window focus and dismiss anything open (e.g. a toast or pause screen).
mc_focus >/dev/null && xdotool mousemove 640 360 click 1 >/dev/null 2>&1 || true
echo "[up] ready in $(( $(date +%s) - T0 ))s"
