package com.example.touchgrass.core.sync

/**
 * Transport-level models for cross-device sync. Deliberately storage-agnostic and
 * envelope-based ([SyncRecord.payloadJson]) so a NEW syncable thing — a focus
 * session, a language-learning streak, a chess rating, a desktop input-activity
 * sample — needs no change to this contract, mirroring how the goals table carries
 * type-specific data in JSON.
 *
 * No server exists yet: [SyncClient] is bound to a local no-op. This file is the
 * shape the backend and the desktop agent will speak.
 */

/** A device participating in the account's sync (phone, laptop agent, …). */
data class DeviceInfo(
    val id: String,
    val platform: DevicePlatform,
    val name: String,
    val lastSeenAt: Long
)

enum class DevicePlatform { ANDROID, DESKTOP, WEB, UNKNOWN }

/** What a record carries. Adding a value is additive — payload stays JSON. */
enum class SyncEntityType { GOAL, FOCUS_SESSION, POINTS_ENTRY, DEVICE_EVENT }

/**
 * One syncable change. [id] is stable across devices (so the same goal edited on
 * two devices is one record), [updatedAt] drives last-write-wins by default,
 * [deleted] is a tombstone, and [payloadJson] is the entity's own serialized form.
 */
data class SyncRecord(
    val type: SyncEntityType,
    val id: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val payloadJson: String,
    val originDeviceId: String
)

/** A push from one device: its records plus the cursor it last pulled. */
data class SyncBatch(
    val deviceId: String,
    val records: List<SyncRecord>,
    val cursor: String?
)

/** A pull response: remote records since the requested cursor, and the new cursor. */
data class SyncPullResult(
    val records: List<SyncRecord>,
    val nextCursor: String?,
    val serverTimeMillis: Long
) {
    companion object {
        fun empty(nowMillis: Long) = SyncPullResult(emptyList(), null, nowMillis)
    }
}
