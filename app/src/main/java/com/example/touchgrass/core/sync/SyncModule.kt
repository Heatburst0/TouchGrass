package com.example.touchgrass.core.sync

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Wires the sync seam. Today [SyncClient] resolves to the local no-op; when a
 * backend exists, bind the real client here instead — no other change. The
 * [SyncSource] set is empty for now; each feature that becomes syncable binds its
 * source into it (@IntoSet) and a future SyncEngine picks it up for free.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds
    abstract fun bindSyncClient(impl: LocalOnlySyncClient): SyncClient

    @Multibinds
    abstract fun syncSources(): Set<SyncSource>
}
