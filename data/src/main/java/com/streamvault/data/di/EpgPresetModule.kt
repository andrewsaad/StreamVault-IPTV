package com.streamvault.data.di

import com.streamvault.data.epg.EpgPresetManagerImpl
import com.streamvault.data.epg.EpgPresetProviderDirectory
import com.streamvault.data.epg.EpgPresetStateStore
import com.streamvault.data.epg.EpgSourceRefreshGate
import com.streamvault.data.epg.ProviderDaoPresetDirectory
import com.streamvault.domain.repository.EpgPresetManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class EpgPresetModule {
    @Binds
    abstract fun bindEpgSourceRefreshGate(store: EpgPresetStateStore): EpgSourceRefreshGate

    @Binds
    abstract fun bindEpgPresetProviderDirectory(impl: ProviderDaoPresetDirectory): EpgPresetProviderDirectory

    @Binds
    abstract fun bindEpgPresetManager(impl: EpgPresetManagerImpl): EpgPresetManager
}
