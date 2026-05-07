package com.zero.babycare.statistics

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.blankj.utilcode.util.StringUtils
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentStatisticsBinding
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.navigation.BackPressHandler
import com.zero.babycare.statistics.adapter.StatisticsBabyAgeAdapter
import com.zero.babycare.statistics.adapter.StatisticsCalendarAdapter
import com.zero.babycare.statistics.adapter.StatisticsEmptyAdapter
import com.zero.babycare.statistics.adapter.StatisticsGrowthPercentileAdapter
import com.zero.babycare.statistics.adapter.StatisticsGrowthAdapter
import com.zero.babycare.statistics.adapter.StatisticsHealthAdapter
import com.zero.babycare.statistics.adapter.StatisticsStructureAdapter
import com.zero.babycare.statistics.adapter.StatisticsSummaryAdapter
import com.zero.babycare.statistics.adapter.StatisticsTrendAdapter
import com.zero.babycare.statistics.adapter.TimelineAdapter
import com.zero.babycare.statistics.model.TimelineEditTarget
import com.zero.babycare.statistics.model.TimelineUiItem
import com.zero.common.ext.launchInLifecycle
import com.zero.components.base.BaseFragment

/**
 * 数据统计页面
 */
class StatisticsFragment : BaseFragment<FragmentStatisticsBinding>(), BackPressHandler {

    companion object {
        fun create(): StatisticsFragment {
            return StatisticsFragment()
        }
    }

    private val vm by viewModels<StatisticsViewModel>()
    private val mainVm: MainViewModel by lazy {
        activityViewModels<MainViewModel>().value
    }

    private lateinit var calendarAdapter: StatisticsCalendarAdapter
    private lateinit var summaryAdapter: StatisticsSummaryAdapter
    private lateinit var trendAdapter: StatisticsTrendAdapter
    private lateinit var structureAdapter: StatisticsStructureAdapter
    private lateinit var growthAdapter: StatisticsGrowthAdapter
    private lateinit var growthPercentileAdapter: StatisticsGrowthPercentileAdapter
    private lateinit var healthAdapter: StatisticsHealthAdapter
    private lateinit var babyAgeAdapter: StatisticsBabyAgeAdapter
    private lateinit var emptyAdapter: StatisticsEmptyAdapter
    private lateinit var concatAdapter: ConcatAdapter
    private lateinit var timelineAdapter: TimelineAdapter

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)

        // 设置标题
        binding.toolbar.title = StringUtils.getString(com.zero.common.R.string.statistics)
        
        // 左侧返回按钮 - 返回到入口页面
        binding.toolbar.showBackButton {
            mainVm.navigateTo(getReturnTarget())
        }
        
        // 隐藏右侧按钮
        binding.toolbar.hideAction()

        // 初始化列表内容
        setupContentList()
    }

    override fun initData(view: View, savedInstanceState: Bundle?) {
        super.initData(view, savedInstanceState)

        // 获取当前宝宝ID
        ensureBabyOrNavigate()

        // 观察数据变化
        observeData()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // 页面显示时刷新数据
            if (ensureBabyOrNavigate()) {
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
    }

    /**
     * 初始化列表内容
     */
    private fun setupContentList() {
        calendarAdapter = StatisticsCalendarAdapter(
            onDateSelected = { date -> vm.selectDate(date) },
            onMonthChanged = { yearMonth -> vm.onMonthChanged(yearMonth) },
            onModeChanged = { },
            onTodayClick = { vm.goToToday() }
        )
        summaryAdapter = StatisticsSummaryAdapter()
        trendAdapter = StatisticsTrendAdapter()
        structureAdapter = StatisticsStructureAdapter()
        growthAdapter = StatisticsGrowthAdapter()
        growthPercentileAdapter = StatisticsGrowthPercentileAdapter()
        healthAdapter = StatisticsHealthAdapter()
        babyAgeAdapter = StatisticsBabyAgeAdapter()
        timelineAdapter = TimelineAdapter { item ->
            handleTimelineItemClick(item)
        }
        emptyAdapter = StatisticsEmptyAdapter {
            if (!ensureBabyOrNavigate()) {
                return@StatisticsEmptyAdapter
            }
            mainVm.navigateTo(NavTarget.Dashboard)
        }

        concatAdapter = ConcatAdapter(
            calendarAdapter,
            babyAgeAdapter,
            summaryAdapter,
            timelineAdapter,
            trendAdapter,
            structureAdapter,
            growthAdapter,
            growthPercentileAdapter,
            healthAdapter
        )

        binding.rvStatistics.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = concatAdapter
        }
    }

    private fun getReturnTarget(): NavTarget {
        return (mainVm.navTarget.value as? NavTarget.Statistics)?.returnTarget ?: NavTarget.Dashboard
    }

    override fun onSystemBackPressed(): Boolean {
        mainVm.navigateTo(getReturnTarget())
        return true
    }

    private fun ensureBabyOrNavigate(): Boolean {
        val currentBaby = mainVm.getCurrentBabyInfo()
        return if (currentBaby == null) {
            babyAgeAdapter.updateBabyDaysText(null)
            mainVm.navigateTo(NavTarget.BabyInfo.create(returnTarget = NavTarget.Statistics(getReturnTarget())))
            false
        } else {
            vm.setBabyId(currentBaby.babyId)
            true
        }
    }

    /**
     * 观察数据变化
     */
    private fun observeData() {
        launchInLifecycle {
            vm.uiState.collect { state ->
                calendarAdapter.setDatesWithRecords(state.datesWithRecords)
                calendarAdapter.syncSelectedDate(state.selectedDate)
                babyAgeAdapter.bindDayRecord(state.dayRecord)
                summaryAdapter.updateSummary(state.dayRecord.summaryMetrics)
                updateTimelineUI(state.dayRecord.timelineItems)
                trendAdapter.updateTrend(state.insights.trend)
                structureAdapter.updateStructure(state.insights.structure)
                growthAdapter.updateTrend(state.insights.growth)
                growthPercentileAdapter.updatePercentile(state.insights.percentile)
                healthAdapter.updateHealth(state.insights.health)
            }
        }
    }

    /**
     * 更新时间轴 UI
     */
    private fun updateTimelineUI(items: List<TimelineUiItem>) {
        timelineAdapter.submitList(items)
        val hasEmptyAdapter = concatAdapter.adapters.contains(emptyAdapter)
        val timelineIndex = concatAdapter.adapters.indexOf(timelineAdapter)
        if (items.isEmpty()) {
            if (!hasEmptyAdapter) {
                concatAdapter.addAdapter(timelineIndex, emptyAdapter)
            }
            timelineAdapter.setRoundBottom(false)
        } else {
            if (hasEmptyAdapter) {
                concatAdapter.removeAdapter(emptyAdapter)
            }
            timelineAdapter.setRoundBottom(true)
        }
    }

    /**
     * 处理时间轴项点击
     */
    private fun handleTimelineItemClick(item: TimelineUiItem) {
        when (val target = item.editTarget) {
            is TimelineEditTarget.Feeding -> {
                // 跳转到喂养编辑页面
                mainVm.navigateTo(
                    NavTarget.FeedingRecord(
                        editRecordId = target.recordId,
                        returnTarget = NavTarget.Statistics(getReturnTarget())
                    )
                )
            }
            is TimelineEditTarget.Sleep -> {
                // 跳转到睡眠编辑页面
                mainVm.navigateTo(
                    NavTarget.SleepRecord(
                        editRecordId = target.recordId,
                        returnTarget = NavTarget.Statistics(getReturnTarget())
                    )
                )
            }
            is TimelineEditTarget.Event -> {
                // 跳转到事件编辑页面
                mainVm.navigateTo(
                    NavTarget.EventRecord(
                        editRecordId = target.recordId,
                        returnTarget = NavTarget.Statistics(getReturnTarget())
                    )
                )
            }
        }
    }
}
