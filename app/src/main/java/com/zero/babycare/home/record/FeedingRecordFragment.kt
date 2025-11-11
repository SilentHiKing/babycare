package com.zero.babycare.home.record

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.chad.library.adapter4.QuickAdapterHelper
import com.zero.babycare.MainViewModel
import com.zero.babycare.babyinfo.UpdateInfoViewModel
import com.zero.babycare.databinding.FragmentDashboardBinding
import com.zero.babycare.databinding.FragmentFeedingRecordBinding
import com.zero.babycare.home.DashboardAdapter
import com.zero.babycare.home.DashboardViewModel
import com.zero.babycare.home.bean.DashboardEntity
import com.zero.common.ext.launchInLifecycle
import com.zero.components.base.BaseFragment
import com.zero.components.base.vm.UiState
import java.util.Date
import kotlin.getValue

class FeedingRecordFragment : BaseFragment<FragmentFeedingRecordBinding>() {
    companion object {
        fun create(): FeedingRecordFragment {
            return FeedingRecordFragment()
        }
    }

    private val vm by viewModels<FeedingRecordViewModel>()
    private val mainVm: MainViewModel by lazy {
        activityViewModels<MainViewModel>().value
    }


    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
    }

    override fun initData(view: View, savedInstanceState: Bundle?) {
        super.initData(view, savedInstanceState)
        launchInLifecycle {
            vm.uiState.collect {
                when (it) {
                    is UiState.Success -> {
                        (it.data as? List<DashboardEntity>)?.let { list ->
                        }
                    }
                    else -> {
                    }
                }
            }
        }


    }
}