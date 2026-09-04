package fr.tadikwa.wallhavenrotator

import android.content.Context

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("wallhaven_rotator", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        enabled = prefs.getBoolean("enabled", false),
        intervalMinutes = prefs.getLong("interval", 60L),
        targetMode = enumValueOrDefault(prefs.getString("target", null), TargetMode.BOTH_SAME),
        orientationMode = enumValueOrDefault(prefs.getString("orientation", null), OrientationMode.AUTO),
        homeProfile = loadProfile("home", ProfileSettings()),
        lockProfile = loadProfile(
            "lock",
            ProfileSettings(SourceMode.TRENDING, CategoryMode.GENERAL)
        )
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putBoolean("enabled", settings.enabled)
            .putLong("interval", settings.intervalMinutes)
            .putString("target", settings.targetMode.name)
            .putString("orientation", settings.orientationMode.name)
            .apply()
        saveProfile("home", settings.homeProfile)
        saveProfile("lock", settings.lockProfile)
    }

    private fun loadProfile(prefix: String, defaults: ProfileSettings): ProfileSettings = ProfileSettings(
        source = enumValueOrDefault(prefs.getString("${prefix}_source", null), defaults.source),
        category = enumValueOrDefault(prefs.getString("${prefix}_category", null), defaults.category),
        query = prefs.getString("${prefix}_query", defaults.query) ?: defaults.query
    )

    private fun saveProfile(prefix: String, profile: ProfileSettings) {
        prefs.edit()
            .putString("${prefix}_source", profile.source.name)
            .putString("${prefix}_category", profile.category.name)
            .putString("${prefix}_query", profile.query)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(default)
}
