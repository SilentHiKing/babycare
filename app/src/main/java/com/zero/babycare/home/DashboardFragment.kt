package com.zero.babycare.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.StringUtils
import com.chad.library.adapter4.QuickAdapterHelper
import com.chad.library.adapter4.layoutmanager.QuickGridLayoutManager
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentDashboardBinding
import com.zero.babycare.home.bean.DashboardEntity
import com.zero.babycare.navigation.NavTarget
import com.zero.common.ext.launchInLifecycle
import com.zero.components.base.BaseFragment
import com.zero.components.base.vm.UiState
import java.util.Date
import kotlin.getValue

class DashboardFragment : BaseFragment<FragmentDashboardBinding>() {
    companion object {
        fun create(): DashboardFragment {
            return DashboardFragment()
        }
    }

    private val vm by viewModels<DashboardViewModel>()
    private val mainVm: MainViewModel by lazy {
        activityViewModels<MainViewModel>().value
    }

    private val adapter by lazy(LazyThreadSafetyMode.NONE) {
        DashboardAdapter(emptyList())
    }

    private val helper by lazy(LazyThreadSafetyMode.NONE) {
        QuickAdapterHelper.Builder(adapter)
            .build()
    }

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        
        // 设置标题
        binding.btn.title = StringUtils.getString(com.zero.common.R.string.dashboard)
        
        // 左侧菜单按钮 - 打开侧边栏
        binding.btn.showMenuButton {
            (activity as? MainActivity)?.openDrawer()
        }
        
        // 隐藏右侧按钮（底部已有快捷操作）
        binding.btn.hideAction()
        
        binding.rv.apply {
            adapter = helper.adapter
            layoutManager = QuickGridLayoutManager(requireContext(), 1)
        }
        
        // 底部快捷操作
        binding.llFeedingAction.setOnClickListener {
            mainVm.navigateTo(NavTarget.FeedingRecord)
        }
        binding.llSleepAction.setOnClickListener {
            mainVm.navigateTo(NavTarget.SleepRecord)
        }
    }

    override fun initData(view: View, savedInstanceState: Bundle?) {
        super.initData(view, savedInstanceState)
        launchInLifecycle {
            vm.uiState.collect {
                when (it) {
                    is UiState.Success -> {
                        (it.data as? List<DashboardEntity>)?.let { list ->
                            adapter.submitList(list)
                        }
                    }
                    else -> {
                    }
                }
            }
        }



    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        LogUtils.d("hello1=${hidden}")
        if(!hidden){
            vm.request(mainVm.getCurrentBabyInfo()?.babyId, Date())
        }
    }

    override fun onResume() {
        super.onResume()


    }
}