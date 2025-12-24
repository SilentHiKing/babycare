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
import com.zero.common.R

fun stringOf(@StringRes id: Int, vararg formatArgs: Any): String = getString(id, *formatArgs)

fun stringOf(@StringRes id: Int): String = getString(id)

fun getString(@StringRes id: Int, vararg formatArgs: Any?): String {
    return Utils.getApp().resources.getString(id, *formatArgs)
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
    context.theme.resolveAttribute(attrRes, typedValue, true)
    return ContextCompat.getColor(context, typedValue.resourceId)
}

fun Context.getThemeDrawable(@AttrRes attrRes: Int): Drawable? {
    val context = this as? ContextThemeWrapper ?: Utils.getApp()
    val typedValue = TypedValue()
    return try {
        context.theme.resolveAttribute(attrRes, typedValue, true)
        ResourcesCompat.getDrawable(context.resources, typedValue.resourceId, context.theme)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
fun Context.dp(resId: Int) = this.resources.getDimensionPixelSize(resId)
fun Context.bgForCorner(corner: CornerType, @ColorInt color: Int = Color.WHITE): Drawable {
    val r = this.resources.getDimensionPixelSize(R.dimen.dp_10).toFloat()
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