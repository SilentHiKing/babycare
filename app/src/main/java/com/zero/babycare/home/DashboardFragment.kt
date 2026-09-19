package com.zero.babycare.home

import android.os.Bundle
import android.text.Layout
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentDashboardBinding
import com.zero.babycare.home.bean.DashboardData
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.home.prediction.PredictionResult
import com.zero.babycare.home.prediction.PredictionSource
import com.zero.babycare.navigation.NavTarget
import com.zero.common.ext.launchInLifecycle
import com.zero.common.util.DeviceUtils
import com.zero.components.base.BaseFragment
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class DashboardFragment : BaseFragment<FragmentDashboardBinding>() {
    companion object {
        fun create(): DashboardFragment {
            return DashboardFragment()
        }
    }

    private val vm by viewModels<DashboardViewModel>()
    private val quickRecordLayoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        // 布局中修改列数可能保留旧子项宽度，等本轮布局结束后再请求一次完整重排。
        view.removeCallbacks(quickRecordGridUpdate)
        view.post(quickRecordGridUpdate)
    }
    private val quickRecordGridUpdate = Runnable {
        // 只查当前 View，不在异步回调中访问可能已销毁的 binding。
        view?.findViewById<RecyclerView>(com.zero.babycare.R.id.rvQuickRecord)?.let(::updateQuickRecordColumns)
    }
    private val predictionLayoutListener = View.OnLayoutChangeListener { target, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left != oldRight - oldLeft) {
            target.removeCallbacks(predictionLayoutUpdate)
            target.post(predictionLayoutUpdate)
        }
    }
    private val predictionLayoutUpdate = Runnable {
        // 与网格重排相同，只操作当前 View；页面销毁后不再通过旧 binding 写入布局。
        view?.let { root ->
            updatePredictionRow(root.findViewById(com.zero.babycare.R.id.rowPredictFeeding))
            updatePredictionRow(root.findViewById(com.zero.babycare.R.id.rowPredictSleep))
        }
    }
    private val mainVm: MainViewModel by lazy {
        activityViewModels<MainViewModel>().value
    }

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)

        setupDashboardToolbar()
        binding.cardPrediction.addOnLayoutChangeListener(predictionLayoutListener)

        // 状态卡点击
        binding.cardStatus.setOnClickListener {
            mainVm.navigateTo(NavTarget.Statistics(returnTarget = NavTarget.Dashboard))
        }
        binding.cardRecords.setOnClickListener {
            mainVm.navigateTo(NavTarget.Statistics(returnTarget = NavTarget.Dashboard))
        }
        // 入口合并朗读标题和说明，保留真实子文字，避免多语言拼接和重复焦点。
        ViewCompat.setScreenReaderFocusable(binding.cardRecords, true)
        ViewCompat.setAccessibilityDelegate(binding.cardRecords, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        })

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
            DashboardUiState.Loading -> {
                showDashboardContent(false)
                binding.pageState.showLoading(com.zero.common.R.string.loading)
            }
            DashboardUiState.NoBaby -> {
                setupDashboardToolbar(null)
                showDashboardContent(false)
                binding.pageState.showMessage(
                    message = getString(com.zero.common.R.string.no_baby_yet),
                    actionText = getString(com.zero.common.R.string.add_baby),
                    onAction = { mainVm.navigateTo(NavTarget.BabyInfo.create()) }
                )
            }
            DashboardUiState.Error -> {
                showDashboardContent(false)
                binding.pageState.showMessage(
                    message = getString(com.zero.common.R.string.data_load_failed),
                    actionText = getString(com.zero.common.R.string.retry),
                    onAction = vm::onVisible
                )
            }
            is DashboardUiState.Content -> {
                setupDashboardToolbar(state.babyName)
                binding.pageState.hide()
                showDashboardContent(true)
                updateUI(state.data, state.nowMillis)
            }
        }
    }

    private fun showDashboardContent(show: Boolean) {
        binding.contentScroll.visibility = if (show) View.VISIBLE else View.GONE
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
            formatDurationMinutesCompact(data.displayFeedingMinutes)
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
            formatDurationMinutesCompact(data.displaySleepMinutes)
        )

        // ==================== 预测卡片 ====================
        updatePredictionCard(data)
    }

    private fun updateStatusCard(data: DashboardData, now: Long) {
        // 计时数字使用强调层级；空态与久未记录的说明恢复正文，避免长文案挤占首页。
        binding.tvStatusTime.setTextAppearance(com.zero.common.R.style.TextAppearance_BabyCare_Display2)
        when (data.currentStatus) {
            DashboardData.BabyStatus.AWAKE -> {
                binding.cardStatus.setBackgroundResource(com.zero.common.R.drawable.bg_status_card_awake)
                binding.ivStatusIcon.setImageResource(com.zero.common.R.drawable.ic_baby_awake)
                binding.tvStatusTitle.text = getString(com.zero.common.R.string.baby_awake)
                binding.tvStatusAction.visibility = View.GONE
                
                // 醒着时长（实时计算）
                data.lastSleepEndTime?.let { endTime ->
                    val awakeTime = getSafeElapsedMillis(endTime, now)
                    if (awakeTime >= TimeUnit.DAYS.toMillis(1)) {
                        // 长时间缺少记录不代表宝宝一直清醒，只展示有记录支持的相对时间。
                        binding.tvStatusTitle.setText(com.zero.common.R.string.dashboard_no_active_record)
                        binding.tvStatusTime.setTextAppearance(com.zero.common.R.style.TextAppearance_BabyCare_Body1)
                        binding.tvStatusTime.text = getString(
                            com.zero.common.R.string.dashboard_last_sleep_record,
                            formatMinutesCompact(TimeUnit.MILLISECONDS.toMinutes(awakeTime))
                        )
                    } else {
                        binding.tvStatusTime.text = getString(
                            com.zero.common.R.string.awake_duration,
                            formatDuration(awakeTime)
                        )
                    }
                } ?: run {
                    // 无睡眠记录时显示默认提示
                    binding.tvStatusTitle.setText(com.zero.common.R.string.dashboard_no_active_record)
                    binding.tvStatusTime.setTextAppearance(com.zero.common.R.style.TextAppearance_BabyCare_Body1)
                    binding.tvStatusTime.text = getString(com.zero.common.R.string.no_sleep_record)
                }
            }
            DashboardData.BabyStatus.SLEEPING -> {
                binding.cardStatus.setBackgroundResource(com.zero.common.R.drawable.bg_status_card_sleeping)
                binding.ivStatusIcon.setImageResource(com.zero.common.R.drawable.ic_baby_sleeping)
                binding.tvStatusTitle.text = getString(com.zero.common.R.string.baby_sleeping)
                binding.tvStatusAction.visibility = View.VISIBLE
                binding.tvStatusAction.text = getString(com.zero.common.R.string.end_record)
                
                data.ongoingSleepDurationMillis?.let { duration ->
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
                
                data.ongoingFeedingDurationMillis?.let { duration ->
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
        if (data.currentStatus != DashboardData.BabyStatus.FEEDING &&
            feedingPrediction != null && !feedingPrediction.isExpired()) {
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
                formatDurationMinutesCompact(remainMinutes)
            )
            updatePredictionEvidence(binding.tvPredictFeedingEvidence, feedingPrediction)
        } else {
            binding.tvPredictFeedingTime.setText(com.zero.common.R.string.prediction_feeding_empty)
            binding.tvPredictFeedingRemain.text = ""
            binding.tvPredictFeedingEvidence.visibility = View.VISIBLE
            binding.tvPredictFeedingEvidence.setText(
                if (data.currentStatus == DashboardData.BabyStatus.FEEDING)
                    com.zero.common.R.string.prediction_in_progress_hint
                else com.zero.common.R.string.prediction_feeding_empty_hint
            )
        }

        // 睡眠预测
        val sleepPrediction = data.sleepPrediction
        if (data.currentStatus != DashboardData.BabyStatus.SLEEPING &&
            sleepPrediction != null && !sleepPrediction.isExpired()) {
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
                formatDurationMinutesCompact(remainMinutes)
            )
            updatePredictionEvidence(binding.tvPredictSleepEvidence, sleepPrediction)
        } else {
            binding.tvPredictSleepTime.setText(com.zero.common.R.string.prediction_sleep_empty)
            binding.tvPredictSleepRemain.text = ""
            binding.tvPredictSleepEvidence.visibility = View.VISIBLE
            binding.tvPredictSleepEvidence.setText(
                if (data.currentStatus == DashboardData.BabyStatus.SLEEPING)
                    com.zero.common.R.string.prediction_in_progress_hint
                else com.zero.common.R.string.prediction_sleep_empty_hint
            )
        }
        // 空态不占一个剩余时间列；有效预测完整保留文案，是否同行仅由实际字宽决定。
        binding.tvPredictFeedingRemain.visibility = if (binding.tvPredictFeedingRemain.text.isEmpty()) View.GONE else View.VISIBLE
        binding.tvPredictSleepRemain.visibility = if (binding.tvPredictSleepRemain.text.isEmpty()) View.GONE else View.VISIBLE
        binding.cardPrediction.removeCallbacks(predictionLayoutUpdate)
        binding.cardPrediction.post(predictionLayoutUpdate)
    }

    /** 根据完整文字宽度重排预测行，避免长英文或大字号让主列被剩余时间挤成零宽。 */
    private fun updatePredictionRow(row: LinearLayout) {
        val available = row.width - row.paddingLeft - row.paddingRight
        if (available <= 0) return
        val title = row.getChildAt(0) as TextView
        val remaining = row.getChildAt(1) as TextView
        val gap = resources.getDimensionPixelSize(com.zero.common.R.dimen.content_gap_medium)
        val stackedGap = resources.getDimensionPixelSize(com.zero.common.R.dimen.content_gap_small)
        fun desiredWidth(text: TextView) = kotlin.math.ceil(Layout.getDesiredWidth(text.text, text.paint).toDouble()).toInt() +
            text.compoundPaddingLeft + text.compoundPaddingRight
        val horizontal = remaining.visibility == View.GONE || desiredWidth(title) + desiredWidth(remaining) + gap <= available
        val orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val titleWidth = if (horizontal) 0 else LinearLayout.LayoutParams.MATCH_PARENT
        val remainingWidth = if (horizontal) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT
        val topMargin = if (horizontal) 0 else stackedGap
        val startMargin = if (horizontal) gap else 0
        val titleParams = title.layoutParams as LinearLayout.LayoutParams
        val remainingParams = remaining.layoutParams as LinearLayout.LayoutParams
        if (row.orientation == orientation && titleParams.width == titleWidth &&
            remainingParams.width == remainingWidth && remainingParams.topMargin == topMargin &&
            remainingParams.marginStart == startMargin) return
        row.orientation = orientation
        title.layoutParams = LinearLayout.LayoutParams(titleParams).apply {
            width = titleWidth
            weight = if (horizontal) 1f else 0f
        }
        remaining.layoutParams = LinearLayout.LayoutParams(remainingParams).apply {
            width = remainingWidth
            weight = 0f
            this.topMargin = topMargin
            marginStart = startMargin
        }
    }

    /** 预测依据使用明确文字表达，不再让家长通过透明度猜测可信程度。 */
    private fun updatePredictionEvidence(textView: android.widget.TextView, result: PredictionResult) {
        textView.visibility = View.VISIBLE
        textView.text = when {
            result.source == PredictionSource.POPULATION_STATS || result.evidenceCount <= 0 -> {
                getString(com.zero.common.R.string.prediction_evidence_default)
            }
            result.confidence < 0.6f -> {
                getString(
                    com.zero.common.R.string.prediction_evidence_weak_samples,
                    result.evidenceCount
                )
            }
            else -> {
                getString(
                    com.zero.common.R.string.prediction_evidence_samples,
                    result.evidenceCount
                )
            }
        }
    }

    /**
     * 首页概览卡空间较窄，英文使用 22H 37m 这类短格式，避免大号时长换行后显得拥挤。
     */
    private fun formatMinutesCompact(minutes: Long?): String {
        if (minutes == null || minutes < 0) return "--"
        // 久未记录时改用天数，避免四位小时数撑开并排概览卡。
        if (minutes >= TimeUnit.DAYS.toMinutes(1)) {
            return getString(com.zero.common.R.string.summary_duration_days_compact, minutes / 1440)
        }
        return formatDurationMinutesCompact(minutes)
    }

    /** 总时长与预测剩余时间保留小时、分钟精度，不能套用“距上次”的整天简写。 */
    private fun formatDurationMinutesCompact(minutes: Long?): String {
        if (minutes == null || minutes < 0) return "--"
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours > 0 && mins > 0 -> getString(
                com.zero.common.R.string.summary_duration_hours_minutes_compact,
                hours,
                mins
            )
            hours > 0 -> getString(
                com.zero.common.R.string.summary_duration_hours_compact,
                hours
            )
            mins > 0 -> getString(
                com.zero.common.R.string.summary_duration_minutes_compact,
                mins
            )
            else -> getString(com.zero.common.R.string.summary_duration_zero_compact)
        }
    }

    /**
     * 计算从指定开始时间到现在的时长，避免时间回拨导致负值
     */
    private fun getSafeElapsedMillis(startTime: Long, now: Long): Long {
        return (now - startTime).coerceAtLeast(0)
    }

    /**
     * 格式化毫秒时长为 HH:MM:SS
     */
    private fun formatDuration(durationMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
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
        binding.rvQuickRecord.addOnLayoutChangeListener(quickRecordLayoutListener)
        adapter.submitList(QuickRecordItem.getQuickRecordItems())
    }

    private fun updateQuickRecordColumns(list: RecyclerView) {
        val manager = list.layoutManager as? GridLayoutManager ?: return
        // 以卡片内列表宽度计算，平板的 maxWidth 和分屏变化都不能被整屏宽度覆盖。
        val columns = DeviceUtils.getQuickRecordColumns(
            list.context, list.width - list.paddingLeft - list.paddingRight
        )
        if (manager.spanCount != columns) manager.spanCount = columns
    }

    override fun onDestroyView() {
        binding.cardPrediction.removeOnLayoutChangeListener(predictionLayoutListener)
        binding.cardPrediction.removeCallbacks(predictionLayoutUpdate)
        binding.rvQuickRecord.removeOnLayoutChangeListener(quickRecordLayoutListener)
        binding.rvQuickRecord.removeCallbacks(quickRecordGridUpdate)
        binding.rvQuickRecord.adapter = null
        super.onDestroyView()
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

}
