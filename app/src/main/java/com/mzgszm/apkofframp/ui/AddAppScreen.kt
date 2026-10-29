package com.mzgszm.apkofframp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzgszm.apkofframp.R
import com.mzgszm.apkofframp.core.Naming
import com.mzgszm.apkofframp.data.Settings
import com.mzgszm.apkofframp.graph
import com.mzgszm.apkofframp.system.InstalledSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Candidate(val pkg: String, val label: String, val installed: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAppScreen(onBack: () -> Unit, onAdded: (String) -> Unit) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    var pending by remember { mutableStateOf<Candidate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add app") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") } },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installed apps") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("By package ID") })
            }
            if (tab == 0) InstalledPicker { pending = it } else ByIdForm { pending = it }
        }
    }

    val destinations by g.dao.observeDestinations().collectAsStateWithLifecycle(emptyList())

    pending?.let { c ->
        var uploadNow by remember(c) { mutableStateOf(true) }
        // Null is the default destination. Picked here so the first upload already goes to the right place.
        var destId by remember(c) { mutableStateOf<Long?>(null) }
        var destMenu by remember(c) { mutableStateOf(false) }
        // Re-adding a monitored package by ID starts from the destination it already had.
        LaunchedEffect(c) { destId = g.dao.app(c.pkg)?.destinationId }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Add ${c.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(c.pkg, style = MaterialTheme.typography.bodySmall)
                    if (!c.installed) Text("Not installed. It shows as Waiting until it appears.")
                    if (destinations.size > 1) {
                        val defaultName = destinations.firstOrNull { it.isDefault }?.name
                        val label = destinations.firstOrNull { it.id == destId }?.name
                            ?: "Default${defaultName?.let { " ($it)" } ?: ""}"
                        Box {
                            OutlinedButton(onClick = { destMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Destination: $label", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = destMenu, onDismissRequest = { destMenu = false }) {
                                DropdownMenuItem(text = { Text("Default${defaultName?.let { " ($it)" } ?: ""}") },
                                    onClick = { destId = null; destMenu = false })
                                destinations.forEach { d ->
                                    DropdownMenuItem(text = { Text(d.name) }, onClick = { destId = d.id; destMenu = false })
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Upload the installed version now", Modifier.weight(1f))
                        Switch(checked = uploadNow, onCheckedChange = { uploadNow = it })
                    }
                    if (!uploadNow) {
                        Text("The installed version becomes the baseline. Only later updates upload.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        try {
                            g.repo.addApp(c.pkg, c.label, uploadNow, destId)
                            pending = null
                            onAdded(c.pkg)
                        } catch (e: Exception) {
                            error = e.message
                        }
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InstalledPicker(onPick: (Candidate) -> Unit) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val settings by g.settings.flow.collectAsStateWithLifecycle(Settings())
    val monitored by g.dao.observeApps().collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    val all by produceState<List<InstalledSnapshot>?>(null, settings.showSystemApps) {
        value = withContext(Dispatchers.IO) { g.installed.list(settings.showSystemApps) }
    }
    val taken = monitored.map { it.packageName }.toSet()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            placeholder = { Text("Search by name or package") },
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Show system apps", Modifier.weight(1f))
            Switch(checked = settings.showSystemApps, onCheckedChange = { on ->
                scope.launch { g.settings.update { it.copy(showSystemApps = on) } }
            })
        }
        val list = all
        if (list == null) {
            Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        } else {
            val q = query.trim().lowercase()
            val shown = list.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.packageName }) { s ->
                    val already = s.packageName in taken
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !already) { onPick(Candidate(s.packageName, s.label, true)) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(s.packageName)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                s.packageName + if (s.isSplit) "  ·  splits" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (already) Text("Added", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ByIdForm(onPick: (Candidate) -> Unit) {
    val g = LocalContext.current.graph
    var id by rememberSaveable { mutableStateOf("") }
    val trimmed = id.trim()
    val valid = Naming.isValidPackage(trimmed)
    var found by remember { mutableStateOf<InstalledSnapshot?>(null) }
    LaunchedEffect(trimmed, valid) {
        found = if (valid) withContext(Dispatchers.IO) { g.installed.snapshot(trimmed) } else null
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = id, onValueChange = { id = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Package ID") },
            placeholder = { Text("com.example.app") },
            singleLine = true,
            isError = trimmed.isNotEmpty() && !valid,
            supportingText = {
                if (trimmed.isNotEmpty() && !valid) Text("Use letters, digits and underscores, with at least one dot")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )
        if (valid) {
            val f = found
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (f != null) {
                    AppIcon(f.packageName)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(f.label, style = MaterialTheme.typography.titleMedium)
                        Text(versionText(f.versionName, f.versionCode), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("Not installed. It will be added as Waiting.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Button(onClick = { onPick(Candidate(trimmed, found?.label ?: trimmed, found != null)) }, enabled = valid) {
            Text("Continue")
        }
    }
}
