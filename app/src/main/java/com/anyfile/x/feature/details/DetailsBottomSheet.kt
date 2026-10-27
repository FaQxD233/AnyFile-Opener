package com.anyfile.x.feature.details

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anyfile.x.data.PrefsManager
import com.anyfile.x.engine.FileInspector
import com.anyfile.x.feature.common.ComposeBottomSheet
import com.anyfile.x.feature.common.toBundle
import com.anyfile.x.feature.common.toInspection

/** File details: name, type, path, size, modification time, checksums and APK info. */
class DetailsBottomSheet : ComposeBottomSheet() {

    private val viewModel: DetailsViewModel by viewModels()

    companion object {
        private const val ARG_URI = "uri"
        private const val ARG_PRESET = "preset"

        /** [preset] skips detecting the file again. */
        fun newInstance(uri: Uri, preset: FileInspector.Inspection? = null): DetailsBottomSheet =
            DetailsBottomSheet().apply {
                arguments = bundleOf(ARG_URI to uri.toString(), ARG_PRESET to preset?.toBundle())
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = arguments?.getString(ARG_URI)?.let(Uri::parse) ?: return
        viewModel.load(
            uri,
            arguments?.getBundle(ARG_PRESET)?.toInspection(),
            PrefsManager(requireContext()).defaultMime
        )
    }

    @Composable
    override fun SheetContent() {
        val info by viewModel.info.collectAsStateWithLifecycle()
        val checksums by viewModel.checksums.collectAsStateWithLifecycle()
        val apkInfo by viewModel.apkInfo.collectAsStateWithLifecycle()
        DetailsContent(
            info = info,
            checksums = checksums,
            apkInfo = apkInfo,
            onComputeChecksums = viewModel::computeChecksums,
            onReadApkInfo = viewModel::readApkInfo,
            onCopy = ::copyToClipboard
        )
    }

    private fun copyToClipboard(label: String, text: String) {
        val context = context ?: return
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
    }
}
