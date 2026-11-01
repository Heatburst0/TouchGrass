package com.example.touchgrass.core.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** What the Focus tab says about the laptop agent. */
sealed interface LaptopStatus {
    /** Not signed in, or the server couldn't be reached — say nothing definite. */
    data object Unknown : LaptopStatus
    data object NotConnected : LaptopStatus
    data object Online : LaptopStatus
    data class LastSeen(val ago: Duration) : LaptopStatus

    companion object {
        /** The agent counts as online if it reported within this window. */
        val ONLINE_WINDOW: Duration = Duration.ofMinutes(30)

        /** [lastSeen] = newest sign of life from any DESKTOP device; null = none registered. */
        fun of(lastSeen: Instant?, now: Instant): LaptopStatus {
            if (lastSeen == null) return NotConnected
            val ago = Duration.between(lastSeen, now).coerceAtLeast(Duration.ZERO)
            return if (ago <= ONLINE_WINDOW) Online else LastSeen(ago)
        }

        /** "5 min", "3 h", "2 days". */
        fun formatAgo(ago: Duration): String = when {
            ago.toHours() < 1 -> "${ago.toMinutes()} min"
            ago.toDays() < 1 -> "${ago.toHours()} h"
            ago.toDays() == 1L -> "1 day"
            else -> "${ago.toDays()} days"
        }
    }
}

@Serializable
private data class RemoteDesktopDevice(
    val last_seen_at: String? = null,
    val updated_at: String? = null
)

@Serializable
private data class RemoteUsageStamp(val updated_at: String? = null)

/**
 * Reads when the laptop agent was last heard from: the `devices` row it upserts at
 * start-up (last_seen_at / updated_at) and, since that row isn't a heartbeat, the
 * newest `app_usage` write it makes while you work.
 */
@Singleton
class LaptopStatusRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val auth: AuthRepository
) {
    suspend fun status(now: Instant = Instant.now()): LaptopStatus {
        if (!auth.isConfigured || auth.currentUserId() == null) return LaptopStatus.Unknown
        return runCatching {
            val devices = supabase.from("devices").select {
                filter { eq("platform", "DESKTOP") }
            }.decodeList<RemoteDesktopDevice>()
            if (devices.isEmpty()) return@runCatching LaptopStatus.NotConnected
            val usage = supabase.from("app_usage").select {
                order("updated_at", Order.DESCENDING)
                limit(1)
            }.decodeList<RemoteUsageStamp>()
            val stamps = devices.flatMap { listOf(it.last_seen_at, it.updated_at) } + usage.map { it.updated_at }
            LaptopStatus.of(stamps.mapNotNull(::parse).maxOrNull(), now)
        }.getOrElse {
            if (it is CancellationException) throw it
            Timber.tag("Sync").w(it, "laptop status fetch failed")
            LaptopStatus.Unknown
        }
    }

    private fun parse(ts: String?): Instant? =
        ts?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
}
