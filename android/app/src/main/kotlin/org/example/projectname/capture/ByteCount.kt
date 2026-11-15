package org.example.projectname.capture

/** A number of bytes in words for the debug log, such as "1 byte" or "2000 bytes". */
internal fun byteCount(count: Long): String = if (count == 1L) "1 byte" else "$count bytes"
