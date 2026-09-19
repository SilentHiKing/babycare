package com.zero.babycare.babies

import com.zero.babycare.navigation.NavTarget

/** 删除完成时只结束原来的编辑任务，不抢占用户已经切换到的页面。 */
internal object BabyDeletionNavigation {
    fun destination(
        deletedBabyId: Int,
        requestedFrom: NavTarget,
        currentTarget: NavTarget,
        hasRemainingBabies: Boolean
    ): NavTarget? {
        val editor = requestedFrom as? NavTarget.BabyInfo ?: return null
        if (editor.mode != NavTarget.BabyInfo.Mode.EDIT || editor.babyId != deletedBabyId ||
            currentTarget != requestedFrom
        ) return null
        return if (hasRemainingBabies) editor.returnTarget
        else NavTarget.BabyInfo.create(returnTarget = editor.returnTarget)
    }
}
