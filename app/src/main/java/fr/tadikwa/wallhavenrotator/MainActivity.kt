package fr.tadikwa.wallhavenrotator

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data object Downloading : UpdateUiState
    data object Installing : UpdateUiState
    data class Available(val update: UpdateInfo) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

private sealed interface ManualRotationUiState {
    data object Idle : ManualRotationUiState
    data object Running : ManualRotationUiState
    data class Success(val message: String) : ManualRotationUiState
    data class Error(val message: String) : ManualRotationUiState
}

private sealed interface DiagnosticsUiState {
    data object Idle : DiagnosticsUiState
    data object Exporting : DiagnosticsUiState
    data class Error(val message: String) : DiagnosticsUiState
}

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        val settings = SettingsRepository(applicationContext).load()
        if (settings.enabled) {
            // Returning from Android's exact-alarm special-access screen must immediately
            // re-register the deadline as exact without postponing it.
            RotationScheduler.reconcile(applicationContext, settings)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Diagnostics.log(applicationContext, "app.open")

        setContent {
            WallhavenRotatorTheme {
                val context = LocalContext.current
                val repository = remember { SettingsRepository(context) }
                var settings by remember { mutableStateOf(repository.load()) }
                var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
                var manualRotationState by remember {
                    mutableStateOf<ManualRotationUiState>(ManualRotationUiState.Idle)
                }
                var diagnosticsState by remember {
                    mutableStateOf<DiagnosticsUiState>(DiagnosticsUiState.Idle)
                }
                var cacheStats by remember { mutableStateOf(WallpaperCache(context).stats()) }

                fun applyUpdateResult(result: UpdateCheckResult) {
                    updateState = when (result) {
                        UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                        is UpdateCheckResult.Available -> UpdateUiState.Available(result.update)
                        is UpdateCheckResult.Failed -> UpdateUiState.Error(result.message)
                    }
                }

                LaunchedEffect(Unit) {
                    // Alpha.11 keeps the next deadline in AlarmManager, outside our
                    // process, so MagicOS can kill the app without deleting the wake-up.
                    val schedulerMigration =
                        AutoRotationGate.configVersion(context.applicationContext) !=
                            AutoRotationGate.CONFIG_VERSION
                    RotationScheduler.reconcile(context.applicationContext, settings)

                    if (
                        schedulerMigration &&
                        settings.enabled &&
                        !RotationAlarmScheduler.canScheduleExact(context)
                    ) {
                        Toast.makeText(
                            context,
                            "Autorise « Alarmes et rappels » pour la rotation automatique en arrière-plan.",
                            Toast.LENGTH_LONG
                        ).show()
                        runCatching {
                            context.startActivity(
                                RotationAlarmScheduler.exactAlarmAccessIntent(context)
                            )
                        }.onFailure { failure ->
                            Diagnostics.log(
                                context,
                                "alarm.permission.open_failure",
                                level = "WARN",
                                throwable = failure
                            )
                        }
                    }

                    thread(name = "wallhaven-cache-maintenance") {
                        val maintained = WallpaperCache(context.applicationContext).maintenance(settings)
                        runOnUiThread {
                            if (!isDestroyed && !isFinishing) cacheStats = maintained
                        }
                    }
                    if (UpdateManager.shouldAutoCheck(context)) {
                        updateState = UpdateUiState.Checking
                        UpdateManager.checkAsync(context, manual = false, callback = ::applyUpdateResult)
                    }
                }

                MainScreen(
                    settings = settings,
                    onSettingsChanged = { settings = it },
                    updateState = updateState,
                    manualRotationState = manualRotationState,
                    diagnosticsState = diagnosticsState,
                    cacheStats = cacheStats,
                    onCheckUpdate = {
                        updateState = UpdateUiState.Checking
                        UpdateManager.checkAsync(context, manual = true, callback = ::applyUpdateResult)
                    },
                    onInstallUpdate = { update ->
                        if (!UpdateManager.canRequestPackageInstalls(context)) {
                            context.startActivity(UpdateManager.unknownSourcesSettingsIntent(context))
                            Toast.makeText(
                                context,
                                "Autorise Wallhaven Rotator à installer ses mises à jour, puis relance l'installation.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            updateState = UpdateUiState.Downloading
                            UpdateManager.downloadAndInstallAsync(context, update) { result ->
                                updateState = result.fold(
                                    onSuccess = { UpdateUiState.Installing },
                                    onFailure = { UpdateUiState.Error(it.message ?: "Échec de la mise à jour") }
                                )
                            }
                        }
                    },
                    onSave = {
                        repository.save(settings)
                        Diagnostics.log(
                            context,
                            "settings.save",
                            fields = mapOf(
                                "enabled" to settings.enabled,
                                "intervalMinutes" to settings.intervalMinutes,
                                "target" to settings.targetMode.name,
                                "orientation" to settings.orientationMode.name,
                                "cacheLimitMb" to settings.cacheLimitMb,
                                "homeContentFilter" to settings.homeProfile.contentFilter.name,
                                "lockContentFilter" to settings.lockProfile.contentFilter.name
                            )
                        )
                        RotationScheduler.configure(context, settings)
                        if (settings.enabled) {
                            RotationScheduler.preload(context)
                            if (!RotationAlarmScheduler.canScheduleExact(context)) {
                                Toast.makeText(
                                    context,
                                    "Autorise « Alarmes et rappels » pour une rotation fiable en arrière-plan.",
                                    Toast.LENGTH_LONG
                                ).show()
                                runCatching {
                                    context.startActivity(
                                        RotationAlarmScheduler.exactAlarmAccessIntent(context)
                                    )
                                }.onFailure { failure ->
                                    Diagnostics.log(
                                        context,
                                        "alarm.permission.open_failure",
                                        level = "WARN",
                                        throwable = failure
                                    )
                                }
                            }
                        }
                        thread(name = "wallhaven-cache-maintenance-save") {
                            val maintained = WallpaperCache(context.applicationContext).maintenance(settings)
                            runOnUiThread {
                                if (!isDestroyed && !isFinishing) cacheStats = maintained
                            }
                        }
                        val message = if (settings.enabled) {
                            "Réglages enregistrés, préchargement lancé."
                        } else {
                            "Réglages enregistrés, rotation en pause."
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    },
                    onRotateNow = {
                        if (manualRotationState !is ManualRotationUiState.Running) {
                            val snapshot = settings
                            repository.save(snapshot)
                            manualRotationState = ManualRotationUiState.Running

                            // "Changer maintenant" also persists the edited settings. If
                            // automatic rotation is enabled, rebuild one clean schedule from
                            // this point so the next automatic transition cannot arrive right
                            // behind the manual one.
                            if (snapshot.enabled) {
                                RotationScheduler.configure(context, snapshot, reason = "manual_change")
                                RotationScheduler.requestManualPriority(context, snapshot.intervalMinutes)
                            } else {
                                RotationScheduler.configure(context, snapshot, reason = "manual_change_disabled")
                            }
                            Diagnostics.log(
                                context,
                                "manual_rotation.requested",
                                fields = mapOf("target" to snapshot.targetMode.name)
                            )
                            thread(name = "wallhaven-manual-rotation") {
                                val result = runCatching {
                                    RotationEngine.rotateOnce(context.applicationContext, snapshot)
                                }
                                if (snapshot.enabled) {
                                    // The interrupted preload is intentionally replaced only
                                    // after the visible manual rotation has had first priority.
                                    RotationScheduler.preload(context.applicationContext)
                                }
                                runOnUiThread {
                                    if (!isDestroyed && !isFinishing) {
                                        manualRotationState = result.fold(
                                            onSuccess = { outcome ->
                                                ManualRotationUiState.Success(outcome.userMessage())
                                            },
                                            onFailure = { failure ->
                                                ManualRotationUiState.Error(
                                                    failure.message ?: "Échec du changement de fond d'écran"
                                                )
                                            }
                                        )
                                        cacheStats = WallpaperCache(context.applicationContext).stats()
                                        val toast = when (val state = manualRotationState) {
                                            is ManualRotationUiState.Success -> state.message
                                            is ManualRotationUiState.Error -> state.message
                                            else -> null
                                        }
                                        if (toast != null) Toast.makeText(context, toast, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    },
                    onShareDiagnostics = {
                        if (diagnosticsState !is DiagnosticsUiState.Exporting) {
                            val snapshot = settings
                            diagnosticsState = DiagnosticsUiState.Exporting
                            thread(name = "wallhaven-diagnostics-export") {
                                val result = runCatching {
                                    Diagnostics.createReport(context.applicationContext, snapshot)
                                }
                                runOnUiThread {
                                    if (!isDestroyed && !isFinishing) {
                                        result.fold(
                                            onSuccess = { report ->
                                                diagnosticsState = DiagnosticsUiState.Idle
                                                val share = Diagnostics.shareIntent(context, report)
                                                startActivity(Intent.createChooser(share, "Partager les diagnostics"))
                                            },
                                            onFailure = { failure ->
                                                diagnosticsState = DiagnosticsUiState.Error(
                                                    failure.message ?: "Impossible d'exporter les diagnostics"
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    onClearCache = {
                        thread(name = "wallhaven-cache-clear") {
                            val cleared = WallpaperCache(context.applicationContext).clearAll()
                            runOnUiThread {
                                if (!isDestroyed && !isFinishing) {
                                    cacheStats = cleared
                                    Toast.makeText(context, "Cache d'images vidé.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun WallhavenRotatorTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    updateState: UpdateUiState,
    manualRotationState: ManualRotationUiState,
    diagnosticsState: DiagnosticsUiState,
    cacheStats: CacheStats,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (UpdateInfo) -> Unit,
    onSave: () -> Unit,
    onRotateNow: () -> Unit,
    onShareDiagnostics: () -> Unit,
    onClearCache: () -> Unit
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Wallhaven Rotator") })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(2.dp)) }
            item {
                StatusCard(
                    device = DeviceProfile.displayLabel(context, settings.orientationMode),
                    cacheStats = cacheStats
                )
            }
            item {
                SettingCard("Rotation automatique") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (settings.enabled) "Activée" else "En pause", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Alarme système Android + WorkManager de secours ; intervalle minimal : 15 min",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = settings.enabled,
                            onCheckedChange = { onSettingsChanged(settings.copy(enabled = it)) }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    IntervalDropdown(settings.intervalMinutes) {
                        onSettingsChanged(settings.copy(intervalMinutes = it))
                    }
                }
            }
            item {
                SettingCard("Destination") {
                    EnumDropdown(
                        label = "Appliquer sur",
                        value = settings.targetMode,
                        values = TargetMode.entries,
                        text = { it.label },
                        onSelected = { onSettingsChanged(settings.copy(targetMode = it)) }
                    )
                    Spacer(Modifier.height(10.dp))
                    EnumDropdown(
                        label = "Orientation",
                        value = settings.orientationMode,
                        values = OrientationMode.entries,
                        text = { it.label },
                        onSelected = { onSettingsChanged(settings.copy(orientationMode = it)) }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Auto : téléphone = portrait ; tablette = orientation courante, avec paysage comme usage naturel.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (settings.targetMode != TargetMode.LOCK) {
                item {
                    ProfileCard("Profil Accueil", settings.homeProfile) {
                        onSettingsChanged(settings.copy(homeProfile = it))
                    }
                }
            }

            if (settings.targetMode == TargetMode.LOCK || settings.targetMode == TargetMode.BOTH_INDEPENDENT) {
                item {
                    ProfileCard("Profil Verrouillage", settings.lockProfile) {
                        onSettingsChanged(settings.copy(lockProfile = it))
                    }
                }
            }

            item {
                UpdateCard(
                    state = updateState,
                    onCheck = onCheckUpdate,
                    onInstall = onInstallUpdate
                )
            }

            item {
                DiagnosticsCard(
                    state = diagnosticsState,
                    onShare = onShareDiagnostics
                )
            }

            item {
                SettingCard("Cache et réseau") {
                    Text(
                        "${cacheStats.files} image(s) • ${formatBytes(cacheStats.bytes)} utilisés. Le cache est nettoyé automatiquement et ne dépasse pas la limite globale choisie.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    CacheLimitDropdown(settings.cacheLimitMb) {
                        onSettingsChanged(settings.copy(cacheLimitMb = it))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Chaque pool vise ${PoolPolicy.TARGET_SIZE} images et se recharge à ${PoolPolicy.LOW_WATERMARK} ou moins. Les anciens pools et fichiers orphelins sont supprimés automatiquement.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onClearCache, modifier = Modifier.fillMaxWidth()) {
                        Text("Vider le cache")
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                        Text("Enregistrer")
                    }
                    OutlinedButton(
                        onClick = onRotateNow,
                        enabled = manualRotationState !is ManualRotationUiState.Running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (manualRotationState is ManualRotationUiState.Running) {
                                "Changement…"
                            } else {
                                "Changer maintenant"
                            }
                        )
                    }
                }
            }
            item {
                when (manualRotationState) {
                    ManualRotationUiState.Idle -> Unit
                    ManualRotationUiState.Running -> Text(
                        "Téléchargement / application en cours…",
                        style = MaterialTheme.typography.bodySmall
                    )
                    is ManualRotationUiState.Success -> Text(
                        manualRotationState.message,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    is ManualRotationUiState.Error -> Text(
                        manualRotationState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item {
                Text(
                    "SFW uniquement • aucune télémétrie • projet non affilié à Wallhaven",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun UpdateCard(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onInstall: (UpdateInfo) -> Unit
) {
    SettingCard("Mises à jour") {
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            fontWeight = FontWeight.SemiBold
        )
        Text(
            if (BuildConfig.VERSION_NAME.contains('-')) {
                "Canal : préversions et versions stables"
            } else {
                "Canal : versions stables"
            },
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(10.dp))

        when (state) {
            UpdateUiState.Idle -> Text("Vérification automatique : au maximum une fois par 24 h.")
            UpdateUiState.Checking -> Text("Vérification en cours…")
            UpdateUiState.UpToDate -> Text("Aucune mise à jour disponible.")
            UpdateUiState.Downloading -> Text("Téléchargement et vérification de l’APK…")
            UpdateUiState.Installing -> Text("APK vérifié. L’installateur Android a été ouvert.")
            is UpdateUiState.Available -> {
                Text(
                    "Version ${state.update.versionName} disponible.",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "L’APK sera contrôlé (SHA-256, package et signature) avant l’installation.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            is UpdateUiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onCheck,
                enabled = state !is UpdateUiState.Checking && state !is UpdateUiState.Downloading,
                modifier = Modifier.weight(1f)
            ) {
                Text("Vérifier")
            }
            if (state is UpdateUiState.Available) {
                Button(
                    onClick = { onInstall(state.update) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Installer")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(
    state: DiagnosticsUiState,
    onShare: () -> Unit
) {
    SettingCard("Diagnostics") {
        Text(
            "Les logs restent sur l'appareil. L'export contient l'état du cache, WorkManager et les résultats séparés Accueil / Verrouillage.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onShare,
            enabled = state !is DiagnosticsUiState.Exporting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state is DiagnosticsUiState.Exporting) "Préparation…" else "Partager les diagnostics")
        }
        if (state is DiagnosticsUiState.Error) {
            Spacer(Modifier.height(8.dp))
            Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusCard(device: String, cacheStats: CacheStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(device, fontWeight = FontWeight.Bold)
            Text(
                "${cacheStats.files} wallpaper(s) • ${formatBytes(cacheStats.bytes)} en cache",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ProfileCard(title: String, profile: ProfileSettings, onChanged: (ProfileSettings) -> Unit) {
    SettingCard(title) {
        EnumDropdown(
            label = "Source",
            value = profile.source,
            values = SourceMode.entries,
            text = { it.label },
            onSelected = { onChanged(profile.copy(source = it)) }
        )
        Spacer(Modifier.height(10.dp))
        EnumDropdown(
            label = "Catégorie",
            value = profile.category,
            values = CategoryMode.entries,
            text = { it.label },
            onSelected = { onChanged(profile.copy(category = it)) }
        )
        Spacer(Modifier.height(10.dp))
        EnumDropdown(
            label = "Contenu suggestif",
            value = profile.contentFilter,
            values = ContentFilterMode.entries,
            text = { it.label },
            onSelected = { onChanged(profile.copy(contentFilter = it)) }
        )
        Spacer(Modifier.height(6.dp))
        Text(
            ContentFilterPolicy.description(profile.contentFilter),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = profile.query,
            onValueChange = { onChanged(profile.copy(query = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Recherche / tags (optionnel)") },
            supportingText = { Text("Syntaxe Wallhaven : +tag, -tag, id:123…") }
        )
    }
}

@Composable
private fun IntervalDropdown(value: Long, onSelected: (Long) -> Unit) {
    val choices = listOf(
        15L to "15 min",
        30L to "30 min",
        60L to "1 h",
        180L to "3 h",
        360L to "6 h",
        720L to "12 h",
        1440L to "24 h"
    )
    DropdownSelector(
        label = "Fréquence",
        selectedText = choices.firstOrNull { it.first == value }?.second ?: "$value min",
        choices = choices,
        itemText = { it.second },
        onSelected = { onSelected(it.first) }
    )
}

@Composable
private fun CacheLimitDropdown(value: Int, onSelected: (Int) -> Unit) {
    val choices = CachePolicy.ALLOWED_LIMITS_MB.map { it to "$it Mo" }
    DropdownSelector(
        label = "Limite du cache",
        selectedText = choices.firstOrNull { it.first == value }?.second ?: "${CachePolicy.DEFAULT_LIMIT_MB} Mo",
        choices = choices,
        itemText = { it.second },
        onSelected = { onSelected(it.first) }
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f Go", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f Mo", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f Ko", bytes / 1024.0)
    else -> "$bytes o"
}

@Composable
private fun <T> EnumDropdown(
    label: String,
    value: T,
    values: List<T>,
    text: (T) -> String,
    onSelected: (T) -> Unit
) {
    DropdownSelector(label, text(value), values, text, onSelected)
}

@Composable
private fun <T> DropdownSelector(
    label: String,
    selectedText: String,
    choices: List<T>,
    itemText: (T) -> String,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selectedText)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(itemText(choice)) },
                    onClick = {
                        expanded = false
                        onSelected(choice)
                    }
                )
            }
        }
    }
}
