package com.zero.babydata.domain

/** 首页、日摘要和周期趋势共用的时长归属规则。范围终点按数据库查询的闭区间传入。 */
object RecordDurationCalculator {

    fun overlappedDuration(
        start: Long,
        end: Long,
        duration: Long,
        rangeStart: Long,
        rangeEnd: Long
    ): Long {
        if (start <= 0L || end <= start || rangeEnd < rangeStart) return 0L
        val rangeEndExclusive = if (rangeEnd == Long.MAX_VALUE) rangeEnd else rangeEnd + 1L
        val overlapStart = maxOf(start, rangeStart)
        val overlapEnd = minOf(end, rangeEndExclusive)
        if (overlapEnd <= overlapStart) return 0L

        val span = end - start
        val savedDuration = if (duration > 0L) minOf(duration, span) else span
        // 旧记录只有有效时长、没有暂停片段：按时间跨度分摊，不能把跨日暂停重新计入。
        // 用累计值之差分配毫秒余数，保证相邻天数相加等于完整记录的有效时长。
        fun allocatedUntil(timestamp: Long): Long {
            if (timestamp >= end) return savedDuration
            return (savedDuration.toDouble() * (timestamp - start) / span)
                .toLong().coerceIn(0L, savedDuration)
        }
        return allocatedUntil(overlapEnd) - allocatedUntil(overlapStart)
    }
}
