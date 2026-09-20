package com.zero.babycare.statistics.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.zero.babycare.databinding.ItemStatisticsCalendarBinding
import com.zero.babycare.statistics.widget.BabyCalendarView
import com.zero.babycare.statistics.model.CalendarBrowseState
import java.time.LocalDate

class StatisticsCalendarAdapter(
    private val onDateSelected: (LocalDate) -> Unit,
    private val onBrowseStateChanged: (CalendarBrowseState) -> Unit,
    private val onTodayClick: () -> Unit
) : RecyclerView.Adapter<StatisticsCalendarAdapter.CalendarViewHolder>() {

    private var binding: ItemStatisticsCalendarBinding? = null
    private var pendingDates: Set<LocalDate>? = null
    private var pendingSelectedDate: LocalDate? = null
    private var pendingBrowseState: CalendarBrowseState? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CalendarViewHolder {
        val binding = ItemStatisticsCalendarBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CalendarViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CalendarViewHolder, position: Int) {
        val binding = holder.binding
        this.binding = binding

        binding.calendarView.setOnDateSelectedListener { date ->
            onDateSelected(date)
            updateCalendarTitle()
        }

        binding.calendarView.setOnModeChangedListener { mode ->
            updateExpandIndicator(mode, animate = true)
        }
        binding.calendarView.setOnBrowseStateChangedListener { state ->
            pendingBrowseState = state
            onBrowseStateChanged(state)
            updateCalendarTitle()
        }

        binding.ivPrevious.setOnClickListener {
            binding.calendarView.navigatePrevious()
            updateCalendarTitle()
        }

        binding.ivNext.setOnClickListener {
            binding.calendarView.navigateNext()
            updateCalendarTitle()
        }

        binding.tvToday.setOnClickListener {
            binding.calendarView.goToToday()
            onTodayClick()
            updateCalendarTitle()
        }

        binding.ivExpandIndicator.setOnClickListener {
            binding.calendarView.toggleViewMode()
        }

        pendingSelectedDate?.let {
            binding.calendarView.setSelectedDate(it, notify = false)
        }
        pendingBrowseState?.let(binding.calendarView::restoreBrowseState)
        pendingDates?.let { binding.calendarView.setDatesWithRecords(it) }
        updateExpandIndicator(binding.calendarView.getViewMode(), animate = false)
        updateCalendarTitle()
    }

    override fun onViewRecycled(holder: CalendarViewHolder) {
        if (binding === holder.binding) {
            binding = null
        }
    }

    override fun getItemCount(): Int = 1

    fun setDatesWithRecords(dates: Set<LocalDate>) {
        if (pendingDates == dates) return
        pendingDates = dates
        binding?.calendarView?.setDatesWithRecords(dates)
    }

    /** 日期未选变且记录点相同时，也要重画“今天”并更新日历的虚拟读屏节点。 */
    fun refreshClock() {
        binding?.calendarView?.setDatesWithRecords(pendingDates.orEmpty())
    }

    fun syncBrowseState(state: CalendarBrowseState) {
        if (pendingBrowseState == state && binding?.calendarView?.getBrowseState() == state) return
        pendingBrowseState = state
        binding?.calendarView?.restoreBrowseState(state)
        binding?.calendarView?.let { updateExpandIndicator(it.getViewMode(), animate = false) }
        updateCalendarTitle()
    }

    fun syncSelectedDate(date: LocalDate) {
        pendingSelectedDate = date
        val current = binding?.calendarView?.getSelectedDate()
        if (current != null && current != date) {
            binding?.calendarView?.setSelectedDate(date, notify = false)
            updateCalendarTitle()
        }
    }

    fun updateCalendarTitle() {
        val binding = binding ?: return
        binding.tvCalendarTitle.text = binding.calendarView.getFormattedTitle()
    }

    private fun updateExpandIndicator(mode: BabyCalendarView.ViewMode, animate: Boolean) {
        val binding = binding ?: return
        val rotation = when (mode) {
            BabyCalendarView.ViewMode.WEEK -> 90f
            BabyCalendarView.ViewMode.MONTH -> -90f
        }
        if (animate) {
            binding.ivExpandIndicator.animate()
                .rotation(rotation)
                .setDuration(200)
                .start()
        } else {
            binding.ivExpandIndicator.rotation = rotation
        }
    }

    class CalendarViewHolder(val binding: ItemStatisticsCalendarBinding) :
        RecyclerView.ViewHolder(binding.root)
}
