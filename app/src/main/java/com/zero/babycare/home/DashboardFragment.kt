package com.zero.babycare.home

import com.zero.babycare.databinding.FragmentDashboardBinding
import com.zero.components.base.BaseFragment

class DashboardFragment: BaseFragment<FragmentDashboardBinding>() {
    companion object{
        fun create():DashboardFragment{
            return DashboardFragment()
        }
    }
}