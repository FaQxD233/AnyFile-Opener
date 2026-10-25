package com.anyfile.x

import com.anyfile.x.engine.FileInspector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileInspectorTest {

    @Test
    fun returnsValueWithinTimeout() = runBlocking<Unit> {
        assertEquals("done", FileInspector.withIoTimeout(1_000) { "done" })
    }

    @Test
    fun abandonsBlockedReadAfterTimeout() = runBlocking<Unit> {
        val started = System.nanoTime()
        val value = FileInspector.withIoTimeout(100) {
            Thread.sleep(2_000)
            "late"
        }
        assertNull(value)
        assertTrue(System.nanoTime() - started < 1_500_000_000L)
    }
}
