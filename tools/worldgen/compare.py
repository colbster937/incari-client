#!/usr/bin/env python3
"""Compares the chunks two worlds saved (ADR 0041's world generation check).

    python3 tools/worldgen/compare.py run/worldgen-off/world run/worldgen-on/world

Reads every region file of the overworld in both worlds and compares each chunk both hold: its
generation status, every section's blocks and biomes, and its heightmaps. Prints how many chunks
match, and for those that don't, what differs; exits 1 if any chunk differs.

Standard library only. Region files: a 4 KiB table of chunk offsets, then each chunk as a 4-byte
length, a compression byte (2 = zlib, 1 = gzip, 3 = none) and NBT.
"""

import gzip
import struct
import sys
import zlib
from collections import Counter
from pathlib import Path


def read_nbt(data):
    """The root compound of uncompressed NBT bytes."""
    pos = 0

    def take(fmt):
        nonlocal pos
        values = struct.unpack_from(fmt, data, pos)
        pos += struct.calcsize(fmt)
        return values[0] if len(values) == 1 else values

    def string():
        nonlocal pos
        length = take(">H")
        text = data[pos : pos + length].decode("utf-8", "replace")
        pos += length
        return text

    def payload(tag):
        nonlocal pos
        if tag == 1:
            return take(">b")
        if tag == 2:
            return take(">h")
        if tag == 3:
            return take(">i")
        if tag == 4:
            return take(">q")
        if tag == 5:
            return take(">f")
        if tag == 6:
            return take(">d")
        if tag == 7:
            length = take(">i")
            value = data[pos : pos + length]
            pos += length
            return bytes(value)
        if tag == 8:
            return string()
        if tag == 9:
            inner = take(">b")
            length = take(">i")
            return [payload(inner) for _ in range(length)]
        if tag == 10:
            compound = {}
            while True:
                inner = take(">b")
                if inner == 0:
                    return compound
                name = string()
                compound[name] = payload(inner)
        if tag in (11, 12):
            length = take(">i")
            width = 4 if tag == 11 else 8
            values = list(struct.unpack_from(">%d%s" % (length, "i" if tag == 11 else "q"), data, pos))
            pos += width * length
            return values
        raise ValueError("Unknown NBT tag %d" % tag)

    tag = take(">b")
    string()
    return payload(tag)


def chunks(region):
    """Each chunk of a region file: (x, z) within the region, and its NBT."""
    data = region.read_bytes()
    for index in range(1024):
        entry = struct.unpack_from(">I", data, index * 4)[0]
        offset, sectors = entry >> 8, entry & 0xFF
        if offset == 0 or sectors == 0:
            continue
        start = offset * 4096
        length, compression = struct.unpack_from(">IB", data, start)
        body = data[start + 5 : start + 4 + length]
        if compression & 0x80:
            continue  # stored in a separate .mcc file; rare, left out
        if compression == 2:
            body = zlib.decompress(body)
        elif compression == 1:
            body = gzip.decompress(body)
        elif compression != 3:
            raise ValueError("%s: compression %d" % (region, compression))
        yield (index % 32, index // 32), read_nbt(body)


def unpack(container, size):
    """A paletted container's entries, as palette values, in index order."""
    palette = container.get("palette", [])
    if len(palette) == 1 or "data" not in container:
        return [repr(palette[0])] * size if palette else [None] * size
    bits = max(4 if size == 4096 else 1, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    out = []
    for word in container["data"]:
        word &= (1 << 64) - 1
        for i in range(per_long):
            if len(out) == size:
                break
            out.append(repr(palette[(word >> (i * bits)) & mask]))
    return out


def describe(chunk):
    """What's compared of a chunk: status, blocks and biomes by section, heightmaps."""
    sections = {}
    for section in chunk.get("sections", []):
        y = section["Y"]
        blocks = unpack(section["block_states"], 4096) if "block_states" in section else None
        biomes = unpack(section["biomes"], 64) if "biomes" in section else None
        sections[y] = (blocks, biomes)
    return chunk.get("Status"), sections, chunk.get("Heightmaps", {})


def load(world):
    region_dir = Path(world) / "region"
    if not region_dir.is_dir():
        region_dir = Path(world) / "dimensions" / "minecraft" / "overworld" / "region"
    result = {}
    for region in sorted(region_dir.glob("r.*.mca")):
        _, rx, rz, _ = region.name.split(".")
        for (x, z), chunk in chunks(region):
            result[(int(rx) * 32 + x, int(rz) * 32 + z)] = describe(chunk)
    return result


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    a, b = load(sys.argv[1]), load(sys.argv[2])
    common = sorted(set(a) & set(b))
    print("chunks: %d in the first, %d in the second, %d in both" % (len(a), len(b), len(common)))
    statuses = Counter()
    same = 0
    differing = []
    for pos in common:
        status_a, sections_a, heights_a = a[pos]
        status_b, sections_b, heights_b = b[pos]
        if status_a != status_b:
            statuses["status differs"] += 1
            continue
        statuses[status_a] += 1
        blocks = Counter()
        biomes = 0
        for y in sorted(set(sections_a) | set(sections_b)):
            blocks_a, biomes_a = sections_a.get(y, (None, None))
            blocks_b, biomes_b = sections_b.get(y, (None, None))
            if blocks_a != blocks_b:
                if blocks_a is None or blocks_b is None:
                    blocks["section missing on one side"] += 1
                else:
                    for one, other in zip(blocks_a, blocks_b):
                        if one != other:
                            blocks[(one, other)] += 1
            if biomes_a != biomes_b:
                biomes += 1
        heights = sorted(k for k in set(heights_a) | set(heights_b) if heights_a.get(k) != heights_b.get(k))
        if blocks or biomes or heights:
            differing.append((pos, status_a, blocks, biomes, heights))
        else:
            same += 1
    print("compared by status: %s" % dict(statuses))
    print("identical: %d, differing: %d" % (same, len(differing)))
    kinds = Counter()
    for pos, status, blocks, biomes, heights in differing:
        kinds[status] += 1
    if differing:
        print("differing by status: %s" % dict(kinds))
    for pos, status, blocks, biomes, heights in differing[:15]:
        top = ", ".join("%s -> %s x%d" % (k[0], k[1], n) if isinstance(k, tuple) else "%s x%d" % (k, n) for k, n in blocks.most_common(3))
        print("  chunk %s (%s): %d blocks [%s], %d biome sections, heightmaps %s" % (pos, status, sum(blocks.values()), top, biomes, heights))
    sys.exit(1 if differing else 0)


if __name__ == "__main__":
    main()
