package com.zero.babycare

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.Utils
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.reminder.ReminderScheduler
import com.zero.babycare.reminder.ReminderType
import com.zero.babydata.entity.BabyInfo
import com.zero.components.base.vm.BaseViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.common.mmkv.MMKVKeys.CURRENT_BABY_ID
import com.zero.common.mmkv.MMKVKeys.LEGACY_BABY_INFO
import com.zero.babycare.babies.CurrentBabySelection
import com.zero.babycare.babies.BabyDeletionNavigation
import com.zero.common.mmkv.MMKVStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow


class MainViewModel(
    private val savedStateHandle: SavedStateHandle
) : BaseViewModel() {
    
    /** 导航目标状态 */
    private val _navTarget = MutableStateFlow(
        savedStateHandle.get<NavTarget>(NAV_TARGET_KEY) ?: NavTarget.Dashboard
    )
    val navTarget: StateFlow<NavTarget> = _navTarget

    /**
     * 当前选中的宝宝。
     *
     * 旧页面仍可使用 getCurrentBabyInfo() 同步读取；新数据流页面应优先收集该 StateFlow，
     * 避免每个 Fragment 自己判断何时重新查询当前宝宝。
     */
    private val _currentBaby = MutableStateFlow<BabyInfo?>(null)
    val currentBaby: StateFlow<BabyInfo?> = _currentBaby.asStateFlow()

    /** 首次 Room 快照到达后才允许 Activity 决定初始页面。 */
    private val _babyDataLoaded = MutableStateFlow(false)
    val babyDataLoaded: StateFlow<Boolean> = _babyDataLoaded.asStateFlow()

    /** 数据库中的宝宝列表是页面与当前宝宝校验的唯一事实源。 */
    private val _allBabies = MutableStateFlow<List<BabyInfo>>(emptyList())
    val allBabies: StateFlow<List<BabyInfo>> = _allBabies.asStateFlow()

    enum class BabyDeletionResult { SUCCESS, FAILED }

    private val deletionResults = Channel<BabyDeletionResult>(Channel.BUFFERED)
    // 仅由 MainActivity 收集，隐藏 Fragment 不争抢结果，配置重建期间也不丢失反馈。
    val babyDeletionEffects = deletionResults.receiveAsFlow()
    private val _deletingBabyIds = MutableStateFlow<Set<Int>>(emptySet())
    val deletingBabyIds: StateFlow<Set<Int>> = _deletingBabyIds.asStateFlow()

    fun isDeletingBaby(babyId: Int?): Boolean = babyId != null && babyId in _deletingBabyIds.value

    init {
        // Room Flow 自动跟踪增删改；缓存只负责恢复选择，不再触发主线程数据库查询。
        viewModelScope.launch {
            repository.observeAllBabyInfo().collectLatest { babies ->
                reconcileBabies(babies)
                _babyDataLoaded.value = true
            }
        }
    }

    /** 系统通知设置可在应用外改变，返回前台时恢复因权限被关闭而结束的提醒任务。 */
    fun onAppForeground() {
        ReminderScheduler.ensureScheduled(Utils.getApp())
    }

    /**
     * 导航到指定目标
     */
    fun navigateTo(target: NavTarget) {
        val current = _navTarget.value
        val resolvedTarget = when (target) {
            is NavTarget.Statistics -> target.copy(
                returnTarget = resolveReturnTarget(target.returnTarget, current, (current as? NavTarget.Statistics)?.returnTarget)
            )
            is NavTarget.FeedingRecord -> target.copy(
                returnTarget = resolveReturnTarget(target.returnTarget, current, (current as? NavTarget.FeedingRecord)?.returnTarget)
            )
            is NavTarget.SleepRecord -> target.copy(
                returnTarget = resolveReturnTarget(target.returnTarget, current, (current as? NavTarget.SleepRecord)?.returnTarget)
            )
            is NavTarget.EventRecord -> target.copy(
                returnTarget = resolveReturnTarget(target.returnTarget, current, (current as? NavTarget.EventRecord)?.returnTarget)
            )
            is NavTarget.Settings -> target.copy(
                returnTarget = resolveReturnTarget(target.returnTarget, current, (current as? NavTarget.Settings)?.returnTarget)
            )
            is NavTarget.Backup -> target.copy(
                returnTarget = resolveReturnTarget(target.returnTarget, current, (current as? NavTarget.Backup)?.returnTarget)
            )
            else -> target
        }
        _navTarget.value = resolvedTarget
        // 导航参数随进程状态保存，Activity 重建后不会丢失编辑对象或返回路径。
        savedStateHandle[NAV_TARGET_KEY] = resolvedTarget
    }

    private fun resolveReturnTarget(
        explicitReturnTarget: NavTarget?,
        current: NavTarget,
        sameTypeReturnTarget: NavTarget?
    ): NavTarget {
        return explicitReturnTarget ?: sameTypeReturnTarget ?: current
    }

    /**
     * 获取当前选中的宝宝信息
     * 
     * 安全策略：验证缓存的宝宝是否仍存在于数据库中
     * 防止因删除宝宝后缓存未清理导致的外键约束失败
     */
    fun getCurrentBabyInfo(): BabyInfo? {
        return _currentBaby.value
    }

    /**
     * 使用数据库最新列表校验当前选择；MMKV 只保存 ID，完整对象始终以 Room 为准。
     */
    private fun reconcileBabies(babies: List<BabyInfo>) {
        _allBabies.value = babies
        val selectedId = CurrentBabySelection.resolveId(
            availableIds = babies.map { it.babyId },
            inMemoryId = _currentBaby.value?.babyId,
            storedId = MMKVStore.getInt(CURRENT_BABY_ID).takeIf { it > 0 },
            legacyJson = MMKVStore.getString(LEGACY_BABY_INFO)
        )
        val selected = babies.firstOrNull { it.babyId == selectedId } ?: babies.firstOrNull()
        _currentBaby.value = selected
        if (selected == null) {
            MMKVStore.remove(CURRENT_BABY_ID)
        } else {
            MMKVStore.put(CURRENT_BABY_ID, selected.babyId)
        }
        // 新缓存写入后再删除旧完整对象，升级中断时仍可重试恢复选择。
        MMKVStore.remove(LEGACY_BABY_INFO)
    }

    /**
     * 设置当前选中的宝宝
     */
    fun setCurrentBaby(babyInfo: BabyInfo) {
        MMKVStore.put(CURRENT_BABY_ID, babyInfo.babyId)
        MMKVStore.remove(LEGACY_BABY_INFO)
        _currentBaby.value = babyInfo
    }

    /**
     * 获取所有宝宝列表
     */
    fun getAllBabies(): List<BabyInfo> {
        return _allBabies.value
    }

    /**
     * 根据 ID 获取宝宝信息
     */
    fun getBabyById(babyId: Int): BabyInfo? {
        return _allBabies.value.find { it.babyId == babyId }
    }

    /**
     * 如果编辑的是当前选中的宝宝，更新缓存
     */
    fun updateCurrentBabyIfNeeded(babyInfo: BabyInfo) {
        val currentBaby = _currentBaby.value ?: getCurrentBabyInfo()
        if (currentBaby?.babyId == babyInfo.babyId) {
            setCurrentBaby(babyInfo)
        }
    }

    /**
     * 删除宝宝信息
     */
    fun deleteBaby(babyInfo: BabyInfo) {
        if (isDeletingBaby(babyInfo.babyId)) return
        val requestedFrom = _navTarget.value
        _deletingBabyIds.value += babyInfo.babyId
        // 删除任务只持有数据和导航身份；页面销毁不会留下访问旧 binding 的完成闭包。
        viewModelScope.launch {
            try {
                repository.deleteBabyInfo(babyInfo)
                if (_currentBaby.value?.babyId == babyInfo.babyId) {
                    MMKVStore.remove(CURRENT_BABY_ID)
                }
                val remaining = repository.getAllBabyInfo()
                reconcileBabies(remaining)
                BabyDeletionNavigation.destination(
                    deletedBabyId = babyInfo.babyId,
                    requestedFrom = requestedFrom,
                    currentTarget = _navTarget.value,
                    hasRemainingBabies = remaining.isNotEmpty()
                )?.let(::navigateTo)
                deletionResults.send(BabyDeletionResult.SUCCESS)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                deletionResults.send(BabyDeletionResult.FAILED)
            } finally {
                _deletingBabyIds.value -= babyInfo.babyId
            }
        }
    }

    /** 通知点击按 babyId 重新查库并切换对象，避免只依赖可能过期的当前宝宝缓存。 */
    fun openReminderTarget(babyId: Int, type: ReminderType) {
        safeLaunch {
            val baby = repository.getBabyInfo(babyId) ?: return@safeLaunch
            setCurrentBaby(baby)
            val target = when (type) {
                ReminderType.FEEDING -> NavTarget.FeedingRecord(returnTarget = NavTarget.Dashboard)
                ReminderType.SLEEP -> NavTarget.SleepRecord(returnTarget = NavTarget.Dashboard)
            }
            navigateTo(target)
        }
    }

    private companion object {
        const val NAV_TARGET_KEY = "main_nav_target"
    }
}
