package com.zero.babydata.backup

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** 先用流式扫描限制结构，再允许 Gson 分配对象；小文件也可能包含极深结构或海量空记录。 */
internal object BackupJsonLimits {
    const val MAX_DEPTH = 64
    const val MAX_RECORDS = 200_000
    const val MAX_JSON_CHARS = 20 * 1024 * 1024
    const val MAX_EXTRA_VALUES = 20_000
    private val recordPaths = setOf(
        "$.data.babies", "$.data.feedingRecords", "$.data.sleepRecords",
        "$.data.eventRecords", "$.data.childDailyRecords"
    )

    fun validate(json: String, countRecords: Boolean = false, maxValues: Int = Int.MAX_VALUE) {
        if (json.length > MAX_JSON_CHARS) invalid()
        var records = 0
        var values = 0
        JsonReader(StringReader(json)).use { reader ->
            // 未知字段仍完整保留；这里只检验结构，不把正文重写成另一份 JSON。
            fun visit(depth: Int) {
                if (depth > MAX_DEPTH || ++values > maxValues) invalid()
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            reader.nextName()
                            visit(depth + 1)
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        val isRecordArray = countRecords && reader.path in recordPaths
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (isRecordArray && ++records > MAX_RECORDS) invalid()
                            visit(depth + 1)
                        }
                        reader.endArray()
                    }
                    JsonToken.STRING, JsonToken.NUMBER, JsonToken.BOOLEAN, JsonToken.NULL -> reader.skipValue()
                    else -> invalid()
                }
            }
            visit(0)
            if (reader.peek() != JsonToken.END_DOCUMENT) invalid()
        }
    }

    private fun invalid(): Nothing = throw BackupImportException(BackupImportError.INVALID_JSON)
}
