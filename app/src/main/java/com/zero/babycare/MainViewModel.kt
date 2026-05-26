package com.zero.babycare

import com.blankj.utilcode.util.ThreadUtils
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.entity.BabyInfo
import com.zero.components.base.vm.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.common.mmkv.MMKVKeys.BABY_INFO
import com.zero.common.mmkv.MMKVStore


class MainViewModel : BaseViewModel() {
    
    /** 导航目标状态 */
    private val _navTarget = MutableStateFlow<NavTarget>(NavTarget.Dashboard)
    val navTarget: StateFlow<NavTarget> = _navTarget

    /**
     * 当前选中的宝宝。
     *
     * 旧页面仍可使用 getCurrentBabyInfo() 同步读取；新数据流页面应优先收集该 StateFlow，
     * 避免每个 Fragment 自己判断何时重新查询当前宝宝。
     */
    private val _currentBaby = MutableStateFlow(loadCurrentBabyFromStore())
    val currentBaby: StateFlow<BabyInfo?> = _currentBaby.asStateFlow()

    /**
     * 导航到指定目标
     */
    fun navigateTo(target: NavTarget) {
        val current = _navTarget.value
        _navTarget.value = when (target) {
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
        return loadCurrentBabyFromStore().also { baby ->
            _currentBaby.value = baby
        }
    }

    /**
     * 从本地缓存和数据库解析当前宝宝。
     *
     * 该方法只负责同步解析和修正缓存，不直接驱动 UI；调用方需要自行更新 _currentBaby。
     */
    private fun loadCurrentBabyFromStore(): BabyInfo? {
        val cachedBaby = MMKVStore.get(BABY_INFO, BabyInfo::class.java)
        
        if (cachedBaby != null) {
            // 验证缓存的宝宝是否存在于数据库中
            val existsInDb = repository.getAllBabyInfo().any { it.babyId == cachedBaby.babyId }
            if (existsInDb) {
                return cachedBaby
            } else {
                // 缓存无效，清除
                MMKVStore.remove(BABY_INFO)
            }
        }
        
        // 返回数据库中的第一个宝宝
        return repository.getAllBabyInfo().firstOrNull()?.also {
            MMKVStore.put(BABY_INFO, it)
        }
    }

    /**
     * 设置当前选中的宝宝
     */
    fun setCurrentBaby(babyInfo: BabyInfo) {
        MMKVStore.put(BABY_INFO, babyInfo)
        _currentBaby.value = babyInfo
    }

    /**
     * 获取所有宝宝列表
     */
    fun getAllBabies(): List<BabyInfo> {
        return repository.getAllBabyInfo()
    }

    /**
     * 根据 ID 获取宝宝信息
     */
    fun getBabyById(babyId: Int): BabyInfo? {
        return repository.getAllBabyInfo().find { it.babyId == babyId }
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
    fun deleteBaby(babyInfo: BabyInfo, onSuccess: () -> Unit) {
        safeLaunch {
            repository.deleteBabyInfo(babyInfo) {
                // 如果删除的是当前选中的宝宝，清除缓存
                if (getCurrentBabyInfo()?.babyId == babyInfo.babyId) {
                    MMKVStore.remove(BABY_INFO)
                }
                val nextBaby = loadCurrentBabyFromStore()
                ThreadUtils.runOnUiThread {
                    _currentBaby.value = nextBaby
                    onSuccess()
                }
            }
        }
    }

    /**
     * 添加宝宝信息
     */
    fun insertBaby(babyInfo: BabyInfo, onSuccess: () -> Unit) {
        safeLaunch {
            repository.insertBabyInfo(babyInfo) {
                ThreadUtils.runOnUiThread {
                    setCurrentBaby(babyInfo)
                    onSuccess()
                }
            }
        }
    }
}
