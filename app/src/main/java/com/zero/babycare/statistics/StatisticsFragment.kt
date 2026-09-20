package com.zero.babycare.statistics

import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
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
import com.zero.babycare.statistics.adapter.StatisticsInsightHeaderAdapter
import com.zero.babycare.statistics.adapter.StatisticsStructureAdapter
import com.zero.babycare.statistics.adapter.StatisticsSummaryAdapter
import com.zero.babycare.statistics.adapter.StatisticsTrendAdapter
import com.zero.babycare.statistics.adapter.TimelineAdapter
import com.zero.babycare.statistics.model.TimelineEditTarget
import com.zero.babycare.statistics.model.TimelineUiItem
import com.zero.babycare.statistics.model.StatisticsUiState
import com.zero.common.ext.launchInLifecycle
import com.zero.components.base.BaseFragment
import kotlinx.coroutines.flow.combine

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
    private lateinit var insightHeaderAdapter: StatisticsInsightHeaderAdapter
    private lateinit var concatAdapter: ConcatAdapter
    private lateinit var timelineAdapter: TimelineAdapter
    private var renderedState: StatisticsUiState? = null
    private var clockReceiverContext: Context? = null
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshClock(force = true)
        }
    }

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)

        // 设置标题
        binding.toolbar.title = getString(com.zero.common.R.string.statistics)
        
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

        // 语言切换会保留 ViewModel；重新建立页面时也重建其中已经格式化的摘要文案。
        syncCurrentBaby(refresh = true)
        observeData()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isCurrentStatisticsTarget()) {
            syncCurrentBaby(refresh = true)
        }
    }

    override fun onStart() {
        super.onStart()
        // 只在页面生命周期内监听；隐藏页面不查询，重新显示时由原可见入口刷新。
        val context = requireContext().applicationContext
        ContextCompat.registerReceiver(context, clockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        clockReceiverContext = context
    }

    override fun onStop() {
        clockReceiverContext?.unregisterReceiver(clockReceiver)
        clockReceiverContext = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshClock()
    }

    /** 恢复只核对时间上下文，正常后台往返不重复重查，也不会把历史选中日改成今天。 */
    private fun refreshClock(force: Boolean = false) {
        if (view == null || isHidden || !isCurrentStatisticsTarget() || !mainVm.babyDataLoaded.value) return
        syncCurrentBaby(refresh = false)
        if (isCurrentStatisticsTarget()) vm.onClockChanged(force)
    }

    /**
     * 初始化列表内容
     */
    private fun setupContentList() {
        calendarAdapter = StatisticsCalendarAdapter(
            onDateSelected = { date -> vm.selectDate(date) },
            onBrowseStateChanged = vm::onCalendarBrowsed,
            onTodayClick = { vm.goToToday() }
        )
        summaryAdapter = StatisticsSummaryAdapter()
        trendAdapter = StatisticsTrendAdapter()
        structureAdapter = StatisticsStructureAdapter()
        growthAdapter = StatisticsGrowthAdapter()
        growthPercentileAdapter = StatisticsGrowthPercentileAdapter()
        healthAdapter = StatisticsHealthAdapter()
        babyAgeAdapter = StatisticsBabyAgeAdapter(::openBirthdateEditor)
        insightHeaderAdapter = StatisticsInsightHeaderAdapter()
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
            insightHeaderAdapter,
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

    private fun isCurrentStatisticsTarget(): Boolean {
        return mainVm.navTarget.value is NavTarget.Statistics
    }

    private fun getReturnTarget(): NavTarget {
        return (mainVm.navTarget.value as? NavTarget.Statistics)?.returnTarget ?: NavTarget.Dashboard
    }

    /** 编辑后回到同一统计入口，选中日期继续由当前 StatisticsViewModel 保留。 */
    private fun openBirthdateEditor() {
        val statisticsTarget = mainVm.navTarget.value as? NavTarget.Statistics ?: return
        val babyId = mainVm.getCurrentBabyInfo()?.babyId ?: return
        mainVm.navigateTo(NavTarget.BabyInfo.edit(babyId, returnTarget = statisticsTarget))
    }

    override fun onSystemBackPressed(): Boolean {
        mainVm.navigateTo(getReturnTarget())
        return true
    }

    private fun ensureBabyOrNavigate(): Boolean {
        // 冷恢复时 Room 尚未给出首个快照，null 只代表未加载，不能据此创建新宝宝。
        if (!mainVm.babyDataLoaded.value) return false
        val currentBaby = mainVm.getCurrentBabyInfo()
        return if (currentBaby == null) {
            vm.setBabyId(0)
            mainVm.navigateTo(NavTarget.BabyInfo.create(returnTarget = NavTarget.Statistics(getReturnTarget())))
            false
        } else {
            true
        }
    }

    /** 仅当前统计页消费已加载的宝宝；显示页面或当前宝宝改变后都从同一个入口同步归属。 */
    private fun syncCurrentBaby(refresh: Boolean) {
        if (isHidden || !isCurrentStatisticsTarget()) return
        if (ensureBabyOrNavigate()) {
            val babyId = mainVm.getCurrentBabyInfo()?.babyId ?: return
            if (vm.currentBabyId.value != babyId) {
                vm.setBabyId(babyId)
            } else if (refresh) {
                vm.refreshData()
            }
        }
        renderStatistics(vm.uiState.value)
    }

    /**
     * 观察数据变化
     */
    private fun observeData() {
        launchInLifecycle {
            // 等待真实宝宝快照；同一页面内发生宝宝切换时也不能继续显示旧对象的数据。
            combine(mainVm.currentBaby, mainVm.babyDataLoaded, mainVm.navTarget) { _, _, _ -> Unit }
                .collect { syncCurrentBaby(refresh = false) }
        }
        launchInLifecycle {
            vm.uiState.collect(::renderStatistics)
        }
    }

    private fun renderStatistics(state: StatisticsUiState) {
        renderPageState(state)
        if (!mainVm.babyDataLoaded.value || state.isLoading || state.errorMessage != null) return
        val clockChanged = renderedState?.clockContext != state.clockContext
        calendarAdapter.setDatesWithRecords(state.datesWithRecords)
        if (clockChanged) calendarAdapter.refreshClock()
        calendarAdapter.syncSelectedDate(state.selectedDate)
        calendarAdapter.syncBrowseState(state.calendarBrowse)
        // 月份记录点和加载状态不能使所有时间轴重新绑定，浏览位置也不能因此回到选中日。
        if (clockChanged || renderedState?.dayRecord != state.dayRecord) {
            babyAgeAdapter.bindDayRecord(state.dayRecord)
            summaryAdapter.updateSummary(state.dayRecord.summaryMetrics)
            updateTimelineUI(state.dayRecord.timelineItems)
        }
        if (clockChanged || renderedState?.insights != state.insights) {
            trendAdapter.updateTrend(state.insights.trend, state.selectedDate)
            structureAdapter.updateStructure(state.insights.structure, state.selectedDate)
            growthAdapter.updateTrend(state.insights.growth, state.selectedDate)
            growthPercentileAdapter.updatePercentile(state.insights.percentile, state.selectedDate)
            healthAdapter.updateHealth(state.insights.health)
        }
        renderedState = state
    }

    /** 页面级错误优先于加载态；内容恢复后再显示列表，避免旧数据伪装成最新结果。 */
    private fun renderPageState(state: StatisticsUiState) {
        when {
            !mainVm.babyDataLoaded.value -> {
                binding.rvStatistics.visibility = View.GONE
                binding.pageState.showLoading(com.zero.common.R.string.loading)
            }
            state.errorMessage != null -> {
                binding.rvStatistics.visibility = View.GONE
                binding.pageState.showMessage(
                    message = state.errorMessage,
                    actionText = getString(com.zero.common.R.string.retry),
                    onAction = vm::refreshData
                )
            }
            state.isLoading -> {
                binding.rvStatistics.visibility = View.GONE
                binding.pageState.showLoading(com.zero.common.R.string.loading)
            }
            else -> {
                binding.pageState.hide()
                binding.rvStatistics.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        // ConcatAdapter 的日历 holder 带有回调和 View 引用，View 销毁时同步断开列表连接。
        binding.rvStatistics.adapter = null
        renderedState = null
        super.onDestroyView()
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
