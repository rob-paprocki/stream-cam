package org.example.projectname.capture

import org.example.projectname.core.settings.AppSettings
import org.example.projectname.core.settings.AudioChannels
import org.example.projectname.core.settings.FrameRate
import org.example.projectname.core.settings.OutputResolution
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureConfigTest {
    @Test
    fun defaultsGiveTheSpecsEncoderSettings() {
        assertEquals(
            CaptureConfig(
                width = 1920,
                height = 1080,
                fps = 30,
                videoBitrateBps = 6_000_000,
                keyframeIntervalSeconds = 2,
                sampleRateHz = 48_000,
                stereo = true,
                audioBitrateBps = 128_000,
            ),
            CaptureConfig.forTestPattern(),
        )
    }

    @Test
    fun OUT5_keyframeIntervalComesFromTheSettings() {
        val settings = AppSettings().let { it.copy(video = it.video.copy(keyframeIntervalSeconds = 1)) }
        assertEquals(1, CaptureConfig.forTestPattern(settings).keyframeIntervalSeconds)
    }

    @Test
    fun frameRateNeverGoesAboveThePatternsOwn() {
        val settings = AppSettings().let { it.copy(video = it.video.copy(frameRate = FrameRate.FPS_60)) }
        assertEquals(30, CaptureConfig.forTestPattern(settings).fps)
    }

    @Test
    fun sevenTwentyPAndMonoAreHonoured() {
        val base = AppSettings()
        val settings = base.copy(
            video = base.video.copy(resolution = OutputResolution.P720, bitrateKbps = 3_000),
            audio = base.audio.copy(channels = AudioChannels.MONO, bitrateKbps = 96),
        )
        val config = CaptureConfig.forTestPattern(settings)
        assertEquals(1280 to 720, config.width to config.height)
        assertEquals(3_000_000, config.videoBitrateBps)
        assertEquals(false, config.stereo)
        assertEquals(96_000, config.audioBitrateBps)
    }
}
