package com.example.touchgrass.core.discipline

/** Settings committed for the week; stricter means less entertainment or more work. */
data class WeeklyPlan(
    val forceReadEnabled: Boolean = false,
    val forceReadEveryMin: Int = 60,
    val shortsDailyLimit: Int = 20,
    val blockedPackages: Set<String> = emptySet(),
    val weeklyReadingPages: Int = 0,
    val accessibilityPenaltyEnabled: Boolean = true,
    val focusQuotaMinutes: Map<String, Int> = emptyMap()
)

/** Independently locked commitment fields. */
enum class PlanField {
    FORCE_READ_ENABLED,
    FORCE_READ_INTERVAL,
    SHORTS_LIMIT,
    BLOCKED_APPS,
    WEEKLY_PAGES,
    ACCESSIBILITY_PENALTY,
    FOCUS_QUOTAS
}
