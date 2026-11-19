#!/bin/sh
# Concatenates src/ into the single ysm3d_animator.js that Blockbench loads.
# Order: core modules first, feature modules after, plugin.js last (it registers the plugin).
set -e
cd "$(dirname "$0")"
out=ysm3d_animator.js
version=$(sed -n "s/^YSM3D.VERSION = '\(.*\)';$/\1/p" src/core.js)
{
	printf '/* YSM 3D Animator %s: Blockbench plugin. Built from tools/blockbench-plugin/src by build.sh; do not edit. */\n' "$version"
	printf '(function () {\n"use strict";\n'
	for f in src/core.js src/settings.js src/java_import.js src/preview.js src/first_person.js src/tacz.js \
		src/animations.js src/project.js; do
		[ -f "$f" ] && { printf '\n// ---- %s\n' "$f"; cat "$f"; }
	done
	for f in src/vrm.js src/paint.js src/plugin.js; do
		[ -f "$f" ] && { printf '\n// ---- %s\n' "$f"; cat "$f"; }
	done
	printf '\n})();\n'
} > "$out.tmp"
mv "$out.tmp" "$out"
echo "built $out ($(wc -c < "$out") bytes)"
