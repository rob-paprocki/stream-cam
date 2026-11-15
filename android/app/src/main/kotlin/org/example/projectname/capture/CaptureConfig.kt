package org.example.projectname.capture

import org.example.projectname.core.settings.AppSettings
import org.example.projectname.core.settings.AudioChannels
import org.example.projectname.core.settings.FrameRate
import org.example.projectname.core.settings.OutputResolution

/** What the encoders are set up with. Bitrates are in bits per second. */
data class CaptureConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateBps: Int,
    val keyframeIntervalSeconds: Int,
    val sampleRateHz: Int,
    val stereo: Boolean,
    val audioBitrateBps: Int,
) {
    companion object {
        /** The test pattern's own mode. */
        const val PATTERN_WIDTH = 1920
        const val PATTERN_HEIGHT = 1080
        const val PATTERN_FPS = 30

        /**
         * The settings for the test-pattern source. The pattern is 1920x1080 at 30 fps, so "source" picks
         * that, and a choice above the source's own mode falls back to it. With the default settings this
         * is 1920x1080, 30 fps, 6 Mbps H.264 with a keyframe every 2 s, and 48 kHz stereo AAC at 128 kbps.
         */
        fun forTestPattern(settings: AppSettings = AppSettings()): CaptureConfig {
            val (width, height) = when (settings.video.resolution) {
                OutputResolution.SOURCE, OutputResolution.P1080 -> PATTERN_WIDTH to PATTERN_HEIGHT
                OutputResolution.P720 -> 1280 to 720
            }
            val fps = when (settings.video.frameRate) {
                FrameRate.SOURCE -> minOf(PATTERN_FPS, FrameRate.SOURCE_MAX_FPS)
                FrameRate.FPS_60 -> minOf(PATTERN_FPS, 60)
                FrameRate.FPS_30 -> minOf(PATTERN_FPS, 30)
            }
            return CaptureConfig(
                width = width,
                height = height,
                fps = fps,
                videoBitrateBps = settings.video.bitrateKbps * 1000,
                keyframeIntervalSeconds = settings.video.keyframeIntervalSeconds,
                sampleRateHz = settings.audio.sampleRateHz,
                stereo = settings.audio.channels == AudioChannels.STEREO,
                audioBitrateBps = settings.audio.bitrateKbps * 1000,
            )
        }
    }
}
