package com.zero.babycare.home.record.event

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.ThreadUtils
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.babydata.entity.EventExtraData
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.components.base.vm.BaseViewModel
import com.zero.common.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 事件记录 ViewModel
 */
class EventRecordViewModel : BaseViewModel() {

    /** 当前选中的大类 */
    private val _selectedCategory = MutableStateFlow<EventCategory?>(null)
    val selectedCategory: StateFlow<EventCategory?> = _selectedCategory

    /** 当前选中的子类型 */
    private val _selectedSubtype = MutableStateFlow<EventSubtype?>(null)
    val selectedSubtype: StateFlow<EventSubtype?> = _selectedSubtype

    /** 事件时间 */
    private val _eventTime = MutableStateFlow(System.currentTimeMillis())
    val eventTime: StateFlow<Long> = _eventTime

    /** 活动类事件的显式开始时间，只有点击开始计时或手动输入开始时间后才写入 */
    private val _durationStartTime = MutableStateFlow<Long?>(null)
    val durationStartTime: StateFlow<Long?> = _durationStartTime

    /** 结束时间（活动类事件） */
    private val _endTime = MutableStateFlow<Long?>(null)
    val endTime: StateFlow<Long?> = _endTime

    /** 扩展数据 */
    private val _extraData = MutableStateFlow<EventExtraData?>(null)
    val extraData: StateFlow<EventExtraData?> = _extraData

    /** 备注 */
    private val _note = MutableStateFlow("")
    val note: StateFlow<String> = _note

    /**
     * 选择大类
     */
    fun selectCategory(category: EventCategory) {
        // 先清理依赖旧类型的详情状态，再发出分类/子类型变化，避免页面重建时读到旧表单数据。
        clearDurationTiming()
        _extraData.value = null
        _selectedCategory.value = category
        _selectedSubtype.value = null
    }

    /**
     * 选择子类型
     */
    fun selectSubtype(subtype: EventSubtype) {
        val subtypeChanged = _selectedSubtype.value?.type != subtype.type
        if (subtypeChanged) {
            // selectedSubtype 的观察者会同步重建详情区，必须在发出新类型前清空旧计时状态。
            clearDurationTiming()
        }
        _extraData.value = null
        _selectedSubtype.value = subtype
    }

    /**
     * 设置事件时间
     */
    fun setEventTime(time: Long) {
        _eventTime.value = time
    }

    /**
     * 设置活动类事件的开始时间。
     *
     * 页面顶部事件时间是普通事件的默认发生时间；活动类事件只有用户明确开始计时
     * 或手动选择开始时间后，才把该值作为可保存的开始时间。
     */
    fun setDurationStartTime(time: Long?) {
        val normalizedTime = time?.takeIf { it > 0L }
        _durationStartTime.value = normalizedTime
        if (normalizedTime != null) {
            _eventTime.value = normalizedTime
        }
    }

    /**
     * 设置结束时间
     */
    fun setEndTime(time: Long?) {
        _endTime.value = time
    }

    /**
     * 设置扩展数据
     */
    fun setExtraData(data: EventExtraData?) {
        _extraData.value = data
    }

    /**
     * 设置备注
     */
    fun setNote(note: String) {
        _note.value = note
    }

    fun loadEventRecordById(eventId: Int, callback: (EventRecord?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val record = repository.getEventRecordById(eventId)
            withContext(Dispatchers.Main) {
                callback(record)
            }
        }
    }

    /**
     * 判断当前事件类型是否需要时长
     */
    fun requiresDuration(): Boolean {
        val subtype = _selectedSubtype.value ?: return false
        return EventType.hasDuration(subtype.type)
    }

    /**
     * 验证数据是否完整
     */
    fun validateData(): ValidationResult {
        val subtype = _selectedSubtype.value
            ?: return ValidationResult.Error(R.string.please_select_event_type)

        // 需要时长的事件验证结束时间
        if (requiresDuration()) {
            val start = _durationStartTime.value
            if (start == null || start <= 0L) {
                return ValidationResult.Error(R.string.start_time_required)
            }
            val end = _endTime.value
            if (end == null || end <= start) {
                return ValidationResult.Error(R.string.event_end_time_invalid)
            }
        }

        // 生长类验证数值
        if (EventType.isGrowth(subtype.type)) {
            val data = _extraData.value
            if (data !is com.zero.babydata.entity.GrowthData) {
                return ValidationResult.Error(R.string.event_value_required)
            }
        }

        // 体温验证
        if (subtype.type == EventType.HEALTH_TEMPERATURE) {
            val data = _extraData.value
            if (data !is com.zero.babydata.entity.TemperatureData) {
                return ValidationResult.Error(R.string.temperature_value_required)
            }
        }

        // 用药验证
        if (subtype.type == EventType.HEALTH_MEDICINE) {
            val data = _extraData.value
            if (data !is com.zero.babydata.entity.MedicineData || data.name.isBlank()) {
                return ValidationResult.Error(R.string.medicine_name_hint)
            }
        }

        // 疫苗验证
        if (subtype.type == EventType.HEALTH_VACCINE) {
            val data = _extraData.value
            if (data !is com.zero.babydata.entity.VaccineData || data.name.isBlank()) {
                return ValidationResult.Error(R.string.vaccine_name_hint)
            }
        }

        // 症状验证
        if (subtype.type == EventType.HEALTH_SYMPTOM) {
            val data = _extraData.value
            if (data !is com.zero.babydata.entity.SymptomData || data.description.isNullOrBlank()) {
                return ValidationResult.Error(R.string.symptom_hint)
            }
        }

        // 自定义里程碑验证
        if (subtype.type == EventType.MILESTONE_CUSTOM) {
            val data = _extraData.value
            if (data !is com.zero.babydata.entity.MilestoneData || data.name.isNullOrBlank()) {
                return ValidationResult.Error(R.string.milestone_name_hint)
            }
        }

        // 自定义事件验证
        if (subtype.type == EventType.OTHER_CUSTOM) {
            val data = _extraData.value
            val hasValue = data is com.zero.babydata.entity.CustomEventData &&
                (!data.name.isNullOrBlank() || !data.description.isNullOrBlank())
            if (!hasValue) {
                return ValidationResult.Error(R.string.custom_event_required)
            }
        }

        return ValidationResult.Success
    }

    /**
     * 保存事件记录
     */
    fun saveRecord(
        babyId: Int,
        editRecordId: Int? = null,
        createdAt: Long? = null,
        onSuccess: () -> Unit,
        onError: (Int) -> Unit
    ) {
        val validation = validateData()
        if (validation is ValidationResult.Error) {
            onError(validation.messageResId)
            return
        }

        val subtype = _selectedSubtype.value ?: return
        val recordTime = if (EventType.hasDuration(subtype.type)) {
            _durationStartTime.value ?: _eventTime.value
        } else {
            _eventTime.value
        }

        safeLaunch {
            val record = EventRecord(
                eventId = editRecordId ?: 0,
                babyId = babyId,
                type = subtype.type,
                time = recordTime,
                endTime = _endTime.value ?: 0L,
                extraData = _extraData.value?.toJson() ?: "",
                note = _note.value,
                createdAt = createdAt ?: System.currentTimeMillis()
            )

            val onComplete = Runnable {
                ThreadUtils.runOnUiThread { onSuccess() }
            }
            val onFailure: (Throwable) -> Unit = {
                ThreadUtils.runOnUiThread {
                    onError(R.string.save_failed)
                }
            }

            if (editRecordId != null) {
                repository.updateEventRecord(record, onComplete, onFailure)
            } else {
                repository.insertEventRecord(record, onComplete, onFailure)
            }
        }
    }

    /**
     * 重置状态
     */
    fun reset() {
        _selectedCategory.value = null
        _selectedSubtype.value = null
        _eventTime.value = System.currentTimeMillis()
        clearDurationTiming()
        _extraData.value = null
        _note.value = ""
    }

    private fun clearDurationTiming() {
        _durationStartTime.value = null
        _endTime.value = null
    }

    /**
     * 验证结果
     */
    sealed class ValidationResult {
        object Success : ValidationResult()
        data class Error(@StringRes val messageResId: Int) : ValidationResult()
    }
}
