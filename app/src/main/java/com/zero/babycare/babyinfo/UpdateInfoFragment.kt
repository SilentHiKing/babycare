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
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentUpdateInfoBinding
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.entity.BabyInfo
import com.zero.common.ext.launchInLifecycle
import com.zero.common.util.CompatDateUtils
import com.zero.components.base.BaseFragment
import com.zero.components.base.util.DialogHelper
import com.zero.components.base.vm.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/**
 * 婴儿信息页面
 * 支持两种模式：
 * - 创建模式：首次使用，创建新宝宝
 * - 编辑模式：编辑现有宝宝信息
 */
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

    /** 当前编辑的宝宝信息（编辑模式时有值） */
    private var editingBaby: BabyInfo? = null

    /** 是否是编辑模式 */
    private val isEditMode: Boolean
        get() = editingBaby != null

    /** 是否是首次创建（没有任何宝宝数据） */
    private val isFirstCreate: Boolean
        get() = mainVm.getAllBabies().isEmpty()

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)

        // 日期选择
        binding.etBirthday.setOnClickListener {
            showTimePickerDialog()
        }

        // 性别选择
        binding.etGender.setOnClickListener {
            showGenderDialog()
        }

        // 血型选择
        binding.etBloodType.setOnClickListener {
            showBloodTypeDialog()
        }

        // Toolbar 配置
        setupToolbar()

        // 删除按钮
        binding.tvDelete.setOnClickListener {
            showDeleteConfirm()
        }
    }

    override fun initData(view: View, savedInstanceState: Bundle?) {
        super.initData(view, savedInstanceState)

        // 监听 UI 状态
        launchInLifecycle {
            vm.uiState.collect {
                when (it) {
                    is UiState.Loading -> showLoading()
                    is UiState.Success -> {
                        ToastUtils.showLong(com.zero.common.R.string.updateSuccess)
                        hideLoading()
                    }
                    is UiState.Error -> hideLoading()
                    else -> {}
                }
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // 页面显示时，刷新模式和数据
            refreshPageMode()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) {
            refreshPageMode()
        }
    }

    /**
     * 刷新页面模式
     * 根据导航参数决定是创建模式还是编辑模式
     */
    private fun refreshPageMode() {
        val navTarget = mainVm.navTarget.value
        
        when (navTarget) {
            is NavTarget.BabyInfo -> {
                when (navTarget.mode) {
                    NavTarget.BabyInfo.Mode.CREATE -> {
                        editingBaby = null
                        setupCreateMode()
                    }
                    NavTarget.BabyInfo.Mode.EDIT -> {
                        // 编辑模式：通过 babyId 获取要编辑的宝宝（不依赖 currentBaby）
                        val babyId = navTarget.babyId
                        editingBaby = if (babyId != null) {
                            mainVm.getBabyById(babyId)
                        } else {
                            null
                        }
                        
                        if (editingBaby != null) {
                            setupEditMode()
                        } else {
                            // 找不到宝宝，回退到创建模式
                            setupCreateMode()
                        }
                    }
                }
            }
            else -> {
                // 兼容旧的导航方式：根据是否有当前宝宝判断模式
                editingBaby = mainVm.getCurrentBabyInfo()
                if (isEditMode) {
                    setupEditMode()
                } else {
                    setupCreateMode()
                }
            }
        }
    }

    /**
     * 设置 Toolbar
     */
    private fun setupToolbar() {
        binding.toolbar.showBackButton { goBack() }
        binding.toolbar.setOnActionClickListener { saveBabyInfo() }
    }

    /**
     * 设置创建模式
     */
    private fun setupCreateMode() {
        // 标题
        binding.toolbar.title = StringUtils.getString(com.zero.common.R.string.create_baby)
        // 右侧按钮
        binding.toolbar.setActionText(StringUtils.getString(com.zero.common.R.string.finish))
        // 隐藏删除按钮
        binding.tvDelete.visibility = View.GONE
        // 返回按钮：首次创建时隐藏
        if (isFirstCreate) {
            binding.toolbar.hideLeftButton()
        } else {
            binding.toolbar.showBackButton { goBack() }
        }

        // 清空表单
        clearForm()
    }

    /**
     * 设置编辑模式
     */
    private fun setupEditMode() {
        val baby = editingBaby ?: return

        // 标题
        binding.toolbar.title = StringUtils.getString(com.zero.common.R.string.edit_baby_info)
        // 右侧按钮
        binding.toolbar.setActionText(StringUtils.getString(com.zero.common.R.string.save))
        // 显示删除按钮（如果不是唯一的宝宝）
        binding.tvDelete.visibility = if (mainVm.getAllBabies().size > 1) View.VISIBLE else View.GONE
        // 显示返回按钮
        binding.toolbar.showBackButton { goBack() }

        // 填充表单数据
        fillForm(baby)
    }

    /**
     * 清空表单
     */
    private fun clearForm() {
        binding.etName.setText("")
        binding.etBirthday.setText("")
        binding.etGender.setText("")
        binding.etWeight.setText("")
        binding.etHeight.setText("")
        binding.etBloodType.setText("")
    }

    /**
     * 填充表单数据
     */
    private fun fillForm(baby: BabyInfo) {
        binding.etName.setText(baby.name)
        binding.etGender.setText(baby.gender)

        if (baby.birthDate > 0) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            binding.etBirthday.setText(dateFormat.format(Date(baby.birthDate)))
        }

        if (baby.birthWeight > 0) {
            binding.etWeight.setText(baby.birthWeight.toString())
        }

        if (baby.birthHeight > 0) {
            binding.etHeight.setText(baby.birthHeight.toString())
        }

        if (baby.bloodType.isNotEmpty()) {
            binding.etBloodType.setText(baby.bloodType)
        }
    }

    /**
     * 保存宝宝信息
     */
    private fun saveBabyInfo() {
        val name = binding.etName.text.toString().trim()
        if (name.isEmpty()) {
            ToastUtils.showLong(com.zero.common.R.string.nameIsNullTip)
            return
        }

        val baby = editingBaby?.copy() ?: BabyInfo()
        baby.name = name
        baby.gender = binding.etGender.text.toString()

        binding.etBirthday.text?.toString()?.takeIf { it.isNotEmpty() }?.let {
            baby.birthDate = CompatDateUtils.stringToTimestamp(it) ?: 0L
        }

        binding.etWeight.text?.toString()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { it.toFloat() }.getOrNull()?.let { weight ->
                baby.birthWeight = weight
            }
        }

        binding.etHeight.text?.toString()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { it.toFloat() }.getOrNull()?.let { height ->
                baby.birthHeight = height
            }
        }

        baby.bloodType = binding.etBloodType.text.toString()

        if (isEditMode) {
            // 更新
            vm.update(baby) {
                LogUtils.d("update success")
                // 如果编辑的是当前选中的宝宝，更新缓存
                mainVm.updateCurrentBabyIfNeeded(baby)
                // 刷新侧边栏（如果编辑的是当前宝宝，侧边栏信息需要更新）
                (activity as? MainActivity)?.refreshDrawerBabyInfo()
                // 返回上一页
                goBack()
            }
        } else {
            // 新增
            val isFirstBaby = isFirstCreate  // 保存状态，因为 insert 后 isFirstCreate 会变化
            vm.insert(baby) {
                LogUtils.d("insert success")
                // 只有首次创建（之前没有任何宝宝）才设为当前宝宝
                // 非首次添加不改变当前宝宝
                if (isFirstBaby) {
                    mainVm.setCurrentBaby(baby)
                }
                // 刷新侧边栏
                (activity as? MainActivity)?.refreshDrawerBabyInfo()
                // 返回来源页面
                goBack()
            }
        }
    }

    /**
     * 显示删除确认弹窗
     */
    private fun showDeleteConfirm() {
        val baby = editingBaby ?: return

        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = StringUtils.getString(com.zero.common.R.string.delete_baby),
            content = StringUtils.getString(com.zero.common.R.string.delete_baby_confirm, baby.name),
            confirmText = StringUtils.getString(com.zero.common.R.string.confirm),
            cancelText = StringUtils.getString(com.zero.common.R.string.cancel),
            onConfirm = {
                mainVm.deleteBaby(baby) {
                    ToastUtils.showShort(com.zero.common.R.string.delete_success)
                    // 刷新侧边栏
                    (activity as? MainActivity)?.refreshDrawerBabyInfo()
                    // 检查是否还有其他宝宝
                    val remaining = mainVm.getAllBabies()
                    if (remaining.isEmpty()) {
                        // 没有宝宝了，进入创建模式
                        editingBaby = null
                        refreshPageMode()
                    } else {
                        // 切换到第一个宝宝
                        mainVm.setCurrentBaby(remaining.first())
                        goBack()
                    }
                }
            }
        )
    }

    /**
     * 返回上一页
     * 根据导航参数中的 returnTarget 决定返回目标
     */
    private fun goBack() {
        val navTarget = mainVm.navTarget.value
        val returnTarget = if (navTarget is NavTarget.BabyInfo) {
            navTarget.returnTarget
        } else {
            NavTarget.Dashboard
        }
        mainVm.navigateTo(returnTarget)
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

            override fun onCancel() {}
        })
        XPopup.Builder(requireContext())
            .asCustom(popup)
            .show()
    }

    private fun showBloodTypeDialog() {
        val popup = CommonPickerPopup(requireContext())
        val list = ArrayList<String?>()
        list.add(StringUtils.getString(com.zero.common.R.string.blood_type_a))
        list.add(StringUtils.getString(com.zero.common.R.string.blood_type_b))
        list.add(StringUtils.getString(com.zero.common.R.string.blood_type_ab))
        list.add(StringUtils.getString(com.zero.common.R.string.blood_type_o))
        list.add(StringUtils.getString(com.zero.common.R.string.blood_type_unknown))
        popup.setPickerData(list)
        popup.setCommonPickerListener(object : CommonPickerListener {
            override fun onItemSelected(index: Int, data: String?) {
                binding.etBloodType.setText(data)
            }

            override fun onCancel() {}
        })
        XPopup.Builder(requireContext())
            .asCustom(popup)
            .show()
    }

    private fun showTimePickerDialog() {
        val popup = TimePickerPopup(requireContext())
            .setMode(TimePickerPopup.Mode.YMDHMS)
            .setTimePickerListener(object : TimePickerListener {
                override fun onTimeChanged(date: Date?) {}

                override fun onTimeConfirm(date: Date, view: View?) {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    binding.etBirthday.setText(dateFormat.format(date))
                    LogUtils.d(date.toLocaleString())
                }

                override fun onCancel() {}
            })

        XPopup.Builder(requireContext())
            .asCustom(popup)
            .show()
    }
}
