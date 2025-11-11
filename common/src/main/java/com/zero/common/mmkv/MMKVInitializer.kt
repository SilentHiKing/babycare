package com.zero.common.mmkv

import android.content.Context
import com.tencent.mmkv.MMKV

object MMKVInitializer {

    fun init(context: Context) {
        val rootDir = MMKV.initialize(context)
        println("MMKV root: $rootDir")
        migrateFromSp(context)
    }

    /**
     * 一次性迁移旧 SharedPreferences → MMKV
     */
    private fun migrateFromSp(context: Context) {
        val oldSp = context.getSharedPreferences("old_config", Context.MODE_PRIVATE)
        if (oldSp.all.isNotEmpty()) {
            val kv = MMKV.defaultMMKV()
            oldSp.all.forEach { (key, value) ->
                when (value) {
                    is String -> kv.encode(key, value)
                    is Boolean -> kv.encode(key, value)
                    is Int -> kv.encode(key, value)
                    is Long -> kv.encode(key, value)
                    is Float -> kv.encode(key, value)
                }
            }
            oldSp.edit().clear().apply()
            println("✅ SP data migrated to MMKV.")
        }
    }
}
