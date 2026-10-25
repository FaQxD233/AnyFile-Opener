package com.anyfile.x.engine.utils

/** Byte-pattern helpers shared by the signature and container checks. */
object ByteSearch {

    /**
     * Returns the first offset in [from]..[maxOffset] where [needle] occurs in
     * [haystack], or -1.
     */
    fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
        from: Int = 0,
        maxOffset: Int = Int.MAX_VALUE
    ): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val last = minOf(haystack.size - needle.size, maxOffset)
        var offset = maxOf(from, 0)
        while (offset <= last) {
            if (needle.indices.all { haystack[offset + it] == needle[it] }) return offset
            offset++
        }
        return -1
    }

    /** True when [bytes] contains the ASCII [text] at [offset]. */
    fun matchesAscii(bytes: ByteArray, offset: Int, text: String): Boolean =
        offset >= 0 && bytes.size >= offset + text.length && text.indices.all {
            bytes[offset + it] == text[it].code.toByte()
        }
}
