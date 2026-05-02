package com.zero.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatUtilsTest {

    @Test
    fun `format timer clock uses minute second format before one hour`() {
        // 低于 1 小时时固定显示 MM:SS，保证计时器短时长展示稳定可扫读。
        assertEquals("00:00", DurationFormatUtils.formatTimerClock(0L))
        assertEquals("00:05", DurationFormatUtils.formatTimerClock(5_000L))
        assertEquals("59:59", DurationFormatUtils.formatTimerClock(3_599_000L))
    }

    @Test
    fun `format timer clock uses hour minute second format from one hour`() {
        // 达到 1 小时后展示小时位，小时不补零以避免长时长计时出现多余前导位。
        assertEquals("1:00:00", DurationFormatUtils.formatTimerClock(3_600_000L))
        assertEquals("2:05:09", DurationFormatUtils.formatTimerClock(7_509_000L))
    }

    @Test
    fun `format timer clock clamps negative duration to zero`() {
        // 负数通常来自时间源抖动或异常输入，按 0 处理可避免 UI 出现倒计时样式。
        assertEquals("00:00", DurationFormatUtils.formatTimerClock(-1L))
    }
}
