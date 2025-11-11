package com.zero.babydata.domain

import com.blankj.utilcode.util.Utils
import com.zero.babydata.room.BabyRepository

object BabyDataHelper {
    val repository = BabyRepository(Utils.getApp())
}