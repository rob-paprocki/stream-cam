package org.example.projectname.core.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class SettingsCodecTest {
    private val codec = SettingsCodec()

    private fun loaded(result: SettingsLoad): SettingsLoad.Loaded = when (result) {
        is SettingsLoad.Loaded -> result
        is SettingsLoad.Rejected -> fail("expected settings to load, got: ${result.reason}")
    }

    private fun rejected(result: SettingsLoad): String = when (result) {
        is SettingsLoad.Loaded -> fail("expected a refusal, got ${result.settings}")
        is SettingsLoad.Rejected -> result.reason
    }

    @Test
    fun defaultsFollowTheSpecSettingsTable() {
        val s = AppSettings()
        assertEquals(OutputResolution.SOURCE, s.video.resolution)
        assertEquals(1080, OutputResolution.SOURCE_MAX_HEIGHT)
        assertEquals(FrameRate.SOURCE, s.video.frameRate)
        assertEquals(30, FrameRate.SOURCE_MAX_FPS)
        assertEquals(VideoCodec.H264, s.video.codec)
        assertEquals(6_000, s.video.bitrateKbps)
        assertEquals(2, s.video.keyframeIntervalSeconds)
        assertEquals(AudioSource.AUTO, s.audio.source)
        assertEquals(128, s.audio.bitrateKbps)
        assertEquals(48_000, s.audio.sampleRateHz)
        assertEquals(AudioChannels.STEREO, s.audio.channels)
        assertEquals(0, s.audio.offsetMs)
        assertEquals(30, s.recording.segmentMinutes)
        assertEquals(null, s.recording.saveFolderUri)
        assertEquals(true, s.display.keepScreenOn)
        assertEquals(false, s.display.dimPreviewWhileLive)
        assertEquals(OrientationLock.LANDSCAPE, s.display.orientationLock)
        assertEquals(emptyList(), SettingsValidator.validate(s))
    }

    @Test
    fun SRC8_automaticAudioUsesUsbAudioForUsbSources() {
        assertEquals(AudioSource.USB_AUDIO, AudioSource.AUTO.resolve(videoSourceIsUsb = true))
        assertEquals(AudioSource.BUILT_IN_MIC, AudioSource.AUTO.resolve(videoSourceIsUsb = false))
        assertEquals(AudioSource.NONE, AudioSource.NONE.resolve(videoSourceIsUsb = true))
    }

    @Test
    fun encodedSettingsCarryTheVersionAndEveryGroup() {
        val document = Json.parseToJsonElement(codec.encode(AppSettings())).jsonObject
        assertEquals(SETTINGS_VERSION, document.getValue("version").jsonPrimitive.int)
        assertEquals(listOf("version", "video", "audio", "recording", "display"), document.keys.toList())
        assertEquals(JsonPrimitive("source"), document.getValue("video").jsonObject["resolution"])
        assertEquals(JsonPrimitive("builtInMic"), Json.parseToJsonElement(
            codec.encode(AppSettings(audio = AudioSettings(source = AudioSource.BUILT_IN_MIC))),
        ).jsonObject.getValue("audio").jsonObject["source"])
    }

    @Test
    fun settingsSurviveEncodeAndDecode() {
        val custom = AppSettings(
            video = VideoSettings(OutputResolution.P720, FrameRate.FPS_60, VideoCodec.HEVC, 12_000, 1),
            audio = AudioSettings(AudioSource.NONE, 64, 48_000, AudioChannels.MONO, -120),
            recording = RecordingSettings(segmentMinutes = RecordingSettings.SEGMENTS_OFF, saveFolderUri = "content://example/tree/movies"),
            display = DisplaySettings(keepScreenOn = false, dimPreviewWhileLive = true, orientationLock = OrientationLock.PORTRAIT),
        )
        assertEquals(SettingsLoad.Loaded(custom, migratedFrom = null), codec.decode(codec.encode(custom)))
    }

    @Test
    fun missingFieldsTakeTheirDefaults() {
        val result = loaded(codec.decode("""{"version": 1, "video": {"bitrateKbps": 8000}}"""))
        assertEquals(AppSettings(video = VideoSettings(bitrateKbps = 8_000)), result.settings)
    }

    /**
     * A made-up history, to prove the framework: version 1 stored the bitrate in Mbps, version 2 renamed
     * the orientation setting, and version 3 is today's format.
     */
    private val history = SettingsMigrations(
        currentVersion = 3,
        steps = mapOf(
            1 to SettingsMigration { document ->
                val video = document.getValue("video").jsonObject
                val mbps = video.getValue("bitrateMbps").jsonPrimitive.int
                JsonObject(document + ("video" to JsonObject(video - "bitrateMbps" + ("bitrateKbps" to JsonPrimitive(mbps * 1000)))))
            },
            2 to SettingsMigration { document ->
                val display = document["display"]?.jsonObject ?: return@SettingsMigration document
                val renamed = display["orientation"]?.let { display - "orientation" + ("orientationLock" to it) } ?: display
                JsonObject(document + ("display" to JsonObject(renamed)))
            },
        ),
    )

    @Test
    fun anOlderSettingsDocumentMigratesToTheCurrentVersion() {
        val versionOne = """{"version": 1, "video": {"bitrateMbps": 8, "codec": "hevc"}, "display": {"orientation": "portrait"}}"""
        val result = loaded(SettingsCodec(history).decode(versionOne))
        assertEquals(1, result.migratedFrom)
        assertEquals(
            AppSettings(
                video = VideoSettings(codec = VideoCodec.HEVC, bitrateKbps = 8_000),
                display = DisplaySettings(orientationLock = OrientationLock.PORTRAIT),
            ),
            result.settings,
        )
        val versionTwo = """{"version": 2, "display": {"orientation": "auto"}}"""
        assertEquals(SettingsLoad.Loaded(AppSettings(display = DisplaySettings(orientationLock = OrientationLock.AUTO)), 2), SettingsCodec(history).decode(versionTwo))
    }

    @Test
    fun aCurrentDocumentLoadsWithoutMigrating() {
        val current = SettingsCodec(history)
        assertEquals(SettingsLoad.Loaded(AppSettings(), migratedFrom = null), current.decode(current.encode(AppSettings())))
    }

    @Test
    fun unknownFutureVersionsAreRejectedSafely() {
        val reason = rejected(codec.decode("""{"version": ${SETTINGS_VERSION + 1}, "video": {"brandNewSetting": true}}"""))
        assertTrue("newer version of the app" in reason, reason)
        assertTrue("Using the defaults" in reason, reason)
        assertIs<SettingsLoad.Rejected>(SettingsCodec(history).decode("""{"version": 4}"""))
        assertIs<SettingsLoad.Rejected>(codec.decode("""{"version": 2147483647}"""))
    }

    @Test
    fun badVersionsAreRejectedSafely() {
        assertEquals("The saved settings have no version number.", rejected(codec.decode("""{"video": {}}""")))
        assertEquals("The saved settings have a version that isn't a whole number.", rejected(codec.decode("""{"version": "1"}""")))
        assertEquals("The saved settings have a version that isn't a whole number.", rejected(codec.decode("""{"version": 1.5}""")))
        assertEquals("The saved settings have version 0, which doesn't exist.", rejected(codec.decode("""{"version": 0}""")))
    }

    @Test
    fun damagedFilesAreRejectedSafely() {
        assertEquals("The saved settings aren't valid JSON.", rejected(codec.decode("{\"version\": 1, ")))
        assertEquals("The saved settings aren't a settings file.", rejected(codec.decode("[]")))
        assertEquals("Some saved settings couldn't be read.", rejected(codec.decode("""{"version": 1, "video": {"codec": "av1"}}""")))
        assertEquals("Some saved settings couldn't be read.", rejected(codec.decode("""{"version": 1, "extra": 1}""")))
        assertEquals("Video bitrate must be from 1 to 20 Mbps.", rejected(codec.decode("""{"version": 1, "video": {"bitrateKbps": 50000}}""")))
        assertEquals(
            "Segment length must be off, 5, 15, 30 or 60 minutes.",
            rejected(codec.decode("""{"version": 1, "recording": {"segmentMinutes": 7}}""")),
        )
    }

    @Test
    fun aFailingMigrationStepIsRejectedSafely() {
        val broken = SettingsMigrations(2, mapOf(1 to SettingsMigration { error("bug in step") }))
        assertEquals("The saved settings couldn't be upgraded from version 1.", rejected(SettingsCodec(broken).decode("""{"version": 1}""")))
    }

    @Test
    fun aRegistryWithAMissingStepIsRefused() {
        assertFailsWith<IllegalArgumentException> { SettingsMigrations(3, mapOf(1 to SettingsMigration { it })) }
        assertFailsWith<IllegalArgumentException> { SettingsMigrations(2, mapOf(2 to SettingsMigration { it })) }
        assertFailsWith<IllegalArgumentException> { SettingsMigrations(0, emptyMap()) }
    }

    @Test
    fun theShippedRegistryReachesTheCurrentVersion() {
        assertEquals(SETTINGS_VERSION, SettingsMigrations.CURRENT.currentVersion)
    }
}
