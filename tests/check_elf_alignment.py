#!/usr/bin/env python3
"""Check that the native libraries in an APK work on devices with 16 KB memory pages.

Newer Android devices use 16 KB memory pages. A native library built for 4 KB pages runs there only in
a slower compatibility mode, and Google Play rejects it. For every lib/<abi>/<name>.so in each APK:

  * every LOAD segment in the library's ELF program header table must be aligned to 16384 bytes or
    more;
  * a library stored uncompressed must start at a multiple of 16384 bytes inside the APK, so that it
    can be mapped straight from the file.

Messages name the APK file and the library's path inside it, and nothing else.

usage: check_elf_alignment.py APK [APK ...]

Exit status: 0 when every library passes (or an APK has none), 1 when a library fails, 2 when a file
can't be read as an APK. Standard library only; runs on Python 3.9 or later.
"""

from __future__ import annotations

import os
import re
import struct
import sys
import zipfile

PAGE_16K = 16384
NATIVE_LIBRARY = re.compile(r"lib/[^/]+/[^/]+\.so")
PT_LOAD = 1
PN_XNUM = 0xFFFF
LOCAL_HEADER_SIGNATURE = b"PK\x03\x04"
LOCAL_HEADER_SIZE = 30


class ElfError(Exception):
    """The file isn't an ELF file this check can read. The message says why in plain words."""


def load_alignments(data: bytes) -> list[int]:
    """Return the p_align value of every PT_LOAD segment in an ELF file."""
    if len(data) < 16 or data[:4] != b"\x7fELF":
        raise ElfError("isn't an ELF file")
    elf_class, byte_order = data[4], data[5]
    if elf_class not in (1, 2):
        raise ElfError("has an unknown ELF class")
    if byte_order not in (1, 2):
        raise ElfError("has an unknown ELF byte order")
    end = "<" if byte_order == 1 else ">"
    is_64 = elf_class == 2

    header_size = 64 if is_64 else 52
    if len(data) < header_size:
        raise ElfError("is cut short inside its ELF header")
    if is_64:
        phoff, shoff = struct.unpack_from(end + "QQ", data, 32)
        phentsize, phnum = struct.unpack_from(end + "HH", data, 54)
        shentsize = struct.unpack_from(end + "H", data, 58)[0]
    else:
        phoff, shoff = struct.unpack_from(end + "II", data, 28)
        phentsize, phnum = struct.unpack_from(end + "HH", data, 42)
        shentsize = struct.unpack_from(end + "H", data, 46)[0]

    if phnum == PN_XNUM:
        # Too many segments for the header field: the real count is in section header 0's sh_info.
        info_at = 44 if is_64 else 28
        if shoff == 0 or shentsize < info_at + 4 or shoff + info_at + 4 > len(data):
            raise ElfError("has an extended segment count that can't be read")
        phnum = struct.unpack_from(end + "I", data, shoff + info_at)[0]

    entry_size = 56 if is_64 else 32
    if phnum == 0:
        raise ElfError("has no program headers")
    if phentsize < entry_size:
        raise ElfError("has program header entries that are too small")
    if phoff + phnum * phentsize > len(data):
        raise ElfError("is cut short inside its program header table")

    alignments = []
    for index in range(phnum):
        at = phoff + index * phentsize
        p_type = struct.unpack_from(end + "I", data, at)[0]
        if p_type != PT_LOAD:
            continue
        if is_64:
            p_align = struct.unpack_from(end + "Q", data, at + 48)[0]
        else:
            p_align = struct.unpack_from(end + "I", data, at + 28)[0]
        alignments.append(p_align)
    if not alignments:
        raise ElfError("has no LOAD segment")
    return alignments


def data_offset(apk_file, info: zipfile.ZipInfo) -> int:
    """Where an entry's data starts in the APK, read from its local file header."""
    apk_file.seek(info.header_offset)
    header = apk_file.read(LOCAL_HEADER_SIZE)
    if len(header) != LOCAL_HEADER_SIZE or header[:4] != LOCAL_HEADER_SIGNATURE:
        raise zipfile.BadZipFile("bad local file header")
    name_length, extra_length = struct.unpack_from("<HH", header, 26)
    return info.header_offset + LOCAL_HEADER_SIZE + name_length + extra_length


def check_apk(path: str) -> tuple[int, list[str]]:
    """Check one APK. Returns (number of native libraries, problems)."""
    name = os.path.basename(path)
    problems = []
    with open(path, "rb") as apk_file, zipfile.ZipFile(apk_file) as apk:
        libraries = sorted((info for info in apk.infolist() if NATIVE_LIBRARY.fullmatch(info.filename)),
                           key=lambda info: info.filename)
        for info in libraries:
            where = f"{name}: {info.filename}"
            try:
                alignments = load_alignments(apk.read(info))
            except ElfError as error:
                problems.append(f"{where} {error}")
                continue
            smallest = min(alignments)
            if smallest < PAGE_16K:
                problems.append(f"{where} has a LOAD segment aligned to {smallest} bytes; "
                                f"16 KB pages need {PAGE_16K} or more")
            if info.compress_type == zipfile.ZIP_STORED:
                offset = data_offset(apk_file, info)
                if offset % PAGE_16K:
                    problems.append(f"{where} is stored uncompressed but doesn't start on a 16 KB "
                                    "boundary in the APK (zip-align it with 16 KB page alignment)")
    return len(libraries), problems


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if not args or any(arg.startswith("-") for arg in args):
        print("usage: check_elf_alignment.py APK [APK ...]", file=sys.stderr)
        return 2

    failed = False
    for path in args:
        name = os.path.basename(path)
        try:
            count, problems = check_apk(path)
        except (OSError, zipfile.BadZipFile) as error:
            reason = "isn't a readable APK" if isinstance(error, zipfile.BadZipFile) else "can't be opened"
            print(f"check_elf_alignment: {name} {reason}", file=sys.stderr)
            return 2
        if problems:
            failed = True
            for problem in problems:
                print(f"check_elf_alignment: {problem}", file=sys.stderr)
        elif count == 0:
            print(f"check_elf_alignment: {name}: no native libraries, nothing to check")
        else:
            noun = "library" if count == 1 else "libraries"
            print(f"check_elf_alignment: {name}: {count} native {noun}, all aligned for 16 KB pages")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
