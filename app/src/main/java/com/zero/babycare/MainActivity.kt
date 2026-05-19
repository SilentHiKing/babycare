package com.zero.babycare

import android.content.res.Configuration
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.FragmentUtils
import com.blankj.utilcode.util.LogUtils
import com.zero.babycare.babies.AllChildrenFragment
import com.zero.babycare.babyinfo.UpdateInfoFragment
import com.zero.babycare.databinding.ActivityMainBinding
import com.zero.babycare.databinding.LayoutNavDrawerBinding
import com.zero.babycare.home.DashboardFragment
import com.zero.babycare.home.record.FeedingRecordFragment
import com.zero.babycare.home.record.SleepRecordFragment
import com.zero.babycare.home.record.event.EventRecordFragment
import com.zero.babycare.navigation.BackPressHandler
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.settings.SettingsFragment
import com.zero.babycare.settings.backup.BackupFragment
import com.zero.babycare.statistics.StatisticsFragment
import com.zero.babydata.entity.BabyInfo
import com.zero.common.ext.launchInLifecycle
import com.zero.common.theme.ThemeManager
import com.zero.common.util.BabyGender
import com.zero.common.util.DeviceUtils
import com.zero.components.base.BaseActivity
import kotlinx.coroutines.flow.collectLatest
import me.jessyan.autosize.AutoSizeConfig
import java.util.concurrent.TimeUnit
import kotlin.getValue
import kotlin.math.abs

class MainActivity : BaseActivity<ActivityMainBinding>() {
    companion object {
        private const val DRAWER_EDGE_WIDTH_DP = 120f
        private const val DRAWER_OPEN_DISTANCE_DP = 4f
        private const val DRAWER_HORIZONTAL_RATIO = 1f
    }

    private val vm by viewModels<MainViewModel>()
    private val fragments = mutableListOf<Fragment>()
    private val backPressCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            handleSystemBack()
        }
    }
    
    /** 侧边栏绑定 */
    private val drawerBinding: LayoutNavDrawerBinding by lazy {
        LayoutNavDrawerBinding.bind(binding.navDrawer.root)
    }
    private val drawerEdgeWidthPx by lazy { dpToPx(DRAWER_EDGE_WIDTH_DP) }
    private val drawerTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    private val drawerSwipeTriggerPx by lazy { maxOf(drawerTouchSlop, dpToPx(DRAWER_OPEN_DISTANCE_DP)) }
    private var drawerGestureStartX = 0f
    private var drawerGestureStartY = 0f
    private var isTrackingDrawerGesture = false
    private var isDrawerGestureClaimed = false
    private var hasCancelledDashboardTouch = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横竖屏切换时重新配置 AutoSize 设计稿宽度
        val designWidth = DeviceUtils.getDesignWidthInDp(this)
        AutoSizeConfig.getInstance().setDesignWidthInDp(designWidth)
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
            // 无宝宝数据时也只使用男孩 / 女孩主题集合内的兜底值，避免出现第三套中性视觉。
            ThemeManager.applyTheme(this, ThemeManager.BabyTheme.BOY)
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
        // 统一系统返回
        setupBackPressDispatcher()
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
        fragments.add(SettingsFragment.create())
        fragments.add(BackupFragment.create())

        fragments.forEach {
            FragmentUtils.add(supportFragmentManager, it, R.id.flContainer, true)
        }
        LogUtils.d("setupFragments")
    }

    /**
     * 初始化侧边栏
     */
    private fun setupDrawer() {
        // 首页去掉显式菜单按钮后，确保 DrawerLayout 自身的 start 侧边缘手势不被状态恢复锁住。
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)

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
        
        // 设置
        drawerBinding.llSettings.setOnClickListener {
            closeDrawer()
            vm.navigateTo(NavTarget.Settings())
        }
    }

    private fun setupBackPressDispatcher() {
        onBackPressedDispatcher.addCallback(this, backPressCallback)
    }

    /**
     * 更新侧边栏宝宝信息
     */
    private fun updateDrawerBabyInfo(babyInfo: BabyInfo) {
        drawerBinding.tvBabyName.text = babyInfo.name
        
        // 计算出生天数
        if (babyInfo.birthDate > 0) {
            val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - babyInfo.birthDate)
            drawerBinding.tvBabyDays.text = getString(com.zero.common.R.string.days_born, days.toInt())
        } else {
            drawerBinding.tvBabyDays.visibility = View.GONE
        }
        
        // 性别图标
        when (BabyGender.normalize(babyInfo.gender)) {
            BabyGender.BOY -> {
                drawerBinding.ivGender.setImageResource(com.zero.common.R.drawable.ic_gender_boy)
                drawerBinding.ivGender.visibility = View.VISIBLE
            }
            BabyGender.GIRL -> {
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
        drawerBinding.tvBabyName.text = getString(com.zero.common.R.string.no_baby_yet)
        drawerBinding.tvBabyDays.text = getString(com.zero.common.R.string.add_baby)
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (handleDashboardDrawerGesture(event)) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun handleDashboardDrawerGesture(event: MotionEvent): Boolean {
        if (vm.navTarget.value !is NavTarget.Dashboard) {
            resetDrawerGestureTracking()
            return false
        }

        if (isDrawerOpen() && !isDrawerGestureClaimed) {
            resetDrawerGestureTracking()
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawerGestureStartX = event.x
                drawerGestureStartY = event.y
                isTrackingDrawerGesture = event.x <= drawerEdgeWidthPx
                isDrawerGestureClaimed = false
                hasCancelledDashboardTouch = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDrawerGestureClaimed) {
                    return true
                }

                if (!isTrackingDrawerGesture) return false

                val dx = event.x - drawerGestureStartX
                val dy = event.y - drawerGestureStartY
                val absDx = abs(dx)
                val absDy = abs(dy)
                if (absDx < drawerSwipeTriggerPx && absDy < drawerSwipeTriggerPx) {
                    return false
                }

                if (absDy > absDx * DRAWER_HORIZONTAL_RATIO) {
                    resetDrawerGestureTracking()
                    return false
                }

                if (dx > 0f && absDx >= absDy * DRAWER_HORIZONTAL_RATIO) {
                    // 左边缘触摸一旦明确为右横滑，就立刻接管整段事件，避免 NestedScrollView 继续竞争。
                    isDrawerGestureClaimed = true
                    cancelDashboardTouchIfNeeded(event)
                    if (!isDrawerOpen()) {
                        openDrawer()
                    }
                    return true
                }

                resetDrawerGestureTracking()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val shouldConsume = isDrawerGestureClaimed
                resetDrawerGestureTracking()
                return shouldConsume
            }
        }
        return false
    }

    private fun resetDrawerGestureTracking() {
        isTrackingDrawerGesture = false
        isDrawerGestureClaimed = false
        hasCancelledDashboardTouch = false
        drawerGestureStartX = 0f
        drawerGestureStartY = 0f
    }

    private fun cancelDashboardTouchIfNeeded(event: MotionEvent) {
        if (hasCancelledDashboardTouch) return

        val cancelEvent = MotionEvent.obtain(event).apply {
            action = MotionEvent.ACTION_CANCEL
        }
        super.dispatchTouchEvent(cancelEvent)
        cancelEvent.recycle()
        hasCancelledDashboardTouch = true
    }

    private fun dpToPx(value: Float): Float {
        return value * resources.displayMetrics.density
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

    private fun handleSystemBack() {
        if (isDrawerOpen()) {
            closeDrawer()
            return
        }

        val currentTarget = vm.navTarget.value
        val currentFragment = fragments.firstOrNull { fragment ->
            currentTarget.fragmentClass.isInstance(fragment)
        }

        if (currentFragment is BackPressHandler && currentFragment.onSystemBackPressed()) {
            return
        }

        if (currentTarget !is NavTarget.Dashboard) {
            vm.navigateTo(NavTarget.Dashboard)
            return
        }

        backPressCallback.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
        backPressCallback.isEnabled = true
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
