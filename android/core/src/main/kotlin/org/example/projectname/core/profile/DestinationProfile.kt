package org.example.projectname.core.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.example.projectname.core.validation.FieldRules
import org.example.projectname.core.validation.StreamUrls
import org.example.projectname.core.validation.UrlScheme

/** The profile file format this app writes, and the newest one it reads. */
const val PROFILE_SCHEMA_VERSION: Int = 1

const val DEFAULT_SRT_LISTENER_PORT: Int = 9000
const val DEFAULT_RTSP_SERVER_PORT: Int = 8554
const val DEFAULT_RTMP_SERVER_PORT: Int = 1935

/**
 * One saved stream destination (spec OUT-8). Its JSON form follows `shared/profile.schema.json`.
 *
 * Secrets are never stored here. A profile holds only [SecretRef]s, the IDs of entries in the platform's
 * secret store (spec OUT-6), and exports leave even those out.
 */
@Serializable
data class DestinationProfile(
    val schemaVersion: Int = PROFILE_SCHEMA_VERSION,
    val id: String,
    val name: String,
    /** Which network this stream must use (spec NET-1). */
    val network: NetworkChoice = NetworkChoice.AUTO,
    /** Whether the stream may move to another network when the chosen one is missing (spec NET-4). */
    val allowFallback: Boolean = false,
    val destination: Destination,
) {
    /** A copy that is safe to export: every secret reference is removed. */
    fun withoutSecrets(): DestinationProfile = copy(destination = destination.withoutSecrets())

    /** The secrets the user still has to enter before this profile can stream, for example after an import. */
    fun missingSecrets(): List<SecretKind> = destination.missingSecrets()
}

/** The network a stream is pinned to (spec NET-1). */
@Serializable
enum class NetworkChoice {
    /** Follow the system's default network. */
    @SerialName("auto")
    AUTO,

    @SerialName("wifi")
    WIFI,

    @SerialName("cellular")
    CELLULAR,

    @SerialName("ethernet")
    ETHERNET,
}

/** SRT encryption. [keyLengthBytes] is the value libsrt calls `pbkeylen`. */
@Serializable
enum class SrtEncryption(val keyLengthBytes: Int) {
    @SerialName("none")
    NONE(0),

    @SerialName("aes128")
    AES_128(16),

    @SerialName("aes192")
    AES_192(24),

    @SerialName("aes256")
    AES_256(32),
}

/** The kinds of secret a destination can need. */
enum class SecretKind { SRT_PASSPHRASE, STREAM_KEY, PASSWORD }

/** The ID of a secret held in the Android Keystore or the iOS Keychain. Never the secret itself. */
@Serializable
@JvmInline
value class SecretRef(val id: String) {
    override fun toString(): String = "SecretRef(<id>)"
}

/** Where a stream goes. There is one type for each row of the spec's streaming protocols table. */
@Serializable
sealed interface Destination {

    /** The app connects out to an SRT listener. */
    @Serializable
    @SerialName("srtCaller")
    data class SrtCaller(
        val host: String,
        val port: Int,
        val streamId: String? = null,
        val latencyMs: Int = FieldRules.SRT_DEFAULT_LATENCY_MS,
        val encryption: SrtEncryption = SrtEncryption.NONE,
        val passphraseRef: SecretRef? = null,
    ) : Destination

    /** The app waits for an SRT caller to connect in. */
    @Serializable
    @SerialName("srtListener")
    data class SrtListener(
        val port: Int = DEFAULT_SRT_LISTENER_PORT,
        val latencyMs: Int = FieldRules.SRT_DEFAULT_LATENCY_MS,
        val encryption: SrtEncryption = SrtEncryption.NONE,
        val passphraseRef: SecretRef? = null,
    ) : Destination

    /** The app publishes to an RTMP server. [url] starts with `rtmp://` or `rtmps://`. */
    @Serializable
    @SerialName("rtmpPush")
    data class RtmpPush(
        val url: String,
        val streamKeyRef: SecretRef? = null,
    ) : Destination

    /** The app publishes to an RTSP server. [url] starts with `rtsp://` or `rtsps://`. */
    @Serializable
    @SerialName("rtspPush")
    data class RtspPush(
        val url: String,
        val username: String? = null,
        val passwordRef: SecretRef? = null,
    ) : Destination

    /** The app runs an RTSP server that players connect to. */
    @Serializable
    @SerialName("rtspPull")
    data class RtspPull(
        val port: Int = DEFAULT_RTSP_SERVER_PORT,
        val path: String,
        val username: String? = null,
        val passwordRef: SecretRef? = null,
    ) : Destination

    /** The app runs an RTMP server that players connect to, at `rtmp://<device>:<port>/<app>/<streamName>`. */
    @Serializable
    @SerialName("rtmpPull")
    data class RtmpPull(
        val port: Int = DEFAULT_RTMP_SERVER_PORT,
        val app: String,
        val streamName: String,
    ) : Destination

    /** True when the app waits for receivers to connect in, rather than connecting out. */
    val isInbound: Boolean
        get() = when (this) {
            is SrtListener, is RtspPull, is RtmpPull -> true
            is SrtCaller, is RtmpPush, is RtspPush -> false
        }

    /**
     * The address a receiver connects to for an inbound destination (spec UI-7), such as
     * `srt://192.168.100.20:9000`, or null for an outbound one. [deviceHost] is the address of the network
     * the stream is bound to; an IPv6 address is put in square brackets.
     */
    fun connectAddress(deviceHost: String): String? = when (this) {
        is SrtListener -> StreamUrls.format(UrlScheme.SRT, deviceHost, port)
        is RtspPull -> StreamUrls.format(UrlScheme.RTSP, deviceHost, port, path.split('/'))
        is RtmpPull -> StreamUrls.format(UrlScheme.RTMP, deviceHost, port, listOf(app, streamName))
        is SrtCaller, is RtmpPush, is RtspPush -> null
    }

    /** A copy with every secret reference removed. */
    fun withoutSecrets(): Destination = when (this) {
        is SrtCaller -> copy(passphraseRef = null)
        is SrtListener -> copy(passphraseRef = null)
        is RtmpPush -> copy(streamKeyRef = null)
        is RtspPush -> copy(passwordRef = null)
        is RtspPull -> copy(passwordRef = null)
        is RtmpPull -> this
    }

    /**
     * Secrets this destination needs but doesn't have. An encrypted SRT stream needs a passphrase, and an
     * RTSP sign-in with a user name needs a password. An RTMP stream key is optional, because some servers
     * don't use one.
     */
    fun missingSecrets(): List<SecretKind> = when (this) {
        is SrtCaller -> listOfNotNull(SecretKind.SRT_PASSPHRASE.takeIf { encryption != SrtEncryption.NONE && passphraseRef == null })
        is SrtListener -> listOfNotNull(SecretKind.SRT_PASSPHRASE.takeIf { encryption != SrtEncryption.NONE && passphraseRef == null })
        is RtmpPush -> emptyList()
        is RtspPush -> listOfNotNull(SecretKind.PASSWORD.takeIf { username != null && passwordRef == null })
        is RtspPull -> listOfNotNull(SecretKind.PASSWORD.takeIf { username != null && passwordRef == null })
        is RtmpPull -> emptyList()
    }
}
