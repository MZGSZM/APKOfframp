package com.mzgszm.apkofframp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzgszm.apkofframp.R
import com.mzgszm.apkofframp.data.AppEntity
import com.mzgszm.apkofframp.data.AppStatus
import com.mzgszm.apkofframp.graph
import com.mzgszm.apkofframp.system.InstalledSnapshot
import com.mzgszm.apkofframp.work.Work
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(outer: PaddingValues, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val apps by g.dao.observeApps().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.padding(bottom = outer.calculateBottomPadding()),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("APK Offramp") },
                actions = {
                    IconButton(onClick = { Work.sweepNow(ctx) }) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Check now")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(painterResource(R.drawable.ic_add), contentDescription = "Add app") }
        },
    ) { inner ->
        if (apps.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No apps yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap + to pick an installed app or enter a package ID. Add a destination in Settings first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = 88.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    AppRow(app, onClick = { onOpen(app.packageName) }, onToggle = { on ->
                        scope.launch { g.repo.setEnabled(app.packageName, on) }
                    })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntity, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val installed by produceState<InstalledSnapshot?>(null, app.packageName, app.status) {
        value = withContext(Dispatchers.IO) { ctx.graph.installed.snapshot(app.packageName) }
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.packageName)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(app.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Installed ${installed?.let { versionText(it.versionName, it.versionCode) } ?: "no"}  ·  " +
                        "Uploaded ${if (app.lastUploadTime != null) versionText(app.lastVersionName, app.lastVersionCode) else "none"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(app.status, app.progress)
                    if ((app.status == AppStatus.FAILED || app.status == AppStatus.PAUSED) && app.lastError != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(app.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Switch(checked = app.enabled, onCheckedChange = onToggle)
        }
        if (app.status == AppStatus.UPLOADING) {
            Spacer(Modifier.height(8.dp))
            if (app.progress >= 0) {
                LinearProgressIndicator(progress = { app.progress / 100f }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
