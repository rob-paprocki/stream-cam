"""Tests of scripts/release/changelog_check.py, run on small temporary git repositories.

The check reads private/changelog-skip.txt when it exists: reviewed commits it skips, one full commit
SHA and a reason per line. Only the development repository has that file.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(os.path.dirname(HERE), "scripts", "release", "changelog_check.py")
SKIP_LIST = os.path.join("private", "changelog-skip.txt")

# Commits get a fixed identity, and no user or system git configuration (signing, hooks) applies.
ENV = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@example.com",
           GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@example.com",
           GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")


class ChangelogCheckTests(unittest.TestCase):
    def setUp(self):
        self.repo = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.repo, ignore_errors=True)
        self.git("init", "-q", "-b", "main")
        self.commit("Add the changelog", {"CHANGELOG.md": "# Changelog\n"})

    def git(self, *args):
        return subprocess.run(["git", "-C", self.repo] + list(args), capture_output=True, text=True,
                              check=True, env=ENV).stdout.strip()

    def write(self, path, text):
        full = os.path.join(self.repo, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as handle:
            handle.write(text)

    def commit(self, message, files):
        for path, text in files.items():
            self.write(path, text)
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")

    def skip_list(self, text):
        self.write(SKIP_LIST, text)

    def run_check(self, *args):
        result = subprocess.run([sys.executable, "-B", "-I", SCRIPT] + list(args), cwd=self.repo,
                                capture_output=True, text=True, env=ENV)
        return result.returncode, result.stdout + result.stderr

    def test_a_visible_change_without_a_changelog_line_fails(self):
        sha = self.commit("Change the build", {"android/build.gradle.kts": "// one\n"})
        status, out = self.run_check()
        self.assertEqual(status, 1, out)
        self.assertIn(sha[:10], out)
        self.assertNotIn("skipped", out)

    def test_without_a_skip_list_nothing_is_skipped_or_counted(self):
        self.commit("Change the docs [no changelog]", {"docs/a.md": "a\n"})
        status, out = self.run_check()
        self.assertEqual(status, 0, out)
        self.assertNotIn("skipped", out)

    def test_a_listed_commit_is_skipped_and_counted(self):
        listed = self.commit("Reword a comment", {"android/build.gradle.kts": "// one\n"})
        self.skip_list("# Reviewed commits\n\n  # an indented comment\n%s  changes a comment only; nothing "
                       "visible\n" % listed)
        status, out = self.run_check()
        self.assertEqual(status, 0, out)
        self.assertIn("skipped 1 commit", out)
        self.assertIn("changelog check: OK", out)

    def test_only_listed_commits_are_skipped(self):
        listed = self.commit("Reword a comment", {"android/build.gradle.kts": "// one\n"})
        other = self.commit("Change the build", {"android/build.gradle.kts": "// two\n"})
        self.skip_list("%s\tcomments only\n" % listed)
        status, out = self.run_check()
        self.assertEqual(status, 1, out)
        self.assertIn(other[:10], out)
        self.assertNotIn(listed[:10], out)
        self.assertIn("skipped 1 commit", out)

    def test_a_listed_commit_before_the_base_is_not_counted(self):
        listed = self.commit("Reword a comment", {"android/build.gradle.kts": "// one\n"})
        self.commit("Change the docs [no changelog]", {"docs/a.md": "a\n"})
        self.skip_list("%s comments only\n" % listed)
        status, out = self.run_check(listed)
        self.assertEqual(status, 0, out)
        self.assertIn("skipped 0 commits", out)

    def test_malformed_lines_fail_and_name_the_line(self):
        listed = self.commit("Reword a comment", {"android/build.gradle.kts": "// one\n"})
        cases = {
            "no reason": listed,
            "short SHA": listed[:10] + " comments only",
            "not hex": "g" * 40 + " comments only",
            "upper case": listed.upper() + " comments only",
            "no space": listed + "comments only",
            "too long": listed + "a comments only",
            "comment after a SHA only": listed + " # comments only",
        }
        for name, line in cases.items():
            with self.subTest(case=name):
                self.skip_list("# comment\n%s\n" % line)
                status, out = self.run_check()
                self.assertEqual(status, 2, out)
                self.assertIn("changelog-skip.txt line 2", out)
                self.assertNotIn("changelog check: OK", out)

    def test_a_sha_that_is_not_in_the_history_fails(self):
        listed = self.commit("Reword a comment", {"android/build.gradle.kts": "// one\n"})
        tree = self.git("rev-parse", "HEAD^{tree}")
        self.git("checkout", "-q", "-b", "side")
        elsewhere = self.commit("On another branch", {"docs/side.md": "side\n"})
        self.git("checkout", "-q", "main")
        for name, sha in (("unknown", "0123456789abcdef" * 2 + "01234567"), ("a tree, not a commit", tree),
                          ("on another branch", elsewhere)):
            with self.subTest(case=name):
                self.skip_list("%s  comments only\n%s  something else\n" % (listed, sha))
                status, out = self.run_check()
                self.assertEqual(status, 2, out)
                self.assertIn("changelog-skip.txt line 2", out)
                self.assertIn("history", out)

    def test_a_commit_listed_twice_fails(self):
        listed = self.commit("Reword a comment", {"android/build.gradle.kts": "// one\n"})
        self.skip_list("%s  comments only\n%s  again\n" % (listed, listed))
        status, out = self.run_check()
        self.assertEqual(status, 2, out)
        self.assertIn("changelog-skip.txt line 2", out)

    def test_a_bad_list_fails_even_when_no_commit_needs_it(self):
        self.commit("Change the docs [no changelog]", {"docs/a.md": "a\n"})
        self.skip_list("not a commit\n")
        status, out = self.run_check()
        self.assertEqual(status, 2, out)

    def test_an_unreadable_list_fails(self):
        os.makedirs(os.path.join(self.repo, SKIP_LIST))
        status, out = self.run_check()
        self.assertEqual(status, 2, out)
        self.assertIn("changelog-skip.txt", out)


if __name__ == "__main__":
    unittest.main()
