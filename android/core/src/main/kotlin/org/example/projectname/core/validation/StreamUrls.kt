package org.example.projectname.core.validation

/** The URL schemes the app reads. [defaultPort] is the port used when a URL doesn't name one. */
enum class UrlScheme(val text: String, val defaultPort: Int?) {
    RTMP("rtmp", 1935),
    RTMPS("rtmps", 443),
    RTSP("rtsp", 554),
    RTSPS("rtsps", 322),

    /** SRT has no standard port, so an SRT URL must always name one. */
    SRT("srt", null),
    ;

    companion object {
        val RTMP_ANY: Set<UrlScheme> = setOf(RTMP, RTMPS)
        val RTSP_ANY: Set<UrlScheme> = setOf(RTSP, RTSPS)
    }
}

/**
 * A parsed stream URL.
 *
 * [host] is a host name, an IPv4 address, or an IPv6 address without its square brackets. [port] is null
 * when the URL doesn't name one. Query values can hold secrets, so [toString] leaves them out.
 */
data class StreamUrl(
    val scheme: UrlScheme,
    val host: String,
    val port: Int?,
    val pathSegments: List<String>,
    val queryParameters: Map<String, String>,
    val hasQuery: Boolean,
) {
    val hostIsIpv6: Boolean get() = ':' in host

    /** The port the app connects to: the one in the URL, or the scheme's usual port. */
    val effectivePort: Int? get() = port ?: scheme.defaultPort

    override fun toString(): String =
        "StreamUrl(scheme=$scheme, host=$host, port=$port, pathSegments=$pathSegments, " +
            "queryKeys=${queryParameters.keys})"
}

/** The parts of an `srt://host:port?streamid=...&passphrase=...` URL. [toString] leaves out the passphrase. */
data class SrtAddress(
    val host: String,
    val port: Int,
    val streamId: String?,
    val passphrase: String?,
) {
    override fun toString(): String =
        "SrtAddress(host=$host, port=$port, streamId=${streamId?.let { "<set>" }}, " +
            "passphrase=${passphrase?.let { "<set>" }})"
}

/** The result of reading something a user typed: a value, or a plain-words reason it can't be used. */
sealed interface Parsed<out T> {
    data class Valid<T>(val value: T) : Parsed<T>
    data class Invalid(val message: String) : Parsed<Nothing>
}

/**
 * Reads and writes the stream URLs a user types or pastes: `rtmp://`, `rtmps://`, `rtsp://`, `rtsps://`
 * and `srt://`. IPv6 addresses must be in square brackets, as in `rtsp://[2001:db8::1]:8554/live`.
 *
 * Names and passwords never belong in a URL here: they have their own fields, and the secrets go to the
 * secret store, so a URL with `user:password@` is refused.
 */
object StreamUrls {

    fun parse(text: String, allowed: Set<UrlScheme>): Parsed<StreamUrl> {
        require(allowed.isNotEmpty()) { "allowed must name at least one scheme" }
        val startHint = startHint(allowed)
        if (text.isEmpty()) return Parsed.Invalid("Enter an address. $startHint")
        if (text.any { it.isWhitespace() || it.isISOControl() }) {
            return Parsed.Invalid("The address can't contain spaces or line breaks.")
        }

        val separator = text.indexOf("://")
        if (separator <= 0) return Parsed.Invalid(startHint)
        val schemeText = text.substring(0, separator)
        val scheme = UrlScheme.entries.firstOrNull { it.text == schemeText }
        if (scheme == null) {
            val lowered = UrlScheme.entries.firstOrNull { it.text == schemeText.lowercase() }
            return if (lowered != null && lowered in allowed) {
                Parsed.Invalid("Write the start of the address in lower case: ${lowered.text}://")
            } else {
                Parsed.Invalid(startHint)
            }
        }
        if (scheme !in allowed) return Parsed.Invalid(startHint)

        var rest = text.substring(separator + 3)
        var query: String? = null
        val queryStart = rest.indexOf('?')
        if (queryStart >= 0) {
            query = rest.substring(queryStart + 1)
            rest = rest.substring(0, queryStart)
        }
        // SRT stream IDs often start with "#!::" and are pasted without encoding, so in an SRT query a #
        // is part of the value. Anywhere else it would start a fragment, which stream URLs don't use.
        val hashInQuery = query != null && '#' in query && scheme != UrlScheme.SRT
        if ('#' in rest || hashInQuery) return Parsed.Invalid("Remove the part of the address from # onwards.")

        val pathStart = rest.indexOf('/')
        val authority = if (pathStart >= 0) rest.substring(0, pathStart) else rest
        val path = if (pathStart >= 0) rest.substring(pathStart) else ""

        if ('@' in authority) {
            return Parsed.Invalid("Put the user name and password in their own fields, not in the address.")
        }

        val host: String
        val portText: String?
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close < 0) return Parsed.Invalid("The IPv6 address is missing its closing ].")
            host = authority.substring(1, close)
            if (host.isEmpty()) return Parsed.Invalid("The square brackets are empty. Put an IPv6 address in them.")
            if ('%' in host) return Parsed.Invalid("IPv6 addresses with a zone (the part after %) aren't supported.")
            if (IpLiterals.parseIpv6(host) == null) {
                return Parsed.Invalid("The address in square brackets isn't a valid IPv6 address.")
            }
            val after = authority.substring(close + 1)
            portText = when {
                after.isEmpty() -> null
                after.startsWith(':') -> after.substring(1)
                else -> return Parsed.Invalid("Only a colon and a port number can follow the ].")
            }
        } else {
            val colons = authority.count { it == ':' }
            if (colons > 1) {
                return Parsed.Invalid("Put an IPv6 address in square brackets, like ${scheme.text}://[2001:db8::1].")
            }
            if (']' in authority) return Parsed.Invalid("The address has a ] without a matching [.")
            host = authority.substringBefore(':')
            portText = if (colons == 1) authority.substringAfter(':') else null
            if (host.isEmpty()) return Parsed.Invalid("The address is missing a host name or IP address.")
            FieldRules.checkNameOrIpv4(host)?.let { return Parsed.Invalid(it) }
        }

        val port = when (portText) {
            null -> null
            else -> parsePort(portText) ?: return Parsed.Invalid(
                "The port must be a number from ${FieldRules.MIN_PORT} to ${FieldRules.MAX_PORT}, " +
                    "written without leading zeros.",
            )
        }

        val segments = if (path.isEmpty()) {
            emptyList()
        } else {
            val parts = path.substring(1).split('/')
            for ((index, part) in parts.withIndex()) {
                if (part.isEmpty()) {
                    return Parsed.Invalid(
                        if (index == parts.lastIndex) {
                            "Remove the slash at the end of the address."
                        } else {
                            "The address has two slashes in a row. Remove one."
                        },
                    )
                }
                if (!part.all { IpLiterals.isAsciiLetterOrDigit(it) || it in URL_PATH_SYMBOLS }) {
                    return Parsed.Invalid("The path of the address can only use letters, digits and the symbols . _ ~ -")
                }
            }
            parts
        }

        val parameters = when (query) {
            null -> emptyMap()
            else -> parseQuery(query) ?: return Parsed.Invalid("The part of the address after ? isn't written correctly.")
        }

        return Parsed.Valid(StreamUrl(scheme, host, port, segments, parameters, hasQuery = query != null))
    }

    /**
     * Reads an `srt://host:port` URL, with the optional `streamid` and `passphrase` parameters that most
     * SRT tools understand. Other parameters are ignored, because tools disagree on their units.
     */
    fun parseSrt(text: String): Parsed<SrtAddress> {
        val url = when (val parsed = parse(text, setOf(UrlScheme.SRT))) {
            is Parsed.Invalid -> return parsed
            is Parsed.Valid -> parsed.value
        }
        val port = url.port ?: return Parsed.Invalid("Add the port number, like srt://${formatHost(url.host)}:9000")
        if (url.pathSegments.isNotEmpty()) {
            return Parsed.Invalid("SRT addresses don't have a path. Remove the part after the port.")
        }
        val streamId = url.queryParameters["streamid"]
        streamId?.let { id -> FieldRules.checkSrtStreamId(id)?.let { return Parsed.Invalid(it) } }
        val passphrase = url.queryParameters["passphrase"]
        passphrase?.let { value -> FieldRules.checkSrtPassphrase(value)?.let { return Parsed.Invalid(it) } }
        return Parsed.Valid(SrtAddress(url.host, port, streamId, passphrase))
    }

    /**
     * Builds a URL such as the connect address shown for a listener or server (spec UI-7). IPv6 hosts
     * are put in square brackets. Path segments are joined with slashes.
     */
    fun format(scheme: UrlScheme, host: String, port: Int?, pathSegments: List<String> = emptyList()): String {
        val builder = StringBuilder()
        builder.append(scheme.text).append("://").append(formatHost(host))
        if (port != null) builder.append(':').append(port)
        for (segment in pathSegments) builder.append('/').append(segment)
        return builder.toString()
    }

    /** Puts an IPv6 address in square brackets; leaves host names and IPv4 addresses as they are. */
    fun formatHost(host: String): String = if (':' in host && !host.startsWith('[')) "[$host]" else host

    private fun parsePort(text: String): Int? {
        if (text.isEmpty() || text.length > 5 || !text.all { it in '0'..'9' }) return null
        if (text[0] == '0') return null
        val value = text.toInt()
        return if (value in FieldRules.MIN_PORT..FieldRules.MAX_PORT) value else null
    }

    private fun parseQuery(query: String): Map<String, String>? {
        if (query.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = percentDecode(pair.substringBefore('=')) ?: return null
            val value = percentDecode(pair.substringAfter('=', missingDelimiterValue = "")) ?: return null
            if (key.isEmpty()) return null
            result[key] = value
        }
        return result
    }

    /** Decodes %XX sequences as UTF-8. A plus sign stays a plus sign. */
    private fun percentDecode(text: String): String? {
        if ('%' !in text) return text
        val bytes = java.io.ByteArrayOutputStream(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index] == '%') {
                if (index + 2 > text.lastIndex) return null
                val high = Character.digit(text[index + 1], 16)
                val low = Character.digit(text[index + 2], 16)
                if (high < 0 || low < 0) return null
                bytes.write((high shl 4) or low)
                index += 3
            } else {
                val end = text.indexOf('%', index).let { if (it < 0) text.length else it }
                val encoded = text.substring(index, end).toByteArray(Charsets.UTF_8)
                bytes.write(encoded, 0, encoded.size)
                index = end
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private fun startHint(allowed: Set<UrlScheme>): String {
        val names = UrlScheme.entries.filter { it in allowed }.map { "${it.text}://" }
        val list = when (names.size) {
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " or " + names.last()
        }
        return "Start the address with $list"
    }

    private const val URL_PATH_SYMBOLS = "._~-"
}
