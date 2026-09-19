package com.zero.babycare.home.record

import android.view.View
import android.view.ViewGroup
import java.util.IdentityHashMap

/** 暂停提交中的输入而保留页面导航；解锁时恢复各控件原状态，不启用原本禁用的操作。 */
internal class RecordFormFreeze {
    private val enabledBeforeFreeze = IdentityHashMap<View, Boolean>()

    fun render(frozen: Boolean, vararg roots: View) {
        if (!frozen) {
            enabledBeforeFreeze.forEach { (view, enabled) -> view.isEnabled = enabled }
            enabledBeforeFreeze.clear()
            return
        }
        fun freeze(view: View) {
            enabledBeforeFreeze.putIfAbsent(view, view.isEnabled)
            view.isEnabled = false
            if (view is ViewGroup) for (index in 0 until view.childCount) freeze(view.getChildAt(index))
        }
        roots.forEach(::freeze)
    }

    fun clear() = enabledBeforeFreeze.clear()
}
