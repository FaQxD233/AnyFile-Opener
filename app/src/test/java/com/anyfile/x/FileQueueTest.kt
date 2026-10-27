package com.anyfile.x

import com.anyfile.x.routing.FileQueue
import com.anyfile.x.routing.FileQueue.Next
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileQueueTest {

    @Test
    fun resetKeepsTheLimitAndReportsTheRest() {
        val queue = FileQueue()
        assertEquals(2, queue.reset(listOf("a", "b", "c", "d"), limit = 2))
        assertEquals("a", queue.current)
        assertEquals(1, queue.position)
        assertEquals(2, queue.total)
    }

    @Test
    fun openingWaitsForReturnUntilTheLastFile() {
        val queue = FileQueue()
        queue.reset(listOf("a", "b"), limit = 50)

        assertEquals(Next.WAIT_FOR_RETURN, queue.onOpened())
        assertEquals("b", queue.current)
        assertEquals(2, queue.position)

        assertEquals(Next.FINISH, queue.onOpened())
        assertNull(queue.current)
    }

    @Test
    fun skippingShowsTheNextFile() {
        val queue = FileQueue()
        queue.reset(listOf("a", "b"), limit = 50)

        assertEquals(Next.SHOW_NEXT, queue.onSkipped())
        assertEquals("b", queue.current)
        assertEquals(Next.FINISH, queue.onSkipped())
    }

    @Test
    fun chooserAdvancesOnlyWhenAnAppWasPicked() {
        val queue = FileQueue()
        queue.reset(listOf("a", "b"), limit = 50)

        assertEquals(Next.RESOLVE_CHOOSER, queue.onChooserLaunched(tracked = true))
        // Dismissing the chooser shows the same file again.
        assertEquals(Next.SHOW_NEXT, queue.onChooserResolved(chosen = false))
        assertEquals("a", queue.current)

        assertEquals(Next.SHOW_NEXT, queue.onChooserResolved(chosen = true))
        assertEquals("b", queue.current)
        assertEquals(Next.FINISH, queue.onChooserResolved(chosen = true))

        assertEquals(Next.SHOW_NEXT, FileQueue().onChooserLaunched(tracked = false))
    }

    @Test
    fun cancellingDropsTheRest() {
        val queue = FileQueue()
        queue.reset(listOf("a", "b", "c"), limit = 50)
        assertEquals(Next.FINISH, queue.onCancelled())
        assertNull(queue.current)
    }

    @Test
    fun resetStartsOverAfterProgress() {
        val queue = FileQueue()
        queue.reset(listOf("a", "b"), limit = 50)
        queue.onOpened()
        queue.reset(listOf("x"), limit = 50)
        assertEquals("x", queue.current)
        assertEquals(1, queue.position)
        assertEquals(1, queue.total)
    }

    @Test
    fun snapshotRestoresPosition() {
        val queue = FileQueue()
        queue.reset(listOf("a", "b", "c"), limit = 50)
        queue.onSkipped()

        val restored = FileQueue()
        restored.restore(queue.snapshot())
        assertEquals("b", restored.current)
        assertEquals(2, restored.position)
        assertEquals(3, restored.total)
    }
}
