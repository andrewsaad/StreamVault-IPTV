package com.streamvault.data.health

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Runs one provider's channel health scan in the background; the scan itself lives in [ChannelHealthScanner]. */
class ChannelHealthScanWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun channelHealthScanner(): ChannelHealthScanner
    }

    override suspend fun doWork(): Result {
        val providerId = inputData.getLong(KEY_PROVIDER_ID, 0L)
        if (providerId <= 0L) return Result.failure()
        val categoryIds = if (inputData.getBoolean(KEY_FILTER_CATEGORIES, false)) {
            inputData.getLongArray(KEY_CATEGORY_IDS)?.toSet().orEmpty()
        } else {
            null
        }
        val scanner = EntryPointAccessors.fromApplication(applicationContext, Entry::class.java).channelHealthScanner()
        return if (scanner.run(providerId, categoryIds, inputData.getLong(KEY_STARTED_AT, 0L))) {
            Result.success()
        } else {
            Result.failure()
        }
    }

    internal companion object {
        const val KEY_PROVIDER_ID = "provider_id"
        const val KEY_FILTER_CATEGORIES = "filter_categories"
        const val KEY_CATEGORY_IDS = "category_ids"
        const val KEY_STARTED_AT = "started_at"

        fun uniqueName(providerId: Long): String = "channel-health-scan-$providerId"
    }
}

/** Owns the scan's WorkManager request so starting and cancelling stay in one place. */
@Singleton
class ChannelHealthScanScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /** Queues a scan; a scan of the same provider that is already queued or running is kept. */
    fun enqueue(providerId: Long, categoryIds: Set<Long>?, startedAt: Long) {
        val request = OneTimeWorkRequestBuilder<ChannelHealthScanWorker>()
            .setInputData(
                workDataOf(
                    ChannelHealthScanWorker.KEY_PROVIDER_ID to providerId,
                    ChannelHealthScanWorker.KEY_FILTER_CATEGORIES to (categoryIds != null),
                    ChannelHealthScanWorker.KEY_CATEGORY_IDS to categoryIds.orEmpty().toLongArray(),
                    ChannelHealthScanWorker.KEY_STARTED_AT to startedAt,
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ChannelHealthScanWorker.uniqueName(providerId),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * True while the provider's scan work is queued, blocked or running, including the gaps when
     * WorkManager has stopped the job (about every 10 minutes) and will resume it.
     */
    fun observeActive(providerId: Long): Flow<Boolean> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(ChannelHealthScanWorker.uniqueName(providerId))
            .map { infos ->
                infos.any {
                    it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.BLOCKED
                }
            }
            .distinctUntilChanged()

    fun cancel(providerId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(ChannelHealthScanWorker.uniqueName(providerId))
    }
}
