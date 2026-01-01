package com.zero.babycare.home.record.event

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
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
import com.zero.babycare.databinding.LayoutEventDetailCustomBinding
import com.zero.babycare.databinding.LayoutEventDetailSymptomBinding
import com.zero.babycare.databinding.LayoutEventDetailVaccineBinding
import com.zero.babycare.home.record.RecordTimerController
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.navigation.BackPressHandler
import com.zero.babydata.entity.CustomEventData
import com.zero.babydata.entity.DiaperData
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.GrowthData
import com.zero.babydata.entity.MedicineData
import com.zero.babydata.entity.MilestoneData
import com.zero.babydata.entity.SymptomData
import com.zero.babydata.entity.TemperatureData
import com.zero.babydata.entity.VaccineData
import com.zero.common.R
import com.zero.common.ext.launchInLifecycle
import com.zero.common.util.UnitConfig
import com.zero.common.util.UnitConverter
import com.zero.components.base.BaseFragment
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 事件记录页面
 */
class EventRecordFragment : BaseFragment<FragmentEventRecordBinding>(), BackPressHandler {

    private val vm by viewModels<EventRecordViewModel>()
    private val mainVm by activityViewModels<MainViewModel>()

    private lateinit var categoryAdapter: EventCategoryAdapter
    private lateinit var subtypeAdapter: EventSubtypeAdapter

    private var editRecordId: Int? = null
    private var editingRecord: com.zero.babydata.entity.EventRecord? = null
    private var isEditMode = false
    private var isCategoryLocked = false
    private var pendingExtraData: com.zero.babydata.entity.EventExtraData? = null
    private var activityTimerController: RecordTimerController? = null

    private val dateFormat = SimpleDateFormat("M月d日 E", Locale.CHINESE)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)

        resolveEditMode()

        setupToolbar()
        setupTimeCard()
        setupCategoryRecyclerView()
        setupSubtypeRecyclerView()
        setupSaveButton()

        if (isEditMode) {
            loadEditRecord()
        } else {
            // 先处理预选分类，再开始观察，确保初始值正确
            handlePreSelectedCategory()
        }

        observeViewModel()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            resolveEditMode()
            if (isEditMode) {
                loadEditRecord()
            } else {
                // Fragment 从隐藏变为显示时，处理预选分类
                handlePreSelectedCategory()
            }
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

    private fun resolveEditMode() {
        val navTarget = mainVm.navTarget.value as? NavTarget.EventRecord
        editRecordId = navTarget?.editRecordId
        isEditMode = editRecordId != null
        isCategoryLocked = isEditMode && navTarget?.returnTarget is NavTarget.Statistics
        if (!isEditMode) {
            editingRecord = null
            pendingExtraData = null
        }
        applyCategoryLockState()
    }

    private fun loadEditRecord() {
        val recordId = editRecordId ?: return
        vm.loadEventRecordById(recordId) { record ->
            if (record == null) {
                mainVm.navigateTo(getReturnTarget())
                return@loadEventRecordById
            }
            editingRecord = record
            pendingExtraData = com.zero.babydata.entity.EventExtraData.parse(record.type, record.extraData)

            val category = EventCategory.fromId(com.zero.babydata.entity.EventType.getCategory(record.type))
            val subtype = EventSubtype.fromType(record.type)
            if (category != null) {
                vm.selectCategory(category)
            }
            if (subtype != null) {
                vm.selectSubtype(subtype)
            }

            vm.setEventTime(record.time)
            vm.setEndTime(if (record.endTime > 0L) record.endTime else null)
            vm.setExtraData(pendingExtraData)
            vm.setNote(record.note)
            binding.etNote.setText(record.note)
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
            if (!isCategoryLocked) {
                vm.selectCategory(category)
            }
        }
        binding.rvCategory.adapter = categoryAdapter
        applyCategoryLockState()
    }

    private fun setupSubtypeRecyclerView() {
        subtypeAdapter = EventSubtypeAdapter { subtype ->
            if (!isCategoryLocked) {
                vm.selectSubtype(subtype)
            }
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
        applyCategoryLockState()
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
                        val subtype = vm.selectedSubtype.value
                        if (subtype != null && EventType.hasDuration(subtype.type)) {
                            syncActivityStartTime(calendar.timeInMillis)
                        }
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

    private fun syncActivityStartTime(timestamp: Long) {
        val root = binding.containerDetail
        if (root.childCount == 0) return
        val detailBinding = LayoutEventDetailActivityBinding.bind(root.getChildAt(0))
        if (detailBinding.timerPanel.startInput.getTimestamp() == timestamp) return

        activityTimerController?.let { controller ->
            controller.syncStartTime(
                timestamp = timestamp,
                clearEnd = true,
                resetTimer = true,
                notify = false,
                notifyEndClear = true
            )
            return
        }

        detailBinding.timerPanel.startInput.setTimestamp(timestamp)
        detailBinding.timerPanel.endInput.clear()
        detailBinding.timerPanel.endInput.setReferenceTimestamp(timestamp)
        detailBinding.timerPanel.timerView.reset()
        vm.setEndTime(null)
    }

    private fun updateDetailView(subtype: EventSubtype?) {
        binding.containerDetail.removeAllViews()
        activityTimerController = null

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
            subtype.type == EventType.HEALTH_VACCINE -> {
                inflateVaccineDetail(inflater)
            }
            subtype.type == EventType.HEALTH_SYMPTOM -> {
                inflateSymptomDetail(inflater)
            }
            EventType.isMilestone(subtype.type) -> {
                inflateMilestoneDetail(inflater, subtype.type)
            }
            EventType.hasDuration(subtype.type) -> {
                inflateActivityDetail(inflater)
            }
            subtype.type == EventType.OTHER_CUSTOM -> {
                inflateCustomDetail(inflater)
            }
            else -> {
                // 简单事件无详情
                binding.containerDetail.visibility = View.GONE
            }
        }

        applyEditDetailIfNeeded(subtype)
    }

    private fun applyEditDetailIfNeeded(subtype: EventSubtype) {
        if (!isEditMode) return

        val root = binding.containerDetail
        val data = pendingExtraData

        when {
            EventType.isDiaper(subtype.type) -> {
                val consistencyGroup = root.findViewById<RadioGroup>(com.zero.babycare.R.id.rgStoolConsistency)
                val colorGroup = root.findViewById<RadioGroup>(com.zero.babycare.R.id.rgStoolColor)
                val urineGroup = root.findViewById<RadioGroup>(com.zero.babycare.R.id.rgUrineAmount)
                if (data is DiaperData) {
                    when (data.consistency) {
                        DiaperData.CONSISTENCY_WATERY -> consistencyGroup?.check(com.zero.babycare.R.id.rbWatery)
                        DiaperData.CONSISTENCY_HARD -> consistencyGroup?.check(com.zero.babycare.R.id.rbHard)
                        DiaperData.CONSISTENCY_NORMAL -> consistencyGroup?.check(com.zero.babycare.R.id.rbSoft)
                    }
                    when (data.color) {
                        DiaperData.COLOR_YELLOW -> colorGroup?.check(com.zero.babycare.R.id.rbYellow)
                        DiaperData.COLOR_GREEN -> colorGroup?.check(com.zero.babycare.R.id.rbGreen)
                        DiaperData.COLOR_BROWN -> colorGroup?.check(com.zero.babycare.R.id.rbBrown)
                        DiaperData.COLOR_BLACK -> colorGroup?.check(com.zero.babycare.R.id.rbBlack)
                    }
                    when (data.urineAmount) {
                        DiaperData.URINE_AMOUNT_LITTLE -> urineGroup?.check(com.zero.babycare.R.id.rbLittle)
                        DiaperData.URINE_AMOUNT_NORMAL -> urineGroup?.check(com.zero.babycare.R.id.rbNormal)
                        DiaperData.URINE_AMOUNT_MUCH -> urineGroup?.check(com.zero.babycare.R.id.rbMuch)
                        else -> if (data.abnormal) {
                            urineGroup?.check(com.zero.babycare.R.id.rbMuch)
                        }
                    }
                }
            }
            EventType.isGrowth(subtype.type) -> {
                val valueView = root.findViewById<EditText>(com.zero.babycare.R.id.etValue)
                if (data is GrowthData) {
                    val targetUnit = getGrowthUnitValue(subtype.type)
                    val displayValue = when (subtype.type) {
                        EventType.GROWTH_WEIGHT -> UnitConverter.weightToDisplay(data.value, data.unit, targetUnit)
                        EventType.GROWTH_HEIGHT, EventType.GROWTH_HEAD -> {
                            UnitConverter.heightToDisplay(data.value, data.unit, targetUnit)
                        }
                        else -> data.value
                    }
                    valueView?.setText(UnitConverter.formatDecimal(displayValue))
                }
            }
            subtype.type == EventType.HEALTH_TEMPERATURE -> {
                val tempView = root.findViewById<EditText>(com.zero.babycare.R.id.etTemperature)
                if (data is TemperatureData) {
                    tempView?.setText(data.value.toString())
                    val detailBinding = LayoutEventDetailTemperatureBinding.bind(root.getChildAt(0))
                    selectTemperatureLocation(detailBinding, data.location)
                    updateTemperatureStatus(detailBinding, data.value)
                }
            }
            subtype.type == EventType.HEALTH_MEDICINE -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etMedicineName)
                val dosageView = root.findViewById<EditText>(com.zero.babycare.R.id.etDosage)
                val unitView = root.findViewById<Spinner>(com.zero.babycare.R.id.spinnerUnit)
                if (data is MedicineData) {
                    nameView?.setText(data.name)
                    dosageView?.setText(data.dosage)
                    val adapter = unitView?.adapter
                    if (adapter != null) {
                        for (index in 0 until adapter.count) {
                            if (adapter.getItem(index).toString() == data.unit) {
                                unitView.setSelection(index)
                                break
                            }
                        }
                    }
                }
            }
            subtype.type == EventType.HEALTH_VACCINE -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineName)
                val doseView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineDose)
                val siteView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineSite)
                val clinicView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineClinic)
                val batchView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineBatch)
                if (data is VaccineData) {
                    nameView?.setText(data.name)
                    doseView?.setText(data.dose?.toString().orEmpty())
                    siteView?.setText(data.site.orEmpty())
                    clinicView?.setText(data.clinic.orEmpty())
                    batchView?.setText(data.batchNumber.orEmpty())
                }
            }
            subtype.type == EventType.HEALTH_SYMPTOM -> {
                val descView = root.findViewById<EditText>(com.zero.babycare.R.id.etSymptomDescription)
                if (data is SymptomData) {
                    descView?.setText(data.description.orEmpty())
                }
            }
            EventType.isMilestone(subtype.type) -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etMilestoneName)
                val descView = root.findViewById<EditText>(com.zero.babycare.R.id.etDescription)
                if (data is MilestoneData) {
                    nameView?.setText(data.name.orEmpty())
                    descView?.setText(data.description.orEmpty())
                }
            }
            subtype.type == EventType.OTHER_CUSTOM -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etCustomEventName)
                val descView = root.findViewById<EditText>(com.zero.babycare.R.id.etCustomEventDescription)
                if (data is CustomEventData) {
                    nameView?.setText(data.name.orEmpty())
                    descView?.setText(data.description.orEmpty())
                }
            }
        }

        if (EventType.hasDuration(subtype.type)) {
            val endTime = vm.endTime.value
            activityTimerController?.setStartTime(vm.eventTime.value, notify = false)
            if (endTime != null && endTime > 0L) {
                activityTimerController?.setEndTime(endTime, notify = false, updateDuration = true)
            } else {
                activityTimerController?.clearEndTime(notify = false)
            }
        }

        pendingExtraData = null
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
        val urineAmount = when (detailBinding.rgUrineAmount.checkedRadioButtonId) {
            com.zero.babycare.R.id.rbLittle -> DiaperData.URINE_AMOUNT_LITTLE
            com.zero.babycare.R.id.rbNormal -> DiaperData.URINE_AMOUNT_NORMAL
            com.zero.babycare.R.id.rbMuch -> DiaperData.URINE_AMOUNT_MUCH
            else -> null
        }
        val abnormal = urineAmount == DiaperData.URINE_AMOUNT_MUCH

        vm.setExtraData(
            DiaperData(
                color = color,
                consistency = consistency,
                abnormal = abnormal,
                urineAmount = urineAmount
            )
        )
    }

    private fun inflateGrowthDetail(inflater: LayoutInflater, type: Int) {
        val detailBinding = LayoutEventDetailGrowthBinding.inflate(inflater, binding.containerDetail, true)

        // 设置单位
        val unitResId = getGrowthUnitLabelResId(type)
        val unitValue = getGrowthUnitValue(type)
        detailBinding.tvUnit.text = StringUtils.getString(unitResId)

        // 监听输入变化
        detailBinding.etValue.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = detailBinding.etValue.text.toString().toDoubleOrNull()
                if (value != null) {
                    vm.setExtraData(GrowthData(value, unitValue))
                }
            }
        }
    }

    private fun inflateTemperatureDetail(inflater: LayoutInflater) {
        val detailBinding = LayoutEventDetailTemperatureBinding.inflate(inflater, binding.containerDetail, true)

        detailBinding.etTemperature.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateTemperatureData(detailBinding)
            }
        }

        detailBinding.rgTemperatureLocation.setOnCheckedChangeListener { _, _ ->
            updateTemperatureData(detailBinding)
        }

        selectTemperatureLocation(detailBinding, TemperatureData.LOCATION_EAR)
    }

    private fun updateTemperatureData(detailBinding: LayoutEventDetailTemperatureBinding) {
        val value = detailBinding.etTemperature.text.toString().toDoubleOrNull()
        if (value != null) {
            val location = getTemperatureLocation(detailBinding)
            vm.setExtraData(TemperatureData(value, location))
            updateTemperatureStatus(detailBinding, value)
        } else {
            vm.setExtraData(null)
        }
    }

    private fun getTemperatureLocation(detailBinding: LayoutEventDetailTemperatureBinding): String {
        return when (detailBinding.rgTemperatureLocation.checkedRadioButtonId) {
            com.zero.babycare.R.id.rbTempForehead -> TemperatureData.LOCATION_FOREHEAD
            com.zero.babycare.R.id.rbTempArmpit -> TemperatureData.LOCATION_ARMPIT
            com.zero.babycare.R.id.rbTempOral -> TemperatureData.LOCATION_ORAL
            com.zero.babycare.R.id.rbTempRectal -> TemperatureData.LOCATION_RECTAL
            else -> TemperatureData.LOCATION_EAR
        }
    }

    private fun selectTemperatureLocation(
        detailBinding: LayoutEventDetailTemperatureBinding,
        location: String
    ) {
        val targetId = when (location) {
            TemperatureData.LOCATION_FOREHEAD -> com.zero.babycare.R.id.rbTempForehead
            TemperatureData.LOCATION_ARMPIT -> com.zero.babycare.R.id.rbTempArmpit
            TemperatureData.LOCATION_ORAL -> com.zero.babycare.R.id.rbTempOral
            TemperatureData.LOCATION_RECTAL -> com.zero.babycare.R.id.rbTempRectal
            else -> com.zero.babycare.R.id.rbTempEar
        }
        detailBinding.rgTemperatureLocation.check(targetId)
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
        val defaultUnit = StringUtils.getString(R.string.unit_ml_abbr)

        detailBinding.etMedicineName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val name = detailBinding.etMedicineName.text.toString()
                val dosage = detailBinding.etDosage.text.toString()
                if (name.isNotBlank()) {
                    vm.setExtraData(MedicineData(name, dosage, defaultUnit))
                }
            }
        }
    }

    private fun inflateVaccineDetail(inflater: LayoutInflater) {
        val detailBinding = LayoutEventDetailVaccineBinding.inflate(inflater, binding.containerDetail, true)

        detailBinding.etVaccineName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateVaccineData(detailBinding)
            }
        }
        detailBinding.etVaccineDose.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateVaccineData(detailBinding)
            }
        }
        detailBinding.etVaccineSite.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateVaccineData(detailBinding)
            }
        }
        detailBinding.etVaccineClinic.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateVaccineData(detailBinding)
            }
        }
        detailBinding.etVaccineBatch.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateVaccineData(detailBinding)
            }
        }
    }

    private fun updateVaccineData(detailBinding: LayoutEventDetailVaccineBinding) {
        val name = detailBinding.etVaccineName.text.toString().trim()
        val dose = detailBinding.etVaccineDose.text.toString().trim().toIntOrNull()
        val site = detailBinding.etVaccineSite.text.toString().trim()
        val clinic = detailBinding.etVaccineClinic.text.toString().trim()
        val batch = detailBinding.etVaccineBatch.text.toString().trim()
        val doseValue = dose?.takeIf { it > 0 }
        if (name.isNotBlank() || site.isNotBlank() || clinic.isNotBlank() || batch.isNotBlank() || doseValue != null) {
            vm.setExtraData(
                VaccineData(
                    name = name,
                    dose = doseValue,
                    site = site.ifBlank { null },
                    batchNumber = batch.ifBlank { null },
                    clinic = clinic.ifBlank { null }
                )
            )
        } else {
            vm.setExtraData(null)
        }
    }

    private fun inflateSymptomDetail(inflater: LayoutInflater) {
        val detailBinding = LayoutEventDetailSymptomBinding.inflate(inflater, binding.containerDetail, true)

        detailBinding.etSymptomDescription.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val description = detailBinding.etSymptomDescription.text.toString().trim()
                if (description.isNotBlank()) {
                    vm.setExtraData(SymptomData(description))
                } else {
                    vm.setExtraData(null)
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

    private fun inflateCustomDetail(inflater: LayoutInflater) {
        val detailBinding = LayoutEventDetailCustomBinding.inflate(inflater, binding.containerDetail, true)

        detailBinding.etCustomEventName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateCustomData(detailBinding)
            }
        }

        detailBinding.etCustomEventDescription.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateCustomData(detailBinding)
            }
        }
    }

    private fun inflateActivityDetail(inflater: LayoutInflater) {
        val detailBinding = LayoutEventDetailActivityBinding.inflate(inflater, binding.containerDetail, true)

        activityTimerController = RecordTimerController(
            context = requireContext(),
            timerView = detailBinding.timerPanel.timerView,
            startInput = detailBinding.timerPanel.startInput,
            endInput = detailBinding.timerPanel.endInput,
            startPicker = detailBinding.timerPanel.startPicker,
            endPicker = detailBinding.timerPanel.endPicker,
            config = RecordTimerController.Config(
                invalidEndTimeMessageRes = R.string.event_end_time_invalid
            ),
            callbacks = RecordTimerController.Callbacks(
                onStartTimeChanged = { timestamp -> vm.setEventTime(timestamp) },
                onEndTimeChanged = { timestamp -> vm.setEndTime(timestamp) }
            )
        )

        // 初始化时间
        val startTime = vm.eventTime.value
        activityTimerController?.setStartTime(startTime, notify = false)

        val endTime = vm.endTime.value
        if (endTime != null && endTime > 0L) {
            activityTimerController?.setEndTime(endTime, notify = false, updateDuration = true)
        } else {
            activityTimerController?.clearEndTime(notify = false)
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

        syncDetailInputs()

        // 获取备注
        vm.setNote(binding.etNote.text.toString().trim())

        vm.saveRecord(
            babyId = babyId,
            editRecordId = editRecordId,
            createdAt = editingRecord?.createdAt,
            onSuccess = {
                ToastUtils.showShort(R.string.save_success)
                vm.reset()
                mainVm.navigateTo(getReturnTarget())
            },
            onError = { message ->
                ToastUtils.showShort(message)
            }
        )
    }

    private fun syncDetailInputs() {
        val subtype = vm.selectedSubtype.value ?: return
        val root = binding.containerDetail
        when {
            EventType.isDiaper(subtype.type) -> {
                val consistencyGroup = root.findViewById<RadioGroup>(com.zero.babycare.R.id.rgStoolConsistency)
                val colorGroup = root.findViewById<RadioGroup>(com.zero.babycare.R.id.rgStoolColor)
                val urineGroup = root.findViewById<RadioGroup>(com.zero.babycare.R.id.rgUrineAmount)
                if (consistencyGroup == null || colorGroup == null || urineGroup == null) {
                    return
                }

                val consistency = when (consistencyGroup.checkedRadioButtonId) {
                    com.zero.babycare.R.id.rbWatery -> DiaperData.CONSISTENCY_WATERY
                    com.zero.babycare.R.id.rbSoft -> DiaperData.CONSISTENCY_NORMAL
                    com.zero.babycare.R.id.rbHard -> DiaperData.CONSISTENCY_HARD
                    else -> null
                }
                val color = when (colorGroup.checkedRadioButtonId) {
                    com.zero.babycare.R.id.rbYellow -> DiaperData.COLOR_YELLOW
                    com.zero.babycare.R.id.rbGreen -> DiaperData.COLOR_GREEN
                    com.zero.babycare.R.id.rbBrown -> DiaperData.COLOR_BROWN
                    com.zero.babycare.R.id.rbBlack -> DiaperData.COLOR_BLACK
                    else -> null
                }
                val urineAmount = when (urineGroup.checkedRadioButtonId) {
                    com.zero.babycare.R.id.rbLittle -> DiaperData.URINE_AMOUNT_LITTLE
                    com.zero.babycare.R.id.rbNormal -> DiaperData.URINE_AMOUNT_NORMAL
                    com.zero.babycare.R.id.rbMuch -> DiaperData.URINE_AMOUNT_MUCH
                    else -> null
                }
                val abnormal = urineAmount == DiaperData.URINE_AMOUNT_MUCH
                vm.setExtraData(
                    DiaperData(
                        color = color,
                        consistency = consistency,
                        abnormal = abnormal,
                        urineAmount = urineAmount
                    )
                )
            }
            EventType.isGrowth(subtype.type) -> {
                val valueView = root.findViewById<EditText>(com.zero.babycare.R.id.etValue)
                val value = valueView?.text?.toString()?.toDoubleOrNull()
                val unit = getGrowthUnitValue(subtype.type)
                if (value != null) {
                    vm.setExtraData(GrowthData(value, unit))
                } else {
                    vm.setExtraData(null)
                }
            }
            subtype.type == EventType.HEALTH_TEMPERATURE -> {
                val tempView = root.findViewById<EditText>(com.zero.babycare.R.id.etTemperature)
                val locationGroup = root.findViewById<RadioGroup>(com.zero.babycare.R.id.rgTemperatureLocation)
                val value = tempView?.text?.toString()?.toDoubleOrNull()
                if (value != null) {
                    val location = when (locationGroup?.checkedRadioButtonId) {
                        com.zero.babycare.R.id.rbTempForehead -> TemperatureData.LOCATION_FOREHEAD
                        com.zero.babycare.R.id.rbTempArmpit -> TemperatureData.LOCATION_ARMPIT
                        com.zero.babycare.R.id.rbTempOral -> TemperatureData.LOCATION_ORAL
                        com.zero.babycare.R.id.rbTempRectal -> TemperatureData.LOCATION_RECTAL
                        else -> TemperatureData.LOCATION_EAR
                    }
                    vm.setExtraData(TemperatureData(value, location))
                } else {
                    vm.setExtraData(null)
                }
            }
            subtype.type == EventType.HEALTH_MEDICINE -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etMedicineName)
                val dosageView = root.findViewById<EditText>(com.zero.babycare.R.id.etDosage)
                val unitView = root.findViewById<Spinner>(com.zero.babycare.R.id.spinnerUnit)
                val name = nameView?.text?.toString()?.trim().orEmpty()
                val dosage = dosageView?.text?.toString()?.trim().orEmpty()
                val defaultUnit = StringUtils.getString(R.string.unit_ml_abbr)
                val unit = unitView?.selectedItem?.toString()?.ifBlank { defaultUnit } ?: defaultUnit
                if (name.isNotBlank()) {
                    vm.setExtraData(MedicineData(name, dosage, unit))
                } else {
                    vm.setExtraData(null)
                }
            }
            subtype.type == EventType.HEALTH_VACCINE -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineName)
                val doseView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineDose)
                val siteView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineSite)
                val clinicView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineClinic)
                val batchView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineBatch)
                val name = nameView?.text?.toString()?.trim().orEmpty()
                val dose = doseView?.text?.toString()?.trim()?.toIntOrNull()
                val site = siteView?.text?.toString()?.trim().orEmpty()
                val clinic = clinicView?.text?.toString()?.trim().orEmpty()
                val batch = batchView?.text?.toString()?.trim().orEmpty()
                val doseValue = dose?.takeIf { it > 0 }
                if (name.isNotBlank() || site.isNotBlank() || clinic.isNotBlank() || batch.isNotBlank() || doseValue != null) {
                    vm.setExtraData(
                        VaccineData(
                            name = name,
                            dose = doseValue,
                            site = site.ifBlank { null },
                            batchNumber = batch.ifBlank { null },
                            clinic = clinic.ifBlank { null }
                        )
                    )
                } else {
                    vm.setExtraData(null)
                }
            }
            subtype.type == EventType.HEALTH_SYMPTOM -> {
                val descView = root.findViewById<EditText>(com.zero.babycare.R.id.etSymptomDescription)
                val description = descView?.text?.toString()?.trim().orEmpty()
                if (description.isNotBlank()) {
                    vm.setExtraData(SymptomData(description))
                } else {
                    vm.setExtraData(null)
                }
            }
            subtype.type == EventType.MILESTONE_CUSTOM -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etMilestoneName)
                val descView = root.findViewById<EditText>(com.zero.babycare.R.id.etDescription)
                val name = nameView?.text?.toString()?.trim().orEmpty()
                val description = descView?.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) {
                    vm.setExtraData(MilestoneData(name, description, true))
                } else {
                    vm.setExtraData(null)
                }
            }
            subtype.type == EventType.OTHER_CUSTOM -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etCustomEventName)
                val descView = root.findViewById<EditText>(com.zero.babycare.R.id.etCustomEventDescription)
                val name = nameView?.text?.toString()?.trim().orEmpty()
                val description = descView?.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank() || description.isNotBlank()) {
                    vm.setExtraData(CustomEventData(name, description))
                } else {
                    vm.setExtraData(null)
                }
            }
        }
    }

    private fun updateCustomData(detailBinding: LayoutEventDetailCustomBinding) {
        val name = detailBinding.etCustomEventName.text.toString().trim()
        val description = detailBinding.etCustomEventDescription.text.toString().trim()
        if (name.isNotBlank() || description.isNotBlank()) {
            vm.setExtraData(CustomEventData(name, description))
        } else {
            vm.setExtraData(null)
        }
    }

    /**
     * 生长类单位资源
     */
    private fun getGrowthUnitLabelResId(type: Int): Int {
        return when (type) {
            EventType.GROWTH_WEIGHT -> UnitConfig.getWeightUnitLabelResId()
            EventType.GROWTH_HEIGHT, EventType.GROWTH_HEAD -> UnitConfig.getHeightUnitLabelResId()
            else -> UnitConfig.getHeightUnitLabelResId()
        }
    }

    /**
     * 生长类单位值
     */
    private fun getGrowthUnitValue(type: Int): String {
        return when (type) {
            EventType.GROWTH_WEIGHT -> UnitConfig.getWeightUnit()
            EventType.GROWTH_HEIGHT, EventType.GROWTH_HEAD -> UnitConfig.getHeightUnit()
            else -> UnitConfig.getHeightUnit()
        }
    }

    private fun getReturnTarget(): NavTarget {
        return (mainVm.navTarget.value as? NavTarget.EventRecord)?.returnTarget ?: NavTarget.Dashboard
    }

    override fun onSystemBackPressed(): Boolean {
        handleBack()
        return true
    }

    private fun handleBack() {
        mainVm.navigateTo(getReturnTarget())
    }

    private fun applyCategoryLockState() {
        if (!::categoryAdapter.isInitialized || !::subtypeAdapter.isInitialized) {
            return
        }
        categoryAdapter.setLocked(isCategoryLocked)
        subtypeAdapter.setLocked(isCategoryLocked)
        binding.rvCategory.isEnabled = !isCategoryLocked
        binding.rvSubtype.isEnabled = !isCategoryLocked
    }
}
