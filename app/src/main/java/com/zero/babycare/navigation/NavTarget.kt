package com.zero.babycare.navigation

import androidx.fragment.app.Fragment
import com.zero.babycare.babies.AllChildrenFragment
import com.zero.babycare.babyinfo.UpdateInfoFragment
import com.zero.babycare.home.DashboardFragment
import com.zero.babycare.home.record.FeedingRecordFragment
import com.zero.babycare.home.record.SleepRecordFragment
import com.zero.babycare.home.record.event.EventRecordFragment
import com.zero.babycare.statistics.StatisticsFragment

/**
 * 导航目标定义
 * 使用 sealed class 实现类型安全的导航参数传递
 */
sealed class NavTarget {
    
    /** 获取对应的 Fragment 类 */
    abstract val fragmentClass: Class<out Fragment>

    /** 概况页 */
    data object Dashboard : NavTarget() {
        override val fragmentClass = DashboardFragment::class.java
    }

    /** 所有宝宝列表 */
    data object AllChildren : NavTarget() {
        override val fragmentClass = AllChildrenFragment::class.java
    }

    /** 数据统计页 */
    data object Statistics : NavTarget() {
        override val fragmentClass = StatisticsFragment::class.java
    }

    /** 喂养记录页 */
    data object FeedingRecord : NavTarget() {
        override val fragmentClass = FeedingRecordFragment::class.java
    }

    /** 睡眠记录页 */
    data object SleepRecord : NavTarget() {
        override val fragmentClass = SleepRecordFragment::class.java
    }

    /**
     * 事件记录页
     * @param preSelectedCategory 预选的事件分类ID（对应 EventType.CATEGORY_*），null 表示不预选
     */
    data class EventRecord(
        val preSelectedCategory: Int? = null
    ) : NavTarget() {
        override val fragmentClass = EventRecordFragment::class.java
    }

    /**
     * 宝宝信息页
     * @param mode 页面模式：创建/编辑
     * @param babyId 编辑模式时的宝宝ID（创建模式为 null）
     * @param returnTarget 完成后返回的目标页面（默认 Dashboard）
     */
    data class BabyInfo(
        val mode: Mode,
        val babyId: Int? = null,
        val returnTarget: NavTarget = Dashboard
    ) : NavTarget() {
        override val fragmentClass = UpdateInfoFragment::class.java

        enum class Mode {
            /** 创建新宝宝 */
            CREATE,
            /** 编辑现有宝宝 */
            EDIT
        }

        companion object {
            /** 创建模式 */
            fun create(returnTarget: NavTarget = Dashboard) = BabyInfo(Mode.CREATE, returnTarget = returnTarget)
            
            /** 编辑模式 */
            fun edit(babyId: Int, returnTarget: NavTarget = Dashboard) = BabyInfo(Mode.EDIT, babyId, returnTarget)
        }
    }
}
