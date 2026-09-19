package com.zero.babydata.room

/** 日历只需区间端点，避免为日期标记加载整月备注和喂养详情。 */
data class RecordTimeSpan(val startTime: Long, val endTime: Long)
