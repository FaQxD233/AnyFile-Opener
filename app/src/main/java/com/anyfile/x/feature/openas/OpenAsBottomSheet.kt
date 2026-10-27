package com.anyfile.x.feature.openas

import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anyfile.x.data.PrefsManager
import com.anyfile.x.engine.FileInspector
import com.anyfile.x.engine.MimeDetector
import com.anyfile.x.feature.common.ComposeBottomSheet
import com.anyfile.x.feature.common.RenamedOpenEffect
import com.anyfile.x.feature.common.rememberNotificationRequest
import com.anyfile.x.feature.common.rememberSaveCopy
import com.anyfile.x.feature.common.toBundle
import com.anyfile.x.feature.common.toInspection
import com.anyfile.x.feature.details.DetailsBottomSheet
import com.anyfile.x.routing.IntentRouter
import com.anyfile.x.tasks.FileTasks

/** Displays one item in the incoming file queue, or the type grid for the home screen. */
class OpenAsBottomSheet : ComposeBottomSheet() {

    private val viewModel: OpenAsViewModel by viewModels()
    private var pendingOutcome: String? = null
    private var pendingChooserRequestId: String? = null
    private var resultSent = false

    companion object {
        const val RESULT_REQUEST_KEY = "open_as_result"
        const val RESULT_OUTCOME = "outcome"
        const val RESULT_CHOOSER_REQUEST_ID = "chooser_request_id"
        const val OUTCOME_OPENED = "opened"
        const val OUTCOME_CHOOSER_LAUNCHED = "chooser_launched"
        const val OUTCOME_SKIPPED = "skipped"
        const val OUTCOME_CANCELLED = "cancelled"

        private const val ARG_URI = "arg_uri"
        private const val ARG_QUEUE_POSITION = "queue_position"
        private const val ARG_QUEUE_TOTAL = "queue_total"
        private const val ARG_MANAGED_QUEUE = "managed_queue"
        private const val ARG_PRESET = "preset"
        private const val ARG_TYPES_ONLY = "types_only"
        private const val DETAILS_TAG = "details"

        /**
         * [preset] skips detecting the file again; [typesOnly] shows just the type grid,
         * for the home screen, which offers the other actions itself.
         */
        fun newInstance(
            uri: Uri,
            queuePosition: Int = 1,
            queueTotal: Int = 1,
            managedQueue: Boolean = false,
            preset: FileInspector.Inspection? = null,
            typesOnly: Boolean = false
        ): OpenAsBottomSheet = OpenAsBottomSheet().apply {
            arguments = bundleOf(
                ARG_URI to uri.toString(),
                ARG_QUEUE_POSITION to queuePosition,
                ARG_QUEUE_TOTAL to queueTotal,
                ARG_MANAGED_QUEUE to managedQueue,
                ARG_PRESET to preset?.toBundle(),
                ARG_TYPES_ONLY to typesOnly
            )
        }
    }

    private val sourceUri: Uri? get() = arguments?.getString(ARG_URI)?.let(Uri::parse)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = sourceUri ?: return
        viewModel.load(
            uri,
            arguments?.getBundle(ARG_PRESET)?.toInspection(),
            PrefsManager(requireContext()).defaultMime
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (sourceUri == null) complete(OUTCOME_CANCELLED)
    }

    @Composable
    override fun SheetContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val pendingRename by viewModel.renamedOpener.pending.collectAsStateWithLifecycle()
        val allTasks by FileTasks.tasks.collectAsStateWithLifecycle()
        val saveCopy = rememberSaveCopy()
        val requestNotifications = rememberNotificationRequest()

        // A file:// source is unusable until it has been copied.
        val bridging = state.workingUri == null && !state.unreadable
        val busy = pendingRename != null || bridging
        LaunchedEffect(busy) { setBusy(busy) }

        RenamedOpenEffect(viewModel.renamedOpener) { request, copy ->
            handleOpenResult(
                IntentRouter.open(
                    requireContext(),
                    copy,
                    request.mime,
                    request.fileName,
                    trackChooserResult = isManagedQueue(),
                    recentUri = request.source
                )
            )
        }

        val source = sourceUri
        OpenAsContent(
            state = state,
            queuePosition = arguments?.getInt(ARG_QUEUE_POSITION, 1) ?: 1,
            queueTotal = arguments?.getInt(ARG_QUEUE_TOTAL, 1) ?: 1,
            typesOnly = arguments?.getBoolean(ARG_TYPES_ONLY, false) == true,
            tasks = allTasks.filter { it.source == source || it.source == state.workingUri },
            busy = busy,
            onOpen = { open(state) },
            onOpenWithApp = { openWithLastApp(state) },
            onOpenCorrected = {
                val uri = state.workingUri
                val inspection = state.inspection
                val hint = state.suggestion
                if (uri != null && inspection != null && hint != null) {
                    viewModel.renamedOpener.start(uri, hint.suggestedName, inspection.result.mime, inspection.size)
                    requestNotifications()
                }
            },
            onShare = {
                val uri = state.workingUri
                val inspection = state.inspection
                if (uri != null && inspection != null) {
                    IntentRouter.share(requireContext(), uri, inspection.result.mime, inspection.displayName)
                }
            },
            onSave = {
                val uri = state.workingUri
                val inspection = state.inspection
                if (uri != null && inspection != null) {
                    val name = state.suggestion?.suggestedName ?: inspection.displayName ?: "file"
                    saveCopy(uri, name, inspection.size)
                }
            },
            onDetails = {
                val uri = state.workingUri
                if (uri != null) {
                    DetailsBottomSheet.newInstance(uri, state.inspection)
                        .show(parentFragmentManager, DETAILS_TAG)
                }
            },
            onOpenAsType = { type -> openAsType(state, type) },
            onSkip = { complete(OUTCOME_SKIPPED) },
            onCancelQueue = { complete(OUTCOME_CANCELLED) }
        )
    }

    private fun open(state: OpenAsViewModel.State) {
        val uri = state.workingUri ?: return
        val inspection = state.inspection ?: return
        handleOpenResult(
            IntentRouter.open(
                requireContext(),
                uri,
                inspection.result.mime,
                inspection.displayName,
                trackChooserResult = isManagedQueue()
            )
        )
    }

    private fun openWithLastApp(state: OpenAsViewModel.State) {
        val uri = state.workingUri ?: return
        val inspection = state.inspection ?: return
        val app = state.lastApp ?: return
        handleOpenResult(
            IntentRouter.openWithApp(
                requireContext(),
                uri,
                inspection.result.mime,
                app.packageName,
                inspection.displayName,
                trackChooserResult = isManagedQueue()
            )
        )
    }

    /** The detected category opens with the exact detected type, others with the category's. */
    private fun openAsType(state: OpenAsViewModel.State, type: MimeDetector.FileType) {
        val uri = state.workingUri ?: return
        val inspection = state.inspection ?: return
        val mime = if (type == inspection.result.fileType) {
            inspection.result.mime
        } else {
            MimeDetector.mimeOf(type, PrefsManager(requireContext()).defaultMime)
        }
        handleOpenResult(
            IntentRouter.open(
                requireContext(),
                uri,
                mime,
                inspection.displayName,
                trackChooserResult = isManagedQueue()
            )
        )
    }

    override fun onCancel(dialog: DialogInterface) {
        if (pendingOutcome == null) pendingOutcome = OUTCOME_CANCELLED
        super.onCancel(dialog)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        sendManagedResultIfNeeded()
    }

    private fun sendManagedResultIfNeeded() {
        val outcome = pendingOutcome ?: return
        if (!isManagedQueue()) return
        if (resultSent) return
        resultSent = true
        parentFragmentManager.setFragmentResult(
            RESULT_REQUEST_KEY,
            bundleOf(
                RESULT_OUTCOME to outcome,
                RESULT_CHOOSER_REQUEST_ID to pendingChooserRequestId
            )
        )
    }

    private fun handleOpenResult(result: IntentRouter.OpenResult) {
        when (result.status) {
            IntentRouter.OpenStatus.DIRECT_STARTED -> complete(OUTCOME_OPENED)
            IntentRouter.OpenStatus.CHOOSER_STARTED -> {
                if (isManagedQueue()) {
                    val requestId = result.chooserRequestId
                    if (requestId == null) {
                        Log.e("OpenAsBottomSheet", "Tracked chooser has no request ID")
                    } else {
                        complete(OUTCOME_CHOOSER_LAUNCHED, requestId)
                    }
                } else {
                    complete(OUTCOME_OPENED)
                }
            }

            IntentRouter.OpenStatus.NO_HANDLER,
            IntentRouter.OpenStatus.FAILED -> Unit
        }
    }

    private fun complete(outcome: String, chooserRequestId: String? = null) {
        if (pendingOutcome != null) return
        pendingOutcome = outcome
        pendingChooserRequestId = chooserRequestId
        sendManagedResultIfNeeded()
        dismiss()
    }

    private fun isManagedQueue(): Boolean =
        arguments?.getBoolean(ARG_MANAGED_QUEUE, false) == true
}
