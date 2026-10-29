package com.mzgszm.apkofframp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzgszm.apkofframp.R
import com.mzgszm.apkofframp.AppTarget
import com.mzgszm.apkofframp.core.Naming
import com.mzgszm.apkofframp.data.AppEntity
import com.mzgszm.apkofframp.data.AppStatus
import com.mzgszm.apkofframp.data.DestinationEntity
import com.mzgszm.apkofframp.data.Outcome
import com.mzgszm.apkofframp.data.Settings
import com.mzgszm.apkofframp.data.VersionEntity
import com.mzgszm.apkofframp.data.VersionState
import com.mzgszm.apkofframp.graph
import com.mzgszm.apkofframp.notify.Clip
import com.mzgszm.apkofframp.system.InstalledSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(pkg: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val app by g.dao.observeApp(pkg).collectAsStateWithLifecycle(null)
    val versions by g.dao.observeVersions(pkg).collectAsStateWithLifecycle(emptyList())
    val history by g.dao.observeHistoryFor(pkg).collectAsStateWithLifecycle(emptyList())
    val destinations by g.dao.observeDestinations().collectAsStateWithLifecycle(emptyList())
    val settings by g.settings.flow.collectAsStateWithLifecycle(Settings())
    var confirmRemove by remember { mutableStateOf(false) }

    val act: (suspend () -> String?) -> Unit = { block ->
        scope.launch {
            val msg = try { block() } catch (e: Exception) { e.message ?: "Failed" }
            msg?.let { snack.showSnackbar(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(app?.displayName ?: pkg) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") } },
                actions = {
                    IconButton(onClick = { confirmRemove = true }) { Icon(painterResource(R.drawable.ic_delete), "Remove app") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { inner ->
        val a = app ?: return@Scaffold
        val target by produceState<AppTarget?>(null, a, destinations, settings.defaultKeep) {
            value = runCatching { g.repo.target(a) }.getOrNull()
        }
        val installed by produceState<InstalledSnapshot?>(null, a.status) {
            value = withContext(Dispatchers.IO) { g.installed.snapshot(pkg) }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
            top = inner.calculateTopPadding() + 8.dp, bottom = inner.calculateBottomPadding() + 24.dp,
            start = 16.dp, end = 16.dp,
        ), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg, 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Installed: ${installed?.let { versionText(it.versionName, it.versionCode) + if (it.isSplit) ", splits" else "" } ?: "not installed"}",
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.size(4.dp))
                        StatusChip(a.status, a.progress)
                    }
                }
                a.lastError?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = if (a.status == AppStatus.FAILED || a.status == AppStatus.PAUSED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
            item {
                AppSettingsCard(a, destinations, settings.defaultKeep) { destId, folder, keep ->
                    act { g.repo.updateAppSettings(pkg, destId, folder, keep) }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { act { g.repo.uploadNow(pkg); "Upload queued" } }) {
                        Icon(painterResource(R.drawable.ic_upload), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Upload now")
                    }
                    OutlinedButton(onClick = { target?.let { Clip.copy(ctx, "Folder link", g.repo.folderLink(it)) } }, enabled = target != null) {
                        Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy folder link")
                    }
                    if (a.status == AppStatus.PAUSED) {
                        Button(onClick = { act { g.repo.retestAndResume(pkg) ?: "Permissions OK, resumed" } }) { Text("Re-test and resume") }
                    }
                    if (a.status == AppStatus.FAILED) {
                        OutlinedButton(onClick = { act { g.repo.retry(pkg); "Retrying" } }) { Text("Retry") }
                    }
                }
            }
            item { Text("Versions on server", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            if (versions.isEmpty()) {
                item { Text("Nothing uploaded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(versions, key = { "v${it.id}" }) { v ->
                // With more than one destination, say which server each copy is on.
                val on = if (destinations.size > 1) destinations.firstOrNull { it.id == v.destinationId }?.name else null
                VersionRow(v, on)
            }
            item { Text("History", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            if (history.isEmpty()) {
                item { Text("No uploads yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(history, key = { "h${it.id}" }) { h ->
                ListItem(
                    headlineContent = { Text("${versionText(h.versionName, h.versionCode)}  ·  ${outcomeLabel(h.outcome)}") },
                    supportingContent = {
                        Text(formatTime(h.startTime) + (if (h.size > 0) "  ·  ${formatSize(h.size)}" else "") +
                            (h.error?.let { "\n$it" } ?: ""))
                    },
                )
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Stop monitoring?") },
            text = { Text("Files already on the server stay there. The kept-version list and history for this app are removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch { g.repo.removeApp(pkg); onBack() }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

fun outcomeLabel(o: Outcome) = when (o) {
    Outcome.RUNNING -> "Running"
    Outcome.UPLOADED -> "Uploaded"
    Outcome.FAILED -> "Failed"
    Outcome.SKIPPED -> "Skipped"
}

@Composable
private fun VersionRow(v: VersionEntity, destinationName: String?) {
    val ctx = LocalContext.current
    val pruned = v.state == VersionState.PRUNED
    Card(Modifier.fillMaxWidth().alpha(if (pruned) 0.5f else 1f)) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    versionText(v.versionName, v.versionCode) + when (v.state) {
                        VersionState.CURRENT -> "  ·  current"
                        VersionState.ARCHIVED -> ""
                        VersionState.PRUNED -> "  ·  pruned"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("${formatSize(v.size)}  ·  ${formatTime(v.uploadTime)}" + (destinationName?.let { "  ·  $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (v.certChanged) {
                    Text("Signing certificate changed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (!pruned && v.url.isNotEmpty()) {
                IconButton(onClick = { Clip.copy(ctx, "APK link", v.url) }) {
                    Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy link")
                }
            }
        }
    }
}

@Composable
private fun AppSettingsCard(
    app: AppEntity,
    destinations: List<DestinationEntity>,
    defaultKeep: Int,
    onSave: (Long?, String?, Int?) -> Unit,
) {
    // Keyed on the saved values only. Keying on the whole row reset every field each time the upload
    // progress or status changed, so a destination picked during an upload snapped back to Default
    // before Save, and Save then stored Default.
    var destId by remember(app.packageName, app.destinationId) { mutableStateOf(app.destinationId) }
    var folder by remember(app.packageName, app.pathOverride) { mutableStateOf(app.pathOverride.orEmpty()) }
    var useDefaultKeep by remember(app.packageName, app.keepVersions) { mutableStateOf(app.keepVersions == null) }
    var keepAll by remember(app.packageName, app.keepVersions) { mutableStateOf(app.keepVersions == Naming.KEEP_ALL) }
    var keepText by remember(app.packageName, app.keepVersions, defaultKeep) {
        mutableStateOf((app.keepVersions?.takeIf { it >= 0 } ?: defaultKeep).toString())
    }
    var menu by remember { mutableStateOf(false) }

    val keepValue: Int? = when {
        useDefaultKeep -> null
        keepAll -> Naming.KEEP_ALL
        else -> keepText.toIntOrNull()?.takeIf { it >= 0 }
    }
    val keepValid = useDefaultKeep || keepAll || keepValue != null
    val folderValid = folder.split('/').none { it.trim() == "." || it.trim() == ".." }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Upload settings", style = MaterialTheme.typography.titleMedium)
            val defaultName = destinations.firstOrNull { it.isDefault }?.name
            val selected = destinations.firstOrNull { it.id == destId }
            Box {
                ListItem(
                    modifier = Modifier.fillMaxWidth().clickable { menu = true },
                    headlineContent = { Text("Destination") },
                    supportingContent = {
                        Text(
                            (selected?.name ?: "Default${defaultName?.let { " ($it)" } ?: " (none set)"}") +
                                if (destId != app.destinationId) "\nNot saved yet. After Save, uploads go here, and an app already uploaded elsewhere is sent here too." else "",
                        )
                    },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Default") }, onClick = { destId = null; menu = false })
                    destinations.forEach { d ->
                        DropdownMenuItem(text = { Text(d.name) }, onClick = { destId = d.id; menu = false })
                    }
                }
            }
            OutlinedTextField(
                value = folder, onValueChange = { folder = it },
                label = { Text("Folder") },
                placeholder = { Text(app.packageName) },
                supportingText = { Text(if (folderValid) "Relative to the destination URL. Empty means the package name." else "Cannot contain . or ..") },
                isError = !folderValid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use default versions kept ($defaultKeep)", Modifier.weight(1f))
                Switch(checked = useDefaultKeep, onCheckedChange = { useDefaultKeep = it })
            }
            if (!useDefaultKeep) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep all versions", Modifier.weight(1f))
                    Switch(checked = keepAll, onCheckedChange = { keepAll = it })
                }
                if (!keepAll) {
                    OutlinedTextField(
                        value = keepText, onValueChange = { keepText = it.filter(Char::isDigit).take(4) },
                        label = { Text("Older versions kept") },
                        isError = !keepValid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Button(onClick = { onSave(destId, folder.ifBlank { null }, keepValue) }, enabled = keepValid && folderValid) { Text("Save") }
        }
    }
}
