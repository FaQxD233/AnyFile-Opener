package com.anyfile.x

import com.anyfile.x.files.FileHasher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class FileHasherTest {

    @Test
    fun matchesKnownDigests() = runBlocking<Unit> {
        val hashes = FileHasher.hash(ByteArrayInputStream("abc".toByteArray()), 3L)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hashes.md5)
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hashes.sha256
        )
    }

    @Test
    fun reportsFinalProgress() = runBlocking<Unit> {
        var reported = -1L
        FileHasher.hash(ByteArrayInputStream(ByteArray(10)), 10L) { done, _ -> reported = done }
        assertEquals(10L, reported)
    }
}
