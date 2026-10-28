package com.mzgszm.apkofframp.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzgszm.apkofframp.R
import com.mzgszm.apkofframp.core.Naming
import com.mzgszm.apkofframp.data.Settings
import com.mzgszm.apkofframp.data.ThemeMode
import com.mzgszm.apkofframp.graph
import com.mzgszm.apkofframp.notify.Channels
import com.mzgszm.apkofframp.ui.theme.supportsDynamic
import com.mzgszm.apkofframp.work.InstantModeService
import com.mzgszm.apkofframp.work.Work
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(outer: PaddingValues, onEditDestination: (Long) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsStateWithLifecycle(Settings())
    val destinations by g.dao.observeDestinations().collectAsStateWithLifecycle(emptyList())

    // Re-read system state when returning from Android's settings screens.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val notificationsOn = remember(tick) { g.notifier.canPost() }
    val batteryExempt = remember(tick) { ctx.getSystemService(PowerManager::class.java)!!.isIgnoringBatteryOptimizations(ctx.packageName) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val update: ((Settings) -> Settings) -> Unit = { t -> scope.launch { g.settings.update(t) } }

    Scaffold(
        modifier = Modifier.padding(bottom = outer.calculateBottomPadding()),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = 24.dp)) {
            item { Header("Destinations") }
            destinations.forEach { d ->
                item(key = "d${d.id}") {
                    ListItem(
                        modifier = Modifier.clickable { onEditDestination(d.id) },
                        headlineContent = { Text(d.name + if (d.isDefault) "  ·  default" else "") },
                        supportingContent = {
                            Text(
                                d.baseUrl + when {
                                    d.authFailed -> "\nPassword rejected. Edit to retry."
                                    d.lastTestSummary != null -> "\n${d.lastTestSummary}"
                                    else -> "\nNot tested yet"
                                },
                                color = if (d.authFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { onEditDestination(0L) },
                    leadingContent = { Icon(painterResource(R.drawable.ic_add), null) },
                    headlineContent = { Text(if (destinations.isEmpty()) "Add the default destination" else "Add destination") },
                )
            }

            item { Header("Behavior") }
            item {
                ChoiceItem("Sweep interval", "${s.sweepMinutes} minutes", listOf(15, 30, 60, 120, 360, 720), { "$it minutes" }) { m ->
                    update { it.copy(sweepMinutes = m) }
                    Work.schedulePeriodic(ctx, m)
                }
            }
            item {
                ChoiceItem("Settle delay", "${s.settleSeconds} seconds", listOf(15, 30, 60, 120, 300), { "$it seconds" }) { v ->
                    update { it.copy(settleSeconds = v) }
                }
            }
            item { SwitchItem("Wi-Fi only", "Uploads wait for an unmetered network", s.wifiOnly) { v -> update { it.copy(wifiOnly = v) } } }
            item { SwitchItem("Charging only", "Uploads wait until the phone is charging", s.chargingOnly) { v -> update { it.copy(chargingOnly = v) } } }
            item {
                ChoiceItem("Default versions kept", keepLabel(s.defaultKeep), listOf(0, 1, 2, 3, 5, 10, 20, Naming.KEEP_ALL), ::keepLabel) { v ->
                    update { it.copy(defaultKeep = v) }
                }
            }
            item {
                SwitchItem("Instant mode", "Reacts within seconds of an update. Shows a permanent notification.", s.instantMode) { v ->
                    update { it.copy(instantMode = v) }
                    if (v) InstantModeService.start(ctx) else InstantModeService.stop(ctx)
                }
            }
            item { SwitchItem("Show system apps in the picker", null, s.showSystemApps) { v -> update { it.copy(showSystemApps = v) } } }

            item { Header("Appearance") }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        val modes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                        modes.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = s.theme == mode,
                                onClick = { update { it.copy(theme = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            ) { Text(label) }
                        }
                    }
                }
            }
            if (supportsDynamic()) {
                item { SwitchItem("Dynamic color", "Colors from your wallpaper", s.dynamicColor) { v -> update { it.copy(dynamicColor = v) } } }
            }

            item { Header("Notifications and battery") }
            if (!notificationsOn) {
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Notifications are off. Uploads still run, but you will not hear about failures.",
                                Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = {
                                if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else ctx.startActivity(appNotificationSettings(ctx))
                            }) { Text("Turn on") }
                        }
                    }
                }
            }
            Channels.all.forEach { ch ->
                item(key = ch.id) {
                    ListItem(
                        modifier = Modifier.clickable { ctx.startActivity(channelSettings(ctx, ch.id)) },
                        headlineContent = { Text(stringResource(ch.nameRes)) },
                        supportingContent = { Text(stringResource(ch.descRes)) },
                    )
                }
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(enabled = !batteryExempt) { ctx.startActivity(batteryIntent(ctx)) },
                    headlineContent = { Text("Battery optimization") },
                    supportingContent = {
                        Text(if (batteryExempt) "Offramp is exempt, so sweeps run on time."
                        else "Tap to exempt Offramp. Without it, Android can delay sweeps for hours.")
                    },
                )
            }

            item { Header("About") }
            item {
                val info = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }
                ListItem(
                    headlineContent = { Text("Version") },
                    supportingContent = { Text("${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})") },
                )
            }
            item {
                val url = stringResource(R.string.github_url)
                ListItem(
                    modifier = Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                    headlineContent = { Text("Source code on GitHub") },
                    supportingContent = { Text(url) },
                )
            }
        }
    }
}

private fun keepLabel(n: Int) = if (n == Naming.KEEP_ALL) "All (never prune)" else "$n"

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp))
}

@Composable
private fun SwitchItem(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun <T> ChoiceItem(title: String, value: String, options: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        ListItem(
            modifier = Modifier.clickable { open = true },
            headlineContent = { Text(title) },
            supportingContent = { Text(value) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(label(o)) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

private fun channelSettings(ctx: Context, id: String) =
    Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)
        .putExtra(AndroidSettings.EXTRA_CHANNEL_ID, id)

private fun appNotificationSettings(ctx: Context) =
    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)

/** Direct exemption prompt. Allowed for apps outside Play; the spec lists this permission. */
@SuppressLint("BatteryLife")
private fun batteryIntent(ctx: Context) =
    Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
