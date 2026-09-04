package fr.tadikwa.wallhavenrotator

enum class TargetMode(val label: String) {
    HOME("Accueil"),
    LOCK("Verrouillage"),
    BOTH_SAME("Les deux - même image"),
    BOTH_INDEPENDENT("Les deux - indépendants")
}

enum class OrientationMode(val label: String) {
    AUTO("Automatique"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Paysage")
}

enum class ResolvedOrientation {
    PORTRAIT,
    LANDSCAPE
}

enum class SourceMode(val label: String) {
    TRENDING("Tendance"),
    POPULAR("Populaires"),
    NEW("Nouveautés"),
    RANDOM("Aléatoire")
}

enum class CategoryMode(val label: String, val apiValue: String) {
    ALL("Tout", "111"),
    GENERAL("General", "100"),
    ANIME("Anime", "010"),
    PEOPLE("People", "001")
}

enum class ContentFilterMode(val label: String) {
    STANDARD("Standard"),
    REDUCED("Moins suggestif"),
    STRICT("Strict")
}

data class ProfileSettings(
    val source: SourceMode = SourceMode.RANDOM,
    val category: CategoryMode = CategoryMode.ALL,
    val query: String = "",
    val contentFilter: ContentFilterMode = ContentFilterMode.REDUCED
)

data class AppSettings(
    val enabled: Boolean = false,
    val intervalMinutes: Long = 60,
    val targetMode: TargetMode = TargetMode.BOTH_SAME,
    val orientationMode: OrientationMode = OrientationMode.AUTO,
    val cacheLimitMb: Int = CachePolicy.DEFAULT_LIMIT_MB,
    val homeProfile: ProfileSettings = ProfileSettings(),
    val lockProfile: ProfileSettings = ProfileSettings(source = SourceMode.TRENDING, category = CategoryMode.GENERAL)
)

data class WallhavenItem(
    val id: String,
    val path: String,
    val width: Int,
    val height: Int
)

data class CachedWallpaper(
    val id: String,
    val fileName: String
)
