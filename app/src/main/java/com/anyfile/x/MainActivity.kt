package com.anyfile.x

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anyfile.x.data.PrefsManager
import com.anyfile.x.feature.openas.OpenAsBottomSheet
import com.anyfile.x.routing.ChooserRequestStore
import com.anyfile.x.ui.AnyFileOpenerTheme
import com.anyfile.x.ui.ThemePreference
import java.util.ArrayDeque
import java.util.LinkedHashSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Transparent entry point that processes VIEW/SEND files as a sequential queue. */
class MainActivity : AppCompatActivity() {

    private val pendingUris = ArrayDeque<Uri>()
    private var totalFiles = 0
    private var processedFiles = 0
    private var waitingForExternalReturn = false
    private var showNextWhenSheetIsRemoved = false
    private var pendingChooserRequestId: String? = null
    private var chooserResolutionJob: Job? = null
    private var isPostResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = PrefsManager(this)
        setContent {
            val themePref by prefs.themePreference.collectAsStateWithLifecycle(
                ThemePreference.SYSTEM_DEFAULT
            )
            AnyFileOpenerTheme(themePreference = themePref) {
                Box(modifier = Modifier.fillMaxSize())
            }
        }

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
            savedInstanceState.getStringArrayList(STATE_PENDING_URIS)
                .orEmpty()
                .forEach { pendingUris.add(Uri.parse(it)) }
            totalFiles = savedInstanceState.getInt(STATE_TOTAL_FILES, pendingUris.size)
            processedFiles = savedInstanceState.getInt(STATE_PROCESSED_FILES, 0)
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
        outState.putStringArrayList(
            STATE_PENDING_URIS,
            ArrayList(pendingUris.map(Uri::toString))
        )
        outState.putInt(STATE_TOTAL_FILES, totalFiles)
        outState.putInt(STATE_PROCESSED_FILES, processedFiles)
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
        val extractedUris = extractUris(intent)
        val uris = extractedUris.take(MAX_QUEUE_SIZE)
        if (uris.isEmpty()) {
            startLauncher()
            return
        }
        if (extractedUris.size > MAX_QUEUE_SIZE) {
            Toast.makeText(
                this,
                "Only the first $MAX_QUEUE_SIZE files were added to the queue",
                Toast.LENGTH_LONG
            ).show()
        }

        pendingUris.clear()
        pendingUris.addAll(uris)
        totalFiles = uris.size
        processedFiles = 0
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
        when (outcome) {
            OpenAsBottomSheet.OUTCOME_OPENED -> {
                advanceQueue()
                if (pendingUris.isEmpty()) {
                    finish()
                } else {
                    waitingForExternalReturn = true
                }
            }

            OpenAsBottomSheet.OUTCOME_CHOOSER_LAUNCHED -> {
                if (chooserRequestId.isNullOrBlank()) {
                    showNextWhenSheetIsRemoved = true
                    return
                }
                pendingChooserRequestId = chooserRequestId
                waitingForExternalReturn = false
                if (isPostResumed) scheduleChooserResolution()
            }

            OpenAsBottomSheet.OUTCOME_SKIPPED -> {
                advanceQueue()
                if (pendingUris.isEmpty()) {
                    finish()
                } else {
                    showNextWhenSheetIsRemoved = true
                }
            }

            OpenAsBottomSheet.OUTCOME_CANCELLED -> {
                ChooserRequestStore.clear(this, pendingChooserRequestId)
                pendingChooserRequestId = null
                pendingUris.clear()
                finish()
            }
        }
    }

    private fun advanceQueue() {
        if (pendingUris.isNotEmpty()) {
            pendingUris.removeFirst()
            processedFiles++
        }
    }

    private fun showNextIfReady() {
        if (isFinishing || waitingForExternalReturn || pendingChooserRequestId != null ||
            supportFragmentManager.isStateSaved
        ) return
        if (supportFragmentManager.findFragmentByTag(OPEN_AS_TAG) != null) return

        val uri = pendingUris.peekFirst() ?: return
        OpenAsBottomSheet.newInstance(
            uri = uri,
            queuePosition = processedFiles + 1,
            queueTotal = totalFiles,
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
            if (wasChosen) {
                advanceQueue()
                if (pendingUris.isEmpty()) {
                    finish()
                    return@launch
                }
            }

            if (supportFragmentManager.findFragmentByTag(OPEN_AS_TAG) != null) {
                showNextWhenSheetIsRemoved = true
            } else {
                showNextIfReady()
            }
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
