"""Tests for check_elf_alignment.py. Standard library only."""

import io
import os
import struct
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_elf_alignment as check  # noqa: E402

PT_LOAD, PT_DYNAMIC = 1, 2


def elf(segments, is_64=True, little=True, xnum=False):
    """Build a minimal ELF file whose program headers are (p_type, p_align) pairs."""
    end = "<" if little else ">"
    header_size, entry_size = (64, 56) if is_64 else (52, 32)
    phoff = header_size
    count = len(segments)
    shoff = phoff + count * entry_size if xnum else 0
    ident = b"\x7fELF" + bytes([2 if is_64 else 1, 1 if little else 2, 1]) + bytes(9)
    phnum_field = 0xFFFF if xnum else count
    if is_64:
        header = ident + struct.pack(end + "HHIQQQIHHHHHH", 3, 183, 1, 0, phoff, shoff, 0,
                                     header_size, entry_size, phnum_field, 64, 1 if xnum else 0, 0)
    else:
        header = ident + struct.pack(end + "HHIIIIIHHHHHH", 3, 40, 1, 0, phoff, shoff, 0,
                                     header_size, entry_size, phnum_field, 40, 1 if xnum else 0, 0)
    table = b""
    for p_type, p_align in segments:
        if is_64:
            table += struct.pack(end + "IIQQQQQQ", p_type, 5, 0, 0, 0, 0, 0, p_align)
        else:
            table += struct.pack(end + "IIIIIIII", p_type, 0, 0, 0, 0, 0, 5, p_align)
    sections = b""
    if xnum:
        if is_64:
            sections = struct.pack(end + "IIQQQQIIQQ", 0, 0, 0, 0, 0, 0, 0, count, 0, 0)
        else:
            sections = struct.pack(end + "IIIIIIIIII", 0, 0, 0, 0, 0, 0, 0, count, 0, 0)
    return header + table + sections


class TempApk:
    """Writes an APK-like zip into a temporary folder."""

    def __init__(self, test):
        self.dir = tempfile.TemporaryDirectory()
        test.addCleanup(self.dir.cleanup)

    def write(self, entries, name="app.apk"):
        """entries: (name, data, compress_type, pad) where pad is the extra-field length to add."""
        path = os.path.join(self.dir.name, name)
        with zipfile.ZipFile(path, "w") as apk:
            for entry_name, data, compress_type, pad in entries:
                info = zipfile.ZipInfo(entry_name)
                info.compress_type = compress_type
                if pad:
                    info.extra = struct.pack("<HH", 0xD935, pad - 4) + bytes(pad - 4)
                apk.writestr(info, data)
        return path


def run(args):
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        status = check.main(args)
    return status, out.getvalue(), err.getvalue()


def write_aligned_apk(test, libraries, filler=b"classes"):
    """Build an APK whose stored .so entries start on 16 KB boundaries, by padding the extra field."""
    apk = TempApk(test)
    entries = [("classes.dex", filler, zipfile.ZIP_DEFLATED, 0)]
    # Write once to learn the offsets, then pad each stored entry's extra field to reach a boundary.
    probe = apk.write(entries + [(n, d, zipfile.ZIP_STORED, 0) for n, d in libraries], "probe.apk")
    pads = []
    with zipfile.ZipFile(probe) as z:
        shift = 0
        for name, data in libraries:
            info = z.getinfo(name)
            start = info.header_offset + shift + 30 + len(name.encode())
            pad = (-start) % check.PAGE_16K
            if 0 < pad < 4:
                pad += check.PAGE_16K
            pads.append(pad)
            shift += pad
    return apk.write(entries + [(n, d, zipfile.ZIP_STORED, p)
                                for (n, d), p in zip(libraries, pads)])


class LoadAlignments(unittest.TestCase):
    def test_reads_every_load_segment_in_64_bit_little_endian(self):
        data = elf([(PT_LOAD, 16384), (PT_DYNAMIC, 8), (PT_LOAD, 65536)])
        self.assertEqual(check.load_alignments(data), [16384, 65536])

    def test_reads_32_bit_big_endian(self):
        data = elf([(PT_LOAD, 4096), (PT_LOAD, 16384)], is_64=False, little=False)
        self.assertEqual(check.load_alignments(data), [4096, 16384])

    def test_reads_the_extended_segment_count(self):
        data = elf([(PT_LOAD, 16384), (PT_LOAD, 4096)], xnum=True)
        self.assertEqual(check.load_alignments(data), [16384, 4096])

    def test_refuses_files_that_arent_elf(self):
        for data in (b"", b"not an elf file at all", b"\x7fELF" + bytes([3, 1]) + bytes(70)):
            with self.subTest(data=data[:8]), self.assertRaises(check.ElfError):
                check.load_alignments(data)

    def test_refuses_a_truncated_program_header_table(self):
        data = elf([(PT_LOAD, 16384), (PT_LOAD, 16384)])
        with self.assertRaises(check.ElfError):
            check.load_alignments(data[:-10])

    def test_refuses_a_library_without_load_segments(self):
        with self.assertRaises(check.ElfError):
            check.load_alignments(elf([(PT_DYNAMIC, 8)]))


class Main(unittest.TestCase):
    def test_an_apk_without_native_code_passes_and_says_so(self):
        path = TempApk(self).write([("classes.dex", b"dex", zipfile.ZIP_DEFLATED, 0),
                                    ("res/raw/notes.so.txt", b"x", zipfile.ZIP_DEFLATED, 0)])
        status, out, _ = run([path])
        self.assertEqual(status, 0)
        self.assertIn("no native libraries", out)

    def test_aligned_libraries_pass_stored_or_compressed(self):
        good = elf([(PT_LOAD, 16384), (PT_LOAD, 16384)])
        path = write_aligned_apk(self, [("lib/arm64-v8a/libgood.so", good),
                                        ("lib/x86_64/libgood.so", good)])
        status, out, err = run([path])
        self.assertEqual((status, err), (0, ""))
        self.assertIn("2 native libraries", out)
        compressed = TempApk(self).write([("lib/arm64-v8a/libgood.so", good, zipfile.ZIP_DEFLATED, 0)])
        self.assertEqual(run([compressed])[0], 0)

    def test_a_4k_aligned_library_fails_and_is_named(self):
        bad = elf([(PT_LOAD, 16384), (PT_LOAD, 4096)])
        path = TempApk(self).write([("lib/arm64-v8a/libold.so", bad, zipfile.ZIP_DEFLATED, 0)])
        status, out, err = run([path])
        self.assertEqual(status, 1)
        self.assertIn("app.apk: lib/arm64-v8a/libold.so has a LOAD segment aligned to 4096", err)

    def test_a_stored_library_off_a_16k_boundary_fails(self):
        good = elf([(PT_LOAD, 16384)])
        path = TempApk(self).write([("classes.dex", b"dex", zipfile.ZIP_DEFLATED, 0),
                                    ("lib/x86_64/libgood.so", good, zipfile.ZIP_STORED, 0)])
        status, _, err = run([path])
        self.assertEqual(status, 1)
        self.assertIn("lib/x86_64/libgood.so is stored uncompressed but doesn't start on a 16 KB", err)

    def test_a_so_that_isnt_elf_fails(self):
        path = TempApk(self).write([("lib/arm64-v8a/libfake.so", b"text", zipfile.ZIP_DEFLATED, 0)])
        status, _, err = run([path])
        self.assertEqual(status, 1)
        self.assertIn("lib/arm64-v8a/libfake.so isn't an ELF file", err)

    def test_only_lib_abi_so_entries_are_checked(self):
        bad = elf([(PT_LOAD, 4096)])
        path = TempApk(self).write([("assets/lib/x/libbad.so", bad, zipfile.ZIP_DEFLATED, 0),
                                    ("lib/arm64-v8a/sub/libbad.so", bad, zipfile.ZIP_DEFLATED, 0),
                                    ("lib/libbad.so", bad, zipfile.ZIP_DEFLATED, 0)])
        self.assertEqual(run([path])[0], 0)

    def test_messages_name_the_apk_file_only_not_its_folder(self):
        bad = elf([(PT_LOAD, 4096)])
        path = TempApk(self).write([("lib/arm64-v8a/libold.so", bad, zipfile.ZIP_DEFLATED, 0)])
        _, out, err = run([path])
        self.assertNotIn(os.path.dirname(path), out + err)

    def test_a_file_that_isnt_a_zip_is_a_setup_error(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = os.path.join(folder.name, "broken.apk")
        with open(path, "wb") as handle:
            handle.write(b"not a zip")
        self.assertEqual(run([path])[0], 2)
        self.assertEqual(run([os.path.join(folder.name, "missing.apk")])[0], 2)

    def test_no_arguments_is_a_usage_error(self):
        self.assertEqual(run([])[0], 2)
        self.assertEqual(run(["--help"])[0], 2)


if __name__ == "__main__":
    unittest.main()
