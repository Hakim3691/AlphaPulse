package com.aistudio.alphapulse.data.model

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.Result
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

/** WorkManagerScheduler - Defines and schedules recurring background jobs.
 * Jobs:
 *   1) Data Refresh Job — every 15 min, fetch latest market data
 *   2) Model Re-Eval Job — every 4 hr, re-calibrate model weights
 *   3) State Sync Job — every 24 hr, sync predictions and session state to Room
 */
object WorkManagerScheduler {

    private const val WORK_NAME_DATA_REFRESH = "com.aistudio.alphapulse.work.dataRefresh"
    private const val WORK_NAME_MODEL_REEVAL = "com.aistudio.alphapulse.work.modelReeval"
    private const val WORK_NAME_STATE_SYNC = "com.aistudio.alphapulse.work.stateSync"

    /** Schedule the Data Refresh job — runs every 15 minutes. */
    fun scheduleDataRefresh(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONSTRAINED)
            .build()
        val workRequest = PeriodicWorkRequest.Builder(
            DataRefreshWorker::class.java,
            15, java.util.concurrent.TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_DATA_REFRESH,
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )
    }

    /** Schedule the Model Re-Eval job — runs every 4 hours. */
    fun scheduleModelReeval(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONSTRAINED)
            .build()
        val workRequest = PeriodicWorkRequest.Builder(
            ModelReevalWorker::class.java,
            4, java.util.concurrent.TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_MODEL_REEVAL,
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )
    }

    /** Schedule the State Sync job — runs once per day at midnight. */
    fun scheduleStateSync(context: Context) {
        val workRequest = PeriodicWorkRequest.Builder(
            StateSyncWorker::class.java,
            24, java.util.concurrent.TimeUnit.HOURS
        )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_STATE_SYNC,
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )
    }

    /** Flow that emits WorkManager status updates (success/failure/output). */
    data class WorkStatus(
        val workName: String,
        val success: Boolean,
        val outputData: kotlin.collections.Map<String, Any?>? = null
    )
}

/** DataRefreshWorker — fetches the latest market data from the exchange API. */
class DataRefreshWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {
    override fun doWork(): androidx.work.Result {
        // In production: fetch ticker, order book, aggregate trades from Binance
        // For now, mark success with a placeholder
        return Result.success()
    }
}

/** ModelReevalWorker — re-calibrates model weights using fresh data. */
class ModelReevalWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {
    override fun doWork(): androidx.work.Result {
        // In production: re-run ModelManager.calibrateInitialWeightsFromHistory
        // For now, mark success
        return Result.success()
    }
}

/** StateSyncWorker — syncs predictions and session state to Room database. */
class StateSyncWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {
    override fun doWork(): androidx.work.Result {
        // In production: sync pending predictions, update session timestamps
        // For now, mark success
        return Result.success()
    }
}
