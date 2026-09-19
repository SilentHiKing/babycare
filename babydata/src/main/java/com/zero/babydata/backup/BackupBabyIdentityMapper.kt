package com.zero.babydata.backup

import com.zero.babydata.entity.BabyInfo
import java.util.UUID

/** 先规划整包身份，再写入；保留历史大小写双行，不能把不同历史宝宝静默折叠。 */
internal object BackupBabyIdentityMapper {

    /** 返回原始备份身份到本地 ID 的映射；null 明确表示需要保留该原始身份新建。 */
    fun plan(incoming: List<BackupBaby>, local: List<BabyInfo>): Map<String, Int?> {
        val exact = local.associateBy { it.stableId }
        val canonical = local.groupBy { canonicalUuid(it.stableId) }
        val targets = HashMap<String, Int?>(incoming.size)
        val occupied = HashSet<Int>()

        // 所有精确匹配先占位，避免备份中较早出现的大小写变体抢走后面明确的目标。
        incoming.forEach { baby ->
            exact[baby.stableId]?.let { existing ->
                if (!occupied.add(existing.babyId)) ambiguous()
                targets[baby.stableId] = existing.babyId
            }
        }
        incoming.forEach { baby ->
            if (targets.containsKey(baby.stableId)) return@forEach
            val identity = canonicalUuid(baby.stableId)
                ?: throw BackupImportException(BackupImportError.INVALID_JSON)
            val candidates = canonical[identity].orEmpty()
            targets[baby.stableId] = when (candidates.size) {
                // 只参考事务开始时的本地快照：空库的历史大小写双行必须分别恢复。
                0 -> null
                1 -> candidates.single().babyId.also { if (!occupied.add(it)) ambiguous() }
                else -> ambiguous()
            }
        }
        return targets
    }

    /** UUID.fromString 也接受部分缩写；只为完整合法 UUID 提供大小写兼容。 */
    private fun canonicalUuid(raw: String): String? =
        runCatching { UUID.fromString(raw).toString() }.getOrNull()
            ?.takeIf { it.equals(raw, ignoreCase = true) }

    private fun ambiguous(): Nothing =
        throw BackupImportException(BackupImportError.AMBIGUOUS_BABY_IDENTITY)
}
