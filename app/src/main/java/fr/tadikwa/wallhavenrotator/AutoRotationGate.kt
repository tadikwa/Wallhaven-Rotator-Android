package fr.tadikwa.wallhavenrotator

import android.content.Context
import android.os.SystemClock
import kotlin.math.max

/**
 * Monotonic cadence gate for automatic rotation.
 *
 * Automatic callers only INSPECT the due state before a wallpaper write. The durable
 * deadline is advanced only after a successful rotation. This prevents a WorkManager
 * or AlarmManager wake-up from consuming an interval while another rotation is busy or
 * while WallpaperManager is stalled by the OEM.
 */
data class AutomaticRunEligibility(
    val allowed: Boolean,
    val nowMs: Long,
    val dueAtMs: Long,
    val reason: String,
    val earlyByMs: Long = 0L
)

data class AutomaticRunCompletion(
    val committed: Boolean,
    val previousDueAtMs: Long,
    val nextDueAtMs: Long,
    val reason: String
)

object AutoRotationPolicy {
    const val MIN_INTERVAL_MINUTES = 15L
    const val ALARM_EARLY_TOLERANCE_MS = 3L * 60L * 1_000L
    private const val FUTURE_SANITY_MARGIN_MS = 5L * 60L * 1_000L

    fun normalizeIntervalMinutes(value: Long): Long = value.coerceAtLeast(MIN_INTERVAL_MINUTES)

    fun intervalMillis(intervalMinutes: Long): Long =
        normalizeIntervalMinutes(intervalMinutes) * 60_000L

    fun nextDueAt(nowMs: Long, intervalMinutes: Long): Long =
        nowMs + intervalMillis(intervalMinutes)

    fun isDue(nowMs: Long, dueAtMs: Long): Boolean = dueAtMs > 0L && nowMs >= dueAtMs

    fun isDueForAlarm(nowMs: Long, dueAtMs: Long): Boolean =
        dueAtMs > 0L && nowMs + ALARM_EARLY_TOLERANCE_MS >= dueAtMs

    fun looksLikeSameBootDeadline(nowMs: Long, dueAtMs: Long, intervalMinutes: Long): Boolean {
        if (dueAtMs <= 0L) return false
        val maxExpectedFuture = nowMs + intervalMillis(intervalMinutes) + FUTURE_SANITY_MARGIN_MS
        return dueAtMs <= maxExpectedFuture
    }
}

object AutoRotationGate {
    const val CONFIG_VERSION = 16

    private const val PREFS = "wallhaven_rotation_scheduler"
    private const val KEY_NEXT_DUE_AT = "next_due_elapsed_ms"
    private const val KEY_NEXT_DUE_WALL_AT = "next_due_wall_ms"
    private const val KEY_CONFIG_VERSION = "config_version"
    private const val KEY_LAST_SUCCESS_SOURCE = "last_success_source"
    private const val KEY_LAST_SUCCESS_AT = "last_success_elapsed_ms"
    private const val KEY_LAST_SUCCESS_WALL_AT = "last_success_wall_ms"
    private const val KEY_CLOCK_SAMPLE = "elapsed_clock_sample_ms"
    private val lock = Any()

    fun reset(
        context: Context,
        intervalMinutes: Long,
        reason: String,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): Long = synchronized(lock) {
        val next = AutoRotationPolicy.nextDueAt(nowMs, intervalMinutes)
        val wallNow = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(KEY_NEXT_DUE_AT, next)
            .putLong(KEY_NEXT_DUE_WALL_AT, wallNow + (next - nowMs))
            .putInt(KEY_CONFIG_VERSION, CONFIG_VERSION)
            .putString(KEY_LAST_SUCCESS_SOURCE, "reset:$reason")
            .putLong(KEY_LAST_SUCCESS_AT, nowMs)
            .putLong(KEY_LAST_SUCCESS_WALL_AT, wallNow)
            .putLong(KEY_CLOCK_SAMPLE, nowMs)
            .apply()
        next
    }

    fun ensureInitialized(
        context: Context,
        intervalMinutes: Long,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): Long = synchronized(lock) {
        val preferences = prefs(context)
        val current = preferences.getLong(KEY_NEXT_DUE_AT, 0L)
        val previousClockSample = preferences.getLong(KEY_CLOCK_SAMPLE, 0L)
        val sameBoot = previousClockSample <= 0L || nowMs + 10_000L >= previousClockSample
        if (sameBoot && AutoRotationPolicy.looksLikeSameBootDeadline(nowMs, current, intervalMinutes)) {
            preferences.edit().putLong(KEY_CLOCK_SAMPLE, nowMs).apply()
            return@synchronized current
        }

        val next = AutoRotationPolicy.nextDueAt(nowMs, intervalMinutes)
        val wallNow = System.currentTimeMillis()
        preferences.edit()
            .putLong(KEY_NEXT_DUE_AT, next)
            .putLong(KEY_NEXT_DUE_WALL_AT, wallNow + (next - nowMs))
            .putLong(KEY_CLOCK_SAMPLE, nowMs)
            .apply()
        next
    }

    /**
     * Inspect whether an automatic run is due WITHOUT advancing the deadline.
     * The caller must hold/own the rotation execution slot before calling this.
     */
    fun inspectDue(
        context: Context,
        intervalMinutes: Long,
        allowEarlyAlarmTolerance: Boolean,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): AutomaticRunEligibility = synchronized(lock) {
        val preferences = prefs(context)
        var dueAt = preferences.getLong(KEY_NEXT_DUE_AT, 0L)
        val previousClockSample = preferences.getLong(KEY_CLOCK_SAMPLE, 0L)
        val sameBoot = previousClockSample <= 0L || nowMs + 10_000L >= previousClockSample

        if (!sameBoot || !AutoRotationPolicy.looksLikeSameBootDeadline(nowMs, dueAt, intervalMinutes)) {
            dueAt = AutoRotationPolicy.nextDueAt(nowMs, intervalMinutes)
            val wallNow = System.currentTimeMillis()
            preferences.edit()
                .putLong(KEY_NEXT_DUE_AT, dueAt)
                .putLong(KEY_NEXT_DUE_WALL_AT, wallNow + (dueAt - nowMs))
                .putLong(KEY_CLOCK_SAMPLE, nowMs)
                .apply()
            return@synchronized AutomaticRunEligibility(
                allowed = false,
                nowMs = nowMs,
                dueAtMs = dueAt,
                reason = "initialized_not_due"
            )
        }

        val allowed = if (allowEarlyAlarmTolerance) {
            AutoRotationPolicy.isDueForAlarm(nowMs, dueAt)
        } else {
            AutoRotationPolicy.isDue(nowMs, dueAt)
        }

        if (!allowed) {
            return@synchronized AutomaticRunEligibility(
                allowed = false,
                nowMs = nowMs,
                dueAtMs = dueAt,
                reason = "not_due"
            )
        }

        val earlyBy = max(0L, dueAt - nowMs)
        AutomaticRunEligibility(
            allowed = true,
            nowMs = nowMs,
            dueAtMs = dueAt,
            reason = if (earlyBy > 0L) "due_early_tolerance" else "due",
            earlyByMs = earlyBy
        )
    }

    /**
     * Advance the cadence only after the wallpaper transition completed successfully.
     * If settings/manual activity changed the deadline during the transition, preserve
     * that newer deadline rather than overwriting it.
     */
    fun completeSuccessfulRun(
        context: Context,
        intervalMinutes: Long,
        expectedDueAtMs: Long,
        source: String,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): AutomaticRunCompletion = synchronized(lock) {
        val preferences = prefs(context)
        val current = preferences.getLong(KEY_NEXT_DUE_AT, 0L)
        if (current != expectedDueAtMs) {
            return@synchronized AutomaticRunCompletion(
                committed = false,
                previousDueAtMs = expectedDueAtMs,
                nextDueAtMs = current,
                reason = "due_changed_during_rotation"
            )
        }

        val next = AutoRotationPolicy.nextDueAt(nowMs, intervalMinutes)
        val wallNow = System.currentTimeMillis()
        preferences.edit()
            .putLong(KEY_NEXT_DUE_AT, next)
            .putLong(KEY_NEXT_DUE_WALL_AT, wallNow + (next - nowMs))
            .putString(KEY_LAST_SUCCESS_SOURCE, source)
            .putLong(KEY_LAST_SUCCESS_AT, nowMs)
            .putLong(KEY_LAST_SUCCESS_WALL_AT, wallNow)
            .putLong(KEY_CLOCK_SAMPLE, nowMs)
            .apply()

        AutomaticRunCompletion(
            committed = true,
            previousDueAtMs = expectedDueAtMs,
            nextDueAtMs = next,
            reason = "success_committed"
        )
    }

    fun deferAfterManual(
        context: Context,
        intervalMinutes: Long,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): Long = synchronized(lock) {
        val next = AutoRotationPolicy.nextDueAt(nowMs, intervalMinutes)
        val wallNow = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(KEY_NEXT_DUE_AT, next)
            .putLong(KEY_NEXT_DUE_WALL_AT, wallNow + (next - nowMs))
            .putString(KEY_LAST_SUCCESS_SOURCE, "manual")
            .putLong(KEY_LAST_SUCCESS_AT, nowMs)
            .putLong(KEY_LAST_SUCCESS_WALL_AT, wallNow)
            .putLong(KEY_CLOCK_SAMPLE, nowMs)
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
        val nowElapsed = SystemClock.elapsedRealtime()
        val dueElapsed = p.getLong(KEY_NEXT_DUE_AT, 0L)
        return buildString {
            append("configVersion=${p.getInt(KEY_CONFIG_VERSION, 0)}")
            append(",clock=elapsedRealtime")
            append(",nextDueElapsedMs=$dueElapsed")
            append(",remainingMs=${if (dueElapsed > 0L) dueElapsed - nowElapsed else 0L}")
            append(",nextDueWallMs=${p.getLong(KEY_NEXT_DUE_WALL_AT, 0L)}")
            append(",lastSuccessSource=${p.getString(KEY_LAST_SUCCESS_SOURCE, "") ?: ""}")
            append(",lastSuccessElapsedMs=${p.getLong(KEY_LAST_SUCCESS_AT, 0L)}")
            append(",lastSuccessWallMs=${p.getLong(KEY_LAST_SUCCESS_WALL_AT, 0L)}")
            append(",clockSampleMs=${p.getLong(KEY_CLOCK_SAMPLE, 0L)}")
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
