package org.example.projectname.log

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.MediaCodecInfo
import android.os.Build
import com.pedro.encoder.utils.CodecUtil
import org.example.projectname.capture.CaptureConfig

/**
 * Facts about the phone and the app for the top of the debug log. Only things that help with debugging
 * and say nothing about the person: no serial number, account, network address or device ID.
 */
object DeviceInfo {
    private const val H264 = "video/avc"
    private const val AAC = "audio/mp4a-latm"

    @Volatile
    private var cached: List<Pair<String, String>>? = null

    /** The facts, worked out once (listing the encoders takes a moment) and then reused. */
    fun header(context: Context, config: CaptureConfig): List<Pair<String, String>> =
        cached ?: collect(context, config).also { cached = it }

    /** Writes the facts into the debug log. Call it once at start, off the main thread. */
    fun logOnce(context: Context, config: CaptureConfig) {
        for ((name, value) in header(context, config)) AppLog.i(TAG, "$name: $value")
    }

    private const val TAG = "Device"

    fun collect(context: Context, config: CaptureConfig): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out += "App" to appVersion(context)
        out += "Device" to "${Build.MANUFACTURER} ${Build.MODEL}"
        out += "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        out += "Supported ABIs" to Build.SUPPORTED_ABIS.joinToString(", ")
        out += "Source" to "Test pattern, ${config.width}x${config.height}, ${config.fps} fps"
        out += "Video settings" to "H.264, ${config.videoBitrateBps / 1000} kbps, " +
            "keyframe every ${config.keyframeIntervalSeconds} s"
        out += "Audio settings" to "AAC, ${config.sampleRateHz} Hz, " +
            "${if (config.stereo) "stereo" else "mono"}, ${config.audioBitrateBps / 1000} kbps"
        out += encoderLines()
        return out
    }

    /** The app's version name and code, and whether this is a debuggable build. */
    fun appVersion(context: Context): String {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            return "${context.packageName} (version unknown: ${e.javaClass.simpleName})"
        }
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val kind = if (debuggable) "debug" else "release"
        return "${context.packageName} ${info.versionName} (${info.longVersionCode}), $kind build"
    }

    /**
     * The H.264 and AAC encoders the phone offers. RootEncoder takes the first suitable one in the order it
     * sorts them (hardware first), so that one is named separately.
     */
    fun encoderLines(): List<Pair<String, String>> = try {
        val video = CodecUtil.getAllEncoders(H264, true, true)
        val audio = CodecUtil.getAllEncoders(AAC, true)
        listOf(
            "H.264 encoder RootEncoder picks" to (video.firstOrNull(::takesSurfaceInput)?.name ?: "none found"),
            "H.264 encoders" to describeAll(video),
            "AAC encoder RootEncoder picks" to (audio.firstOrNull()?.name ?: "none found"),
            "AAC encoders" to describeAll(audio),
        )
    } catch (e: RuntimeException) {
        listOf("Encoders" to "couldn't be listed: ${e.javaClass.simpleName}: ${e.message}")
    }

    private fun describeAll(codecs: List<MediaCodecInfo>): String =
        if (codecs.isEmpty()) "none" else codecs.joinToString(", ") { codec ->
            val kind = when {
                codec.isHardwareAccelerated -> "hardware"
                codec.isSoftwareOnly -> "software"
                else -> "unknown"
            }
            "${codec.name} ($kind)"
        }

    private fun takesSurfaceInput(codec: MediaCodecInfo): Boolean = try {
        CodecUtil.getCapabilities(codec, H264)?.colorFormats
            ?.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) == true
    } catch (_: RuntimeException) {
        false
    }
}
