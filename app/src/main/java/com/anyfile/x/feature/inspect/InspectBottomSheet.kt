package com.anyfile.x.feature.inspect

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.anyfile.x.data.PrefsManager
import com.anyfile.x.databinding.BottomSheetInspectBinding
import com.anyfile.x.engine.FileInspector
import com.anyfile.x.engine.utils.ByteReader
import com.anyfile.x.feature.common.formatFileSize
import com.anyfile.x.routing.IntentRouter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class InspectBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetInspectBinding? = null
    private val binding get() = _binding!!

    private var loadedBytes = ByteArray(0)

    companion object {
        private const val ARG_URI = "uri"
        private const val INITIAL_BYTES = 256
        private const val PAGE_BYTES = 512
        private const val MAX_INSPECT_BYTES = 64 * 1024

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
        var resolvedFileName: String? = null
        var detectedMime = "application/octet-stream"

        binding.btnShare.setOnClickListener {
            IntentRouter.share(
                requireContext(),
                uri,
                detectedMime,
                resolvedFileName
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val inspection = FileInspector.inspect(appContext, uri, fallbackMime)
            val result = inspection.result
            resolvedFileName = inspection.displayName
            detectedMime = result.mime
            binding.inspectFileName.text = inspection.displayName ?: "Unknown"
            binding.inspectSizeText.text = inspection.size?.let(::formatFileSize) ?: "Unknown"
            binding.inspectMimeText.text = result.mime
            binding.headerDetectionLabel.visibility = View.VISIBLE
            binding.headerDetectionLabel.text =
                "${result.confidence.label} confidence • ${result.source.label}\n${result.evidence}"
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
        binding.btnLoadMore.text = if (atLimit) "64 KB inspection limit reached" else "End of file"
    }

    private fun updateDumpUI() {
        binding.hexByteCount.text = "OFFSET | HEX DUMP (${loadedBytes.size} bytes loaded)"
        binding.hexDumpText.text = buildHexDump(loadedBytes)
        binding.asciiDumpText.text = buildAsciiPreview(loadedBytes)
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
