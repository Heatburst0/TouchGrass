package com.example.touchgrass.core.remote

import com.example.touchgrass.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Account/session over Supabase Auth (email magic-link). Sign-in is passwordless:
 * we email a link that deep-links back into the app (touchgrass://auth-callback),
 * where [io.github.jan.supabase.SupabaseClient.handleDeeplinks] completes it.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val supabase: SupabaseClient
) {
    /** Live session state. Authenticated carries the user session. */
    val sessionStatus: StateFlow<SessionStatus> = supabase.auth.sessionStatus

    /** False until a backend URL/key is configured — the UI hides sync then. */
    val isConfigured: Boolean =
        BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id
    fun currentUserEmail(): String? = supabase.auth.currentUserOrNull()?.email

    /** Send a magic-link sign-in email. */
    suspend fun sendMagicLink(email: String): Result<Unit> = runCatching {
        supabase.auth.signInWith(OTP) { this.email = email.trim() }
    }

    suspend fun signOut(): Result<Unit> = runCatching { supabase.auth.signOut() }
}
