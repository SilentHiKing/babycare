package com.zero.components.widget

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.util.AttributeSet
import android.view.inputmethod.InputMethodManager
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

    // 用于结束时间跨天判断的参考时间戳（通常是开始时间）
    private var referenceTimestamp: Long = 0L

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
                        val resultTimestamp = calculateSmartTimestamp(hour, minute)
                        currentTimestamp = resultTimestamp

                        val calendar = Calendar.getInstance().apply { timeInMillis = resultTimestamp }
                        val month = calendar.get(Calendar.MONTH) + 1
                        val day = calendar.get(Calendar.DAY_OF_MONTH)
                        val formatted = String.format(
                            Locale.getDefault(),
                            "%02d-%02d %02d:%02d:00",
                            month,
                            day,
                            hour,
                            minute
                        )
                        setText(formatted)

                        // 延迟执行，确保 setText 完成后再操作光标
                        post {
                            // 显示光标并移动到末尾
                            isCursorVisible = true
                            moveCursorToEnd()

                            // 自动关闭键盘
                            hideKeyboard()

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

    /**
     * 智能计算时间戳
     * - 如果有参考时间（开始时间），则结束时间应该 >= 开始时间
     * - 如果没有参考时间，则判断是否超过当前时间，超过则设为昨天
     */
    private fun calculateSmartTimestamp(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val inputCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 如果有参考时间（通常是开始时间）
        if (referenceTimestamp > 0) {
            val refCalendar = Calendar.getInstance().apply { timeInMillis = referenceTimestamp }
            
            // 如果输入的时间小于参考时间的时分，可能是跨天到第二天
            val refHour = refCalendar.get(Calendar.HOUR_OF_DAY)
            val refMinute = refCalendar.get(Calendar.MINUTE)
            
            // 设置输入时间为参考时间的同一天
            inputCalendar.set(Calendar.YEAR, refCalendar.get(Calendar.YEAR))
            inputCalendar.set(Calendar.MONTH, refCalendar.get(Calendar.MONTH))
            inputCalendar.set(Calendar.DAY_OF_MONTH, refCalendar.get(Calendar.DAY_OF_MONTH))
            
            // 如果输入的时间小于参考时间，说明跨天了，加一天
            if (hour < refHour || (hour == refHour && minute < refMinute)) {
                // 检查加一天后是否超过当前时间
                inputCalendar.add(Calendar.DAY_OF_MONTH, 1)
                if (inputCalendar.timeInMillis > now.timeInMillis) {
                    // 如果超过当前时间，则不跨天，保持原来的日期
                    inputCalendar.add(Calendar.DAY_OF_MONTH, -1)
                }
            }
            
            // 最终检查：不能超过当前时间
            if (inputCalendar.timeInMillis > now.timeInMillis) {
                inputCalendar.add(Calendar.DAY_OF_MONTH, -1)
            }
        } else {
            // 没有参考时间，使用默认逻辑：超过当前时间则设为昨天
            if (inputCalendar.timeInMillis > now.timeInMillis) {
                inputCalendar.add(Calendar.DAY_OF_MONTH, -1)
            }
        }

        return inputCalendar.timeInMillis
    }

    /**
     * 设置参考时间戳（用于结束时间的跨天判断）
     * 通常在设置结束时间之前，先设置开始时间作为参考
     */
    fun setReferenceTimestamp(timestamp: Long) {
        referenceTimestamp = timestamp
    }

    /** 清除参考时间戳 */
    fun clearReferenceTimestamp() {
        referenceTimestamp = 0L
    }

    /** 设置时间（通过时间戳），可选择是否触发回调 */
    fun setTimestamp(timestamp: Long, triggerCallback: Boolean = false) {
        if (timestamp <= 0) {
            isEditing = true
            setText("")
            currentTimestamp = 0L
            isEditing = false
            if (triggerCallback) {
                onTimestampChangedListener?.invoke(0L)
            }
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
        
        // 延迟将光标移动到末尾
        post { moveCursorToEnd() }
        
        isEditing = false

        if (triggerCallback) {
            onTimeEnteredListener?.invoke(hour, minute)
            onTimestampChangedListener?.invoke(currentTimestamp)
        }
    }

    /** 设置为当前时间 */
    fun setCurrentTime(triggerCallback: Boolean = false) {
        setTimestamp(System.currentTimeMillis(), triggerCallback)
    }

    /** 清空时间 */
    fun clear() {
        isEditing = true
        setText("")
        currentTimestamp = 0L
        isEditing = false
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

    // ============ 私有辅助方法 ============

    /** 将光标移动到文本末尾 */
    private fun moveCursorToEnd() {
        val length = text?.length ?: 0
        if (length > 0) {
            setSelection(length)
        }
    }

    /** 隐藏软键盘 */
    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }
}
