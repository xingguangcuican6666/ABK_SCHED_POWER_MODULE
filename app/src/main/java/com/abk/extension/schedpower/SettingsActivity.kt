package com.abk.extension.schedpower

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HOST_PROVIDER_FALLBACK = "com.abk.kernel.extensionhost"
private const val EXTENSION_ID_FALLBACK = "sched_power_profile"

private data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
)

private enum class SchedTab { HOME, APPS }

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hostAuthority = intent.getStringExtra(ABK_EXTENSION_EXTRA_HOST_PROVIDER)
            ?.takeIf { it.isNotBlank() }
            ?: HOST_PROVIDER_FALLBACK
        val extensionId = intent.getStringExtra(ABK_EXTENSION_EXTRA_ID)
            ?.takeIf { it.isNotBlank() }
            ?: EXTENSION_ID_FALLBACK

        setContent {
            SchedPowerTheme {
                SettingsRoute(
                    bridge = remember { HostBridge(contentResolver, hostAuthority, extensionId) },
                    onRequireOobe = {
                        startActivity(
                            Intent(this, OobeActivity::class.java)
                                .putExtra(ABK_EXTENSION_EXTRA_HOST_PROVIDER, hostAuthority)
                                .putExtra(ABK_EXTENSION_EXTRA_ID, extensionId)
                        )
                        finish()
                    },
                    onBack = ::finish,
                )
            }
        }
    }
}

@Composable
private fun SettingsRoute(
    bridge: HostBridge,
    onRequireOobe: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var config by remember { mutableStateOf(ExtensionConfig()) }
    var foregroundPackage by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(SchedTab.HOME) }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedApp by rememberSaveable { mutableStateOf<String?>(null) }
    var apps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }
    var redirectingToOobe by rememberSaveable { mutableStateOf(false) }

    fun reloadAll() {
        scope.launch {
            loading = true
            val stateResult = withContext(Dispatchers.IO) { bridge.read() }
            config = stateResult.getOrElse { ExtensionConfig() }
            foregroundPackage = withContext(Dispatchers.IO) {
                bridge.readForegroundPackage().getOrElse { "" }
            }
            apps = withContext(Dispatchers.IO) { loadInstalledApps(context, showSystemApps) }
            statusMessage = stateResult.exceptionOrNull()?.message.orEmpty()
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        reloadAll()
    }

    LaunchedEffect(showSystemApps) {
        apps = withContext(Dispatchers.IO) { loadInstalledApps(context, showSystemApps) }
    }

    LaunchedEffect(loading, config.oobeCompleted, redirectingToOobe) {
        if (!loading && !config.oobeCompleted && !redirectingToOobe) {
            redirectingToOobe = true
            onRequireOobe()
        }
    }

    val filteredApps = remember(apps, query) {
        val needle = query.trim()
        apps.filter { app ->
            needle.isBlank() ||
                app.label.contains(needle, ignoreCase = true) ||
                app.packageName.contains(needle, ignoreCase = true)
        }
    }
    val selectedAppEntry = remember(filteredApps, selectedApp) {
        filteredApps.firstOrNull { it.packageName == selectedApp }
    }

    BackHandler(enabled = selectedAppEntry != null) {
        selectedApp = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedAppEntry?.label ?: stringResource(R.string.home_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedAppEntry != null) {
                            selectedApp = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = ::reloadAll) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            selectedAppEntry != null -> AppRuleDetail(
                padding = padding,
                app = selectedAppEntry,
                currentRule = config.appRules.firstOrNull { it.packageName == selectedAppEntry.packageName },
                onSave = { rule ->
                    val nextRules = config.appRules
                        .filterNot { it.packageName == selectedAppEntry.packageName } + rule
                    persistConfig(
                        scope = scope,
                        context = context,
                        bridge = bridge,
                        next = config.copy(appRules = nextRules),
                        onApplied = { updated ->
                            config = updated
                            selectedApp = null
                            statusMessage = context.getString(R.string.status_save_ok)
                        },
                        onError = { statusMessage = it }
                    )
                },
                onRemove = {
                    val nextRules = config.appRules.filterNot { it.packageName == selectedAppEntry.packageName }
                    persistConfig(
                        scope = scope,
                        context = context,
                        bridge = bridge,
                        next = config.copy(appRules = nextRules),
                        onApplied = { updated ->
                            config = updated
                            selectedApp = null
                            statusMessage = context.getString(R.string.status_save_ok)
                        },
                        onError = { statusMessage = it }
                    )
                }
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                TabRow(selectedTabIndex = tab.ordinal) {
                    Tab(
                        selected = tab == SchedTab.HOME,
                        onClick = { tab = SchedTab.HOME },
                        text = { Text(stringResource(R.string.tab_home)) },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) }
                    )
                    Tab(
                        selected = tab == SchedTab.APPS,
                        onClick = { tab = SchedTab.APPS },
                        text = { Text(stringResource(R.string.tab_apps)) },
                        icon = { Icon(Icons.Default.Apps, contentDescription = null) }
                    )
                }

                when (tab) {
                    SchedTab.HOME -> HomeTab(
                        modifier = Modifier.weight(1f),
                        config = config,
                        foregroundPackage = foregroundPackage,
                        statusMessage = statusMessage,
                        onModeChange = { mode -> config = config.copy(defaultMode = mode) },
                        onDisplayStateChange = { value ->
                            config = config.copy(conservativeDisplayState = value.toIntOrNull() ?: config.conservativeDisplayState)
                        },
                        onPerAppEnabledChange = { enabled -> config = config.copy(perAppEnabled = enabled) },
                        onSave = {
                            persistConfig(
                                scope = scope,
                                context = context,
                                bridge = bridge,
                                next = config,
                                onApplied = { updated ->
                                    config = updated
                                    statusMessage = context.getString(R.string.status_save_ok)
                                },
                                onError = { statusMessage = it }
                            )
                        }
                    )

                    SchedTab.APPS -> AppsTab(
                        modifier = Modifier.weight(1f),
                        query = query,
                        onQueryChange = { query = it },
                        showSystemApps = showSystemApps,
                        onShowSystemAppsChange = { showSystemApps = it },
                        apps = filteredApps,
                        rules = config.appRules,
                        onOpen = { selectedApp = it.packageName }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeTab(
    modifier: Modifier,
    config: ExtensionConfig,
    foregroundPackage: String,
    statusMessage: String,
    onModeChange: (String) -> Unit,
    onDisplayStateChange: (String) -> Unit,
    onPerAppEnabledChange: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.status_card_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(config.summary(), style = MaterialTheme.typography.bodyLarge)
                if (foregroundPackage.isNotBlank()) {
                    Text(
                        stringResource(R.string.current_foreground, foregroundPackage),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (statusMessage.isNotBlank()) {
                    Text(statusMessage, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.global_title), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = config.defaultMode != "perf",
                        onClick = { onModeChange("balanced") },
                        label = { Text(stringResource(R.string.mode_balanced)) },
                        leadingIcon = if (config.defaultMode != "perf") ({ Icon(Icons.Default.Check, contentDescription = null) }) else null
                    )
                    FilterChip(
                        selected = config.defaultMode == "perf",
                        onClick = { onModeChange("perf") },
                        label = { Text(stringResource(R.string.mode_perf)) },
                        leadingIcon = if (config.defaultMode == "perf") ({ Icon(Icons.Default.Check, contentDescription = null) }) else null
                    )
                }
                OutlinedTextField(
                    value = config.conservativeDisplayState.toString(),
                    onValueChange = onDisplayStateChange,
                    label = { Text(stringResource(R.string.display_state_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.per_app_label), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.per_app_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = config.perAppEnabled,
                        onCheckedChange = onPerAppEnabledChange
                    )
                }
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_save))
                }
            }
        }
    }
}

@Composable
private fun AppsTab(
    modifier: Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    showSystemApps: Boolean,
    onShowSystemAppsChange: (Boolean) -> Unit,
    apps: List<InstalledAppEntry>,
    rules: List<ExtensionConfig.AppRule>,
    onOpen: (InstalledAppEntry) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item("search") {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                label = { Text(stringResource(R.string.search_apps)) },
                singleLine = true
            )
        }
        item("system") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.show_system_apps))
                Switch(checked = showSystemApps, onCheckedChange = onShowSystemAppsChange)
            }
        }
        if (apps.isEmpty()) {
            item("empty") {
                Text(stringResource(R.string.no_apps), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(apps, key = { it.packageName }) { app ->
                val rule = rules.firstOrNull { it.packageName == app.packageName }
                Card(
                    onClick = { onOpen(app) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (rule == null) {
                                stringResource(R.string.rule_not_set)
                            } else {
                                stringResource(
                                    R.string.rule_summary,
                                    if (rule.enabled) stringResource(R.string.rule_enabled) else stringResource(R.string.rule_disabled),
                                    if (rule.mode == "perf") stringResource(R.string.mode_perf) else stringResource(R.string.mode_balanced),
                                    rule.conservativeDisplayState
                                )
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRuleDetail(
    padding: PaddingValues,
    app: InstalledAppEntry,
    currentRule: ExtensionConfig.AppRule?,
    onSave: (ExtensionConfig.AppRule) -> Unit,
    onRemove: () -> Unit,
) {
    var enabled by rememberSaveable(app.packageName) { mutableStateOf(currentRule?.enabled ?: true) }
    var mode by rememberSaveable(app.packageName) { mutableStateOf(currentRule?.mode ?: "balanced") }
    var displayState by rememberSaveable(app.packageName) {
        mutableStateOf((currentRule?.conservativeDisplayState ?: 9).toString())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.rule_enable))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode != "perf",
                        onClick = { mode = "balanced" },
                        label = { Text(stringResource(R.string.mode_balanced)) },
                        leadingIcon = if (mode != "perf") ({ Icon(Icons.Default.Check, contentDescription = null) }) else null
                    )
                    FilterChip(
                        selected = mode == "perf",
                        onClick = { mode = "perf" },
                        label = { Text(stringResource(R.string.mode_perf)) },
                        leadingIcon = if (mode == "perf") ({ Icon(Icons.Default.Check, contentDescription = null) }) else null
                    )
                }
                OutlinedTextField(
                    value = displayState,
                    onValueChange = { displayState = it },
                    label = { Text(stringResource(R.string.display_state_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSave(
                                ExtensionConfig.AppRule(
                                    packageName = app.packageName,
                                    enabled = enabled,
                                    mode = mode,
                                    conservativeDisplayState = displayState.toIntOrNull() ?: 9
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_save))
                    }
                    TextButton(onClick = onRemove, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.remove_rule))
                    }
                }
            }
        }
    }
}

private fun persistConfig(
    scope: CoroutineScope,
    context: Context,
    bridge: HostBridge,
    next: ExtensionConfig,
    onApplied: (ExtensionConfig) -> Unit,
    onError: (String) -> Unit,
) {
    scope.launch {
        val result = withContext(Dispatchers.IO) { bridge.write(next) }
        if (result.isFailure) {
            onError(result.exceptionOrNull()?.message ?: context.getString(R.string.status_save_fail))
            return@launch
        }
        val applyResult = withContext(Dispatchers.IO) {
            SchedulerPolicyController.applyCurrentConfig(context, bridge)
        }
        if (applyResult.isSuccess) {
            ContextCompat.startForegroundService(context, Intent(context, SchedulerPolicyService::class.java))
            onApplied(next)
        } else {
            onError(applyResult.exceptionOrNull()?.message ?: context.getString(R.string.status_save_fail))
        }
    }
}

@Suppress("DEPRECATION")
private fun loadInstalledApps(context: Context, showSystemApps: Boolean): List<InstalledAppEntry> {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val launcherPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(0)
        )
    } else {
        packageManager.queryIntentActivities(launcherIntent, 0)
    }.mapNotNull { it.activityInfo?.packageName }.toSet()

    val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        packageManager.getInstalledApplications(0)
    }

    return installedApps.asSequence()
        .mapNotNull { info ->
            val packageName = info.packageName ?: return@mapNotNull null
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isLaunchable = packageName in launcherPackages
            if (!showSystemApps && (isSystem || !isLaunchable)) {
                return@mapNotNull null
            }
            InstalledAppEntry(
                packageName = packageName,
                label = packageManager.getApplicationLabel(info).toString().ifBlank { packageName },
                isSystemApp = isSystem
            )
        }
        .sortedWith(compareBy<InstalledAppEntry> { it.isSystemApp }.thenBy { it.label.lowercase() })
        .toList()
}
