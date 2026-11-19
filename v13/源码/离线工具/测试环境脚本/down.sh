#!/usr/bin/env bash
# Stop client, server (clean "stop" via console) and Xvfb.
# usage: down.sh          stop everything
#        down.sh client   stop only the client (e.g. to restart it with other options)
set -uo pipefail
source "$(dirname "$(readlink -f "$0")")/common.sh"
stop_pid() { # pid timeout
    local p=$1 i=0
    kill -TERM "$p" 2>/dev/null || return 0
    while kill -0 "$p" 2>/dev/null && [ $i -lt "$2" ]; do sleep 1; i=$((i + 1)); done
    kill -0 "$p" 2>/dev/null && kill -KILL "$p" 2>/dev/null
    return 0
}
p=$(client_pid); if [ -n "$p" ]; then stop_pid "$p" 20; echo "[down] client stopped"; fi
pkill -f "$MC/venv/bin/portablemc" 2>/dev/null || true
[ "${1:-}" = client ] && exit 0
p=$(server_pid)
if [ -n "$p" ]; then
    (send_cmd stop) 2>/dev/null || true
    for _ in $(seq 60); do kill -0 "$p" 2>/dev/null || break; sleep 1; done
    kill -0 "$p" 2>/dev/null && { echo "[down] server ignored stop; killing"; stop_pid "$p" 15; }
    echo "[down] server stopped"
fi
p=$(xvfb_pid); if [ -n "$p" ]; then stop_pid "$p" 5; echo "[down] Xvfb stopped"; fi
rm -f "$FIFO"
echo "[down] done"
