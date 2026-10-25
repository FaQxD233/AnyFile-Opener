package com.anyfile.x.engine

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Best-effort text encoding detection. Order: byte-order mark, BOM-less UTF-16,
 * ASCII, strict UTF-8, strict GB18030, then ISO-8859-1 as a lossless fallback.
 * Big5 and Shift_JIS cannot be told apart from GBK this way; the preview offers
 * them as manual choices instead.
 */
object TextEncoding {

    data class Detection(val charset: Charset, val label: String, val bomLength: Int)

    private const val DEFAULT_SAMPLE_BYTES = 64 * 1024
    private const val TEXT_CHECK_BYTES = 1024
    private val ALLOWED_CONTROLS = charArrayOf('\t', '\n', '\r', '\u000C', '\u001B')

    val GB18030: Charset = Charset.forName("GB18030")
    private val UTF_32LE: Charset = Charset.forName("UTF-32LE")
    private val UTF_32BE: Charset = Charset.forName("UTF-32BE")

    /** Charsets offered for manual selection, in menu order. */
    val choices: List<Pair<String, Charset>> by lazy {
        listOf(
            "UTF-8" to Charsets.UTF_8,
            "GBK / GB18030" to GB18030,
            "Big5" to Charset.forName("Big5"),
            "Shift_JIS" to Charset.forName("Shift_JIS"),
            "UTF-16LE" to Charsets.UTF_16LE,
            "UTF-16BE" to Charsets.UTF_16BE,
            "ISO-8859-1" to Charsets.ISO_8859_1
        )
    }

    fun bomLength(bytes: ByteArray): Int = byteOrderMark(bytes)?.bomLength ?: 0

    fun detect(bytes: ByteArray, sampleLimit: Int = DEFAULT_SAMPLE_BYTES): Detection {
        byteOrderMark(bytes)?.let { return it }
        val length = minOf(bytes.size, sampleLimit)
        bomLessUtf16(bytes, length)?.let { return it }
        if ((0 until length).all { bytes[it] >= 0 }) {
            return Detection(Charsets.UTF_8, "ASCII", 0)
        }
        if (decodesStrictly(bytes, length, Charsets.UTF_8)) {
            return Detection(Charsets.UTF_8, "UTF-8", 0)
        }
        if (decodesStrictly(bytes, length, GB18030)) {
            return Detection(GB18030, "GBK/GB18030", 0)
        }
        return Detection(Charsets.ISO_8859_1, "8-bit (unknown)", 0)
    }

    /**
     * True when the sample decodes in a recognized encoding and control
     * characters or undecodable bytes stay rare.
     */
    fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val detection = detect(bytes, TEXT_CHECK_BYTES)
        if (detection.bomLength > 0) return true
        val length = minOf(bytes.size, TEXT_CHECK_BYTES)
        val text = decode(bytes, detection.charset, 0, length, endOfInput = false)
        if (text.isEmpty()) return false
        val suspicious = text.count {
            it == '�' || (it.isISOControl() && it !in ALLOWED_CONTROLS)
        }
        return suspicious <= text.length / 50
    }

    /**
     * Decodes [length] bytes from [offset]. With [endOfInput] false, a multi-byte
     * sequence cut off at the end of the sample is dropped instead of replaced.
     */
    fun decode(
        bytes: ByteArray,
        charset: Charset,
        offset: Int = 0,
        length: Int = bytes.size - offset,
        endOfInput: Boolean = true
    ): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val input = ByteBuffer.wrap(bytes, offset, maxOf(length, 0))
        val output = CharBuffer.allocate(8192)
        val text = StringBuilder(maxOf(length, 0))
        while (true) {
            val result = decoder.decode(input, output, endOfInput)
            output.flip()
            text.append(output)
            output.clear()
            if (!result.isOverflow) break
        }
        if (endOfInput) {
            decoder.flush(output)
            output.flip()
            text.append(output)
        }
        return text.toString()
    }

    private fun byteOrderMark(bytes: ByteArray): Detection? = when {
        startsWith(bytes, 0xEF, 0xBB, 0xBF) -> Detection(Charsets.UTF_8, "UTF-8 (BOM)", 3)
        startsWith(bytes, 0xFF, 0xFE, 0x00, 0x00) -> Detection(UTF_32LE, "UTF-32LE", 4)
        startsWith(bytes, 0x00, 0x00, 0xFE, 0xFF) -> Detection(UTF_32BE, "UTF-32BE", 4)
        startsWith(bytes, 0xFF, 0xFE) -> Detection(Charsets.UTF_16LE, "UTF-16LE", 2)
        startsWith(bytes, 0xFE, 0xFF) -> Detection(Charsets.UTF_16BE, "UTF-16BE", 2)
        else -> null
    }

    /** UTF-16 without a BOM: mostly-ASCII text leaves every other byte zero. */
    private fun bomLessUtf16(bytes: ByteArray, length: Int): Detection? {
        val sample = minOf(length, TEXT_CHECK_BYTES) and 1.inv()
        if (sample < 8) return null
        var evenZeros = 0
        var oddZeros = 0
        for (index in 0 until sample) {
            if (bytes[index].toInt() == 0) {
                if (index % 2 == 0) evenZeros++ else oddZeros++
            }
        }
        val units = sample / 2
        return when {
            oddZeros * 10 >= units * 4 && evenZeros * 20 <= units ->
                Detection(Charsets.UTF_16LE, "UTF-16LE (no BOM)", 0)
            evenZeros * 10 >= units * 4 && oddZeros * 20 <= units ->
                Detection(Charsets.UTF_16BE, "UTF-16BE (no BOM)", 0)
            else -> null
        }
    }

    /** Strict decode that tolerates one multi-byte sequence cut off at the end. */
    private fun decodesStrictly(bytes: ByteArray, length: Int, charset: Charset): Boolean {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes, 0, length)
        val output = CharBuffer.allocate(8192)
        while (true) {
            val result = decoder.decode(input, output, false)
            if (result.isError) return false
            if (!result.isOverflow) return true
            output.clear()
        }
    }

    private fun startsWith(bytes: ByteArray, vararg prefix: Int): Boolean =
        bytes.size >= prefix.size &&
            prefix.indices.all { (bytes[it].toInt() and 0xFF) == prefix[it] }
}
