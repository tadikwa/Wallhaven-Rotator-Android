package fr.tadikwa.wallhavenrotator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Short-lived foreground service launched by AlarmManager.
 *
 * Alpha.13 can also keep this service alive for a bounded few minutes when a current
 * or stale vendor alarm arrives shortly before the real gate deadline. This avoids
 * trying to dispatch a second allow-while-idle alarm inside Android's idle quota.
 */
class RotationForegroundService : Service() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wallhaven-alarm-rotation")
    }
    private val inFlight = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        promoteToForeground()
        RotationServiceStatus.setRunning(applicationContext, true)
        acquireWakeLock()
        Diagnostics.log(
            applicationContext,
            "service.rotation.created",
            fields = mapOf("mode" to "one_shot_alarm_v2")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()
        val targetDueAtMs = intent?.getLongExtra(EXTRA_TARGET_DUE_AT, 0L) ?: 0L
        val wakeScheduleId = intent?.getLongExtra(EXTRA_WAKE_SCHEDULE_ID, 0L) ?: 0L
        val wakeReason = intent?.getStringExtra(EXTRA_WAKE_REASON).orEmpty()

        Diagnostics.log(
            applicationContext,
            "service.rotation.start_command",
            fields = mapOf(
                "startId" to startId,
                "action" to action,
                "mode" to "one_shot_alarm_v2",
                "targetDueAtMs" to targetDueAtMs,
                "wakeScheduleId" to wakeScheduleId,
                "wakeReason" to wakeReason
            )
        )

        if (action != ACTION_RUN_ALARM) {
            finishService()
            return START_NOT_STICKY
        }

        if (!inFlight.compareAndSet(false, true)) {
            Diagnostics.log(applicationContext, "service.rotation.skip_inflight")
            return START_NOT_STICKY
        }

        executor.execute {
            try {
                runDueRotation(targetDueAtMs, wakeScheduleId, wakeReason)
            } finally {
                inFlight.set(false)
                finishService()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        executor.shutdown()
        RotationServiceStatus.setRunning(applicationContext, false)
        Diagnostics.log(
            applicationContext,
            "service.rotation.destroyed",
            fields = mapOf("mode" to "one_shot_alarm_v2")
        )
        super.onDestroy()
    }

    private fun runDueRotation(
        targetDueAtMs: Long,
        wakeScheduleId: Long,
        wakeReason: String
    ) {
        val appContext = applicationContext
        var settings = SettingsRepository(appContext).load()
        if (!settings.enabled) {
            Diagnostics.log(appContext, "service.rotation.skip_disabled")
            RotationAlarmScheduler.cancel(appContext)
            return
        }

        if (!waitUntilGateDue(targetDueAtMs, wakeScheduleId, wakeReason)) return

        settings = SettingsRepository(appContext).load()
        if (!settings.enabled) {
            Diagnostics.log(appContext, "service.rotation.skip_disabled_after_wait")
            RotationAlarmScheduler.cancel(appContext)
            return
        }

        val claim = AutoRotationGate.claimIfDue(
            appContext,
            settings.intervalMinutes,
            source = "alarm_manager"
        )
        Diagnostics.log(
            appContext,
            "service.rotation.claim",
            fields = mapOf(
                "allowed" to claim.allowed,
                "reason" to claim.reason,
                "previousDueAtMs" to claim.previousDueAtMs,
                "nextDueAtMs" to claim.nextDueAtMs,
                "source" to "alarm_manager",
                "wakeReason" to wakeReason,
                "wakeScheduleId" to wakeScheduleId
            )
        )

        // Whether the claim succeeds or not, keep one versioned system-owned trigger
        // aligned with the durable gate.
        RotationAlarmScheduler.schedule(appContext, claim.nextDueAtMs)
        if (!claim.allowed) return

        RotationScheduler.requestAutomaticPriority(appContext, source = "alarm_manager")
        try {
            val outcome = RotationEngine.rotateOnce(appContext, settings)
            Diagnostics.log(
                appContext,
                "service.rotation.success",
                fields = mapOf(
                    "destinations" to outcome.destinations.joinToString(","),
                    "wallhavenIds" to outcome.wallhavenIds.joinToString(","),
                    "source" to "alarm_manager"
                )
            )
            RotationScheduler.preloadIfNeeded(appContext)
        } catch (failure: Throwable) {
            Diagnostics.log(
                appContext,
                "service.rotation.failure",
                level = "ERROR",
                fields = mapOf("source" to "alarm_manager"),
                throwable = failure
            )
        }
    }

    private fun waitUntilGateDue(
        targetDueAtMs: Long,
        wakeScheduleId: Long,
        wakeReason: String
    ): Boolean {
        val appContext = applicationContext
        if (targetDueAtMs <= 0L) {
            Diagnostics.log(
                appContext,
                "service.rotation.wait_invalid_due",
                level = "WARN",
                fields = mapOf("targetDueAtMs" to targetDueAtMs)
            )
            return false
        }

        var waitLogged = false
        while (true) {
            val settings = SettingsRepository(appContext).load()
            if (!settings.enabled) return false

            val currentGateDue = AutoRotationGate.nextDueAt(appContext)
            if (currentGateDue != targetDueAtMs) {
                Diagnostics.log(
                    appContext,
                    "service.rotation.wait_cancelled_due_changed",
                    fields = mapOf(
                        "targetDueAtMs" to targetDueAtMs,
                        "currentGateDueAtMs" to currentGateDue,
                        "wakeScheduleId" to wakeScheduleId
                    )
                )
                if (currentGateDue > 0L) RotationAlarmScheduler.schedule(appContext, currentGateDue)
                return false
            }

            val now = System.currentTimeMillis()
            val remaining = targetDueAtMs - now
            if (remaining <= 0L) {
                if (waitLogged) {
                    Diagnostics.log(
                        appContext,
                        "service.rotation.wait_complete",
                        fields = mapOf(
                            "targetDueAtMs" to targetDueAtMs,
                            "wakeScheduleId" to wakeScheduleId
                        )
                    )
                }
                return true
            }

            if (remaining > AlarmDispatchPolicy.MAX_EARLY_WAIT_MS) {
                Diagnostics.log(
                    appContext,
                    "service.rotation.wait_too_early",
                    level = "WARN",
                    fields = mapOf(
                        "remainingMs" to remaining,
                        "targetDueAtMs" to targetDueAtMs,
                        "wakeScheduleId" to wakeScheduleId,
                        "wakeReason" to wakeReason
                    )
                )
                RotationAlarmScheduler.schedule(appContext, targetDueAtMs)
                return false
            }

            if (!waitLogged) {
                waitLogged = true
                Diagnostics.log(
                    appContext,
                    "service.rotation.waiting_for_due",
                    fields = mapOf(
                        "remainingMs" to remaining,
                        "targetDueAtMs" to targetDueAtMs,
                        "wakeScheduleId" to wakeScheduleId,
                        "wakeReason" to wakeReason
                    )
                )
            }

            Thread.sleep(min(1_000L, remaining))
        }
    }

    private fun finishService() {
        releaseWakeLock()
        stopSelf()
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${BuildConfig.APPLICATION_ID}:wallpaper-rotation"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        if (lock != null && lock.isHeld) runCatching { lock.release() }
        wakeLock = null
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_wallpaper)
            .setContentTitle("Wallhaven Rotator")
            .setContentText("Rotation automatique en cours…")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Rotation automatique",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Affichée brièvement pendant une rotation automatique."
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val ACTION_RUN_ALARM =
            "fr.tadikwa.wallhavenrotator.action.RUN_ALARM_ROTATION"
        private const val EXTRA_TARGET_DUE_AT = "wallhaven_target_due_at"
        private const val EXTRA_WAKE_SCHEDULE_ID = "wallhaven_wake_schedule_id"
        private const val EXTRA_WAKE_REASON = "wallhaven_wake_reason"
        private const val CHANNEL_ID = "wallhaven_rotation_service"
        private const val NOTIFICATION_ID = 2401
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 1_000L

        fun startForAlarm(
            context: Context,
            targetDueAtMs: Long,
            wakeScheduleId: Long,
            wakeReason: String
        ) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, RotationForegroundService::class.java).apply {
                action = ACTION_RUN_ALARM
                putExtra(EXTRA_TARGET_DUE_AT, targetDueAtMs)
                putExtra(EXTRA_WAKE_SCHEDULE_ID, wakeScheduleId)
                putExtra(EXTRA_WAKE_REASON, wakeReason)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, RotationForegroundService::class.java)
            )
        }
    }
}

object RotationServiceStatus {
    private const val PREFS = "wallhaven_rotation_service_status"
    private const val KEY_RUNNING = "running"
    private const val KEY_HEARTBEAT = "heartbeat_ms"

    fun setRunning(context: Context, running: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, running)
            .putLong(KEY_HEARTBEAT, System.currentTimeMillis())
            .apply()
    }

    fun summary(context: Context): String {
        val p = prefs(context)
        return "running=${p.getBoolean(KEY_RUNNING, false)},lastStateChangeMs=${p.getLong(KEY_HEARTBEAT, 0L)},mode=one_shot_alarm_v2"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
