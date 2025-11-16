package com.zero.babycare.home.record

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.StringUtils
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentFeedingRecordBinding
import com.zero.babycare.home.DashboardFragment
import com.zero.babycare.home.bean.DashboardEntity
import com.zero.babydata.entity.FeedingRecord
import com.zero.common.ext.launchInLifecycle
import com.zero.common.util.DateUtils
import com.zero.common.util.DateUtils.parseToTimestamp
import com.zero.common.util.DateUtils.timestampToMMddHHmmss
import com.zero.components.base.BaseFragment
import com.zero.components.base.vm.UiState
import com.zero.components.widget.RecordView
import com.zero.components.widget.RecordView.RecordState

class FeedingRecordFragment : BaseFragment<FragmentFeedingRecordBinding>() {
    companion object {
        fun create(): FeedingRecordFragment {
            return FeedingRecordFragment()
        }
    }

    private val vm by viewModels<FeedingRecordViewModel>()
    private val mainVm: MainViewModel by lazy {
        activityViewModels<MainViewModel>().value
    }


    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        binding.btn.title = StringUtils.getString(com.zero.common.R.string.feeding)
        binding.btn.setOnFinishListener {
            if (binding.rvCounter.currentShowState == RecordState.RECORDING) {
                binding.rvCounter.performClick()
            }

            val feedingRecord = FeedingRecord().apply {
                createdAt = System.currentTimeMillis()
                babyId = mainVm.getCurrentBabyInfo()?.babyId ?: -1
                feedingStart = parseToTimestamp(binding.etStartTime.text.toString())
                feedingEnd = parseToTimestamp(binding.etEndTime.text.toString())
                var duration = binding.rvCounter.getDuration()
                duration =
                    if (duration > (feedingEnd - feedingStart)) feedingEnd - feedingStart else duration
                feedingDuration = duration
            }

            vm.insert(feedingRecord) {
                LogUtils.d("feedingRecord success")
                mainVm.switchFragment(DashboardFragment::class.java)
            }

        }


        binding.etStartTime.setOnTimeEnteredListener { hour, minute ->
            binding.etEndTime.setText("")
            binding.rvCounter.reset()
        }
        binding.rvCounter.statusChange = { current, next ->
            if (current == RecordState.INIT) {

                DateUtils.getDiffFromNow(binding.etStartTime.text.toString().trim())?.let {
                    binding.rvCounter.setPauseOffset(it)
                }
                binding.rvCounter.post {
                    binding.etStartTime.setText(binding.rvCounter.getStartTimeStr())

                }
            }
            if (next == RecordState.RECORDING) {
                binding.etEndTime.setText("")
            }
            if (next == RecordState.PAUSE) {
                binding.etEndTime.setText(timestampToMMddHHmmss(System.currentTimeMillis()))
            }

        }
    }

    override fun initData(view: View, savedInstanceState: Bundle?) {
        super.initData(view, savedInstanceState)
        launchInLifecycle {
            vm.uiState.collect {
                when (it) {
                    is UiState.Success -> {
                        (it.data as? List<DashboardEntity>)?.let { list ->
                        }
                    }

                    else -> {
                    }
                }
            }
        }


    }
}