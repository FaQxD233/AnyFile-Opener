package com.anyfile.x.feature.common

import android.os.Bundle
import androidx.core.os.bundleOf
import com.anyfile.x.engine.FileInspector
import com.anyfile.x.engine.MimeDetector

/** Hands a finished inspection to a sheet, so the sheet does not detect the file again. */
fun FileInspector.Inspection.toBundle(): Bundle = bundleOf(
    KEY_NAME to displayName,
    KEY_SIZE to (size ?: -1L),
    KEY_TYPE to result.fileType.name,
    KEY_MIME to result.mime,
    KEY_CONFIDENCE to result.confidence.name,
    KEY_SOURCE to result.source.name,
    KEY_EVIDENCE to result.evidence,
    KEY_EXTENSION_HINT to result.extensionHint
)

fun Bundle.toInspection(): FileInspector.Inspection? {
    val mime = getString(KEY_MIME) ?: return null
    val result = MimeDetector.DetectionResult(
        fileType = enumOrNull<MimeDetector.FileType>(getString(KEY_TYPE)) ?: return null,
        mime = mime,
        confidence = enumOrNull<MimeDetector.DetectionConfidence>(getString(KEY_CONFIDENCE)) ?: return null,
        source = enumOrNull<MimeDetector.DetectionSource>(getString(KEY_SOURCE)) ?: return null,
        evidence = getString(KEY_EVIDENCE).orEmpty(),
        extensionHint = getString(KEY_EXTENSION_HINT)
    )
    return FileInspector.Inspection(getString(KEY_NAME), getLong(KEY_SIZE, -1L).takeIf { it >= 0 }, result)
}

private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
    enumValues<T>().firstOrNull { it.name == name }

private const val KEY_NAME = "name"
private const val KEY_SIZE = "size"
private const val KEY_TYPE = "type"
private const val KEY_MIME = "mime"
private const val KEY_CONFIDENCE = "confidence"
private const val KEY_SOURCE = "source"
private const val KEY_EVIDENCE = "evidence"
private const val KEY_EXTENSION_HINT = "extension_hint"
