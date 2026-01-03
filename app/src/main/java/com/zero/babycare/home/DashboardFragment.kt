package com.zero.babycare.home

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.StringUtils
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentDashboardBinding
import com.zero.babycare.home.bean.DashboardData
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.navigation.NavTarget
import com.zero.common.ext.launchInLifecycle
import com.zero.components.base.BaseFragment
import com.zero.components.base.vm.UiState
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

    private val handler = Handler(Looper.getMainLooper())
    private var secondTimerRunnable: Runnable? = null   // 每秒更新（状态卡片）
    private var minuteTimerRunnable: Runnable? = null   // 每分钟更新（距上次时间）

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)

        // 设置标题：显示宝宝名称
        updateToolbarTitle()

        // 左侧菜单按钮 - 打开侧边栏
        binding.toolbar.showMenuButton {
            (activity as? MainActivity)?.openDrawer()
        }

        // 隐藏右侧按钮
        binding.toolbar.hideAction()

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
            vm.dashboardData.collect { data ->
                data?.let { updateUI(it) }
            }
        }

        // 首次进入时加载数据
        refreshData()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        LogUtils.d("DashboardFragment onHiddenChanged: $hidden")
        if (!hidden) {
            updateToolbarTitle()
            refreshData()
            startTimers()
        } else {
            stopTimers()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) {
            refreshData()
            startTimers()
        }
    }

    override fun onPause() {
        super.onPause()
        stopTimers()
    }

    private fun updateToolbarTitle() {
        val baby = mainVm.getCurrentBabyInfo()
        binding.toolbar.title = if (baby != null) {
            StringUtils.getString(com.zero.common.R.string.baby_dashboard_title, baby.name)
        } else {
            StringUtils.getString(com.zero.common.R.string.dashboard)
        }
    }

    private fun refreshData() {
        val baby = mainVm.getCurrentBabyInfo() ?: return
        val ageMonths = calculateBabyAgeMonths(baby.birthDate)
        vm.loadDashboardData(baby.babyId, ageMonths)
    }

    private fun setupQuickActions() {
        val actions = buildQuickActions()
        binding.rvQuickActions.apply {
            visibility = View.GONE
            adapter = quickActionAdapter
            layoutManager = GridLayoutManager(context, actions.size.coerceAtLeast(1))
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
     * 计算宝宝月龄
     */
    private fun calculateBabyAgeMonths(birthDay: Long): Int {
        if (birthDay <= 0) return 0
        val now = System.currentTimeMillis()
        val diffMs = now - birthDay
        val days = TimeUnit.MILLISECONDS.toDays(diffMs)
        return (days / 30).toInt().coerceAtLeast(0)
    }

    /**
     * 开始所有定时器
     */
    private fun startTimers() {
        stopTimers()
        startSecondTimer()
        startMinuteTimer()
    }

    /**
     * 停止所有定时器
     */
    private fun stopTimers() {
        secondTimerRunnable?.let { handler.removeCallbacks(it) }
        secondTimerRunnable = null
        minuteTimerRunnable?.let { handler.removeCallbacks(it) }
        minuteTimerRunnable = null
    }

    /**
     * 每秒更新（状态卡片实时时间）
     */
    private fun startSecondTimer() {
        secondTimerRunnable = object : Runnable {
            override fun run() {
                updateStatusCardTime()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(secondTimerRunnable!!)
    }

    /**
     * 每分钟更新（距上次时间、预测剩余时间、进行中记录的今日统计）
     */
    private fun startMinuteTimer() {
        minuteTimerRunnable = object : Runnable {
            override fun run() {
                updateSinceLastTime()
                updatePredictionRemainTime()
                updateOngoingTodayStats()
                handler.postDelayed(this, 60_000)
            }
        }
        handler.post(minuteTimerRunnable!!)
    }

    /**
     * 更新状态卡片的实时时间（每秒调用）
     */
    private fun updateStatusCardTime() {
        val data = vm.dashboardData.value ?: return
        
        when (data.currentStatus) {
            DashboardData.BabyStatus.SLEEPING -> {
                data.ongoingSleepStart?.let { startTime ->
                    val duration = System.currentTimeMillis() - startTime
                    binding.tvStatusTime.text = StringUtils.getString(
                        com.zero.common.R.string.sleeping_duration,
                        formatDuration(duration)
                    )
                }
            }
            DashboardData.BabyStatus.FEEDING -> {
                data.ongoingFeedingStart?.let { startTime ->
                    val duration = System.currentTimeMillis() - startTime
                    binding.tvStatusTime.text = StringUtils.getString(
                        com.zero.common.R.string.feeding_duration,
                        formatDuration(duration)
                    )
                }
            }
            DashboardData.BabyStatus.AWAKE -> {
                // 醒着的情况下显示距上次睡觉的时间（实时计算）
                data.lastSleepEndTime?.let { endTime ->
                    val awakeTime = System.currentTimeMillis() - endTime
                    binding.tvStatusTime.text = StringUtils.getString(
                        com.zero.common.R.string.awake_duration,
                        formatDuration(awakeTime)
                    )
                }
            }
        }
    }

    /**
     * 更新"距上次"时间（每分钟调用）
     */
    private fun updateSinceLastTime() {
        val data = vm.dashboardData.value ?: return
        val now = System.currentTimeMillis()

        // 距上次喂奶
        data.lastFeedingEndTime?.let { endTime ->
            val minutes = TimeUnit.MILLISECONDS.toMinutes(now - endTime)
            binding.tvFeedingSinceTime.text = formatMinutesToReadable(minutes)
        }

        // 距上次睡觉
        data.lastSleepEndTime?.let { endTime ->
            val minutes = TimeUnit.MILLISECONDS.toMinutes(now - endTime)
            binding.tvSleepSinceTime.text = formatMinutesToReadable(minutes)
        }
    }

    /**
     * 更新预测剩余时间（每分钟调用）
     */
    private fun updatePredictionRemainTime() {
        val data = vm.dashboardData.value ?: return

        // 喂养预测剩余时间
        val feedingPrediction = data.feedingPrediction
        if (feedingPrediction != null && !feedingPrediction.isExpired()) {
            val remainMinutes = TimeUnit.MILLISECONDS.toMinutes(
                feedingPrediction.getRemainingMillis()
            )
            binding.tvPredictFeedingRemain.text = StringUtils.getString(
                com.zero.common.R.string.remain_time,
                formatMinutesToReadable(remainMinutes)
            )
        } else if (feedingPrediction != null && feedingPrediction.isExpired()) {
            // 预测已过期，刷新数据重新预测
            binding.tvPredictFeedingRemain.text = ""
            refreshData()
        }

        // 睡眠预测剩余时间
        val sleepPrediction = data.sleepPrediction
        if (sleepPrediction != null && !sleepPrediction.isExpired()) {
            val remainMinutes = TimeUnit.MILLISECONDS.toMinutes(
                sleepPrediction.getRemainingMillis()
            )
            binding.tvPredictSleepRemain.text = StringUtils.getString(
                com.zero.common.R.string.remain_time,
                formatMinutesToReadable(remainMinutes)
            )
        } else if (sleepPrediction != null && sleepPrediction.isExpired()) {
            // 预测已过期，刷新数据重新预测
            binding.tvPredictSleepRemain.text = ""
            refreshData()
        }
    }

    /**
     * 更新进行中记录的今日统计时长（每分钟调用）
     */
    private fun updateOngoingTodayStats() {
        val data = vm.dashboardData.value ?: return

        // 如果正在喂养，更新今日喂养总时长
        if (data.currentStatus == DashboardData.BabyStatus.FEEDING && data.ongoingFeedingStart != null) {
            val ongoingMinutes = TimeUnit.MILLISECONDS.toMinutes(
                System.currentTimeMillis() - data.ongoingFeedingStart
            )
            val totalMinutes = data.totalFeedingMinutes + ongoingMinutes
            binding.tvFeedingTodayDuration.text = StringUtils.getString(
                com.zero.common.R.string.today_duration,
                formatMinutesToReadable(totalMinutes)
            )
        }

        // 如果正在睡觉，更新今日睡眠总时长
        if (data.currentStatus == DashboardData.BabyStatus.SLEEPING && data.ongoingSleepStart != null) {
            val ongoingMinutes = TimeUnit.MILLISECONDS.toMinutes(
                System.currentTimeMillis() - data.ongoingSleepStart
            )
            val totalMinutes = data.totalSleepMinutes + ongoingMinutes
            binding.tvSleepTodayDuration.text = StringUtils.getString(
                com.zero.common.R.string.today_duration,
                formatMinutesToReadable(totalMinutes)
            )
        }
    }

    /**
     * 更新 UI
     */
    private fun updateUI(data: DashboardData) {
        // ==================== 状态卡片 ====================
        updateStatusCard(data)

        // ==================== 喂养卡片 ====================
        // 距上次时间（实时计算）
        val now = System.currentTimeMillis()
        data.lastFeedingEndTime?.let { endTime ->
            val minutes = TimeUnit.MILLISECONDS.toMinutes(now - endTime)
            binding.tvFeedingSinceTime.text = formatMinutesToReadable(minutes)
        } ?: run {
            binding.tvFeedingSinceTime.text = "--"
        }
        binding.tvFeedingTodayCount.text = StringUtils.getString(
            com.zero.common.R.string.today_count,
            data.feedingCount
        )
        binding.tvFeedingTodayDuration.text = StringUtils.getString(
            com.zero.common.R.string.today_duration,
            formatMinutesToReadable(data.totalFeedingMinutes)
        )

        // ==================== 睡眠卡片 ====================
        // 距上次时间（实时计算）
        data.lastSleepEndTime?.let { endTime ->
            val minutes = TimeUnit.MILLISECONDS.toMinutes(now - endTime)
            binding.tvSleepSinceTime.text = formatMinutesToReadable(minutes)
        } ?: run {
            binding.tvSleepSinceTime.text = "--"
        }
        binding.tvSleepTodayCount.text = StringUtils.getString(
            com.zero.common.R.string.today_count,
            data.sleepCount
        )
        binding.tvSleepTodayDuration.text = StringUtils.getString(
            com.zero.common.R.string.today_duration,
            formatMinutesToReadable(data.totalSleepMinutes)
        )

        // ==================== 预测卡片 ====================
        updatePredictionCard(data)
    }

    private fun updateStatusCard(data: DashboardData) {
        when (data.currentStatus) {
            DashboardData.BabyStatus.AWAKE -> {
                binding.cardStatus.setBackgroundResource(com.zero.common.R.drawable.bg_status_card_awake)
                binding.ivStatusIcon.setImageResource(com.zero.common.R.drawable.ic_baby_awake)
                binding.tvStatusTitle.text = StringUtils.getString(com.zero.common.R.string.baby_awake)
                binding.tvStatusAction.visibility = View.GONE
                
                // 醒着时长（实时计算）
                data.lastSleepEndTime?.let { endTime ->
                    val awakeTime = System.currentTimeMillis() - endTime
                    binding.tvStatusTime.text = StringUtils.getString(
                        com.zero.common.R.string.awake_duration,
                        formatDuration(awakeTime)
                    )
                } ?: run {
                    // 无睡眠记录时显示默认提示
                    binding.tvStatusTime.text = StringUtils.getString(com.zero.common.R.string.no_sleep_record)
                }
            }
            DashboardData.BabyStatus.SLEEPING -> {
                binding.cardStatus.setBackgroundResource(com.zero.common.R.drawable.bg_status_card_sleeping)
                binding.ivStatusIcon.setImageResource(com.zero.common.R.drawable.ic_baby_sleeping)
                binding.tvStatusTitle.text = StringUtils.getString(com.zero.common.R.string.baby_sleeping)
                binding.tvStatusAction.visibility = View.VISIBLE
                binding.tvStatusAction.text = StringUtils.getString(com.zero.common.R.string.end_record)
                
                data.ongoingSleepStart?.let { startTime ->
                    val duration = System.currentTimeMillis() - startTime
                    binding.tvStatusTime.text = StringUtils.getString(
                        com.zero.common.R.string.sleeping_duration,
                        formatDuration(duration)
                    )
                }
            }
            DashboardData.BabyStatus.FEEDING -> {
                binding.cardStatus.setBackgroundResource(com.zero.common.R.drawable.bg_status_card_feeding)
                binding.ivStatusIcon.setImageResource(com.zero.common.R.drawable.ic_baby_feeding)
                binding.tvStatusTitle.text = StringUtils.getString(com.zero.common.R.string.baby_feeding)
                binding.tvStatusAction.visibility = View.VISIBLE
                binding.tvStatusAction.text = StringUtils.getString(com.zero.common.R.string.end_record)
                
                data.ongoingFeedingStart?.let { startTime ->
                    val duration = System.currentTimeMillis() - startTime
                    binding.tvStatusTime.text = StringUtils.getString(
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
            binding.tvPredictFeedingTime.text = StringUtils.getString(
                com.zero.common.R.string.predict_feeding_with_range,
                timeFormat.format(feedingPrediction.earliestTime),
                timeFormat.format(feedingPrediction.latestTime)
            )
            // 剩余时间
            val remainMinutes = TimeUnit.MILLISECONDS.toMinutes(
                feedingPrediction.getRemainingMillis()
            )
            binding.tvPredictFeedingRemain.text = StringUtils.getString(
                com.zero.common.R.string.remain_time,
                formatMinutesToReadable(remainMinutes)
            )
            // 更新置信度颜色
            updatePredictionConfidenceColor(binding.tvPredictFeedingTime, feedingPrediction.confidence)
        } else {
            binding.tvPredictFeedingTime.text = StringUtils.getString(com.zero.common.R.string.cannotPredict)
            binding.tvPredictFeedingRemain.text = ""
        }

        // 睡眠预测
        val sleepPrediction = data.sleepPrediction
        if (sleepPrediction != null && !sleepPrediction.isExpired()) {
            binding.tvPredictSleepTime.text = StringUtils.getString(
                com.zero.common.R.string.predict_sleep_with_range,
                timeFormat.format(sleepPrediction.earliestTime),
                timeFormat.format(sleepPrediction.latestTime)
            )
            val remainMinutes = TimeUnit.MILLISECONDS.toMinutes(
                sleepPrediction.getRemainingMillis()
            )
            binding.tvPredictSleepRemain.text = StringUtils.getString(
                com.zero.common.R.string.remain_time,
                formatMinutesToReadable(remainMinutes)
            )
            updatePredictionConfidenceColor(binding.tvPredictSleepTime, sleepPrediction.confidence)
        } else {
            binding.tvPredictSleepTime.text = StringUtils.getString(com.zero.common.R.string.cannotPredict)
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
            StringUtils.getString(com.zero.common.R.string.hour_min_format, hours.toInt(), mins.toInt())
        } else {
            StringUtils.getString(com.zero.common.R.string.min_format, mins.toInt())
        }
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
        binding.rvQuickRecord.layoutManager = androidx.recyclerview.widget.GridLayoutManager(
            requireContext(), 
            QuickRecordItem.getQuickRecordItems().size
        )
        binding.rvQuickRecord.adapter = adapter
        adapter.submitList(QuickRecordItem.getQuickRecordItems())
    }

    /**
     * 处理结束进行中的记录
     */
    private fun handleEndOngoingRecord() {
        val babyId = mainVm.getCurrentBabyInfo()?.babyId ?: return
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
        stopTimers()
    }
}
