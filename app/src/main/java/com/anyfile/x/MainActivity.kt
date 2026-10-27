package com.anyfile.x

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.anyfile.x.feature.openas.OpenAsBottomSheet
import com.anyfile.x.routing.ChooserRequestStore
import com.anyfile.x.routing.FileQueue
import java.util.LinkedHashSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Transparent entry point that processes VIEW/SEND files as a sequential queue.
 * It has no content view of its own: the queue is shown as bottom sheets, and
 * [FileQueue] decides what follows each sheet.
 */
class MainActivity : AppCompatActivity() {

    private val queue = FileQueue()
    private var waitingForExternalReturn = false
    private var showNextWhenSheetIsRemoved = false
    private var pendingChooserRequestId: String? = null
    private var chooserResolutionJob: Job? = null
    private var isPostResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportFragmentManager.setFragmentResultListener(
            OpenAsBottomSheet.RESULT_REQUEST_KEY,
            this
        ) { _, result ->
            handleSheetResult(
                result.getString(OpenAsBottomSheet.RESULT_OUTCOME),
                result.getString(OpenAsBottomSheet.RESULT_CHOOSER_REQUEST_ID)
            )
        }
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentDetached(fm: FragmentManager, fragment: Fragment) {
                    if (fragment is OpenAsBottomSheet && showNextWhenSheetIsRemoved) {
                        showNextWhenSheetIsRemoved = false
                        showNextIfReady()
                    }
                }
            },
            false
        )

        if (savedInstanceState == null) {
            handleIntent(intent)
        } else {
            queue.restore(
                FileQueue.Snapshot(
                    pending = savedInstanceState.getStringArrayList(STATE_PENDING_URIS).orEmpty(),
                    total = savedInstanceState.getInt(STATE_TOTAL_FILES, 0),
                    processed = savedInstanceState.getInt(STATE_PROCESSED_FILES, 0)
                )
            )
            waitingForExternalReturn = savedInstanceState.getBoolean(
                STATE_WAITING_FOR_EXTERNAL_RETURN,
                false
            )
            showNextWhenSheetIsRemoved = savedInstanceState.getBoolean(
                STATE_SHOW_NEXT_WHEN_SHEET_REMOVED,
                false
            )
            pendingChooserRequestId = savedInstanceState.getString(
                STATE_PENDING_CHOOSER_REQUEST_ID
            )
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        isPostResumed = true
        if (pendingChooserRequestId != null) {
            scheduleChooserResolution()
            return
        }
        if (waitingForExternalReturn) {
            waitingForExternalReturn = false
        }
        showNextIfReady()
    }

    override fun onPause() {
        isPostResumed = false
        chooserResolutionJob?.cancel()
        chooserResolutionJob = null
        super.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleIntent(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val snapshot = queue.snapshot()
        outState.putStringArrayList(STATE_PENDING_URIS, ArrayList(snapshot.pending))
        outState.putInt(STATE_TOTAL_FILES, snapshot.total)
        outState.putInt(STATE_PROCESSED_FILES, snapshot.processed)
        outState.putBoolean(STATE_WAITING_FOR_EXTERNAL_RETURN, waitingForExternalReturn)
        outState.putBoolean(
            STATE_SHOW_NEXT_WHEN_SHEET_REMOVED,
            showNextWhenSheetIsRemoved
        )
        outState.putString(STATE_PENDING_CHOOSER_REQUEST_ID, pendingChooserRequestId)
        super.onSaveInstanceState(outState)
    }

    private fun handleIntent(intent: Intent) {
        ChooserRequestStore.clear(this, pendingChooserRequestId)
        pendingChooserRequestId = null
        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
                // Shared text or links, not files.
                Toast.makeText(this, "Nothing to open: no file was shared", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                startLauncher()
            }
            return
        }

        // A sheet still showing belongs to the previous queue; its outcome must not
        // advance the new one.
        supportFragmentManager.findFragmentByTag(OPEN_AS_TAG)?.let { sheet ->
            supportFragmentManager.beginTransaction().remove(sheet).commitNowAllowingStateLoss()
        }

        val dropped = queue.reset(uris.map(Uri::toString), MAX_QUEUE_SIZE)
        if (dropped > 0) {
            Toast.makeText(
                this,
                "Only the first $MAX_QUEUE_SIZE files were added to the queue",
                Toast.LENGTH_LONG
            ).show()
        }
        waitingForExternalReturn = false
        showNextWhenSheetIsRemoved = false
        showNextIfReady()
    }

    private fun extractUris(intent: Intent): List<Uri> {
        val result = LinkedHashSet<Uri>()
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let(result::add)
        }

        if (intent.action == Intent.ACTION_SEND) {
            getSingleStream(intent)?.let(result::add)
        }

        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            getMultipleStreams(intent).forEach(result::add)
        }

        intent.clipData?.let { clipData ->
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let(result::add)
            }
        }

        return result.filter { it.scheme == "content" || it.scheme == "file" }
    }

    private fun getSingleStream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun getMultipleStreams(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }

    private fun handleSheetResult(outcome: String?, chooserRequestId: String?) {
        val next = when (outcome) {
            OpenAsBottomSheet.OUTCOME_OPENED -> queue.onOpened()
            OpenAsBottomSheet.OUTCOME_CHOOSER_LAUNCHED ->
                queue.onChooserLaunched(tracked = !chooserRequestId.isNullOrBlank())
            OpenAsBottomSheet.OUTCOME_SKIPPED -> queue.onSkipped()
            OpenAsBottomSheet.OUTCOME_CANCELLED -> {
                ChooserRequestStore.clear(this, pendingChooserRequestId)
                pendingChooserRequestId = null
                queue.onCancelled()
            }
            else -> return
        }
        proceed(next, chooserRequestId)
    }

    private fun proceed(next: FileQueue.Next, chooserRequestId: String? = null) {
        when (next) {
            FileQueue.Next.FINISH -> finish()
            FileQueue.Next.WAIT_FOR_RETURN -> waitingForExternalReturn = true
            FileQueue.Next.SHOW_NEXT -> {
                if (supportFragmentManager.findFragmentByTag(OPEN_AS_TAG) != null) {
                    showNextWhenSheetIsRemoved = true
                } else {
                    showNextIfReady()
                }
            }
            FileQueue.Next.RESOLVE_CHOOSER -> {
                pendingChooserRequestId = chooserRequestId
                waitingForExternalReturn = false
                if (isPostResumed) scheduleChooserResolution()
            }
        }
    }

    private fun showNextIfReady() {
        if (isFinishing || waitingForExternalReturn || pendingChooserRequestId != null ||
            supportFragmentManager.isStateSaved
        ) return
        if (supportFragmentManager.findFragmentByTag(OPEN_AS_TAG) != null) return

        val uri = queue.current?.let(Uri::parse) ?: return
        OpenAsBottomSheet.newInstance(
            uri = uri,
            queuePosition = queue.position,
            queueTotal = queue.total,
            managedQueue = true
        ).show(supportFragmentManager, OPEN_AS_TAG)
    }

    private fun scheduleChooserResolution() {
        val requestId = pendingChooserRequestId ?: return
        chooserResolutionJob?.cancel()
        chooserResolutionJob = lifecycleScope.launch {
            delay(300)
            if (!isPostResumed || isFinishing || pendingChooserRequestId != requestId) {
                return@launch
            }

            val wasChosen = ChooserRequestStore.consumeWasChosen(this@MainActivity, requestId)
            pendingChooserRequestId = null
            proceed(queue.onChooserResolved(wasChosen))
        }
    }

    private fun startLauncher() {
        startActivity(Intent(this, LauncherActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        chooserResolutionJob?.cancel()
        if (isFinishing) {
            ChooserRequestStore.clear(this, pendingChooserRequestId)
            pendingChooserRequestId = null
        }
        super.onDestroy()
    }

    private companion object {
        const val OPEN_AS_TAG = "openAs"
        const val STATE_PENDING_URIS = "pending_uris"
        const val STATE_TOTAL_FILES = "total_files"
        const val STATE_PROCESSED_FILES = "processed_files"
        const val STATE_WAITING_FOR_EXTERNAL_RETURN = "waiting_for_external_return"
        const val STATE_SHOW_NEXT_WHEN_SHEET_REMOVED = "show_next_when_sheet_removed"
        const val STATE_PENDING_CHOOSER_REQUEST_ID = "pending_chooser_request_id"
        const val MAX_QUEUE_SIZE = 50
    }
}
