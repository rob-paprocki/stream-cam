package org.example.projectname.core.validation

/**
 * Parsers for IP address literals, shared by field validation and log redaction.
 *
 * These only read text. They never resolve names, so they're safe to call on anything.
 */
internal object IpLiterals {

    /**
     * Reads a dotted-quad IPv4 address and returns its four parts, or null if [text] isn't one.
     *
     * Field validation passes `allowLeadingZeros = false`, because some parsers read "010" as octal.
     */
    fun parseIpv4(text: String, allowLeadingZeros: Boolean = false): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            if (!allowLeadingZeros && part.length > 1 && part[0] == '0') return null
            val value = part.toInt()
            if (value > 255) return null
            octets[index] = value
        }
        return octets
    }

    /**
     * Reads an IPv6 address in text form and returns its eight 16-bit groups, or null if [text] isn't one.
     *
     * Zone suffixes (`%name`) are never accepted. A trailing dotted IPv4 part, as in IPv4-mapped
     * addresses, is accepted only when [allowEmbeddedIpv4] is true; the profile format doesn't allow it.
     */
    fun parseIpv6(text: String, allowEmbeddedIpv4: Boolean = false): IntArray? {
        if (text.isEmpty() || text.length > 45) return null
        val gap = text.indexOf("::")
        if (gap >= 0 && text.indexOf("::", gap + 1) >= 0) return null

        val head: List<String>
        val tail: List<String>
        if (gap >= 0) {
            val left = text.substring(0, gap)
            val right = text.substring(gap + 2)
            head = if (left.isEmpty()) emptyList() else left.split(':')
            tail = if (right.isEmpty()) emptyList() else right.split(':')
        } else {
            head = text.split(':')
            tail = emptyList()
        }

        val pieces = head + tail
        val groups = ArrayList<Int>(8)
        val headGroups = ArrayList<Int>(8)
        val tailGroups = ArrayList<Int>(8)
        for ((index, piece) in pieces.withIndex()) {
            val target = if (index < head.size) headGroups else tailGroups
            val isLast = index == pieces.lastIndex
            if (isLast && allowEmbeddedIpv4 && '.' in piece) {
                val v4 = parseIpv4(piece, allowLeadingZeros = false) ?: return null
                target += (v4[0] shl 8) or v4[1]
                target += (v4[2] shl 8) or v4[3]
                continue
            }
            if (piece.isEmpty() || piece.length > 4 || !piece.all(::isHexDigit)) return null
            target += piece.toInt(16)
        }

        val explicit = headGroups.size + tailGroups.size
        if (gap >= 0) {
            if (explicit > 7) return null
            groups += headGroups
            repeat(8 - explicit) { groups += 0 }
            groups += tailGroups
        } else {
            if (explicit != 8) return null
            groups += headGroups
        }
        return groups.toIntArray()
    }

    fun isLoopbackOrUnspecifiedIpv4(octets: IntArray): Boolean =
        octets[0] == 127 || octets.all { it == 0 }

    fun isLoopbackOrUnspecifiedIpv6(groups: IntArray): Boolean {
        val firstSixZero = (0 until 6).all { groups[it] == 0 }
        if (firstSixZero && groups[6] == 0 && (groups[7] == 0 || groups[7] == 1)) return true
        // IPv4-mapped (five zero groups, then ffff, then the IPv4 address): judge by the IPv4 part.
        val mapped = (0 until 5).all { groups[it] == 0 } && groups[5] == 0xffff
        if (mapped) {
            val octets = intArrayOf(groups[6] shr 8, groups[6] and 0xff, groups[7] shr 8, groups[7] and 0xff)
            return isLoopbackOrUnspecifiedIpv4(octets)
        }
        return false
    }

    /**
     * A DNS host name: dot-separated labels of 1 to 63 letters, digits and hyphens, not starting or ending
     * with a hyphen, at most 253 characters in all. The last label must start with a letter, so that an
     * all-numeric name is always read as an IPv4 address instead.
     */
    fun isHostName(text: String): Boolean {
        if (text.isEmpty() || text.length > 253) return false
        val labels = text.split('.')
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (!label.all { isAsciiLetterOrDigit(it) || it == '-' }) return false
            if (label.first() == '-' || label.last() == '-') return false
        }
        return isAsciiLetter(labels.last().first())
    }

    fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    fun isAsciiLetterOrDigit(c: Char): Boolean = isAsciiLetter(c) || c in '0'..'9'
}
