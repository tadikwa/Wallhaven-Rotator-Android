package fr.tadikwa.wallhavenrotator

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

data class BackgroundReliabilitySnapshot(
    val exactAlarmAllowed: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val backgroundRestricted: Boolean,
    val honorDevice: Boolean
)

/**
 * Standard Android signals/settings that influence long-lived background reliability.
 *
 * HONOR's App launch policy is intentionally not guessed through private OEM APIs. The
 * app can request the standard Doze exemption and report standard restriction state;
 * the HONOR-specific "Manage automatically" switch remains a manual check, with
 * persistent guidance in the UI. The standard exemption does not prove OEM approval.
 */
object BackgroundReliability {
    private const val PREFS = "wallhaven_background_reliability"
    private const val KEY_BATTERY_PROMPT_VERSION = "battery_prompt_version"

    fun snapshot(context: Context): BackgroundReliabilitySnapshot {
        val appContext = context.applicationContext
        val power = appContext.getSystemService(PowerManager::class.java)
        val activity = appContext.getSystemService(ActivityManager::class.java)
        val batteryOptimizationIgnored = runCatching {
            power?.isIgnoringBatteryOptimizations(appContext.packageName) ?: false
        }.getOrDefault(false)
        val backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { activity?.isBackgroundRestricted ?: false }.getOrDefault(false)
        } else {
            false
        }

        return BackgroundReliabilitySnapshot(
            exactAlarmAllowed = RotationAlarmScheduler.canScheduleExact(appContext),
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            backgroundRestricted = backgroundRestricted,
            honorDevice = isHonorDevice()
        )
    }

    fun shouldRequestBatteryOptimizationExemption(context: Context): Boolean {
        val appContext = context.applicationContext
        val state = snapshot(appContext)
        if (state.batteryOptimizationIgnored) return false
        // Do not request a broad power exemption on healthy stock Android devices.
        // This prompt is reserved for the observed HONOR case or an OS-reported
        // background restriction.
        if (!state.honorDevice && !state.backgroundRestricted) return false
        return prefs(appContext).getInt(KEY_BATTERY_PROMPT_VERSION, 0) != BuildConfig.VERSION_CODE
    }

    fun markBatteryOptimizationPrompted(context: Context) {
        prefs(context.applicationContext).edit()
            .putInt(KEY_BATTERY_PROMPT_VERSION, BuildConfig.VERSION_CODE)
            .apply()
    }

    fun requestBatteryOptimizationExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.applicationContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    // The observed HONOR launch activity requires an OEM permission. Open the
    // public settings entry point instead of depending on a private component.
    fun deviceSettingsIntent(): Intent = Intent(Settings.ACTION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun summary(context: Context): String {
        val state = snapshot(context.applicationContext)
        return buildString {
            append("exactAlarmAllowed=${state.exactAlarmAllowed}")
            append(",batteryOptimizationIgnored=${state.batteryOptimizationIgnored}")
            append(",backgroundRestricted=${state.backgroundRestricted}")
            append(",honorDevice=${state.honorDevice}")
            if (state.honorDevice) append(",honorAppLaunchPolicy=manual_check_required")
        }
    }

    fun isHonorDevice(): Boolean =
        Build.MANUFACTURER.equals("HONOR", ignoreCase = true) ||
            Build.BRAND.equals("HONOR", ignoreCase = true)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
