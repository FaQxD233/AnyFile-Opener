package com.anyfile.x.feature.openas

import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.anyfile.x.data.DefaultAppRuleScope
import com.anyfile.x.data.DefaultAppRuleStore
import com.anyfile.x.data.PrefsManager
import com.anyfile.x.databinding.BottomSheetOpenAsBinding
import com.anyfile.x.engine.MimeDetector
import com.anyfile.x.routing.IntentRouter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Displays one item in the incoming file queue. */
class OpenAsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetOpenAsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PrefsManager
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

        val uri = arguments?.getString(ARG_URI)?.let(Uri::parse)
        if (uri == null) {
            complete(OUTCOME_CANCELLED)
            return
        }

        val queuePosition = arguments?.getInt(ARG_QUEUE_POSITION, 1) ?: 1
        val queueTotal = arguments?.getInt(ARG_QUEUE_TOTAL, 1) ?: 1
        setupQueueControls(queuePosition, queueTotal)

        var resolvedFileName: String? = uri.lastPathSegment
        var detectedMime = prefs.defaultMime.ifBlank { "application/octet-stream" }

        binding.btnShare.setOnClickListener {
            IntentRouter.share(
                requireContext(),
                uri,
                detectedMime,
                resolvedFileName
            )
        }

        val resolver = requireContext().contentResolver
        viewLifecycleOwner.lifecycleScope.launch {
            val fileName = withContext(Dispatchers.IO) {
                resolveFileName(resolver, uri) ?: uri.lastPathSegment
            }
            resolvedFileName = fileName
            val detectedResult = withContext(Dispatchers.IO) {
                MimeDetector.detect(
                    resolver,
                    uri,
                    fileName,
                    prefs.defaultMime
                )
            }
            detectedMime = detectedResult.mime

            binding.titleText.text = when {
                fileName != null && fileName.length <= 44 -> fileName
                fileName != null -> "${fileName.take(41)}…"
                else -> "Open as…"
            }
            binding.subtitleText.text =
                "Detected: ${detectedResult.fileType.emoji} ${detectedResult.fileType.label} • ${detectedResult.mime}"
            binding.confidenceText.text =
                "${detectedResult.confidence.label} confidence • ${detectedResult.source.label}\n${detectedResult.evidence}"

            setupDefaultRuleSummary(fileName, detectedResult.mime)
            setupLastAppChip(uri, fileName, detectedResult.mime)
            // Default-rule setup actions stay hidden in the open sheet; manage rules in Settings.
            binding.defaultRuleActions.visibility = View.GONE

            val types = MimeDetector.FileType.entries.filter { it != MimeDetector.FileType.UNKNOWN }
            val detectedIndex = types.indexOf(detectedResult.fileType)
            binding.typeList.layoutManager = GridLayoutManager(requireContext(), 3)
            binding.typeList.adapter = FileTypeAdapter(types, detectedIndex) { selectedType ->
                val mime = if (selectedType == detectedResult.fileType) {
                    detectedResult.mime
                } else {
                    MimeDetector.mimeOf(selectedType, prefs.defaultMime)
                }
                handleOpenResult(
                    IntentRouter.open(
                        requireContext(),
                        uri,
                        mime,
                        fileName,
                        trackChooserResult = isManagedQueue()
                    )
                )
            }
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

    private fun setupDefaultRuleSummary(fileName: String?, mime: String) {
        val rule = DefaultAppRuleStore.findRule(requireContext(), mime, fileName) ?: return
        val label = DefaultAppRuleStore.appLabel(requireContext(), rule.packageName)
        val key = if (rule.scope == DefaultAppRuleScope.EXTENSION) ".${rule.key}" else rule.key
        binding.defaultRuleText.visibility = View.VISIBLE
        binding.defaultRuleText.text = "Default rule: $key → $label"
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
                            useDefaultRule = false,
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

    private fun resolveFileName(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): String? = try {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        Log.e("OpenAsBottomSheet", "Could not resolve display name for $uri", e)
        null
    }
}
