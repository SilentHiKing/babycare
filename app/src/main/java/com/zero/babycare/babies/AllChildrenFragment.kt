package com.zero.babycare.babies

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.StringUtils
import com.chad.library.adapter4.BaseQuickAdapter
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentAllChildrenBinding
import com.zero.babycare.databinding.ItemBabyCardBinding
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.navigation.BackPressHandler
import com.zero.babydata.entity.BabyInfo
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
                    contentDescription = StringUtils.getString(com.zero.common.R.string.add_baby)
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
            title = StringUtils.getString(com.zero.common.R.string.delete_baby),
            content = StringUtils.getString(com.zero.common.R.string.delete_baby_confirm, baby.name),
            confirmText = StringUtils.getString(com.zero.common.R.string.confirm),
            cancelText = StringUtils.getString(com.zero.common.R.string.cancel),
            onConfirm = {
                mainVm.deleteBaby(baby) {
                    loadBabies()
                    (activity as? MainActivity)?.refreshDrawerBabyInfo()
                }
            }
        )
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
                // 名字
                tvName.text = item.name

                // 性别和天数
                val genderText = when {
                    item.gender.contains("男") -> "♂ 男孩"
                    item.gender.contains("女") -> "♀ 女孩"
                    else -> ""
                }
                val days = if (item.birthDate > 0) {
                    TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - item.birthDate)
                } else 0
                tvInfo.text = if (genderText.isNotEmpty()) {
                    "$genderText · ${days}天"
                } else {
                    "${days}天"
                }

                // 出生日期
                if (item.birthDate > 0) {
                    tvBirthDate.text = "出生: ${dateFormat.format(Date(item.birthDate))}"
                    tvBirthDate.visibility = View.VISIBLE
                } else {
                    tvBirthDate.visibility = View.GONE
                }

                // 当前选中状态
                ivSelected.visibility = if (item.babyId == currentBabyId) View.VISIBLE else View.GONE

                // 头像颜色根据性别
                when {
                    item.gender.contains("男") -> {
                        ivAvatar.setColorFilter(context.getColor(com.zero.common.R.color.boy_brand))
                    }
                    item.gender.contains("女") -> {
                        ivAvatar.setColorFilter(context.getColor(com.zero.common.R.color.girl_brand))
                    }
                    else -> {
                        ivAvatar.setColorFilter(context.getColor(com.zero.common.R.color.neutral_brand))
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
