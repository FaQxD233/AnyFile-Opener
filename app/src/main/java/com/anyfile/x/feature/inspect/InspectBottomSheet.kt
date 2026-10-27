package com.anyfile.x.feature.inspect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.content.DialogInterface
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import com.anyfile.x.R
import com.anyfile.x.data.PrefsManager
import com.anyfile.x.databinding.BottomSheetInspectBinding
import com.anyfile.x.engine.FileInspector
import com.anyfile.x.engine.MimeDetector
import com.anyfile.x.engine.MimeTypes
import com.anyfile.x.engine.NameCheck
import com.anyfile.x.engine.TextEncoding
import com.anyfile.x.engine.utils.ByteReader
import com.anyfile.x.feature.common.SaveCopyAction
import com.anyfile.x.feature.common.TaskProgress
import com.anyfile.x.feature.common.formatFileSize
import com.anyfile.x.files.ApkInfoReader
import com.anyfile.x.files.FileHasher
import com.anyfile.x.routing.IntentRouter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class InspectBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetInspectBinding? = null
    private val binding get() = _binding!!
    private val saveAction = SaveCopyAction(this)

    private var loadedBytes = ByteArray(0)

    /** First bytes of the file for the text preview, read on first use. */
    private var textBytes: ByteArray? = null
    private var textIsWholeFile = false
    /** Encoding picked by the user; null means automatic detection. */
    private var manualEncoding: Pair<String, Charset>? = null

    companion object {
        private const val ARG_URI = "uri"
        private const val INITIAL_BYTES = 256
        private const val PAGE_BYTES = 512
        private const val MAX_INSPECT_BYTES = 64 * 1024
        private const val TEXT_PREVIEW_BYTES = 64 * 1024

        fun newInstance(uri: Uri): InspectBottomSheet {
            return InspectBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_URI, uri.toString())
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetInspectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val uri = arguments?.getString(ARG_URI)?.let(Uri::parse) ?: return
        val appContext = requireContext().applicationContext
        val fallbackMime = PrefsManager(appContext).defaultMime
        val progress = TaskProgress(binding.taskProgress)
        var inspection: FileInspector.Inspection? = null
        var saveName: String? = null

        binding.btnShare.setOnClickListener {
            IntentRouter.share(
                requireContext(),
                uri,
                inspection?.result?.mime ?: MimeTypes.OCTET_STREAM,
                inspection?.displayName
            )
        }
        binding.btnSave.setOnClickListener {
            val current = inspection ?: return@setOnClickListener
            saveAction.start(uri, saveName ?: "file", current.size)
        }
        binding.btnChecksums.setOnClickListener {
            showChecksums(appContext, uri, inspection?.size)
        }
        binding.btnApkInfo.setOnClickListener {
            showApkInfo(appContext, uri, inspection?.size)
        }
        binding.encodingSelector.setOnClickListener { showEncodingMenu(it) }
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) showTextMode(checkedId == R.id.btnModeText, appContext, uri)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val current = FileInspector.inspect(appContext, uri, fallbackMime)
            val result = current.result
            val hint = NameCheck.check(current.displayName, result)
            inspection = current
            saveName = hint?.suggestedName ?: current.displayName
            binding.inspectFileName.text = current.displayName ?: "Unknown"
            binding.inspectSizeText.text = current.size?.let(::formatFileSize) ?: "Unknown"
            binding.inspectMimeText.text = result.mime
            binding.headerDetectionLabel.visibility = View.VISIBLE
            binding.headerDetectionLabel.text =
                "${result.confidence.label} confidence • ${result.source.label}\n${result.evidence}"
            binding.nameHintText.visibility = if (hint == null) View.GONE else View.VISIBLE
            binding.nameHintText.text = hint?.let { "${it.message} → ${it.suggestedName}" }
            binding.btnSave.isEnabled = true
            binding.btnApkInfo.visibility =
                if (result.mime == MimeTypes.APK) View.VISIBLE else View.GONE
            if (result.fileType == MimeDetector.FileType.TEXT) {
                binding.modeToggle.check(R.id.btnModeText)
            }
        }

        binding.btnLoadMore.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            loadNext(appContext, uri, INITIAL_BYTES)
        }

        binding.btnLoadMore.setOnClickListener {
            binding.btnLoadMore.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                loadNext(appContext, uri, PAGE_BYTES)
                val currentBinding = _binding ?: return@launch
                val hexScrollView = currentBinding.hexScrollView
                val asciiScrollView = currentBinding.asciiScrollView
                hexScrollView.post { hexScrollView.fullScroll(View.FOCUS_DOWN) }
                asciiScrollView.post { asciiScrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    /** Appends the next chunk of the file to the dump, within the inspection limit. */
    private suspend fun loadNext(context: Context, uri: Uri, count: Int) {
        val requested = minOf(count, MAX_INSPECT_BYTES - loadedBytes.size)
        val offset = loadedBytes.size.toLong()
        val chunk = if (requested > 0) {
            FileInspector.withIoTimeout(FileInspector.DETECTION_TIMEOUT_MS) {
                ByteReader.readBytes(context.contentResolver, uri, offset, requested)
            } ?: ByteArray(0)
        } else {
            ByteArray(0)
        }
        loadedBytes += chunk
        updateDumpUI()

        val atLimit = loadedBytes.size >= MAX_INSPECT_BYTES
        if (chunk.size < requested || atLimit) {
            showEndOfFile(atLimit)
        } else {
            binding.btnLoadMore.isEnabled = true
        }
    }

    private fun showEndOfFile(atLimit: Boolean = false) {
        binding.btnLoadMore.isEnabled = false
        binding.btnLoadMore.text = if (atLimit) "64 KB limit" else "End of file"
    }

    private fun updateDumpUI() {
        if (binding.textContainer.visibility != View.VISIBLE) {
            binding.hexByteCount.text = "OFFSET | HEX DUMP (${loadedBytes.size} bytes loaded)"
        }
        binding.hexDumpText.text = buildHexDump(loadedBytes)
        binding.asciiDumpText.text = buildAsciiPreview(loadedBytes)
    }

    private fun showTextMode(text: Boolean, context: Context, uri: Uri) {
        binding.hexContainer.visibility = if (text) View.GONE else View.VISIBLE
        binding.textContainer.visibility = if (text) View.VISIBLE else View.GONE
        binding.btnLoadMore.visibility = if (text) View.GONE else View.VISIBLE
        if (!text) {
            updateDumpUI()
            return
        }
        binding.hexByteCount.text = "TEXT PREVIEW (FIRST 64 KB)"
        if (textBytes == null) {
            binding.textPreview.text = "Loading…"
            viewLifecycleOwner.lifecycleScope.launch {
                // One byte past the limit tells whether the preview covers the whole file.
                val bytes = FileInspector.withIoTimeout(FileInspector.DETECTION_TIMEOUT_MS) {
                    ByteReader.readBytes(context.contentResolver, uri, 0, TEXT_PREVIEW_BYTES + 1)
                } ?: ByteArray(0)
                textIsWholeFile = bytes.size <= TEXT_PREVIEW_BYTES
                textBytes = bytes.copyOf(minOf(bytes.size, TEXT_PREVIEW_BYTES))
                renderText()
            }
        }
    }

    private fun renderText() {
        val bytes = textBytes ?: return
        val detected = TextEncoding.detect(bytes)
        val manual = manualEncoding
        val charset = manual?.second ?: detected.charset
        val skip = if (manual == null) detected.bomLength else 0
        binding.encodingSelector.text =
            "Encoding: ${manual?.first ?: "Auto · ${detected.label}"} ▾"
        if (bytes.isEmpty()) {
            binding.textPreview.text = "(empty)"
            return
        }
        val text = TextEncoding.decode(bytes, charset, skip, bytes.size - skip, endOfInput = textIsWholeFile)
        binding.textPreview.text = if (textIsWholeFile) {
            text
        } else {
            "$text\n\n… preview limited to the first 64 KB"
        }
    }

    private fun showEncodingMenu(anchor: View) {
        val menu = PopupMenu(requireContext(), anchor)
        menu.menu.add(0, 0, 0, "Auto")
        TextEncoding.choices.forEachIndexed { index, (label, _) ->
            menu.menu.add(0, index + 1, index + 1, label)
        }
        menu.setOnMenuItemClickListener { item ->
            manualEncoding = if (item.itemId == 0) null else TextEncoding.choices[item.itemId - 1]
            renderText()
            true
        }
        menu.show()
    }

    private fun showChecksums(context: Context, uri: Uri, size: Long?) {
        showResultDialog("Checksums", "Computing", size) { onProgress ->
            val hashes = FileHasher.hash(context, uri, size, onProgress)
            "MD5\n${hashes.md5}\n\nSHA-256\n${hashes.sha256}"
        }
    }

    private fun showApkInfo(context: Context, uri: Uri, size: Long?) {
        showResultDialog("APK info", "Reading", size) { onProgress ->
            val info = ApkInfoReader.read(context, uri, size, onProgress)
            buildString {
                info.label?.let { append("Name: ").append(it).append('\n') }
                append("Package: ").append(info.packageName).append('\n')
                append("Version: ").append(info.versionName ?: "?")
                    .append(" (").append(info.versionCode).append(")\n")
                append("SDK: min ").append(info.minSdk ?: "?")
                    .append(", target ").append(info.targetSdk ?: "?")
                if (info.signers.isEmpty()) {
                    append("\n\nSigning certificate: not available")
                }
                info.signers.forEachIndexed { index, fingerprint ->
                    val number = if (info.signers.size > 1) " ${index + 1}" else ""
                    append("\n\nSigning certificate$number (SHA-256)\n").append(fingerprint)
                }
            }
        }
    }

    /**
     * Runs [work] with progress in a dialog, then shows its text with a Copy button.
     * Closing the dialog cancels the work.
     */
    private fun showResultDialog(
        title: String,
        progressLabel: String,
        size: Long?,
        work: suspend (onProgress: (Long, Long?) -> Unit) -> String
    ) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage("$progressLabel…")
            .setPositiveButton("Copy", null)
            .setNegativeButton("Close", null)
            .create()
        var resultText: String? = null
        var finished = false
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.apply {
                isEnabled = resultText != null
                setOnClickListener {
                    resultText?.let { copyToClipboard(title, it) }
                    dialog.dismiss()
                }
            }
        }
        val job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val text = work { done, _ ->
                    dialog.window?.decorView?.post {
                        if (!finished) dialog.setMessage(progressMessage(progressLabel, done, size))
                    }
                }
                finished = true
                resultText = text
                dialog.setMessage(text)
                dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finished = true
                dialog.setMessage("Failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        dialog.setOnDismissListener { job.cancel() }
        dialog.show()
    }

    private fun progressMessage(label: String, done: Long, size: Long?): String =
        if (size != null && size > 0) {
            "$label… ${done * 100 / size}% (${formatFileSize(done)} / ${formatFileSize(size)})"
        } else {
            "$label… ${formatFileSize(done)}"
        }

    private fun copyToClipboard(label: String, text: String) {
        val context = context ?: return
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun buildHexDump(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (i in bytes.indices step 16) {
            // Offset ruler column
            sb.append("%08X | ".format(i))

            for (j in 0 until 16) {
                if (i + j < bytes.size) {
                    sb.append("%02X ".format(bytes[i + j].toInt() and 0xFF))
                } else {
                    sb.append("   ")
                }
                if (j == 7) sb.append(" ") // Small gap mid-way
            }
            if (i + 16 < bytes.size) sb.append("\n")
        }
        return sb.toString()
    }

    private fun buildAsciiPreview(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (i in bytes.indices step 16) {
            sb.append("%08X | ".format(i))
            val end = minOf(i + 16, bytes.size)
            for (j in i until end) {
                val b = bytes[j].toInt() and 0xFF
                if (b in 32..126) sb.append(b.toChar()) else sb.append(".")
                if (j - i == 7) sb.append(" ")
            }
            if (i + 16 < bytes.size) sb.append("\n")
        }
        return sb.toString()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
