package com.zero.babydata.backup

import com.google.gson.Gson
import java.io.Writer

/** 导出先核验可恢复性，序列化期间限长；不能先分配任意大的 JSON 再报告超限。 */
internal object BackupJsonEncoder {
    private val gson = Gson()

    fun encode(envelope: BabyBackupEnvelope): String {
        try {
            if (envelope.data.totalRecordCount() > BackupJsonLimits.MAX_RECORDS ||
                envelope.data.eventRecords.any { it.extraData.length > BackupJsonLimits.MAX_JSON_CHARS }) {
                throw BackupExportException(BackupExportError.LIMIT_EXCEEDED)
            }
            BabyBackupParser.validateEnvelope(envelope)
            val writer = LimitedWriter(BackupJsonLimits.MAX_JSON_CHARS)
            gson.toJson(envelope, writer)
            return writer.toString()
        } catch (error: BackupExportException) {
            throw error
        } catch (_: Exception) {
            throw BackupExportException(BackupExportError.INVALID_DATA)
        }
    }

    /** 按写入前检查字符数，整段长备注也不能越过限制后才拒绝；不截断任何字段。 */
    internal class LimitedWriter(private val maxChars: Int) : Writer() {
        private val value = StringBuilder()

        private fun checkSize(count: Int) {
            if (count > maxChars - value.length) throw BackupExportException(BackupExportError.LIMIT_EXCEEDED)
        }

        override fun write(buffer: CharArray, offset: Int, length: Int) {
            checkSize(length)
            value.append(buffer, offset, length)
        }

        override fun write(text: String, offset: Int, length: Int) {
            checkSize(length)
            value.append(text, offset, offset + length)
        }

        override fun write(character: Int) {
            checkSize(1)
            value.append(character.toChar())
        }

        override fun flush() = Unit
        override fun close() = Unit
        override fun toString(): String = value.toString()
    }
}
