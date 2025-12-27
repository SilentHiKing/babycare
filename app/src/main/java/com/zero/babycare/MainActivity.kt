package com.zero.babycare

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.FragmentUtils
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.StringUtils
import com.zero.babycare.babies.AllChildrenFragment
import com.zero.babycare.babyinfo.UpdateInfoFragment
import com.zero.babycare.databinding.ActivityMainBinding
import com.zero.babycare.databinding.LayoutNavDrawerBinding
import com.zero.babycare.home.DashboardFragment
import com.zero.babycare.home.record.FeedingRecordFragment
import com.zero.babycare.home.record.SleepRecordFragment
import com.zero.babycare.home.record.event.EventRecordFragment
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.statistics.StatisticsFragment
import com.zero.babydata.entity.BabyInfo
import com.zero.common.ext.launchInLifecycle
import com.zero.common.theme.ThemeManager
import com.zero.components.base.BaseActivity
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.TimeUnit
import kotlin.getValue

class MainActivity : BaseActivity<ActivityMainBinding>() {
    private val vm by viewModels<MainViewModel>()
    private val fragments = mutableListOf<Fragment>()
    
    /** 侧边栏绑定 */
    private val drawerBinding: LayoutNavDrawerBinding by lazy {
        LayoutNavDrawerBinding.bind(binding.navDrawer.root)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    /**
     * 覆写主题应用逻辑
     * 根据当前选中的宝宝性别设置主题
     */
    override fun applyThemeIfNeeded() {
        val currentBaby = vm.getCurrentBabyInfo()
        if (currentBaby != null) {
            // 有宝宝数据，根据性别设置主题
            val theme = ThemeManager.getThemeByGender(currentBaby.gender)
            ThemeManager.saveTheme(theme)
            ThemeManager.applyTheme(this, theme)
        } else {
            // 无宝宝数据，使用默认主题
            ThemeManager.applyTheme(this, ThemeManager.BabyTheme.DEFAULT)
        }
    }

    /**
     * 切换宝宝并更新主题
     * @param babyInfo 新选中的宝宝信息
     */
    fun switchBabyAndUpdateTheme(babyInfo: BabyInfo) {
        // 保存当前宝宝
        vm.setCurrentBaby(babyInfo)
        // 更新侧边栏显示
        updateDrawerBabyInfo(babyInfo)
        // 检查是否需要切换主题
        if (ThemeManager.switchBabyTheme(babyInfo.gender)) {
            // 主题发生变化，需要重建 Activity
            recreate()
        }
    }

    override fun initView(savedInstanceState: Bundle?) {
        // 初始化 Fragments
        setupFragments()
        // 初始化侧边栏
        setupDrawer()
        // 监听 Fragment 切换
        observeFragmentStatus()
    }

    /**
     * 初始化 Fragments
     */
    private fun setupFragments() {
        fragments.add(UpdateInfoFragment.create())
        fragments.add(DashboardFragment.create())
        fragments.add(FeedingRecordFragment.create())
        fragments.add(SleepRecordFragment.create())
        fragments.add(AllChildrenFragment.create())
        fragments.add(EventRecordFragment.create())
        fragments.add(StatisticsFragment.create())

        fragments.forEach {
            FragmentUtils.add(supportFragmentManager, it, R.id.flContainer, true)
        }
        LogUtils.d("setupFragments")
    }

    /**
     * 初始化侧边栏
     */
    private fun setupDrawer() {
        // 版本号
        drawerBinding.tvVersion.text = "v${AppUtils.getAppVersionName()}"
        
        // 更新当前宝宝信息
        val currentBaby = vm.getCurrentBabyInfo()
        if (currentBaby != null) {
            updateDrawerBabyInfo(currentBaby)
        } else {
            showNoBabyState()
        }
        
        // 点击宝宝信息区域 -> 进入宝宝详情
        drawerBinding.clBabyInfo.setOnClickListener {
            closeDrawer()
            val currentBabyInfo = vm.getCurrentBabyInfo()
            if (currentBabyInfo != null) {
                // 进入宝宝详情/编辑页面
                vm.navigateTo(NavTarget.BabyInfo.edit(currentBabyInfo.babyId))
            } else {
                // 没有宝宝，进入创建页面
                vm.navigateTo(NavTarget.BabyInfo.create())
            }
        }
        
        // 所有宝宝
        drawerBinding.llAllBabies.setOnClickListener {
            closeDrawer()
            vm.navigateTo(NavTarget.AllChildren)
        }
        
        // 数据统计
        drawerBinding.llStatistics.setOnClickListener {
            closeDrawer()
            vm.navigateTo(NavTarget.Statistics)
        }
        
        // 设置
        drawerBinding.llSettings.setOnClickListener {
            closeDrawer()
            // TODO: 进入设置页面
        }
    }

    /**
     * 更新侧边栏宝宝信息
     */
    private fun updateDrawerBabyInfo(babyInfo: BabyInfo) {
        drawerBinding.tvBabyName.text = babyInfo.name
        
        // 计算出生天数
        if (babyInfo.birthDate > 0) {
            val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - babyInfo.birthDate)
            drawerBinding.tvBabyDays.text = StringUtils.getString(com.zero.common.R.string.days_born, days.toInt())
        } else {
            drawerBinding.tvBabyDays.visibility = View.GONE
        }
        
        // 性别图标
        when {
            babyInfo.gender.contains("男") || babyInfo.gender.lowercase().contains("boy") -> {
                drawerBinding.ivGender.setImageResource(com.zero.common.R.drawable.ic_gender_boy)
                drawerBinding.ivGender.visibility = View.VISIBLE
            }
            babyInfo.gender.contains("女") || babyInfo.gender.lowercase().contains("girl") -> {
                drawerBinding.ivGender.setImageResource(com.zero.common.R.drawable.ic_gender_girl)
                drawerBinding.ivGender.visibility = View.VISIBLE
            }
            else -> {
                drawerBinding.ivGender.visibility = View.GONE
            }
        }
    }

    /**
     * 显示无宝宝状态
     */
    private fun showNoBabyState() {
        drawerBinding.tvBabyName.text = StringUtils.getString(com.zero.common.R.string.no_baby_yet)
        drawerBinding.tvBabyDays.text = StringUtils.getString(com.zero.common.R.string.add_baby)
        drawerBinding.ivGender.visibility = View.GONE
    }

    /**
     * 监听导航状态切换 Fragment
     */
    private fun observeFragmentStatus() {
        launchInLifecycle {
            vm.navTarget.collectLatest { navTarget ->
                LogUtils.d("navTarget: $navTarget")
                val targetClass = navTarget.fragmentClass
                fragments.firstOrNull { f ->
                    targetClass.isInstance(f)
                }?.let { target ->
                    LogUtils.d("target: $target")
                    FragmentUtils.showHide(target, fragments.filter { it != target })
                }
            }
        }
    }

    /**
     * 打开侧边栏
     */
    fun openDrawer() {
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    /**
     * 关闭侧边栏
     */
    fun closeDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    /**
     * 侧边栏是否打开
     */
    fun isDrawerOpen(): Boolean {
        return binding.drawerLayout.isDrawerOpen(GravityCompat.START)
    }

    override fun initData(savedInstanceState: Bundle?) {
        // 判断是否有宝宝数据，决定显示哪个页面
        val currentBaby = vm.getCurrentBabyInfo()
        if (currentBaby == null) {
            // 无宝宝数据，进入创建页面
            vm.navigateTo(NavTarget.BabyInfo.create())
        } else {
            // 有宝宝数据，进入概况页
            vm.navigateTo(NavTarget.Dashboard)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isDrawerOpen()) {
            closeDrawer()
        } else {
            super.onBackPressed()
        }
    }

    /**
     * 刷新侧边栏宝宝信息
     */
    fun refreshDrawerBabyInfo() {
        val currentBaby = vm.getCurrentBabyInfo()
        if (currentBaby != null) {
            updateDrawerBabyInfo(currentBaby)
        } else {
            showNoBabyState()
        }
    }
}
