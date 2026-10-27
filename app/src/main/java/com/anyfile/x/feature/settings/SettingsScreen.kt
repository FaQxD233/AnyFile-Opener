package com.anyfile.x.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.anyfile.x.data.PrefsManager
import com.anyfile.x.engine.MimeTypes
import com.anyfile.x.ui.ThemePreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefsManager: PrefsManager,
    onBackClick: () -> Unit
) {
    // Choosing a theme recreates the activity, which reads the new value again.
    val themePreference = remember { prefsManager.themePreference }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var autoOpen by remember { mutableStateOf(prefsManager.autoOpen) }
    var defaultMime by remember { mutableStateOf(prefsManager.defaultMime) }
    val defaultMimeIsValid = remember(defaultMime) { MimeTypes.isValidInput(defaultMime) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            item {
                SectionHeader("BEHAVIOR")
            }

            item {
                ListItem(
                    headlineContent = { Text("Auto-open files picked here") },
                    supportingContent = {
                        Text(
                            "When a file picked on the home screen is detected with high " +
                                "confidence, show the app chooser right away. Files opened from " +
                                "other apps always show the Open As sheet."
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = autoOpen,
                            onCheckedChange = {
                                autoOpen = it
                                prefsManager.autoOpen = it
                            }
                        )
                    }
                )
            }

            item {
                OutlinedTextField(
                    value = defaultMime,
                    onValueChange = {
                        defaultMime = it
                        if (MimeTypes.isValidInput(it)) prefsManager.defaultMime = MimeTypes.normalize(it)
                    },
                    label = { Text("Unknown-file fallback MIME") },
                    supportingText = {
                        Text(if (defaultMimeIsValid) "Example: application/octet-stream" else "Enter type/subtype or */*")
                    },
                    isError = !defaultMimeIsValid,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // APPEARANCE SECTION
            item {
                SectionHeader("APPEARANCE")
            }

            item {
                ListItem(
                    headlineContent = { Text("Theme") },
                    supportingContent = { Text(themePreference.label) },
                    modifier = Modifier.clickable { showThemeDialog = true }
                )
            }

            // ABOUT SECTION
            item {
                SectionHeader("ABOUT")
            }

            item {
                ListItem(
                    headlineContent = { Text("How to Use") },
                    supportingContent = { Text("Help & Guide") },
                    modifier = Modifier.clickable { showHelpDialog = true }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("About") },
                    supportingContent = { Text("Author, original project and source code") },
                    modifier = Modifier.clickable { showAboutDialog = true }
                )
            }

            item {
                val version = try {
                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    pInfo.versionName
                } catch (e: Exception) {
                    "1.0"
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Version $version",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentSelection = themePreference,
            onDismiss = { showThemeDialog = false },
            onSelect = { selected ->
                showThemeDialog = false
                if (selected != themePreference) prefsManager.themePreference = selected
            }
        )
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("Help & Guide") },
            text = { HelpContent() },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) { Text("Got it") }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About AnyFile Opener") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "AnyFile Opener is a versatile utility designed to handle files that Android " +
                            "usually struggles with. It automatically identifies the correct file type " +
                            "using magic-byte detection and lets you override it manually to open files " +
                            "with any app you choose."
                    )
                    Spacer(Modifier.height(4.dp))
                    LinkLine("Created by ", "FaQxD233", "https://github.com/FaQxD233")
                    LinkLine("Forked from ", "tapman104/OpenBridge", "https://github.com/tapman104/OpenBridge")
                    LinkLine("Repository: ", "AnyFile-Opener", "https://github.com/FaQxD233/AnyFile-Opener")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun HelpContent() {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "AnyFile Opener tells what a file really is from its content, then hands it to an app " +
                "that can open it."
        )
        HelpSection(
            title = "Open a file",
            numbered = true,
            items = listOf(
                "Pick a file on the home screen, or choose AnyFile Opener in another app's " +
                    "\"Open with\" or share menu.",
                "Check the detected type. Confidence and evidence show how the type was found.",
                "Tap Open, or pick another category under \"Open as type\" when the detected type " +
                    "is not what you want."
            )
        )
        HelpSection(
            title = "Actions",
            items = listOf(
                "Open with <app>: opens the file directly in the app you last picked for this " +
                    "kind of file.",
                "Open as <name>: when the extension does not match the content, forwards a copy " +
                    "with the right name to apps that go by the extension.",
                "Save: stores a copy anywhere, with a wrong extension corrected. Copies keep " +
                    "running in the background with a progress notification.",
                "Share: sends the file to another app, such as a file manager.",
                "Details: path, size, modification time, checksums and, for APKs, package details."
            )
        )
        HelpSection(
            title = "Good to know",
            items = listOf(
                "Several shared files open one after another; skip a file or cancel the rest.",
                "Advanced options on the home screen let you type a MIME type yourself.",
                "Recent files expire when the source app only granted temporary access; remove " +
                    "them with ×.",
                "The Files widget opens the system file manager from the home screen."
            )
        )
    }
}

@Composable
private fun HelpSection(title: String, items: List<String>, numbered: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        items.forEachIndexed { index, item ->
            Row {
                Text(if (numbered) "${index + 1}." else "•", modifier = Modifier.width(20.dp))
                Text(item)
            }
        }
    }
}

/** "Created by FaQxD233", where the second part opens [url] in the browser. */
@Composable
private fun LinkLine(prefix: String, label: String, url: String) {
    val context = LocalContext.current
    Row {
        Text(prefix)
        Text(
            text = label,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(onClickLabel = "Open in browser") { openUrl(context, url) }
        )
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 8.dp)
    )
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun ThemeSelectionDialog(
    currentSelection: ThemePreference,
    onDismiss: () -> Unit,
    onSelect: (ThemePreference) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Theme") },
        text = {
            Column {
                ThemePreference.entries.forEach { option ->
                    ThemeOption(
                        label = option.label,
                        selected = option == currentSelection,
                        onClick = { onSelect(option) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ThemeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label)
    }
}
