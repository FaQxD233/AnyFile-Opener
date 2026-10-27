package com.anyfile.x.feature.common

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.anyfile.x.engine.MimeDetector
import com.anyfile.x.routing.IntentRouter

/** The app last picked for a file's type, offered as an "Open with <app>" shortcut. */
data class LastApp(val packageName: String, val label: String, val icon: ImageBitmap?) {
    companion object {
        private const val ICON_DP = 24

        /** Reads the package manager, so call it off the main thread. */
        fun find(context: Context, uri: Uri, result: MimeDetector.DetectionResult): LastApp? {
            val packageName = IntentRouter.lastAppFor(context, result) ?: return null
            if (packageName == context.packageName) return null
            // The app may be gone, or may have been picked for a broader type than this file's.
            if (!IntentRouter.canOpen(context, uri, result.mime, packageName)) return null
            return try {
                val packageManager = context.packageManager
                val info = packageManager.getApplicationInfo(packageName, 0)
                val iconSize = (ICON_DP * context.resources.displayMetrics.density).toInt()
                LastApp(
                    packageName = packageName,
                    label = packageManager.getApplicationLabel(info).toString(),
                    icon = runCatching {
                        packageManager.getApplicationIcon(info).toBitmap(iconSize, iconSize).asImageBitmap()
                    }.getOrNull()
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * The actions for one file, shared by the home screen and the "Open as" sheet: open
 * with the detected type, open with the last app, open under the corrected name, then
 * share, save a copy and show details.
 */
@Composable
fun FileActions(
    enabled: Boolean,
    lastApp: LastApp?,
    correctedName: String?,
    onOpen: () -> Unit,
    onOpenWithApp: () -> Unit,
    onOpenCorrected: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onOpen, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Open")
        }
        if (lastApp != null) {
            OutlinedButton(onClick = onOpenWithApp, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                lastApp.icon?.let {
                    Image(it, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text("Open with ${lastApp.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (correctedName != null) {
            OutlinedButton(onClick = onOpenCorrected, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("Open as ", maxLines = 1)
                FileNameText(correctedName, modifier = Modifier.weight(1f, fill = false))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryAction(Icons.Outlined.Share, "Share", enabled, onShare)
            SecondaryAction(Icons.Outlined.SaveAlt, "Save", enabled, onSave)
            SecondaryAction(Icons.Outlined.Info, "Details", enabled, onDetails)
        }
    }
}

@Composable
private fun RowScope.SecondaryAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
