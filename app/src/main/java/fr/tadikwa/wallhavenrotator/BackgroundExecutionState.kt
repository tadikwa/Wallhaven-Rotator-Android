package fr.tadikwa.wallhavenrotator

import android.content.Context
import android.os.PowerManager

/** Small Android-facing helper kept separate so scheduling policy remains testable. */
object BackgroundExecutionState {
    fun isInteractive(context: Context): Boolean {
        val power = context.applicationContext.getSystemService(PowerManager::class.java)
        return runCatching { power?.isInteractive ?: true }.getOrDefault(true)
    }
}
