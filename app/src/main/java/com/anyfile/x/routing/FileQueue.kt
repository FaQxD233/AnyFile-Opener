package com.anyfile.x.routing

/**
 * The files handed to MainActivity, shown one sheet at a time, and what to do after
 * each sheet outcome. Plain Kotlin with string URIs, so it is testable on the JVM.
 */
class FileQueue {

    /** What the activity does after an outcome. */
    enum class Next {
        /** Nothing is left: close the activity. */
        FINISH,

        /** Show the current file (the next one, if the queue advanced) once the sheet is gone. */
        SHOW_NEXT,

        /** Another app is in front; show the next file when the activity resumes. */
        WAIT_FOR_RETURN,

        /** The app chooser is open; decide when it reports whether an app was picked. */
        RESOLVE_CHOOSER
    }

    /** What survives recreation of the activity. */
    data class Snapshot(val pending: List<String>, val total: Int, val processed: Int)

    private val pending = ArrayDeque<String>()

    var total = 0
        private set
    var processed = 0
        private set

    val current: String? get() = pending.firstOrNull()

    /** 1-based position of [current] among all the files that were queued. */
    val position: Int get() = processed + 1

    /** Starts over with [items], keeping at most [limit]; returns how many were dropped. */
    fun reset(items: List<String>, limit: Int): Int {
        val kept = items.take(limit)
        pending.clear()
        pending.addAll(kept)
        total = kept.size
        processed = 0
        return items.size - kept.size
    }

    /** The file was handed to an app directly. */
    fun onOpened(): Next {
        advance()
        return if (pending.isEmpty()) Next.FINISH else Next.WAIT_FOR_RETURN
    }

    /** The app chooser opened; an untracked chooser cannot tell, so the file is shown again. */
    fun onChooserLaunched(tracked: Boolean): Next =
        if (tracked) Next.RESOLVE_CHOOSER else Next.SHOW_NEXT

    /** The chooser closed; [chosen] tells whether an app was picked or it was dismissed. */
    fun onChooserResolved(chosen: Boolean): Next {
        if (chosen) advance()
        return if (pending.isEmpty()) Next.FINISH else Next.SHOW_NEXT
    }

    fun onSkipped(): Next {
        advance()
        return if (pending.isEmpty()) Next.FINISH else Next.SHOW_NEXT
    }

    fun onCancelled(): Next {
        pending.clear()
        return Next.FINISH
    }

    fun snapshot(): Snapshot = Snapshot(pending.toList(), total, processed)

    fun restore(snapshot: Snapshot) {
        pending.clear()
        pending.addAll(snapshot.pending)
        total = snapshot.total
        processed = snapshot.processed
    }

    private fun advance() {
        if (pending.isNotEmpty()) {
            pending.removeFirst()
            processed++
        }
    }
}
