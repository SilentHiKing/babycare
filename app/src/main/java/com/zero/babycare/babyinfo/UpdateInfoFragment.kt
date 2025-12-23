package com.zero.babycare.babyinfo

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.StringUtils
import com.blankj.utilcode.util.ToastUtils
import com.lxj.xpopup.XPopup
import com.lxj.xpopupext.listener.CommonPickerListener
import com.lxj.xpopupext.listener.TimePickerListener
import com.lxj.xpopupext.popup.CommonPickerPopup
import com.lxj.xpopupext.popup.TimePickerPopup
import com.zero.babycare.MainViewModel
import com.zero.babycare.R
import com.zero.babycare.databinding.FragmentUpdateInfoBinding
import com.zero.babycare.home.DashboardFragment
import com.zero.babydata.entity.BabyInfo
import com.zero.common.ext.launchInLifecycle
import com.zero.common.util.CompatDateUtils
import com.zero.components.base.BaseFragment
import com.zero.components.base.vm.UiState
import java.util.Date


class UpdateInfoFragment : BaseFragment<FragmentUpdateInfoBinding>() {
    companion object {
        fun create(): UpdateInfoFragment {
            return UpdateInfoFragment()
        }
    }

    private val vm by viewModels<UpdateInfoViewModel>()
    private val mainVm: MainViewModel by lazy {
        activityViewModels<MainViewModel>().value
    }

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        binding.etBirthday.setOnClickListener {
            showTimePickerDialog()
        }
        binding.etGender.setOnClickListener {
            showGenderDialog()
        }
        binding.tvFinish.setOnClickListener {
            val baby = BabyInfo()
            val name = binding.etName.text.toString().trim()
            if (name.isEmpty()) {
                ToastUtils.showLong(com.zero.common.R.string.nameIsNullTip)
                return@setOnClickListener
            }
            baby.name = name
            baby.gender = binding.etGender.text.toString()
            binding.etBirthday.text?.toString()?.takeIf { it.isNotEmpty() }?.let {
                baby.birthDate = CompatDateUtils.stringToTimestamp(it) ?: 0L
            }
            binding.etHeight.text?.toString()?.takeIf { it.isNotEmpty() }?.let {
                it.toFloat().also { baby.birthHeight = it }
            }
            binding.etWeight.text?.toString()?.takeIf { it.isNotEmpty() }?.let {
                it.toFloat().also { baby.birthWeight = it }
            }
            vm.insert(baby) {
                LogUtils.d("insert success")
                mainVm.switchFragment(DashboardFragment::class.java)
            }
        }
    }

    override fun initData(view: View, savedInstanceState: Bundle?) {
        super.initData(view, savedInstanceState)
        launchInLifecycle {
            vm.uiState.collect {
                when (it) {
                    is UiState.Loading -> {
                        showLoading()
                    }

                    is UiState.Success -> {
                        ToastUtils.showLong(com.zero.common.R.string.updateSuccess)
                        hideLoading()
                    }

                    is UiState.Error -> {
                        hideLoading()
                    }

                    else -> {
                    }
                }
            }
        }


    }

    private fun showGenderDialog() {
        val popup = CommonPickerPopup(requireContext())
        val list = ArrayList<String?>()
        list.add(StringUtils.getString(com.zero.common.R.string.boy))
        list.add(StringUtils.getString(com.zero.common.R.string.girl))
        popup.setPickerData(list)
        popup.setCommonPickerListener(object : CommonPickerListener {
            override fun onItemSelected(index: Int, data: String?) {
                binding.etGender.setText(data)
            }

            override fun onCancel() {

            }
        })
        XPopup.Builder(requireContext())
            .asCustom(popup)
            .show()
    }

    private fun showTimePickerDialog() {

        val popup = TimePickerPopup(requireContext())
            .setMode(TimePickerPopup.Mode.YMDHMS)
            .setTimePickerListener(object : TimePickerListener {
                override fun onTimeChanged(date: Date?) {
                }

                override fun onTimeConfirm(date: Date, view: View?) {
                    binding.etBirthday.setText(date.toLocaleString())
                    LogUtils.d(date.toLocaleString())
                }

                override fun onCancel() {
                }
            })

        XPopup.Builder(requireContext())
            .asCustom(popup)
            .show()
    }


}