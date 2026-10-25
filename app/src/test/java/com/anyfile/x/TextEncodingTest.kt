package com.anyfile.x

import com.anyfile.x.engine.TextEncoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TextEncodingTest {

    private val chinese = "这是一段用于测试编码识别的中文文本。"

    @Test
    fun detectsUnicodeEncodings() {
        assertEquals("ASCII", TextEncoding.detect("plain text".toByteArray()).label)
        assertEquals("UTF-8", TextEncoding.detect(chinese.toByteArray()).label)

        val bom = TextEncoding.detect("﻿$chinese".toByteArray(Charsets.UTF_16LE))
        assertEquals("UTF-16LE", bom.label)
        assertEquals(2, bom.bomLength)

        val noBom = TextEncoding.detect("Hello, plain UTF-16 text".toByteArray(Charsets.UTF_16LE))
        assertEquals(Charsets.UTF_16LE, noBom.charset)
    }

    @Test
    fun truncatedUtf8IsStillUtf8() {
        val bytes = chinese.toByteArray()
        val cut = bytes.copyOf(bytes.size - 1)
        assertEquals("UTF-8", TextEncoding.detect(cut).label)
        // The incomplete last character is dropped, not replaced.
        val decoded = TextEncoding.decode(cut, Charsets.UTF_8, endOfInput = false)
        assertEquals(chinese.dropLast(1), decoded)
    }

    @Test
    fun detectsGbk() {
        val gbk = chinese.toByteArray(Charset.forName("GBK"))
        val detection = TextEncoding.detect(gbk)
        assertEquals("GBK/GB18030", detection.label)
        assertEquals(chinese, TextEncoding.decode(gbk, detection.charset))
    }

    @Test
    fun separatesTextFromBinary() {
        assertTrue(TextEncoding.looksLikeText(chinese.toByteArray()))
        assertTrue(TextEncoding.looksLikeText(chinese.toByteArray(Charset.forName("GBK"))))
        assertFalse(TextEncoding.looksLikeText(ByteArray(256) { it.toByte() }))
        // Little-endian 16-bit samples look like BOM-less UTF-16 but decode to controls.
        val pcm = ByteArray(512) { if (it % 2 == 0) (it % 7).toByte() else 0 }
        assertFalse(TextEncoding.looksLikeText(pcm))
    }
}
