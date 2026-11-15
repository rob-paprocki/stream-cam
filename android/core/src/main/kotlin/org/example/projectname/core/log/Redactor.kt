package org.example.projectname.core.log

import org.example.projectname.core.validation.IpLiterals

/**
 * Removes secrets and personal values from log text before it is kept or exported (spec OUT-6).
 *
 * What it replaces, in this order:
 * 1. `user:password@` (or just `user@`) in any URL, with `<redacted>@`.
 * 2. The stream key: the last path segment of every `rtmp://` and `rtmps://` URL.
 * 3. `Authorization`, `Proxy-Authorization`, `Cookie` and `Set-Cookie` header values: a quoted value that
 *    ends the field (see 5), or else the rest of the line.
 * 4. USB serial numbers: `iSerialNumber` lines as `lsusb -v` prints them, and any field whose name
 *    ends in serial, serialNumber or serialno (`serial=`, `mSerialNumber=`, `[ro.serialno]: [...]`). A
 *    quoted value with no closing quote runs to the end of the line.
 * 5. Values of fields named like a secret: any name ending in passphrase, password, passwd, pwd, secret,
 *    token, key or streamid (so streamKey and x-api-key count too), and the words pass and auth. This
 *    covers the `passphrase=` of SRT URLs. SRT passphrases can hold spaces, `&` and quotes, so a value
 *    that isn't quoted runs to the start of the next field (`, name=` with its space, or `&name=`), to a
 *    `)` or `}`, or to the end of the line. A quoted value ends at its closing quote; a backslash escapes
 *    a quote inside it. It counts as quoted only if the field ends there: if `, ; & ) } ]` or the end of
 *    the line comes next, after any spaces, or a space and the next field's `name=` or `name:`. Otherwise
 *    (`'it's'`, `"Tom" says hi, ...`) the quotes are part of a value that isn't quoted.
 *    JSON logged inside a JSON string, where each quote has a backslash in front
 *    (`{\"password\":\"...\"}`), is read the same way, for these fields and for 3 and 4.
 * 6. MAC addresses, with `<mac>`.
 * 7. IPv6 and IPv4 addresses, with `<ip>`. Loopback (127.0.0.0/8, ::1) and the unspecified addresses
 *    (0.0.0.0, ::) stay, because they say nothing about the user and help with debugging.
 *
 * A MAC or IPv6 address can follow a name and a colon (`mac:...`, `addr:...`), and a colon can follow it,
 * alone or before a word (`MAC 00:00:5E:00:53:01: link up`, `...:connected`, `...:added`). Only a name
 * with a letter that isn't a hex digit counts. After a MAC address, one or two hex digits that end the word
 * (`:1`, `:0a`) mean the six pairs are part of a longer run; after an IPv6 address, a hex group that fits
 * (`:1`, `:cafe`) does. So part of such a run is never taken for an address, and times such as 12:34:56
 * aren't addresses at all. A MAC address can also follow a name and a dash, as in a file name
 * (`hw-<mac>`, `scan-<mac>-1.json`), and an IPv6 address's zone or a word after it ends it (`%wlan0:5`).
 *
 * A line longer than [MAX_LINE_LENGTH] characters keeps only its first [MAX_LINE_LENGTH], less any part of
 * a word the cut falls in, and ends with a note, ` [line cut: N characters]`, that says how many were
 * taken off. Half a value can't be told from text that isn't one, so the word goes whole, unless it
 * started more than 512 characters before the cut. The cut comes before any rule runs, so what was cut off
 * never reaches the output.
 *
 * It errs towards removing too much: a four-part version number also becomes `<ip>`, and a secret
 * value that isn't quoted takes the rest of its line with it when no next field follows.
 * Running it twice gives the same result as running it once. A cut line keeps its note when it is
 * redacted again. It is cut again only if it has grown past the limit since (a timestamp added in front,
 * or short secrets that became `<redacted>`), and the note then counts both cuts.
 */
object Redactor {
    const val REDACTED = "<redacted>"
    const val IP = "<ip>"
    const val MAC = "<mac>"

    /**
     * The longest line the rules see. Android's regex engine (ICU) runs out of stack, where the JVM doesn't,
     * on a line of about 90,000 characters for some of the secret rules (a value like `a,a,a,...`) and
     * 180,000 for most, so no line may come near that. No useful log line is this long.
     */
    const val MAX_LINE_LENGTH = 16_000

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        // A line that must be cut, or that ends with an earlier cut's note, is done on its own. The lines
        // between such lines go through the rules together: no rule reaches across a line break.
        val out = StringBuilder()
        var done = 0
        var start = 0
        while (start < text.length) {
            var end = start
            while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
            val cut = cutLine(text, start, end)
            if (cut != null) {
                out.append(redactLines(text.substring(done, start))).append(cut)
                done = end
            }
            start = end + 1
        }
        if (done == 0) return redactLines(text)
        return out.append(redactLines(text.substring(done))).toString()
    }

    /**
     * The line from [start] to [end], cut and redacted, with its note; null if it is short enough and has
     * no note. A note an earlier run left is kept out of the rules' way (a secret value would otherwise take
     * it), and its count goes on into the new one if the line has to be cut again.
     */
    private fun cutLine(text: String, start: Int, end: Int): String? {
        var bodyEnd = end
        var removedBefore = 0L
        if (end > start && text[end - 1] == ']') {
            CUT_NOTE.find(text.substring(maxOf(start, end - CUT_NOTE_LONGEST), end))?.let {
                bodyEnd = end - it.value.length
                removedBefore = it.groupValues[1].toLong()
            }
        }
        if (bodyEnd == end && end - start <= MAX_LINE_LENGTH) return null
        var keep = bodyEnd
        if (bodyEnd - start > MAX_LINE_LENGTH) {
            keep = start + MAX_LINE_LENGTH
            if (Character.isHighSurrogate(text[keep - 1])) keep-- // never half of a pair
            keep = startOfCutWord(text, start, keep)
        }
        val note = if (keep < bodyEnd) cutNote(removedBefore + (bodyEnd - keep)) else text.substring(bodyEnd, end)
        return redactLines(text.substring(start, keep)) + note
    }

    /**
     * Where the word that a cut at [cut] falls in starts, so that the word goes whole: half a value can't
     * be told apart from text that isn't one (a URL without the `@` of its user info, a quoted value
     * without its closing quote, five and a half groups of a MAC address). A word is a run of characters
     * other than whitespace and `, ; ) ] }`. A word that started more than [CUT_WORD_LONGEST] characters
     * before the cut is cut where the cut falls; [cut] is returned then, and when the cut falls between
     * two words.
     */
    private fun startOfCutWord(text: String, start: Int, cut: Int): Int {
        if (text[cut].isWhitespace()) return cut
        val limit = maxOf(start, cut - CUT_WORD_LONGEST)
        // A URL's user info may hold , ; ) ] or }, so inside a run without whitespace that has a "://"
        // before the cut, the cut goes back to the start of the whole run.
        var runStart = cut
        while (runStart > limit && !text[runStart - 1].isWhitespace()) runStart--
        val runIsWhole = runStart == start || text[runStart - 1].isWhitespace()
        if (runIsWhole && text.indexOf("://", runStart) in runStart until cut) return runStart
        if (!isWordChar(text[cut])) return cut
        var wordStart = cut
        while (wordStart > limit && isWordChar(text[wordStart - 1])) wordStart--
        return if (wordStart == start || !isWordChar(text[wordStart - 1])) wordStart else cut
    }

    private fun isWordChar(c: Char): Boolean = !c.isWhitespace() && c != ',' && c != ';' && c != ')' && c != ']' && c != '}'

    private const val CUT_WORD_LONGEST = 512

    private fun cutNote(removed: Long): String = " [line cut: $removed ${if (removed == 1L) "character" else "characters"}]"

    private fun redactLines(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = URL_USERINFO.replace(out) { it.groupValues[1] + REDACTED + "@" }
        out = redactRtmpKeys(out)
        out = HEADER.replace(out) { keepNameReplaceValue(it) }
        out = LSUSB_SERIAL.replace(out) { it.groupValues[1] + REDACTED }
        out = SERIAL_FIELD.replace(out) { keepNameReplaceValue(it) }
        out = SECRET_TIGHT.replace(out) { keepNameReplaceValue(it) }
        out = SECRET_SPACED.replace(out) { keepNameReplaceValue(it) }
        out = MAC_ADDRESS.replace(out, MAC)
        out = IPV6_CANDIDATE.replace(out) { redactIpv6(it.value) }
        out = IPV4_CANDIDATE.replace(out) { redactIpv4(it.value) }
        return out
    }

    /**
     * Replaces the stream key of every rtmp:// and rtmps:// URL: the last segment of its path, up to a quote.
     * The path is the run of segments after the host, each one at least one character other than whitespace,
     * `/ ? #`. It ends at the first empty segment (`//`, or a `/` with nothing after it), and the key is the
     * last of its segments that doesn't start with a quote. A URL with no such segment keeps its text.
     */
    private fun redactRtmpKeys(text: String): String {
        var scheme = RTMP_SCHEME.find(text) ?: return text
        val out = StringBuilder(text.length)
        var copied = 0
        while (true) {
            val key = rtmpKey(text, scheme.range.last + 1)
            val next = if (key == null) {
                scheme.range.first + 1
            } else {
                out.append(text, copied, key.first).append(REDACTED)
                copied = key.last + 1
                copied
            }
            scheme = RTMP_SCHEME.find(text, next) ?: break
        }
        return out.append(text, copied, text.length).toString()
    }

    /** Where the stream key of the URL whose host starts at [hostStart] is, or null if it has none. */
    private fun rtmpKey(text: String, hostStart: Int): IntRange? {
        var i = hostStart
        while (i < text.length && !endsUrlPart(text[i])) i++
        if (i == hostStart) return null
        var keyStart = -1
        var keyEnd = -1
        while (i < text.length && text[i] == '/') {
            val segmentStart = i + 1
            i = segmentStart
            while (i < text.length && !endsUrlPart(text[i])) i++
            if (i == segmentStart) break
            if (text[segmentStart] != '"' && text[segmentStart] != '\'') {
                keyStart = segmentStart
                keyEnd = i
            }
        }
        if (keyStart < 0) return null
        var end = keyStart
        while (end < keyEnd && text[end] != '"' && text[end] != '\'') end++
        return keyStart until end
    }

    // The characters that end a host or a path segment: \s as Java's regex reads it, '/', '?' and '#'.
    private fun endsUrlPart(c: Char): Boolean =
        c == '/' || c == '?' || c == '#' || c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    /** Group 1 is the field name, group 2 the separator, group 3 the value. Quotes around the value stay. */
    private fun keepNameReplaceValue(match: MatchResult): String {
        val value = match.groupValues[3]
        val quote = QUOTE_MARKS.firstOrNull { value.length >= 2 * it.length && value.startsWith(it) && value.endsWith(it) }
        val replacement = if (quote != null) "$quote$REDACTED$quote" else REDACTED
        return match.groupValues[1] + match.groupValues[2] + replacement
    }

    private val QUOTE_MARKS = listOf("\\\"", "\"", "'")

    private fun redactIpv6(token: String): String {
        val zone = token.indexOf('%')
        if (zone >= 0) {
            // The zone goes with the address. A colon after it stays.
            val groups = parseIpv6(token.substring(0, zone))
            return if (groups == null || IpLiterals.isLoopbackOrUnspecifiedIpv6(groups)) token else IP
        }
        if (token.any { it == '_' || it in 'g'..'z' || it in 'G'..'Z' }) {
            // A word after the address and a colon or dot, as in "2001:db8::1:connected" or
            // "2001:db8::1.local". The candidate took the word's leading hex letters too, so the address
            // ends at the last colon or dot. The word stays.
            val end = maxOf(token.lastIndexOf(':'), token.lastIndexOf('.'))
            return addressBefore(token, end) ?: token
        }
        var core = token.trimEnd('.')
        var groups = parseIpv6(core)
        // A colon right after the address belongs to the sentence, as in "peer 2001:db8::2: closed".
        if (groups == null && core.endsWith(':')) {
            core = core.dropLast(1)
            groups = parseIpv6(core)
        }
        if (groups == null) {
            // A word of hex letters after the address and a colon, as in "2001:db8::1:added": the address
            // ends at the last colon if what follows it can't be a group. A group that can ("2001:db8::1:1"
            // with one too many groups) means the run isn't an address, so it stays.
            val colon = core.lastIndexOf(':')
            val last = core.substring(colon + 1)
            return if (last.length > 4 || '.' in last) addressBefore(token, colon) ?: token else token
        }
        if (IpLiterals.isLoopbackOrUnspecifiedIpv6(groups)) return token
        // Dots and a colon after the address stay.
        return IP + token.substring(core.length)
    }

    /** `<ip>` and what follows [end] in [token], if the part before [end] is an address to remove; else null. */
    private fun addressBefore(token: String, end: Int): String? {
        val groups = if (end > 0) parseIpv6(token.substring(0, end)) else null
        return if (groups == null || IpLiterals.isLoopbackOrUnspecifiedIpv6(groups)) null else IP + token.substring(end)
    }

    private fun parseIpv6(text: String): IntArray? =
        if (text.count { it == ':' } < 2) null else IpLiterals.parseIpv6(text, allowEmbeddedIpv4 = true)

    private fun redactIpv4(token: String): String {
        val octets = IpLiterals.parseIpv4(token, allowLeadingZeros = true) ?: return token
        return if (IpLiterals.isLoopbackOrUnspecifiedIpv4(octets)) token else IP
    }

    // The note at the end of a cut line. The count has at most 18 digits, so it always fits in a Long.
    private val CUT_NOTE = Regex(""" \[line cut: ([1-9][0-9]{0,17}) characters?\]$""")
    private const val CUT_NOTE_LONGEST = 48

    // A quoted value. Inside the quotes, a backslash escapes the next character, so "abc\"def" is one value.
    private const val QUOTED = """"(?:[^"\\\r\n]++|\\[^\r\n])*+"|'(?:[^'\\\r\n]++|\\[^\r\n])*+'"""

    // The same inside a JSON string, where each quote has a backslash in front: \"abc\\\"def\" is one
    // value. Two backslashes stand for one, which escapes whatever comes after it.
    private const val ESCAPED_QUOTED = """\\"(?:[^"\\\r\n]++|\\\\(?:\\[^\r\n]|[^\\\r\n])|\\[^"\\\r\n])*+\\""""

    // The separator after a field's name may start with the name's closing quote, escaped or not.
    private const val NAME_END = """(?:\\?["']|\])?"""
    private const val FIELD_NAME = """[A-Za-z_][A-Za-z0-9_.-]*+"""

    // A quoted value counts as quoted only where its field ends: at , ; & ) } ] or the end of the line,
    // after any spaces, or at a space and the next field's name= or name:. Otherwise the quotes were part
    // of the value ('it's', "Tom" says hi), and the bare form below takes the whole value.
    private const val FIELD_ENDS = """(?=[ \t]*+(?:[,;&)}\]\r\n]|$)|[ \t]++$FIELD_NAME[ \t]*+[=:])"""
    private const val QUOTED_FIELD = """(?:$QUOTED|$ESCAPED_QUOTED)$FIELD_ENDS"""

    // Where a bare secret value ends: before the next field (", name=" as Kotlin's toString writes it, or
    // "&name=" in a URL), at a ) or }, or at the end of the line. Not at a space, a lone & or a comma with no
    // space after it: SRT passphrases can contain all three, and SRT stream IDs use "#!::r=live,s=...".
    // Runs of other characters are taken whole, never one character at a time with a look-ahead at each:
    // ICU keeps a backtrack frame for every turn of such a loop.
    private const val SECRET_BARE =
        """(?![ \t])(?:[^,&)}\r\n]++|,(?![ \t]++$FIELD_NAME[ \t]*+=)|&(?!$FIELD_NAME=))++"""
    private const val SECRET_VALUE = """($QUOTED_FIELD|$SECRET_BARE)"""

    // Field names are found by their last word only, never with an open-ended prefix such as
    // [A-Za-z.]*serial, which would make long dotted runs of text take quadratic time.
    private const val SERIAL_NAME = """(serial(?:[ _-]?(?:number|num|no))?)"""
    private const val SECRET_NAME =
        """(passphrase|password|passwd|pwd|secret|token|key|streamid|(?<![A-Za-z0-9_])(?:pass|auth))"""

    // The scheme is matched possessively: ':' can't be part of it, so giving characters back never helps.
    // A try starts only where a run of scheme characters starts, and steps over any digits, dots, dashes and
    // pluses before its first letter: a try at every letter inside a run (each after a dot or a dash, as \b
    // allows) would each scan to the end of the run, which takes quadratic time on a long dotted run.
    private val URL_USERINFO = Regex("""(?i)(?<![a-z0-9+.-])([0-9+.-]*+[a-z][a-z0-9+.-]*+://)[^\s/?#]+@""")

    // Where an rtmp:// or rtmps:// URL starts. The path and its key are read by [redactRtmpKeys]: a
    // repeated group over the path's segments makes the JVM's regex engine recurse once per segment.
    private val RTMP_SCHEME = Regex("""(?i)\brtmps?://""")

    private val HEADER = Regex(
        """(?i)\b((?:proxy-)?authorization|set-cookie|cookie)((?:\\?["'])?[ \t]*[:=][ \t]*)($QUOTED_FIELD|[^\r\n]+)""",
    )

    private val LSUSB_SERIAL = Regex("""(?im)^([ \t]*iSerialNumber[ \t]+[0-9]+[ \t]+)(\S[^\r\n]*)""")

    // A quote with no closing quote on the line takes the rest of the line, as a bare secret value does.
    private val SERIAL_FIELD =
        Regex("""(?i)$SERIAL_NAME($NAME_END[ \t]*[:=][ \t]*\[?)($QUOTED|$ESCAPED_QUOTED|\\?["'][^\r\n]*+|[^\s"',;)\]}]+)""")

    private val SECRET_TIGHT = Regex("""(?i)$SECRET_NAME($NAME_END=)$SECRET_VALUE""")

    // The spaces are taken possessively: a value never starts with one, and ICU's frames get smaller.
    private val SECRET_SPACED = Regex(
        """(?i)$SECRET_NAME($NAME_END(?:[ \t]++=[ \t]*+|=[ \t]++|[ \t]*+:[ \t]*+))$SECRET_VALUE""",
    )

    // Right after a name and a colon, as in mac:... or addr:..., or after a space and a colon. The name needs
    // a letter that isn't a hex digit within its last 31 characters, so a group of a longer hex run, such as
    // the 0a in 0a:0b:..., never counts as a name. The plain (?<=:) comes first because it is cheap: the
    // longer look-behind then runs only right after a colon.
    private const val AFTER_NAME_AND_COLON = """(?<=:)(?<=[G-Zg-z][0-9A-Za-z_]{0,30}+:|[ \t]:)"""

    // Right after a name and a dash, as in a file name (scan-<mac>.json, hw-<mac>). The name ends in a
    // letter, digit or _ that isn't a lone hex digit or pair: the 0a- of 0a-0b-... is part of the run, not a
    // name. tests/check_repo.py reads a dash in front the same way. The plain (?<=-) comes first because it is
    // cheap.
    private const val AFTER_NAME_AND_DASH =
        """(?<=-)(?<=[G-Zg-z_]-|[G-Zg-z_][0-9A-Fa-f]-|[0-9A-Za-z_][0-9A-Fa-f]{2}-)"""

    private const val SIX_PAIRS =
        """(?:[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}|[0-9A-Fa-f]{2}(?:-[0-9A-Fa-f]{2}){5})(?![0-9A-Fa-f])"""

    // Six pairs. A colon or dash may follow, alone or before a word (": up", ":connected", ":added"), but
    // not before one or two hex digits that end the word (":1", ":0a") or before another separator: then the
    // six are part of a longer run. After a name and a dash, a file name may put a dash and a copy number
    // after the address (scan-<mac>-1.json), so there only a whole pair after a dash means a longer run.
    private val MAC_ADDRESS = Regex(
        """(?:(?<![0-9A-Fa-f:-])|$AFTER_NAME_AND_COLON)$SIX_PAIRS""" +
            """(?![:-](?:[0-9A-Fa-f]{1,2}(?![0-9A-Za-z_])|[:-]))""" +
            """|$AFTER_NAME_AND_DASH$SIX_PAIRS""" +
            """(?!:[0-9A-Fa-f]{1,2}(?![0-9A-Za-z_])|-[0-9A-Fa-f]{2}(?![0-9A-Za-z_])|[:-][:-])""",
    )

    // Possessive quantifiers: a long run of hex and colons can't make the search backtrack. The run can be
    // followed by a zone, or by the rest of a word whose first letters it took (":connected" gives
    // ":c" + "onnected"); redactIpv6 then finds where the address ends. Either ends the address, so what
    // follows it stays, a colon and a hex group too (%wlan0:5).
    private val IPV6_CANDIDATE = Regex(
        """(?:(?<![0-9A-Za-z_:.%])|$AFTER_NAME_AND_COLON)[0-9A-Fa-f:.]{2,}+""" +
            """(?:%[0-9A-Za-z._~-]++|[G-Zg-z_][0-9A-Za-z_]*+)?+(?![0-9A-Za-z_%])""",
    )

    private val IPV4_CANDIDATE = Regex("""(?<![0-9.])[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}(?![0-9]|\.[0-9])""")
}
