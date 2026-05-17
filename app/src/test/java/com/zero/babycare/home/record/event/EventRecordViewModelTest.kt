package com.zero.babycare.home.record.event

import com.zero.babydata.entity.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventRecordViewModelTest {

    @Test
    fun `page event time does not create duration start time`() {
        val viewModel = EventRecordViewModel()

        viewModel.setEventTime(START_AT)

        assertEquals(START_AT, viewModel.eventTime.value)
        assertNull(viewModel.durationStartTime.value)
    }

    @Test
    fun `selecting another duration subtype clears previous duration end time`() {
        durationSubtypes().forEach { previousSubtype ->
            durationSubtypes()
                .filterNot { it.type == previousSubtype.type }
                .forEach { nextSubtype ->
                    val viewModel = EventRecordViewModel()

                    viewModel.selectSubtype(previousSubtype)
                    viewModel.setDurationStartTime(START_AT)
                    viewModel.setEndTime(START_AT + TEN_MINUTES)
                    viewModel.selectSubtype(nextSubtype)

                    assertNull(
                        "duration start should be cleared when switching ${previousSubtype.type} to ${nextSubtype.type}",
                        viewModel.durationStartTime.value
                    )
                    assertNull(
                        "duration end should be cleared when switching ${previousSubtype.type} to ${nextSubtype.type}",
                        viewModel.endTime.value
                    )
                }
        }
    }

    @Test
    fun `duration state is already cleared when new duration subtype is emitted`() {
        val viewModel = EventRecordViewModel()
        val observed = mutableListOf<ObservedSubtypeState>()
        val outdoor = requireNotNull(EventSubtype.fromType(EventType.ACTIVITY_OUTDOOR))
        val tummyTime = requireNotNull(EventSubtype.fromType(EventType.ACTIVITY_TUMMY_TIME))
        val collectionJob = CoroutineScope(Dispatchers.Unconfined).launch {
            viewModel.selectedSubtype.drop(1).collect { subtype ->
                if (subtype != null) {
                    // Fragment 在收到 selectedSubtype 后会立即重建详情区并读取计时状态。
                    observed += ObservedSubtypeState(
                        type = subtype.type,
                        startTime = viewModel.durationStartTime.value,
                        endTime = viewModel.endTime.value
                    )
                }
            }
        }

        viewModel.selectSubtype(outdoor)
        viewModel.setDurationStartTime(START_AT)
        viewModel.setEndTime(START_AT + TEN_MINUTES)
        viewModel.selectSubtype(tummyTime)

        collectionJob.cancel()
        assertEquals(
            ObservedSubtypeState(
                type = EventType.ACTIVITY_TUMMY_TIME,
                startTime = null,
                endTime = null
            ),
            observed.last()
        )
    }

    @Test
    fun `selecting another category clears previous duration end time`() {
        val viewModel = EventRecordViewModel()
        val outdoor = requireNotNull(EventSubtype.fromType(EventType.ACTIVITY_OUTDOOR))

        viewModel.selectCategory(EventCategory.ACTIVITY)
        viewModel.selectSubtype(outdoor)
        viewModel.setDurationStartTime(START_AT)
        viewModel.setEndTime(START_AT + TEN_MINUTES)
        viewModel.selectCategory(EventCategory.OTHER)

        assertNull(viewModel.durationStartTime.value)
        assertNull(viewModel.endTime.value)
    }

    private companion object {
        private const val START_AT = 1_700_000_000_000L
        private const val TEN_MINUTES = 10 * 60 * 1000L

        private data class ObservedSubtypeState(
            val type: Int,
            val startTime: Long?,
            val endTime: Long?
        )

        private fun durationSubtypes(): List<EventSubtype> {
            val durationSubtypes = EventCategory.entries
                .flatMap { category -> EventSubtype.getSubtypes(category) }
                .filter { subtype -> EventType.hasDuration(subtype.type) }

            assertTrue(
                "Duration subtype coverage should include at least two types for switch isolation",
                durationSubtypes.size > 1
            )
            return durationSubtypes
        }
    }
}
