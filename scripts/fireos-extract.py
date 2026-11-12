"""Pull files out of a Fire OS block-based update without building its system image.

Usage: fireos-extract.py SOURCE OUTDIR PATH...
SOURCE is the update's URL or a local copy. Each PATH is a file in the system
image as Amazon's file map names it (//system/...); it lands in OUTDIR under
its base name. Only as much of system.new.dat.br is fetched and decompressed as
it takes to reach the last block wanted, read with HTTP range requests when
SOURCE is a URL.
"""
import struct
import sys
import urllib.request
import zlib

import brotli

BS = 4096
FILE_MAP = 'META-INF/com/amazon/android/target.system.file_map'


class Source:
    def __init__(self, where):
        self.url = where if '://' in where else None
        self.file = None if self.url else open(where, 'rb')
        if self.url:
            req = urllib.request.Request(self.url, method='HEAD')
            with urllib.request.urlopen(req) as r:
                self.size = int(r.headers['Content-Length'])
        else:
            self.file.seek(0, 2)
            self.size = self.file.tell()

    def read(self, off, n):
        if not self.url:
            self.file.seek(off)
            return self.file.read(n)
        req = urllib.request.Request(self.url, headers={'Range': f'bytes={off}-{off + n - 1}'})
        with urllib.request.urlopen(req) as r:
            return r.read()

    def stream(self, off, n):
        if not self.url:
            self.file.seek(off)
            while n:
                chunk = self.file.read(min(n, 1 << 20))
                if not chunk:
                    return
                n -= len(chunk)
                yield chunk
            return
        req = urllib.request.Request(self.url, headers={'Range': f'bytes={off}-{off + n - 1}'})
        with urllib.request.urlopen(req) as r:
            while chunk := r.read(1 << 20):
                yield chunk


def zip_entries(src):
    tail = src.read(max(0, src.size - 65558), min(src.size, 65558))
    eocd = tail.rfind(b'PK\x05\x06')
    if eocd < 0:
        sys.exit('fireos-extract: no zip directory at the end of the update')
    cd_size, cd_off = struct.unpack_from('<II', tail, eocd + 12)
    cd = src.read(cd_off, cd_size)
    entries, p = {}, 0
    while cd.startswith(b'PK\x01\x02', p):
        method, = struct.unpack_from('<H', cd, p + 10)
        csize, usize, nlen, xlen, clen = struct.unpack_from('<IIHHH', cd, p + 20)
        local, = struct.unpack_from('<I', cd, p + 42)
        name = cd[p + 46:p + 46 + nlen].decode()
        entries[name] = (method, csize, usize, local)
        p += 46 + nlen + xlen + clen
    return entries


def entry_data_offset(src, local):
    head = src.read(local, 30)
    nlen, xlen = struct.unpack_from('<HH', head, 26)
    return local + 30 + nlen + xlen


def read_entry(src, entries, name):
    method, csize, usize, local = entries[name]
    data = src.read(entry_data_offset(src, local), csize)
    return zlib.decompress(data, -15) if method == 8 else data


def ranges(spec):
    v = [int(x) for x in spec.split(',')]
    if v[0] != len(v) - 1:
        sys.exit(f'fireos-extract: malformed range set {spec}')
    return [(v[i], v[i + 1]) for i in range(1, len(v), 2)]


def trim(name, data):
    """The file map counts whole blocks; recover the file's real length."""
    if data.startswith(b'\x7fELF'):
        shoff, = struct.unpack_from('<Q', data, 0x28)
        shentsize, shnum = struct.unpack_from('<HH', data, 0x3a)
        end = shoff + shentsize * shnum
        phoff, = struct.unpack_from('<Q', data, 0x20)
        phentsize, phnum = struct.unpack_from('<HH', data, 0x36)
        for i in range(phnum):
            off, = struct.unpack_from('<Q', data, phoff + i * phentsize + 8)
            filesz, = struct.unpack_from('<Q', data, phoff + i * phentsize + 32)
            end = max(end, off + filesz)
        return data[:end]
    return data.rstrip(b'\0')


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    src = Source(sys.argv[1])
    out, wanted = sys.argv[2], sys.argv[3:]
    entries = zip_entries(src)

    file_map = {}
    for line in read_entry(src, entries, FILE_MAP).decode().splitlines():
        parts = line.split()
        if len(parts) >= 3:
            file_map[parts[0]] = ranges(parts[2])
    transfer = read_entry(src, entries, 'system.transfer.list').decode().splitlines()

    # Each image block that is wanted, mapped to the file and its block within it.
    need = {}
    for path in wanted:
        if path not in file_map:
            sys.exit(f'fireos-extract: {path} is not in the file map')
        i = 0
        for a, b in file_map[path]:
            for blk in range(a, b):
                need[blk] = (path, i)
                i += 1
    blocks = {path: {} for path in wanted}

    # The order in which the image's blocks appear in system.new.dat.
    order = []
    for line in transfer[4:]:
        cmd, _, arg = line.partition(' ')
        if cmd == 'new':
            order.extend(ranges(arg))

    method, csize, _, local = entries['system.new.dat.br']
    if method != 0:
        sys.exit('fireos-extract: system.new.dat.br is compressed inside the zip')
    data = src.stream(entry_data_offset(src, local), csize)
    dec = brotli.Decompressor()
    pending = bytearray()
    remaining = len(need)
    seg, blk = 0, order[0][0] if order else 0
    fetched = 0
    for chunk in data:
        fetched += len(chunk)
        pending += dec.process(chunk)
        whole = len(pending) // BS
        for k in range(whole):
            if blk in need:
                path, i = need[blk]
                blocks[path][i] = bytes(pending[k * BS:(k + 1) * BS])
                remaining -= 1
            blk += 1
            if blk == order[seg][1]:
                seg += 1
                if seg == len(order):
                    break
                blk = order[seg][0]
        del pending[:whole * BS]
        if remaining == 0 or seg == len(order):
            break
    if remaining:
        sys.exit(f'fireos-extract: the update ended with {remaining} blocks still missing')

    for path in wanted:
        got = blocks[path]
        raw = b''.join(got[i] for i in range(len(got)))
        name = path.rsplit('/', 1)[1]
        with open(f'{out}/{name}', 'wb') as f:
            f.write(trim(name, raw))
    print(f'fireos-extract: {len(wanted)} files after {fetched / 1e6:.0f} MB of {csize / 1e6:.0f} MB')


main()
