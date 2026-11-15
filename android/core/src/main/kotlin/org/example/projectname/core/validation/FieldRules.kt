package org.example.projectname.core.validation

/**
 * One problem with one field, in words a user can act on.
 *
 * [field] is the field's path in the profile file (for example `destination.port`), or an empty string
 * when the problem is with the whole file.
 */
data class FieldError(val field: String, val message: String)

/**
 * The rules for each field a user types in. Each check returns null when the value is fine, or a short
 * plain-words sentence saying what to change.
 *
 * The same rules are written as patterns in `shared/profile.schema.json`; `SchemaParityTest` keeps the
 * two in step.
 */
object FieldRules {
    const val MIN_PORT = 1
    const val MAX_PORT = 65535

    /** Ports up to 1024 are reserved, and apps can't listen on them. */
    const val MIN_LISTEN_PORT = 1025

    const val SRT_MIN_LATENCY_MS = 20
    const val SRT_MAX_LATENCY_MS = 8000
    const val SRT_DEFAULT_LATENCY_MS = 120

    /** libsrt accepts passphrases of 10 to 79 characters (spec OUT-2). */
    const val SRT_MIN_PASSPHRASE_LENGTH = 10
    const val SRT_MAX_PASSPHRASE_LENGTH = 79

    /** libsrt limits a stream ID to 512 characters. */
    const val SRT_MAX_STREAM_ID_LENGTH = 512

    const val MAX_PROFILE_NAME_LENGTH = 80
    const val MAX_PROFILE_ID_LENGTH = 64
    const val MAX_PATH_PART_LENGTH = 64
    const val MAX_PATH_PARTS = 8
    const val MAX_USERNAME_LENGTH = 64
    const val MAX_PASSWORD_LENGTH = 128
    const val MAX_STREAM_KEY_LENGTH = 512

    /** Checks a host name, an IPv4 address, or an IPv6 address written without square brackets. */
    fun checkHost(value: String): String? {
        if (value.isEmpty()) return "Enter a host name or IP address."
        if (value.any { it.isWhitespace() }) return "The host can't contain spaces."
        if (value.startsWith('[')) return "Enter the IPv6 address without square brackets."
        if (':' in value) {
            if ('%' in value) return "IPv6 addresses with a zone (the part after %) aren't supported."
            return if (IpLiterals.parseIpv6(value) != null) null else "This isn't a valid IPv6 address."
        }
        return checkNameOrIpv4(value)
    }

    /** Checks a host taken from inside a URL, where IPv6 addresses are already out of their brackets. */
    internal fun checkNameOrIpv4(value: String): String? {
        val lastLabel = value.substringAfterLast('.')
        val looksNumeric = lastLabel.isNotEmpty() && lastLabel.all { it in '0'..'9' }
        if (looksNumeric) {
            return if (IpLiterals.parseIpv4(value) != null) {
                null
            } else {
                "This isn't a valid IPv4 address. It needs four numbers from 0 to 255 with dots between " +
                    "them, and no leading zeros."
            }
        }
        if (value.length > 253) return "The host name is too long."
        if (!IpLiterals.isHostName(value)) {
            return "This isn't a valid host name. Use letters, digits, hyphens and dots, like stream.example.com."
        }
        return null
    }

    /** Checks the port of a server the app connects out to. */
    fun checkRemotePort(port: Int): String? =
        if (port in MIN_PORT..MAX_PORT) null else "The port must be a number from $MIN_PORT to $MAX_PORT."

    /** Checks a port the app listens on: SRT listener, RTSP server or RTMP server. */
    fun checkListenPort(port: Int): String? =
        if (port in MIN_LISTEN_PORT..MAX_PORT) {
            null
        } else {
            "Choose a port from $MIN_LISTEN_PORT to $MAX_PORT. Ports up to 1024 are reserved by the system."
        }

    fun checkSrtLatency(latencyMs: Int): String? =
        if (latencyMs in SRT_MIN_LATENCY_MS..SRT_MAX_LATENCY_MS) {
            null
        } else {
            "SRT latency must be from $SRT_MIN_LATENCY_MS to $SRT_MAX_LATENCY_MS ms."
        }

    /**
     * Checks an SRT passphrase (spec OUT-2). Only printable ASCII is accepted, because the receiver
     * compares bytes and other characters can be encoded differently on another device.
     */
    fun checkSrtPassphrase(value: String): String? {
        if (!value.all { it in ' '..'~' }) {
            return "The passphrase can only use unaccented letters, digits, spaces and common symbols."
        }
        if (value.length !in SRT_MIN_PASSPHRASE_LENGTH..SRT_MAX_PASSPHRASE_LENGTH) {
            return "The passphrase must be $SRT_MIN_PASSPHRASE_LENGTH to $SRT_MAX_PASSPHRASE_LENGTH characters long."
        }
        return null
    }

    fun checkSrtStreamId(value: String): String? {
        if (value.isEmpty()) return "The stream ID is empty. Leave it out if the server doesn't need one."
        if (value.length > SRT_MAX_STREAM_ID_LENGTH) {
            return "The stream ID can be at most $SRT_MAX_STREAM_ID_LENGTH characters."
        }
        if (!value.all { it in '!'..'~' }) return "The stream ID can't contain spaces or accented letters."
        return null
    }

    /** Checks the path an RTSP server offers, like `live` or `cam/main`. */
    fun checkRtspPath(value: String): String? {
        if (value.isEmpty()) return "Enter a path, like live."
        if (value.startsWith('/')) return "Leave out the slash at the start of the path."
        if (value.endsWith('/')) return "Leave out the slash at the end of the path."
        val parts = value.split('/')
        if (parts.size > MAX_PATH_PARTS) return "The path can have at most $MAX_PATH_PARTS parts."
        for (part in parts) {
            checkNamePart(part, "path", MAX_PATH_PART_LENGTH)?.let { return it }
        }
        return null
    }

    /** Checks the application name of the RTMP server, the `live` in `rtmp://host/live/name`. */
    fun checkRtmpApp(value: String): String? = checkNamePart(value, "app name", MAX_PATH_PART_LENGTH)

    /** Checks the stream name of the RTMP server, the `name` in `rtmp://host/live/name`. */
    fun checkRtmpStreamName(value: String): String? = checkNamePart(value, "stream name", MAX_PATH_PART_LENGTH)

    private fun checkNamePart(value: String, what: String, maxLength: Int): String? {
        if (value.isEmpty()) return "The $what has an empty part. Remove the extra slash."
        if (value.length > maxLength) return "Each part of the $what can be at most $maxLength characters."
        if (!value.all { IpLiterals.isAsciiLetterOrDigit(it) || it in PATH_SYMBOLS }) {
            return "The $what can only use letters, digits and the symbols . _ ~ -"
        }
        if (!IpLiterals.isAsciiLetterOrDigit(value.first())) {
            return "Start each part of the $what with a letter or a digit."
        }
        return null
    }

    /** Checks a user name for RTSP sign-in. */
    fun checkUsername(value: String): String? {
        if (value.isEmpty()) return "The user name is empty. Leave it out if no sign-in is needed."
        if (value.length > MAX_USERNAME_LENGTH) return "The user name can be at most $MAX_USERNAME_LENGTH characters."
        if (':' in value) return "The user name can't contain a colon."
        if (!value.all { it in '!'..'~' }) return "The user name can't contain spaces or accented letters."
        return null
    }

    /** Checks an RTSP password before it's put in the secret store. It never goes in a profile file. */
    fun checkPassword(value: String): String? {
        if (value.isEmpty()) return "Enter a password."
        if (value.length > MAX_PASSWORD_LENGTH) return "The password can be at most $MAX_PASSWORD_LENGTH characters."
        if (value.any { it.isISOControl() }) return "The password can't contain line breaks or tabs."
        return null
    }

    /** Checks an RTMP stream key before it's put in the secret store. It never goes in a profile file. */
    fun checkStreamKey(value: String): String? {
        if (value.isEmpty()) return "Enter the stream key."
        if (value.length > MAX_STREAM_KEY_LENGTH) return "The stream key can be at most $MAX_STREAM_KEY_LENGTH characters."
        if (value.any { it.isWhitespace() || it.isISOControl() }) return "The stream key can't contain spaces."
        return null
    }

    /**
     * Checks a profile name. Its length is counted in characters (code points), as the schema counts it,
     * not in UTF-16 units: an emoji counts once.
     */
    fun checkProfileName(value: String): String? {
        if (value.isEmpty()) return "Give the profile a name."
        if (value.first() == ' ') return "Start the name with something other than a space."
        if (value.codePointCount(0, value.length) > MAX_PROFILE_NAME_LENGTH) {
            return "The name can be at most $MAX_PROFILE_NAME_LENGTH characters."
        }
        if (value.any { it.code < 0x20 || it.code == 0x7f }) return "The name can't contain line breaks or tabs."
        return null
    }

    fun checkProfileId(value: String): String? {
        if (value.isEmpty() || value.length > MAX_PROFILE_ID_LENGTH) {
            return "The profile ID must be 1 to $MAX_PROFILE_ID_LENGTH characters."
        }
        if (!value.all { IpLiterals.isAsciiLetterOrDigit(it) || it in ID_SYMBOLS }) {
            return "The profile ID can only use letters, digits and the symbols . _ -"
        }
        return null
    }

    private const val PATH_SYMBOLS = "._~-"
    private const val ID_SYMBOLS = "._-"
}
