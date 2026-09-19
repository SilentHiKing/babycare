package com.zero.babycare.home.record

import com.zero.babycare.home.OngoingRecordManager.OngoingSession

/** 保存只结束提交时的会话；数据库挂起期间新建或继续的计时必须保留。 */
internal object OngoingSessionSavePolicy {
    suspend fun save(
        submittedSession: OngoingSession?,
        persist: suspend () -> Unit,
        currentSession: () -> OngoingSession?,
        finishSession: () -> Unit
    ) {
        persist()
        // 调用方在主线程完成此段，比较和清理之间不挂起，不给新会话插入的机会。
        if (submittedSession != null && currentSession() == submittedSession) finishSession()
    }
}
