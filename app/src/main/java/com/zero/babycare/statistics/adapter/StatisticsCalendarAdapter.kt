package com.zero.babycare.statistics.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.zero.babycare.databinding.ItemStatisticsCalendarBinding
import com.zero.babycare.statistics.widget.BabyCalendarView
import java.time.LocalDate
import java.time.YearMonth

class StatisticsCalendarAdapter(
    private val onDateSelected: (LocalDate) -> Unit,
    private val onMonthChanged: (YearMonth) -> Unit,
    private val onModeChanged: (BabyCalendarView.ViewMode) -> Unit,
    private val onTodayClick: () -> Unit
) : RecyclerView.Adapter<StatisticsCalendarAdapter.CalendarViewHolder>() {

    private var binding: ItemStatisticsCalendarBinding? = null
    private var pendingDates: Set<LocalDate>? = null
    private var pendingSelectedDate: LocalDate? = null

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

        binding.calendarView.setOnMonthChangedListener { yearMonth ->
            onMonthChanged(yearMonth)
            updateCalendarTitle()
        }

        binding.calendarView.setOnModeChangedListener { mode ->
            onModeChanged(mode)
            updateExpandIndicator(mode, animate = true)
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
        pendingDates = dates
        binding?.calendarView?.setDatesWithRecords(dates)
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
