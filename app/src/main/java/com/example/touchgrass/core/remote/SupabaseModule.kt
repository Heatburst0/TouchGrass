package com.example.touchgrass.core.remote

import com.example.touchgrass.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import javax.inject.Singleton

/**
 * The single Supabase client for the app. Auth is configured with the app's deep
 * link (touchgrass://auth-callback) so magic-link sign-in returns into the app.
 * Only constructed when first injected, so a missing config doesn't crash startup.
 */
@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient =
        createSupabaseClient(
            // Fallbacks keep construction from crashing an unconfigured build; the UI
            // gates real usage on AuthRepository.isConfigured.
            supabaseUrl = BuildConfig.SUPABASE_URL.ifBlank { "https://unconfigured.supabase.co" },
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY.ifBlank { "unconfigured" }
        ) {
            install(Auth) {
                scheme = "touchgrass"
                host = "auth-callback"
            }
            install(Postgrest)
            install(Realtime)
        }
}
