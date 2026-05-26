package com.zero.babycare.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.blankj.utilcode.util.LogUtils
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentDashboardBinding
import com.zero.babycare.home.bean.DashboardData
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.navigation.NavTarget
import com.zero.common.ext.launchInLifecycle
import com.zero.common.util.DateUtils
import com.zero.common.util.DeviceUtils
import com.zero.components.base.BaseFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

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

    private val quickActionAdapter by lazy {
        DashboardQuickActionAdapter { action ->
            mainVm.navigateTo(action.target)
        }
    }

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)

        setupDashboardToolbar()

        // 底部快捷操作
        setupQuickActions()

        // 状态卡点击
        binding.cardStatus.setOnClickListener {
            mainVm.navigateTo(NavTarget.Statistics(returnTarget = NavTarget.Dashboard))
        }

        // 卡片点击
        binding.cardFeeding.setOnClickListener {
            mainVm.navigateTo(NavTarget.FeedingRecord())
        }
        binding.cardSleep.setOnClickListener {
            mainVm.navigateTo(NavTarget.SleepRecord())
        }

        // 快速记录区域点击
        setupQuickRecordClickListeners()

        // 状态卡片的结束按钮点击
        binding.tvStatusAction.setOnClickListener {
            handleEndOngoingRecord()
        }
    }

    override fun initData(view: View, savedInstanceState: Bundle?) {
        super.initData(view, savedInstanceState)

        launchInLifecycle {
            mainVm.currentBaby.collect { baby ->
                setupDashboardToolbar(baby?.name)
                vm.setCurrentBaby(baby)
            }
        }

        launchInLifecycle {
            vm.dashboardUiState.collect { state ->
                renderDashboardState(state)
            }
        }

        vm.onVisible()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        LogUtils.d("DashboardFragment onHiddenChanged: $hidden")
        if (!hidden) {
            setupDashboardToolbar()
            vm.onVisible()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) {
            vm.onVisible()
        }
    }

    private fun setupDashboardToolbar(babyName: String? = mainVm.currentBaby.value?.name) {
        val identityTitle = babyName
            ?.takeIf { it.isNotBlank() }
            ?: getString(com.zero.common.R.string.no_baby_yet)

        // 首页头部表达“当前宝宝”而不是页面标题；点击身份区打开侧边栏，承接宝宝与设置入口。
        binding.toolbar.showIdentityTitle(identityTitle) {
            (activity as? MainActivity)?.openDrawer()
        }
        binding.toolbar.hideAction()
    }

    private fun renderDashboardState(state: DashboardUiState) {
        when (state) {
            DashboardUiState.Loading -> Unit
            DashboardUiState.NoBaby -> setupDashboardToolbar(null)
            is DashboardUiState.Error -> Unit
            is DashboardUiState.Content -> {
                setupDashboardToolbar(state.babyName)
                updateUI(state.data, state.nowMillis)
            }
        }
    }

    private fun setupQuickActions() {
        val actions = buildQuickActions()
        binding.rvQuickActions.apply {
            visibility = View.GONE
            adapter = quickActionAdapter
            layoutManager = GridLayoutManager(context, DeviceUtils.getQuickActionColumns(requireContext()))
            setHasFixedSize(true)
        }
        quickActionAdapter.submitList(actions)
    }

    private fun buildQuickActions(): List<DashboardQuickAction> {
        return listOf(
            DashboardQuickAction(
                iconResId = com.zero.common.R.drawable.ic_feeding,
                labelResId = com.zero.common.R.string.feeding,
                color = DashboardQuickActionColor.Res(com.zero.common.R.color.feeding_primary),
                target = NavTarget.FeedingRecord()
            ),
            DashboardQuickAction(
                iconResId = com.zero.common.R.drawable.ic_sleep,
                labelResId = com.zero.common.R.string.sleeping,
                color = DashboardQuickActionColor.Res(com.zero.common.R.color.sleep_primary),
                target = NavTarget.SleepRecord()
            ),
            DashboardQuickAction(
                iconResId = com.zero.common.R.drawable.ic_statistics,
                labelResId = com.zero.common.R.string.data_statistics,
                color = DashboardQuickActionColor.Res(com.zero.common.R.color.statistics_primary),
                target = NavTarget.Statistics()
            ),
            DashboardQuickAction(
                iconResId = com.zero.common.R.drawable.ic_event_other,
                labelResId = com.zero.common.R.string.more_events,
                color = DashboardQuickActionColor.Res(com.zero.common.R.color.event_other),
                target = NavTarget.EventRecord()
            )
        )
    }
    
    /**
     * 更新 UI
     */
    private fun updateUI(data: DashboardData, now: Long) {
        // ==================== 状态卡片 ====================
        updateStatusCard(data, now)

        // ==================== 喂养卡片 ====================
        // 距上次时间（实时计算）
        data.lastFeedingEndTime?.let { endTime ->
            val minutes = TimeUnit.MILLISECONDS.toMinutes(now - endTime)
            binding.tvFeedingSinceTime.text = formatMinutesCompact(minutes)
        } ?: run {
            binding.tvFeedingSinceTime.text = "--"
        }
        binding.tvFeedingTodayCount.text = getString(
            com.zero.common.R.string.today_count,
            data.feedingCount
        )
        binding.tvFeedingTodayDuration.text = getString(
            com.zero.common.R.string.today_duration,
            formatMinutesToReadable(getFeedingTotalMinutesForDisplay(data, now))
        )

        // ==================== 睡眠卡片 ====================
        // 距上次时间（实时计算）
        data.lastSleepEndTime?.let { endTime ->
            val minutes = TimeUnit.MILLISECONDS.toMinutes(now - endTime)
            binding.tvSleepSinceTime.text = formatMinutesCompact(minutes)
        } ?: run {
            binding.tvSleepSinceTime.text = "--"
        }
        binding.tvSleepTodayCount.text = getString(
            com.zero.common.R.string.today_count,
            data.sleepCount
        )
        binding.tvSleepTodayDuration.text = getString(
            com.zero.common.R.string.today_duration,
            formatMinutesToReadable(getSleepTotalMinutesForDisplay(data, now))
        )

        // ==================== 预测卡片 ====================
        updatePredictionCard(data)
    }

    private fun updateStatusCard(data: DashboardData, now: Long) {
        when (data.currentStatus) {
            DashboardData.BabyStatus.AWAKE -> {
                binding.cardStatus.setBackgroundResource(com.zero.common.R.drawable.bg_status_card_awake)
                binding.ivStatusIcon.setImageResource(com.zero.common.R.drawable.ic_baby_awake)
                binding.tvStatusTitle.text = getString(com.zero.common.R.string.baby_awake)
                binding.tvStatusAction.visibility = View.GONE
                
                // 醒着时长（实时计算）
                data.lastSleepEndTime?.let { endTime ->
                    val awakeTime = getSafeElapsedMillis(endTime, now)
                    binding.tvStatusTime.text = getString(
                        com.zero.common.R.string.awake_duration,
                        formatDuration(awakeTime)
                    )
                } ?: run {
                    // 无睡眠记录时显示默认提示
                    binding.tvStatusTime.text = getString(com.zero.common.R.string.no_sleep_record)
                }
            }
            DashboardData.BabyStatus.SLEEPING -> {
                binding.cardStatus.setBackgroundResource(com.zero.common.R.drawable.bg_status_card_sleeping)
                binding.ivStatusIcon.setImageResource(com.zero.common.R.drawable.ic_baby_sleeping)
                binding.tvStatusTitle.text = getString(com.zero.common.R.string.baby_sleeping)
                binding.tvStatusAction.visibility = View.VISIBLE
                binding.tvStatusAction.text = getString(com.zero.common.R.string.end_record)
                
                data.ongoingSleepStart?.let { startTime ->
                    val duration = getSafeElapsedMillis(startTime, now)
                    binding.tvStatusTime.text = getString(
                        com.zero.common.R.string.sleeping_duration,
                        formatDuration(duration)
                    )
                }
            }
            DashboardData.BabyStatus.FEEDING -> {
                binding.cardStatus.setBackgroundResource(com.zero.common.R.drawable.bg_status_card_feeding)
                binding.ivStatusIcon.setImageResource(com.zero.common.R.drawable.ic_baby_feeding)
                binding.tvStatusTitle.text = getString(com.zero.common.R.string.baby_feeding)
                binding.tvStatusAction.visibility = View.VISIBLE
                binding.tvStatusAction.text = getString(com.zero.common.R.string.end_record)
                
                data.ongoingFeedingStart?.let { startTime ->
                    val duration = getSafeElapsedMillis(startTime, now)
                    binding.tvStatusTime.text = getString(
                        com.zero.common.R.string.feeding_duration,
                        formatDuration(duration)
                    )
                }
            }
        }
    }

    private fun updatePredictionCard(data: DashboardData) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        // 喂养预测
        val feedingPrediction = data.feedingPrediction
        if (feedingPrediction != null && !feedingPrediction.isExpired()) {
            // 显示时间区间
            binding.tvPredictFeedingTime.text = getString(
                com.zero.common.R.string.predict_feeding_with_range,
                timeFormat.format(feedingPrediction.earliestTime),
                timeFormat.format(feedingPrediction.latestTime)
            )
            // 剩余时间
            val remainMinutes = TimeUnit.MILLISECONDS.toMinutes(
                feedingPrediction.getRemainingMillis()
            )
            binding.tvPredictFeedingRemain.text = getString(
                com.zero.common.R.string.remain_time,
                formatMinutesToReadable(remainMinutes)
            )
            // 更新置信度颜色
            updatePredictionConfidenceColor(binding.tvPredictFeedingTime, feedingPrediction.confidence)
        } else {
            binding.tvPredictFeedingTime.text = getString(com.zero.common.R.string.cannotPredict)
            binding.tvPredictFeedingRemain.text = ""
        }

        // 睡眠预测
        val sleepPrediction = data.sleepPrediction
        if (sleepPrediction != null && !sleepPrediction.isExpired()) {
            binding.tvPredictSleepTime.text = getString(
                com.zero.common.R.string.predict_sleep_with_range,
                timeFormat.format(sleepPrediction.earliestTime),
                timeFormat.format(sleepPrediction.latestTime)
            )
            val remainMinutes = TimeUnit.MILLISECONDS.toMinutes(
                sleepPrediction.getRemainingMillis()
            )
            binding.tvPredictSleepRemain.text = getString(
                com.zero.common.R.string.remain_time,
                formatMinutesToReadable(remainMinutes)
            )
            updatePredictionConfidenceColor(binding.tvPredictSleepTime, sleepPrediction.confidence)
        } else {
            binding.tvPredictSleepTime.text = getString(com.zero.common.R.string.cannotPredict)
            binding.tvPredictSleepRemain.text = ""
        }
    }
    
    /**
     * 根据置信度更新文字透明度
     * 置信度高时文字更实，置信度低时文字更虚
     */
    private fun updatePredictionConfidenceColor(textView: android.widget.TextView, confidence: Float) {
        val alpha = (0.5f + confidence * 0.5f).coerceIn(0.5f, 1.0f)
        textView.alpha = alpha
    }

    /**
     * 格式化分钟数为可读字符串
     */
    private fun formatMinutesToReadable(minutes: Long?): String {
        if (minutes == null || minutes < 0) return "--"
        val hours = minutes / 60
        val mins = minutes % 60
        return if (hours > 0) {
            getString(com.zero.common.R.string.hour_min_format, hours.toInt(), mins.toInt())
        } else {
            getString(com.zero.common.R.string.min_format, mins.toInt())
        }
    }

    /**
     * 首页概览卡空间较窄，英文使用 22H 37m 这类短格式，避免大号时长换行后显得拥挤。
     */
    private fun formatMinutesCompact(minutes: Long?): String {
        if (minutes == null || minutes < 0) return "--"
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours > 0 && mins > 0 -> getString(
                com.zero.common.R.string.summary_duration_hours_minutes_compact,
                hours.toInt(),
                mins.toInt()
            )
            hours > 0 -> getString(
                com.zero.common.R.string.summary_duration_hours_compact,
                hours.toInt()
            )
            mins > 0 -> getString(
                com.zero.common.R.string.summary_duration_minutes_compact,
                mins.toInt()
            )
            else -> getString(com.zero.common.R.string.summary_duration_zero_compact)
        }
    }

    private fun getFeedingTotalMinutesForDisplay(data: DashboardData, now: Long): Long {
        if (data.currentStatus != DashboardData.BabyStatus.FEEDING || data.ongoingFeedingStart == null) {
            return data.totalFeedingMinutes
        }

        // 进行中的记录尚未落库，首页展示时只补当天部分，避免跨天计入今日统计。
        val effectiveStart = maxOf(data.ongoingFeedingStart, getStartOfTodayMillis(now))
        val ongoingMinutes = TimeUnit.MILLISECONDS.toMinutes(getSafeElapsedMillis(effectiveStart, now))
        return data.totalFeedingMinutes + ongoingMinutes
    }

    private fun getSleepTotalMinutesForDisplay(data: DashboardData, now: Long): Long {
        if (data.currentStatus != DashboardData.BabyStatus.SLEEPING || data.ongoingSleepStart == null) {
            return data.totalSleepMinutes
        }

        // 进行中的记录尚未落库，首页展示时只补当天部分，避免跨天计入今日统计。
        val effectiveStart = maxOf(data.ongoingSleepStart, getStartOfTodayMillis(now))
        val ongoingMinutes = TimeUnit.MILLISECONDS.toMinutes(getSafeElapsedMillis(effectiveStart, now))
        return data.totalSleepMinutes + ongoingMinutes
    }

    /**
     * 计算从指定开始时间到现在的时长，避免时间回拨导致负值
     */
    private fun getSafeElapsedMillis(startTime: Long, now: Long): Long {
        return (now - startTime).coerceAtLeast(0)
    }

    /**
     * 获取当天零点时间戳，用于跨天统计截断
     */
    private fun getStartOfTodayMillis(now: Long): Long {
        return DateUtils.getDayRange(Date(now)).first
    }

    /**
     * 格式化毫秒时长为 HH:MM:SS
     */
    private fun formatDuration(durationMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    /**
     * 设置快速记录区域点击事件
     */
    private fun setupQuickRecordClickListeners() {
        val adapter = QuickRecordAdapter { item ->
            mainVm.navigateTo(NavTarget.EventRecord(item.categoryId))
        }
        val columns = DeviceUtils.getQuickRecordColumns(requireContext())
        binding.rvQuickRecord.layoutManager = GridLayoutManager(requireContext(), columns)
        binding.rvQuickRecord.adapter = adapter
        adapter.submitList(QuickRecordItem.getQuickRecordItems())
    }

    /**
     * 处理结束进行中的记录
     */
    private fun handleEndOngoingRecord() {
        val babyId = mainVm.currentBaby.value?.babyId ?: return
        val currentStatus = OngoingRecordManager.getCurrentStatus(babyId)

        when (currentStatus) {
            OngoingRecordManager.OngoingStatus.SLEEPING -> {
                // 跳转到睡眠记录页面完成记录
                mainVm.navigateTo(NavTarget.SleepRecord())
            }
            OngoingRecordManager.OngoingStatus.FEEDING -> {
                // 跳转到喂养记录页面完成记录
                mainVm.navigateTo(NavTarget.FeedingRecord())
            }
            else -> { /* 无进行中记录 */ }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }
}
