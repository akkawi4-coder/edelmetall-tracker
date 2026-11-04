package de.akkaoui.edelmetall.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.akkaoui.edelmetall.data.Repository
import java.util.concurrent.TimeUnit

/** Zeichnet im Hintergrund alle 15 Minuten die Kurse auf (kürzester Abstand, den Android erlaubt). */
class PriceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Repository.get(applicationContext).refreshPrices()
        return Result.success()
    }

    companion object {
        private const val NAME = "price-recording"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PriceWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
