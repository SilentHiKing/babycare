package com.zero.babydata

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babydata.backup.*
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.ChildDailyRecord
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 身份兼容只在独立内存库验证；不读写应用真实宝宝、报告或回执。 */
@RunWith(AndroidJUnit4::class)
class BackupIdentityCompatibilityTest {
    private val lower = "8668a9e3-406d-478c-af23-6b4ad5327458"
    private val upper = lower.uppercase()
    private val mixed = "8668A9e3-406d-478c-af23-6b4ad5327458"
    private val other = "ab8b2723-f439-42bb-81a1-c65e96b5739f"

    @Test
    fun uniqueCaseVariantMatchesInBothDirectionsWithoutChangingLocalIdentityOrHistory() {
        listOf(lower to upper, upper to lower).forEach { (localIdentity, incomingIdentity) ->
            withDatabase { db ->
                val local = BabyInfo(stableId = localIdentity, name = "本地合成资料", gender = "girl")
                val id = db.babyInfoDao().insertBabyInfo(local).toInt()
                val history = SleepRecord(babyId = id, sleepStart = 10, sleepEnd = 20,
                    sleepDuration = 10, note = "原有合成历史", createdAt = 1)
                val historyId = db.sleepRecordDao().insertSleepRecord(history).toInt()
                val importer = BabyBackupImporter(db)
                val envelope = sampleEnvelope(listOf(incomingIdentity))

                val first = importer.importMerge(envelope, 0)
                assertEquals(0, first.babyInserted)
                assertEquals(1, first.babyMatched)
                assertEquals(local.copy(babyId = id), db.babyInfoDao().getAllBabyInfo().single())
                assertEquals(history.copy(sleepId = historyId), db.sleepRecordDao().getSleepRecordById(historyId))
                assertEquals(listOf(id), db.feedingRecordDao().getAllFeedingRecords(id).map { it.babyId })
                assertEquals(listOf(id, id), db.sleepRecordDao().getAllSleepRecords(id).map { it.babyId })
                assertEquals(listOf(id), db.eventRecordDao().getAllEventRecord(id).map { it.babyId })
                assertEquals(listOf(id), db.childDailyRecordDao().getAllRecordsByBabyId(id).map { it.babyId })

                val beforeRepeat = BabyBackupExporter(db).buildEnvelope().data
                val repeated = importer.importMerge(envelope, 0)
                assertEquals(0, repeated.babyInserted)
                assertEquals(1, repeated.babyMatched)
                assertEquals(0, repeated.feedingInserted + repeated.sleepInserted + repeated.eventInserted + repeated.childDailyInserted)
                assertEquals(beforeRepeat, BabyBackupExporter(db).buildEnvelope().data)
            }
        }
    }

    @Test
    fun competingIncomingVariantsRejectBeforeAnyInsertRegardlessOfOrderOrExactMatch() {
        // 第一组有精确目标，第二组只有两个大小写回退；两者都不能共用一个本地宝宝。
        listOf(lower, mixed).forEach { localIdentity ->
            withDatabase { db ->
                db.babyInfoDao().insertBabyInfo(BabyInfo(stableId = localIdentity, name = "合成资料"))
                val before = BabyBackupExporter(db).buildEnvelope().data
                // 若先插 unrelated 宝宝再发现歧义，此触发器会抛 SQL 错误而非指定业务错误。
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER require_identity_plan BEFORE INSERT ON Baby_Info BEGIN SELECT RAISE(ABORT, 'synthetic preflight failure'); END"
                )
                listOf(listOf(other, upper, lower), listOf(lower, upper, other)).forEach { order ->
                    assertAmbiguousWithoutChanges(db, sampleEnvelope(order), before)
                }
            }
        }
    }

    @Test
    fun historicalLocalVariantsKeepExactTargetsAndRejectAnUnresolvableThirdSpelling() = withDatabase { db ->
        val importer = BabyBackupImporter(db)
        val original = sampleEnvelope(listOf(lower, upper))
        importer.importOverwrite(original)
        val before = BabyBackupExporter(db).buildEnvelope().data
        val originalIds = db.babyInfoDao().getAllBabyInfo().associate { it.stableId to it.babyId }
        val reversed = original.copy(data = original.data.copy(babies = original.data.babies.reversed()))

        val report = importer.importMerge(reversed, 0)
        assertEquals(0, report.babyInserted)
        assertEquals(2, report.babyMatched)
        assertEquals(originalIds, db.babyInfoDao().getAllBabyInfo().associate { it.stableId to it.babyId })
        assertEquals(before, BabyBackupExporter(db).buildEnvelope().data)

        // 即使另一个候选已被精确匹配占用，也不能猜测剩下的候选属于第三种拼写。
        assertAmbiguousWithoutChanges(db, sampleEnvelope(listOf(other, mixed)), before)
        assertAmbiguousWithoutChanges(db, sampleEnvelope(listOf(lower, mixed)), before)
    }

    @Test
    fun emptyMergeAndExportOverwriteRoundTripPreserveBothHistoricalVariants() = withDatabase { db ->
        val original = sampleEnvelope(listOf(lower, upper))
        val report = BabyBackupImporter(db).importMerge(original, 0)
        assertEquals(2, report.babyInserted)
        assertEquals(0, report.babyMatched)
        val exporter = BabyBackupExporter(db)
        val exported = BabyBackupParser.parse(exporter.toJson(exporter.buildEnvelope()))
        assertEquals(normalized(original.data), normalized(exported.data))

        val restored = newDatabase()
        try {
            BabyBackupImporter(restored).importOverwrite(exported)
            val restoredExporter = BabyBackupExporter(restored)
            val roundTrip = BabyBackupParser.parse(restoredExporter.toJson(restoredExporter.buildEnvelope()))
            assertEquals(normalized(original.data), normalized(roundTrip.data))
            val babies = restored.babyInfoDao().getAllBabyInfo()
            assertEquals(setOf(lower, upper), babies.map { it.stableId }.toSet())
            assertEquals(2, babies.map { it.babyId }.toSet().size)
        } finally {
            restored.close()
        }
    }

    @Test
    fun shortenedLegacyUuidCannotClaimAValidBackupIdentity() = withDatabase { db ->
        // Java 接受此缩写，但备份协议从未承认它是完整 UUID，不能借规范化猜测相同宝宝。
        val malformed = BabyInfo(stableId = "1-1-1-1-1", name = "合成旧资料")
        val oldId = db.babyInfoDao().insertBabyInfo(malformed).toInt()
        val valid = "00000001-0001-0001-0001-000000000001"
        val report = BabyBackupImporter(db).importMerge(sampleEnvelope(listOf(valid)), 0)
        assertEquals(1, report.babyInserted)
        assertEquals(0, report.babyMatched)
        assertEquals(malformed.copy(babyId = oldId), db.babyInfoDao().getBabyInfo(oldId))
        val restoredId = db.babyInfoDao().getAllBabyInfo().single { it.stableId == valid }.babyId
        assertNotEquals(oldId, restoredId)
        assertEquals(listOf(restoredId), db.sleepRecordDao().getAllSleepRecords(restoredId).map { it.babyId })
        assertTrue(db.sleepRecordDao().getAllSleepRecords(oldId).isEmpty())
    }

    private suspend fun assertAmbiguousWithoutChanges(db: BabyDatabase, envelope: BabyBackupEnvelope, before: BabyBackupData) {
        val commitId = UUID.randomUUID().toString()
        val pendingBefore = BackupImportJournal(db).pendingCleanup()
        val failure = runCatching { BabyBackupImporter(db).importMerge(envelope, 0, commitId) }.exceptionOrNull()
        assertTrue("歧义必须是明确的导入错误", failure is BackupImportException)
        assertEquals(BackupImportError.AMBIGUOUS_BABY_IDENTITY, (failure as BackupImportException).error)
        assertEquals(before, BabyBackupExporter(db).buildEnvelope().data)
        assertNull(BackupImportJournal(db).get(commitId))
        assertEquals(pendingBefore, BackupImportJournal(db).pendingCleanup())
    }

    private fun sampleEnvelope(identities: List<String>): BabyBackupEnvelope {
        return BabyBackupEnvelope(BABY_BACKUP_VERSION, 1000, data = BabyBackupData(
            babies = identities.mapIndexed { index, identity ->
                BabyInfo(stableId = identity, name = "合成资料$index", gender = "boy",
                    birthWeight = 3f + index, birthHeight = 50f + index).toBackup()
            },
            feedingRecords = identities.mapIndexed { index, identity ->
                FeedingRecord(feedingId = index + 1, feedingStart = 1000, feedingEnd = 2000,
                    feedingDuration = 1000, note = "喂养$index", createdAt = 1000).toBackup(identity)
            },
            sleepRecords = identities.mapIndexed { index, identity ->
                SleepRecord(sleepId = index + 1, sleepStart = 3000, sleepEnd = 4000,
                    sleepDuration = 1000, note = "睡眠$index", createdAt = 1000).toBackup(identity)
            },
            eventRecords = identities.mapIndexed { index, identity ->
                EventRecord(eventId = index + 1, type = EventType.OTHER_CUSTOM, time = 5000,
                    extraData = "{\"name\":\"synthetic\",\"description\":\"$index\"}",
                    note = "事件$index", createdAt = 1000).toBackup(identity)
            },
            childDailyRecords = identities.mapIndexed { index, identity ->
                ChildDailyRecord(recordId = index + 1, recordDate = 6000, weight = 4f + index,
                    height = 50f + index, pic = "合成照片$index", extra = "成长$index").toBackup(identity)
            }
        ))
    }

    /** 重新分配的数据库自增 ID 不属于跨设备身份；其他所有字段和宝宝归属必须原样往返。 */
    private fun normalized(data: BabyBackupData): BabyBackupData = data.copy(
        babies = data.babies.sortedBy { it.stableId },
        feedingRecords = data.feedingRecords.map { it.copy(sourceId = "") }.sortedBy { it.babyStableId },
        sleepRecords = data.sleepRecords.map { it.copy(sourceId = "") }.sortedBy { it.babyStableId },
        eventRecords = data.eventRecords.map { it.copy(sourceId = "") }.sortedBy { it.babyStableId },
        childDailyRecords = data.childDailyRecords.map { it.copy(sourceId = "") }.sortedBy { it.babyStableId }
    )

    private fun newDatabase(): BabyDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, BabyDatabase::class.java
    ).build()

    private fun withDatabase(block: suspend (BabyDatabase) -> Unit) {
        val db = newDatabase()
        try { runBlocking { block(db) } } finally { db.close() }
    }
}
