#!/usr/bin/env bash
# Contact sheet of shots in the given order: sheet.sh <out.png> <title> <shot names...> (under ~/mc/shots, no .png)
set -euo pipefail
out=$1 title=$2; shift 2
tmp=$(mktemp -d)
files=()
for n in "$@"; do
    f=~/mc/shots/$n.png
    [ -f "$f" ] || continue
    convert "$f" -crop 560x620+360+50 +repage -resize 50% "$tmp/$n.png"
    files+=("$tmp/$n.png")
done
montage -label '%t' -pointsize 11 "${files[@]}" -tile 6x -geometry +2+2 -title "$title" "$out"
rm -rf "$tmp"
echo "$out"
