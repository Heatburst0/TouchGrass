package com.example.touchgrass.features.focus

import com.example.touchgrass.core.data.db.GoalEntity
import com.example.touchgrass.core.goals.GoalType
import com.example.touchgrass.core.goals.GoalTypeKey
import com.example.touchgrass.core.goals.GoalVerifier
import com.example.touchgrass.core.goals.VerificationCadence
import com.example.touchgrass.core.goals.VerificationResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Inject

/**
 * Focus sessions verify by completion, not by polling: when a session finishes,
 * [FocusSessionManager] pushes a `reportProgress(FOCUS_SESSION, 1)` into the spine.
 * This verifier exists so FOCUS_SESSION is a first-class goal type in the registry
 * (the architecture's "every goal has a verifier" contract); its [cadence] is
 * [VerificationCadence.OnDemand], so the poller never calls [verify].
 */
class FocusVerifier @Inject constructor() : GoalVerifier {
    override val type = GoalType.FOCUS_SESSION
    override val cadence: VerificationCadence = VerificationCadence.OnDemand

    override suspend fun verify(goal: GoalEntity): VerificationResult =
        VerificationResult.NoChange
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FocusVerifierModule {
    @Binds
    @IntoMap
    @GoalTypeKey(GoalType.FOCUS_SESSION)
    abstract fun bindFocusVerifier(impl: FocusVerifier): GoalVerifier
}
