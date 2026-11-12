"""Pull files out of a Kindle e-reader update.

Usage: kindle-extract.py SOURCE OUTDIR PATH...
SOURCE is the update's URL or a local copy. Each PATH names a file either in
the update's user-data archive, as data/<path>, or in the speech engine image
in its system partition, as tts/<path>; it lands in OUTDIR under its base name.
The update is one compressed stream, so all of it is read, once.

The layout follows KindleTool: a signature envelope (SP01), a recovery header
(FB03), then a gzipped tarball whose bytes are scrambled by XOR with 0x7A and
a nibble swap. The tarball holds rootfs.img.gz, an ext3 image, and data.stgz,
a signature envelope around a plain gzipped tarball of user data.
Needs debugfs and unsquashfs on PATH.
"""
import gzip
import io
import os
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

UNSCRAMBLE = bytes((((c ^ 0x7A) >> 4) | ((c ^ 0x7A) << 4)) & 0xFF for c in range(256))
CERTIFICATE_SIZES = {0: 128, 1: 128, 2: 256}
RECOVERY_HEADER = 131068


class Unscrambled(io.RawIOBase):
    def __init__(self, raw):
        self.raw = raw

    def readable(self):
        return True

    def readinto(self, b):
        data = self.raw.read(len(b))
        b[:len(data)] = data.translate(UNSCRAMBLE)
        return len(data)


def exactly(stream, n):
    data = stream.read(n)
    if len(data) != n:
        sys.exit('kindle-extract: the update ends early')
    return data


def skip_envelope(stream):
    """Steps over an SP01 signature envelope, if the stream starts with one."""
    head = exactly(stream, 4)
    if head != b'SP01':
        return head
    cert, = struct.unpack_from('<I', exactly(stream, 60))
    if cert not in CERTIFICATE_SIZES:
        sys.exit(f'kindle-extract: unknown certificate number {cert}')
    exactly(stream, CERTIFICATE_SIZES[cert])
    return exactly(stream, 4)


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    source, out, wanted = sys.argv[1], sys.argv[2], sys.argv[3:]
    data_files = {p[5:] for p in wanted if p.startswith('data/')}
    tts_files = {p[4:] for p in wanted if p.startswith('tts/')}
    if len(data_files) + len(tts_files) != len(wanted):
        sys.exit('kindle-extract: each path starts with data/ or tts/')

    raw = urllib.request.urlopen(source) if '://' in source else open(source, 'rb')
    with raw, tempfile.TemporaryDirectory(dir=out) as tmp:
        if skip_envelope(raw) != b'FB03':
            sys.exit('kindle-extract: not a recovery-style (FB03) update')
        exactly(raw, RECOVERY_HEADER)
        stream = io.BufferedReader(Unscrambled(raw), 1 << 20)
        rootfs = os.path.join(tmp, 'rootfs.img')
        user_data = None
        with tarfile.open(fileobj=stream, mode='r|gz') as tar:
            for member in tar:
                if member.name == 'rootfs.img.gz' and tts_files:
                    with gzip.open(tar.extractfile(member)) as src, open(rootfs, 'wb') as dst:
                        shutil.copyfileobj(src, dst, 1 << 20)
                elif member.name == 'data.stgz' and data_files:
                    user_data = tar.extractfile(member).read()

        found = {}
        if data_files:
            if user_data is None:
                sys.exit('kindle-extract: the update has no user data')
            inner = io.BytesIO(user_data)
            head = skip_envelope(inner)
            inner = io.BytesIO(head + inner.read())
            with tarfile.open(fileobj=inner, mode='r:gz') as tar:
                for member in tar.getmembers():
                    if member.isfile() and member.name in data_files:
                        found['data/' + member.name] = tar.extractfile(member).read()
        if tts_files:
            if not os.path.exists(rootfs):
                sys.exit('kindle-extract: the update has no system image')
            image = os.path.join(tmp, 'tts.sqsh')
            subprocess.run(['debugfs', '-R', f'dump /usr/lib/tts.sqsh {image}', rootfs],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            unpacked = os.path.join(tmp, 'tts')
            subprocess.run(['unsquashfs', '-q', '-n', '-d', unpacked, image, *sorted(tts_files)],
                           check=True, stdout=subprocess.DEVNULL)
            for path in tts_files:
                f = os.path.join(unpacked, path)
                if os.path.isfile(f):
                    found['tts/' + path] = open(f, 'rb').read()

        missing = [p for p in wanted if p not in found]
        if missing:
            sys.exit(f'kindle-extract: not in the update: {" ".join(missing)}')
        for path, data in found.items():
            with open(os.path.join(out, os.path.basename(path)), 'wb') as f:
                f.write(data)
    print(f'kindle-extract: {len(wanted)} files')


main()
