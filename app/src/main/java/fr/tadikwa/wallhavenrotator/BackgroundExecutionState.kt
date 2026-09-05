package fr.tadikwa.wallhavenrotator

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

data class BackgroundExecutionSnapshot(
    val interactive: Boolean,
    val deviceLocked: Boolean,
    val keyguardLocked: Boolean
) {
    val readyForAutomaticWallpaper: Boolean
        get() = BackgroundExecutionPolicy.isReady(interactive, deviceLocked, keyguardLocked)

    val reason: String
        get() = BackgroundExecutionPolicy.reason(interactive, deviceLocked, keyguardLocked)
}

/** Pure policy kept testable without Android framework objects. */
object BackgroundExecutionPolicy {
    fun isReady(interactive: Boolean, deviceLocked: Boolean, keyguardLocked: Boolean): Boolean =
        interactive && !deviceLocked && !keyguardLocked

    fun reason(interactive: Boolean, deviceLocked: Boolean, keyguardLocked: Boolean): String = when {
        !interactive -> "non_interactive"
        deviceLocked -> "device_locked"
        keyguardLocked -> "keyguard_locked"
        else -> "ready"
    }
}

/** Android-facing readiness probe for automatic WallpaperManager calls. */
object BackgroundExecutionState {
    fun snapshot(context: Context): BackgroundExecutionSnapshot {
        val appContext = context.applicationContext
        val power = appContext.getSystemService(PowerManager::class.java)
        val keyguard = appContext.getSystemService(KeyguardManager::class.java)
        return BackgroundExecutionSnapshot(
            interactive = runCatching { power?.isInteractive ?: true }.getOrDefault(true),
            deviceLocked = runCatching { keyguard?.isDeviceLocked ?: false }.getOrDefault(false),
            keyguardLocked = runCatching { keyguard?.isKeyguardLocked ?: false }.getOrDefault(false)
        )
    }

    fun isInteractive(context: Context): Boolean = snapshot(context).interactive

    fun isReadyForAutomaticWallpaper(context: Context): Boolean =
        snapshot(context).readyForAutomaticWallpaper
}

class AutomaticWallpaperApplyDeferredException(
    val executionState: BackgroundExecutionSnapshot,
    val phase: String
) : IllegalStateException(
    "Automatic wallpaper apply deferred at $phase: ${executionState.reason}"
)
