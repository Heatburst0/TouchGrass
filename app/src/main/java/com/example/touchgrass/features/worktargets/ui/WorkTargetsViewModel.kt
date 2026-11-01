package com.example.touchgrass.features.worktargets.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.touchgrass.core.discipline.PlanField
import com.example.touchgrass.core.remote.DeviceApp
import com.example.touchgrass.core.remote.DeviceAppsRepository
import com.example.touchgrass.features.weeklyplan.WeeklyPlanRepository
import com.example.touchgrass.features.weeklyplan.WeeklyPlanState
import com.example.touchgrass.features.worktargets.AppUsageRow
import com.example.touchgrass.features.worktargets.DeviceProject
import com.example.touchgrass.features.worktargets.WorkTarget
import com.example.touchgrass.features.worktargets.WorkTargetUsage
import com.example.touchgrass.features.worktargets.WorkTargetsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject

/** A target as the list shows it: this week's minutes against its (plan-owned) quota. */
data class TargetRow(val target: WorkTarget, val quotaMin: Int, val weekMin: Int)

/** Rows for [targets] this week; quotas come from the weekly plan. Shared with the Focus tab. */
internal fun buildTargetRows(
    targets: List<WorkTarget>,
    usage: List<AppUsageRow>,
    quotas: Map<String, Int>,
    today: LocalDate = LocalDate.now()
): List<TargetRow> {
    val week = WorkTargetUsage.weekStart(today)
    return targets.map { t -> TargetRow(t, quotas[t.id] ?: 0, WorkTargetUsage.minutesInWeek(t, usage, week)) }
}

/** Everything the target picker suggests from. */
data class PickerSource(
    val projects: List<DeviceProject> = emptyList(),
    val usage: List<AppUsageRow> = emptyList(),
    val apps: List<DeviceApp> = emptyList()
)

@HiltViewModel
class WorkTargetsViewModel @Inject constructor(
    private val repo: WorkTargetsRepository,
    private val deviceApps: DeviceAppsRepository,
    private val weeklyPlan: WeeklyPlanRepository
) : ViewModel() {
    val isConfigured: Boolean = repo.isConfigured
    val usage: StateFlow<List<AppUsageRow>> = repo.usage
    val plan: StateFlow<WeeklyPlanState> = weeklyPlan.state
    val message = MutableStateFlow<String?>(null)

    val rows: StateFlow<List<TargetRow>> = combine(repo.targets, repo.usage, weeklyPlan.state) { targets, usage, plan ->
        buildTargetRows(targets, usage, plan.plan.focusQuotaMinutes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pickerSource: StateFlow<PickerSource> = combine(repo.projects, repo.usage, deviceApps.apps, ::PickerSource)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PickerSource())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repo.refresh()
            deviceApps.refresh()
        }
    }

    /** Save the target, then its quota through the weekly plan (lowering waits for the weekend). */
    fun save(target: WorkTarget, quotaMin: Int) {
        viewModelScope.launch {
            // A locked quota also locks WHAT it measures — otherwise retargeting it to a
            // busier app or "any project" would quietly make the commitment easier.
            val original = repo.targets.value.firstOrNull { it.id == target.id }
            val lockedQuota = weeklyPlan.currentState().locked &&
                (weeklyPlan.plan.focusQuotaMinutes[target.id] ?: 0) > 0
            if (original != null && lockedQuota &&
                (original.appName != target.appName || original.project != target.project)
            ) {
                message.value = "This target's quota is locked until the weekend — its app and project can't change."
                return@launch
            }
            val id = attempt("Couldn't save — check your connection.") { repo.save(target) } ?: return@launch
            val result = weeklyPlan.update { p ->
                val quotas = if (quotaMin > 0) p.focusQuotaMinutes + (id to quotaMin) else p.focusQuotaMinutes - id
                p.copy(focusQuotaMinutes = quotas)
            }
            if (PlanField.FOCUS_QUOTAS in result.refused) {
                message.value = "Saved, but the quota is locked by your weekly plan — you can raise it, not lower it."
            }
        }
    }

    /** Quick toggle from the list card. */
    fun setAutoFocus(target: WorkTarget, on: Boolean) {
        viewModelScope.launch {
            attempt("Couldn't update auto-focus.") { repo.save(target.copy(autoFocus = on)) }
        }
    }

    /** A target whose quota is locked can't be deleted until the weekend (it'd drop the quota). */
    fun delete(target: WorkTarget) {
        viewModelScope.launch {
            if (target.id in weeklyPlan.plan.focusQuotaMinutes) {
                val result = weeklyPlan.update { it.copy(focusQuotaMinutes = it.focusQuotaMinutes - target.id) }
                if (PlanField.FOCUS_QUOTAS in result.refused) {
                    message.value = "This target's quota is locked by your weekly plan until the weekend."
                    return@launch
                }
            }
            attempt("Couldn't delete — check your connection.") { repo.delete(target.id) }
        }
    }

    private suspend fun <T> attempt(failure: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag("WorkTargets").w(e, failure)
        message.value = failure
        null
    }
}
