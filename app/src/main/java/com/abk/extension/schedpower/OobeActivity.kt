package com.abk.extension.schedpower

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OobeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hostAuthority = intent.getStringExtra(ABK_EXTENSION_EXTRA_HOST_PROVIDER)
            ?.takeIf { it.isNotBlank() }
            ?: ABK_EXTENSION_DEFAULT_HOST_PROVIDER
        val extensionId = intent.getStringExtra(ABK_EXTENSION_EXTRA_ID)
            ?.takeIf { it.isNotBlank() }
            ?: ABK_EXTENSION_DEFAULT_ID

        setContent {
            SchedPowerTheme {
                OobeRoute(
                    bridge = remember { HostBridge(contentResolver, hostAuthority, extensionId) },
                    onComplete = {
                        startActivity(
                            Intent(this, SettingsActivity::class.java)
                                .putExtra(ABK_EXTENSION_EXTRA_HOST_PROVIDER, hostAuthority)
                                .putExtra(ABK_EXTENSION_EXTRA_ID, extensionId)
                        )
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun OobeRoute(
    bridge: HostBridge,
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var defaultMode by remember { mutableStateOf("balanced") }
    var displayState by remember { mutableStateOf("9") }
    var perAppEnabled by remember { mutableStateOf(false) }
    var existingRules by remember { mutableStateOf<List<ExtensionConfig.AppRule>>(emptyList()) }
    var statusMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { bridge.read() }.getOrElse { ExtensionConfig() }
        defaultMode = loaded.defaultMode
        displayState = loaded.conservativeDisplayState.toString()
        perAppEnabled = loaded.perAppEnabled
        existingRules = loaded.appRules
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.oobe_title)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.oobe_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.oobe_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.global_title), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = defaultMode != "perf",
                            onClick = { defaultMode = "balanced" },
                            label = { Text(stringResource(R.string.mode_balanced)) },
                            leadingIcon = if (defaultMode != "perf") ({ Icon(Icons.Default.Check, contentDescription = null) }) else null
                        )
                        FilterChip(
                            selected = defaultMode == "perf",
                            onClick = { defaultMode = "perf" },
                            label = { Text(stringResource(R.string.mode_perf)) },
                            leadingIcon = if (defaultMode == "perf") ({ Icon(Icons.Default.Check, contentDescription = null) }) else null
                        )
                    }
                    OutlinedTextField(
                        value = displayState,
                        onValueChange = { displayState = it },
                        label = { Text(stringResource(R.string.display_state_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.per_app_label), modifier = Modifier.weight(1f))
                        Switch(
                            checked = perAppEnabled,
                            onCheckedChange = { perAppEnabled = it }
                        )
                    }
                    if (statusMessage.isNotBlank()) {
                        Text(statusMessage, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            persistConfig(
                                scope = scope,
                                context = context,
                                bridge = bridge,
                                next = ExtensionConfig(
                                    defaultMode = defaultMode,
                                    conservativeDisplayState = displayState.toIntOrNull() ?: 9,
                                    perAppEnabled = perAppEnabled,
                                    oobeCompleted = true,
                                    appRules = existingRules
                                ),
                                onApplied = { onComplete() },
                                onError = { statusMessage = it }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.oobe_save))
                    }
                }
            }
        }
    }
}
