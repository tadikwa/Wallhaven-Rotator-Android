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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Keeps the automatic wallpaper cadence alive on OEMs that aggressively defer
 * WorkManager while the app is backgrounded. WorkManager remains enabled as a durable
 * fallback, but both paths share AutoRotationGate so they can never create a burst.
 */
class RotationForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wallhaven-auto-rotation-service")
    }
    private val rotationInFlight = AtomicBoolean(false)
    private val wakeRunnable = Runnable { tick() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        promoteToForeground()
        RotationServiceStatus.setRunning(applicationContext, true)
        Diagnostics.log(applicationContext, "service.rotation.created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Diagnostics.log(
            applicationContext,
            "service.rotation.start_command",
            fields = mapOf("startId" to startId, "action" to (intent?.action ?: ""))
        )
        scheduleFromGate()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(wakeRunnable)
        executor.shutdownNow()
        RotationServiceStatus.setRunning(applicationContext, false)
        Diagnostics.log(applicationContext, "service.rotation.destroyed")
        super.onDestroy()
    }

    private fun tick() {
        handler.removeCallbacks(wakeRunnable)
        val settings = SettingsRepository(applicationContext).load()
        if (!settings.enabled) {
            Diagnostics.log(applicationContext, "service.rotation.stop_disabled")
            stopSelf()
            return
        }

        val dueAt = AutoRotationGate.ensureInitialized(
            applicationContext,
            settings.intervalMinutes
        )
        val now = System.currentTimeMillis()
        if (now < dueAt) {
            updateNotification(dueAt)
            scheduleAt(dueAt)
            return
        }

        if (!rotationInFlight.compareAndSet(false, true)) {
            Diagnostics.log(applicationContext, "service.rotation.skip_inflight")
            handler.postDelayed(wakeRunnable, 5_000L)
            return
        }

        executor.execute {
            try {
                val current = SettingsRepository(applicationContext).load()
                if (!current.enabled) return@execute

                val claim = AutoRotationGate.claimIfDue(
                    applicationContext,
                    current.intervalMinutes,
                    source = "foreground_service"
                )
                Diagnostics.log(
                    applicationContext,
                    "service.rotation.claim",
                    fields = mapOf(
                        "allowed" to claim.allowed,
                        "reason" to claim.reason,
                        "previousDueAtMs" to claim.previousDueAtMs,
                        "nextDueAtMs" to claim.nextDueAtMs
                    )
                )
                if (!claim.allowed) return@execute

                RotationScheduler.requestAutomaticPriority(
                    applicationContext,
                    source = "foreground_service"
                )
                val outcome = RotationEngine.tryRotateOnce(applicationContext, current)
                if (outcome == null) {
                    Diagnostics.log(applicationContext, "service.rotation.skip_busy")
                } else {
                    Diagnostics.log(
                        applicationContext,
                        "service.rotation.success",
                        fields = mapOf(
                            "destinations" to outcome.destinations.joinToString(","),
                            "wallhavenIds" to outcome.wallhavenIds.joinToString(",")
                        )
                    )
                }
            } catch (failure: Throwable) {
                Diagnostics.log(
                    applicationContext,
                    "service.rotation.failure",
                    level = "ERROR",
                    throwable = failure
                )
            } finally {
                rotationInFlight.set(false)
                handler.post { scheduleFromGate() }
            }
        }
    }

    private fun scheduleFromGate() {
        val settings = SettingsRepository(applicationContext).load()
        if (!settings.enabled) {
            stopSelf()
            return
        }
        val dueAt = AutoRotationGate.ensureInitialized(applicationContext, settings.intervalMinutes)
        updateNotification(dueAt)
        scheduleAt(dueAt)
    }

    private fun scheduleAt(dueAtMs: Long) {
        handler.removeCallbacks(wakeRunnable)
        val delay = max(1_000L, dueAtMs - System.currentTimeMillis())
        handler.postDelayed(wakeRunnable, delay)
        RotationServiceStatus.heartbeat(applicationContext, dueAtMs)
    }

    private fun promoteToForeground() {
        val notification = buildNotification(AutoRotationGate.nextDueAt(applicationContext))
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

    private fun updateNotification(dueAtMs: Long) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(dueAtMs))
    }

    private fun buildNotification(dueAtMs: Long): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (dueAtMs > 0L) {
            "Rotation automatique active • prochaine vers ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(dueAtMs))}"
        } else {
            "Rotation automatique active"
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_wallpaper)
            .setContentTitle("Wallhaven Rotator")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Rotation automatique",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintient la rotation de fond d'écran active en arrière-plan."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "wallhaven_rotation_service"
        private const val NOTIFICATION_ID = 2401

        fun start(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, RotationForegroundService::class.java)
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
    private const val KEY_DUE_AT = "due_at_ms"

    fun setRunning(context: Context, running: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, running)
            .putLong(KEY_HEARTBEAT, System.currentTimeMillis())
            .apply()
    }

    fun heartbeat(context: Context, dueAtMs: Long) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, true)
            .putLong(KEY_HEARTBEAT, System.currentTimeMillis())
            .putLong(KEY_DUE_AT, dueAtMs)
            .apply()
    }

    fun summary(context: Context): String {
        val p = prefs(context)
        return "running=${p.getBoolean(KEY_RUNNING, false)},heartbeatMs=${p.getLong(KEY_HEARTBEAT, 0L)},dueAtMs=${p.getLong(KEY_DUE_AT, 0L)}"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
