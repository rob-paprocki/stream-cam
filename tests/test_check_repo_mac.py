"""Tests of check_repo.py's MAC address pattern, next to dashes, colons and copy numbers.

Every disallowed value is built at run time, so none appears in this (public) file.
"""
import importlib.util
import os
import shutil
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


check_repo = load("check_repo", os.path.join(HERE, "check_repo.py"))

PAIRS = ["e0", "51", "d8", "00", "11", "22", "33", "44"]


def run(sep, count=6):
    """COUNT of the sample hex pairs joined by SEP: six make a MAC address outside the documentation range."""
    return sep.join((PAIRS * 8)[:count])


def kinds(text):
    return [k for _, k in check_repo.scan_text(text)]


class MacNextToACopyNumberTests(unittest.TestCase):
    def test_dash_mac_followed_by_a_copy_number_is_found(self):
        mac = run("-")
        for text in ("scan-%s-1.json" % mac, "cap-%s-2.txt" % mac, "x-%s-1.json" % mac,
                     "%s-9" % mac, "dev %s-3 up" % mac, "%s-123.json" % mac):
            with self.subTest(text=text):
                self.assertEqual(kinds(text), ["MAC address"])

    def test_dash_mac_with_a_copy_number_is_found_in_a_file_name(self):
        root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, root)
        name = "scan-%s-1.json" % run("-")
        with open(os.path.join(root, name), "w", encoding="utf-8") as f:
            f.write("{}\n")
        self.assertEqual(check_repo.check(root), ["%s: MAC address in the file name" % name])

    def test_documentation_range_with_a_copy_number_is_allowed(self):
        self.assertEqual(kinds("scan-00-00-5e-00-53-01-1.json, cap-00-00-5E-00-53-02-2.txt"), [])

    def test_colon_mac_next_to_a_dash_or_colon_is_still_found(self):
        mac = run(":")
        for text in ("file-%s.txt" % mac, "mac:%s" % mac, "MAC %s: link up" % mac, "%s:port" % mac,
                     "%s-1.json" % mac):
            with self.subTest(text=text):
                self.assertEqual(kinds(text), ["MAC address"])


class LongerHexRunTests(unittest.TestCase):
    def test_runs_of_more_than_six_pairs_are_not_macs(self):
        for sep in (":", "-"):
            for text in (run(sep, 7),                                   # seven pairs
                         run(sep, 8),                                   # an EUI-64 ID
                         "fp " + run(sep, 16),                          # key fingerprints
                         "sha256 " + run(sep, 32),
                         "ff" + sep + run(sep),                         # one more pair in front
                         run(sep) + sep + "ff",                         # or behind
                         "x-" + run(sep) + sep + "01.json"):            # a two-digit copy number reads as a 7th pair
                with self.subTest(text=text):
                    self.assertNotIn("MAC address", kinds(text))

    def test_a_lone_digit_after_a_colon_or_in_front_still_makes_a_longer_run(self):
        for text in ("dump " + run(":") + ":1", "1:" + run(":"), "1-" + run("-")):
            with self.subTest(text=text):
                self.assertNotIn("MAC address", kinds(text))


if __name__ == "__main__":
    unittest.main()
