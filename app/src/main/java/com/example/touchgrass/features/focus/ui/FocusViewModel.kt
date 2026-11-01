package com.example.touchgrass.features.focus.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.data.db.FocusStats
import com.example.touchgrass.core.discipline.PlanField
import com.example.touchgrass.core.remote.LaptopStatus
import com.example.touchgrass.core.remote.LaptopStatusRepository
import com.example.touchgrass.core.screentime.ScreenTimeNudger
import com.example.touchgrass.features.focus.AppInfo
import com.example.touchgrass.features.focus.FocusConfig
import com.example.touchgrass.features.focus.FocusSchedule
import com.example.touchgrass.features.focus.FocusScheduleRepository
import com.example.touchgrass.features.focus.FocusSessionManager
import com.example.touchgrass.features.focus.InstalledApps
import com.example.touchgrass.features.focus.insights.AppCategoryLookup
import com.example.touchgrass.features.focus.insights.RecordItem
import com.example.touchgrass.features.focus.insights.RecordMerger
import com.example.touchgrass.features.focus.insights.UsageEventsSource
import com.example.touchgrass.features.weeklyplan.WeeklyPlanRepository
import com.example.touchgrass.features.weeklyplan.WeeklyPlanState
import com.example.touchgrass.features.worktargets.WorkTargetsRepository
import com.example.touchgrass.features.worktargets.ui.TargetRow
import com.example.touchgrass.features.worktargets.ui.buildTargetRows
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Only suggest apps used at least this much in the last week. */
private const val SUGGEST_MIN_WEEK_MS = 20 * 60_000L
private const val SUGGEST_MAX = 3
private const val WEEK_MS = 7 * 24 * 60 * 60_000L

@HiltViewModel
class FocusViewModel @Inject constructor(
    private val manager: FocusSessionManager,
    private val settings: SettingsRepository,
    private val installedApps: InstalledApps,
    private val scheduleRepo: FocusScheduleRepository,
    private val usageEvents: UsageEventsSource,
    private val appCategories: AppCategoryLookup,
    private val weeklyPlanRepo: WeeklyPlanRepository,
    private val workTargets: WorkTargetsRepository,
    private val laptopStatusRepo: LaptopStatusRepository
) : ViewModel() {
    val activeSession = manager.activeSession
    val rememberedBlocked = settings.focusBlockedPackages

    /** "Your record": phone + laptop sessions, a synced pair merged into one row. */
    val recordItems: StateFlow<List<RecordItem>> = manager.recentSessions
        .map { RecordMerger.merge(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recordStats: StateFlow<FocusStats> = recordItems
        .map { RecordMerger.stats(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FocusStats(0, 0, 0))

    private val weekUsage = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val dismissedSuggestions = MutableStateFlow<Set<String>>(emptySet())

    /** Social / entertainment apps used a lot this week that focus doesn't block yet. */
    val blockSuggestions: StateFlow<List<BlockSuggestion>> =
        combine(weekUsage, rememberedBlocked, dismissedSuggestions) { usage, remembered, dismissed ->
            val blocked = remembered.ifEmpty { ScreenTimeNudger.WATCHED_PACKAGES }
            usage.entries
                .filter { (pkg, ms) ->
                    ms >= SUGGEST_MIN_WEEK_MS && pkg !in blocked && pkg !in dismissed &&
                        appCategories.isDistracting(pkg)
                }
                .sortedByDescending { it.value }
                .take(SUGGEST_MAX)
                .map { BlockSuggestion(it.key, installedApps.label(it.key), it.value) }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Add a suggested app to the focus blocklist. */
    fun blockSuggested(pkg: String) {
        viewModelScope.launch {
            // Adding a block is always allowed (it's stricter), even while locked.
            weeklyPlanRepo.update { plan ->
                plan.copy(blockedPackages = plan.blockedPackages.ifEmpty { ScreenTimeNudger.WATCHED_PACKAGES } + pkg)
            }
        }
    }

    /** Weekly plan lock — drives the lock pills and which blocked apps can be removed. */
    val weeklyPlan: StateFlow<WeeklyPlanState> = weeklyPlanRepo.state

    /** Refused loosening while the weekly plan is locked, for a snackbar. */
    val lockMessage = MutableStateFlow<String?>(null)

    /** Work targets with a weekly quota, for the "Weekly quotas" summary. */
    val quotaRows: StateFlow<List<TargetRow>> =
        combine(workTargets.targets, workTargets.usage, weeklyPlanRepo.state) { targets, usage, plan ->
            buildTargetRows(targets, usage, plan.plan.focusQuotaMinutes).filter { it.quotaMin > 0 }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Targets that start focus on their own when you open them on the laptop. */
    val autoFocusTargets: StateFlow<Int> = workTargets.targets
        .map { list -> list.count { it.enabled && it.autoFocus } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val laptopStatus = MutableStateFlow<LaptopStatus>(LaptopStatus.Unknown)

    /** "Not now": hide the current suggestions for this visit. */
    fun dismissSuggestions() {
        dismissedSuggestions.value = dismissedSuggestions.value + blockSuggestions.value.map { it.packageName }
    }
    val schedules = scheduleRepo.schedules
    val syncToLaptop = settings.syncFocusToLaptop
    val blockSitesOnLaptop = settings.blockSitesOnLaptop
    val violations: Int get() = manager.violations

    fun setSyncToLaptop(v: Boolean) { viewModelScope.launch { settings.setSyncFocusToLaptop(v) } }
    fun setBlockSites(v: Boolean) { viewModelScope.launch { settings.setBlockSitesOnLaptop(v) } }

    val apps = MutableStateFlow<List<AppInfo>>(emptyList())

    init {
        viewModelScope.launch { apps.value = installedApps.launchable() }
        viewModelScope.launch {
            weekUsage.value = usageEvents.totalForegroundMillis(System.currentTimeMillis() - WEEK_MS)
        }
    }

    /** On resume: fresh agent status and quota progress (both change while you're away). */
    fun refreshLaptop() {
        viewModelScope.launch { laptopStatus.value = laptopStatusRepo.status() }
        viewModelScope.launch { workTargets.refreshUsage() }
    }

    fun start(config: FocusConfig, syncToLaptop: Boolean, blockSites: Boolean) =
        manager.start(config, syncToLaptop, blockSites)
    fun endEarly() = manager.endEarly()
    fun clearToIdle() = manager.clearToIdle()
    fun settleIfComplete() = manager.settleIfComplete()

    /** Goes through the weekly plan: adding apps always works; removing waits for the weekend. */
    fun saveBlocked(packages: Set<String>) {
        viewModelScope.launch {
            val result = weeklyPlanRepo.update { it.copy(blockedPackages = packages) }
            if (PlanField.BLOCKED_APPS in result.refused) {
                lockMessage.value = "Locked by your weekly plan — you can add apps, not remove them."
            }
        }
    }
    fun label(pkg: String): String = installedApps.label(pkg)

    fun addSchedule(schedule: FocusSchedule) {
        viewModelScope.launch { scheduleRepo.create(schedule) }
    }
    fun deleteSchedule(id: String) {
        viewModelScope.launch { scheduleRepo.delete(id) }
    }
}
