#!/usr/bin/env python3
"""Read the three observed KZBF 03 00 03 00 containers without executing them.

This is a bounded format reconstruction, not a Kanzi scene interpreter.
The default writes metadata; --images also decodes observed PNG/ASTC6x6 assets.
Pillow and texture2ddecoder==1.0.6 are needed only for --images.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct


def require(ok, message):
    if not ok:
        raise ValueError(message)


def strings(data, pos, count):
    require(0 <= count <= len(data), "Invalid string count")
    result = []
    for _ in range(count):
        end = data.index(b"\0", pos)
        result.append(data[pos:end].decode("utf-8"))
        pos = end + 1
    return result, pos


def inspect(path, out, images=False):
    data = path.read_bytes()
    require(data[:8] == b"KZBF\x03\x00\x03\x00", "Unsupported header")
    name_size = struct.unpack_from("<I", data, 8)[0]
    require(12 + name_size + 4 <= len(data), "Truncated container name")
    name = data[12:12 + name_size].decode("utf-8")
    pos = 12 + name_size
    count = struct.unpack_from("<I", data, pos)[0]
    names, pos = strings(data, pos + 4, count)
    table_offset = pos
    require(struct.unpack_from("<I", data, pos)[0] == count, "Count mismatch")
    pos += 4
    table_end = pos + count * 24
    require(table_end <= len(data), "Truncated resource table")
    entries = []
    ids = set()
    for j in range(count):
        idx, typ, offset, size, field4, field5 = struct.unpack_from("<6I", data, pos + j * 24)
        require(idx < count and idx not in ids, "Invalid or duplicate resource index")
        ids.add(idx)
        require(table_end <= offset <= offset + size <= len(data), "Resource out of bounds")
        # All supplied samples have an uncompressed body and equal size fields.
        require(field4 == 0 and field5 == size, "Unsupported resource storage fields")
        body = data[offset:offset + size]
        entries.append(dict(index=idx, name=names[idx], type_id=typ, offset=offset,
                            bytes=size, sha256=hashlib.sha256(body).hexdigest()))
    entries.sort(key=lambda e: e["index"])
    previous = table_end
    padding = 0
    for entry in sorted(entries, key=lambda e: e["offset"]):
        gap = data[previous:entry["offset"]]
        require(entry["offset"] >= previous, "Overlapping resource bodies")
        require(len(gap) <= 3 and not any(gap), "Unexpected bytes between resource bodies")
        padding += len(gap)
        previous = entry["offset"] + entry["bytes"]
    require(previous == len(data), "Unaccounted trailing bytes")
    by_name = {e["name"]: e for e in entries}

    def body(entry):
        return data[entry["offset"]:entry["offset"] + entry["bytes"]]

    pools = {}
    for key in ("/$strings", "/$metaclass_dictionary", "/$plugins"):
        raw = body(by_name[key])
        n = struct.unpack_from("<I", raw)[0]
        values, end = strings(raw, 4, n)
        require(end == len(raw), "Unaccounted string-pool bytes")
        pools[key] = values
    image_entries = []
    image_dir = out / (path.stem + "_images")
    if images:
        from PIL import Image
        import texture2ddecoder
        image_dir.mkdir(parents=True, exist_ok=True)
    for entry in entries:
        if not entry["name"].startswith("/Resource Files/Images/"):
            continue
        raw = body(entry)
        require(len(raw) >= 12, "Truncated image record")
        prefix = struct.unpack_from("<3I", raw)
        meta = dict(index=entry["index"], name=entry["name"])
        im = None
        if prefix[:2] == (0, 1):
            n = prefix[2]
            require(len(raw) == 12 + n * 4, "Invalid image reference list")
            indices = struct.unpack_from("<" + "I" * n, raw, 12)
            require(all(i < len(pools["/$strings"]) for i in indices), "Invalid string index")
            refs = [pools["/$strings"][i] for i in indices]
            prefix_uri = "kzb://" + name
            require(all(r.startswith(prefix_uri + "/") and r[len(prefix_uri):] in by_name
                        for r in refs), "Unresolved or external image reference")
            meta.update(encoding="reference_list", references=refs)
        elif prefix == (0, 0, 0):
            require(len(raw) >= 20, "Truncated PNG header")
            zero, size = struct.unpack_from("<2I", raw, 12)
            require(zero == 0 and len(raw) == size + 20, "Invalid PNG size")
            require(raw[20:28] == b"\x89PNG\r\n\x1a\n", "Unexpected inline image encoding")
            width, height = struct.unpack_from(">2I", raw, 36)
            meta.update(encoding="PNG", width=width, height=height)
            if images:
                im = Image.open(io.BytesIO(raw[20:])).convert("RGBA")
                require(im.size == (width, height), "PNG size mismatch")
        elif prefix == (0, 0, 2):
            fmt, size = struct.unpack_from("<2I", raw, 12)
            require(fmt == 59 and len(raw) == size + 28, "Unsupported compressed image")
            width, height = struct.unpack_from("<2I", raw, len(raw) - 8)
            require(0 < width <= 16384 and 0 < height <= 16384, "Invalid image dimensions")
            require(((width + 5) // 6) * ((height + 5) // 6) * 16 == size, "ASTC block count mismatch")
            meta.update(encoding="ASTC6x6_observed", width=width, height=height)
            if images:
                pixels = texture2ddecoder.decode_astc(raw[20:20 + size], width, height, 6, 6)
                im = Image.frombytes("RGBA", (width, height), pixels, "raw", "BGRA")
        else:
            raise ValueError("Unsupported image record: " + entry["name"])
        if im is not None:
            image_name = f"{entry['index']:04d}.png"
            im.save(image_dir / image_name)
            meta["decoded_file"] = image_name
            meta["rgba_sha256"] = hashlib.sha256(im.tobytes()).hexdigest()
        image_entries.append(meta)
    summary = dict(file=path.name, sha256=hashlib.sha256(data).hexdigest(), bytes=len(data),
                   container=name, entries=len(entries), table_offset=table_offset,
                   table_end=table_end, alignment_bytes=padding, all_bytes_accounted=True,
                   string_pool_counts={k: len(v) for k, v in pools.items()},
                   resources=entries, image_entries=image_entries,
                   limitation="Resource framing and observed image encodings only; scene properties, meshes, state transitions and runtime ownership are not interpreted.")
    out.mkdir(parents=True, exist_ok=True)
    (out / (path.stem + "_inventory.json")).write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--images", action="store_true")
    args = parser.parse_args()
    for file in args.files:
        result = inspect(file, args.out, args.images)
        print(json.dumps({k: result[k] for k in ("file", "sha256", "bytes", "entries", "all_bytes_accounted")}))
