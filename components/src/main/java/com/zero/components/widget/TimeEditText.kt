package com.zero.components.widget

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.util.AttributeSet
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import java.util.Locale

/**
 * 自动格式化时间输入框：
 * - 输入 4 位数字自动变成 HH:mm
 * - 校验合法时间（小时 < 24，分钟 < 60）
 * - 自动收起键盘
 * - 提供输入完成回调 onTimeEntered(hour, minute)
 */
class TimeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private var isEditing = false
    private var onTimeEnteredListener: ((hour: Int, minute: Int) -> Unit)? = null

    init {
        // 限制输入数字且最多5位
        filters = arrayOf(InputFilter.LengthFilter(5))
        keyListener = DigitsKeyListener.getInstance("0123456789")

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isEditing) return
                isEditing = true

                if (s.isNullOrEmpty()) {
                    isEditing = false
                    return
                }

                val digits = s.toString().replace(":", "").trim()
                if (digits.length == 4) {
                    val hour = digits.substring(0, 2).toIntOrNull() ?: 0
                    val minute = digits.substring(2, 4).toIntOrNull() ?: 0

                    if (hour !in 0..23 || minute !in 0..59) {
                        Toast.makeText(context, "请输入有效的时间", Toast.LENGTH_SHORT).show()
                        setText("")
                    } else {
                        val formatted = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
                        setText(formatted)

                        // ✅ 延迟执行防止光标越界
                        post {
                            val safeLength = text?.length ?: 0
                            val cursorPos = formatted.length.coerceAtMost(safeLength)
                            setSelection(cursorPos)

                            // ✅ 自动关闭键盘
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.hideSoftInputFromWindow(windowToken, 0)

                            // ✅ 回调监听
                            onTimeEnteredListener?.invoke(hour, minute)
                        }
                    }
                }

                isEditing = false
            }
        })
    }

    /** 获取格式化时间 "HH:mm" */
    fun getFormattedTime(): String = text?.toString()?.trim() ?: ""

    /** 获取小时和分钟 */
    fun getHourMinute(): Pair<Int, Int>? {
        val parts = getFormattedTime().split(":")
        return if (parts.size == 2) {
            val h = parts[0].toIntOrNull() ?: 0
            val m = parts[1].toIntOrNull() ?: 0
            h to m
        } else null
    }

    /** 设置回调监听 */
    fun setOnTimeEnteredListener(listener: (hour: Int, minute: Int) -> Unit) {
        onTimeEnteredListener = listener
    }

    /** 清除回调 */
    fun clearOnTimeEnteredListener() {
        onTimeEnteredListener = null
    }
}
