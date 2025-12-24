package com.zero.babycare

import com.blankj.utilcode.util.ThreadUtils
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.entity.BabyInfo
import com.zero.components.base.vm.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.common.mmkv.MMKVKeys.BABY_INFO
import com.zero.common.mmkv.MMKVStore


class MainViewModel : BaseViewModel() {
    
    /** 导航目标状态 */
    private val _navTarget = MutableStateFlow<NavTarget>(NavTarget.Dashboard)
    val navTarget: StateFlow<NavTarget> = _navTarget

    /**
     * 导航到指定目标
     */
    fun navigateTo(target: NavTarget) {
        _navTarget.value = target
    }

    /**
     * 获取当前选中的宝宝信息
     */
    fun getCurrentBabyInfo(): BabyInfo? {
        return MMKVStore.get(BABY_INFO, BabyInfo::class.java) ?: run {
            repository.getAllBabyInfo().firstOrNull()
        }
    }

    /**
     * 设置当前选中的宝宝
     */
    fun setCurrentBaby(babyInfo: BabyInfo) {
        MMKVStore.put(BABY_INFO, babyInfo)
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
        val currentBaby = getCurrentBabyInfo()
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
                ThreadUtils.runOnUiThread { onSuccess() }
            }
        }
    }

    /**
     * 添加宝宝信息
     */
    fun insertBaby(babyInfo: BabyInfo, onSuccess: () -> Unit) {
        safeLaunch {
            repository.insertBabyInfo(babyInfo) {
                ThreadUtils.runOnUiThread { onSuccess() }
            }
        }
    }
}
