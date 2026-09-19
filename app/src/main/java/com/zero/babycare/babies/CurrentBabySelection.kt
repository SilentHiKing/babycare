package com.zero.babycare.babies

import com.google.gson.JsonParser

/** 旧缓存只用于恢复选择 ID，姓名、生日等字段绝不替代数据库中的最新值。 */
internal object CurrentBabySelection {
    fun resolveId(
        availableIds: List<Int>,
        inMemoryId: Int?,
        storedId: Int?,
        legacyJson: String?
    ): Int? {
        val legacyId = legacyJson?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching {
                JsonParser.parseString(raw).asJsonObject.get("babyId")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asString?.toIntOrNull()?.takeIf { it > 0 }
            }.getOrNull()
        }
        // 新缓存可能来自用户升级后的主动切换，只有它缺失或失效时才回退旧缓存。
        return listOfNotNull(inMemoryId, storedId, legacyId)
            .firstOrNull { it in availableIds }
            ?: availableIds.firstOrNull()
    }
}
