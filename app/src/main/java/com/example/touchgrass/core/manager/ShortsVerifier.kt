package com.example.touchgrass.core.manager

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
 * Shorts are a LIMIT goal, fed live by the accessibility stream. The battle-tested
 * counting + blocking mechanics stay in [ShortsTrackerManager]; this verifier is the
 * registry token that makes SHORTS_LIMIT a first-class goal type
 * ([VerificationCadence.Continuous]). Wiring the tracker's count into a
 * SHORTS_LIMIT goal (reportProgress) is a one-line follow-up once a shorts limit is
 * user-creatable as a goal.
 */
class ShortsVerifier @Inject constructor() : GoalVerifier {
    override val type = GoalType.SHORTS_LIMIT
    override val cadence: VerificationCadence = VerificationCadence.Continuous

    override suspend fun verify(goal: GoalEntity): VerificationResult =
        VerificationResult.NoChange
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ShortsVerifierModule {
    @Binds
    @IntoMap
    @GoalTypeKey(GoalType.SHORTS_LIMIT)
    abstract fun bindShortsVerifier(impl: ShortsVerifier): GoalVerifier
}
