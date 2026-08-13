package com.example.touchgrass.core.goals

import dagger.Binds          // (used by real bindings added in later phases)
import dagger.MapKey
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@MapKey
annotation class GoalTypeKey(val value: GoalType)

@Module
@InstallIn(SingletonComponent::class)
abstract class GoalVerifierModule {
    /** Lets `Map<GoalType, GoalVerifier>` be injected even while empty. */
    @Multibinds
    abstract fun verifiers(): Map<GoalType, @JvmSuppressWildcards GoalVerifier>
}
