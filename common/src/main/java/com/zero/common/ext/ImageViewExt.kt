package com.zero.common.ext

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.core.widget.ImageViewCompat
import com.zero.common.R


fun ImageView.setIconColor(mContext: Context) {
    val iconColor = mContext.getThemeColor(R.attr.icon_primary)
    setColorFilter(iconColor)
}

//设置主题色方法
fun ImageView.setIconBrandColor(mContext: Context) {
    val iconColor = mContext.getThemeColor(R.attr.icon_brand)
    setColorFilter(iconColor)
}

fun ImageView.setImageAttr(attrId: Int) {
    val typedValue = TypedValue()
    if (context.theme.resolveAttribute(attrId, typedValue, true)) {
        setImageResource(typedValue.resourceId)
    }
}

fun ImageView.setTintCompat(@ColorInt color: Int) {
    ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color))
}


