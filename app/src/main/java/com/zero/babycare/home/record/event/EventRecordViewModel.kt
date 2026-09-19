package com.zero.babycare.home.record.event

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.zero.babycare.home.BabyRecordChangeBus
import com.zero.babydata.domain.BabyDataHelper
import com.zero.babydata.room.BabyRepository
import com.zero.babydata.entity.EventExtraData
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.GenericData
import com.zero.components.base.vm.BaseViewModel
import com.zero.common.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zero.babycare.home.record.RecordMutationController

/**
 * 事件记录 ViewModel
 */
class EventRecordViewModel(private val injectedRepository: BabyRepository? = null) : BaseViewModel() {
    // 延迟获取默认数据源，让草稿状态恢复独立于数据库初始化，并支持隔离的数据层验证。
    private val repository by lazy { injectedRepository ?: BabyDataHelper.repository }
    internal val mutation = RecordMutationController(viewModelScope)

    /** 草稿只保存未提交的页面状态，详情原始输入由 View 层层级状态保留。 */
    data class Draft(
        val categoryId: Int?,
        val subtypeType: Int?,
        val eventTime: Long,
        val durationStartTime: Long?,
        val endTime: Long?
    )

    /** 只比较页面可编辑字段的原始输入，不对旧 JSON 对象图执行深层 equals/hash。 */
    internal data class ExtraDataInputs(val type: Int, val values: List<String>)

    internal data class ExtraDataEdit(
        val originalRecord: EventRecord,
        val originalInputs: ExtraDataInputs?,
        val currentInputs: ExtraDataInputs?
    )

    fun captureDraft(): Draft = Draft(
        _selectedCategory.value?.id, _selectedSubtype.value?.type, _eventTime.value,
        _durationStartTime.value, _endTime.value
    )

    /** 恢复依赖字段后再发布类型，防止详情重建时清空原有时间。 */
    fun restoreDraft(draft: Draft) {
        _extraData.value = null
        _eventTime.value = draft.eventTime
        _durationStartTime.value = draft.durationStartTime
        _endTime.value = draft.endTime
        _selectedCategory.value = draft.categoryId?.let(EventCategory::fromId)
        _selectedSubtype.value = draft.subtypeType?.let(EventSubtype::fromType)
    }

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
        // 通用扩展没有编辑表单；旧嵌套对象只能沿用原文，不能进入 StateFlow 的深层相等比较。
        _extraData.value = data.takeUnless { it is GenericData }
    }

    /** 没有详情表单的事件从加载开始就保留原文，避免把旧深层 JSON 解析成通用对象图。 */
    internal fun editableExtraData(record: EventRecord): EventExtraData? =
        if (hasEditableExtraData(record.type)) EventExtraData.parse(record.type, record.extraData) else null

    private fun hasEditableExtraData(type: Int): Boolean =
        EventType.isDiaper(type) || EventType.isGrowth(type) || EventType.isMilestone(type) ||
            type == EventType.HEALTH_TEMPERATURE || type == EventType.HEALTH_MEDICINE ||
            type == EventType.HEALTH_VACCINE || type == EventType.HEALTH_SYMPTOM || type == EventType.OTHER_CUSTOM

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
    internal fun saveRecord(
        target: RecordMutationController.Target,
        createdAt: Long? = null,
        extraDataEdit: ExtraDataEdit? = null,
    ) {
        if (mutation.isPending) return
        val validation = validateData()
        if (validation is ValidationResult.Error) {
            mutation.reject(target, RecordMutationController.Kind.SAVE, validation.messageResId)
            return
        }

        val subtype = _selectedSubtype.value ?: return
        val recordTime = if (EventType.hasDuration(subtype.type)) {
            _durationStartTime.value ?: _eventTime.value
        } else {
            _eventTime.value
        }

        // 原文只属于同一宝宝、记录和类型；恢复后的输入仍须与首次回填基线一致。
        val original = extraDataEdit?.originalRecord?.copy()
        val originalInputs = extraDataEdit?.originalInputs
        val currentInputs = extraDataEdit?.currentInputs
        val hasOriginalExtra = original != null && original.eventId == target.recordId &&
            original.babyId == target.babyId && original.type == subtype.type
        val inputsUnchanged = originalInputs != null && currentInputs != null &&
            originalInputs.type == subtype.type && currentInputs.type == subtype.type &&
            originalInputs.values == currentInputs.values

        // 冻结所有输入，再在受控保存协程内序列化；失败不写空值，也不绕过错误状态通道。
        val extraSnapshot = _extraData.value
        val record = EventRecord(
                eventId = target.recordId ?: 0,
                babyId = target.babyId,
                type = subtype.type,
                time = recordTime,
                endTime = _endTime.value ?: 0L,
                note = _note.value,
                createdAt = createdAt ?: System.currentTimeMillis()
            )

        mutation.submit(target, RecordMutationController.Kind.SAVE, R.string.save_failed) {
            withContext(Dispatchers.IO) {
                // 旧草稿没有输入基线：无详情表单可直接保真；有表单只接受扁平已知值严格相等。
                // GenericData 从不解析或比较，类型切换也不能把旧通用扩展带入新事件。
                val keepOriginalExtra = hasOriginalExtra && (
                    !hasEditableExtraData(subtype.type) || inputsUnchanged ||
                        (originalInputs == null && extraSnapshot != null &&
                            extraSnapshot == editableExtraData(requireNotNull(original)))
                    )
                record.extraData = if (keepOriginalExtra) requireNotNull(original).extraData
                    else extraSnapshot?.toJson() ?: ""
                if (target.recordId != null) repository.updateEventRecord(record)
                else repository.insertEventRecordOnce(record, target.draftId)
            }
            BabyRecordChangeBus.notifyChanged(record.babyId)
        }
    }

    /** 删除事件后通知所有记录消费者，保证时间轴、统计和预测立即失效重算。 */
    internal fun deleteRecord(
        eventId: Int,
        target: RecordMutationController.Target
    ) {
        mutation.submit(target, RecordMutationController.Kind.DELETE, R.string.record_delete_failed) {
            repository.deleteEventRecordById(eventId)
            BabyRecordChangeBus.notifyChanged(target.babyId)
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
        data class Error(@param:StringRes val messageResId: Int) : ValidationResult()
    }
}
