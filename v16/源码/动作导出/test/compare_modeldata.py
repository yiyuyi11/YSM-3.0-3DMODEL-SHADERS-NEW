#!/usr/bin/env python3
"""Compares two player ModelData blobs (ImportDump's <out>.modeldata.bin) byte by byte, ignoring only the ZIP
modification time/date fields inside the endfield_npr package: EndfieldNprPackage writes its ZIP entries with the
current time, so two imports of the same model by the same jar already differ there.

Usage: compare_modeldata.py a.modeldata.bin b.modeldata.bin   (exit 0 = identical after that normalization)
"""
import sys


def time_fields(data):
    """Offsets of every ZIP local / central header's DOS time+date (4 bytes each)."""
    fields = set()
    for signature, offset in ((b"PK\x03\x04", 10), (b"PK\x01\x02", 12)):
        at = data.find(signature)
        while at >= 0:
            fields.update(range(at + offset, at + offset + 4))
            at = data.find(signature, at + 1)
    return fields


def main(a_path, b_path):
    a = open(a_path, "rb").read()
    b = open(b_path, "rb").read()
    if len(a) != len(b):
        print(f"DIFFERENT: sizes {len(a)} vs {len(b)}")
        return 1
    diff = [i for i in range(len(a)) if a[i] != b[i]] if a != b else []
    allowed = time_fields(a) & time_fields(b)
    other = [i for i in diff if i not in allowed]
    print(f"{len(a)} bytes, {len(diff)} differing, {len(diff) - len(other)} of them in ZIP time/date fields, "
          f"{len(other)} elsewhere")
    if other:
        print("DIFFERENT at offsets", other[:20])
        return 1
    print("IDENTICAL (up to endfield_npr ZIP timestamps)")
    return 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:3]))
