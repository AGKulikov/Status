"""Read selected DEX declarations from an APK without executing its code."""
import argparse
import hashlib
import json
import struct
import zipfile


def inspect(data, prefixes):
    if not data.startswith(b'dex\n'):
        return []
    def u32(offset):
        return struct.unpack_from('<I', data, offset)[0]
    def uleb(offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
        raise ValueError('invalid ULEB128')
    strings_size, strings_off = struct.unpack_from('<II', data, 56)
    strings = []
    for i in range(strings_size):
        _, off = uleb(u32(strings_off + i * 4))
        end = data.index(0, off)
        strings.append(data[off:end].decode('utf-8', errors='replace'))
    types_size, types_off = struct.unpack_from('<II', data, 64)
    types = [strings[u32(types_off + i * 4)] for i in range(types_size)]
    proto_size, proto_off = struct.unpack_from('<II', data, 72)
    protos = []
    for i in range(proto_size):
        _, return_type, off = struct.unpack_from('<III', data, proto_off + i * 12)
        params = [] if not off else [types[struct.unpack_from('<H', data, off+4+j*2)[0]]
                                    for j in range(u32(off))]
        protos.append('(' + ''.join(params) + ')' + types[return_type])
    fields_size, fields_off = struct.unpack_from('<II', data, 80)
    fields = []
    for i in range(fields_size):
        owner, typ, name = struct.unpack_from('<HHI', data, fields_off+i*8)
        fields.append((types[owner], strings[name], types[typ]))
    methods_size, methods_off = struct.unpack_from('<II', data, 88)
    methods = []
    for i in range(methods_size):
        owner, proto, name = struct.unpack_from('<HHI', data, methods_off+i*8)
        methods.append((types[owner], strings[name], protos[proto]))
    classes_size, classes_off = struct.unpack_from('<II', data, 96)
    result = []
    for i in range(classes_size):
        owner, access, parent, interfaces, source, annotations, off, static_values = struct.unpack_from(
            '<8I', data, classes_off+i*32)
        name = types[owner]
        if not any(name.startswith(p) for p in prefixes):
            continue
        row = {'class': name, 'access': access,
               'parent': None if parent == 0xffffffff else types[parent],
               'fields': [], 'methods': []}
        if off:
            counts = []
            for _ in range(4):
                count, off = uleb(off)
                counts.append(count)
            for count in counts[:2]:
                index = 0
                for _ in range(count):
                    delta, off = uleb(off)
                    flags, off = uleb(off)
                    index += delta
                    f = fields[index]
                    row['fields'].append({'name': f[1], 'type': f[2], 'access': flags})
            for count in counts[2:]:
                index = 0
                for _ in range(count):
                    delta, off = uleb(off)
                    flags, off = uleb(off)
                    code, off = uleb(off)
                    index += delta
                    m = methods[index]
                    method = {'name': m[1], 'signature': m[2], 'access': flags}
                    if code:
                        n = u32(code+12)
                        method['code_sha256'] = hashlib.sha256(data[code+16:code+16+n*2]).hexdigest()
                        method['code_offset'] = code
                    row['methods'].append(method)
        result.append(row)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    parser.add_argument('prefixes', nargs='+')
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as apk:
        for name in apk.namelist():
            if name.endswith('.dex'):
                found = inspect(apk.read(name), args.prefixes)
                if found:
                    print(json.dumps({'dex': name, 'declarations': found}))
