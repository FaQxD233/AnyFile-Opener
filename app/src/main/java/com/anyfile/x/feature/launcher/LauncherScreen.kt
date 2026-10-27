package com.anyfile.x.feature.launcher

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.anyfile.x.data.PrefsManager
import com.anyfile.x.data.RecentFile
import com.anyfile.x.data.RecentFileStore
import com.anyfile.x.engine.FileInspector
import com.anyfile.x.engine.MimeTypes
import com.anyfile.x.engine.NameCheck
import com.anyfile.x.feature.common.FileActions
import com.anyfile.x.feature.common.FileNameText
import com.anyfile.x.feature.common.FileSummary
import com.anyfile.x.feature.common.RenamedOpenEffect
import com.anyfile.x.feature.common.TaskRows
import com.anyfile.x.feature.common.rememberNotificationRequest
import com.anyfile.x.feature.common.rememberSaveCopy
import com.anyfile.x.routing.IntentRouter
import com.anyfile.x.tasks.FileTasks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    viewModel: LauncherViewModel,
    prefsManager: PrefsManager,
    onSettingsClick: () -> Unit,
    onOpenAsClick: (Uri, FileInspector.Inspection?) -> Unit,
    onDetailsClick: (Uri, FileInspector.Inspection?) -> Unit,
    onSystemFilesClick: () -> Unit
) {
    val context = LocalContext.current
    val detectionResult by viewModel.detectionResult.collectAsStateWithLifecycle()
    val isDetecting by viewModel.isDetecting.collectAsStateWithLifecycle()
    val lastApp by viewModel.lastApp.collectAsStateWithLifecycle()
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val tasks by FileTasks.tasks.collectAsStateWithLifecycle()
    val pendingRename by viewModel.renamedOpener.pending.collectAsStateWithLifecycle()

    var selectedUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var fileName by rememberSaveable { mutableStateOf("") }
    var manualMime by rememberSaveable { mutableStateOf("") }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var selectionVersion by rememberSaveable { mutableIntStateOf(0) }
    var autoOpenPending by rememberSaveable { mutableStateOf(false) }
    val selectedUri = remember(selectedUriString) { selectedUriString?.let(Uri::parse) }
    val currentSelectedUri by rememberUpdatedState(selectedUri)
    var isPreparing by remember(selectedUriString, selectionVersion) {
        mutableStateOf(selectedUriString != null)
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.resetDetection()
            selectedUriString = uri.toString()
            // The provider's name arrives shortly; a document ID would only flash meanwhile.
            fileName = ""
            manualMime = ""
            autoOpenPending = prefsManager.autoOpen
            selectionVersion++
        }
    }
    val saveCopy = rememberSaveCopy()
    val requestNotifications = rememberNotificationRequest()

    val suggestion = remember(detectionResult, fileName) {
        detectionResult?.let { NameCheck.check(fileName, it) }
    }
    val inspection = detectionResult?.let {
        FileInspector.Inspection(fileName.ifBlank { null }, viewModel.selectedSize, it)
    }
    val manualMimeValid = manualMime.isBlank() || MimeTypes.isValidInput(manualMime)
    // A valid manual type overrides the detected one for Open, Open with and Share.
    val openMime = manualMime.takeIf { it.isNotBlank() && manualMimeValid }
        ?: detectionResult?.mime
        ?: MimeTypes.OCTET_STREAM
    val ready = !isPreparing && !isDetecting && detectionResult != null && pendingRename == null

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshRecents() }

    LaunchedEffect(selectedUriString, selectionVersion) {
        val uriString = selectedUriString ?: run {
            isPreparing = false
            return@LaunchedEffect
        }
        val version = selectionVersion
        val uri = Uri.parse(uriString)
        val triggerAutoOpen = autoOpenPending
        autoOpenPending = false
        isPreparing = true
        try {
            val resolvedName = viewModel.queryFileName(uri)
                ?: fileName.ifBlank { null }
                ?: uri.lastPathSegment
                ?: "Unknown file"
            if (selectedUriString != uriString || selectionVersion != version) {
                return@LaunchedEffect
            }
            fileName = resolvedName
            viewModel.detectMime(
                uri = uri,
                fileName = resolvedName,
                fallbackMime = prefsManager.defaultMime,
                triggerAutoOpen = triggerAutoOpen
            )
        } finally {
            if (selectedUriString == uriString && selectionVersion == version) {
                isPreparing = false
            }
        }
    }

    LaunchedEffect(detectionResult) {
        detectionResult?.takeIf { manualMime.isBlank() }?.let {
            manualMime = it.mime
        }
    }

    // The app chooser can only open while the screen is visible; requests wait until then.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.autoOpenEvent.collect { request ->
                if (currentSelectedUri == request.uri) {
                    IntentRouter.open(context, request.uri, request.result.mime, request.fileName)
                }
            }
        }
    }

    RenamedOpenEffect(viewModel.renamedOpener) { request, copy ->
        IntentRouter.open(context, copy, request.mime, request.fileName, recentUri = request.source)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AnyFile Opener", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                PickerCard(
                    selected = selectedUri != null,
                    fileName = fileName,
                    inspection = inspection,
                    suggestion = suggestion,
                    loading = isPreparing || isDetecting,
                    onClick = { filePicker.launch(arrayOf("*/*")) }
                )
            }

            if (selectedUri != null) {
                item {
                    FileActions(
                        enabled = ready,
                        lastApp = lastApp,
                        correctedName = suggestion?.suggestedName,
                        onOpen = { IntentRouter.open(context, selectedUri, openMime, fileName) },
                        onOpenWithApp = {
                            lastApp?.let {
                                IntentRouter.openWithApp(context, selectedUri, openMime, it.packageName, fileName)
                            }
                        },
                        onOpenCorrected = {
                            val result = detectionResult
                            val hint = suggestion
                            if (result != null && hint != null) {
                                viewModel.openWithCorrectedName(selectedUri, hint.suggestedName, result.mime)
                                requestNotifications()
                            }
                        },
                        onShare = { IntentRouter.share(context, selectedUri, openMime, fileName) },
                        onSave = {
                            val name = suggestion?.suggestedName ?: fileName.ifBlank { "file" }
                            saveCopy(selectedUri, name, viewModel.selectedSize)
                        },
                        onDetails = { onDetailsClick(selectedUri, inspection) }
                    )
                }

                item {
                    OutlinedButton(
                        onClick = { onOpenAsClick(selectedUri, inspection) },
                        enabled = ready,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Category, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open as type…")
                    }
                }

                item {
                    AdvancedSection(
                        expanded = advancedExpanded,
                        onToggle = { advancedExpanded = !advancedExpanded },
                        manualMime = manualMime,
                        valid = manualMimeValid,
                        onMimeChange = { manualMime = it }
                    )
                }
            }

            if (tasks.isNotEmpty()) {
                item { TaskRows(tasks) }
            }

            item {
                TextButton(
                    onClick = onSystemFilesClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("System file manager")
                }
            }

            if (recents.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Recent files",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { RecentFileStore.clearRecentFilesAsync(context) }) {
                            Text("Clear")
                        }
                    }
                }

                items(recents, key = { it.file.uri }) { entry ->
                    RecentFileItem(
                        recent = entry.file,
                        expired = !entry.readable,
                        onRemove = { RecentFileStore.removeRecentFileAsync(context, entry.file.uri) },
                        onClick = {
                            viewModel.resetDetection()
                            selectedUriString = entry.file.uri
                            fileName = entry.file.fileName
                            manualMime = ""
                            autoOpenPending = false
                            selectionVersion++
                        }
                    )
                }
            }
        }
    }
}

/** Before a file is picked: an invitation. After: what was detected. Tapping picks a file. */
@Composable
private fun PickerCard(
    selected: Boolean,
    fileName: String,
    inspection: FileInspector.Inspection?,
    suggestion: NameCheck.Suggestion?,
    loading: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        if (selected) {
            Column(Modifier.padding(16.dp)) {
                FileSummary(
                    name = fileName.ifBlank { null },
                    inspection = inspection,
                    suggestion = suggestion,
                    loading = loading
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap to pick another file",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Outlined.AddCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Text("Pick a file", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Or choose AnyFile Opener in another app's \"Open with\" menu",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun AdvancedSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    manualMime: String,
    valid: Boolean,
    onMimeChange: (String) -> Unit
) {
    Column {
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "Hide advanced options" else "Advanced options")
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        if (expanded) {
            OutlinedTextField(
                value = manualMime,
                onValueChange = onMimeChange,
                label = { Text("MIME type for Open and Share") },
                supportingText = {
                    Text(
                        if (valid) {
                            "Overrides the detected type"
                        } else {
                            "Enter type/subtype, for example video/mp4"
                        }
                    )
                },
                isError = !valid,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            MimeChips(selected = MimeTypes.normalize(manualMime), onChipClick = onMimeChange)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MimeChips(selected: String, onChipClick: (String) -> Unit) {
    val mimes = listOf(
        MimeTypes.APK to "APK",
        "text/plain" to "TXT",
        "application/json" to "JSON",
        "application/pdf" to "PDF",
        "image/jpeg" to "JPG",
        "video/mp4" to "MP4",
        MimeTypes.ZIP to "ZIP"
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        mimes.forEach { (mime, label) ->
            FilterChip(
                selected = mime == selected,
                onClick = { onChipClick(mime) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun RecentFileItem(
    recent: RecentFile,
    expired: Boolean,
    onRemove: () -> Unit,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    val subtitle = buildString {
        append(recent.mimeType)
        append(" · ")
        append(dateFormat.format(Date(recent.timestamp)))
        if (expired) append(" · Expired")
    }

    ListItem(
        headlineContent = {
            FileNameText(
                recent.fileName,
                color = if (expired) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        },
        supportingContent = {
            Text(
                subtitle,
                maxLines = 1,
                color = if (expired) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        },
        leadingContent = {
            Icon(
                imageVector = if (expired) Icons.Outlined.LinkOff else Icons.Outlined.History,
                contentDescription = null,
                tint = if (expired) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Remove from recent",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        modifier = Modifier.clickable(enabled = !expired, onClick = onClick)
    )
}
