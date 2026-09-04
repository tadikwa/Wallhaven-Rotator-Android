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

/**
 * Short-lived foreground service launched by AlarmManager.
 *
 * Alpha.10 kept a foreground service alive and timed with Handler.postDelayed(). On
 * the tested HONOR device MagicOS killed that process in the background, so the timer
 * vanished with it. Alpha.11 keeps the deadline in AlarmManager instead; this service
 * only exists while a due rotation is actually being executed.
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
        Diagnostics.log(applicationContext, "service.rotation.created", fields = mapOf("mode" to "one_shot_alarm"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()
        Diagnostics.log(
            applicationContext,
            "service.rotation.start_command",
            fields = mapOf("startId" to startId, "action" to action, "mode" to "one_shot_alarm")
        )

        if (action != ACTION_RUN_ALARM) {
            finishService(startId)
            return START_NOT_STICKY
        }

        if (!inFlight.compareAndSet(false, true)) {
            Diagnostics.log(applicationContext, "service.rotation.skip_inflight")
            return START_NOT_STICKY
        }

        executor.execute {
            try {
                runDueRotation()
            } finally {
                inFlight.set(false)
                finishService(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        executor.shutdown()
        RotationServiceStatus.setRunning(applicationContext, false)
        Diagnostics.log(applicationContext, "service.rotation.destroyed", fields = mapOf("mode" to "one_shot_alarm"))
        super.onDestroy()
    }

    private fun runDueRotation() {
        val appContext = applicationContext
        val settings = SettingsRepository(appContext).load()
        if (!settings.enabled) {
            Diagnostics.log(appContext, "service.rotation.skip_disabled")
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
                "source" to "alarm_manager"
            )
        )

        // Always restore a system-owned next trigger. This also heals duplicate or
        // slightly-early alarm delivery without changing the cadence gate.
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

    private fun finishService(startId: Int) {
        releaseWakeLock()
        stopSelf(startId)
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
        private const val CHANNEL_ID = "wallhaven_rotation_service"
        private const val NOTIFICATION_ID = 2401
        private const val WAKE_LOCK_TIMEOUT_MS = 120_000L

        fun startForAlarm(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, RotationForegroundService::class.java).apply {
                action = ACTION_RUN_ALARM
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
        return "running=${p.getBoolean(KEY_RUNNING, false)},lastStateChangeMs=${p.getLong(KEY_HEARTBEAT, 0L)},mode=one_shot_alarm"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
