package com.zero.babycare

import androidx.fragment.app.Fragment
import com.blankj.utilcode.util.StringUtils
import com.blankj.utilcode.util.Utils
import com.zero.babycare.babyinfo.UpdateInfoFragment
import com.zero.babydata.domain.BabyInfoRepository
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.room.BabyRepository
import com.zero.components.base.vm.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow


class MainViewModel() : BaseViewModel() {
    val fragmentStatus = MutableStateFlow<Class<out Fragment>?>(UpdateInfoFragment::class.java)



    fun switchFragment(fragment: Class<out Fragment>) {
        fragmentStatus.value = fragment
    }

    fun insert(babyInfo: BabyInfo) {
        safeLaunch {
        }
    }


}