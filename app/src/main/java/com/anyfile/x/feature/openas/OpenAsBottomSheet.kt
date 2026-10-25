package com.anyfile.x.feature.openas

import android.content.ContentResolver
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.anyfile.x.data.PrefsManager
import com.anyfile.x.databinding.BottomSheetOpenAsBinding
import com.anyfile.x.engine.FileInspector
import com.anyfile.x.engine.MimeDetector
import com.anyfile.x.engine.MimeTypes
import com.anyfile.x.engine.NameCheck
import com.anyfile.x.feature.common.SaveCopyAction
import com.anyfile.x.feature.common.TaskProgress
import com.anyfile.x.feature.inspect.InspectBottomSheet
import com.anyfile.x.files.FileBridge
import com.anyfile.x.routing.IntentRouter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Displays one item in the incoming file queue. */
class OpenAsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetOpenAsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PrefsManager
    private lateinit var progress: TaskProgress
    private val saveAction = SaveCopyAction(this)
    private var pendingOutcome: String? = null
    private var pendingChooserRequestId: String? = null
    private var resultSent = false

    /** The file being handled: the incoming URI, or a cached copy of a file:// source. */
    private var workingUri: Uri? = null
    private var fileName: String? = null
    private var fileSize: Long? = null
    private var detection: MimeDetector.DetectionResult? = null
    private var suggestion: NameCheck.Suggestion? = null
    private var busy = false

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
        private const val INSPECT_TAG = "inspect"

        fun newInstance(
            uri: Uri,
            queuePosition: Int = 1,
            queueTotal: Int = 1,
            managedQueue: Boolean = false
        ): OpenAsBottomSheet = OpenAsBottomSheet().apply {
            arguments = bundleOf(
                ARG_URI to uri.toString(),
                ARG_QUEUE_POSITION to queuePosition,
                ARG_QUEUE_TOTAL to queueTotal,
                ARG_MANAGED_QUEUE to managedQueue
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetOpenAsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PrefsManager(requireContext())
        progress = TaskProgress(binding.taskProgress)

        val uri = arguments?.getString(ARG_URI)?.let(Uri::parse)
        if (uri == null) {
            complete(OUTCOME_CANCELLED)
            return
        }

        val queuePosition = arguments?.getInt(ARG_QUEUE_POSITION, 1) ?: 1
        val queueTotal = arguments?.getInt(ARG_QUEUE_TOTAL, 1) ?: 1
        setupQueueControls(queuePosition, queueTotal)

        binding.btnRecommended.setOnClickListener { openDetected() }
        binding.btnSave.setOnClickListener { saveCopy() }
        binding.btnShare.setOnClickListener { share() }
        binding.btnInspect.setOnClickListener { inspect() }
        binding.correctedOpenChip.setOnClickListener { openWithCorrectedName() }

        fileName = uri.lastPathSegment
        val legacyFile = uri.scheme == ContentResolver.SCHEME_FILE
        if (!legacyFile) workingUri = uri
        updateActions()

        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val source = if (legacyFile) bridgeLegacyFile(uri) ?: return@launch else uri
            workingUri = source
            updateActions()
            showInspection(source, FileInspector.inspect(appContext, source, prefs.defaultMime))
        }
    }

    private fun showInspection(uri: Uri, inspection: FileInspector.Inspection) {
        val result = inspection.result
        val name = inspection.displayName
        fileName = name
        fileSize = inspection.size
        detection = result

        binding.titleText.text = when {
            name != null && name.length <= 44 -> name
            name != null -> "${name.take(41)}…"
            else -> "Open as…"
        }
        binding.subtitleText.text =
            "Detected: ${result.fileType.emoji} ${result.fileType.label} • ${result.mime}"
        binding.confidenceText.text =
            "${result.confidence.label} confidence • ${result.source.label}\n${result.evidence}"

        showNameHint(NameCheck.check(name, result))
        setupLastAppChip(uri, name, result.mime)
        setupTypeGrid(uri, name, result)
        updateActions()
    }

    private fun showNameHint(hint: NameCheck.Suggestion?) {
        suggestion = hint
        binding.nameHint.visibility = if (hint == null) View.GONE else View.VISIBLE
        if (hint != null) {
            binding.nameHintText.text = hint.message
            binding.correctedOpenChip.text = "Open as ${hint.suggestedName}"
        }
    }

    private fun setupTypeGrid(uri: Uri, name: String?, result: MimeDetector.DetectionResult) {
        val types = MimeDetector.FileType.entries.filter { it != MimeDetector.FileType.UNKNOWN }
        binding.typeList.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.typeList.adapter = FileTypeAdapter(types, types.indexOf(result.fileType)) { selectedType ->
            val mime = if (selectedType == result.fileType) {
                result.mime
            } else {
                MimeDetector.mimeOf(selectedType, prefs.defaultMime)
            }
            handleOpenResult(
                IntentRouter.open(
                    requireContext(),
                    uri,
                    mime,
                    name,
                    trackChooserResult = isManagedQueue()
                )
            )
        }
    }

    private fun setupQueueControls(position: Int, total: Int) {
        if (total <= 1) return

        binding.queuePositionText.visibility = View.VISIBLE
        binding.queuePositionText.text = "FILE $position OF $total"
        binding.queueActions.visibility = View.VISIBLE
        binding.btnSkipFile.setOnClickListener { complete(OUTCOME_SKIPPED) }
        binding.btnCancelQueue.setOnClickListener { complete(OUTCOME_CANCELLED) }
    }

    private fun openDetected() {
        val uri = workingUri ?: return
        val result = detection ?: return
        handleOpenResult(
            IntentRouter.open(
                requireContext(),
                uri,
                result.mime,
                fileName,
                trackChooserResult = isManagedQueue()
            )
        )
    }

    private fun share() {
        val uri = workingUri ?: return
        val mime = detection?.mime ?: prefs.defaultMime.ifBlank { MimeTypes.OCTET_STREAM }
        IntentRouter.share(requireContext(), uri, mime, fileName)
    }

    private fun saveCopy() {
        val uri = workingUri ?: return
        val name = suggestion?.suggestedName ?: fileName ?: "file"
        saveAction.start(uri, name, fileSize, progress, ::setBusy)
    }

    private fun inspect() {
        val uri = workingUri ?: return
        InspectBottomSheet.newInstance(uri).show(parentFragmentManager, INSPECT_TAG)
    }

    /** Forwards a cached copy under the corrected name, for apps that trust the extension. */
    private fun openWithCorrectedName() {
        val uri = workingUri ?: return
        val hint = suggestion ?: return
        val result = detection ?: return
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true)
            progress.start("Preparing", fileSize)
            try {
                val copy = FileBridge.copy(
                    appContext,
                    uri,
                    hint.suggestedName,
                    fileSize,
                    progress.listener()
                )
                handleOpenResult(
                    IntentRouter.open(
                        requireContext(),
                        copy,
                        result.mime,
                        hint.suggestedName,
                        trackChooserResult = isManagedQueue(),
                        recentUri = uri
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("OpenAsBottomSheet", "Could not copy $uri", e)
                val reason = e.message?.let { ": $it" }.orEmpty()
                Toast.makeText(appContext, "Could not prepare the file$reason", Toast.LENGTH_LONG).show()
            } finally {
                progress.finish()
                setBusy(false)
            }
        }
    }

    /**
     * Copies a legacy file:// source into the cache so it can be forwarded with a
     * content URI. Returns null, and explains why, when the file cannot be read.
     */
    private suspend fun bridgeLegacyFile(uri: Uri): Uri? {
        val appContext = requireContext().applicationContext
        val name = uri.lastPathSegment ?: "file"
        val size = uri.path?.let { File(it).length() }?.takeIf { it > 0 }
        progress.start("Preparing", size)
        return try {
            FileBridge.copy(appContext, uri, name, size, progress.listener())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("OpenAsBottomSheet", "Could not read legacy file URI $uri", e)
            binding.titleText.text = name
            binding.subtitleText.text = "This file can't be read"
            binding.confidenceText.text =
                "The sending app shared a legacy file:// link, which this app has no " +
                    "permission to read. Use Share or Open with in that app instead."
            null
        } finally {
            progress.finish()
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        updateActions()
    }

    private fun updateActions() {
        val currentBinding = _binding ?: return
        val ready = workingUri != null && !busy
        val detected = ready && detection != null
        currentBinding.btnRecommended.setActionEnabled(detected)
        currentBinding.btnSave.setActionEnabled(detected)
        currentBinding.btnShare.setActionEnabled(ready)
        currentBinding.btnInspect.setActionEnabled(ready)
        currentBinding.correctedOpenChip.isEnabled = detected
    }

    private fun View.setActionEnabled(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.38f
    }

    private fun setupLastAppChip(uri: Uri, fileName: String?, mime: String) {
        val context = requireContext()
        val lastPackage = IntentRouter.getLastApp(context, mime) ?: return
        if (lastPackage == context.packageName) return

        try {
            val appInfo = context.packageManager.getApplicationInfo(lastPackage, 0)
            val appLabel = context.packageManager.getApplicationLabel(appInfo)
            val appIcon = context.packageManager.getApplicationIcon(appInfo)

            binding.lastAppChip.apply {
                visibility = View.VISIBLE
                text = "Open again with $appLabel"
                chipIcon = appIcon
                setOnClickListener {
                    val result = IntentRouter.openWithPackage(
                        context,
                        uri,
                        mime,
                        lastPackage,
                        fileName
                    )
                    if (result.status == IntentRouter.OpenStatus.DIRECT_STARTED) {
                        handleOpenResult(result)
                    } else {
                        visibility = View.GONE
                        val fallback = IntentRouter.open(
                            context,
                            uri,
                            mime,
                            fileName,
                            trackChooserResult = isManagedQueue()
                        )
                        handleOpenResult(fallback)
                    }
                }
            }
        } catch (_: Exception) {
            binding.lastAppChip.visibility = View.GONE
        }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
