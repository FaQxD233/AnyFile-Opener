package com.anyfile.x.feature.openas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anyfile.x.engine.MimeDetector.FileType
import com.anyfile.x.feature.common.FileActions
import com.anyfile.x.feature.common.FileNameText
import com.anyfile.x.feature.common.FileSummary
import com.anyfile.x.feature.common.TaskRows
import com.anyfile.x.feature.common.icon
import com.anyfile.x.tasks.FileTasks

/**
 * One file of the incoming queue: summary, actions and the type grid. With [typesOnly]
 * (opened from the home screen, which has the actions itself) only the grid remains.
 */
@Composable
fun OpenAsContent(
    state: OpenAsViewModel.State,
    queuePosition: Int,
    queueTotal: Int,
    typesOnly: Boolean,
    tasks: List<FileTasks.Task>,
    busy: Boolean,
    onOpen: () -> Unit,
    onOpenWithApp: () -> Unit,
    onOpenCorrected: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDetails: () -> Unit,
    onOpenAsType: (FileType) -> Unit,
    onSkip: () -> Unit,
    onCancelQueue: () -> Unit
) {
    val ready = state.workingUri != null && state.inspection != null && !busy
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (queueTotal > 1) {
            Text(
                "File $queuePosition of $queueTotal",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (state.unreadable) {
            UnreadableFile(state.name)
        } else {
            FileSummary(
                name = state.name,
                inspection = state.inspection,
                suggestion = state.suggestion,
                loading = state.inspection == null
            )
            if (!typesOnly) {
                FileActions(
                    enabled = ready,
                    lastApp = state.lastApp,
                    correctedName = state.suggestion?.suggestedName,
                    onOpen = onOpen,
                    onOpenWithApp = onOpenWithApp,
                    onOpenCorrected = onOpenCorrected,
                    onShare = onShare,
                    onSave = onSave,
                    onDetails = onDetails
                )
            }
            if (tasks.isNotEmpty()) TaskRows(tasks)
            Column {
                Text("Open as type", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                TypeGrid(
                    detected = state.inspection?.result?.fileType,
                    enabled = ready,
                    onSelect = onOpenAsType
                )
            }
        }
        if (queueTotal > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancelQueue, enabled = !busy) { Text("Cancel queue") }
                TextButton(onClick = onSkip, enabled = !busy) { Text("Skip file") }
            }
        }
    }
}

@Composable
private fun UnreadableFile(name: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FileNameText(
            name ?: "file",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text("This file can't be read", style = MaterialTheme.typography.bodyMedium)
        Text(
            "The sending app shared a legacy file:// link, which this app has no permission " +
                "to read. Use Share or Open with in that app instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The file categories; the detected one is highlighted and opens with the exact type. */
@Composable
private fun TypeGrid(detected: FileType?, enabled: Boolean, onSelect: (FileType) -> Unit) {
    val rows = remember { FileType.entries.filter { it != FileType.UNKNOWN }.chunked(COLUMNS) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { type ->
                    TypeCell(
                        type = type,
                        selected = type == detected,
                        enabled = enabled,
                        onClick = { onSelect(type) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TypeCell(
    type: FileType,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val colors = if (selected) {
        CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    } else {
        CardDefaults.outlinedCardColors()
    }
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = colors,
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            CardDefaults.outlinedCardBorder(enabled)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(type.icon, contentDescription = null)
            Spacer(Modifier.height(4.dp))
            Text(type.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

private const val COLUMNS = 4
