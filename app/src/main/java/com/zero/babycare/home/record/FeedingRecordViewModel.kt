package com.zero.babycare.home.record

import com.blankj.utilcode.util.StringUtils
import com.blankj.utilcode.util.Utils
import com.zero.babycare.home.bean.DashboardEntity
import com.zero.babycare.home.bean.DashboardEntity.Companion.TYPE_INFO
import com.zero.babycare.home.bean.DashboardEntity.Companion.TYPE_NEXT
import com.zero.babycare.home.bean.DashboardEntity.Companion.TYPE_TITLE
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyRepository
import com.zero.common.util.DateUtils
import com.zero.common.util.DateUtils.getTimeOfDayMillis
import com.zero.components.base.vm.BaseViewModel
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.common.R
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong


class FeedingRecordViewModel() : BaseViewModel() {

    fun insert(feedingRecord: FeedingRecord, callback: Runnable) {
        safeLaunch {
            repository.insertFeedingRecord(feedingRecord, callback)
        }
    }


}