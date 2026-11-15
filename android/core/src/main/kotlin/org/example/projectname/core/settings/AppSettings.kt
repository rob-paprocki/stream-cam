package org.example.projectname.core.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The app's settings, with the defaults from the spec's Settings table. Stream destinations, their
 * networks and the server ports live in destination profiles instead.
 */
@Serializable
data class AppSettings(
    val video: VideoSettings = VideoSettings(),
    val audio: AudioSettings = AudioSettings(),
    val recording: RecordingSettings = RecordingSettings(),
    val display: DisplaySettings = DisplaySettings(),
)

@Serializable
data class VideoSettings(
    val resolution: OutputResolution = OutputResolution.SOURCE,
    val frameRate: FrameRate = FrameRate.SOURCE,
    val codec: VideoCodec = VideoCodec.H264,
    val bitrateKbps: Int = 6_000,
    val keyframeIntervalSeconds: Int = 2,
) {
    companion object {
        val BITRATE_RANGE_KBPS = 1_000..20_000
        val KEYFRAME_INTERVAL_RANGE_SECONDS = 1..4
    }
}

/** Output resolution. [SOURCE] keeps the source's own size, but no larger than [SOURCE_MAX_HEIGHT]. */
@Serializable
enum class OutputResolution {
    @SerialName("source")
    SOURCE,

    @SerialName("1080p")
    P1080,

    @SerialName("720p")
    P720,
    ;

    companion object {
        const val SOURCE_MAX_HEIGHT = 1080
    }
}

/** Output frame rate. [SOURCE] keeps the source's own rate, but no faster than [SOURCE_MAX_FPS]. */
@Serializable
enum class FrameRate {
    @SerialName("source")
    SOURCE,

    @SerialName("60")
    FPS_60,

    @SerialName("30")
    FPS_30,
    ;

    companion object {
        const val SOURCE_MAX_FPS = 30
    }
}

@Serializable
enum class VideoCodec {
    @SerialName("h264")
    H264,

    /** Offered only where the phone has a hardware HEVC encoder (spec OUT-1). */
    @SerialName("hevc")
    HEVC,
}

@Serializable
data class AudioSettings(
    val source: AudioSource = AudioSource.AUTO,
    val bitrateKbps: Int = 128,
    val sampleRateHz: Int = 48_000,
    val channels: AudioChannels = AudioChannels.STEREO,
    /** Shifts audio against video, for capture cards with a fixed delay (spec SRC-9). */
    val offsetMs: Int = 0,
) {
    companion object {
        val BITRATE_RANGE_KBPS = 64..256
        val SAMPLE_RATES_HZ = setOf(48_000)
        val OFFSET_RANGE_MS = -500..500
    }
}

@Serializable
enum class AudioSource {
    /** USB audio when the video source is a USB device, the built-in microphone otherwise. */
    @SerialName("auto")
    AUTO,

    @SerialName("builtInMic")
    BUILT_IN_MIC,

    @SerialName("usbAudio")
    USB_AUDIO,

    @SerialName("none")
    NONE,
    ;

    /** What [AUTO] means for the current video source. */
    fun resolve(videoSourceIsUsb: Boolean): AudioSource = when (this) {
        AUTO -> if (videoSourceIsUsb) USB_AUDIO else BUILT_IN_MIC
        else -> this
    }
}

@Serializable
enum class AudioChannels {
    @SerialName("mono")
    MONO,

    @SerialName("stereo")
    STEREO,
}

@Serializable
data class RecordingSettings(
    /** Length of each recording segment in minutes, or [SEGMENTS_OFF] for one file (spec REC-2). */
    val segmentMinutes: Int = 30,
    /** A folder the user picked, as the platform's URI for it, or null for the platform default (spec REC-3). */
    val saveFolderUri: String? = null,
) {
    companion object {
        const val SEGMENTS_OFF = 0
        val SEGMENT_CHOICES_MINUTES = listOf(SEGMENTS_OFF, 5, 15, 30, 60)
    }
}

@Serializable
data class DisplaySettings(
    /** Keep the screen on while previewing, recording or streaming (spec UI-9). */
    val keepScreenOn: Boolean = true,
    val dimPreviewWhileLive: Boolean = false,
    /** How built-in cameras are oriented (spec UI-10). */
    val orientationLock: OrientationLock = OrientationLock.LANDSCAPE,
)

@Serializable
enum class OrientationLock {
    @SerialName("auto")
    AUTO,

    @SerialName("landscape")
    LANDSCAPE,

    @SerialName("portrait")
    PORTRAIT,
}

/** Checks every setting against its allowed range. Returns plain-words problems, or an empty list. */
object SettingsValidator {
    fun validate(settings: AppSettings): List<String> {
        val problems = ArrayList<String>()
        with(settings.video) {
            if (bitrateKbps !in VideoSettings.BITRATE_RANGE_KBPS) {
                problems += "Video bitrate must be from 1 to 20 Mbps."
            }
            if (keyframeIntervalSeconds !in VideoSettings.KEYFRAME_INTERVAL_RANGE_SECONDS) {
                problems += "Keyframe interval must be from 1 to 4 seconds."
            }
        }
        with(settings.audio) {
            if (bitrateKbps !in AudioSettings.BITRATE_RANGE_KBPS) problems += "Audio bitrate must be from 64 to 256 kbps."
            if (sampleRateHz !in AudioSettings.SAMPLE_RATES_HZ) problems += "Audio sample rate must be 48 kHz."
            if (offsetMs !in AudioSettings.OFFSET_RANGE_MS) problems += "Audio offset must be from -500 to +500 ms."
        }
        with(settings.recording) {
            if (segmentMinutes !in RecordingSettings.SEGMENT_CHOICES_MINUTES) {
                problems += "Segment length must be off, 5, 15, 30 or 60 minutes."
            }
            if (saveFolderUri != null && saveFolderUri.isBlank()) problems += "The save folder is empty."
        }
        return problems
    }
}
