package com.example.touchgrass.core.goals

enum class CommitmentStatus {
    ACTIVE,   // in progress, before deadline
    MET,      // target reached -> reward paid
    MISSED    // deadline passed unmet -> penalty applied
}
