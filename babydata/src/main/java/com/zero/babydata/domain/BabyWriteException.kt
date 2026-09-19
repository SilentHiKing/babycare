package com.zero.babydata.domain

/** 仅暴露可映射的失败原因，不把宝宝资料或请求身份写入异常。 */
class BabyWriteException(val reason: BabyWriteFailure) : RuntimeException()

enum class BabyWriteFailure { INVALID_REQUEST, CONTENT_CONFLICT, BABY_DELETED }
