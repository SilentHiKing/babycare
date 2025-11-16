package com.zero.common.ext

import com.zero.common.util.DateUtils.millisecondsToSmartMinutes

fun Long.msToSmartMinutes(): String = millisecondsToSmartMinutes(this)