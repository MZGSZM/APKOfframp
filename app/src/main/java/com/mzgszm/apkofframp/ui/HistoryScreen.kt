package com.mzgszm.apkofframp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzgszm.apkofframp.data.Outcome
import com.mzgszm.apkofframp.graph
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(outer: PaddingValues, onOpen: (String) -> Unit) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val rows by g.dao.observeHistory().collectAsStateWithLifecycle(emptyList())

    Scaffold(
        modifier = Modifier.padding(bottom = outer.calculateBottomPadding()),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("History") }) },
    ) { inner ->
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text("No uploads yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = 16.dp)) {
                items(rows, key = { it.id }) { h ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(h.packageName) },
                        leadingContent = { AppIcon(h.packageName) },
                        headlineContent = { Text("${h.displayName} ${versionText(h.versionName, h.versionCode)}") },
                        supportingContent = {
                            Text(
                                "${formatTime(h.startTime)}  ·  ${outcomeLabel(h.outcome)}" +
                                    (if (h.size > 0) "  ·  ${formatSize(h.size)}" else "") +
                                    (h.error?.let { "\n$it" } ?: ""),
                                color = if (h.outcome == Outcome.FAILED) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = if (h.outcome == Outcome.FAILED) {
                            { TextButton(onClick = { scope.launch { g.repo.retry(h.packageName) } }) { Text("Retry") } }
                        } else null,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
