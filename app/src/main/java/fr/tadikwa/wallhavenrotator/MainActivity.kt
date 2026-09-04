package fr.tadikwa.wallhavenrotator

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WallhavenRotatorTheme {
                val context = LocalContext.current
                val repository = remember { SettingsRepository(context) }
                var settings by remember { mutableStateOf(repository.load()) }

                MainScreen(
                    settings = settings,
                    onSettingsChanged = { settings = it },
                    onSave = {
                        repository.save(settings)
                        RotationScheduler.configure(context, settings)
                        if (settings.enabled) RotationScheduler.preload(context)
                        val message = if (settings.enabled) {
                            "Réglages enregistrés, préchargement lancé."
                        } else {
                            "Réglages enregistrés, rotation en pause."
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    },
                    onRotateNow = {
                        repository.save(settings)
                        RotationScheduler.rotateNow(context)
                        Toast.makeText(context, "Changement demandé.", Toast.LENGTH_SHORT).show()
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
    onSave: () -> Unit,
    onRotateNow: () -> Unit
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
                    cacheCount = WallpaperCache(context).countAll()
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
                            Text("WorkManager, intervalle minimal Android : 15 min", style = MaterialTheme.typography.bodySmall)
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
                SettingCard("Cache et réseau") {
                    Text(
                        "La rotation consomme d'abord le cache local. Une recharge Wallhaven n'est déclenchée que lorsque le pool tombe à ${PoolPolicy.LOW_WATERMARK} images ou moins. Chaque pool vise ${PoolPolicy.TARGET_SIZE} images.",
                        style = MaterialTheme.typography.bodyMedium
                    )
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
                    OutlinedButton(onClick = onRotateNow, modifier = Modifier.weight(1f)) {
                        Text("Changer maintenant")
                    }
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
private fun StatusCard(device: String, cacheCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(device, fontWeight = FontWeight.Bold)
            Text("$cacheCount wallpaper(s) actuellement en cache", style = MaterialTheme.typography.bodyMedium)
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
