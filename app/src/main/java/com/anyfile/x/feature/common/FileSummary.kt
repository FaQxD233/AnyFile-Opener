package com.anyfile.x.feature.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anyfile.x.engine.FileInspector
import com.anyfile.x.engine.MimeDetector.FileType
import com.anyfile.x.engine.NameCheck

/** Icon for a detected file type, used by the summary and the "Open as" grid. */
val FileType.icon: ImageVector
    get() = when (this) {
        FileType.VIDEO -> Icons.Outlined.Movie
        FileType.AUDIO -> Icons.Outlined.MusicNote
        FileType.IMAGE -> Icons.Outlined.Image
        FileType.DOCUMENT -> Icons.Outlined.Description
        FileType.ARCHIVE -> Icons.Outlined.Archive
        FileType.APK -> Icons.Outlined.Android
        FileType.TEXT -> Icons.Outlined.Article
        FileType.FONT -> Icons.Outlined.FontDownload
        FileType.UNKNOWN -> Icons.Outlined.InsertDriveFile
    }

/**
 * Name, detected type and size of a file, the evidence behind the detection, and a
 * warning when the extension does not fit the content. [name] shows while detecting.
 */
@Composable
fun FileSummary(
    name: String?,
    inspection: FileInspector.Inspection?,
    suggestion: NameCheck.Suggestion?,
    loading: Boolean,
    modifier: Modifier = Modifier
) {
    val result = inspection?.result
    val size = inspection?.size
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon((result?.fileType ?: FileType.UNKNOWN).icon, contentDescription = null)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                FileNameText(
                    name = name ?: inspection?.displayName ?: "Loading…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when {
                        result == null -> "Detecting…"
                        size != null -> "${result.fileType.label} · ${result.mime} · ${formatFileSize(size)}"
                        else -> "${result.fileType.label} · ${result.mime}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (result != null) {
            Text(
                text = "${result.confidence.label} confidence · ${result.source.label} · ${result.evidence}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (suggestion != null) NameHint(suggestion.message)
    }
}

/** A warning that stands out from the detection details, such as a wrong extension. */
@Composable
fun NameHint(message: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
