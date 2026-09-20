package com.zero.babycare.home.record.event

import androidx.lifecycle.ViewModelStore
import com.zero.babycare.home.record.RecordMutationController
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.entity.EventType
import com.zero.common.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证错误定位仍由 VM 决定，字段校验不会启动或占用数据库写入。 */
class EventFieldValidationTest {
    @Test
    fun missingDetailsReportTheirCorrectFieldWithoutStartingMutation() = withModel { vm ->
        val examples = listOf(
            Triple(EventType.GROWTH_WEIGHT, EventRecordViewModel.ValidationField.GROWTH_VALUE, R.string.event_value_required),
            Triple(EventType.HEALTH_TEMPERATURE, EventRecordViewModel.ValidationField.TEMPERATURE, R.string.temperature_value_required),
            Triple(EventType.HEALTH_MEDICINE, EventRecordViewModel.ValidationField.MEDICINE_NAME, R.string.medicine_name_hint),
            Triple(EventType.HEALTH_VACCINE, EventRecordViewModel.ValidationField.VACCINE_NAME, R.string.vaccine_name_hint),
            Triple(EventType.HEALTH_SYMPTOM, EventRecordViewModel.ValidationField.SYMPTOM, R.string.symptom_hint),
            Triple(EventType.MILESTONE_CUSTOM, EventRecordViewModel.ValidationField.MILESTONE_NAME, R.string.milestone_name_hint),
            Triple(EventType.OTHER_CUSTOM, EventRecordViewModel.ValidationField.CUSTOM_EVENT_NAME, R.string.custom_event_required)
        )
        examples.forEach { (type, field, message) ->
            vm.selectSubtype(requireNotNull(EventSubtype.fromType(type)))
            vm.saveRecord(target)
            val result = requireNotNull(vm.fieldValidation.value)
            assertEquals(field, result.field)
            assertEquals(message, result.messageResId)
            assertEquals(RecordMutationController.State.Idle, vm.mutation.state.value)
        }
    }

    @Test
    fun repeatedSaveRequestsRelocateWhileCorrectionsAndTypeChangesClearOnlyRelevantFeedback() = withModel { vm ->
        vm.selectSubtype(requireNotNull(EventSubtype.fromType(EventType.HEALTH_MEDICINE)))
        vm.saveRecord(target)
        val initial = requireNotNull(vm.fieldValidation.value)
        vm.clearFieldValidation(EventRecordViewModel.ValidationField.GROWTH_VALUE)
        assertEquals(initial, vm.fieldValidation.value)
        vm.saveRecord(target)
        assertTrue(requireNotNull(vm.fieldValidation.value).requestId > initial.requestId)
        vm.clearFieldValidation(EventRecordViewModel.ValidationField.MEDICINE_NAME)
        assertNull(vm.fieldValidation.value)

        vm.saveRecord(target)
        vm.selectSubtype(requireNotNull(EventSubtype.fromType(EventType.HEALTH_TEMPERATURE)))
        assertNull(vm.fieldValidation.value)
        vm.saveRecord(target)
        vm.reset()
        assertNull(vm.fieldValidation.value)
    }

    private fun withModel(block: (EventRecordViewModel) -> Unit) {
        val store = ViewModelStore()
        val vm = EventRecordViewModel()
        store.put("validation", vm)
        try { block(vm) } finally { store.clear() }
    }

    private val target = RecordMutationController.Target(
        "validation-draft", 1, null, NavTarget.EventRecord(), NavTarget.Dashboard
    )
}
