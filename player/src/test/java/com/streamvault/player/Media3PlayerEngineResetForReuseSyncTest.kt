package com.streamvault.player

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.repository.PlaybackCompatibilityRepository
import com.streamvault.domain.sync.AutomaticSyncActivity
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Media3PlayerEngineResetForReuseSyncTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val engine = Media3PlayerEngine(
        context = context,
        okHttpClient = OkHttpClient(),
        playbackCompatibilityRepository = NoOpPlaybackCompatibilityRepository,
        audioCompatibilityMemoryStore = AudioCompatibilityMemoryStore(context),
        playbackSupportSnapshotStore = PlaybackSupportSnapshotStore(context)
    )

    @After
    fun tearDown() {
        engine.release()
    }

    @Test
    fun `resetForReuse releases the engine's automatic sync playback ownership`() {
        // prepare() registers the engine with AutomaticSyncActivity; register it directly here.
        AutomaticSyncActivity.started(engine)
        assertThat(AutomaticSyncActivity.hasPlayback).isTrue()

        engine.resetForReuse()

        assertThat(AutomaticSyncActivity.hasPlayback).isFalse()
    }

    private object NoOpPlaybackCompatibilityRepository : PlaybackCompatibilityRepository {
        override suspend fun getKnownBadRecords(
            deviceFingerprint: String,
            streamType: String,
            videoMimeType: String,
            resolutionBucket: String
        ): List<com.streamvault.domain.model.PlaybackCompatibilityRecord> = emptyList()

        override suspend fun recordFailure(
            key: com.streamvault.domain.model.PlaybackCompatibilityKey,
            failureType: String,
            at: Long
        ) = Unit

        override suspend fun recordSuccess(key: com.streamvault.domain.model.PlaybackCompatibilityKey, at: Long) = Unit

        override suspend fun prune(maxRecords: Int, olderThanMs: Long) = Unit
    }
}
