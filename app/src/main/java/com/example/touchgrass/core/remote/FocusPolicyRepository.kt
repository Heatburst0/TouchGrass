package com.example.touchgrass.core.remote

import com.example.touchgrass.di.ApplicationScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** The desktop enforcement rules the laptop agent reads (edited from the phone). */
data class FocusPolicy(
    val allowedApps: List<String> = emptyList(),
    val blockedApps: List<String> = emptyList(),
    val forceQuitApps: List<String> = emptyList(),
    val blockedSites: List<String> = emptyList()
)

@Serializable
private data class RemotePolicy(
    val allowed_apps: List<String> = emptyList(),
    val blocked_apps: List<String> = emptyList(),
    val force_quit_apps: List<String> = emptyList(),
    val blocked_sites: List<String> = emptyList()
)

private fun RemotePolicy.toDomain() = FocusPolicy(allowed_apps, blocked_apps, force_quit_apps, blocked_sites)
private fun FocusPolicy.toRemote() = RemotePolicy(allowedApps, blockedApps, forceQuitApps, blockedSites)

@Singleton
class FocusPolicyRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val auth: AuthRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _policy = MutableStateFlow(FocusPolicy())
    val policy: StateFlow<FocusPolicy> = _policy.asStateFlow()

    val isConfigured: Boolean = auth.isConfigured

    init {
        if (auth.isConfigured) {
            scope.launch {
                auth.sessionStatus.collect { status ->
                    if (status is SessionStatus.Authenticated) refresh()
                }
            }
        }
    }

    suspend fun refresh() {
        runCatching {
            val rows = supabase.from("focus_policy").select().decodeList<RemotePolicy>()
            _policy.value = rows.firstOrNull()?.toDomain() ?: FocusPolicy()
        }.onFailure { Timber.tag("Sync").w(it, "policy refresh failed") }
    }

    fun update(policy: FocusPolicy) {
        _policy.value = policy // optimistic
        scope.launch {
            runCatching { supabase.from("focus_policy").upsert(policy.toRemote()) }
                .onFailure { Timber.tag("Sync").w(it, "policy update failed") }
        }
    }
}
