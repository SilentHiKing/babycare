package com.zero.babycare.reminder

import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.babydata.entity.BabyInfo

/** 将异步记录查询与通知系统分离，投递测试可用合成候选，不向用户数据库写记录。 */
internal interface ReminderDataSource {
    suspend fun getBaby(babyId: Int): BabyInfo?
    suspend fun getCandidate(baby: BabyInfo, type: ReminderType): ReminderCandidate?
}

internal object RepositoryReminderDataSource : ReminderDataSource {
    override suspend fun getBaby(babyId: Int) = repository.getBabyInfo(babyId)
    override suspend fun getCandidate(baby: BabyInfo, type: ReminderType) = ReminderPolicy.loadCandidates(baby)[type]
}
