package com.zero.babycare

import android.app.Application
import android.content.Context
import com.tencent.mmkv.MMKV

class App : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        init()
    }

    private fun init() {
    }
}