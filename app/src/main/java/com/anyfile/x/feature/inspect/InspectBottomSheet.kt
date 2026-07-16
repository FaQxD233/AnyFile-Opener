package com.anyfile.x.feature.inspect

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.anyfile.x.databinding.BottomSheetInspectBinding
import com.anyfile.x.engine.MimeDetector
import com.anyfile.x.engine.utils.ByteReader
import com.anyfile.x.routing.IntentRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InspectBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetInspectBinding? = null
    private val binding get() = _binding!!
    
    private var totalBytesRead = 0
    private var fullByteArray = ByteArray(0)

    companion object {
        private const val ARG_URI = "uri"
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

        val uriString = arguments?.getString(ARG_URI) ?: return
        val uri = Uri.parse(uriString)
        val resolver = requireContext().applicationContext.contentResolver
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
            // File name
            val fileName = withContext(Dispatchers.IO) { queryFileName(resolver, uri) }
            resolvedFileName = fileName ?: uri.lastPathSegment
            binding.inspectFileName.text = resolvedFileName ?: "Unknown"

            // File size
            val size = withContext(Dispatchers.IO) { queryFileSize(resolver, uri) }
            binding.inspectSizeText.text = if (size >= 0) formatSize(size) else "Unknown"

            // Initial load
            val loaded = withContext(Dispatchers.IO) { loadBytesInternal(resolver, uri, 256) }
            updateDumpUI()
            if (!loaded) showEndOfFile()

            // Show MIME Detector's header-based detection result
            try {
                val result = MimeDetector.detectFromHeader(
                    fullByteArray,
                    fileName ?: "",
                    contentResolver = resolver,
                    uri = uri
                )
                binding.headerDetectionLabel.visibility = View.VISIBLE
                binding.headerDetectionLabel.text =
                    "${result.confidence.label} confidence • ${result.source.label}\n${result.evidence}"
                binding.inspectMimeText.text = result.mime
                detectedMime = result.mime
            } catch (e: Exception) {
                binding.inspectMimeText.text = "detection failed"
            }
        }

        binding.btnLoadMore.setOnClickListener {
            binding.btnLoadMore.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val remaining = MAX_INSPECT_BYTES - totalBytesRead
                val loaded = if (remaining > 0) {
                    withContext(Dispatchers.IO) {
                        loadBytesInternal(resolver, uri, minOf(512, remaining))
                    }
                } else {
                    false
                }
                updateDumpUI()
                if (!loaded || totalBytesRead >= MAX_INSPECT_BYTES) {
                    showEndOfFile(atLimit = totalBytesRead >= MAX_INSPECT_BYTES)
                } else {
                    binding.btnLoadMore.isEnabled = true
                }
                // Scroll to bottom after loading more
                val currentBinding = _binding ?: return@launch
                val hexScrollView = currentBinding.hexScrollView
                val asciiScrollView = currentBinding.asciiScrollView
                hexScrollView.post { hexScrollView.fullScroll(View.FOCUS_DOWN) }
                asciiScrollView.post { asciiScrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun loadBytesInternal(
        resolver: android.content.ContentResolver,
        uri: Uri,
        count: Int
    ): Boolean {
        val newBytes = ByteReader.readBytes(resolver, uri, totalBytesRead.toLong(), count)
        if (newBytes.isEmpty()) {
            return false
        }

        val updatedArray = ByteArray(fullByteArray.size + newBytes.size)
        System.arraycopy(fullByteArray, 0, updatedArray, 0, fullByteArray.size)
        System.arraycopy(newBytes, 0, updatedArray, fullByteArray.size, newBytes.size)
        fullByteArray = updatedArray
        totalBytesRead += newBytes.size
        return true
    }

    private fun showEndOfFile(atLimit: Boolean = false) {
        binding.btnLoadMore.isEnabled = false
        binding.btnLoadMore.text = if (atLimit) "64 KB inspection limit reached" else "End of file"
    }

    private fun updateDumpUI() {
        binding.hexByteCount.text = "OFFSET | HEX DUMP ($totalBytesRead bytes loaded)"
        binding.hexDumpText.text = buildHexDump(fullByteArray)
        binding.asciiDumpText.text = buildAsciiPreview(fullByteArray)
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

    private fun queryFileName(resolver: android.content.ContentResolver, uri: Uri): String? {
        var name: String? = null
        try {
            val cursor: Cursor? = resolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val col = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (col >= 0) name = it.getString(col)
                }
            }
        } catch (_: Exception) {}
        return name
    }

    private fun queryFileSize(resolver: android.content.ContentResolver, uri: Uri): Long {
        var size = -1L
        try {
            val cursor: Cursor? = resolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val col = it.getColumnIndex(OpenableColumns.SIZE)
                    if (col >= 0 && !it.isNull(col)) size = it.getLong(col)
                }
            }
        } catch (_: Exception) {}
        return size
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
