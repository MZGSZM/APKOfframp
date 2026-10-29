package com.mzgszm.apkofframp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mzgszm.apkofframp.R
import com.mzgszm.apkofframp.core.RemoteConfig
import com.mzgszm.apkofframp.data.DestinationEntity
import com.mzgszm.apkofframp.graph
import kotlinx.coroutines.launch

/** Edit or create a destination. id 0 means new. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DestinationScreen(id: Long, onBack: () -> Unit) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(id == 0L) }
    var row by remember { mutableStateOf(DestinationEntity(name = "", baseUrl = "")) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var clearPassword by remember { mutableStateOf(false) }
    var maxMiB by remember { mutableStateOf("32") }
    var allowHttp by remember { mutableStateOf(false) }
    var isDefault by remember { mutableStateOf(false) }
    var savedId by remember { mutableStateOf(id) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (id != 0L) {
            g.dao.destination(id)?.let { d ->
                row = d; name = d.name; url = d.baseUrl; maxMiB = d.maxRequestMiB.toString()
                allowHttp = d.allowHttp; isDefault = d.isDefault
            }
            loaded = true
        }
    }

    val urlError: String? = if (url.isBlank()) null else runCatching {
        RemoteConfig(url, null, RemoteConfig.mib(maxMiB.toIntOrNull() ?: 32), allowHttp); null
    }.getOrElse { it.message }
    val mib = maxMiB.toIntOrNull()
    val valid = url.isNotBlank() && urlError == null && mib != null && mib >= 1

    suspend fun save(): DestinationEntity {
        val pw = when {
            clearPassword -> ""
            password.isNotEmpty() -> password
            else -> null
        }
        val d = row.copy(
            id = savedId, name = name.ifBlank { "Default" }, baseUrl = url.trim(),
            maxRequestMiB = mib ?: 32, allowHttp = allowHttp, isDefault = isDefault,
        )
        val newId = g.repo.saveDestination(d, pw)
        savedId = newId
        password = ""
        clearPassword = false
        val fresh = g.dao.destination(newId)!!
        row = fresh
        isDefault = fresh.isDefault
        return fresh
    }

    val runTask: (String, suspend (DestinationEntity) -> String) -> Unit = { label, block ->
        scope.launch {
            busy = true
            result = try { block(save()) } catch (e: Exception) { "$label failed: ${e.message ?: e.javaClass.simpleName}" }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == 0L) "New destination" else "Destination") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") } },
                actions = {
                    if (savedId != 0L) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(painterResource(R.drawable.ic_delete), "Delete destination") }
                    }
                },
            )
        },
    ) { inner ->
        if (!loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                url, { url = it }, label = { Text("Base URL") },
                placeholder = { Text("https://files.example.com/apks/") },
                isError = urlError != null,
                supportingText = { Text(urlError ?: "Include the folder path. Apps go in a folder named after the package.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password, { password = it; if (it.isNotEmpty()) clearPassword = false },
                label = { Text("Password") },
                placeholder = { Text(if (row.passwordEnc != null) "Saved. Leave blank to keep it." else "Optional") },
                supportingText = { Text("Sent in the PW header. copyparty wants the account password alone, or user:password if the server runs with --usernames.") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (row.passwordEnc != null) {
                SwitchRow("Remove the saved password", clearPassword) { clearPassword = it; if (it) password = "" }
            }
            OutlinedTextField(
                maxMiB, { maxMiB = it.filter(Char::isDigit).take(5) },
                label = { Text("Maximum request size (MiB)") },
                supportingText = { Text("Larger chunks are sent in pieces of this size, for proxies that cap requests.") },
                isError = mib == null || mib < 1,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Allow unencrypted HTTP", allowHttp) { allowHttp = it }
            if (allowHttp) {
                Text("The password and files travel in the clear over HTTP. Only use this on a network you trust.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            SwitchRow("Default destination", isDefault, enabled = !row.isDefault) { isDefault = it }
            if (row.isDefault) {
                Text("Apps set to Default upload here. To change that, turn this on for another destination.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }

            Button(onClick = { runTask("Save") { "Saved" } }, enabled = valid && !busy) { Text("Save") }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tests", style = MaterialTheme.typography.titleMedium)
                    Text("Both tests save first. Test connection writes, moves, lists and deletes a marker file in the base folder.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            runTask("Test connection") { d ->
                                val r = g.repo.testDestination(d)
                                buildString {
                                    append("r ${mark(r.read)}  w ${mark(r.write)}  m ${mark(r.move)}  d ${mark(r.delete)}  . ${mark(r.dot)}\n")
                                    append("Hash verification: ${if (r.hashVerify) "works" else "not available, sizes are checked"}\n")
                                    val missing = r.missing()
                                    if (missing.isNotEmpty()) append("Missing: ${missing.joinToString(" ")}\n")
                                    r.notes.forEach { append("\n$it") }
                                }
                            }
                        }, enabled = valid && !busy) { Text("Test connection") }
                        OutlinedButton(onClick = {
                            runTask("Test request size") { d ->
                                g.repo.testRequestSize(d)
                                "A ${d.maxRequestMiB} MiB request went through."
                            }
                        }, enabled = valid && !busy) { Text("Test request size") }
                    }
                    if (busy) CircularProgressIndicator(Modifier.padding(8.dp))
                    (result ?: row.lastTestSummary)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete destination?") },
            text = { Text("Apps using it switch to the default destination. Files on the server are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { g.repo.deleteDestination(savedId); onBack() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun mark(ok: Boolean) = if (ok) "OK" else "missing"

@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
