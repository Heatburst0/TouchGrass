package com.example.touchgrass.features.focus.insights

import com.example.touchgrass.core.data.db.FocusSessionEntity

/** Device that recorded a focus session. */
enum class SessionSource {
    PHONE,
    LAPTOP
}

/** Returns the recorded source, treating legacy and unknown values as phone sessions. */
fun FocusSessionEntity.sessionSource(): SessionSource =
    if (source == SessionSource.LAPTOP.name) SessionSource.LAPTOP else SessionSource.PHONE
