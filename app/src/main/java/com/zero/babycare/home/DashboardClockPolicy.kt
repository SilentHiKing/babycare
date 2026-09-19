package com.zero.babycare.home

import java.time.Instant
import java.time.ZoneId

/** 时钟只在自然日或时区变化时触发数据查询，正常分钟跳动只更新相对时间。 */
internal object DashboardClockPolicy {

    fun needsDayReload(
        previousTime: Long,
        now: Long,
        loadedTimeZoneId: String,
        currentZone: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        return loadedTimeZoneId != currentZone.id ||
            Instant.ofEpochMilli(previousTime).atZone(currentZone).toLocalDate() !=
            Instant.ofEpochMilli(now).atZone(currentZone).toLocalDate()
    }
}
