package com.zero.babydata.entity

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * 事件扩展数据基类
 * 
 * 使用方式：
 * 1. 根据事件类型创建对应的扩展数据对象
 * 2. 调用 toJson() 存入 EventRecord.extraData
 * 3. 读取时调用对应的 fromJson() 方法解析
 */
sealed class EventExtraData {
    
    companion object {
        private val gson = Gson()
        
        /**
         * 根据事件类型解析扩展数据
         * 
         * 注意：喂养类（包括辅食）使用 FeedingRecord + SolidFoodType，不在此处理
         */
        fun parse(type: Int, json: String): EventExtraData? {
            if (json.isBlank()) return null
            return try {
                when {
                    EventType.isDiaper(type) -> gson.fromJson(json, DiaperData::class.java)
                    EventType.isGrowth(type) -> gson.fromJson(json, GrowthData::class.java)
                    type == EventType.HEALTH_TEMPERATURE -> gson.fromJson(json, TemperatureData::class.java)
                    type == EventType.HEALTH_MEDICINE -> gson.fromJson(json, MedicineData::class.java)
                    type == EventType.HEALTH_VACCINE -> gson.fromJson(json, VaccineData::class.java)
                    EventType.isMilestone(type) -> gson.fromJson(json, MilestoneData::class.java)
                    else -> gson.fromJson(json, GenericData::class.java)
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    fun toJson(): String = gson.toJson(this)
}

/**
 * 换尿布扩展数据
 */
data class DiaperData(
    /** 大便颜色：yellow, green, brown, black, red */
    @SerializedName("color")
    val color: String? = null,
    
    /** 大便性状：normal(正常), loose(稀), hard(硬), watery(水样) */
    @SerializedName("consistency")
    val consistency: String? = null,
    
    /** 是否有异常（如血丝、粘液） */
    @SerializedName("abnormal")
    val abnormal: Boolean = false,
    
    /** 异常描述 */
    @SerializedName("abnormalDesc")
    val abnormalDesc: String? = null
) : EventExtraData() {
    companion object {
        // 颜色常量
        const val COLOR_YELLOW = "yellow"
        const val COLOR_GREEN = "green"
        const val COLOR_BROWN = "brown"
        const val COLOR_BLACK = "black"
        const val COLOR_RED = "red"
        
        // 性状常量
        const val CONSISTENCY_NORMAL = "normal"
        const val CONSISTENCY_LOOSE = "loose"
        const val CONSISTENCY_HARD = "hard"
        const val CONSISTENCY_WATERY = "watery"
        
        fun fromJson(json: String): DiaperData? = try {
            Gson().fromJson(json, DiaperData::class.java)
        } catch (e: Exception) { null }
    }
}

/**
 * 生长测量扩展数据
 */
data class GrowthData(
    /** 数值 */
    @SerializedName("value")
    val value: Double,
    
    /** 单位：kg, g, cm, mm */
    @SerializedName("unit")
    val unit: String
) : EventExtraData() {
    companion object {
        const val UNIT_KG = "kg"
        const val UNIT_G = "g"
        const val UNIT_CM = "cm"
        const val UNIT_MM = "mm"
        
        fun fromJson(json: String): GrowthData? = try {
            Gson().fromJson(json, GrowthData::class.java)
        } catch (e: Exception) { null }
    }
}

/**
 * 体温扩展数据
 */
data class TemperatureData(
    /** 温度值（摄氏度） */
    @SerializedName("value")
    val value: Double,
    
    /** 测量位置：ear(耳温), forehead(额温), armpit(腋下), oral(口腔), rectal(肛温) */
    @SerializedName("location")
    val location: String = LOCATION_EAR
) : EventExtraData() {
    companion object {
        const val LOCATION_EAR = "ear"
        const val LOCATION_FOREHEAD = "forehead"
        const val LOCATION_ARMPIT = "armpit"
        const val LOCATION_ORAL = "oral"
        const val LOCATION_RECTAL = "rectal"
        
        fun fromJson(json: String): TemperatureData? = try {
            Gson().fromJson(json, TemperatureData::class.java)
        } catch (e: Exception) { null }
    }
    
    /**
     * 判断是否发烧
     */
    fun isFever(): Boolean = when (location) {
        LOCATION_RECTAL -> value >= 38.0
        LOCATION_EAR -> value >= 38.0
        LOCATION_ORAL -> value >= 37.8
        LOCATION_ARMPIT -> value >= 37.3
        LOCATION_FOREHEAD -> value >= 37.5
        else -> value >= 37.5
    }
}

/**
 * 用药扩展数据
 */
data class MedicineData(
    /** 药品名称 */
    @SerializedName("name")
    val name: String,
    
    /** 剂量 */
    @SerializedName("dosage")
    val dosage: String? = null,
    
    /** 单位：ml, mg, 滴, 片 */
    @SerializedName("unit")
    val unit: String? = null
) : EventExtraData() {
    companion object {
        fun fromJson(json: String): MedicineData? = try {
            Gson().fromJson(json, MedicineData::class.java)
        } catch (e: Exception) { null }
    }
}

/**
 * 疫苗扩展数据
 */
data class VaccineData(
    /** 疫苗名称 */
    @SerializedName("name")
    val name: String,
    
    /** 接种部位 */
    @SerializedName("site")
    val site: String? = null,
    
    /** 批号 */
    @SerializedName("batchNumber")
    val batchNumber: String? = null,
    
    /** 接种单位 */
    @SerializedName("clinic")
    val clinic: String? = null
) : EventExtraData() {
    companion object {
        fun fromJson(json: String): VaccineData? = try {
            Gson().fromJson(json, VaccineData::class.java)
        } catch (e: Exception) { null }
    }
}

// 注意：辅食/饮品相关数据使用 FeedingRecord + SolidFoodType 处理
// @see FeedingRecord.solidFoodType
// @see FeedingRecord.foodName
// @see SolidFoodType

/**
 * 里程碑扩展数据
 */
data class MilestoneData(
    /** 自定义里程碑名称 */
    @SerializedName("name")
    val name: String? = null,
    
    /** 描述 */
    @SerializedName("description")
    val description: String? = null,
    
    /** 是否第一次 */
    @SerializedName("isFirst")
    val isFirst: Boolean = true
) : EventExtraData() {
    companion object {
        fun fromJson(json: String): MilestoneData? = try {
            Gson().fromJson(json, MilestoneData::class.java)
        } catch (e: Exception) { null }
    }
}

/**
 * 通用扩展数据（用于简单场景或未来扩展）
 */
data class GenericData(
    /** 通用键值对 */
    @SerializedName("data")
    val data: Map<String, Any>? = null
) : EventExtraData() {
    companion object {
        fun fromJson(json: String): GenericData? = try {
            Gson().fromJson(json, GenericData::class.java)
        } catch (e: Exception) { null }
    }
}

