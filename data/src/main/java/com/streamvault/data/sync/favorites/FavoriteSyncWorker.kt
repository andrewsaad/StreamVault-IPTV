package com.streamvault.data.sync.favorites

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Background catch-up for TVs that sit idle; the foreground triggers handle everyday edits. */
class FavoriteSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun favoriteSyncEngine(): FavoriteSyncEngine
    }

    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, Entry::class.java).favoriteSyncEngine().sync()
        // Failures are already in the status line; the next period or foreground pull retries.
        return Result.success()
    }
}

/** Owns the periodic favorites sync request so enabling and disabling stay in one place. */
@Singleton
class FavoriteSyncWorkScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<FavoriteSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelPeriodic() {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    private companion object {
        const val PERIODIC_WORK_NAME = "FavoriteSyncPeriodic"
        const val PERIOD_HOURS = 6L
    }
}
