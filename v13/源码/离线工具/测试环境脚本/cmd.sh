#!/usr/bin/env bash
# Send one console command to the server and print the log lines it produced.
# usage: cmd.sh "<command without slash>" [wait_seconds=2]
set -euo pipefail
source "$(dirname "$(readlink -f "$0")")/common.sh"
[ $# -ge 1 ] || die 'usage: cmd.sh "<console command>" [wait_seconds]'
off=$(stat -c %s "$SLOG")
send_cmd "$1"
sleep "${2:-2}"
tail -c +"$((off + 1))" "$SLOG"
