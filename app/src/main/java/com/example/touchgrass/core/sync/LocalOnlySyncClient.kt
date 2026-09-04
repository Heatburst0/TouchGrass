package com.example.touchgrass.core.sync

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The default [SyncClient] while there is no backend: it reports itself
 * unconfigured and every call is a successful no-op. The app runs entirely on
 * device; swapping in a real client later requires no changes anywhere else.
 */
@Singleton
class LocalOnlySyncClient @Inject constructor() : SyncClient {
    override val isConfigured: Boolean = false

    override suspend fun register(device: DeviceInfo): Result<Unit> = Result.success(Unit)

    override suspend fun push(batch: SyncBatch): Result<Unit> = Result.success(Unit)

    override suspend fun pull(cursor: String?): Result<SyncPullResult> =
        Result.success(SyncPullResult.empty(System.currentTimeMillis()))
}
