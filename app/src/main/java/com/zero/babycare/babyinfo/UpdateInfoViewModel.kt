package com.zero.babycare.babyinfo

import com.blankj.utilcode.util.Utils
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.room.BabyRepository
import com.zero.components.base.vm.BaseViewModel


class UpdateInfoViewModel() : BaseViewModel() {

    fun insert(babyInfo: BabyInfo, callback: Runnable) {
        safeLaunch {
            repository.insertBabyInfo(babyInfo, callback)
        }
    }


}