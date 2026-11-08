"""Decode AirPods sensor descriptors (Apple OSSerializeBinary) from a SensorProbe log.

Usage: python3 -I osbin.py descriptors.log
"""
import re
import struct
import sys

DICT, ARRAY, SET, NUMBER, SYMBOL, STRING, DATA, BOOLEAN, OBJECT = (
    0x01, 0x02, 0x03, 0x04, 0x08, 0x09, 0x0A, 0x0B, 0x0C)


def parse(buf):
    if struct.unpack_from("<I", buf, 0)[0] != 0xD3:
        raise ValueError("not OSSerializeBinary")
    pos = 4
    objects = []

    def read():
        nonlocal pos
        key = struct.unpack_from("<I", buf, pos)[0]
        pos += 4
        end = bool(key & 0x80000000)
        kind = (key >> 24) & 0x7F
        length = key & 0x00FFFFFF
        if kind == DICT:
            obj = {}
            objects.append(obj)
            for _ in range(length):
                k, _ = read()
                v, _ = read()
                obj[k if isinstance(k, str) else repr(k)] = v
        elif kind in (ARRAY, SET):
            obj = []
            objects.append(obj)
            for _ in range(length):
                v, _ = read()
                obj.append(v)
        elif kind == NUMBER:
            value = struct.unpack_from("<Q", buf, pos)[0]
            pos += 8
            obj = value
            objects.append(obj)
        elif kind in (SYMBOL, STRING):
            raw = buf[pos:pos + length]
            pos += (length + 3) & ~3
            obj = raw.rstrip(b"\0").decode("utf-8", "replace")
            objects.append(obj)
        elif kind == DATA:
            raw = buf[pos:pos + length]
            pos += (length + 3) & ~3
            obj = raw.hex()
            objects.append(obj)
        elif kind == BOOLEAN:
            obj = bool(length)
            objects.append(obj)
        elif kind == OBJECT:
            obj = objects[length]
        else:
            raise ValueError(f"unknown kind {kind:#x} at {pos - 4}")
        return obj, end

    obj, _ = read()
    return obj


def show(obj, indent=2):
    pad = " " * indent
    if isinstance(obj, dict):
        for k, v in obj.items():
            if isinstance(v, (dict, list)):
                print(f"{pad}{k}:")
                show(v, indent + 2)
            else:
                extra = f"  (0x{v:x})" if isinstance(v, int) and v > 9 else ""
                print(f"{pad}{k}: {v}{extra}")
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            if isinstance(v, (dict, list)):
                print(f"{pad}[{i}]")
                show(v, indent + 2)
            else:
                print(f"{pad}[{i}] {v}")


for line in open(sys.argv[1]):
    m = re.search(r"descriptor service=(\S+) len=(\d+) data=([0-9a-f]+)", line)
    if not m:
        continue
    print(f"== {m.group(1)} ({m.group(2)} bytes)")
    try:
        show(parse(bytes.fromhex(m.group(3))))
    except Exception as e:  # keep going with the next descriptor
        print("  could not decode:", e)
