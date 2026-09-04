package fr.tadikwa.wallhavenrotator

import android.content.Context

/**
 * Durable cadence gate shared by WorkManager and the foreground rotation service.
 *
 * Some OEM schedulers can deliver several delayed periodic executions when the app
 * process becomes active again. The persisted next-due timestamp guarantees that only
 * one of those executions is allowed to rotate; the rest are acknowledged and skipped.
 */
data class AutomaticRunClaim(
    val allowed: Boolean,
    val nowMs: Long,
    val previousDueAtMs: Long,
    val nextDueAtMs: Long,
    val reason: String
)

object AutoRotationPolicy {
    const val MIN_INTERVAL_MINUTES = 15L

    fun normalizeIntervalMinutes(value: Long): Long = value.coerceAtLeast(MIN_INTERVAL_MINUTES)

    fun intervalMillis(intervalMinutes: Long): Long =
        normalizeIntervalMinutes(intervalMinutes) * 60_000L

    fun nextDueAt(nowMs: Long, intervalMinutes: Long): Long =
        nowMs + intervalMillis(intervalMinutes)

    fun isDue(nowMs: Long, dueAtMs: Long): Boolean = dueAtMs > 0L && nowMs >= dueAtMs
}

object AutoRotationGate {
    const val CONFIG_VERSION = 13

    private const val PREFS = "wallhaven_rotation_scheduler"
    private const val KEY_NEXT_DUE_AT = "next_due_at_ms"
    private const val KEY_CONFIG_VERSION = "config_version"
    private const val KEY_LAST_CLAIM_SOURCE = "last_claim_source"
    private const val KEY_LAST_CLAIM_AT = "last_claim_at_ms"
    private val lock = Any()

    fun reset(
        context: Context,
        intervalMinutes: Long,
        reason: String,
        nowMs: Long = System.currentTimeMillis()
    ): Long = synchronized(lock) {
        val next = AutoRotationPolicy.nextDueAt(nowMs, intervalMinutes)
        prefs(context).edit()
            .putLong(KEY_NEXT_DUE_AT, next)
            .putInt(KEY_CONFIG_VERSION, CONFIG_VERSION)
            .putString(KEY_LAST_CLAIM_SOURCE, "reset:$reason")
            .putLong(KEY_LAST_CLAIM_AT, nowMs)
            .apply()
        next
    }

    fun ensureInitialized(
        context: Context,
        intervalMinutes: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Long = synchronized(lock) {
        val current = prefs(context).getLong(KEY_NEXT_DUE_AT, 0L)
        if (current > 0L) return@synchronized current
        val next = AutoRotationPolicy.nextDueAt(nowMs, intervalMinutes)
        prefs(context).edit().putLong(KEY_NEXT_DUE_AT, next).apply()
        next
    }

    fun claimIfDue(
        context: Context,
        intervalMinutes: Long,
        source: String,
        nowMs: Long = System.currentTimeMillis()
    ): AutomaticRunClaim = synchronized(lock) {
        val preferences = prefs(context)
        var dueAt = preferences.getLong(KEY_NEXT_DUE_AT, 0L)
        if (dueAt <= 0L) {
            dueAt = AutoRotationPolicy.nextDueAt(nowMs, intervalMinutes)
            preferences.edit().putLong(KEY_NEXT_DUE_AT, dueAt).apply()
            return@synchronized AutomaticRunClaim(
                allowed = false,
                nowMs = nowMs,
                previousDueAtMs = 0L,
                nextDueAtMs = dueAt,
                reason = "initialized_not_due"
            )
        }

        if (!AutoRotationPolicy.isDue(nowMs, dueAt)) {
            return@synchronized AutomaticRunClaim(
                allowed = false,
                nowMs = nowMs,
                previousDueAtMs = dueAt,
                nextDueAtMs = dueAt,
                reason = "not_due"
            )
        }

        val next = AutoRotationPolicy.nextDueAt(nowMs, intervalMinutes)
        preferences.edit()
            .putLong(KEY_NEXT_DUE_AT, next)
            .putString(KEY_LAST_CLAIM_SOURCE, source)
            .putLong(KEY_LAST_CLAIM_AT, nowMs)
            .apply()
        AutomaticRunClaim(
            allowed = true,
            nowMs = nowMs,
            previousDueAtMs = dueAt,
            nextDueAtMs = next,
            reason = "claimed"
        )
    }

    fun deferAfterManual(
        context: Context,
        intervalMinutes: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Long = synchronized(lock) {
        val next = AutoRotationPolicy.nextDueAt(nowMs, intervalMinutes)
        prefs(context).edit()
            .putLong(KEY_NEXT_DUE_AT, next)
            .putString(KEY_LAST_CLAIM_SOURCE, "manual")
            .putLong(KEY_LAST_CLAIM_AT, nowMs)
            .apply()
        next
    }

    fun nextDueAt(context: Context): Long = prefs(context).getLong(KEY_NEXT_DUE_AT, 0L)

    fun configVersion(context: Context): Int = prefs(context).getInt(KEY_CONFIG_VERSION, 0)

    fun markConfigVersion(context: Context) {
        prefs(context).edit().putInt(KEY_CONFIG_VERSION, CONFIG_VERSION).apply()
    }

    fun clear(context: Context) = synchronized(lock) {
        prefs(context).edit().clear().apply()
    }

    fun statusSummary(context: Context): String {
        val p = prefs(context)
        return buildString {
            append("configVersion=${p.getInt(KEY_CONFIG_VERSION, 0)}")
            append(",nextDueAtMs=${p.getLong(KEY_NEXT_DUE_AT, 0L)}")
            append(",lastClaimSource=${p.getString(KEY_LAST_CLAIM_SOURCE, "") ?: ""}")
            append(",lastClaimAtMs=${p.getLong(KEY_LAST_CLAIM_AT, 0L)}")
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
