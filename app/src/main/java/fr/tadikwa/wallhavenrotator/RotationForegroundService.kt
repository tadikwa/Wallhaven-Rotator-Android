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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short-lived service for an interactive, due automatic rotation.
 *
 * Alpha.17 is the only automatic WallpaperManager owner. WorkManager only repairs
 * AlarmManager and never writes a wallpaper. Automatic writes use the lightweight
 * encoded-stream path while this foreground service owns a bounded partial wake lock.
 */
class RotationForegroundService : Service() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wallhaven-alarm-rotation")
    }
    private val timeoutExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "wallhaven-alarm-hard-timeout")
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
            fields = mapOf("mode" to "interactive_stream_alarm_v17")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()
        val wakeScheduleId = intent?.getLongExtra(EXTRA_WAKE_SCHEDULE_ID, 0L) ?: 0L
        val wakeReason = intent?.getStringExtra(EXTRA_WAKE_REASON).orEmpty()

        Diagnostics.log(
            applicationContext,
            "service.rotation.start_command",
            fields = mapOf(
                "startId" to startId,
                "action" to action,
                "mode" to "interactive_stream_alarm_v17",
                "wakeScheduleId" to wakeScheduleId,
                "wakeReason" to wakeReason,
                "interactive" to BackgroundExecutionState.snapshot(applicationContext).interactive
            )
        )

        if (action != ACTION_RUN_ALARM) {
            finishService()
            return START_NOT_STICKY
        }

        if (!inFlight.compareAndSet(false, true)) {
            val settings = SettingsRepository(applicationContext).load()
            val watchdog = RotationAlarmScheduler.scheduleWatchdog(
                applicationContext,
                settings.intervalMinutes
            )
            Diagnostics.log(
                applicationContext,
                "service.rotation.skip_inflight",
                fields = mapOf("watchdogTriggerElapsedMs" to watchdog.triggerAtMs)
            )
            return START_NOT_STICKY
        }

        executor.execute {
            try {
                runDueRotation(wakeScheduleId, wakeReason)
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
        timeoutExecutor.shutdownNow()
        RotationServiceStatus.setRunning(applicationContext, false)
        Diagnostics.log(
            applicationContext,
            "service.rotation.destroyed",
            fields = mapOf("mode" to "interactive_stream_alarm_v17")
        )
        super.onDestroy()
    }

    private fun runDueRotation(wakeScheduleId: Long, wakeReason: String) {
        val appContext = applicationContext
        val settings = SettingsRepository(appContext).load()
        if (!settings.enabled) {
            Diagnostics.log(appContext, "service.rotation.skip_disabled")
            RotationAlarmScheduler.cancel(appContext)
            return
        }

        // Race protection: automatic WallpaperManager calls are allowed only after
        // the display is interactive AND the keyguard/device lock is gone.
        val executionState = BackgroundExecutionState.snapshot(appContext)
        if (!executionState.readyForAutomaticWallpaper) {
            val deferred = RotationAlarmScheduler.scheduleDeferredUntilAwake(appContext)
            Diagnostics.log(
                appContext,
                "service.rotation.deferred_not_ready",
                fields = mapOf(
                    "wakeScheduleId" to wakeScheduleId,
                    "wakeReason" to wakeReason,
                    "gateDueElapsedMs" to AutoRotationGate.nextDueAt(appContext),
                    "deferredTriggerElapsedMs" to deferred.triggerAtMs,
                    "interactive" to executionState.interactive,
                    "deviceLocked" to executionState.deviceLocked,
                    "keyguardLocked" to executionState.keyguardLocked,
                    "deferReason" to executionState.reason
                )
            )
            return
        }

        val hardTimeout = timeoutExecutor.schedule(
            {
                Diagnostics.log(
                    appContext,
                    "service.rotation.hard_timeout_process_restart",
                    level = "ERROR",
                    fields = mapOf(
                        "source" to "alarm_manager",
                        "timeoutMs" to APPLY_HARD_TIMEOUT_MS,
                        "gateDueElapsedMs" to AutoRotationGate.nextDueAt(appContext)
                    )
                )
                // WallpaperManager Binder calls are not interruptible. If an OEM leaves
                // the call stuck, terminate only our process; the durable due gate and
                // system-owned watchdog alarm survive and will retry later.
                android.os.Process.killProcess(android.os.Process.myPid())
            },
            APPLY_HARD_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )

        try {
            when (
                val attempt = RotationEngine.tryRotateAutomaticDue(
                    context = appContext,
                    settings = settings,
                    source = "alarm_manager",
                    allowEarlyAlarmTolerance = true
                )
            ) {
                is AutomaticRotationAttempt.Success -> {
                    Diagnostics.log(
                        appContext,
                        "service.rotation.success",
                        fields = mapOf(
                            "destinations" to attempt.outcome.destinations.joinToString(","),
                            "wallhavenIds" to attempt.outcome.wallhavenIds.joinToString(","),
                            "source" to "alarm_manager",
                            "earlyByMs" to attempt.earlyByMs,
                            "nextDueElapsedMs" to attempt.nextDueAtMs,
                            "cadenceCommitted" to attempt.cadenceCommitted
                        )
                    )
                }

                is AutomaticRotationAttempt.NotDue -> {
                    if (attempt.dueAtMs > 0L) {
                        RotationAlarmScheduler.schedule(appContext, attempt.dueAtMs)
                    }
                    Diagnostics.log(
                        appContext,
                        "service.rotation.skipped_not_due",
                        fields = mapOf(
                            "dueElapsedMs" to attempt.dueAtMs,
                            "remainingMs" to attempt.remainingMs,
                            "reason" to attempt.reason
                        )
                    )
                }

                is AutomaticRotationAttempt.Deferred -> {
                    val deferred = RotationAlarmScheduler.scheduleDeferredUntilAwake(appContext)
                    Diagnostics.log(
                        appContext,
                        "service.rotation.deferred_during_apply",
                        fields = mapOf(
                            "reason" to attempt.reason,
                            "phase" to attempt.phase,
                            "interactive" to attempt.executionState.interactive,
                            "deviceLocked" to attempt.executionState.deviceLocked,
                            "keyguardLocked" to attempt.executionState.keyguardLocked,
                            "deferredTriggerElapsedMs" to deferred.triggerAtMs
                        )
                    )
                }

                AutomaticRotationAttempt.Busy -> {
                    val watchdog = RotationAlarmScheduler.scheduleWatchdog(
                        appContext,
                        settings.intervalMinutes
                    )
                    Diagnostics.log(
                        appContext,
                        "service.rotation.skipped_busy",
                        fields = mapOf("watchdogTriggerElapsedMs" to watchdog.triggerAtMs)
                    )
                }
            }
        } catch (failure: Throwable) {
            val watchdog = RotationAlarmScheduler.scheduleWatchdog(
                appContext,
                settings.intervalMinutes
            )
            Diagnostics.log(
                appContext,
                "service.rotation.failure",
                level = "ERROR",
                fields = mapOf(
                    "source" to "alarm_manager",
                    "watchdogTriggerElapsedMs" to watchdog.triggerAtMs
                ),
                throwable = failure
            )
        } finally {
            hardTimeout.cancel(false)
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
        private const val EXTRA_WAKE_SCHEDULE_ID = "wallhaven_wake_schedule_id"
        private const val EXTRA_WAKE_REASON = "wallhaven_wake_reason"
        private const val CHANNEL_ID = "wallhaven_rotation_service"
        private const val NOTIFICATION_ID = 2401
        private const val WAKE_LOCK_TIMEOUT_MS = 5L * 60L * 1_000L
        private const val APPLY_HARD_TIMEOUT_MS = 2L * 60L * 1_000L

        fun startForAlarm(
            context: Context,
            wakeScheduleId: Long,
            wakeReason: String
        ) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, RotationForegroundService::class.java).apply {
                action = ACTION_RUN_ALARM
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
    private const val RUNNING_FRESHNESS_MS = 10L * 60L * 1_000L

    fun setRunning(context: Context, running: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, running)
            .putLong(KEY_HEARTBEAT, System.currentTimeMillis())
            .apply()
    }

    fun isLikelyRunning(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_RUNNING, false)) return false
        val heartbeat = p.getLong(KEY_HEARTBEAT, 0L)
        if (heartbeat <= 0L) return false
        val ageMs = (System.currentTimeMillis() - heartbeat).coerceAtLeast(0L)
        return ageMs <= RUNNING_FRESHNESS_MS
    }

    fun summary(context: Context): String {
        val p = prefs(context)
        return "running=${p.getBoolean(KEY_RUNNING, false)},lastStateChangeMs=${p.getLong(KEY_HEARTBEAT, 0L)},mode=interactive_stream_alarm_v17"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
