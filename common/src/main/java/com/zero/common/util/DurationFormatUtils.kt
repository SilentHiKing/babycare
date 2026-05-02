package com.zero.common.util

object DurationFormatUtils {

    /**
     * 将毫秒时长格式化为计时器展示文本。
     *
     * 负数按 0 处理，避免异常时间源或外部输入让 UI 出现负时长；不足 1 小时保持
     * MM:SS，达到 1 小时后切换为 H:MM:SS，便于长时长记录继续保留分钟和秒的对齐。
     */
    fun formatTimerClock(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        return if (hours > 0L) {
            "$hours:${minutes.toClockPart()}:${seconds.toClockPart()}"
        } else {
            "${minutes.toClockPart()}:${seconds.toClockPart()}"
        }
    }

    private fun Long.toClockPart(): String = toString().padStart(2, '0')
}
