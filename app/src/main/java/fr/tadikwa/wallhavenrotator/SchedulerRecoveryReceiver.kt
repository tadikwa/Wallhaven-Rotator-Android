package fr.tadikwa.wallhavenrotator

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SchedulerRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext).load()

        Diagnostics.log(
            appContext,
            "scheduler.recovery.broadcast",
            fields = mapOf(
                "action" to action,
                "enabled" to settings.enabled,
                "exactAlarmAllowed" to RotationAlarmScheduler.canScheduleExact(appContext)
            )
        )

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                RotationScheduler.recoverAfterSystemEvent(appContext, settings, action)

            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                RotationScheduler.exactAlarmPermissionChanged(appContext, settings)
        }
    }
}
