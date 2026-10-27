package com.anyfile.x.feature.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anyfile.x.engine.MimeTypes
import com.anyfile.x.feature.common.FileNameText
import com.anyfile.x.feature.common.NameHint
import com.anyfile.x.feature.common.formatFileSize
import com.anyfile.x.feature.details.DetailsViewModel.Computation
import com.anyfile.x.files.ApkInfoReader
import com.anyfile.x.files.FileHasher
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Name, type, location, size and time of a file, plus checksums and APK info on request. */
@Composable
fun DetailsContent(
    info: DetailsViewModel.Info?,
    checksums: Computation<FileHasher.Hashes>?,
    apkInfo: Computation<ApkInfoReader.ApkInfo>?,
    onComputeChecksums: () -> Unit,
    onReadApkInfo: () -> Unit,
    onCopy: (label: String, text: String) -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (info == null) {
            Text("Reading file details…", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            FileDetails(info, onCopy)
            HorizontalDivider()
            ChecksumsSection(checksums, onComputeChecksums, onCopy)
            if (info.inspection.result.mime == MimeTypes.APK) {
                HorizontalDivider()
                ApkSection(apkInfo, onReadApkInfo, onCopy)
            }
        }
    }
}

@Composable
private fun FileDetails(info: DetailsViewModel.Info, onCopy: (String, String) -> Unit) {
    val inspection = info.inspection
    val result = inspection.result
    FileNameText(
        inspection.displayName ?: "Unknown file",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold
    )
    Field("Type") {
        Text("${result.fileType.label} · ${result.mime}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "${result.confidence.label} confidence · ${result.source.label} · ${result.evidence}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    info.suggestion?.let { NameHint("${it.message}. Suggested name: ${it.suggestedName}") }

    // Only some providers reveal a path; otherwise the URI is what identifies the file.
    val location = info.path ?: info.readableUri
    val locationLabel = if (info.path != null) "Path" else "URI"
    Field(locationLabel) {
        SelectionContainer {
            Text(location, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { onCopy(locationLabel, location) }) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (info.path != null) "Copy path" else "Copy URI")
        }
    }
    Field("Size") {
        Text(
            inspection.size?.let {
                "${formatFileSize(it)} (${String.format(Locale.getDefault(), "%,d", it)} bytes)"
            } ?: "Unknown",
            style = MaterialTheme.typography.bodyLarge
        )
    }
    Field("Modified") {
        Text(info.modified?.let(::formatDateTime) ?: "Unknown", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ChecksumsSection(
    state: Computation<FileHasher.Hashes>?,
    onStart: () -> Unit,
    onCopy: (String, String) -> Unit
) {
    Field("Checksums") {
        when (state) {
            null -> OutlinedButton(onClick = onStart) { Text("Compute MD5 and SHA-256") }
            is Computation.Running -> Progress("Computing", state)
            is Computation.Done -> {
                CopyableValue("MD5", state.value.md5, onCopy)
                CopyableValue("SHA-256", state.value.sha256, onCopy)
            }
            is Computation.Failed -> Failure(state.message, onStart)
        }
    }
}

@Composable
private fun ApkSection(
    state: Computation<ApkInfoReader.ApkInfo>?,
    onStart: () -> Unit,
    onCopy: (String, String) -> Unit
) {
    Field("APK") {
        when (state) {
            null -> OutlinedButton(onClick = onStart) { Text("Read package details") }
            is Computation.Running -> Progress("Reading", state)
            is Computation.Done -> ApkDetails(state.value, onCopy)
            is Computation.Failed -> Failure(state.message, onStart)
        }
    }
}

@Composable
private fun ApkDetails(apk: ApkInfoReader.ApkInfo, onCopy: (String, String) -> Unit) {
    apk.label?.let { Line("Name", it) }
    Line("Package", apk.packageName)
    Line("Version", "${apk.versionName ?: "?"} (${apk.versionCode})")
    Line("SDK", "min ${apk.minSdk ?: "?"}, target ${apk.targetSdk ?: "?"}")
    if (apk.signers.isEmpty()) Line("Signing certificate", "Not available")
    apk.signers.forEachIndexed { index, fingerprint ->
        val number = if (apk.signers.size > 1) " ${index + 1}" else ""
        CopyableValue("Signing certificate$number (SHA-256)", fingerprint, onCopy)
    }
    // Key rotation: certificates this one replaced, which the APK still vouches for.
    apk.previousSigners.forEachIndexed { index, fingerprint ->
        val number = if (apk.previousSigners.size > 1) " ${index + 1}" else ""
        CopyableValue("Earlier certificate$number, before key rotation", fingerprint, onCopy)
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        content()
    }
}

@Composable
private fun Line(label: String, value: String) {
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun CopyableValue(label: String, value: String, onCopy: (String, String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SelectionContainer {
                Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        IconButton(onClick = { onCopy(label, value) }) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy $label")
        }
    }
}

@Composable
private fun Progress(label: String, state: Computation.Running) {
    val total = state.total
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (total != null && total > 0) {
                "$label… ${state.done * 100 / total}% (${formatFileSize(state.done)} / ${formatFileSize(total)})"
            } else {
                "$label… ${formatFileSize(state.done)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (total != null && total > 0) {
            LinearProgressIndicator(
                progress = { (state.done.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Failure(message: String, onRetry: () -> Unit) {
    Text(
        "Failed: $message",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
    TextButton(onClick = onRetry) { Text("Try again") }
}

private fun formatDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
