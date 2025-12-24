package com.zero.babycare.babyinfo

import com.blankj.utilcode.util.ThreadUtils
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.babydata.entity.BabyInfo
import com.zero.components.base.vm.BaseViewModel


class UpdateInfoViewModel : BaseViewModel() {

    fun insert(babyInfo: BabyInfo, callback: Runnable) {
        safeLaunch {
            repository.insertBabyInfo(babyInfo) {
                ThreadUtils.runOnUiThread(callback)
            }
        }
    }

    fun update(babyInfo: BabyInfo, callback: Runnable) {
        safeLaunch {
            repository.updateBabyInfo(babyInfo) {
                ThreadUtils.runOnUiThread(callback)
            }
        }
    }
}