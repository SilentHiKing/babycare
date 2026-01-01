package com.zero.babycare.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.StringUtils
import com.blankj.utilcode.util.ToastUtils
import com.lxj.xpopup.XPopup
import com.lxj.xpopupext.listener.CommonPickerListener
import com.lxj.xpopupext.popup.CommonPickerPopup
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentSettingsBinding
import com.zero.babycare.databinding.ItemSettingRowBinding
import com.zero.babycare.navigation.BackPressHandler
import com.zero.babycare.navigation.NavTarget
import com.zero.common.ext.launchInLifecycle
import com.zero.components.base.BaseFragment

/**
 * 设置页面
 * 展示基础偏好项，并通过 ViewModel 做持久化同步。
 */
class SettingsFragment : BaseFragment<FragmentSettingsBinding>(), BackPressHandler {

    companion object {
        fun create(): SettingsFragment = SettingsFragment()
    }

    private val vm by viewModels<SettingsViewModel>()
    private val mainVm by activityViewModels<MainViewModel>()

    private val reminderRow by lazy { binding.rowReminder }
    private val feedingUnitRow by lazy { binding.rowFeedingUnit }
    private val weightUnitRow by lazy { binding.rowWeightUnit }
    private val heightUnitRow by lazy { binding.rowHeightUnit }
    private val versionRow by lazy { binding.rowVersion }
    private val languageRow by lazy { binding.rowLanguage }
    private val backupRow by lazy { binding.rowBackup }
    private val familyRow by lazy { binding.rowFamily }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 通知权限结果决定是否真正开启提醒，避免无权限时“假开启”
            if (granted) {
                vm.setReminderEnabled(true)
            } else {
                bindReminderSwitch(false)
                ToastUtils.showShort(com.zero.common.R.string.settings_notification_permission_denied)
            }
        }

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        setupToolbar()
        setupRows()
        observeState()
    }

    /**
     * 初始化工具栏
     */
    private fun setupToolbar() {
        binding.toolbar.title = StringUtils.getString(com.zero.common.R.string.settings)
        binding.toolbar.showBackButton { handleBack() }
        binding.toolbar.hideAction()
    }

    /**
     * 初始化列表项与点击逻辑
     */
    private fun setupRows() {
        reminderRow.switchToggle.isChecked = vm.settingsState.value.reminderEnabled

        feedingUnitRow.tvSummary.visibility = View.GONE
        feedingUnitRow.root.setOnClickListener {
            showFeedingUnitPicker(vm.settingsState.value.feedingUnit)
        }

        weightUnitRow.tvSummary.visibility = View.GONE
        weightUnitRow.root.setOnClickListener {
            showWeightUnitPicker(vm.settingsState.value.weightUnit)
        }

        heightUnitRow.tvSummary.visibility = View.GONE
        heightUnitRow.root.setOnClickListener {
            showHeightUnitPicker(vm.settingsState.value.heightUnit)
        }

        versionRow.tvSummary.visibility = View.GONE
        versionRow.ivArrow.visibility = View.GONE
        versionRow.root.isClickable = false
        versionRow.root.isFocusable = false
        versionRow.tvValue.text = AppUtils.getAppVersionName()

        languageRow.tvSummary.visibility = View.GONE
        languageRow.root.setOnClickListener {
            showLanguagePicker(vm.settingsState.value.languageOption)
        }

        setupBackupRow()
        setupComingSoonRow(familyRow, com.zero.common.R.string.settings_family_share)

        bindRowTexts()
    }

    /**
     * 观察并渲染状态
     */
    private fun observeState() {
        launchInLifecycle {
            vm.settingsState.collect { state ->
                bindReminderSwitch(state.reminderEnabled)
                feedingUnitRow.tvValue.text = StringUtils.getString(state.feedingUnit.labelResId)
                weightUnitRow.tvValue.text = StringUtils.getString(state.weightUnit.labelResId)
                heightUnitRow.tvValue.text = StringUtils.getString(state.heightUnit.labelResId)
                languageRow.tvValue.text = StringUtils.getString(state.languageOption.labelResId)
                bindRowTexts()
            }
        }
    }

    /**
     * 同步 Switch 状态并避免重复触发
     */
    private fun bindReminderSwitch(checked: Boolean) {
        reminderRow.switchToggle.setOnCheckedChangeListener(null)
        reminderRow.switchToggle.isChecked = checked
        reminderRow.switchToggle.setOnCheckedChangeListener { _, isChecked ->
            handleReminderToggle(isChecked)
        }
    }

    /**
     * 处理提醒开关逻辑，必要时先申请通知权限
     */
    private fun handleReminderToggle(enabled: Boolean) {
        if (!enabled) {
            vm.setReminderEnabled(false)
            return
        }
        if (hasNotificationPermission()) {
            vm.setReminderEnabled(true)
            return
        }
        // 无权限时先恢复为关闭态，再引导系统权限弹窗
        bindReminderSwitch(false)
        requestNotificationPermission()
    }

    /**
     * Android 13+ 通知权限判断
     */
    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 触发通知权限申请
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            ToastUtils.showShort(com.zero.common.R.string.settings_notification_permission_rationale)
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * 喂养单位选择
     */
    private fun showFeedingUnitPicker(current: FeedingUnit) {
        showUnitPicker(
            options = FeedingUnit.values().toList(),
            current = current,
            onSelected = { vm.setFeedingUnit(it) }
        )
    }

    /**
     * 体重单位选择
     */
    private fun showWeightUnitPicker(current: WeightUnit) {
        showUnitPicker(
            options = WeightUnit.values().toList(),
            current = current,
            onSelected = { vm.setWeightUnit(it) }
        )
    }

    /**
     * 身高单位选择
     */
    private fun showHeightUnitPicker(current: HeightUnit) {
        showUnitPicker(
            options = HeightUnit.values().toList(),
            current = current,
            onSelected = { vm.setHeightUnit(it) }
        )
    }

    /**
     * 语言选择
     */
    private fun showLanguagePicker(current: LanguageOption) {
        val options = LanguageOption.values().toList()
        val labels = options.map { StringUtils.getString(it.labelResId) }

        val popup = CommonPickerPopup(requireContext())
        popup.setPickerData(ArrayList(labels))
        popup.setCommonPickerListener(object : CommonPickerListener {
            override fun onItemSelected(index: Int, data: String?) {
                val selected = options.getOrNull(index) ?: current
                if (selected == current) return
                vm.setLanguage(selected)
            }

            override fun onCancel() {}
        })

        XPopup.Builder(requireContext())
            .asCustom(popup)
            .show()
    }

    /**
     * 通用单位选择弹窗
     */
    private fun <T> showUnitPicker(
        options: List<T>,
        current: T,
        onSelected: (T) -> Unit
    ) where T : Enum<T> {
        val labels = ArrayList<String?>()
        options.forEach { option ->
            val labelResId = when (option) {
                is FeedingUnit -> option.labelResId
                is WeightUnit -> option.labelResId
                is HeightUnit -> option.labelResId
                else -> null
            }
            labels.add(labelResId?.let { StringUtils.getString(it) } ?: "")
        }

        val popup = CommonPickerPopup(requireContext())
        popup.setPickerData(labels)
        popup.setCommonPickerListener(object : CommonPickerListener {
            override fun onItemSelected(index: Int, data: String?) {
                val selected = options.getOrNull(index) ?: current
                onSelected(selected)
            }

            override fun onCancel() {}
        })

        XPopup.Builder(requireContext())
            .asCustom(popup)
            .show()
    }

    override fun onSystemBackPressed(): Boolean {
        handleBack()
        return true
    }

    private fun handleBack() {
        mainVm.navigateTo(getReturnTarget())
    }

    private fun getReturnTarget(): NavTarget {
        return (mainVm.navTarget.value as? NavTarget.Settings)?.returnTarget ?: NavTarget.Dashboard
    }

    /**
     * 通用占位项
     */
    private fun setupComingSoonRow(row: ItemSettingRowBinding, titleResId: Int) {
        row.tvTitle.text = StringUtils.getString(titleResId)
        row.tvSummary.text = StringUtils.getString(com.zero.common.R.string.settings_feature_coming_soon)
        row.tvSummary.visibility = View.VISIBLE
        row.tvValue.text = ""
        row.root.setOnClickListener { showComingSoonToast() }
    }

    private fun showComingSoonToast() {
        ToastUtils.showShort(com.zero.common.R.string.settings_feature_coming_soon)
    }

    /**
     * 数据备份入口
     */
    private fun setupBackupRow() {
        backupRow.tvTitle.text = StringUtils.getString(com.zero.common.R.string.settings_backup)
        backupRow.tvSummary.text = StringUtils.getString(com.zero.common.R.string.settings_backup_summary)
        backupRow.tvSummary.visibility = View.VISIBLE
        backupRow.tvValue.text = ""
        backupRow.root.setOnClickListener {
            mainVm.navigateTo(NavTarget.Backup(returnTarget = NavTarget.Settings(returnTarget = getReturnTarget())))
        }
    }

    /**
     * 统一刷新行文案，保证语言切换后即时更新
     */
    private fun bindRowTexts() {
        binding.tvGeneralTitle.text = StringUtils.getString(com.zero.common.R.string.settings_general)
        binding.tvReminderTitle.text = StringUtils.getString(com.zero.common.R.string.settings_reminder)
        binding.tvAboutTitle.text = StringUtils.getString(com.zero.common.R.string.settings_about)
        binding.tvMoreTitle.text = StringUtils.getString(com.zero.common.R.string.settings_more)

        reminderRow.tvTitle.text = StringUtils.getString(com.zero.common.R.string.settings_reminder_switch_title)
        reminderRow.tvSummary.text = StringUtils.getString(com.zero.common.R.string.settings_reminder_switch_summary)
        feedingUnitRow.tvTitle.text = StringUtils.getString(com.zero.common.R.string.settings_unit_feeding_title)
        weightUnitRow.tvTitle.text = StringUtils.getString(com.zero.common.R.string.settings_unit_weight_title)
        heightUnitRow.tvTitle.text = StringUtils.getString(com.zero.common.R.string.settings_unit_height_title)
        versionRow.tvTitle.text = StringUtils.getString(com.zero.common.R.string.settings_about_version)
        languageRow.tvTitle.text = StringUtils.getString(com.zero.common.R.string.settings_language)
        backupRow.tvTitle.text = StringUtils.getString(com.zero.common.R.string.settings_backup)
        backupRow.tvSummary.text = StringUtils.getString(com.zero.common.R.string.settings_backup_summary)
        backupRow.tvSummary.visibility = View.VISIBLE
        setupComingSoonRow(familyRow, com.zero.common.R.string.settings_family_share)
    }
}
