#!/usr/bin/env bash
# Harness lock: only one owner may start/drive the game at a time (one Minecraft fits in RAM).
# usage: lock.sh acquire <owner> [wait_seconds=1800] | release <owner> | handover <owner> <next> | status
# handover passes a held lock straight to <next>: its waiting acquire sees itself as owner and proceeds.
set -euo pipefail
source "$(dirname "$(readlink -f "$0")")/common.sh"
dir="$RUN/harness.lock"
case "${1:-}" in
    acquire)
        owner=${2:?owner}; t=${3:-1800}; i=0
        until mkdir "$dir" 2>/dev/null; do
            [ "$(cat "$dir/owner" 2>/dev/null)" = "$owner" ] && { echo "already held by $owner"; exit 0; }
            [ $i -ge "$t" ] && die "lock held by $(cat "$dir/owner" 2>/dev/null) for over ${t}s"
            sleep 10; i=$((i + 10))
        done
        echo "$owner" > "$dir/owner"; date +%T > "$dir/since"; echo "acquired by $owner" ;;
    release)
        owner=${2:?owner}
        [ "$(cat "$dir/owner" 2>/dev/null)" = "$owner" ] || die "lock not held by $owner"
        rm -rf "$dir"; echo "released by $owner" ;;
    handover)
        owner=${2:?owner}; next=${3:?next owner}
        [ "$(cat "$dir/owner" 2>/dev/null)" = "$owner" ] || die "lock not held by $owner"
        echo "$next" > "$dir/owner"; date +%T > "$dir/since"; echo "handed over from $owner to $next" ;;
    status)
        [ -d "$dir" ] && echo "held by $(cat "$dir/owner") since $(cat "$dir/since")" || echo "free" ;;
    *) die "usage: lock.sh acquire <owner> [wait] | release <owner> | handover <owner> <next> | status" ;;
esac
