package com.anyfile.x.feature.common

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import com.anyfile.x.ui.AnyFileOpenerTheme
import com.anyfile.x.ui.appColorScheme
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * A bottom sheet with Compose content in the app theme. It opens fully expanded, paints
 * its container in the theme's sheet color and can refuse to close while busy.
 */
abstract class ComposeBottomSheet : BottomSheetDialogFragment() {

    /** The content below the drag handle. */
    @Composable
    protected abstract fun SheetContent()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AnyFileOpenerTheme {
                // Lets dragging the sheet and scrolling a long sheet work together.
                val nestedScroll = rememberNestedScrollInteropConnection()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .nestedScroll(nestedScroll)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp)
                ) {
                    DragHandle()
                    SheetContent()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sheetDialog = dialog as? BottomSheetDialog ?: return
        // The container is a View; give it the color the Compose theme uses for sheets.
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val color = appColorScheme(night).surfaceContainerLow.toArgb()
        sheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
            ViewCompat.setBackgroundTintList(it, ColorStateList.valueOf(color))
        }
        sheetDialog.behavior.skipCollapsed = true
        sheetDialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    /** While busy, back, taps outside and dragging down do not close the sheet. */
    protected fun setBusy(busy: Boolean) {
        isCancelable = !busy
    }
}

@Composable
private fun DragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )
    }
}
