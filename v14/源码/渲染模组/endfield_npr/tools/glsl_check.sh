#!/usr/bin/env bash
# glslangValidator on the core programs with #moj_import <endfield_npr:X> inlined from shaders/include (first import
# of a file wins, as Minecraft's preprocessor does). Extra include dirs (e.g. a stub of another branch's include) may
# be given as arguments; they are searched first.
set -euo pipefail
cd "$(dirname "$0")/.."
CORE=src/main/resources/assets/endfield_npr/shaders/core
DIRS=("$@" src/main/resources/assets/endfield_npr/shaders/include)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
inline() {
  python3 - "$1" "${DIRS[@]}" <<'PY'
import os, re, sys
src, dirs = sys.argv[1], sys.argv[2:]
seen = set()
def expand(path):
    out = []
    for line in open(path):
        m = re.match(r'\s*#moj_import\s*<endfield_npr:([^>]+)>', line)
        if not m:
            out.append(line)
            continue
        name = m.group(1)
        if name in seen:
            continue
        seen.add(name)
        for d in dirs:
            p = os.path.join(d, name)
            if os.path.exists(p):
                out.extend(expand(p))
                break
        else:
            sys.exit(f"{path}: include {name} not found in {dirs}")
    return out
sys.stdout.write("".join(expand(src)))
PY
}
for f in "$CORE"/*.fsh "$CORE"/*.vsh; do
  ext=${f##*.}; stage=$([ "$ext" = fsh ] && echo frag || echo vert)
  inline "$f" > "$OUT/$(basename "$f" ."$ext").$stage"
  glslangValidator "$OUT/$(basename "$f" ."$ext").$stage" > "$OUT/log" 2>&1 && echo "ok   $(basename "$f")" \
    || { echo "FAIL $(basename "$f")"; cat "$OUT/log"; exit 1; }
done
