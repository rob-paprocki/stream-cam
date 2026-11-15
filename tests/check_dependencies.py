#!/usr/bin/env python3
"""Dependency gate (spec LIC-3) and F-Droid hygiene checks for the Android build.

1. Every library and plugin in android/gradle/libs.versions.toml, and every direct dependency of each
   classpath listed below, must match an entry in config/approved-dependencies.txt.
2. Gradle files declare dependencies and plugins through the version catalog only. No string anywhere
   may be a "group:artifact:version" coordinate, and no call may take group and name as strings. Inside
   a dependencies { } block, and in a dependencies.name(...) call, a string may only be an argument of
   project(), exclude() or because(), the configuration name add() takes first, or the name in
   "configuration"(...). Catalog aliases need no strings, so every other string there fails, however a
   coordinate is put together: positional or named arguments, a variable next to strings, a map,
   dependencies.create(), String.format(), + or .plus(), or a $template. File dependencies, the
   kotlin("...") shorthand and plugins applied with id("...") instead of alias(libs.plugins...) fail too.
   So every direct dependency is a catalog entry, and check 1 covers it. Gradle's own core plugins, whose
   IDs have no dot (java-library, maven-publish), may still be applied with id().
3. No resolved library may match a "!" entry there, or come from Google Play services or Firebase.
4. config/resolved-dependencies.txt lists what these classpaths resolve to, one group:artifact:version per
   line, sorted, in one section per classpath:

     :app fossReleaseRuntimeClasspath         every library the release APK ships
     :app fossDebugRuntimeClasspath           what the debug APK adds
     :app fossDebugUnitTestRuntimeClasspath   what the app's unit tests add
     :core testRuntimeClasspath               what the core module's unit tests add

   A section lists only the libraries that no section above it lists. This script rewrites the file;
   with --check it fails instead when the file is out of date, so every dependency change, debug and
   test libraries included, shows up in a pull request's diff.
5. F-Droid hygiene: no Gradle file names Google Play services or Firebase, and Gradle files declare no
   repository other than google(), mavenCentral(), gradlePluginPortal() and https://jitpack.io.

The dependency trees come from Gradle's dependency report, run in android/:

    ./gradlew -q :app:dependencies --configuration fossReleaseRuntimeClasspath \
        :core:dependencies --configuration testRuntimeClasspath
    ./gradlew -q :app:dependencies --configuration fossDebugRuntimeClasspath
    ./gradlew -q :app:dependencies --configuration fossDebugUnitTestRuntimeClasspath

Without --gradle-output this script runs those commands itself; with it, it reads a saved copy of their
output, all of it in one file ("-" reads standard input).

usage: check_dependencies.py [--check] [--gradle-output FILE]

Exit status: 0 when everything passes, 1 when a check fails, 2 when the check can't run.
Standard library only. Python 3.11 or later, for tomllib.
"""

from __future__ import annotations

import argparse
import datetime
import difflib
import fnmatch
import os
import re
import subprocess
import sys

CHECKOUT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID_DIR = os.path.join(CHECKOUT, "android")
APPROVED_FILE = "config/approved-dependencies.txt"
RESOLVED_FILE = "config/resolved-dependencies.txt"
CATALOG_FILE = "android/gradle/libs.versions.toml"
# (project, configuration, what its section in RESOLVED_FILE holds). The release classpath comes first,
# and each later section lists only what the sections above it don't.
CLASSPATHS = (
    (":app", "fossReleaseRuntimeClasspath", "every library the release APK ships"),
    (":app", "fossDebugRuntimeClasspath", "what the debug APK adds"),
    (":app", "fossDebugUnitTestRuntimeClasspath", "what the app's unit tests add"),
    (":core", "testRuntimeClasspath", "what the core module's unit tests add"),
)
RELEASE_PROJECT, RELEASE_CONFIGURATION = CLASSPATHS[0][:2]

RESOLVED_HEADER = """\
# What each classpath of the Android build resolves to, as group:artifact:version, sorted. The first
# section is every library the release APK ships; each section after it lists only the libraries that
# no section above it lists.
# Written by tests/check_dependencies.py, and CI fails when it's out of date. Don't edit it by hand:
# after changing a dependency, run `python3 -I tests/check_dependencies.py` and commit the result.
"""


def gradle_commands() -> list[list[str]]:
    """The Gradle runs that print every report in CLASSPATHS. Gradle runs a task once per build, so
    each run asks for at most one configuration per project."""
    per_project: dict[str, list[str]] = {}
    for project, configuration, _ in CLASSPATHS:
        per_project.setdefault(project, []).append(configuration)
    commands = []
    for index in range(max(len(names) for names in per_project.values())):
        command = ["./gradlew", "-q"]
        for project, names in per_project.items():
            if index < len(names):
                command += [f"{project}:dependencies", "--configuration", names[index]]
        commands.append(command)
    return commands

# F-Droid refuses Google Play services, Firebase and Play Core (spec LIC-1 rules out the services they
# exist for, too). These are group prefixes, and they are matched in Gradle files as plain text.
NON_FREE_GROUPS = ("com.google.android.gms", "com.google.gms", "com.google.firebase",
                   "com.google.android.play")
NON_FREE_TEXT = re.compile(r"com\.google\.(?:android\.gms|gms|firebase|android\.play)\b")

ALLOWED_REPOSITORY_URLS = {"https://jitpack.io"}
GRADLE_FILE_SUFFIXES = (".gradle.kts", ".gradle")
SCANNED_FILE_SUFFIXES = GRADLE_FILE_SUFFIXES + (".versions.toml", "gradle.properties")
SKIPPED_DIRS = {"build", ".gradle", ".kotlin", ".idea", ".cxx", ".externalNativeBuild", "captures"}

COORDINATE_PART = re.compile(r"[A-Za-z0-9._*-]+")
PLUGIN_ID = re.compile(r"[A-Za-z0-9._*-]+")
PYTHON_PACKAGE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
SPDX_EXPRESSION = re.compile(r"[A-Za-z0-9.+-]+(?: (?:AND|OR|WITH) [A-Za-z0-9.+-]+)*")
TREE_LINE = re.compile(r"((?:[| ]    )*)[+\\]--- (.*)")
TREE_MARK = re.compile(r"(.*?) \(([*cn])\)")
PROJECT_BANNER = re.compile(r"(?:Root project|Project) '([^']*)'")
CONFIGURATION_HEADER = re.compile(r"([A-Za-z][A-Za-z0-9_]*) - ")


class SetupError(Exception):
    """The check can't run: a missing or malformed input. Exit status 2."""


class Entry:
    """One line of config/approved-dependencies.txt."""

    def __init__(self, line_number: int, kind: str, pattern: str):
        self.line_number = line_number
        self.kind = kind              # "library", "plugin", "ci" or "denied"
        self.pattern = pattern        # without its "plugin:", "ci:" or "!" prefix

    def matches(self, name: str) -> bool:
        if self.kind == "plugin":
            return fnmatch.fnmatchcase(name, self.pattern)
        group, _, artifact = name.partition(":")
        group_pattern, _, artifact_pattern = self.pattern.partition(":")
        return fnmatch.fnmatchcase(group, group_pattern) and fnmatch.fnmatchcase(artifact, artifact_pattern)


# ---------------------------------------------------------------------------------------------------
# Inputs


def read_text(relative: str) -> str:
    try:
        with open(os.path.join(CHECKOUT, relative), encoding="utf-8") as handle:
            return handle.read()
    except (OSError, UnicodeDecodeError) as error:
        raise SetupError(f"{relative} can't be read ({error.__class__.__name__})") from None


def parse_approved(text: str, source: str = APPROVED_FILE) -> list[Entry]:
    entries, seen = [], set()
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        where = f"{source} line {number}"
        columns = [column.strip() for column in line.split("|")]
        if len(columns) != 4 or not all(columns):
            raise SetupError(f"{where}: needs four columns separated by \"|\": pattern, licence, date, "
                             "what it is")
        pattern, licence, date, _ = columns
        if pattern.startswith("!"):
            kind, pattern = "denied", pattern[1:]
        elif pattern.startswith("plugin:"):
            kind, pattern = "plugin", pattern[len("plugin:"):]
        elif pattern.startswith("ci:"):
            kind, pattern = "ci", pattern[len("ci:"):]
        else:
            kind = "library"
        if kind == "plugin":
            if not PLUGIN_ID.fullmatch(pattern):
                raise SetupError(f"{where}: the plugin pattern isn't a plugin ID")
        elif kind == "ci":
            if not PYTHON_PACKAGE.fullmatch(pattern):
                raise SetupError(f"{where}: a ci: entry takes a Python package name")
        else:
            parts = pattern.split(":")
            if len(parts) != 2 or not all(COORDINATE_PART.fullmatch(part) for part in parts):
                raise SetupError(f"{where}: the pattern isn't group:artifact")
        if kind == "denied":
            if licence != "-":
                raise SetupError(f"{where}: a \"!\" line takes \"-\" as its licence")
        elif not SPDX_EXPRESSION.fullmatch(licence):
            raise SetupError(f"{where}: the licence isn't an SPDX ID or expression")
        try:
            datetime.date.fromisoformat(date)
        except ValueError:
            raise SetupError(f"{where}: the date isn't YYYY-MM-DD") from None
        key = (kind, pattern)
        if key in seen:
            raise SetupError(f"{where}: the same pattern is listed twice")
        seen.add(key)
        entries.append(Entry(number, kind, pattern))
    if not any(entry.kind == "library" for entry in entries):
        raise SetupError(f"{source} lists no approved libraries")
    return entries


def parse_catalog(text: str, source: str = CATALOG_FILE) -> tuple[list[tuple[str, str]], list[tuple[str, str]]]:
    """Return ([(alias, group:artifact)], [(alias, plugin id)]) from a Gradle version catalog."""
    try:
        import tomllib
    except ImportError:
        raise SetupError("reading the version catalog needs Python 3.11 or later (tomllib)") from None
    try:
        catalog = tomllib.loads(text)
    except tomllib.TOMLDecodeError as error:
        raise SetupError(f"{source} isn't valid TOML ({error})") from None

    libraries = []
    for alias, value in catalog.get("libraries", {}).items():
        if isinstance(value, str):
            parts = value.split(":")
            module = ":".join(parts[:2]) if len(parts) in (2, 3) else None
        elif isinstance(value, dict) and isinstance(value.get("module"), str):
            module = value["module"]
        elif isinstance(value, dict) and isinstance(value.get("group"), str) and isinstance(value.get("name"), str):
            module = f"{value['group']}:{value['name']}"
        else:
            module = None
        if module is None or len(module.split(":")) != 2 or "*" in module:
            raise SetupError(f"{source}: library {alias!r} has no group:artifact this check can read")
        libraries.append((alias, module))

    plugins = []
    for alias, value in catalog.get("plugins", {}).items():
        if isinstance(value, str):
            plugin_id = value.split(":")[0]
        elif isinstance(value, dict) and isinstance(value.get("id"), str):
            plugin_id = value["id"]
        else:
            raise SetupError(f"{source}: plugin {alias!r} has no ID this check can read")
        plugins.append((alias, plugin_id))
    return libraries, plugins


def report_sections(text: str) -> dict[tuple[str, str], list[str]]:
    """Split Gradle's dependency report output into {(project, configuration): tree lines}.

    The output may hold several reports, one after another, each under its project's banner.
    """
    sections: dict[tuple[str, str], list[str]] = {}
    lines = text.splitlines()
    project, index = None, 0
    while index < len(lines):
        banner = PROJECT_BANNER.fullmatch(lines[index].strip())
        header = CONFIGURATION_HEADER.match(lines[index])
        index += 1
        if banner:
            project = banner.group(1)
        elif header and project is not None:
            key = (project, header.group(1))
            if key in sections:
                raise SetupError(f"the Gradle output has two reports for {project} {key[1]}")
            body = []
            while index < len(lines) and lines[index].strip() and not lines[index].startswith("No dependencies"):
                body.append(lines[index])
                index += 1
            sections[key] = body
    return sections


def parse_tree(lines: list[str], where: str) -> tuple[set[str], set[str]]:
    """Return (resolved group:artifact:version lines, direct group:artifact names) from one tree.

    Direct dependencies are the top level of the tree, plus the top level of each project the
    configuration depends on (its own direct dependencies end up on the classpath too).
    """
    resolved, direct = set(), set()
    project_depth = None              # depth of the project line whose children count as direct
    for line in lines:
        match = TREE_LINE.fullmatch(line)
        if not match:
            raise SetupError(f"the Gradle dependency report for {where} has a line this check can't read")
        depth = len(match.group(1)) // 5
        text_part, mark = match.group(2), None
        marked = TREE_MARK.fullmatch(text_part)
        if marked:
            text_part, mark = marked.group(1), marked.group(2)

        if project_depth is not None and depth <= project_depth:
            project_depth = None
        if text_part.startswith("project "):
            if depth == 0:
                project_depth = 0
            continue
        if mark == "c":
            continue                  # a constraint, not a dependency
        if mark == "n" or text_part.endswith(" FAILED"):
            raise SetupError(f"Gradle couldn't resolve a dependency in {where}: {text_part}")

        requested, arrow, selected = text_part.partition(" -> ")
        parts = requested.split(":")
        if len(parts) not in (2, 3):
            raise SetupError(f"the Gradle dependency report for {where} has a dependency this check can't read")
        group, artifact = parts[0], parts[1]
        version = selected if arrow else (parts[2] if len(parts) == 3 else "")
        if not version or not re.fullmatch(r"[A-Za-z0-9._+-]+", version):
            raise SetupError(f"no single resolved version for {group}:{artifact} in {where}")
        resolved.add(f"{group}:{artifact}:{version}")
        if depth == 0 or (project_depth == 0 and depth == 1):
            direct.add(f"{group}:{artifact}")
    if not resolved:
        raise SetupError(f"the Gradle dependency report lists no libraries for {where}")
    return resolved, direct


def parse_dependency_tree(text: str, project: str = RELEASE_PROJECT,
                          configuration: str = RELEASE_CONFIGURATION) -> tuple[set[str], set[str]]:
    """Return (resolved, direct) for one project's configuration, from Gradle's report output."""
    lines = report_sections(text).get((project, configuration))
    if lines is None:
        raise SetupError(f"the Gradle output has no dependency report for {project} {configuration}")
    return parse_tree(lines, f"{project} {configuration}")


def parse_classpaths(text: str) -> list[tuple[str, set[str], set[str]]]:
    """[(name, resolved, direct)] for every entry in CLASSPATHS, in order."""
    sections = report_sections(text)
    found = []
    for project, configuration, _ in CLASSPATHS:
        name = f"{project} {configuration}"
        if (project, configuration) not in sections:
            raise SetupError(f"the Gradle output has no dependency report for {name}")
        found.append((name, *parse_tree(sections[(project, configuration)], name)))
    return found


# ---------------------------------------------------------------------------------------------------
# Kotlin and Groovy source, as far as repository declarations go


def mask_comments_and_strings(text: str) -> tuple[str, list[tuple[int, int]]]:
    """Blank out comments and string contents, keeping offsets. Returns (masked text, string spans).

    Each string span is (start, end) of the string's contents in the original text.
    """
    masked = list(text)
    strings = []
    index, length = 0, len(text)
    while index < length:
        if text.startswith("//", index):
            end = text.find("\n", index)
            end = length if end < 0 else end
            masked[index:end] = " " * (end - index)
            index = end
        elif text.startswith("/*", index):
            end = text.find("*/", index + 2)
            end = length if end < 0 else end + 2
            masked[index:end] = [c if c == "\n" else " " for c in text[index:end]]
            index = end
        elif text.startswith('"""', index):
            end = text.find('"""', index + 3)
            end = length if end < 0 else end
            strings.append((index + 3, end))
            masked[index + 3:end] = [c if c == "\n" else " " for c in text[index + 3:end]]
            index = min(length, end + 3)
        elif text[index] in "\"'":
            quote, start = text[index], index + 1
            end = start
            while end < length and text[end] != quote and text[end] != "\n":
                end += 2 if text[end] == "\\" else 1
            end = min(end, length)
            strings.append((start, end))
            masked[start:end] = " " * (end - start)
            index = end + 1
        else:
            index += 1
    return "".join(masked), strings


def closing_bracket(masked: str, opening: int) -> int:
    pairs = {"(": ")", "{": "}"}
    stack = []
    for index in range(opening, len(masked)):
        char = masked[index]
        if char in pairs:
            stack.append(pairs[char])
        elif stack and char == stack[-1]:
            stack.pop()
            if not stack:
                return index
    return len(masked)


def repository_problems(text: str) -> list[tuple[int, str]]:
    """Return (line number, problem) for each repository a Gradle file declares that F-Droid won't take."""
    masked, strings = mask_comments_and_strings(text)

    def line_of(offset: int) -> int:
        return text.count("\n", 0, offset) + 1

    problems = []
    for match in re.finditer(r"\b(mavenLocal|jcenter)\s*\(", masked):
        problems.append((line_of(match.start()), f"{match.group(1)}() isn't an allowed repository"))
    for match in re.finditer(r"\b(flatDir|ivy)\s*[({]", masked):
        problems.append((line_of(match.start()), f"a {match.group(1)} repository isn't allowed"))
    for match in re.finditer(r"\bmaven\s*([({])", masked):
        start = match.start(1)
        end = closing_bracket(masked, start)
        urls = [text[s:e] for s, e in strings if start < s and e <= end and "://" in text[s:e]]
        if not urls:
            problems.append((line_of(match.start()), "a maven repository whose URL this check can't read"))
        for url in urls:
            if url.rstrip("/") not in ALLOWED_REPOSITORY_URLS:
                problems.append((line_of(match.start()), "a maven repository other than https://jitpack.io"))
    return problems


# "group:artifact", with an optional version, classifier and "@extension": Gradle's string notation.
# Any part may hold a $name or ${...} template, and a version may be a range; a "=" or "/" means it's
# something else, such as a compiler option.
_COORDINATE_PART = r"(?:[A-Za-z0-9_.-]|\$(?=[A-Za-z_])|\$\{[^}\n]*\})+"
COORDINATE_STRING = re.compile(_COORDINATE_PART + ":" + _COORDINATE_PART +
                               r"(?::[A-Za-z0-9_.+()\[\],${}-]*){0,2}(?:@[A-Za-z0-9]+)?")
# group = "..." and name = "..." arguments in one call (Kotlin or Groovy), or group: '...' and
# name: '...' on one line (Groovy without parentheses).
NAMED_IN_PARENTHESES = re.compile(r"\((?=[^()]*\bgroup\s*[=:]\s*[\"'])(?=[^()]*\bname\s*[=:]\s*[\"'])[^()]*\)")
NAMED_ON_ONE_LINE = re.compile(r"^(?=[^\n]*\bgroup\s*:\s*[\"'])(?=[^\n]*\bname\s*:\s*[\"'])[^\n]*", re.M)
CATALOG_NOTE = "declare it in android/gradle/libs.versions.toml and use its alias (LIC-3)"

# Where the string rule applies: a dependencies { } block, and the arguments and trailing lambda of a
# dependencies.name(...) call or the lambda of dependencies.name { }.
DEPENDENCY_REGION = re.compile(r"\bdependencies\s*(?:\.\s*[A-Za-z_]\w*\s*)?([({])")
TRAILING_LAMBDA = re.compile(r"\s*\{")
# There, a string may only be an argument of one of these calls (on nothing, or on dependencies), the
# configuration name that add() takes first, or the name in Kotlin's "configuration"(...). Catalog
# aliases need no strings, so every other string is reported, however the coordinate is put together.
STRING_CALLS = {"project", "exclude", "because"}
STRING_RULE = "in a dependencies block only project(), exclude(), because() and a configuration name take strings"
# Words that start a statement or a condition without naming a call.
NOT_CALLS = {"val", "var", "def", "if", "else", "for", "while", "when", "return", "in", "is", "as"}
# A Groovy call without parentheses: exclude group: '...', because '...', implementation '...'.
GROOVY_COMMAND = re.compile(r"\s*([A-Za-z_]\w*)[ \t]+(?=[\w\"'])")
INVOKED = re.compile(r"\s*\(")
FOLLOWED_BY_COMMA = re.compile(r"\s*,")
IDENTIFIER = re.compile(r"[A-Za-z_]\w*")
TEMPLATE = re.compile(r"(?<!\\)\$[{A-Za-z_]")


def string_literals(text: str, strings: list[tuple[int, int]]) -> dict[int, tuple[int, int, int]]:
    """The offset of each string's opening quote -> (contents start, contents end, offset after its
    closing quote), for mask_comments_and_strings()'s string spans."""
    literals = {}
    for start, end in strings:
        triple = text[start - 3:start] == '"""' and text.startswith('"""', end)
        literals[start - 3 if triple else start - 1] = (start, end, end + 3 if triple else end + 1)
    return literals


def dependency_regions(masked: str) -> list[tuple[int, int]]:
    """(opening, closing) bracket offsets of each region DEPENDENCY_REGION finds, leaving out regions
    that lie inside another one."""
    regions = []
    for match in DEPENDENCY_REGION.finditer(masked):
        opening = match.start(1)
        if regions and opening <= regions[-1][1]:
            continue
        end = closing_bracket(masked, opening)
        lambda_ = TRAILING_LAMBDA.match(masked, end + 1) if masked[opening] == "(" else None
        if lambda_:
            end = closing_bracket(masked, lambda_.end() - 1)
        regions.append((opening, min(end, len(masked) - 1)))
    return regions


def dependency_string_problems(text: str, masked: str, literals: dict[int, tuple[int, int, int]],
                               regions: list[tuple[int, int]]) -> list[tuple[int, str]]:
    """(line number, problem) for the strings in REGIONS that the string rule doesn't allow, one per call
    (or Groovy command) that holds them."""
    ends = {after: opening for opening, (_, _, after) in literals.items()}

    def contents(opening: int) -> str:
        first, last, _ = literals[opening]
        return text[first:last]

    def word_before(at: int) -> int:
        """Where the run of word characters that ends at AT starts."""
        while at > 0 and (masked[at - 1].isalnum() or masked[at - 1] == "_"):
            at -= 1
        return at

    def space_before(at: int) -> int:
        while at > 0 and masked[at - 1].isspace():
            at -= 1
        return at

    def callee(paren: int) -> tuple[str, int, str | None] | None:
        """(name, where it starts, receiver) of the call whose arguments open at PAREN, or None. The
        receiver is None for a call on nothing, and "" when it isn't a plain name."""
        at = space_before(paren)
        if at in ends:                            # "configuration"(...): a name, never one of STRING_CALLS
            return contents(ends[at]), ends[at], ""
        if at > 0 and masked[at - 1] == "`":
            start = masked.rfind("`", 0, at - 1)
            if start < 0:
                return None
            name = masked[start + 1:at - 1]
        else:
            start = word_before(at)
            name = masked[start:at]
            if not IDENTIFIER.fullmatch(name):
                return None
        dot = space_before(start)
        if dot == 0 or masked[dot - 1] != ".":
            return name, start, None
        receiver_end = space_before(dot - 1 - (masked[dot - 2:dot - 1] == "?"))
        receiver = masked[word_before(receiver_end):receiver_end]
        return name, start, receiver if IDENTIFIER.fullmatch(receiver) else ""

    def command(brace: int, at: int) -> tuple[str, int, int] | None:
        """(name, where it starts, where it ends) of the Groovy command that the string at AT is an
        argument of, in the block that opens at BRACE, or None. Its statement starts after the last ;,
        { or }, or at the start of the first line of those that end in a comma before AT."""
        begin = max(masked.rfind(mark, brace, at) for mark in ";{}")
        start = at
        while True:
            line = masked.rfind("\n", begin + 1, start)
            if line < 0 or not masked[begin + 1:line].rstrip().endswith(","):
                start = begin + 1 if line < 0 else line + 1
                break
            start = line
        match = GROOVY_COMMAND.match(masked, start)
        if match and match.end() <= at and match.group(1) not in NOT_CALLS:
            return match.group(1), match.start(1), match.end(1)
        return None

    def allowed(at: int, stack: list[int]) -> bool:
        after = literals[at][2]
        if INVOKED.match(masked, after):          # the name in "configuration"(...)
            return True
        if not stack or masked[stack[-1]] == "[":
            return False
        if masked[stack[-1]] == "(":
            call = callee(stack[-1])
            if call is None or call[2] not in (None, "dependencies"):
                return False
            name, arguments = call[0], stack[-1] + 1
        else:
            call = command(stack[-1], at)
            if call is None:
                return False
            name, arguments = call[0], call[2]
        if name in STRING_CALLS:
            return True
        return name == "add" and not masked[arguments:at].strip() and bool(FOLLOWED_BY_COMMA.match(masked, after))

    def owner(at: int, stack: list[int]) -> tuple[int, str | None]:
        """(offset, name) of what a report on the string at AT names: the outermost call around it in
        its statement, else the Groovy command it belongs to. The name is None when there's neither."""
        named, brace = None, None
        for bracket in reversed(stack):
            if masked[bracket] == "{":
                brace = bracket
                break
            call = callee(bracket) if masked[bracket] == "(" else None
            if call and call[0] not in NOT_CALLS:
                named = call
        if named:
            return named[1], named[0]
        found = command(brace, at) if brace is not None else None
        return (found[1], found[0]) if found else (at, None)

    problems = []
    for opening, end in regions:
        counts, reported, stack, index = {}, {}, [], opening
        while index <= end:
            if index in literals:
                key = owner(index, stack)
                counts[key] = counts.get(key, 0) + 1
                if not allowed(index, stack):
                    reported.setdefault(key, []).append(index)
                index = literals[index][2]
                continue
            if masked[index] in "([{":
                stack.append(index)
            elif masked[index] in ")]}" and stack:
                stack.pop()
            index += 1
        for (offset, name), found in reported.items():
            what = "a string template" if counts[(offset, name)] == 1 and TEMPLATE.search(contents(found[0])) \
                else "strings"
            where = f" in {name}()" if name else ""
            problems.append((text.count("\n", 0, offset) + 1,
                             f"a dependency written as {what}{where}; {STRING_RULE}, so {CATALOG_NOTE}"))
    return problems


def declaration_problems(text: str) -> list[tuple[int, str]]:
    """Return (line number, problem) for each dependency or plugin a Gradle file declares without the
    version catalog. Comments are ignored."""
    masked, strings = mask_comments_and_strings(text)

    def line_of(offset: int) -> int:
        return text.count("\n", 0, offset) + 1

    def blocks(name: str):
        for match in re.finditer(r"\b" + name + r"\s*\{", masked):
            yield match.end() - 1, closing_bracket(masked, match.end() - 1)

    problems = []
    for start, end in strings:
        if COORDINATE_STRING.fullmatch(text[start:end]):
            problems.append((line_of(start), f"a dependency written as a string; {CATALOG_NOTE}"))
    for pattern in (NAMED_IN_PARENTHESES, NAMED_ON_ONE_LINE):
        for match in pattern.finditer(masked):
            problems.append((line_of(match.start()), f"a dependency written as group and name; {CATALOG_NOTE}"))
    for match in re.finditer(r"(?<![\w.])kotlin\s*\(", masked):
        problems.append((line_of(match.start()), f"kotlin(\"...\") names a Kotlin library or plugin outside "
                                                 f"the catalog; {CATALOG_NOTE}"))
    regions = dependency_regions(masked)
    for start, end in regions:
        for match in re.finditer(r"\b(files|fileTree)\b", masked[start:end]):
            problems.append((line_of(start + match.start()), f"a file dependency ({match.group(1)}); every "
                                                             f"dependency must come from a repository and the catalog"))
    # The string rule reports only lines that the more specific rules above leave alone.
    taken = {line for line, _ in problems}
    problems += [problem for problem in dependency_string_problems(text, masked, string_literals(text, strings), regions)
                 if problem[0] not in taken]
    for start, end in blocks("plugins"):
        for match in re.finditer(r"(?<![\w.])id\b\s*(\(?)\s*", masked[start:end]):
            at = start + match.end()
            string = next(((s, e) for s, e in strings if s == at + 1), None)
            if masked[at:at + 1] in "\"'" and string:
                plugin_id = text[string[0]:string[1]]
                if "." not in plugin_id:
                    continue          # one of Gradle's own core plugins, such as java-library
                problems.append((line_of(at), f"plugin {plugin_id} is applied by ID; declare it in "
                                              "android/gradle/libs.versions.toml and apply it with "
                                              "alias(libs.plugins...) (LIC-3)"))
            elif not (match.group(1) and masked.startswith("libs.", at)):
                problems.append((line_of(at), "a plugin ID this check can't read; apply plugins with "
                                              "alias(libs.plugins...) (LIC-3)"))
    return sorted(set(problems))      # a block inside another block is scanned twice


def gradle_files(root: str) -> list[str]:
    found = []
    for folder, dirs, files in os.walk(root):
        dirs[:] = sorted(d for d in dirs if d not in SKIPPED_DIRS)
        for name in sorted(files):
            if name.endswith(SCANNED_FILE_SUFFIXES):
                found.append(os.path.join(folder, name))
    return found


# ---------------------------------------------------------------------------------------------------
# Checks


def approved(name: str, entries: list[Entry], kind: str) -> bool:
    return any(entry.kind == kind and entry.matches(name) for entry in entries)


def denied_reason(name: str, entries: list[Entry]) -> str | None:
    group = name.split(":")[0]
    if any(group == prefix or group.startswith(prefix + ".") for prefix in NON_FREE_GROUPS):
        return "comes from Google Play services or Firebase, which F-Droid doesn't allow"
    for entry in entries:
        if entry.kind == "denied" and entry.matches(name):
            return f"is never allowed ({APPROVED_FILE} line {entry.line_number})"
    return None


def check_catalog(libraries, plugins, entries) -> list[str]:
    problems = []
    for alias, module in libraries:
        reason = denied_reason(module, entries)
        if reason:
            problems.append(f"{CATALOG_FILE}: library {alias} ({module}) {reason}")
        elif not approved(module, entries, "library"):
            problems.append(f"{CATALOG_FILE}: library {alias} ({module}) isn't on {APPROVED_FILE}. "
                            "A new dependency needs the owner's approval first, with its licence (LIC-3)")
    for alias, plugin_id in plugins:
        if NON_FREE_TEXT.search(plugin_id):
            problems.append(f"{CATALOG_FILE}: plugin {alias} ({plugin_id}) is a Google Play services or "
                            "Firebase plugin, which F-Droid doesn't allow")
        elif not approved(plugin_id, entries, "plugin"):
            problems.append(f"{CATALOG_FILE}: plugin {alias} ({plugin_id}) isn't on {APPROVED_FILE}. "
                            "A new plugin needs the owner's approval first, with its licence (LIC-3)")
    return problems


def check_resolved(resolved, direct, entries, where: str = f"{RELEASE_PROJECT} {RELEASE_CONFIGURATION}") -> list[str]:
    problems = []
    for name in sorted(direct):
        if denied_reason(name, entries) is None and not approved(name, entries, "library"):
            problems.append(f"{name} is a direct dependency in {where} but isn't on {APPROVED_FILE}. "
                            "A new dependency needs the owner's approval first, with its licence (LIC-3)")
    for line in sorted(resolved):
        name = ":".join(line.split(":")[:2])
        reason = denied_reason(name, entries)
        if reason:
            problems.append(f"{name}, resolved in {where}, {reason}")
    return problems


def check_gradle_files(root: str) -> list[str]:
    problems = []
    for path in gradle_files(root):
        relative = os.path.relpath(path, CHECKOUT).replace(os.sep, "/")
        try:
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
        except (OSError, UnicodeDecodeError) as error:
            raise SetupError(f"{relative} can't be read ({error.__class__.__name__})") from None
        for number, line in enumerate(text.splitlines(), 1):
            if NON_FREE_TEXT.search(line):
                problems.append(f"{relative} line {number}: names Google Play services or Firebase, "
                                "which F-Droid doesn't allow")
        if path.endswith(GRADLE_FILE_SUFFIXES):
            for number, problem in repository_problems(text):
                problems.append(f"{relative} line {number}: {problem}. F-Droid builds use google(), "
                                "mavenCentral(), gradlePluginPortal() and https://jitpack.io only")
            for number, problem in declaration_problems(text):
                problems.append(f"{relative} line {number}: {problem}")
    return problems


def resolved_file_text(classpaths: list[tuple[str, set[str], set[str]]]) -> str:
    """The text of RESOLVED_FILE for parse_classpaths()'s result."""
    sections, listed = [], set()
    for (name, resolved, _), (_, _, holds) in zip(classpaths, CLASSPATHS):
        new = sorted(resolved - listed, key=lambda line: line.split(":"))
        listed |= resolved
        sections.append(f"# {name}: {holds}.\n" + "".join(line + "\n" for line in new))
    return RESOLVED_HEADER + "".join("\n" + section for section in sections)


def gradle_output(source: str | None) -> str:
    if source == "-":
        return sys.stdin.read()
    if source is not None:
        try:
            with open(source, encoding="utf-8") as handle:
                return handle.read()
        except (OSError, UnicodeDecodeError) as error:
            raise SetupError(f"the saved Gradle output can't be read ({error.__class__.__name__})") from None
    output = []
    for command in gradle_commands():
        print(f"check_dependencies: running {' '.join(command)} in android/", file=sys.stderr, flush=True)
        try:
            result = subprocess.run(command, cwd=ANDROID_DIR, stdout=subprocess.PIPE, text=True)
        except OSError as error:
            raise SetupError(f"Gradle couldn't be started ({error.__class__.__name__})") from None
        if result.returncode != 0:
            raise SetupError(f"Gradle failed with exit status {result.returncode}")
        output.append(result.stdout)
    return "\n".join(output)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true",
                        help=f"fail instead of rewriting {RESOLVED_FILE} when it's out of date")
    parser.add_argument("--gradle-output", metavar="FILE",
                        help="a saved copy of the Gradle dependency reports, or - for standard input")
    args = parser.parse_args(argv)

    try:
        entries = parse_approved(read_text(APPROVED_FILE))
        libraries, plugins = parse_catalog(read_text(CATALOG_FILE))
        classpaths = parse_classpaths(gradle_output(args.gradle_output))
        problems = check_catalog(libraries, plugins, entries)
        for name, resolved, direct in classpaths:
            problems += check_resolved(resolved, direct, entries, name)
        problems += check_gradle_files(ANDROID_DIR)
    except SetupError as error:
        print(f"check_dependencies: {error}", file=sys.stderr)
        return 2

    expected = resolved_file_text(classpaths)
    resolved_path = os.path.join(CHECKOUT, RESOLVED_FILE)
    try:
        with open(resolved_path, encoding="utf-8") as handle:
            current = handle.read()
    except FileNotFoundError:
        current = None
    except (OSError, UnicodeDecodeError) as error:
        print(f"check_dependencies: {RESOLVED_FILE} can't be read ({error.__class__.__name__})",
              file=sys.stderr)
        return 2

    every_library = set().union(*(resolved for _, resolved, _ in classpaths))
    every_direct = set().union(*(direct for _, _, direct in classpaths))
    if current != expected:
        if args.check:
            diff = difflib.unified_diff((current or "").splitlines(keepends=True),
                                        expected.splitlines(keepends=True),
                                        f"{RESOLVED_FILE} (committed)", f"{RESOLVED_FILE} (resolved now)")
            sys.stderr.writelines(diff)
            problems.append(f"{RESOLVED_FILE} is out of date. Run `python3 -I tests/check_dependencies.py` "
                            "and commit the result, so the change shows in the pull request")
        else:
            with open(resolved_path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(expected)
            print(f"check_dependencies: wrote {RESOLVED_FILE} ({len(every_library)} libraries in "
                  f"{len(classpaths)} classpaths)")

    for problem in problems:
        print(f"check_dependencies: {problem}", file=sys.stderr)
    if problems:
        return 1
    print(f"check_dependencies: {len(libraries)} catalog libraries, {len(plugins)} plugins and "
          f"{len(every_direct)} direct dependencies approved; {len(every_library)} resolved libraries in "
          f"{len(classpaths)} classpaths, none from a disallowed group; Gradle files declare dependencies "
          "and plugins through the catalog and use allowed repositories only")
    return 0


if __name__ == "__main__":
    sys.exit(main())
