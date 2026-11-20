#!/usr/bin/env python3
"""Offline check of npr_materials output through YSM's real importer (tools/ysmgen GenNpr, no Minecraft).

For each role: imports the original and the patched VRM with GenNpr, then checks the patched endfield_npr package:
profile.json has every materials.<name> of the VRM with a 4-number rect (w, h > 0), and every maps.<kind>.file is a
PNG inside the ZIP. Prints the converted sizes (ModelData blob and package ZIP) before and after.

Usage: verify.py ORIGINAL_DIR PATCHED_DIR [role ...]   (DIR/<role>/<role>.vrm + side files)
Env: YSM_JAR (the v11 YSM jar), MC_LIBS (a Forge 1.20.1 libraries dir), YSM_NATIVE_PATH (Linux libysm-core .so).
"""

import io
import json
import os
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import npr_materials  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
GEN_NPR = os.path.join(os.path.dirname(HERE), "ysmgen", "src", "com", "elfmcys", "ysm", "format", "parser",
                       "GenNpr.java")
JAR = os.environ.get("YSM_JAR", os.path.expanduser(
    "~/ysm/pelica-fix/deliver-v11/mods/ysm-3.0-dev-forge+mc1.20.1-pelica-motion.jar"))
LIBS = os.environ.get("MC_LIBS", os.path.expanduser("~/mc/client/libraries"))
NATIVE = os.environ.get("YSM_NATIVE_PATH", os.path.expanduser(
    "~/mc/client/home/.ysm/unstable/libysm-core-3.0-dev-forge+mc1.20.1.so"))


def import_model(vrm, classes, cp, work):
    """(package ZIP bytes, ModelData size) of YSM's import of vrm."""
    out = os.path.join(work, "out")
    run = subprocess.run(["java", "-Xmx2g", "-cp", classes + ":" + cp, "com.elfmcys.ysm.format.parser.GenNpr",
                          os.path.abspath(vrm), out], cwd=work,  # YSM writes logs/ into the working directory
                         env=dict(os.environ, YSM_NATIVE_PATH=NATIVE), capture_output=True, text=True)
    if run.returncode != 0 or not os.path.exists(out + ".zip"):
        raise RuntimeError(f"import of {vrm} failed:\n{run.stdout[-2000:]}{run.stderr[-2000:]}")
    with open(out + ".zip", "rb") as f:
        package = f.read()
    size = os.path.getsize(out + ".modeldata.bin")
    os.remove(out + ".zip")
    os.remove(out + ".modeldata.bin")
    return package, size


def check_package(package, vrm):
    with open(vrm, "rb") as f:
        document, _ = npr_materials.read_glb(f.read())
    expected = document["extensions"][npr_materials.EXTENSION].get("materials", {})
    archive = zipfile.ZipFile(io.BytesIO(package))
    names = set(archive.namelist())
    profile = json.loads(archive.read("profile.json"))
    materials = profile.get("materials", {})
    if set(materials) != set(expected):
        raise AssertionError(f"materials differ: VRM {sorted(expected)}, profile {sorted(materials)}")
    files = set()
    for name, entry in materials.items():
        rect = entry.get("rect")
        if not (isinstance(rect, list) and len(rect) == 4 and rect[2] > 0 and rect[3] > 0):
            raise AssertionError(f"{name}: bad rect {rect}")
        for kind, ref in entry.get("maps", {}).items():
            file = ref.get("file")
            if file not in names or not archive.read(file).startswith(b"\x89PNG"):
                raise AssertionError(f"{name}.maps.{kind}: file {file} missing or not a PNG")
            files.add(file)
    return len(materials), len(files)


def main():
    original, patched, roles = sys.argv[1], sys.argv[2], sys.argv[3:]
    roles = roles or sorted(r for r in os.listdir(patched) if os.path.isfile(os.path.join(patched, r, r + ".vrm")))
    cp = JAR + ":" + ":".join(os.path.join(r, f) for r, _, fs in os.walk(LIBS) for f in fs if f.endswith(".jar"))
    with tempfile.TemporaryDirectory() as work:
        classes = os.path.join(work, "classes")
        subprocess.run(["javac", "--release", "17", "-nowarn", "-encoding", "UTF-8", "-d", classes, "-cp", cp, GEN_NPR],
                       check=True)
        print("| role | materials | map files | ModelData before | after | growth | package before | after |")
        print("|---|---:|---:|---:|---:|---:|---:|---:|")
        for role in roles:
            before_zip, before = import_model(os.path.join(original, role, role + ".vrm"), classes, cp, work)
            vrm = os.path.join(patched, role, role + ".vrm")
            after_zip, after = import_model(vrm, classes, cp, work)
            count, files = check_package(after_zip, vrm)
            print(f"| {role} | {count} | {files} | {before / 1e6:.2f} MB | {after / 1e6:.2f} MB | "
                  f"+{(after - before) / 1e6:.2f} MB | {len(before_zip) / 1e6:.2f} MB | {len(after_zip) / 1e6:.2f} MB |",
                  flush=True)


if __name__ == "__main__":
    main()
