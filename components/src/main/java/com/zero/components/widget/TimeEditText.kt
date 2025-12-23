package com.zero.components.widget

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.util.AttributeSet
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import com.blankj.utilcode.util.ToastUtils
import java.util.Calendar
import java.util.Locale

/**
 * 自动格式化时间输入框：
 * - 输入 4 位数字自动变成 MM-dd HH:mm:ss
 * - 校验合法时间（小时 < 24，分钟 < 60）
 * - 智能识别跨天：如果输入的时间大于当前时间，自动理解为昨天
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
    private var onTimestampChangedListener: ((timestamp: Long) -> Unit)? = null

    // 存储当前时间的时间戳
    var currentTimestamp: Long = 0L
        private set

    init {
        keyListener = DigitsKeyListener.getInstance("0123456789")

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isEditing) return
                isEditing = true

                if (s.isNullOrEmpty()) {
                    currentTimestamp = 0L
                    isEditing = false
                    return
                }

                val digits = s.toString().trim()
                if (digits.length == 4 && digits.all { it.isDigit() }) {
                    val hour = digits.substring(0, 2).toIntOrNull() ?: 0
                    val minute = digits.substring(2, 4).toIntOrNull() ?: 0

                    if (hour !in 0..23 || minute !in 0..59) {
                        ToastUtils.showShort(com.zero.common.R.string.invalid_time)
                        setText("")
                        currentTimestamp = 0L
                    } else {
                        isCursorVisible = false
                        val now = Calendar.getInstance()
                        val inputCalendar = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }

                        // 智能识别跨天：如果输入的时间大于当前时间，理解为昨天
                        if (inputCalendar.timeInMillis > now.timeInMillis) {
                            inputCalendar.add(Calendar.DAY_OF_MONTH, -1)
                        }

                        currentTimestamp = inputCalendar.timeInMillis

                        val month = inputCalendar.get(Calendar.MONTH) + 1
                        val day = inputCalendar.get(Calendar.DAY_OF_MONTH)
                        val formatted = String.format(
                            Locale.getDefault(),
                            "%02d-%02d %02d:%02d:00",
                            month,
                            day,
                            hour,
                            minute
                        )
                        setText(formatted)

                        // 延迟执行防止光标越界
                        post {
                            val safeLength = text?.length ?: 0
                            val cursorPos = formatted.length.coerceAtMost(safeLength)
                            isCursorVisible = true
                            setSelection(cursorPos)

                            // 自动关闭键盘
                            val imm =
                                context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.hideSoftInputFromWindow(windowToken, 0)

                            // 回调监听
                            onTimeEnteredListener?.invoke(hour, minute)
                            onTimestampChangedListener?.invoke(currentTimestamp)
                        }
                    }
                }

                isEditing = false
            }
        })
    }

    /** 设置时间（通过时间戳） */
    fun setTimestamp(timestamp: Long) {
        if (timestamp <= 0) {
            setText("")
            currentTimestamp = 0L
            return
        }
        
        isEditing = true
        currentTimestamp = timestamp
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        val formatted = String.format(
            Locale.getDefault(),
            "%02d-%02d %02d:%02d:%02d",
            month, day, hour, minute, second
        )
        setText(formatted)
        isEditing = false
    }

    /** 设置为当前时间 */
    fun setCurrentTime() {
        setTimestamp(System.currentTimeMillis())
    }

    /** 获取时间戳，如果未设置返回0 */
    fun getTimestamp(): Long = currentTimestamp

    /** 检查是否有有效的时间 */
    fun hasValidTime(): Boolean = currentTimestamp > 0

    /** 检查时间是否是未来时间 */
    fun isFutureTime(): Boolean = currentTimestamp > System.currentTimeMillis()

    /** 获取格式化时间 "HH:mm:ss" */
    fun getFormattedTime(): String = (text?.toString()?.trim() ?: "").let {
        if (it.length > 8) it.takeLast(8) else it
    }

    /** 获取小时和分钟 */
    fun getHourMinute(): Pair<Int, Int>? {
        val parts = getFormattedTime().split(":")
        return if (parts.size == 3) {
            val h = parts[0].toIntOrNull() ?: 0
            val m = parts[1].toIntOrNull() ?: 0
            h to m
        } else null
    }

    /** 设置时间输入完成回调 */
    fun setOnTimeEnteredListener(listener: (hour: Int, minute: Int) -> Unit) {
        onTimeEnteredListener = listener
    }

    /** 设置时间戳变化回调 */
    fun setOnTimestampChangedListener(listener: (timestamp: Long) -> Unit) {
        onTimestampChangedListener = listener
    }

    /** 清除回调 */
    fun clearOnTimeEnteredListener() {
        onTimeEnteredListener = null
    }

    /** 清除时间戳变化回调 */
    fun clearOnTimestampChangedListener() {
        onTimestampChangedListener = null
    }
}
