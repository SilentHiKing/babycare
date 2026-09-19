package com.zero.common.ext


import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.Utils
import com.zero.common.util.LanguageManager


fun stringOf(@StringRes id: Int, vararg formatArgs: Any): String = getString(id, *formatArgs)

fun stringOf(@StringRes id: Int): String = getString(id)

fun getString(@StringRes id: Int, vararg formatArgs: Any?): String {
    return Utils.getApp().getAppString(id, *formatArgs)
}

/** 非 Activity 路径同样跟随应用语言；不修改全局 Resources，避免影响并行页面。 */
fun Context.getAppString(@StringRes id: Int, vararg formatArgs: Any?): String {
    val localized = LanguageManager.localizedContext(this)
    return if (formatArgs.isEmpty()) localized.getString(id) else localized.getString(id, *formatArgs)
}

fun color(@ColorRes id: Int) = ContextCompat.getColor(Utils.getApp(), id)

fun dimen(@DimenRes id: Int) = Utils.getApp().resources.getDimension(id)

fun dimenInt(@DimenRes id: Int) = dimen(id).toInt()

fun drawable(@DrawableRes id: Int) = ContextCompat.getDrawable(Utils.getApp(), id)

fun Fragment.getThemeColor(attrRes: Int) = requireContext().getThemeColor(attrRes)

fun View.getThemeColor(attrRes: Int) = context.getThemeColor(attrRes)

fun Fragment.getThemeDrawable(attrRes: Int) = requireContext().getThemeDrawable(attrRes)

fun View.getThemeDrawable(attrRes: Int) = context.getThemeDrawable(attrRes)

fun Context.getThemeColor(attrRes: Int): Int {
    val context = this as? ContextThemeWrapper ?: Utils.getApp()
    val typedValue = TypedValue()
    val resolved = context.theme.resolveAttribute(attrRes, typedValue, true)
    if (!resolved) {
        return Color.TRANSPARENT
    }
    if (typedValue.resourceId != 0) {
        return ContextCompat.getColor(context, typedValue.resourceId)
    }
    return if (typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
        typedValue.data
    } else {
        Color.TRANSPARENT
    }
}

fun Context.getThemeDrawable(@AttrRes attrRes: Int): Drawable? {
    val context = this as? ContextThemeWrapper ?: Utils.getApp()
    val typedValue = TypedValue()
    return try {
        context.theme.resolveAttribute(attrRes, typedValue, true)
        ResourcesCompat.getDrawable(context.resources, typedValue.resourceId, context.theme)
    } catch (_: Exception) {
        // 主题资源解析失败时安全降级，避免把运行环境细节写入日志。
        null
    }
}
fun Context.dp(resId: Int) = this.resources.getDimensionPixelSize(resId)
fun Context.bgForCorner(corner: CornerType, @ColorInt color: Int = Color.WHITE): Drawable {
    val r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, this.resources.displayMetrics)
    val d = GradientDrawable().apply { setColor(color) }
    val all = floatArrayOf(r, r, r, r, r, r, r, r)
    val top = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    val mid = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    val bottom = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
    d.cornerRadii = when (corner) {
        CornerType.SINGLE -> all
        CornerType.TOP -> top
        CornerType.MIDDLE -> mid
        CornerType.BOTTOM -> bottom
    }
    return d
}

enum class CornerType { SINGLE, TOP, MIDDLE, BOTTOM }
