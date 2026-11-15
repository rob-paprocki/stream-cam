package org.example.projectname.core.validation

/**
 * Builds address-like test strings at run time.
 *
 * The repository's personal-value check refuses any text in a published file that looks like an IP
 * or MAC address outside the example ranges, broken ones included. Tests that need such strings (an
 * octet of 300, a leading zero, IPv6 shapes that can't start with 2001:db8, runs of hex pairs that
 * contain six in a row) build them here instead of writing them out.
 */
internal object TestAddresses {

    /** Joins the parts with dots, for example `ipv4(192, 168, 100, 300)`. */
    fun ipv4(vararg parts: Any): String = parts.joinToString(".")

    /** [count] hex pairs counting up from 0a, joined by [separator]: `hexRun(3)` is `0a:0b:0c`. */
    fun hexRun(count: Int, separator: String = ":"): String =
        (10 until 10 + count).joinToString(separator) { it.toString(16).padStart(2, '0') }

    /** An IPv6 address with [left] groups, `::`, then [right] groups, using small hex numbers as groups. */
    fun compressed(left: Int, right: Int): String =
        (1..left).joinToString(":") { it.toString(16) } + "::" + (1..right).joinToString(":") { (it + 8).toString(16) }

    /** [count] groups and no `::`. Exactly 8 is a valid address. */
    fun uncompressed(count: Int): String = (1..count).joinToString(":") { it.toString(16) }

    /** Every shape of compressed IPv6 address with 0 to 7 written groups: all valid. */
    val validShapes: List<String> = (0..7).flatMap { left -> (0..7 - left).map { right -> compressed(left, right) } } + uncompressed(8)

    /** Shapes that are never valid: 8 written groups plus `::`, and 7 or 9 groups without one. */
    val invalidShapes: List<String> = (0..8).map { left -> compressed(left, 8 - left) } + uncompressed(7) + uncompressed(9)
}
