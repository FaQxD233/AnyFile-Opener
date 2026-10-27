package com.anyfile.x.feature.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anyfile.x.tasks.FileTasks

/** Background copies with their progress and a cancel button each. */
@Composable
fun TaskRows(tasks: List<FileTasks.Task>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tasks.forEach { task -> TaskRow(task) }
    }
}

@Composable
private fun TaskRow(task: FileTasks.Task) {
    val total = task.total
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row {
                Text("${task.kind.verb} ", style = style, color = color, maxLines = 1)
                FileNameText(task.fileName, Modifier.weight(1f, fill = false), style = style, color = color)
            }
            Text(
                text = if (total != null) {
                    "${formatFileSize(task.done)} / ${formatFileSize(total)}"
                } else {
                    formatFileSize(task.done)
                },
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
            Spacer(Modifier.height(4.dp))
            if (total != null) {
                LinearProgressIndicator(
                    progress = { (task.done.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        IconButton(onClick = { FileTasks.cancel(task.id) }) {
            Icon(Icons.Outlined.Close, contentDescription = "Cancel")
        }
    }
}
