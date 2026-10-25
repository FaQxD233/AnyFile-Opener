package com.anyfile.x.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anyfile.x.data.PrefsManager
import com.anyfile.x.engine.MimeTypes
import com.anyfile.x.ui.ThemePreference
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefsManager: PrefsManager,
    onBackClick: () -> Unit
) {
    val themePreference by prefsManager.themePreference.collectAsStateWithLifecycle(initialValue = ThemePreference.SYSTEM_DEFAULT)
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var autoOpen by remember { mutableStateOf(prefsManager.autoOpen) }
    var defaultMime by remember { mutableStateOf(prefsManager.defaultMime) }
    val defaultMimeIsValid = remember(defaultMime) { isValidMime(defaultMime) }

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
        ) {
            item {
                SectionHeader("BEHAVIOR")
            }

            item {
                ListItem(
                    headlineContent = { Text("Auto-open after high-confidence detect") },
                    supportingContent = {
                        Text("When detection is high-confidence, show the app chooser right away")
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
                        if (isValidMime(it)) prefsManager.defaultMime = MimeTypes.normalize(it)
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
                    supportingContent = {
                        Text(
                            when (themePreference) {
                                ThemePreference.SYSTEM_DEFAULT -> "System Default"
                                ThemePreference.AMOLED -> "AMOLED Pure Black"
                                ThemePreference.MATERIAL_YOU -> "Material You"
                            }
                        )
                    },
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
                    supportingContent = { Text("Learn about AnyFile Opener") },
                    modifier = Modifier.clickable { showAboutDialog = true }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Buy me a coffee") },
                    supportingContent = { Text("Support the developer ❤️") },
                    modifier = Modifier.clickable {
                        openUrl(context, "https://buymeacoffee.com/tapman")
                    }
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
            prefsManager = prefsManager
        )
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("Help & Guide") },
            text = {
                Text(
                    "AnyFile Opener bridges hard-to-open files to the right app.\n\n" +
                        "How to use:\n" +
                        "1. Pick a file on the home screen, or share/open a file into this app.\n" +
                        "2. Wait for magic-byte detection (type, confidence, evidence).\n" +
                        "3. Tap Open, Share, or Open As… (header open icon uses the exact detected MIME).\n\n" +
                        "Tips:\n" +
                        "• Advanced: type a custom MIME when detection is too broad.\n" +
                        "• Share: send the file to MT Manager or another app.\n" +
                        "• Inspect Binary: preview hex/ASCII headers.\n" +
                        "• Recent items may expire when the source only granted temporary access—remove them with ×."
                )
            },
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
                Text("AnyFile Opener is a versatile utility designed to handle files that Android usually struggles with. It automatically identifies the correct file type using magic-byte detection and lets you override it manually to open files with any app you choose.\n\nCreated with ❤️ by Tapman")
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("OK") }
            }
        )
    }
}

private fun isValidMime(value: String): Boolean =
    value.isNotBlank() && MimeTypes.isValid(MimeTypes.normalize(value))

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
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        // Handle error
    }
}

@Composable
fun ThemeSelectionDialog(
    currentSelection: ThemePreference,
    onDismiss: () -> Unit,
    prefsManager: PrefsManager
) {
    val scope = rememberCoroutineScope()
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Theme") },
        text = {
            Column {
                ThemeOption(
                    label = "System Default",
                    selected = currentSelection == ThemePreference.SYSTEM_DEFAULT,
                    onClick = {
                        scope.launch {
                            prefsManager.setThemePreference(ThemePreference.SYSTEM_DEFAULT)
                            onDismiss()
                        }
                    }
                )
                ThemeOption(
                    label = "AMOLED Pure Black",
                    selected = currentSelection == ThemePreference.AMOLED,
                    onClick = {
                        scope.launch {
                            prefsManager.setThemePreference(ThemePreference.AMOLED)
                            onDismiss()
                        }
                    }
                )
                ThemeOption(
                    label = "Material You",
                    selected = currentSelection == ThemePreference.MATERIAL_YOU,
                    onClick = {
                        scope.launch {
                            prefsManager.setThemePreference(ThemePreference.MATERIAL_YOU)
                            onDismiss()
                        }
                    }
                )
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
