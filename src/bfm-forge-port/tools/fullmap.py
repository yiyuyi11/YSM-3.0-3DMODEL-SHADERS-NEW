#!/usr/bin/env python3
"""Write a complete Mojang -> SRG member map (TSRG v1) for Minecraft 1.20.1.

usage: fullmap.py <client-moj.jar> <client-srg.jar> <out.tsrg>

The shared ~/ysm/npr/mod/tools/moj2srg.tsrg has no members for classes whose obfuscated name equals their Mojang
name (net.minecraft.server.MinecraftServer and other entry points), so ForgeAutoRenamingTool left calls such as
MinecraftServer.getPlayerList() unmapped. client-moj.jar was renamed from client-srg.jar member by member, which keeps
every class's member order, so pairing the two jars' members in order gives the full map.
"""
import struct
import sys
import zipfile


def members(data):
    """(fields, methods) of a class file, each a list of (name, descriptor) in declaration order."""
    pos = 10
    count = struct.unpack_from('>H', data, 8)[0]
    utf8 = {}
    i = 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            n = struct.unpack_from('>H', data, pos + 1)[0]
            utf8[i] = data[pos + 3:pos + 3 + n].decode('utf-8', 'replace')
            pos += 3 + n
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 5
        elif tag in (5, 6):
            pos += 9
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 3
        elif tag == 15:
            pos += 4
        else:
            raise ValueError(f'constant pool tag {tag}')
        i += 1
    pos += 6
    interfaces = struct.unpack_from('>H', data, pos)[0]
    pos += 2 + 2 * interfaces
    out = []
    for _ in range(2):
        n = struct.unpack_from('>H', data, pos)[0]
        pos += 2
        items = []
        for _ in range(n):
            _, name, desc, attrs = struct.unpack_from('>HHHH', data, pos)
            pos += 8
            for _ in range(attrs):
                pos += 6 + struct.unpack_from('>I', data, pos + 2)[0]
            items.append((utf8[name], utf8[desc]))
        out.append(items)
    return out


def main():
    moj_jar, srg_jar, out_path = sys.argv[1:4]
    lines = []
    with zipfile.ZipFile(moj_jar) as moj, zipfile.ZipFile(srg_jar) as srg:
        srg_names = set(srg.namelist())
        for entry in sorted(moj.namelist()):
            if not entry.endswith('.class') or entry not in srg_names:
                continue
            mf, mm = members(moj.read(entry))
            sf, sm = members(srg.read(entry))
            if len(mf) != len(sf) or len(mm) != len(sm):
                raise SystemExit(f'member count differs in {entry}')
            body = []
            for (mn, md), (sn, sd) in zip(mf, sf):
                if md != sd:
                    raise SystemExit(f'field order differs in {entry}: {mn} {md} / {sn} {sd}')
                if mn != sn:
                    body.append(f'\t{mn} {sn}')
            for (mn, md), (sn, sd) in zip(mm, sm):
                if md != sd:
                    raise SystemExit(f'method order differs in {entry}: {mn}{md} / {sn}{sd}')
                if mn != sn:
                    body.append(f'\t{mn} {md} {sn}')
            cls = entry[:-6]
            lines.append(f'{cls} {cls}')
            lines.extend(body)
    with open(out_path, 'w') as f:
        f.write('\n'.join(lines) + '\n')
    print(f'{out_path}: {len(lines)} lines')


if __name__ == '__main__':
    main()
