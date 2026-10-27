package com.anyfile.x.feature.common

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.anyfile.x.engine.MimeTypes

/** Splits "name.ext" into "name" and ".ext"; names without a plausible extension stay whole. */
fun splitFileName(name: String): Pair<String, String> {
    val extension = MimeTypes.extensionOf(name)
    if (extension.isEmpty()) return name to ""
    val cut = name.length - extension.length - 1
    return name.substring(0, cut) to name.substring(cut)
}

/** One line that keeps the extension visible when the name is too long: "holiday-vid….mp4". */
@Composable
fun FileNameText(
    name: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null
) {
    val (base, extension) = remember(name) { splitFileName(name) }
    Row(modifier) {
        Text(
            text = base,
            modifier = Modifier.weight(1f, fill = false),
            color = color,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = style
        )
        if (extension.isNotEmpty()) {
            Text(
                text = extension,
                color = color,
                fontWeight = fontWeight,
                maxLines = 1,
                softWrap = false,
                style = style
            )
        }
    }
}
