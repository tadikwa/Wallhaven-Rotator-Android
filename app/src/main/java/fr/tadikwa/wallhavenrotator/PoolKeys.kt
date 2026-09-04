package fr.tadikwa.wallhavenrotator

import java.security.MessageDigest

object PoolKeys {
    fun home(profile: ProfileSettings, orientation: ResolvedOrientation): String =
        build("home", profile, orientation)

    fun lock(profile: ProfileSettings, orientation: ResolvedOrientation): String =
        build("lock", profile, orientation)

    fun shared(profile: ProfileSettings, orientation: ResolvedOrientation): String =
        build("shared", profile, orientation)

    fun active(settings: AppSettings): Set<String> = buildSet {
        ResolvedOrientation.entries.forEach { orientation ->
            when (settings.targetMode) {
                TargetMode.HOME -> add(home(settings.homeProfile, orientation))
                TargetMode.LOCK -> add(lock(settings.lockProfile, orientation))
                TargetMode.BOTH_SAME -> add(shared(settings.homeProfile, orientation))
                TargetMode.BOTH_INDEPENDENT -> {
                    add(home(settings.homeProfile, orientation))
                    add(lock(settings.lockProfile, orientation))
                }
            }
        }
    }

    private fun build(prefix: String, profile: ProfileSettings, orientation: ResolvedOrientation): String {
        val canonical = listOf(
            profile.source.name,
            profile.category.name,
            profile.query.trim(),
            profile.contentFilter.name
        ).joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "${prefix}_${orientation.name.lowercase()}_$digest"
    }
}
