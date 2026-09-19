package com.zero.babydata.domain

/** 只向界面传递可映射的原因，不把宝宝资料、原始输入或请求标识写入异常。 */
class RecordWriteException(val reason: RecordWriteFailure) : RuntimeException()

enum class RecordWriteFailure {
    INVALID_REQUEST,
    CONTENT_CONFLICT,
    RECORD_DELETED,
    BABY_NOT_FOUND
}
