package com.zero.babycare.babyinfo

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ToastUtils
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentUpdateInfoBinding
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.navigation.BackPressHandler
import com.zero.babydata.entity.BabyInfo
import com.zero.common.ext.launchInLifecycle
import com.zero.common.util.BabyGender
import com.zero.common.util.CompatDateUtils
import com.zero.components.base.BaseFragment
import com.zero.components.base.util.DialogHelper
import com.zero.components.base.vm.UiState
import com.zero.components.widget.ToolbarAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/**
 * 婴儿信息页面
 * 支持两种模式：
 * - 创建模式：首次使用，创建新宝宝
 * - 编辑模式：编辑现有宝宝信息
 */
class UpdateInfoFragment : BaseFragment<FragmentUpdateInfoBinding>(), BackPressHandler {
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

    /** 表单内部保存稳定性别 code，展示文案只在 UI 层按当前语言解析。 */
    private var selectedGenderCode: String = ""

    /** 是否是编辑模式 */
    private val isEditMode: Boolean
        get() = editingBaby != null

    /** 是否是首次创建（没有任何宝宝数据） */
    private val isFirstCreate: Boolean
        get() = mainVm.getAllBabies().isEmpty()

    /** 当前 Toolbar 主操作文案，随创建/编辑模式切换。 */
    private var primaryActionText: String = ""

    /** 页面进入某个模式后的初始表单值，用于判断编辑态是否真的发生改动。 */
    private var initialSnapshot = FormSnapshot()

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)

        // 日期选择
        binding.rowBirthday.setOnClickListener {
            showBirthdayPickerSheet()
        }
        binding.etBirthday.setOnClickListener { showBirthdayPickerSheet() }

        // 性别选择
        binding.rowGender.setOnClickListener {
            showGenderDialog()
        }
        binding.etGender.setOnClickListener { showGenderDialog() }

        // 血型选择
        binding.rowBloodType.setOnClickListener {
            showBloodTypeDialog()
        }
        binding.etBloodType.setOnClickListener { showBloodTypeDialog() }

        binding.rowName.setOnClickListener {
            focusNameInput()
        }
        bindFormWatchers()

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

        launchInLifecycle {
            vm.unitState.collect { unitState ->
                bindUnitLabels(unitState)
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
        vm.refreshUnitState()
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
    }

    /**
     * 设置创建模式
     */
    private fun setupCreateMode() {
        // 标题
        binding.toolbar.title = getString(com.zero.common.R.string.create_baby)
        // 右侧按钮
        setPrimaryAction(getString(com.zero.common.R.string.finish))
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
        bindCreateHeader()
        captureInitialFormState()
        refreshPrimaryActionState()
    }

    /**
     * 设置编辑模式
     */
    private fun setupEditMode() {
        val baby = editingBaby ?: return

        // 标题
        binding.toolbar.title = getString(com.zero.common.R.string.edit_baby_info)
        // 右侧按钮
        setPrimaryAction(getString(com.zero.common.R.string.save))
        // 显示删除按钮（如果不是唯一的宝宝）
        binding.tvDelete.visibility = if (mainVm.getAllBabies().size > 1) View.VISIBLE else View.GONE
        // 显示返回按钮
        binding.toolbar.showBackButton { goBack() }

        // 填充表单数据
        fillForm(baby)
        bindEditHeader(baby)
        captureInitialFormState()
        refreshPrimaryActionState()
    }

    /**
     * 清空表单
     */
    private fun clearForm() {
        selectedGenderCode = ""
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
        selectedGenderCode = BabyGender.normalize(baby.gender)
        binding.etGender.setText(getGenderLabel(selectedGenderCode))

        if (baby.birthDate > 0) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            binding.etBirthday.setText(dateFormat.format(Date(baby.birthDate)))
        } else {
            binding.etBirthday.setText("")
        }

        if (baby.birthWeight > 0) {
            binding.etWeight.setText(vm.formatBirthWeight(baby.birthWeight))
        } else {
            binding.etWeight.setText("")
        }

        if (baby.birthHeight > 0) {
            binding.etHeight.setText(vm.formatBirthHeight(baby.birthHeight))
        } else {
            binding.etHeight.setText("")
        }

        if (baby.bloodType.isNotEmpty()) {
            binding.etBloodType.setText(baby.bloodType)
        } else {
            binding.etBloodType.setText("")
        }
    }

    /**
     * 设置工具栏主操作按钮（单动作也统一走列表能力）
     */
    private fun setPrimaryAction(text: String, enabled: Boolean = true) {
        primaryActionText = text
        binding.toolbar.setActions(
            listOf(ToolbarAction(text = text, enabled = enabled))
        ) { saveBabyInfo() }
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
        baby.gender = selectedGenderCode

        // 编辑页允许把非必填出生信息清空，保存时需要同步清掉旧值。
        val birthdayText = binding.etBirthday.text?.toString()?.trim().orEmpty()
        baby.birthDate = birthdayText
            .takeIf { it.isNotEmpty() }
            ?.let { CompatDateUtils.stringToTimestamp(it) }
            ?: 0L

        val weightText = binding.etWeight.text?.toString()?.trim().orEmpty()
        baby.birthWeight = weightText
            .takeIf { it.isNotEmpty() }
            ?.let { vm.parseBirthWeightToStorage(it) }
            ?: 0f

        val heightText = binding.etHeight.text?.toString()?.trim().orEmpty()
        baby.birthHeight = heightText
            .takeIf { it.isNotEmpty() }
            ?.let { vm.parseBirthHeightToStorage(it) }
            ?: 0f

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
            title = getString(com.zero.common.R.string.delete_baby),
            content = getString(com.zero.common.R.string.delete_baby_confirm, baby.name),
            confirmText = getString(com.zero.common.R.string.confirm),
            cancelText = getString(com.zero.common.R.string.cancel),
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
    override fun onSystemBackPressed(): Boolean {
        goBack()
        return true
    }

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
        DialogHelper.showChoiceSheet(
            context = requireContext(),
            title = getString(com.zero.common.R.string.babyGender),
            options = listOf(
                DialogHelper.PickerOption(BabyGender.BOY, getString(com.zero.common.R.string.boy)),
                DialogHelper.PickerOption(BabyGender.GIRL, getString(com.zero.common.R.string.girl))
            ),
            selectedValue = selectedGenderCode.takeIf { it.isNotBlank() },
            onSelected = { code ->
                selectedGenderCode = code
                binding.etGender.setText(getGenderLabel(code))
                refreshPrimaryActionState()
            }
        )
    }

    private fun getGenderLabel(code: String): String {
        return BabyGender.labelResId(code)?.let { getString(it) }.orEmpty()
    }

    private fun showBloodTypeDialog() {
        val labels = listOf(
            getString(com.zero.common.R.string.blood_type_a),
            getString(com.zero.common.R.string.blood_type_b),
            getString(com.zero.common.R.string.blood_type_ab),
            getString(com.zero.common.R.string.blood_type_o),
            getString(com.zero.common.R.string.blood_type_unknown)
        )
        DialogHelper.showChoiceSheet(
            context = requireContext(),
            title = getString(com.zero.common.R.string.babyBloodType),
            options = labels.map { DialogHelper.PickerOption(it, it) },
            selectedValue = binding.etBloodType.text?.toString()?.takeIf { it.isNotBlank() },
            onSelected = {
                binding.etBloodType.setText(it)
                refreshPrimaryActionState()
            }
        )
    }

    /**
     * 出生体重/身高右侧单位跟随设置页，输入框里的值由 ViewModel 按同一单位换算。
     */
    private fun bindUnitLabels(unitState: BabyInfoUnitState) {
        binding.tvWeightUnit.text = getString(unitState.weightUnitLabelResId)
        binding.tvHeightUnit.text = getString(unitState.heightUnitLabelResId)
    }

    private fun showBirthdayPickerSheet() {
        val initialTime = binding.etBirthday.text?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { CompatDateUtils.stringToTimestamp(it) }
            ?: editingBaby?.birthDate?.takeIf { it > 0L }
            ?: System.currentTimeMillis()

        DialogHelper.showDateTimeSheet(
            context = requireContext(),
            title = getString(com.zero.common.R.string.babyBirthday),
            initialTime = initialTime,
            mode = DialogHelper.DateTimeMode.DATE_TIME,
            maxTime = System.currentTimeMillis(),
            onConfirm = { timestamp ->
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                binding.etBirthday.setText(dateFormat.format(Date(timestamp)))
                refreshPrimaryActionState()
            }
        )
    }

    private fun bindCreateHeader() {
        binding.tvProfileTitle.text = getString(com.zero.common.R.string.baby_profile_new_title)
        binding.tvProfileSubtitle.text = getString(com.zero.common.R.string.baby_profile_create_subtitle)
        binding.ivProfileAvatar.setImageResource(com.zero.common.R.drawable.ic_baby_default)
    }

    private fun bindEditHeader(baby: BabyInfo) {
        binding.tvProfileTitle.text = baby.name
        val gender = getGenderLabel(BabyGender.normalize(baby.gender))
        val birthday = baby.birthDate
            .takeIf { it > 0L }
            ?.let { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it)) }
            .orEmpty()
        binding.tvProfileSubtitle.text = listOf(gender, birthday)
            .filter { it.isNotBlank() }
            .joinToString(getString(com.zero.common.R.string.list_separator_dot))
            .ifBlank { getString(com.zero.common.R.string.baby_profile_create_subtitle) }
        binding.ivProfileAvatar.setImageResource(com.zero.common.R.drawable.ic_baby_default)
    }

    private fun bindFormWatchers() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                refreshPrimaryActionState()
            }
        }
        binding.etName.addTextChangedListener(watcher)
        binding.etBirthday.addTextChangedListener(watcher)
        binding.etWeight.addTextChangedListener(watcher)
        binding.etHeight.addTextChangedListener(watcher)
        binding.etBloodType.addTextChangedListener(watcher)
    }

    private fun focusNameInput() {
        binding.etName.requestFocus()
        binding.etName.setSelection(binding.etName.text?.length ?: 0)
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etName, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun captureInitialFormState() {
        initialSnapshot = currentSnapshot()
    }

    private fun currentSnapshot(): FormSnapshot {
        return FormSnapshot(
            name = binding.etName.text?.toString().orEmpty(),
            birthday = binding.etBirthday.text?.toString().orEmpty(),
            genderCode = selectedGenderCode,
            weight = binding.etWeight.text?.toString().orEmpty(),
            height = binding.etHeight.text?.toString().orEmpty(),
            bloodType = binding.etBloodType.text?.toString().orEmpty()
        )
    }

    /**
     * 创建态只要求姓名非空；编辑态要求姓名非空且表单确实变更。
     * 这样 Toolbar 动作承担提交状态，不再靠字段边框制造“选中态”。
     */
    private fun refreshPrimaryActionState() {
        if (primaryActionText.isBlank()) return
        val hasName = binding.etName.text?.toString()?.trim()?.isNotEmpty() == true
        val hasChanges = currentSnapshot() != initialSnapshot
        val enabled = hasName && (!isEditMode || hasChanges)
        setPrimaryAction(primaryActionText, enabled)
    }

    private data class FormSnapshot(
        val name: String = "",
        val birthday: String = "",
        val genderCode: String = "",
        val weight: String = "",
        val height: String = "",
        val bloodType: String = ""
    )
}
