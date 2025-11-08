package com.zero.babycare.babyinfo

import com.blankj.utilcode.util.StringUtils
import com.blankj.utilcode.util.Utils
import com.zero.babydata.domain.BabyInfoRepository
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.room.BabyRepository
import com.zero.components.base.vm.BaseViewModel


class UpdateInfoViewModel() : BaseViewModel() {
    val repository = BabyRepository(Utils.getApp())

    fun insert(babyInfo: BabyInfo, callback: Runnable) {
        safeLaunch {
            repository.insertBabyInfo(babyInfo, callback)
        }
    }


}