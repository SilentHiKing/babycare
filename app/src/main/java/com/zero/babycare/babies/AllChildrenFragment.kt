package com.zero.babycare.babies

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentAllChildrenBinding
import com.zero.babycare.databinding.ItemBabyCardBinding
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.navigation.BackPressHandler
import com.zero.babydata.entity.BabyInfo
import com.zero.common.util.BabyGender
import com.zero.components.base.BaseFragment
import com.zero.components.base.util.DialogHelper
import com.zero.components.widget.ToolbarAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 所有宝宝列表页面
 * 支持查看、切换、编辑、删除宝宝
 */
class AllChildrenFragment : BaseFragment<FragmentAllChildrenBinding>(), BackPressHandler {

    companion object {
        fun create(): AllChildrenFragment {
            return AllChildrenFragment()
        }
    }

    private val mainVm: MainViewModel by lazy {
        activityViewModels<MainViewModel>().value
    }

    private val adapter by lazy {
        BabyListAdapter()
    }

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)

        // Toolbar 配置
        binding.toolbar.showBackButton {
            mainVm.navigateTo(NavTarget.Dashboard)
        }
        binding.toolbar.setActions(
            listOf(
                ToolbarAction(
                    iconRes = com.zero.common.R.drawable.ic_add,
                    contentDescription = localizedString(com.zero.common.R.string.add_baby)
                )
            )
        ) { goToAddBaby() }

        // 空状态添加按钮
        binding.tvAddBaby.setOnClickListener {
            goToAddBaby()
        }

        // 设置 RecyclerView
        binding.rvBabies.adapter = adapter

        // 点击切换宝宝
        adapter.setOnItemClickListener { _, _, position ->
            val baby = adapter.getItem(position) ?: return@setOnItemClickListener
            switchToBaby(baby)
        }
    }

    override fun initData(view: View, savedInstanceState: Bundle?) {
        super.initData(view, savedInstanceState)
        loadBabies()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            loadBabies()
        }
    }

    /**
     * 加载宝宝列表
     */
    private fun loadBabies() {
        val babies = mainVm.getAllBabies()
        val currentBaby = mainVm.getCurrentBabyInfo()

        if (babies.isEmpty()) {
            binding.rvBabies.visibility = View.GONE
            binding.llEmpty.visibility = View.VISIBLE
        } else {
            binding.rvBabies.visibility = View.VISIBLE
            binding.llEmpty.visibility = View.GONE
            adapter.setCurrentBabyId(currentBaby?.babyId ?: -1)
            adapter.submitList(babies)
        }
    }

    override fun onSystemBackPressed(): Boolean {
        mainVm.navigateTo(NavTarget.Dashboard)
        return true
    }

    /**
     * 切换到指定宝宝
     */
    private fun switchToBaby(baby: BabyInfo) {
        (activity as? MainActivity)?.switchBabyAndUpdateTheme(baby)
        mainVm.navigateTo(NavTarget.Dashboard)
    }

    /**
     * 进入添加宝宝页面
     */
    private fun goToAddBaby() {
        mainVm.navigateTo(NavTarget.BabyInfo.create(returnTarget = NavTarget.AllChildren))
    }

    /**
     * 进入编辑宝宝页面
     * 注意：编辑宝宝不改变当前选中的宝宝，只通过 babyId 传递要编辑的宝宝
     */
    private fun goToEditBaby(baby: BabyInfo) {
        mainVm.navigateTo(NavTarget.BabyInfo.edit(baby.babyId, returnTarget = NavTarget.AllChildren))
    }

    /**
     * 删除宝宝
     */
    private fun deleteBaby(baby: BabyInfo) {
        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = localizedString(com.zero.common.R.string.delete_baby),
            content = localizedString(com.zero.common.R.string.delete_baby_confirm, baby.name),
            confirmText = localizedString(com.zero.common.R.string.confirm),
            cancelText = localizedString(com.zero.common.R.string.cancel),
            onConfirm = {
                mainVm.deleteBaby(baby) {
                    loadBabies()
                    (activity as? MainActivity)?.refreshDrawerBabyInfo()
                }
            }
        )
    }

    /**
     * 使用当前 Fragment context 获取资源，保证应用内语言切换后列表入口文案同步刷新。
     */
    private fun localizedString(@StringRes resId: Int, vararg args: Any): String {
        return if (args.isEmpty()) getString(resId) else getString(resId, *args)
    }

    /**
     * 宝宝列表适配器
     */
    inner class BabyListAdapter : BaseQuickAdapter<BabyInfo, BabyListAdapter.VH>() {

        private var currentBabyId: Int = -1
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        fun setCurrentBabyId(id: Int) {
            currentBabyId = id
        }

        inner class VH(
            parent: ViewGroup,
            val binding: ItemBabyCardBinding = ItemBabyCardBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        ) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): VH {
            return VH(parent)
        }

        override fun onBindViewHolder(holder: VH, position: Int, item: BabyInfo?) {
            if (item == null) return

            with(holder.binding) {
                val itemContext = root.context

                // 名字
                tvName.text = item.name

                // 性别和天数
                val genderCode = BabyGender.normalize(item.gender)
                val genderText = when (genderCode) {
                    BabyGender.BOY -> itemContext.getString(com.zero.common.R.string.baby_gender_boy_label)
                    BabyGender.GIRL -> itemContext.getString(com.zero.common.R.string.baby_gender_girl_label)
                    else -> ""
                }
                val days = if (item.birthDate > 0) {
                    TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - item.birthDate)
                } else 0
                val daysText = itemContext.getString(com.zero.common.R.string.baby_age_days_format, days)
                tvInfo.text = if (genderText.isNotEmpty()) {
                    itemContext.getString(com.zero.common.R.string.baby_info_gender_age_format, genderText, daysText)
                } else {
                    daysText
                }

                // 出生日期
                if (item.birthDate > 0) {
                    tvBirthDate.text = itemContext.getString(
                        com.zero.common.R.string.baby_birth_date_format,
                        dateFormat.format(Date(item.birthDate))
                    )
                    tvBirthDate.visibility = View.VISIBLE
                } else {
                    tvBirthDate.visibility = View.GONE
                }

                // 当前选中状态
                ivSelected.visibility = if (item.babyId == currentBabyId) View.VISIBLE else View.GONE

                // 头像颜色根据性别
                when (genderCode) {
                    BabyGender.BOY -> {
                        ivAvatar.setColorFilter(itemContext.getColor(com.zero.common.R.color.boy_brand))
                    }
                    BabyGender.GIRL -> {
                        ivAvatar.setColorFilter(itemContext.getColor(com.zero.common.R.color.girl_brand))
                    }
                    else -> {
                        // 未识别性别不再使用中性主题色，保持默认品牌色。
                        ivAvatar.setColorFilter(itemContext.getColor(com.zero.common.R.color.boy_brand))
                    }
                }

                // 编辑按钮
                actionRow.tvEdit.setOnClickListener {
                    goToEditBaby(item)
                }

                // 长按删除
                root.setOnLongClickListener {
                    deleteBaby(item)
                    true
                }
            }
        }
    }
}
