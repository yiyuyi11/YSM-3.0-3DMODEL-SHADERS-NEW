# Shared settings for the ~/mc harness. Sourced by every script in ~/mc/bin.
MC="${MC:-$HOME/mc}"
RUN="$MC/run"
SHOTS="$MC/shots"
SERVER_DIR="$MC/server"
CLIENT_DIR="$MC/client"
FIFO="$RUN/server.in"
SLOG="$RUN/server.log"
CLOG="$RUN/client.log"
FORGE_VER="1.20.1-47.4.3"
PLAYER="${PLAYER:-Tester}"
# Fixed pose used by pose.sh: x y z yaw pitch (flat world surface is y=-60).
POSE="${POSE:-0.5 -60 0.5 0 0}"
export DISPLAY="${MC_DISPLAY:-:99}"
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
# Keep YSM's optional global model directory off: on Linux it is only enabled when
# XDG_DATA_HOME (or YSM_HOME) is set, and then it would live outside ~/mc.
unset XDG_DATA_HOME XDG_CACHE_HOME YSM_HOME
mkdir -p "$RUN" "$SHOTS"

die() { echo "error: $*" >&2; exit 1; }

server_pid() { pgrep -f "forge/$FORGE_VER/unix_args.txt" | head -1; }
client_pid() { pgrep -f "cpw.mods.bootstraplauncher.BootstrapLauncher.*--username $PLAYER" | head -1; }
xvfb_pid() { pgrep -f "^Xvfb $DISPLAY " | head -1; }

# Minecraft's GLFW window (title "Minecraft* 1.20.1 - ..."); first match only.
mc_window() { xdotool search --name '^Minecraft' 2>/dev/null | head -1; }

# Give the game X input focus (no window manager on Xvfb, so windowfocus, not windowactivate).
mc_focus() {
    local w; w=$(mc_window) || true
    [ -n "$w" ] || die "Minecraft window not found on $DISPLAY"
    xdotool windowfocus --sync "$w" 2>/dev/null || xdotool windowfocus "$w"
    echo "$w"
}

# Send keys to the focused game via XTEST (synthetic --window events are ignored by GLFW).
mc_key() { mc_focus >/dev/null; xdotool key --delay 80 "$@"; }

# Wait until a regex appears in a file after byte offset $3; $4 = timeout seconds.
wait_log() {
    local file=$1 re=$2 from=${3:-0} t=${4:-120} i=0
    while [ $i -lt "$t" ]; do
        tail -c +"$((from + 1))" "$file" 2>/dev/null | grep -Eq "$re" && return 0
        sleep 1; i=$((i + 1))
    done
    return 1
}

# Write one console line to the server; requires a running server.
send_cmd() {
    [ -p "$FIFO" ] && [ -n "$(server_pid)" ] || die "server is not running (use up.sh)"
    timeout 5 bash -c 'printf "%s\n" "$1" > "$2"' _ "$1" "$FIFO" || die "could not write to $FIFO"
}
