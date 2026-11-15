package org.example.projectname.core.recording

import java.time.LocalDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingNamesTest {

    @Test
    fun REC4_fileNameIsPrefixDateAndTimeDotMp4() {
        assertEquals("PROJECT_NAME_20261114_101530.mp4", RecordingNames.fileName(LocalDateTime.of(2026, 11, 14, 10, 15, 30)))
    }

    @Test
    fun REC4_everyPartIsZeroPadded() {
        assertEquals("PROJECT_NAME_20270102_030405.mp4", RecordingNames.fileName(LocalDateTime.of(2027, 1, 2, 3, 4, 5)))
        assertEquals("PROJECT_NAME_20261231_235959.mp4", RecordingNames.fileName(LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000)))
    }

    @Test
    fun REC4_digitsStayAsciiWhateverTheLanguage() {
        val before = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "hi-IN-u-nu-deva", "th-TH-u-nu-thai")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals("PROJECT_NAME_20261114_101530.mp4", RecordingNames.fileName(LocalDateTime.of(2026, 11, 14, 10, 15, 30)), tag)
            }
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun REC4_prefixIsThePlaceholderName() {
        assertEquals("PROJECT_NAME", APP_FILE_PREFIX)
    }
}
