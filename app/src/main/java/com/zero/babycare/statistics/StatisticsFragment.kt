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
import com.zero.babycare.statistics.adapter.StatisticsSummaryAdapter
import com.zero.babycare.statistics.adapter.TimelineAdapter
import com.zero.babycare.statistics.model.DaySummary
import com.zero.babycare.statistics.model.TimelineItem
import com.zero.babydata.entity.BabyInfo
import com.zero.common.ext.launchInLifecycle
import com.zero.components.base.BaseFragment
import com.zero.components.base.vm.UiState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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
    private lateinit var babyAgeAdapter: StatisticsBabyAgeAdapter
    private lateinit var emptyAdapter: StatisticsEmptyAdapter
    private lateinit var concatAdapter: ConcatAdapter
    private lateinit var timelineAdapter: TimelineAdapter
    private var currentBabyInfo: BabyInfo? = null

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
            summaryAdapter,
            babyAgeAdapter,
            timelineAdapter
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
            currentBabyInfo = null
            babyAgeAdapter.updateBabyDaysText(null)
            mainVm.navigateTo(NavTarget.BabyInfo.create(returnTarget = NavTarget.Statistics(getReturnTarget())))
            false
        } else {
            vm.setBabyId(currentBaby.babyId)
            currentBabyInfo = currentBaby
            updateBabyDays(currentBaby, vm.selectedDate.value)
            true
        }
    }

    private fun updateBabyDays(babyInfo: BabyInfo, selectedDate: LocalDate) {
        if (babyInfo.birthDate > 0) {
            val birthDate = Instant.ofEpochMilli(babyInfo.birthDate)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val days = ChronoUnit.DAYS.between(birthDate, selectedDate)
            if (days < 0) {
                babyAgeAdapter.updateBabyDaysText(
                    StringUtils.getString(com.zero.common.R.string.baby_not_born_yet_playful)
                )
            } else {
                babyAgeAdapter.updateBabyDaysText(
                    StringUtils.getString(com.zero.common.R.string.days_born, days.toInt())
                )
            }
        } else {
            babyAgeAdapter.updateBabyDaysText(null)
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
                calendarAdapter.setDatesWithRecords(dates)
            }
        }

        // 观察 ViewModel 的选中日期变化（外部设置时同步到日历）
        launchInLifecycle {
            vm.selectedDate.collect { date ->
                calendarAdapter.syncSelectedDate(date)
                currentBabyInfo?.let { updateBabyDays(it, date) }
            }
        }
    }

    /**
     * 更新统计摘要 UI
     */
    private fun updateSummaryUI(summary: DaySummary) {
        summaryAdapter.updateSummary(summary)
    }

    /**
     * 更新时间轴 UI
     */
    private fun updateTimelineUI(items: List<TimelineItem>) {
        timelineAdapter.submitList(items)
        val hasEmptyAdapter = concatAdapter.adapters.contains(emptyAdapter)
        if (items.isEmpty()) {
            if (!hasEmptyAdapter) {
                concatAdapter.addAdapter(emptyAdapter)
            }
            timelineAdapter.setRoundBottom(false)
        } else if (hasEmptyAdapter) {
            concatAdapter.removeAdapter(emptyAdapter)
            timelineAdapter.setRoundBottom(true)
        } else {
            timelineAdapter.setRoundBottom(true)
        }
    }

    /**
     * 处理时间轴项点击
     */
    private fun handleTimelineItemClick(item: TimelineItem) {
        when (item) {
            is TimelineItem.Feeding -> {
                // 跳转到喂养编辑页面
                mainVm.navigateTo(
                    NavTarget.FeedingRecord(
                        editRecordId = item.record.feedingId,
                        returnTarget = NavTarget.Statistics(getReturnTarget())
                    )
                )
            }
            is TimelineItem.Sleep -> {
                // 跳转到睡眠编辑页面
                mainVm.navigateTo(
                    NavTarget.SleepRecord(
                        editRecordId = item.record.sleepId,
                        returnTarget = NavTarget.Statistics(getReturnTarget())
                    )
                )
            }
            is TimelineItem.Event -> {
                // 跳转到事件编辑页面
                mainVm.navigateTo(
                    NavTarget.EventRecord(
                        editRecordId = item.record.eventId,
                        returnTarget = NavTarget.Statistics(getReturnTarget())
                    )
                )
            }
        }
    }
}
