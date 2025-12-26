package com.zero.babycare.home.record.event

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.blankj.utilcode.util.SizeUtils
import com.blankj.utilcode.util.StringUtils
import com.zero.components.widget.GridSpacingItemDecoration
import com.blankj.utilcode.util.ToastUtils
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentEventRecordBinding
import com.zero.babycare.databinding.LayoutEventDetailDiaperBinding
import com.zero.babycare.databinding.LayoutEventDetailGrowthBinding
import com.zero.babycare.databinding.LayoutEventDetailTemperatureBinding
import com.zero.babycare.databinding.LayoutEventDetailMedicineBinding
import com.zero.babycare.databinding.LayoutEventDetailMilestoneBinding
import com.zero.babycare.databinding.LayoutEventDetailActivityBinding
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.entity.DiaperData
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.GrowthData
import com.zero.babydata.entity.MedicineData
import com.zero.babydata.entity.MilestoneData
import com.zero.babydata.entity.TemperatureData
import com.zero.common.R
import com.zero.common.ext.launchInLifecycle
import com.zero.components.base.BaseFragment
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 事件记录页面
 */
class EventRecordFragment : BaseFragment<FragmentEventRecordBinding>() {

    private val vm by viewModels<EventRecordViewModel>()
    private val mainVm by activityViewModels<MainViewModel>()

    private lateinit var categoryAdapter: EventCategoryAdapter
    private lateinit var subtypeAdapter: EventSubtypeAdapter

    private val dateFormat = SimpleDateFormat("M月d日 E", Locale.CHINESE)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)

        setupToolbar()
        setupTimeCard()
        setupCategoryRecyclerView()
        setupSubtypeRecyclerView()
        setupSaveButton()

        // 先处理预选分类，再开始观察，确保初始值正确
        handlePreSelectedCategory()

        observeViewModel()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // Fragment 从隐藏变为显示时，处理预选分类
            handlePreSelectedCategory()
        }
    }

    /**
     * 处理从 Dashboard 传递过来的预选分类
     */
    private fun handlePreSelectedCategory() {
        val navTarget = mainVm.navTarget.value
        if (navTarget is NavTarget.EventRecord && navTarget.preSelectedCategory != null) {
            val category = EventCategory.fromId(navTarget.preSelectedCategory)
            if (category != null) {
                vm.selectCategory(category)
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.title = StringUtils.getString(R.string.event_record)
        binding.toolbar.showBackButton { handleBack() }
        binding.toolbar.hideAction()
    }

    private fun setupTimeCard() {
        updateTimeDisplay(System.currentTimeMillis())

        binding.cardTime.setOnClickListener {
            showDateTimePicker()
        }
    }

    private fun setupCategoryRecyclerView() {
        categoryAdapter = EventCategoryAdapter { category ->
            vm.selectCategory(category)
        }
        binding.rvCategory.adapter = categoryAdapter
    }

    private fun setupSubtypeRecyclerView() {
        subtypeAdapter = EventSubtypeAdapter { subtype ->
            vm.selectSubtype(subtype)
        }
        binding.rvSubtype.layoutManager = GridLayoutManager(requireContext(), SUBTYPE_SPAN_COUNT)
        binding.rvSubtype.addItemDecoration(
            GridSpacingItemDecoration(
                spanCount = SUBTYPE_SPAN_COUNT,
                spacing = SizeUtils.dp2px(8f),
                includeEdge = false
            )
        )
        binding.rvSubtype.adapter = subtypeAdapter
    }

    companion object {
        private const val SUBTYPE_SPAN_COUNT = 4

        fun create(): EventRecordFragment = EventRecordFragment()
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            saveRecord()
        }
    }

    private fun observeViewModel() {
        launchInLifecycle {
            vm.selectedCategory.collect { category ->
                categoryAdapter.setSelectedCategory(category)
                if (category != null) {
                    val subtypes = EventSubtype.getSubtypes(category)
                    subtypeAdapter.submitList(subtypes)
                } else {
                    subtypeAdapter.submitList(emptyList())
                }
            }
        }

        launchInLifecycle {
            vm.selectedSubtype.collect { subtype ->
                subtypeAdapter.setSelectedType(subtype?.type)
                updateDetailView(subtype)
                updateUIVisibility(subtype != null)
            }
        }

        launchInLifecycle {
            vm.eventTime.collect { time ->
                updateTimeDisplay(time)
            }
        }
    }

    private fun updateTimeDisplay(timestamp: Long) {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        binding.tvDate.text = dateFormat.format(calendar.time)
        binding.tvTime.text = timeFormat.format(calendar.time)
    }

    private fun showDateTimePicker() {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = vm.eventTime.value
        }

        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, day)

                TimePickerDialog(
                    requireContext(),
                    { _, hour, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hour)
                        calendar.set(Calendar.MINUTE, minute)
                        vm.setEventTime(calendar.timeInMillis)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateDetailView(subtype: EventSubtype?) {
        binding.containerDetail.removeAllViews()

        if (subtype == null) {
            binding.containerDetail.visibility = View.GONE
            return
        }

        binding.containerDetail.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(requireContext())

        when {
            EventType.isDiaper(subtype.type) -> {
                inflateDiaperDetail(inflater, subtype.type)
            }
            EventType.isGrowth(subtype.type) -> {
                inflateGrowthDetail(inflater, subtype.type)
            }
            subtype.type == EventType.HEALTH_TEMPERATURE -> {
                inflateTemperatureDetail(inflater)
            }
            subtype.type == EventType.HEALTH_MEDICINE -> {
                inflateMedicineDetail(inflater)
            }
            EventType.isMilestone(subtype.type) -> {
                inflateMilestoneDetail(inflater, subtype.type)
            }
            EventType.hasDuration(subtype.type) -> {
                inflateActivityDetail(inflater)
            }
            else -> {
                // 简单事件无详情
                binding.containerDetail.visibility = View.GONE
            }
        }
    }

    private fun inflateDiaperDetail(inflater: LayoutInflater, type: Int) {
        val detailBinding = LayoutEventDetailDiaperBinding.inflate(inflater, binding.containerDetail, true)

        // 根据类型显示/隐藏选项
        val showStool = type == EventType.DIAPER_DIRTY || type == EventType.DIAPER_MIXED
        val showUrine = type == EventType.DIAPER_WET || type == EventType.DIAPER_MIXED

        detailBinding.llStoolConsistency.visibility = if (showStool) View.VISIBLE else View.GONE
        detailBinding.llUrineAmount.visibility = if (showUrine) View.VISIBLE else View.GONE

        // 监听选择变化
        detailBinding.rgStoolConsistency.setOnCheckedChangeListener { _, checkedId ->
            updateDiaperData(detailBinding)
        }
        detailBinding.rgStoolColor.setOnCheckedChangeListener { _, checkedId ->
            updateDiaperData(detailBinding)
        }
        detailBinding.rgUrineAmount.setOnCheckedChangeListener { _, checkedId ->
            updateDiaperData(detailBinding)
        }
    }

    private fun updateDiaperData(detailBinding: LayoutEventDetailDiaperBinding) {
        val consistency = when (detailBinding.rgStoolConsistency.checkedRadioButtonId) {
            com.zero.babycare.R.id.rbWatery -> DiaperData.CONSISTENCY_WATERY
            com.zero.babycare.R.id.rbSoft -> DiaperData.CONSISTENCY_NORMAL
            com.zero.babycare.R.id.rbHard -> DiaperData.CONSISTENCY_HARD
            else -> null
        }
        val color = when (detailBinding.rgStoolColor.checkedRadioButtonId) {
            com.zero.babycare.R.id.rbYellow -> DiaperData.COLOR_YELLOW
            com.zero.babycare.R.id.rbGreen -> DiaperData.COLOR_GREEN
            com.zero.babycare.R.id.rbBrown -> DiaperData.COLOR_BROWN
            com.zero.babycare.R.id.rbBlack -> DiaperData.COLOR_BLACK
            else -> null
        }
        val abnormal = detailBinding.rgUrineAmount.checkedRadioButtonId == com.zero.babycare.R.id.rbMuch

        vm.setExtraData(DiaperData(color = color, consistency = consistency, abnormal = abnormal))
    }

    private fun inflateGrowthDetail(inflater: LayoutInflater, type: Int) {
        val detailBinding = LayoutEventDetailGrowthBinding.inflate(inflater, binding.containerDetail, true)

        // 设置单位
        val unit = when (type) {
            EventType.GROWTH_WEIGHT -> StringUtils.getString(R.string.weight_unit)
            EventType.GROWTH_HEIGHT, EventType.GROWTH_HEAD -> StringUtils.getString(R.string.height_unit)
            else -> ""
        }
        detailBinding.tvUnit.text = unit

        // 监听输入变化
        detailBinding.etValue.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = detailBinding.etValue.text.toString().toDoubleOrNull()
                if (value != null) {
                    vm.setExtraData(GrowthData(value, unit))
                }
            }
        }
    }

    private fun inflateTemperatureDetail(inflater: LayoutInflater) {
        val detailBinding = LayoutEventDetailTemperatureBinding.inflate(inflater, binding.containerDetail, true)

        detailBinding.etTemperature.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = detailBinding.etTemperature.text.toString().toDoubleOrNull()
                if (value != null) {
                    vm.setExtraData(TemperatureData(value))
                    updateTemperatureStatus(detailBinding, value)
                }
            }
        }
    }

    private fun updateTemperatureStatus(detailBinding: LayoutEventDetailTemperatureBinding, temp: Double) {
        val (statusText, statusColor, showWarning) = when {
            temp < 36.0 -> Triple(StringUtils.getString(R.string.temperature_normal), R.color.temp_normal, false)
            temp <= 37.5 -> Triple(StringUtils.getString(R.string.temperature_normal), R.color.temp_normal, false)
            temp <= 38.5 -> Triple(StringUtils.getString(R.string.temperature_low_fever), R.color.temp_warning, true)
            else -> Triple(StringUtils.getString(R.string.temperature_high_fever), R.color.temp_danger, true)
        }

        detailBinding.tvStatus.text = statusText
        detailBinding.tvStatus.setTextColor(resources.getColor(statusColor, null))
        detailBinding.tvWarning.visibility = if (showWarning) View.VISIBLE else View.GONE
        
        if (temp > 38.5) {
            detailBinding.tvWarning.setText(R.string.temperature_danger)
        } else if (temp > 37.5) {
            detailBinding.tvWarning.setText(R.string.temperature_warning)
        }
    }

    private fun inflateMedicineDetail(inflater: LayoutInflater) {
        val detailBinding = LayoutEventDetailMedicineBinding.inflate(inflater, binding.containerDetail, true)

        detailBinding.etMedicineName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val name = detailBinding.etMedicineName.text.toString()
                val dosage = detailBinding.etDosage.text.toString()
                if (name.isNotBlank()) {
                    vm.setExtraData(MedicineData(name, dosage, "ml"))
                }
            }
        }
    }

    private fun inflateMilestoneDetail(inflater: LayoutInflater, type: Int) {
        val detailBinding = LayoutEventDetailMilestoneBinding.inflate(inflater, binding.containerDetail, true)

        // 自定义里程碑显示名称输入
        detailBinding.llCustomName.visibility = if (type == EventType.MILESTONE_CUSTOM) {
            View.VISIBLE
        } else {
            View.GONE
        }

        if (type != EventType.MILESTONE_CUSTOM) {
            // 预设里程碑，直接设置数据
            vm.setExtraData(MilestoneData(isFirst = true))
        }

        detailBinding.etMilestoneName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && type == EventType.MILESTONE_CUSTOM) {
                val name = detailBinding.etMilestoneName.text.toString()
                val description = detailBinding.etDescription.text.toString()
                vm.setExtraData(MilestoneData(name, description, true))
            }
        }

        detailBinding.etDescription.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val name = if (type == EventType.MILESTONE_CUSTOM) {
                    detailBinding.etMilestoneName.text.toString()
                } else null
                val description = detailBinding.etDescription.text.toString()
                vm.setExtraData(MilestoneData(name, description, true))
            }
        }
    }

    private fun inflateActivityDetail(inflater: LayoutInflater) {
        val detailBinding = LayoutEventDetailActivityBinding.inflate(inflater, binding.containerDetail, true)

        // 初始化时间
        val startTime = vm.eventTime.value
        detailBinding.tvStartTime.text = timeFormat.format(startTime)

        detailBinding.tvStartTime.setOnClickListener {
            showTimePicker { hour, minute ->
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = vm.eventTime.value
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                }
                vm.setEventTime(calendar.timeInMillis)
                detailBinding.tvStartTime.text = timeFormat.format(calendar.time)
                updateDuration(detailBinding)
            }
        }

        detailBinding.tvEndTime.setOnClickListener {
            showTimePicker { hour, minute ->
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = vm.eventTime.value
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                }
                // 如果结束时间早于开始时间，设为第二天
                if (calendar.timeInMillis <= vm.eventTime.value) {
                    calendar.add(Calendar.DAY_OF_MONTH, 1)
                }
                vm.setEndTime(calendar.timeInMillis)
                detailBinding.tvEndTime.text = timeFormat.format(calendar.time)
                updateDuration(detailBinding)
            }
        }

        // 计时器
        detailBinding.rvTimer.statusChange = { current, next ->
            when (next) {
                com.zero.components.widget.RecordView.RecordState.RECORDING -> {
                    // 开始计时
                    detailBinding.rvTimer.showState(next)
                }
                com.zero.components.widget.RecordView.RecordState.PAUSE -> {
                    // 暂停时设置结束时间
                    vm.setEndTime(System.currentTimeMillis())
                    detailBinding.tvEndTime.text = timeFormat.format(System.currentTimeMillis())
                    updateDuration(detailBinding)
                }
                else -> {}
            }
        }
    }

    private fun showTimePicker(onTimeSet: (Int, Int) -> Unit) {
        val calendar = Calendar.getInstance()
        TimePickerDialog(
            requireContext(),
            { _, hour, minute -> onTimeSet(hour, minute) },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun updateDuration(detailBinding: LayoutEventDetailActivityBinding) {
        val startTime = vm.eventTime.value
        val endTime = vm.endTime.value ?: return

        val durationMinutes = (endTime - startTime) / 60000
        if (durationMinutes > 0) {
            detailBinding.tvDuration.text = StringUtils.getString(
                R.string.event_duration
            ) + "：${durationMinutes} " + StringUtils.getString(R.string.min_format, durationMinutes.toInt())
            detailBinding.tvDuration.visibility = View.VISIBLE
        }
    }

    private fun updateUIVisibility(hasSubtype: Boolean) {
        binding.tvNoteLabel.visibility = if (hasSubtype) View.VISIBLE else View.GONE
        binding.etNote.visibility = if (hasSubtype) View.VISIBLE else View.GONE
        binding.btnSave.visibility = if (hasSubtype) View.VISIBLE else View.GONE
    }

    private fun saveRecord() {
        val babyId = mainVm.getCurrentBabyInfo()?.babyId
        if (babyId == null) {
            ToastUtils.showShort(R.string.no_baby_selected)
            return
        }

        // 获取备注
        vm.setNote(binding.etNote.text.toString().trim())

        vm.saveRecord(
            babyId = babyId,
            onSuccess = {
                ToastUtils.showShort(R.string.save_success)
                vm.reset()
                mainVm.navigateTo(NavTarget.Dashboard)
            },
            onError = { message ->
                ToastUtils.showShort(message)
            }
        )
    }

    private fun handleBack() {
        mainVm.navigateTo(NavTarget.Dashboard)
    }
}

