package com.zero.babycare.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.zero.babycare.MainViewModel
import com.zero.babycare.babyinfo.UpdateInfoViewModel
import com.zero.babycare.databinding.FragmentDashboardBinding
import com.zero.components.base.BaseFragment
import kotlin.getValue

class DashboardFragment: BaseFragment<FragmentDashboardBinding>() {
    companion object{
        fun create():DashboardFragment{
            return DashboardFragment()
        }
    }
    private val vm by viewModels<DashboardViewModel>()
    private val mainVm: MainViewModel by lazy {
        activityViewModels<MainViewModel>().value
    }

    override fun initData(view: View, savedInstanceState: Bundle?) {
        super.initData(view, savedInstanceState)
    }
}