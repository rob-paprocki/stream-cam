package org.example.projectname.core.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The example files in shared/fixtures/ against the app's own decoder. tests/check_profile_schema.py
 * checks the same files against shared/profile.schema.json, so the two readers must agree.
 */
class ProfileFixturesTest {

    @Test
    fun OUT8_thereAreFixturesOfBothKinds() {
        assertTrue(SharedFiles.fixtures("valid").size >= 6, "expected at least one valid fixture per destination type")
        assertTrue(SharedFiles.fixtures("invalid").isNotEmpty(), "expected invalid fixtures")
    }

    @Test
    fun OUT8_everyValidFixtureImports() {
        val failures = SharedFiles.fixtures("valid").mapNotNull { file ->
            when (val result = ProfileCodec.import(file.readText())) {
                is ProfileImportResult.Imported -> null
                is ProfileImportResult.Rejected -> "${file.name}: ${result.summary}"
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun OUT8_everyDestinationTypeHasAValidFixture() {
        val types = SharedFiles.fixtures("valid").map { file ->
            val imported = assertIs<ProfileImportResult.Imported>(ProfileCodec.import(file.readText()), file.name)
            typeName(imported.profile.destination)
        }.toSet()
        assertEquals(destinationTypeNames, types)
    }

    @Test
    fun OUT8_everyInvalidFixtureIsRejectedWithAReason() {
        val accepted = SharedFiles.fixtures("invalid").mapNotNull { file ->
            when (val result = ProfileCodec.import(file.readText())) {
                is ProfileImportResult.Imported -> file.name
                is ProfileImportResult.Rejected -> {
                    assertTrue(result.errors.isNotEmpty() && result.errors.all { it.message.isNotBlank() }, file.name)
                    null
                }
            }
        }
        assertEquals(emptyList(), accepted, "these invalid fixtures were accepted")
    }

    @Test
    fun OUT8_validFixturesSurviveExportAndImportUnchanged() {
        for (file in SharedFiles.fixtures("valid")) {
            val imported = assertIs<ProfileImportResult.Imported>(ProfileCodec.import(file.readText()), file.name).profile
            val exported = when (val result = ProfileCodec.export(imported)) {
                is ProfileExportResult.Exported -> result.json
                is ProfileExportResult.Rejected -> fail("${file.name}: export refused: ${result.errors}")
            }
            assertEquals(imported, assertIs<ProfileImportResult.Imported>(ProfileCodec.import(exported), file.name).profile)
        }
    }
}
