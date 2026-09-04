package com.example.touchgrass.features.reading

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
 * Reading is verified on demand: a page is credited only after it's genuinely read
 * (dwell) or passes an AI quiz, and [ReadingCredit] pushes those verified pages into
 * every active READING goal via the engine. This verifier makes READING a
 * first-class goal type in the registry; the poller never calls [verify].
 */
class ReadingVerifier @Inject constructor() : GoalVerifier {
    override val type = GoalType.READING
    override val cadence: VerificationCadence = VerificationCadence.OnDemand

    override suspend fun verify(goal: GoalEntity): VerificationResult =
        VerificationResult.NoChange
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReadingVerifierModule {
    @Binds
    @IntoMap
    @GoalTypeKey(GoalType.READING)
    abstract fun bindReadingVerifier(impl: ReadingVerifier): GoalVerifier
}
