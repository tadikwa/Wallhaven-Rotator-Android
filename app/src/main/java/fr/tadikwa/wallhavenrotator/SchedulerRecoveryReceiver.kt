package fr.tadikwa.wallhavenrotator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SchedulerRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val settings = SettingsRepository(context.applicationContext).load()
        Diagnostics.log(
            context.applicationContext,
            "scheduler.recovery.broadcast",
            fields = mapOf("action" to action, "enabled" to settings.enabled)
        )
        RotationScheduler.recoverAfterSystemEvent(context.applicationContext, settings, action)
    }
}
