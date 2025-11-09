package com.zero.babycare.home.bean

class DashboardEntity(val type: Int) {


    var title: String = ""
    var content :String = ""
    var desc : String = ""


    companion object {
        const val TYPE_TITLE = 10
        const val TYPE_INFO = 11
        const val TYPE_NEXT = 12
    }
}