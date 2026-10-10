package com.example.touchgrass.features.focus.insights

/** A package foreground-state transition at a point in time. */
data class AppUsageEvent(
    val packageName: String,
    val type: Type,
    val timeMillis: Long
) {
    enum class Type {
        FOREGROUND,
        BACKGROUND,
        SCREEN_OFF
    }
}

/** Calculates foreground durations from application usage transitions. */
object ForegroundTimeCalculator {
    /** Whole seconds each package was in the foreground within [startMillis, endMillis]; zero-second packages omitted. */
    fun compute(
        events: List<AppUsageEvent>,
        startMillis: Long,
        endMillis: Long
    ): Map<String, Int> {
        if (endMillis <= startMillis) return emptyMap()

        val milliseconds = mutableMapOf<String, Long>()
        var currentPackage: String? = null
        var foregroundSince = startMillis

        fun closeCurrent(atMillis: Long) {
            val packageName = currentPackage ?: return
            val clippedStart = maxOf(foregroundSince, startMillis)
            val clippedEnd = minOf(atMillis, endMillis)
            val duration = maxOf(0L, clippedEnd - clippedStart)
            if (duration > 0L) {
                milliseconds[packageName] = (milliseconds[packageName] ?: 0L) + duration
            }
        }

        for (event in events.sortedBy(AppUsageEvent::timeMillis)) {
            when (event.type) {
                AppUsageEvent.Type.FOREGROUND -> {
                    if (currentPackage != event.packageName) {
                        closeCurrent(event.timeMillis)
                        currentPackage = event.packageName
                        foregroundSince = event.timeMillis
                    }
                }

                AppUsageEvent.Type.BACKGROUND -> {
                    if (currentPackage == event.packageName) {
                        closeCurrent(event.timeMillis)
                        currentPackage = null
                    }
                }

                AppUsageEvent.Type.SCREEN_OFF -> {
                    closeCurrent(event.timeMillis)
                    currentPackage = null
                }
            }
        }
        closeCurrent(endMillis)

        return milliseconds.mapNotNull { (packageName, duration) ->
            val seconds = (duration / MILLIS_PER_SECOND).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (seconds > 0) packageName to seconds else null
        }.toMap()
    }

    private const val MILLIS_PER_SECOND = 1_000L
}
