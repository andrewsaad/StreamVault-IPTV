package com.streamvault.data.preferences

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ChannelPreferenceDao
import com.streamvault.data.local.dao.SearchHistoryDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PreferencesRepositoryAppUpdateCheckTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.preferencesDataStoreFile("user_preferences").delete()
    }

    @After
    fun tearDown() {
        context.preferencesDataStoreFile("user_preferences").delete()
    }

    @Test
    fun `automatic app update checks are off until the user turns them on`() = runBlocking {
        val repository = PreferencesRepository(
            context = context,
            channelPreferenceDao = mock<ChannelPreferenceDao>(),
            playbackQualityPreferenceDao = mock(),
            searchHistoryDao = mock<SearchHistoryDao>(),
            corruptionRecovery = mock<PreferencesCorruptionRecovery>()
        )

        assertThat(repository.autoCheckAppUpdates.first()).isFalse()

        repository.setAutoCheckAppUpdates(true)

        assertThat(repository.autoCheckAppUpdates.first()).isTrue()
    }
}
