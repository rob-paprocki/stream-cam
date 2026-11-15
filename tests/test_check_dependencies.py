"""Tests for check_dependencies.py (spec LIC-3). Standard library only.

The tests that read a version catalog need tomllib (Python 3.11 or later) and are skipped on older
versions; CI runs them on Python 3.13.
"""

import io
import os
import sys
import tempfile
import textwrap
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_dependencies as check  # noqa: E402

HAS_TOMLLIB = sys.version_info >= (3, 11)
needs_tomllib = unittest.skipUnless(HAS_TOMLLIB, "reading a version catalog needs Python 3.11 or later")

APPROVED = textwrap.dedent("""\
    # comment
    androidx.*:*                     | Apache-2.0 | 2026-11-14 | AndroidX
    org.jetbrains.kotlin:kotlin-stdlib* | Apache-2.0 | 2026-11-14 | Kotlin
    com.example.lib:core             | MIT OR Apache-2.0 | 2026-11-14 | an example library
    plugin:com.android.application   | Apache-2.0 | 2026-11-14 | AGP
    plugin:org.jetbrains.kotlin.*    | Apache-2.0 | 2026-11-14 | Kotlin plugins
    !org.bouncycastle:*              | -          | 2026-11-14 | never
    junit:junit                      | EPL-1.0    | 2026-11-14 | JUnit 4, tests only
    org.jetbrains.kotlin:kotlin-test* | Apache-2.0 | 2026-11-14 | kotlin-test
    ci:jsonschema                    | MIT        | 2026-11-14 | the schema check, CI only
""")

CATALOG = textwrap.dedent("""\
    [versions]
    agp = "9.5.1"
    [libraries]
    core-ktx = { module = "androidx.core:core-ktx", version = "1.0" }
    example = { group = "com.example.lib", name = "core", version.ref = "agp" }
    stdlib = "org.jetbrains.kotlin:kotlin-stdlib:2.4.21"
    [plugins]
    android-application = { id = "com.android.application", version.ref = "agp" }
    kotlin-jvm = "org.jetbrains.kotlin.jvm:2.4.21"
""")

TREE = textwrap.dedent("""\

    ------------------------------------------------------------
    Project ':app'
    ------------------------------------------------------------

    fossReleaseRuntimeClasspath - Runtime classpath of '/fossRelease'.
    +--- org.jetbrains.kotlin:kotlin-stdlib:2.4.21
    |    \\--- org.jetbrains:annotations:13.0 -> 23.0.0
    +--- project ':core'
    |    +--- org.jetbrains.kotlin:kotlin-stdlib:2.4.21 (*)
    |    \\--- com.example.lib:core:1.2.0
    |         +--- com.example.lib:core-bom:1.2.0
    |         |    \\--- com.example.lib:core:1.2.0 (c)
    |         \\--- com.example.transitive:helper:0.9
    +--- androidx.core:core-ktx:1.19.1
    |    \\--- androidx.annotation:annotation:1.9.1 -> 1.10.0 (*)
    \\--- androidx.compose.ui:ui -> 1.12.0
         \\--- androidx.compose.ui:ui-android:1.12.0

    (c) - A dependency constraint, not a dependency.
    (*) - Indicates repeated occurrences of a transitive dependency subtree.
""")

RESOLVED = {
    "org.jetbrains.kotlin:kotlin-stdlib:2.4.21", "org.jetbrains:annotations:23.0.0",
    "com.example.lib:core:1.2.0", "com.example.lib:core-bom:1.2.0", "com.example.transitive:helper:0.9",
    "androidx.core:core-ktx:1.19.1", "androidx.annotation:annotation:1.10.0",
    "androidx.compose.ui:ui:1.12.0", "androidx.compose.ui:ui-android:1.12.0",
}
DIRECT = {"org.jetbrains.kotlin:kotlin-stdlib", "com.example.lib:core", "androidx.core:core-ktx",
          "androidx.compose.ui:ui"}

# The other three classpaths, as Gradle prints them in separate runs.
DEBUG_TREE = textwrap.dedent("""\

    ------------------------------------------------------------
    Project ':app'
    ------------------------------------------------------------

    fossDebugRuntimeClasspath - Runtime classpath of '/fossDebug'.
    +--- org.jetbrains.kotlin:kotlin-stdlib:2.4.21
    +--- androidx.compose.ui:ui-tooling -> 1.12.0
    |    \\--- androidx.compose.ui:ui-android:1.12.0
    \\--- androidx.core:core-ktx:1.19.1
""")

APP_TEST_TREE = textwrap.dedent("""\

    ------------------------------------------------------------
    Project ':app'
    ------------------------------------------------------------

    fossDebugUnitTestRuntimeClasspath - Runtime classpath of '/fossDebugUnitTest'.
    +--- project ':app' (*)
    +--- junit:junit:4.13.2
    |    \\--- org.hamcrest:hamcrest-core:1.3
    \\--- androidx.core:core-ktx:1.19.1 (*)

    A web-based, searchable dependency report is available by adding the --scan option.
""")

CORE_TEST_TREE = textwrap.dedent("""\

    ------------------------------------------------------------
    Project ':core'
    ------------------------------------------------------------

    testRuntimeClasspath - Runtime classpath of 'test'.
    +--- org.jetbrains.kotlin:kotlin-stdlib:2.4.21
    \\--- org.jetbrains.kotlin:kotlin-test:2.4.21
         \\--- junit:junit:4.13.2
              \\--- org.hamcrest:hamcrest-core:1.3
""")

# What the four Gradle runs print, one after another, the way main() reads them.
ALL_TREES = TREE + CORE_TEST_TREE + DEBUG_TREE + APP_TEST_TREE

SETTINGS = textwrap.dedent("""\
    pluginManagement {
        repositories {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
    dependencyResolutionManagement {
        repositories {
            google()
            mavenCentral()
            // A comment with maven("https://maven.example.com") and mavenLocal() in it.
            maven("https://jitpack.io") {
                content { includeGroupByRegex("com\\\\.github\\\\.example.*") }
            }
        }
    }
""")


def entries():
    return check.parse_approved(APPROVED)


class ParseApproved(unittest.TestCase):
    def test_reads_libraries_plugins_and_denied_entries(self):
        kinds = [(entry.kind, entry.pattern, entry.line_number) for entry in entries()]
        self.assertIn(("library", "androidx.*:*", 2), kinds)
        self.assertIn(("plugin", "com.android.application", 5), kinds)
        self.assertIn(("denied", "org.bouncycastle:*", 7), kinds)
        self.assertIn(("ci", "jsonschema", 10), kinds)

    def test_a_ci_tool_never_approves_a_library_or_plugin(self):
        tool = [entry for entry in entries() if entry.kind == "ci"]
        self.assertFalse(check.approved("ci:jsonschema", tool, "library"))
        self.assertFalse(check.approved("jsonschema", tool, "plugin"))

    def test_bad_lines_are_setup_errors_naming_the_line(self):
        bad = {
            "a:b | Apache-2.0 | 2026-11-14": "four columns",
            "a | Apache-2.0 | 2026-11-14 | x": "group:artifact",
            "a:b:c | Apache-2.0 | 2026-11-14 | x": "group:artifact",
            "a:b | Apache 2.0 | 2026-11-14 | x": "SPDX",
            "a:b | Apache-2.0 | 14.11.2026 | x": "YYYY-MM-DD",
            "!a:b | MIT | 2026-11-14 | x": '"-"',
            "plugin:a b | MIT | 2026-11-14 | x": "plugin ID",
            "ci:a:b | MIT | 2026-11-14 | x": "Python package",
            "a:b | MIT | 2026-11-14 | |": "four columns",
        }
        for line, reason in bad.items():
            with self.subTest(line=line), self.assertRaises(check.SetupError) as caught:
                check.parse_approved("x:y | MIT | 2026-11-14 | ok\n" + line + "\n")
            self.assertIn("line 2", str(caught.exception))
            self.assertIn(reason, str(caught.exception))

    def test_a_pattern_listed_twice_is_refused(self):
        with self.assertRaises(check.SetupError):
            check.parse_approved("a:b | MIT | 2026-11-14 | x\na:b | MIT | 2026-11-14 | again\n")

    def test_a_list_without_libraries_is_refused(self):
        with self.assertRaises(check.SetupError):
            check.parse_approved("# nothing\nplugin:x | MIT | 2026-11-14 | only a plugin\n")


class Matching(unittest.TestCase):
    def test_a_star_stays_inside_the_group_or_the_artifact(self):
        androidx = check.Entry(1, "library", "androidx.*:*")
        self.assertTrue(androidx.matches("androidx.compose.ui:ui"))
        self.assertFalse(androidx.matches("androidxevil:ui"))
        exact = check.Entry(1, "library", "com.example:*")
        self.assertTrue(exact.matches("com.example:anything"))
        self.assertFalse(exact.matches("com.example.other:thing"))

    def test_plugin_patterns_match_whole_ids(self):
        kotlin = check.Entry(1, "plugin", "org.jetbrains.kotlin.*")
        self.assertTrue(kotlin.matches("org.jetbrains.kotlin.plugin.compose"))
        self.assertFalse(kotlin.matches("org.jetbrains.kotlinx.kover"))

    def test_play_services_and_firebase_are_denied_by_group(self):
        for name in ("com.google.android.gms:play-services-base", "com.google.firebase:firebase-bom",
                     "com.google.firebase.crashlytics:x", "com.google.android.play:core"):
            with self.subTest(name=name):
                self.assertIn("F-Droid", check.denied_reason(name, entries()))
        self.assertIsNone(check.denied_reason("com.google.guava:listenablefuture", entries()))
        self.assertIn("line 7", check.denied_reason("org.bouncycastle:bcprov-jdk18on", entries()))


@needs_tomllib
class ParseCatalog(unittest.TestCase):
    def test_reads_every_library_and_plugin_form(self):
        libraries, plugins = check.parse_catalog(CATALOG)
        self.assertEqual(libraries, [("core-ktx", "androidx.core:core-ktx"), ("example", "com.example.lib:core"),
                                     ("stdlib", "org.jetbrains.kotlin:kotlin-stdlib")])
        self.assertEqual(plugins, [("android-application", "com.android.application"),
                                   ("kotlin-jvm", "org.jetbrains.kotlin.jvm")])

    def test_an_unreadable_entry_or_file_is_a_setup_error(self):
        for text in ('[libraries]\nx = { version = "1" }\n', '[libraries]\nx = "nogroup"\n',
                     '[plugins]\nx = { version = "1" }\n', "[libraries\n"):
            with self.subTest(text=text), self.assertRaises(check.SetupError):
                check.parse_catalog(text)

    def test_unapproved_and_denied_catalog_entries_fail(self):
        libraries, plugins = check.parse_catalog(CATALOG)
        libraries.append(("new-thing", "com.example.new:thing"))
        libraries.append(("firebase", "com.google.firebase:firebase-analytics"))
        plugins.append(("google-services", "com.google.gms.google-services"))
        plugins.append(("other", "com.example.plugin"))
        problems = check.check_catalog(libraries, plugins, entries())
        self.assertEqual(len(problems), 4, problems)
        self.assertIn("new-thing (com.example.new:thing) isn't on config/approved-dependencies.txt", problems[0])
        self.assertIn("F-Droid", problems[1])
        self.assertIn("F-Droid", problems[2])
        self.assertIn("other (com.example.plugin) isn't on", problems[3])


class ParseDependencyTree(unittest.TestCase):
    def test_reads_selected_versions_and_skips_constraints_and_projects(self):
        resolved, direct = check.parse_dependency_tree(TREE)
        self.assertEqual(resolved, RESOLVED)

    def test_direct_dependencies_include_those_of_a_project_dependency(self):
        _, direct = check.parse_dependency_tree(TREE)
        self.assertEqual(direct, DIRECT)

    def test_an_unresolved_dependency_is_a_setup_error(self):
        broken = TREE.replace("\\--- com.example.transitive:helper:0.9",
                              "\\--- com.example.transitive:helper:0.9 FAILED")
        with self.assertRaises(check.SetupError):
            check.parse_dependency_tree(broken)

    def test_a_report_for_another_configuration_is_refused(self):
        with self.assertRaises(check.SetupError):
            check.parse_dependency_tree(TREE.replace("fossReleaseRuntimeClasspath", "fossDebugRuntimeClasspath"))

    def test_a_report_for_another_project_is_refused(self):
        with self.assertRaises(check.SetupError):
            check.parse_dependency_tree(TREE.replace("Project ':app'", "Project ':core'"))

    def test_an_empty_tree_is_refused(self):
        for empty in ("fossReleaseRuntimeClasspath - Runtime classpath of '/fossRelease'.\nNo dependencies\n",
                      "Project ':app'\n\nfossReleaseRuntimeClasspath - Runtime classpath.\nNo dependencies\n"):
            with self.subTest(empty=empty), self.assertRaises(check.SetupError):
                check.parse_dependency_tree(empty)

    def test_a_version_range_without_a_selected_version_is_refused(self):
        ranged = TREE.replace("com.example.transitive:helper:0.9", "com.example.transitive:helper:{strictly 0.9}")
        with self.assertRaises(check.SetupError):
            check.parse_dependency_tree(ranged)

    def test_unapproved_direct_and_denied_transitive_dependencies_fail(self):
        problems = check.check_resolved(RESOLVED | {"org.bouncycastle:bcprov-jdk18on:1.80"},
                                        DIRECT | {"com.example.unapproved:lib"}, entries())
        self.assertEqual(len(problems), 2, problems)
        self.assertIn("com.example.unapproved:lib is a direct dependency", problems[0])
        self.assertIn("org.bouncycastle:bcprov-jdk18on", problems[1])

    def test_unlisted_transitive_dependencies_are_accepted(self):
        self.assertEqual(check.check_resolved(RESOLVED, DIRECT, entries()), [])


class Classpaths(unittest.TestCase):
    def test_LIC3_one_gradle_run_per_configuration_of_a_project(self):
        commands = check.gradle_commands()
        asked = [(command[i - 2].rsplit(":", 1)[0] or ":", command[i])
                 for command in commands for i in range(len(command)) if command[i - 1] == "--configuration"]
        self.assertEqual(sorted(asked), sorted((project, name) for project, name, _ in check.CLASSPATHS))
        for command in commands:
            projects = [part for part in command if part.endswith(":dependencies")]
            self.assertEqual(len(projects), len(set(projects)), command)

    def test_LIC3_reads_every_classpath_from_one_output_in_any_order(self):
        found = check.parse_classpaths(ALL_TREES)
        self.assertEqual([name for name, _, _ in found],
                         [f"{project} {name}" for project, name, _ in check.CLASSPATHS])
        self.assertEqual(found[0][1:], (RESOLVED, DIRECT))
        self.assertEqual(found[2][2], {"junit:junit", "androidx.core:core-ktx"})
        self.assertEqual(found[3][2], {"org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains.kotlin:kotlin-test"})

    def test_a_missing_or_repeated_report_is_a_setup_error(self):
        for text in (TREE + DEBUG_TREE + APP_TEST_TREE, ALL_TREES + DEBUG_TREE):
            with self.subTest(length=len(text)), self.assertRaises(check.SetupError):
                check.parse_classpaths(text)

    def test_LIC3_each_section_lists_only_what_the_sections_above_it_dont(self):
        text = check.resolved_file_text(check.parse_classpaths(ALL_TREES))
        sections = [part.splitlines() for part in text.split("\n\n")[1:]]
        self.assertEqual([lines[0] for lines in sections],
                         [f"# {project} {name}: {holds}." for project, name, holds in check.CLASSPATHS])
        self.assertEqual(sections[0][1:], sorted(RESOLVED, key=lambda line: line.split(":")))
        self.assertEqual(sections[1][1:], ["androidx.compose.ui:ui-tooling:1.12.0"])
        self.assertEqual(sections[2][1:], ["junit:junit:4.13.2", "org.hamcrest:hamcrest-core:1.3"])
        self.assertEqual(sections[3][1:], ["org.jetbrains.kotlin:kotlin-test:2.4.21"])

    def test_LIC3_a_new_version_in_a_later_classpath_is_listed_there(self):
        found = check.parse_classpaths(ALL_TREES.replace("|    \\--- androidx.compose.ui:ui-android:1.12.0",
                                                         "|    \\--- androidx.compose.ui:ui-android:1.13.0"))
        text = check.resolved_file_text(found)
        debug = text.split("\n\n")[2].splitlines()
        self.assertIn("androidx.compose.ui:ui-android:1.13.0", debug)

    def test_LIC3_an_unapproved_direct_test_dependency_fails(self):
        found = check.parse_classpaths(ALL_TREES.replace("+--- junit:junit:4.13.2\n",
                                                         "+--- com.example.testlib:mock:1.0\n"))
        problems = [problem for name, resolved, direct in found
                    for problem in check.check_resolved(resolved, direct, entries(), name)]
        self.assertEqual(len(problems), 1, problems)
        self.assertIn("com.example.testlib:mock is a direct dependency in :app fossDebugUnitTestRuntimeClasspath",
                      problems[0])


class Declarations(unittest.TestCase):
    """Every dependency and plugin goes through the version catalog (LIC-3)."""

    def problems(self, text):
        return check.declaration_problems(textwrap.dedent(text))

    def test_LIC3_catalog_aliases_and_project_dependencies_pass(self):
        build = """\
            plugins {
                alias(libs.plugins.android.application)
                alias(libs.plugins.kotlin.compose) apply false
                id("maven-publish")
                id 'java-library'
                `java-test-fixtures`
                id(libs.plugins.kotlin.jvm.get().pluginId)
            }
            dependencies {
                implementation(project(":core"))
                implementation(platform(libs.compose.bom))
                debugImplementation(libs.compose.ui.tooling)
                implementation(libs.rootencoder.library) {
                    exclude(group = "com.github.example.RootEncoder", module = "whip")
                }
                // implementation("com.example:commented-out:1.0")
            }
            kotlin { compilerOptions { freeCompilerArgs.add("-P") } }
            android {
                namespace = "org.example.projectname"
                defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
            }
            val option = "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=/tmp/x"
            tasks.register("hello") { group = "help"; description = "see https://example.com:8443/x" }
        """
        self.assertEqual(self.problems(build), [])
        self.assertEqual(self.problems(SETTINGS), [])

    def test_LIC3_string_coordinates_fail_with_their_line(self):
        cases = [
            'implementation("com.example:lib:1.0")',
            "testImplementation 'com.example:lib:1.0'",
            'debugImplementation("com.example:lib")',
            'implementation(platform("com.example:bom:1.0"))',
            'kapt("com.example:processor:1.0@aar")',
            'implementation("com.example:lib:${version}")',
            'add("implementation", "com.example:lib:[1.0,2.0)")',
        ]
        for line in cases:
            with self.subTest(line=line):
                problems = self.problems("dependencies {\n    %s\n}\n" % line)
                self.assertEqual([number for number, _ in problems], [2], problems)
                self.assertIn("written as a string", problems[0][1])
        buildscript = 'buildscript {\n    dependencies {\n        classpath("com.example:plugin:1.0")\n    }\n}\n'
        self.assertEqual([number for number, _ in self.problems(buildscript)], [3])

    def test_LIC3_group_and_name_arguments_fail(self):
        for line in ('implementation(group = "com.example", name = "lib", version = "1.0")',
                     "implementation group: 'com.example', name: 'lib', version: '1.0'",
                     "implementation(name: 'lib', group: 'com.example')"):
            with self.subTest(line=line):
                problems = self.problems("dependencies {\n    %s\n}\n" % line)
                self.assertTrue(problems)
                self.assertTrue(all("group and name" in problem for _, problem in problems), problems)
                self.assertEqual(problems[0][0], 2)

    def test_LIC3_group_and_name_as_positional_strings_fail(self):
        cases = [
            'implementation("com.example", "lib", "1.0")',
            'androidTestImplementation("com.example", "lib")',
            'ksp("com.example", "processor", "1.0")',
            'lintChecks( "com.example" , "checks" )',
            'implementation("com.example", name = "lib")',
            '`fossImplementation`("com.example", "lib")',
            '"implementation"("com.example", "lib", "1.0")',
            'implementation("com.example", "lib") { because("x") }',
            'add("implementation", "${coordinates}")',
            "implementation 'com.example', 'lib'",
            'implementation(\n        "com.example",\n        "lib",\n    )',
            'implementation("com.example" + ":lib:1.0")',
            'ksp("com.example:" + name)',
        ]
        for line in cases:
            with self.subTest(line=line):
                problems = self.problems("dependencies {\n    %s\n}\n" % line)
                self.assertEqual([number for number, _ in problems], [2], problems)
                self.assertIn("written as strings", problems[0][1])
        buildscript = 'buildscript {\n    dependencies {\n        classpath("com.example", "plugin", "1.0")\n    }\n}\n'
        self.assertEqual([number for number, _ in self.problems(buildscript)], [3])

    def test_LIC3_string_templates_in_coordinates_fail(self):
        cases = [
            'implementation("com.example:$name:1.0")',
            'implementation("$group:lib:1.0")',
            'implementation("${group}:${name}:${version}")',
            'implementation("com.example:lib-${variant}")',
            'implementation("$coordinates")',
            'testImplementation("${libs.versions.example.get()}")',
            'implementation "com.example:$name"',
        ]
        for line in cases:
            with self.subTest(line=line):
                problems = self.problems("dependencies {\n    %s\n}\n" % line)
                self.assertEqual([number for number, _ in problems], [2], problems)
                self.assertIn("written as a string", problems[0][1])

    def test_LIC3_other_calls_that_take_strings_pass(self):
        build = """\
            val withTooling = findProperty("withTooling") != null
            dependencies {
                implementation(project(":core"))
                implementation(project(":core", "default"))
                implementation(project(path = ":core", configuration = "default"))
                implementation(libs.rootencoder.library) {
                    exclude("com.example.group", "whip")
                    exclude(group = "com.example.group", module = "whip")
                    because("the $name module isn't used")
                }
                "fossImplementation"(libs.compose.ui)
                `fossImplementation`(libs.compose.ui)
                if (withTooling) debugImplementation(libs.compose.ui.tooling)
            }
            tasks.register("hello", Exec::class) { commandLine("echo", "hello") }
            val message = "${project.name} is version $version"
        """
        self.assertEqual(self.problems(build), [])

    def test_LIC3_the_allowed_string_forms_pass_in_kotlin_and_groovy(self):
        kotlin = """\
            dependencies {
                implementation(project(":core"))
                testImplementation(testFixtures(project(path = ":core")))
                implementation(libs.example) {
                    exclude(group = "com.example.group", module = "unused")
                    exclude("com.example.group", "unused")
                    because("the unused module isn't needed")
                    isTransitive = false
                }
                add("implementation", libs.example)
                add(
                    "testImplementation",
                    libs.example,
                )
                dependencies.add("implementation", libs.example) { because("it is used") }
                "fossImplementation"(libs.example)
                `fossImplementation`(libs.example)
            }
            dependencies.add("implementation", project(":core"))
            android { dependenciesInfo { includeInApk = false } }
            tasks.register("hello") { group = "help"; description = "not a dependency: com.example" }
        """
        groovy = """\
            dependencies {
                implementation project(':core')
                implementation project(path: ':core', configuration: 'default')
                implementation(libs.example) {
                    exclude group: 'com.example.group',
                            module: 'unused'
                    exclude(group: 'com.example.group', module: 'unused')
                    because 'not needed'
                    transitive = false
                }
                add 'implementation', libs.example
                'fossImplementation'(libs.example)
            }
        """
        for text in (kotlin, groovy):
            with self.subTest(text=text.split("\n")[2]):
                self.assertEqual(self.problems(text), [])

    def test_LIC3_any_other_string_in_a_dependencies_block_fails(self):
        # A coordinate pieced together from a variable and strings, or written in any notation that isn't
        # "group:artifact:version", fails like the string itself: a catalog alias needs no strings.
        cases = [
            'implementation(exampleGroup, "lib", "1.0")',
            'androidTestImplementation(exampleGroup, "lib")',
            'implementation(group = exampleGroup, name = "lib", version = "1.0")',
            'implementation(group = exampleGroup, name = exampleName, version = "1.0")',
            'implementation(mapOf("group" to "com.example", "name" to "lib", "version" to "1.0"))',
            'androidTestImplementation(dependencies.create("com.example", "lib", "1.0"))',
            'androidTestImplementation(create("com.example", "lib", "1.0"))',
            'implementation(String.format("%s:%s:%s", "com.example", "lib", "1.0"))',
            'implementation("com.example".plus(":lib:1.0"))',
            'implementation(exampleGroup + ":lib:1.0")',
            'implementation(listOf("com.example", "lib", "1.0").joinToString(":"))',
            'implementation(libs.example) { version { strictly("1.0") } }',
            'add("implementation", exampleGroup + ":lib:1.0")',
            'add(exampleConfiguration, "com.example", "lib")',
            'dependencies.add("implementation", exampleGroup.plus(":lib"))',
            '`implementation`(exampleGroup, "lib")',
            '"implementation"(exampleGroup, "lib")',
            'implementation(libs.example) { exclude(mapOf("group" to "com.example")) }',
            "implementation exampleGroup, 'lib'",
            "implementation([group: exampleGroup, name: 'lib'])",
            "implementation group: exampleGroup,\n        name: 'lib'",
            # Strings in conditions and assignments fail too; such code goes above the block.
            'if (findProperty("withExtras") != null) implementation(libs.example)',
            'exampleGroup = "com.example"',
            'val exampleGroup = "com.example"',
        ]
        for line in cases:
            with self.subTest(line=line):
                problems = self.problems("dependencies {\n    %s\n}\n" % line)
                self.assertEqual([number for number, _ in problems], [2], problems)
                self.assertIn("a dependency written as", problems[0][1])
                self.assertIn("only project(), exclude(), because() and a configuration name", problems[0][1])

    def test_LIC3_a_report_names_the_call_that_holds_the_strings(self):
        problems = self.problems('dependencies {\n    ksp(String.format("%s:%s", "com.example", "lib"))\n}\n')
        self.assertEqual(len(problems), 1, problems)
        self.assertIn("written as strings in ksp()", problems[0][1])

    def test_LIC3_strings_in_a_dependencies_call_outside_a_block_fail(self):
        cases = [
            'val probe = 1\ndependencies.add("androidTestImplementation", exampleGroup + ":lib:1.0")\n',
            'val probe = 1\nproject.dependencies.add("lintChecks", dependencies.create(exampleGroup, "lib"))\n',
            'val probe = 1\nval extra = dependencies.create(mapOf("group" to "com.example", "name" to "lib"))\n',
            'val probe = 1\ndependencies.constraints { add("implementation", exampleGroup + ":lib:1.0") }\n',
            'val probe = 1\ndependencies.add("implementation", libs.example) { version { strictly("1.0") } }\n',
        ]
        for text in cases:
            with self.subTest(text=text):
                problems = self.problems(text)
                self.assertEqual([number for number, _ in problems], [2], problems)
                self.assertIn("a dependency written as", problems[0][1])

    def test_LIC3_file_dependencies_and_kotlin_shorthands_fail(self):
        cases = {
            'dependencies {\n    implementation(files("libs/a.jar"))\n}\n': "file dependency",
            "dependencies {\n    implementation fileTree(dir: 'libs', include: ['*.jar'])\n}\n": "file dependency",
            'dependencies {\n    implementation(kotlin("reflect"))\n}\n': "kotlin(",
            'plugins {\n    kotlin("jvm") version "2.4.21"\n}\n': "kotlin(",
        }
        for text, reason in cases.items():
            with self.subTest(text=text):
                problems = self.problems(text)
                self.assertEqual(len(problems), 1, problems)
                self.assertEqual(problems[0][0], 2)
                self.assertIn(reason, problems[0][1])

    def test_LIC3_plugins_applied_by_id_fail(self):
        cases = {
            'plugins {\n    id("com.example.plugin") version "1.0"\n}\n': "com.example.plugin is applied by ID",
            "plugins {\n    id 'com.example.plugin' version '1.0'\n}\n": "com.example.plugin is applied by ID",
            'pluginManagement {\n    plugins {\n        id("com.example.plugin") version "1.0"\n    }\n}\n':
                "com.example.plugin is applied by ID",
            'plugins {\n    id(pluginName)\n}\n': "can't read",
        }
        for text, reason in cases.items():
            with self.subTest(text=text):
                problems = self.problems(text)
                self.assertEqual(len(problems), 1, problems)
                self.assertEqual(problems[0][0], text.count("\n", 0, text.index(" id")) + 1)
                self.assertIn(reason, problems[0][1])

    def test_the_repository_gradle_files_pass(self):
        for path in check.gradle_files(check.ANDROID_DIR):
            if path.endswith(check.GRADLE_FILE_SUFFIXES):
                with self.subTest(path=os.path.basename(path)), open(path, encoding="utf-8") as handle:
                    self.assertEqual(check.declaration_problems(handle.read()), [])


class Repositories(unittest.TestCase):
    def test_the_allowed_repositories_pass_and_comments_are_ignored(self):
        self.assertEqual(check.repository_problems(SETTINGS), [])

    def test_groovy_and_kotlin_forms_of_jitpack_pass(self):
        for text in ("repositories { maven { url 'https://jitpack.io' } }",
                     'repositories { maven { url = uri("https://jitpack.io/") } }',
                     'repositories { maven(url = "https://jitpack.io") }',
                     'plugins { id("maven-publish") }'):
            with self.subTest(text=text):
                self.assertEqual(check.repository_problems(text), [])

    def test_other_repositories_fail_with_their_line(self):
        cases = {
            'repositories {\n    maven("https://maven.example.com/releases")\n}': "other than",
            'repositories {\n    mavenLocal()\n}': "mavenLocal",
            'repositories {\n    jcenter()\n}': "jcenter",
            'repositories {\n    flatDir { dirs("libs") }\n}': "flatDir",
            'repositories {\n    ivy { url = uri("https://ivy.example.com") }\n}': "ivy",
            'repositories {\n    maven { url = uri(property("repoUrl")) }\n}': "can't read",
            'repositories {\n    maven("http://jitpack.io")\n}': "other than",
        }
        for text, reason in cases.items():
            with self.subTest(text=text):
                problems = check.repository_problems(text)
                self.assertTrue(problems, text)
                self.assertEqual(problems[0][0], 2)
                self.assertIn(reason, problems[0][1])

    def test_a_url_inside_a_string_is_not_read_as_a_comment(self):
        masked, strings = check.mask_comments_and_strings('maven("https://jitpack.io") // note\n')
        self.assertNotIn("note", masked)
        self.assertNotIn("jitpack", masked)
        self.assertEqual(len(strings), 1)


class Main(unittest.TestCase):
    """End to end, on a small copy of the repository layout."""

    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = folder.name
        self.write("config/approved-dependencies.txt", APPROVED)
        self.write("android/gradle/libs.versions.toml", CATALOG)
        self.write("android/settings.gradle.kts", SETTINGS)
        self.write("android/app/build.gradle.kts", 'dependencies {\n    implementation(libs.core.ktx)\n}\n')
        self.write("tree.txt", ALL_TREES)
        for name, value in (("CHECKOUT", self.root), ("ANDROID_DIR", os.path.join(self.root, "android"))):
            patcher = mock.patch.object(check, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def write(self, relative, text):
        path = os.path.join(self.root, relative)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)

    def read(self, relative):
        with open(os.path.join(self.root, relative), encoding="utf-8") as handle:
            return handle.read()

    def run_main(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            status = check.main(["--gradle-output", os.path.join(self.root, "tree.txt"), *args])
        return status, out.getvalue(), err.getvalue()

    @needs_tomllib
    def test_LIC3_writes_the_sorted_resolved_list_then_check_passes(self):
        status, out, err = self.run_main()
        self.assertEqual((status, err), (0, ""))
        text = self.read("config/resolved-dependencies.txt")
        release = text.split("\n\n")[1].splitlines()
        self.assertEqual(release[0], "# :app fossReleaseRuntimeClasspath: every library the release APK ships.")
        lines = release[1:]
        self.assertEqual(lines, sorted(RESOLVED, key=lambda line: line.split(":")))
        self.assertLess(lines.index("androidx.compose.ui:ui:1.12.0"), lines.index("androidx.compose.ui:ui-android:1.12.0"))
        self.assertIn("junit:junit:4.13.2", text)
        self.assertIn("org.jetbrains.kotlin:kotlin-test:2.4.21", text)
        self.assertEqual(self.run_main("--check")[0], 0)

    @needs_tomllib
    def test_LIC3_check_fails_when_the_committed_list_is_stale(self):
        self.run_main()
        self.write("tree.txt", ALL_TREES.replace("helper:0.9", "helper:1.0"))
        before = self.read("config/resolved-dependencies.txt")
        status, _, err = self.run_main("--check")
        self.assertEqual(status, 1)
        self.assertIn("+com.example.transitive:helper:1.0", err)
        self.assertIn("is out of date", err)
        self.assertEqual(self.read("config/resolved-dependencies.txt"), before)

    @needs_tomllib
    def test_LIC3_check_fails_when_a_test_library_changes(self):
        self.run_main()
        self.write("tree.txt", ALL_TREES.replace("hamcrest-core:1.3", "hamcrest-core:1.4"))
        status, _, err = self.run_main("--check")
        self.assertEqual(status, 1)
        self.assertIn("+org.hamcrest:hamcrest-core:1.4", err)

    @needs_tomllib
    def test_LIC3_a_catalog_library_without_approval_fails(self):
        self.write("android/gradle/libs.versions.toml",
                   CATALOG.replace("[plugins]", 'extra = { module = "com.example.extra:lib", version = "1" }\n[plugins]'))
        status, _, err = self.run_main()
        self.assertEqual(status, 1)
        self.assertIn("library extra (com.example.extra:lib) isn't on config/approved-dependencies.txt", err)

    @needs_tomllib
    def test_a_gradle_file_naming_play_services_or_another_repository_fails(self):
        self.write("android/app/build.gradle.kts",
                   'dependencies {\n    implementation("com.google.android.gms:play-services-base:18.5.0")\n}\n'
                   'repositories {\n    maven("https://maven.example.com/repo")\n}\n')
        status, _, err = self.run_main()
        self.assertEqual(status, 1)
        self.assertIn("android/app/build.gradle.kts line 2: names Google Play services or Firebase", err)
        self.assertIn("android/app/build.gradle.kts line 5: a maven repository other than https://jitpack.io", err)

    @needs_tomllib
    def test_LIC3_a_gradle_file_with_a_string_dependency_fails(self):
        self.write("android/core/build.gradle.kts",
                   'plugins {\n    id("com.example.plugin") version "1.0"\n}\n'
                   'dependencies {\n    testImplementation("com.example:testlib:1.0")\n}\n')
        status, _, err = self.run_main()
        self.assertEqual(status, 1)
        self.assertIn("android/core/build.gradle.kts line 2: plugin com.example.plugin is applied by ID", err)
        self.assertIn("android/core/build.gradle.kts line 5: a dependency written as a string", err)

    @needs_tomllib
    def test_LIC3_a_group_kept_in_a_variable_or_a_map_fails(self):
        # Gradle accepts both of these, and neither spells out "group:artifact:version".
        self.write("android/app/build.gradle.kts",
                   'val probeGroup = "com.example.probe"\n'
                   'dependencies {\n'
                   '    implementation(libs.core.ktx)\n'
                   '    androidTestImplementation(probeGroup, "lib", "1.0")\n'
                   '    androidTestImplementation(mapOf("group" to "com.example.probe", "name" to "other"))\n'
                   '}\n')
        status, _, err = self.run_main()
        self.assertEqual(status, 1)
        self.assertIn("android/app/build.gradle.kts line 4: a dependency written as strings in "
                      "androidTestImplementation()", err)
        self.assertIn("android/app/build.gradle.kts line 5: a dependency written as strings in "
                      "androidTestImplementation()", err)
        self.assertNotIn("line 1:", err)

    @needs_tomllib
    def test_build_output_folders_are_not_scanned(self):
        self.write("android/app/build/tmp/generated.gradle.kts", 'repositories { mavenLocal() }\n')
        self.assertEqual(self.run_main()[0], 0)

    def test_a_missing_approved_list_is_a_setup_error(self):
        os.remove(os.path.join(self.root, "config/approved-dependencies.txt"))
        status, _, err = self.run_main()
        self.assertEqual(status, 2)
        self.assertIn("config/approved-dependencies.txt can't be read", err)


@needs_tomllib
class TheRepositoryItself(unittest.TestCase):
    """The committed approved list and version catalog must agree."""

    def test_LIC3_every_catalog_library_and_plugin_is_approved(self):
        checkout = os.path.dirname(os.path.dirname(os.path.abspath(check.__file__)))
        with open(os.path.join(checkout, check.APPROVED_FILE), encoding="utf-8") as handle:
            approved = check.parse_approved(handle.read())
        with open(os.path.join(checkout, check.CATALOG_FILE), encoding="utf-8") as handle:
            libraries, plugins = check.parse_catalog(handle.read())
        self.assertEqual(check.check_catalog(libraries, plugins, approved), [])


if __name__ == "__main__":
    unittest.main()
