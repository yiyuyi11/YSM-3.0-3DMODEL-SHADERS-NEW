#!/usr/bin/env bash
# Builds the YSM jar with Endfield locomotion: the user's v18 jar with the changed classes (mod/src: the parser package,
# NativeModelState and the Battlefield Movement compat) recompiled against it, the motion data added, and the Linux
# native added beside the Windows one so one jar runs on both.
#
# Usage: build_jar.sh <v18 jar> <v11 jar with libysm.so> <libraries dir with gson/joml/fastutil/...> <anchors.json | -> <out.jar>
# anchors.json: locomotion anchor motion (motion/README.md), packed as endfield_locomotion.json; with - the jar ships
# without it and imported models keep the procedural idle, walk and run.
# YSM_NATIVE_DLL / YSM_NATIVE_SO: rebuilt natives (YesSteveModel-Native + native-patch) replacing the jar's ysm.dll /
# libysm.so. Plain javac against the jar and the libraries is enough; a changed class that calls Minecraft uses the
# production (SRG) member names the game jar there carries.
set -euo pipefail
base=$1 linux=$2 libraries=$3 motion=$4 out=$5
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

if [ "$motion" != "-" ]; then
    python3 "$here/../动作数据工具/make_resource.py" "$motion" "$work/endfield_locomotion.json"
fi
classpath="$base:$(find "$libraries" -name '*.jar' | tr '\n' ':')"
javac --release 17 -nowarn -encoding UTF-8 -d "$work/classes" -cp "$classpath" $(find "$here/src" -name '*.java')

# v18 draws the main-hand stack again in place of the off-hand item; patch that one call (fails unless found once).
layer=com/elfmcys/ysm/client/renderer/layer/CustomPlayerItemInHandLayer.class
asm="$(find "$libraries" -path '*org/ow2/asm/asm/9.7.1/*.jar'):$(find "$libraries" -path '*org/ow2/asm/asm-tree/9.7.1/*.jar')"
javac --release 17 -nowarn -encoding UTF-8 -d "$work/patcher" -cp "$asm" "$here/patch/OffhandItemPatch.java" \
    "$here/patch/LodPatch.java"
mkdir -p "$work/patched/$(dirname "$layer")"
unzip -p "$base" "$layer" > "$work/layer.class"
java -cp "$work/patcher:$asm" OffhandItemPatch "$work/layer.class" "$work/patched/$layer"
# Geometric and spring LOD hooks (client.lod.MeshLodRuntime): five classes, straight-line insertions.
java -cp "$work/patcher:$asm" LodPatch "$base" "$work/patched"

python3 - "$base" "$linux" "$work" "$out" "${YSM_NATIVE_DLL:-}" "${YSM_NATIVE_SO:-}" <<'EOF'
import os
import sys
import zipfile

base, linux, work, out, native_dll, native_so = sys.argv[1:]
classes = os.path.join(work, "classes")
# every top-level class compiled from mod/src replaces the jar's (nested classes included)
replaced = tuple(sorted({os.path.relpath(os.path.join(root, f), classes)[:-len(".class")]
                         for root, _, files in os.walk(classes) for f in files if "$" not in f}))
natives = {"META-INF/native/ysm.dll": native_dll, "META-INF/native/libysm.so": native_so}
with zipfile.ZipFile(base) as source, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as target:
    for info in source.infolist():
        name = info.filename
        if name.endswith(".class") and any(name == p + ".class" or name.startswith(p + "$") for p in replaced):
            continue  # recompiled below, nested classes included
        data = source.read(name)
        if natives.get(name):
            with open(natives[name], "rb") as native:
                data = native.read()  # rebuilt native library
        patched_file = os.path.join(work, "patched", name)
        if name.endswith(".class") and os.path.isfile(patched_file):
            with open(patched_file, "rb") as patched:
                data = patched.read()  # OffhandItemPatch (off-hand item at the left hand), LodPatch (LOD hooks)
        if name.startswith("assets/ysm/shaders/") and data.startswith(b"\xef\xbb\xbf"):
            data = data[3:]  # a UTF-8 BOM before #version makes the GLSL compile fail (v18's ysm_outline)
        target.writestr(info, data)
    for root, _, files in os.walk(classes):
        for file in files:
            path = os.path.join(root, file)
            target.write(path, os.path.relpath(path, classes))
    resource = os.path.join(work, "endfield_locomotion.json")
    if os.path.exists(resource):
        target.write(resource, "com/elfmcys/ysm/format/parser/endfield_locomotion.json")
    if "META-INF/native/libysm.so" not in source.namelist():
        if native_so:
            target.write(native_so, "META-INF/native/libysm.so")
        else:
            target.writestr("META-INF/native/libysm.so", zipfile.ZipFile(linux).read("META-INF/native/libysm.so"))
print("wrote", out)
EOF
