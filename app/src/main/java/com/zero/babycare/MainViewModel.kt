package com.zero.babycare

import androidx.fragment.app.Fragment
import com.zero.babycare.babyinfo.UpdateInfoFragment
import com.zero.babycare.home.record.FeedingRecordFragment
import com.zero.babydata.entity.BabyInfo
import com.zero.components.base.vm.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.common.mmkv.MMKVKeys.BABY_INFO
import com.zero.common.mmkv.MMKVStore


class MainViewModel() : BaseViewModel() {
    val fragmentStatus = MutableStateFlow<Class<out Fragment>?>(FeedingRecordFragment::class.java)


    fun switchFragment(fragment: Class<out Fragment>) {
        fragmentStatus.value = fragment
    }

    fun insert(babyInfo: BabyInfo) {
        safeLaunch {
        }
    }

    fun getCurrentBabyInfo(): BabyInfo? {
        return MMKVStore.get(BABY_INFO, BabyInfo::class.java)?:run {
            repository.allBabyInfo.firstOrNull()
        }
    }


}