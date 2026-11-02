package com.streamvault.data.di

import com.streamvault.data.sync.favorites.EncryptedFavoriteSyncSecretStore
import com.streamvault.data.sync.favorites.FavoriteSyncCatalogListener
import com.streamvault.data.sync.favorites.FavoriteSyncChangeRecorder
import com.streamvault.data.sync.favorites.FavoriteSyncCoordinator
import com.streamvault.data.sync.favorites.FavoriteSyncRecorder
import com.streamvault.data.sync.favorites.FavoriteSyncSecretStore
import com.streamvault.data.sync.favorites.FavoriteSyncTransportFactory
import com.streamvault.data.sync.favorites.HttpFavoriteSyncTransportFactory
import com.streamvault.data.sync.favorites.ProviderIdentityKeyLookup
import com.streamvault.data.sync.favorites.SnapshotProviderIdentityKeyLookup
import com.streamvault.domain.manager.FavoriteSyncManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class FavoriteSyncModule {
    @Binds
    abstract fun bindFavoriteSyncManager(impl: FavoriteSyncCoordinator): FavoriteSyncManager

    @Binds
    abstract fun bindFavoriteSyncCatalogListener(impl: FavoriteSyncCoordinator): FavoriteSyncCatalogListener

    @Binds
    abstract fun bindFavoriteSyncChangeRecorder(impl: FavoriteSyncRecorder): FavoriteSyncChangeRecorder

    @Binds
    abstract fun bindFavoriteSyncSecretStore(impl: EncryptedFavoriteSyncSecretStore): FavoriteSyncSecretStore

    @Binds
    abstract fun bindFavoriteSyncTransportFactory(impl: HttpFavoriteSyncTransportFactory): FavoriteSyncTransportFactory

    @Binds
    abstract fun bindProviderIdentityKeyLookup(impl: SnapshotProviderIdentityKeyLookup): ProviderIdentityKeyLookup
}
