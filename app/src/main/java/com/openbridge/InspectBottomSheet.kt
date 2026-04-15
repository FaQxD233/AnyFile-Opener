package com.openbridge

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.openbridge.databinding.BottomSheetInspectBinding
import com.openbridge.utils.ByteReader

class InspectBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetInspectBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val ARG_URI = "uri"

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

        // File name
        val fileName = queryFileName(uri)
        binding.inspectFileName.text = fileName ?: uri.lastPathSegment ?: "Unknown"

        // File size
        val size = queryFileSize(uri)
        binding.inspectSizeText.text = if (size >= 0) formatSize(size) else "Unknown"

        // Detect MIME
        try {
            val result = MimeDetector.detect(requireContext().contentResolver, uri, fileName)
            binding.inspectMimeText.text = result.mime
        } catch (e: Exception) {
            binding.inspectMimeText.text = "detection failed"
        }

        // Hex dump
        val bytes = ByteReader.readHeader(requireContext().contentResolver, uri, 256)
        binding.hexByteCount.text = "(first ${bytes.size} bytes)"
        binding.hexDumpText.text = buildHexDump(bytes)
        binding.asciiDumpText.text = buildAsciiPreview(bytes)
    }

    private fun buildHexDump(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (i in bytes.indices step 16) {
            val offsetStr = "%04X".format(i)
            sb.append(offsetStr).append("  ")
            
            for (j in 0 until 16) {
                if (i + j < bytes.size) {
                    sb.append("%02X ".format(bytes[i + j].toInt() and 0xFF))
                } else {
                    sb.append("   ")
                }
            }
            if (i + 16 < bytes.size) sb.append("\n")
        }
        return sb.toString()
    }

    private fun buildAsciiPreview(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (i in bytes.indices step 16) {
            val end = minOf(i + 16, bytes.size)
            for (j in i until end) {
                val b = bytes[j].toInt() and 0xFF
                if (b in 32..126) sb.append(b.toChar()) else sb.append(".")
            }
            if (i + 16 < bytes.size) sb.append("\n")
        }
        return sb.toString()
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        try {
            val cursor: Cursor? = requireContext().contentResolver.query(
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

    private fun queryFileSize(uri: Uri): Long {
        var size = -1L
        try {
            val cursor: Cursor? = requireContext().contentResolver.query(
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
