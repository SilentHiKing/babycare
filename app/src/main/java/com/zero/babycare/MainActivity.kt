package com.zero.babycare

import android.os.Bundle
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import com.blankj.utilcode.util.FragmentUtils
import com.blankj.utilcode.util.LogUtils
import com.zero.babycare.babyinfo.UpdateInfoFragment
import com.zero.babycare.databinding.ActivityMainBinding
import com.zero.babycare.home.DashboardFragment
import com.zero.babycare.home.record.FeedingRecordFragment
import com.zero.common.ext.launchInLifecycle
import com.zero.components.base.BaseActivity
import kotlinx.coroutines.flow.collectLatest
import kotlin.getValue

class MainActivity : BaseActivity<ActivityMainBinding>() {
    private val vm by viewModels<MainViewModel>()
    private val fragments = mutableListOf<Fragment>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun initView(savedInstanceState: Bundle?) {

        fragments.add(UpdateInfoFragment.create())
        fragments.add(DashboardFragment.create())
        fragments.add(FeedingRecordFragment.create())
        fragments.forEach {
            FragmentUtils.add(supportFragmentManager, it, R.id.flContainer, true)
        }
        LogUtils.d("initView")
        launchInLifecycle {
            vm.fragmentStatus.collectLatest { status ->
                LogUtils.d("status: $status")
                fragments.firstOrNull { f ->
                    status?.isInstance(f) == true
                }?.let { target ->
                    LogUtils.d("target: $target")
                    FragmentUtils.showHide(target, fragments.filter { it != target })
                }
            }
        }

    }

    override fun initData(savedInstanceState: Bundle?) {


    }


}
