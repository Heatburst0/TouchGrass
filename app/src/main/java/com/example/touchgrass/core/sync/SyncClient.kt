package com.example.touchgrass.core.sync

/**
 * The transport seam a backend drops into. Everything on-device talks to this
 * interface; today it's bound to [LocalOnlySyncClient] (a no-op), so the app is
 * fully phone-first and nothing round-trips a network. When the server + desktop
 * agent land, a real implementation is bound in its place — no feature code changes.
 */
interface SyncClient {
    /** False until a real backend is configured; callers can skip sync work entirely. */
    val isConfigured: Boolean

    /** Announce this device to the account (idempotent). */
    suspend fun register(device: DeviceInfo): Result<Unit>

    /** Push local changes upstream. */
    suspend fun push(batch: SyncBatch): Result<Unit>

    /** Pull remote changes since [cursor] (null = from the beginning). */
    suspend fun pull(cursor: String?): Result<SyncPullResult>
}

/**
 * A feature's adapter between its local storage and the sync transport. A future
 * SyncEngine will iterate the bound [SyncSource]s to gather local changes and fan
 * remote changes back out — so adding sync for a feature is "implement this and
 * bind it into the set", nothing more. Defined now to fix the shape; not yet driven.
 */
interface SyncSource {
    val entityType: SyncEntityType

    /** Local records changed since [cursor] (its own high-water mark). */
    suspend fun localChangesSince(cursor: String?): List<SyncRecord>

    /** Apply records that arrived from another device. */
    suspend fun applyRemote(records: List<SyncRecord>)
}
