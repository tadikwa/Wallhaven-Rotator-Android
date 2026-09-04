package fr.tadikwa.wallhavenrotator

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object RotationScheduler {
    private const val PERIODIC_NAME = "wallhaven_rotation"
    private const val PRELOAD_NAME = "wallhaven_preload"

    fun configure(context: Context, settings: AppSettings) {
        val manager = WorkManager.getInstance(context)
        if (!settings.enabled) {
            manager.cancelUniqueWork(PERIODIC_NAME)
            manager.cancelUniqueWork(PRELOAD_NAME)
            return
        }
        val minutes = settings.intervalMinutes.coerceAtLeast(15L)
        val request = PeriodicWorkRequestBuilder<RotationWorker>(minutes, TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun preload(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            PRELOAD_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PreloadWorker>().build()
        )
    }

    fun rotateNow(context: Context) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<RotationWorker>().build())
    }
}
