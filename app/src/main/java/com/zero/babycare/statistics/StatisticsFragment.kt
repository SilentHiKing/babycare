package com.zero.babycare.statistics

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.blankj.utilcode.util.StringUtils
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentStatisticsBinding
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.statistics.adapter.TimelineAdapter
import com.zero.babycare.statistics.model.DaySummary
import com.zero.babycare.statistics.model.TimelineItem
import com.zero.babycare.statistics.widget.BabyCalendarView
import com.zero.common.ext.launchInLifecycle
import com.zero.components.base.BaseFragment
import com.zero.components.base.vm.UiState

/**
 * 数据统计页面
 */
class StatisticsFragment : BaseFragment<FragmentStatisticsBinding>() {

    companion object {
        fun create(): StatisticsFragment {
            return StatisticsFragment()
        }
    }

    private val vm by viewModels<StatisticsViewModel>()
    private val mainVm: MainViewModel by lazy {
        activityViewModels<MainViewModel>().value
    }

    private lateinit var timelineAdapter: TimelineAdapter

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)

        // 设置标题
        binding.toolbar.title = StringUtils.getString(com.zero.common.R.string.statistics)
        
        // 左侧菜单按钮 - 打开侧边栏
        binding.toolbar.showMenuButton {
            (activity as? MainActivity)?.openDrawer()
        }
        
        // 隐藏右侧按钮
        binding.toolbar.hideAction()

        // 初始化日历
        setupCalendar()
        
        // 初始化时间轴列表
        setupTimeline()
        
        // 设置点击事件
        setupClickListeners()
    }

    override fun initData(view: View, savedInstanceState: Bundle?) {
        super.initData(view, savedInstanceState)

        // 获取当前宝宝ID
        val currentBaby = mainVm.getCurrentBabyInfo()
        currentBaby?.let {
            vm.setBabyId(it.babyId)
        }

        // 观察数据变化
        observeData()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // 页面显示时刷新数据
            val currentBaby = mainVm.getCurrentBabyInfo()
            currentBaby?.let {
                if (vm.currentBabyId.value != it.babyId) {
                    vm.setBabyId(it.babyId)
                } else {
                    vm.refreshData()
                }
            }
        }
    }

    /**
     * 初始化日历
     */
    private fun setupCalendar() {
        // 日期选择监听
        binding.calendarView.setOnDateSelectedListener { date ->
            vm.selectDate(date)
            updateCalendarTitle()
        }

        // 月份变化监听
        binding.calendarView.setOnMonthChangedListener { yearMonth ->
            vm.onMonthChanged(yearMonth)
            // 切换月份时不改变选中日期，所以不需要同步
            updateCalendarTitle()
        }

        // 模式变化监听
        binding.calendarView.setOnModeChangedListener { mode ->
            updateExpandIndicator(mode)
            updateCalendarTitle()
        }

        // 上一周/月
        binding.ivPrevious.setOnClickListener {
            binding.calendarView.navigatePrevious()
            // 切换周/月时不改变选中日期，所以不需要同步到 ViewModel
            updateCalendarTitle()
        }

        // 下一周/月
        binding.ivNext.setOnClickListener {
            binding.calendarView.navigateNext()
            // 切换周/月时不改变选中日期，所以不需要同步到 ViewModel
            updateCalendarTitle()
        }

        // 今天按钮
        binding.tvToday.setOnClickListener {
            binding.calendarView.goToToday()
            vm.goToToday()
            updateCalendarTitle()
        }

        // 点击展开/收起指示器切换视图模式
        binding.ivExpandIndicator.setOnClickListener {
            binding.calendarView.toggleViewMode()
        }

        // 初始化标题
        updateCalendarTitle()
    }

    /**
     * 初始化时间轴列表
     */
    private fun setupTimeline() {
        timelineAdapter = TimelineAdapter { item ->
            handleTimelineItemClick(item)
        }

        binding.rvTimeline.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = timelineAdapter
        }
    }

    /**
     * 设置点击事件
     */
    private fun setupClickListeners() {
        // 空状态添加记录按钮
        binding.tvAddRecord.setOnClickListener {
            // 跳转到快速记录选择
            mainVm.navigateTo(NavTarget.Dashboard)
        }
    }

    /**
     * 观察数据变化
     */
    private fun observeData() {
        // 观察当日摘要
        launchInLifecycle {
            vm.daySummary.collect { state ->
                when (state) {
                    is UiState.None -> {
                        // 初始状态
                    }
                    is UiState.Loading -> {
                        // 可以显示加载状态
                    }
                    is UiState.Success -> {
                        state.data?.let { updateSummaryUI(it) }
                    }
                    is UiState.Error -> {
                        // 显示错误状态
                    }
                }
            }
        }

        // 观察时间轴数据
        launchInLifecycle {
            vm.timelineItems.collect { state ->
                when (state) {
                    is UiState.None -> {
                        // 初始状态
                    }
                    is UiState.Loading -> {
                        // 可以显示加载状态
                    }
                    is UiState.Success -> {
                        updateTimelineUI(state.data ?: emptyList())
                    }
                    is UiState.Error -> {
                        // 显示错误状态
                    }
                }
            }
        }

        // 观察有记录的日期
        launchInLifecycle {
            vm.datesWithRecords.collect { dates ->
                binding.calendarView.setDatesWithRecords(dates)
            }
        }

        // 观察 ViewModel 的选中日期变化（外部设置时同步到日历）
        launchInLifecycle {
            vm.selectedDate.collect { date ->
                val calendarSelectedDate = binding.calendarView.getSelectedDate()
                if (date != calendarSelectedDate) {
                    binding.calendarView.setSelectedDate(date, notify = false)
                    updateCalendarTitle()
                }
            }
        }
    }

    /**
     * 更新日历标题
     */
    private fun updateCalendarTitle() {
        binding.tvCalendarTitle.text = binding.calendarView.getFormattedTitle()
    }

    /**
     * 更新展开/收起指示器
     */
    private fun updateExpandIndicator(mode: BabyCalendarView.ViewMode) {
        val rotation = when (mode) {
            BabyCalendarView.ViewMode.WEEK -> 90f   // 向下箭头
            BabyCalendarView.ViewMode.MONTH -> -90f // 向上箭头
        }
        binding.ivExpandIndicator.animate()
            .rotation(rotation)
            .setDuration(200)
            .start()
    }

    /**
     * 更新统计摘要 UI
     */
    private fun updateSummaryUI(summary: DaySummary) {
        with(binding) {
            // 喂养
            tvFeedingCount.text = if (summary.feedingCount > 0) {
                "${summary.feedingCount}次"
            } else {
                "0次"
            }

            // 睡眠
            tvSleepDuration.text = summary.formatSleepDuration()

            // 尿布
            tvDiaperCount.text = if (summary.totalDiaperCount > 0) {
                "${summary.totalDiaperCount}次"
            } else {
                "0次"
            }

            // 其他
            tvOtherCount.text = if (summary.otherEventCount > 0) {
                "${summary.otherEventCount}次"
            } else {
                "0次"
            }
        }
    }

    /**
     * 更新时间轴 UI
     */
    private fun updateTimelineUI(items: List<TimelineItem>) {
        if (items.isEmpty()) {
            binding.rvTimeline.visibility = View.GONE
            binding.llEmptyState.visibility = View.VISIBLE
        } else {
            binding.rvTimeline.visibility = View.VISIBLE
            binding.llEmptyState.visibility = View.GONE
            timelineAdapter.submitList(items)
        }
    }

    /**
     * 处理时间轴项点击
     */
    private fun handleTimelineItemClick(item: TimelineItem) {
        when (item) {
            is TimelineItem.Feeding -> {
                // 跳转到喂养编辑页面
                mainVm.navigateTo(NavTarget.FeedingRecord)
            }
            is TimelineItem.Sleep -> {
                // 跳转到睡眠编辑页面
                mainVm.navigateTo(NavTarget.SleepRecord)
            }
            is TimelineItem.Event -> {
                // 跳转到事件编辑页面
                mainVm.navigateTo(NavTarget.EventRecord())
            }
        }
    }
}

