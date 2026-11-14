package com.streamvault.data.health

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.streamvault.data.R
import java.util.UUID

/**
 * The ongoing notification that keeps a channel scan alive as a foreground (data sync) job, so Android
 * does not stop it after 10 minutes and then delay the next run. Text comes only from localized resources
 * and never carries the provider login.
 */
internal object ChannelScanNotification {
    const val CHANNEL_ID = "streamvault_channel_scan"
    private const val NOTIFICATION_ID_BASE = 41_000

    /** One id per provider, so two scans running at once never overwrite each other's notification or Stop button. */
    fun notificationId(providerId: Long): Int = NOTIFICATION_ID_BASE + (providerId % 10_000L).toInt()

    /** Progress moves every few seconds; refreshing the notification that often would only churn the system UI. */
    const val MIN_UPDATE_GAP_MS = 60_000L

    /** True when the shown progress changed and the last refresh is old enough (or there was none). */
    fun shouldRefresh(lastRefreshAtMs: Long?, nowMs: Long, progressChanged: Boolean): Boolean =
        progressChanged && (lastRefreshAtMs == null || nowMs - lastRefreshAtMs >= MIN_UPDATE_GAP_MS)

    /** [total] of 0 means the scan has not counted its channels yet. */
    fun foregroundInfo(context: Context, workId: UUID, providerId: Long, scanned: Int, total: Int): ForegroundInfo {
        ensureChannel(context)
        val text = if (total > 0) {
            context.getString(R.string.channel_scan_notification_progress, scanned, total)
        } else {
            context.getString(R.string.channel_scan_notification_preparing)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.channel_scan_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(total, scanned.coerceAtMost(total), total <= 0)
            .addAction(
                0,
                context.getString(R.string.channel_scan_notification_stop),
                WorkManager.getInstance(context).createCancelPendingIntent(workId),
            )
            .build()
        return wrap(notificationId(providerId), notification)
    }

    private fun wrap(id: Int, notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_scan_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }
}
