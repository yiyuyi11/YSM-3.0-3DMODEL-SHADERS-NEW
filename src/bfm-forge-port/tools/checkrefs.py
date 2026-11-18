#!/usr/bin/env python3
"""Check that every Minecraft member the reobfuscated jar references exists at runtime (Forge 1.20.1, SRG names).

usage: checkrefs.py <mod.jar> <runtime.jar>...

Runtime jars are searched in order (put Forge's patched client jar before the vanilla SRG client jar). Each field or
method instruction (read with javap, since the reobf step leaves stale entries in constant pools) whose owner is a
net/minecraft or com/mojang/blaze3d class is resolved through the owner's superclasses and interfaces; unresolved
references (names left unmapped by the reobf step) are printed and make the exit status 1.
"""
import re
import struct
import subprocess
import sys
import zipfile


def parse(data):
    count = struct.unpack_from('>H', data, 8)[0]
    pos = 10
    cp = [None] * count
    i = 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            n = struct.unpack_from('>H', data, pos + 1)[0]
            cp[i] = (1, data[pos + 3:pos + 3 + n].decode('utf-8', 'replace'))
            pos += 3 + n
        elif tag in (9, 10, 11, 12):
            cp[i] = (tag,) + struct.unpack_from('>HH', data, pos + 1)
            pos += 5
        elif tag in (3, 4, 17, 18):
            pos += 5
        elif tag in (5, 6):
            pos += 9
            i += 1
        elif tag == 7:
            cp[i] = (7, struct.unpack_from('>H', data, pos + 1)[0])
            pos += 3
        elif tag in (8, 16, 19, 20):
            pos += 3
        elif tag == 15:
            pos += 4
        else:
            raise ValueError(tag)
        i += 1

    def cls(index):
        return cp[cp[index][1]][1] if index else None

    _, this, sup, nif = struct.unpack_from('>HHHH', data, pos)
    pos += 8
    interfaces = [cls(struct.unpack_from('>H', data, pos + 2 * k)[0]) for k in range(nif)]
    pos += 2 * nif
    members = set()
    for _ in range(2):
        n = struct.unpack_from('>H', data, pos)[0]
        pos += 2
        for _ in range(n):
            _, name, desc, attrs = struct.unpack_from('>HHHH', data, pos)
            pos += 8
            for _ in range(attrs):
                pos += 6 + struct.unpack_from('>I', data, pos + 2)[0]
            members.add((cp[name][1], cp[desc][1]))
    refs = []
    for entry in cp:
        if entry and entry[0] in (9, 10, 11):
            nat = cp[entry[2]]
            refs.append((cls(entry[1]), cp[nat[1]][1], cp[nat[2]][1]))
    return cls(this), cls(sup), interfaces, members, refs


def main():
    mod, runtime = sys.argv[1], [zipfile.ZipFile(p) for p in sys.argv[2:]]
    cache = {}

    def info(name):
        if name not in cache:
            cache[name] = None
            for jar in runtime:
                try:
                    cache[name] = parse(jar.read(name + '.class'))
                    break
                except KeyError:
                    continue
        return cache[name]

    jdk = {}

    def jdk_members(owner):
        """Member names of a JDK class (by name only: enough to tell vanilla names from unmapped Mojang ones)."""
        if owner not in jdk:
            text = subprocess.run(['javap', '-p', owner.replace('/', '.')], capture_output=True, text=True).stdout
            jdk[owner] = set(re.findall(r'([\w$]+)\(', text)) | set(re.findall(r' ([\w$]+);$', text, re.M))
        return jdk[owner]

    def resolves(owner, name, desc, seen):
        if owner is None or owner in seen:
            return False
        seen.add(owner)
        if owner.startswith('java/'):
            return name in jdk_members(owner)
        found = info(owner)
        if found is None:
            return False
        _, sup, interfaces, members, _ = found
        if (name, desc) in members:
            return True
        return any(resolves(t, name, desc, seen) for t in [sup] + interfaces)

    bad = 0
    with zipfile.ZipFile(mod) as jar:
        classes = [e[:-6].replace('/', '.') for e in jar.namelist() if e.endswith('.class')]
    listing = subprocess.run(['javap', '-c', '-p', '-cp', mod] + classes, capture_output=True, text=True,
                             check=True).stdout
    this = None
    for line in listing.splitlines():
        header = re.match(r'^(?:\S.* )?(?:class|interface|enum) (\S+)', line)
        if header and not line.startswith(' '):
            this = header.group(1)
            continue
        ref = re.search(r'// (?:Method|Field|InterfaceMethod) ([\w/$]+)\.([\w$<>]+):(\S+)', line)
        if not ref:
            continue
        owner, name, desc = ref.groups()
        if not owner.startswith(('net/minecraft/', 'com/mojang/blaze3d/')):
            continue
        if not resolves(owner, name, desc, set()):
            bad += 1
            print(f'{this}: unresolved {owner}.{name} {desc}')
    print(f'{bad} unresolved reference(s)')
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
