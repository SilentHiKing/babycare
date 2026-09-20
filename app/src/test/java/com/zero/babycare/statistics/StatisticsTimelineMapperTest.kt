package com.zero.babycare.statistics

import com.zero.babycare.statistics.mapper.StatisticsTimelineMapper
import com.zero.babycare.statistics.model.TimelineEditTarget
import com.zero.babydata.entity.CustomEventData
import com.zero.babydata.entity.ChildDailyRecord
import com.zero.babydata.entity.DiaperData
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.GrowthData
import com.zero.babydata.entity.MedicineData
import com.zero.babydata.entity.MilestoneData
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.entity.TemperatureData
import com.zero.babydata.entity.VaccineData
import com.zero.common.R as CommonR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class StatisticsTimelineMapperTest {

    @Test
    fun `legacy growth preserves float units identity and unparsed full details`() {
        val raw = "  {\"unknown\":9007199254740993}\n" + "合成备注".repeat(300) + "  "
        val photo = "content://synthetic/photo?raw=1\nhttps://example.invalid/reference"
        val record = ChildDailyRecord(recordId = 77, babyId = 9,
            recordDate = Instant.parse("2026-05-02T13:04:05Z").toEpochMilli(),
            weight = 4.234567f, height = 54.12345f, headCircumference = 37.45678f,
            extra = raw, pic = photo)
        val before = record.copy()
        val imperialMapper = StatisticsTimelineMapper(FakeStrings(), ZoneId.of("Asia/Shanghai"), FakeUnits(true))
        val item = imperialMapper.mapLegacyGrowth(record)
        assertEquals(TimelineEditTarget.LegacyGrowth(77), item.editTarget)
        assertEquals(CommonR.drawable.ic_event_growth, item.iconResId)
        assertEquals(CommonR.color.event_growth, item.colorResId)
        assertEquals("21:04", item.timeText)
        assertEquals("体重：4.234567kg · 身高：54.12345cm · 头围：37.45678cm", item.detailText)
        assertEquals(raw.take(500), item.noteText)
        val detail = requireNotNull(item.legacyGrowthDetail)
        assertEquals(9, detail.babyId)
        assertEquals(77, detail.recordId)
        assertTrue(detail.contentText.contains("2026-05-02 21:04:05"))
        assertTrue(detail.contentText.contains(raw))
        assertTrue(detail.contentText.contains(photo))
        assertTrue(detail.contentText.endsWith("旧版记录不计入成长趋势与百分位"))
        assertEquals(before, record)
    }

    @Test
    fun `legacy row without measurements remains visible with its original note`() {
        val item = mapper.mapLegacyGrowth(ChildDailyRecord(recordId = 78, babyId = 9,
            recordDate = 1_000, extra = "只有备注"))
        assertEquals("未填写测量值", item.detailText)
        assertEquals("只有备注", item.noteText)
        assertTrue(requireNotNull(item.legacyGrowthDetail).contentText.contains("备注：只有备注"))
        assertTrue(!item.detailText.contains("0kg") && !item.detailText.contains("0cm"))
    }

    @Test
    fun `medicine stable codes and legacy units share localized timeline labels`() {
        listOf("tablet", "片", "錠", "정").forEach { unit ->
            val item = mapper.mapEvent(EventRecord(babyId = 1, type = EventType.HEALTH_MEDICINE, time = 1_000,
                extraData = MedicineData("合成药品", "1", unit).toJson()))
            assertTrue(item.detailText.contains("1片"))
        }
        val unknown = mapper.mapEvent(EventRecord(babyId = 1, type = EventType.HEALTH_MEDICINE, time = 1_000,
            extraData = MedicineData("合成药品", "1", "spray").toJson()))
        assertTrue(unknown.detailText.contains("1spray"))
    }

    private val mapper = StatisticsTimelineMapper(
        strings = FakeStrings(),
        zone = ZoneId.of("Asia/Shanghai"),
        units = FakeUnits()
    )

    @Test
    fun `overnight records show the original start date and keep their edit identity`() {
        val feeding = FeedingRecord(feedingId = 51, babyId = 1, feedingType = FeedingType.BREAST.type,
            feedingStart = Instant.parse("2026-05-02T15:30:00Z").toEpochMilli(),
            feedingEnd = Instant.parse("2026-05-02T16:30:00Z").toEpochMilli(), feedingDuration = 30 * 60_000L)
        val sleep = SleepRecord(sleepId = 52, babyId = 1,
            sleepStart = Instant.parse("2026-05-02T14:30:00Z").toEpochMilli(),
            sleepEnd = Instant.parse("2026-05-02T23:00:00Z").toEpochMilli(), sleepDuration = 510 * 60_000L)
        val feedingItem = mapper.mapFeeding(feeding)
        val sleepItem = mapper.mapSleep(sleep)
        assertEquals("05-02 23:30", feedingItem.timeText)
        assertEquals("05-02 22:30", sleepItem.timeText)
        assertEquals(TimelineEditTarget.Feeding(51), feedingItem.editTarget)
        assertEquals(TimelineEditTarget.Sleep(52), sleepItem.editTarget)
        // 起点不截成所选日午夜；同日旧格式仍保持紧凑。
        assertEquals("23:30", mapper.mapFeeding(feeding.copy(feedingEnd = feeding.feedingStart + 60_000L)).timeText)
    }

    @Test
    fun `cross year record start includes its year instead of looking like a future December`() {
        val sleep = SleepRecord(sleepId = 53, babyId = 1,
            sleepStart = Instant.parse("2025-12-31T15:00:00Z").toEpochMilli(),
            sleepEnd = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), sleepDuration = 9 * 3_600_000L)
        assertEquals("2025-12-31 23:00", mapper.mapSleep(sleep).timeText)
    }

    @Test
    fun `feeding item exposes edit target and semantic feeding color`() {
        val item = mapper.mapFeeding(
            FeedingRecord(
                feedingId = 9,
                babyId = 1,
                feedingType = FeedingType.BREAST.type,
                feedingStart = Instant.parse("2026-05-02T15:25:00Z").toEpochMilli(),
                feedingDuration = 60_000L
            )
        )

        assertEquals(TimelineEditTarget.Feeding(9), item.editTarget)
        assertEquals(CommonR.color.feeding_primary, item.colorResId)
        assertEquals(CommonR.color.feeding_bg, item.surfaceColorResId)
        assertTrue(item.titleText.contains("母乳"))
        assertTrue(item.detailText.contains("1分钟"))
    }

    @Test
    fun `feeding detail keeps amount and breast side information`() {
        val breast = mapper.mapFeeding(
            FeedingRecord(
                feedingId = 10,
                babyId = 1,
                feedingType = FeedingType.BREAST.type,
                feedingStart = Instant.parse("2026-05-02T15:25:00Z").toEpochMilli(),
                feedingDuration = 180_000L,
                feedingDurationBreastLeft = 60_000L,
                feedingDurationBreastRight = 120_000L
            )
        )
        val formula = mapper.mapFeeding(
            FeedingRecord(
                feedingId = 11,
                babyId = 1,
                feedingType = FeedingType.FORMULA.type,
                feedingStart = Instant.parse("2026-05-02T16:25:00Z").toEpochMilli(),
                feedingDuration = 120_000L,
                feedingAmount = 90
            )
        )

        assertTrue(breast.detailText.contains("左侧1分钟"))
        assertTrue(breast.detailText.contains("右侧2分钟"))
        assertTrue(formula.detailText.contains("90ml"))
        assertTrue(formula.detailText.contains("2分钟"))
    }

    @Test
    fun `sleep item exposes sleep target and duration detail without separate end time`() {
        val item = mapper.mapSleep(
            SleepRecord(
                sleepId = 7,
                babyId = 1,
                sleepStart = Instant.parse("2026-05-02T14:30:00Z").toEpochMilli(),
                sleepEnd = Instant.parse("2026-05-02T15:00:00Z").toEpochMilli(),
                sleepDuration = 30 * 60_000L
            )
        )

        assertEquals(TimelineEditTarget.Sleep(7), item.editTarget)
        assertEquals(CommonR.color.sleep_primary, item.colorResId)
        assertTrue(item.detailText.contains("30分钟"))
    }

    @Test
    fun `diaper event item exposes event target color and diaper detail`() {
        val item = mapper.mapEvent(
            EventRecord(
                eventId = 5,
                babyId = 1,
                type = EventType.DIAPER_WET,
                time = Instant.parse("2026-05-02T13:00:00Z").toEpochMilli(),
                extraData = DiaperData(
                    urineAmount = DiaperData.URINE_AMOUNT_MUCH
                ).toJson()
            )
        )

        assertEquals(TimelineEditTarget.Event(5), item.editTarget)
        assertEquals(CommonR.color.event_diaper, item.colorResId)
        assertEquals(CommonR.color.event_diaper_light, item.surfaceColorResId)
        assertTrue(item.titleText.contains("尿"))
        assertTrue(item.detailText.contains("小便量：多"))
    }

    @Test
    fun `temperature and growth details are formatted from extra data`() {
        val temperature = mapper.mapEvent(
            EventRecord(
                eventId = 12,
                babyId = 1,
                type = EventType.HEALTH_TEMPERATURE,
                time = Instant.parse("2026-05-02T13:00:00Z").toEpochMilli(),
                extraData = TemperatureData(37.6, TemperatureData.LOCATION_FOREHEAD).toJson()
            )
        )
        val growth = mapper.mapEvent(
            EventRecord(
                eventId = 13,
                babyId = 1,
                type = EventType.GROWTH_HEIGHT,
                time = Instant.parse("2026-05-02T13:00:00Z").toEpochMilli(),
                extraData = GrowthData(76.0, GrowthData.UNIT_CM).toJson()
            )
        )

        assertTrue(temperature.titleText.contains("体温"))
        assertTrue(temperature.detailText.contains("37.6℃"))
        assertTrue(temperature.detailText.contains("额温"))
        assertTrue(growth.titleText.contains("身高"))
        assertTrue(growth.detailText.contains("76cm"))
    }

    @Test
    fun `medicine and vaccine details keep names dosage and clinic fields`() {
        val medicine = mapper.mapEvent(
            EventRecord(
                eventId = 20,
                babyId = 1,
                type = EventType.HEALTH_MEDICINE,
                time = Instant.parse("2026-05-02T13:00:00Z").toEpochMilli(),
                extraData = MedicineData(name = "退热药", dosage = "2", unit = "ml").toJson()
            )
        )
        val vaccine = mapper.mapEvent(
            EventRecord(
                eventId = 21,
                babyId = 1,
                type = EventType.HEALTH_VACCINE,
                time = Instant.parse("2026-05-02T13:00:00Z").toEpochMilli(),
                extraData = VaccineData(
                    name = "乙肝",
                    dose = 2,
                    site = "左臂",
                    clinic = "社区医院",
                    batchNumber = "B001"
                ).toJson()
            )
        )

        assertTrue(medicine.detailText.contains("退热药"))
        assertTrue(medicine.detailText.contains("2ml"))
        assertTrue(vaccine.detailText.contains("乙肝"))
        assertTrue(vaccine.detailText.contains("第2剂"))
        assertTrue(vaccine.detailText.contains("社区医院"))
        assertTrue(vaccine.detailText.contains("B001"))
    }

    @Test
    fun `milestone custom and duration event details are preserved`() {
        val milestone = mapper.mapEvent(
            EventRecord(
                eventId = 30,
                babyId = 1,
                type = EventType.MILESTONE_CUSTOM,
                time = Instant.parse("2026-05-02T13:00:00Z").toEpochMilli(),
                extraData = MilestoneData(name = "第一次挥手", description = "看见爸爸").toJson()
            )
        )
        val custom = mapper.mapEvent(
            EventRecord(
                eventId = 31,
                babyId = 1,
                type = EventType.OTHER_CUSTOM,
                time = Instant.parse("2026-05-02T13:00:00Z").toEpochMilli(),
                extraData = CustomEventData(name = "晒太阳", description = "阳台10分钟").toJson()
            )
        )
        val activity = mapper.mapEvent(
            EventRecord(
                eventId = 32,
                babyId = 1,
                type = EventType.ACTIVITY_OUTDOOR,
                time = Instant.parse("2026-05-02T13:00:00Z").toEpochMilli(),
                endTime = Instant.parse("2026-05-02T13:30:00Z").toEpochMilli()
            )
        )

        assertTrue(milestone.detailText.contains("第一次挥手"))
        assertTrue(milestone.detailText.contains("看见爸爸"))
        assertTrue(custom.detailText.contains("晒太阳"))
        assertTrue(custom.detailText.contains("阳台10分钟"))
        assertTrue(activity.detailText.contains("30分钟"))
    }

    private class FakeStrings : StatisticsTimelineMapper.Strings {
        override fun get(resId: Int): String {
            return when (resId) {
                CommonR.string.feeding_type_breast -> "母乳"
                CommonR.string.feeding_type_formula -> "奶粉"
                CommonR.string.feeding_type_mixed -> "混合喂养"
                CommonR.string.feeding_type_solid -> "辅食"
                CommonR.string.feeding_type_other -> "其他喂养"
                CommonR.string.sleeping -> "睡眠"
                CommonR.string.event_diaper_wet_title -> "尿布湿"
                CommonR.string.event_diaper_dirty_title -> "尿布脏"
                CommonR.string.event_diaper_mixed_title -> "尿布混合"
                CommonR.string.event_diaper_dry_title -> "尿布干净"
                CommonR.string.event_health_temperature -> "体温"
                CommonR.string.event_health_medicine -> "用药"
                CommonR.string.event_vaccine_title -> "疫苗"
                CommonR.string.event_doctor_title -> "就医"
                CommonR.string.event_health_symptom -> "症状"
                CommonR.string.event_growth_weight -> "体重"
                CommonR.string.event_growth_height -> "身高"
                CommonR.string.event_growth_head -> "头围"
                CommonR.string.event_milestone_roll -> "翻身"
                CommonR.string.event_milestone_sit -> "独坐"
                CommonR.string.event_milestone_crawl -> "爬行"
                CommonR.string.event_milestone_stand -> "站立"
                CommonR.string.event_milestone_walk -> "行走"
                CommonR.string.event_milestone_first_word -> "第一个词"
                CommonR.string.event_milestone_first_tooth -> "第一颗牙"
                CommonR.string.event_milestone_custom -> "自定义里程碑"
                CommonR.string.event_care_bath -> "洗澡"
                CommonR.string.event_care_nail -> "剪指甲"
                CommonR.string.event_care_skincare -> "护肤"
                CommonR.string.event_care_massage -> "按摩"
                CommonR.string.event_care_nose -> "清洁鼻腔"
                CommonR.string.event_care_ear -> "清洁耳朵"
                CommonR.string.event_activity_outdoor -> "户外"
                CommonR.string.event_activity_tummy -> "趴玩"
                CommonR.string.event_activity_swimming -> "游泳"
                CommonR.string.event_activity_play -> "玩耍"
                CommonR.string.event_other_burp -> "拍嗝"
                CommonR.string.event_other_cry -> "哭闹"
                CommonR.string.event_other_spit_up -> "吐奶"
                CommonR.string.event_other_title -> "其他"
                CommonR.string.left_breast -> "左侧"
                CommonR.string.right_breast -> "右侧"
                CommonR.string.list_separator_dot -> " · "
                CommonR.string.unit_g_abbr -> "g"
                CommonR.string.temperature_unit -> "℃"
                CommonR.string.urine_amount -> "小便量"
                CommonR.string.urine_amount_little -> "少"
                CommonR.string.urine_amount_normal -> "中"
                CommonR.string.urine_amount_much -> "多"
                CommonR.string.stool_color_yellow -> "黄色"
                CommonR.string.stool_color_green -> "绿色"
                CommonR.string.stool_color_brown -> "棕色"
                CommonR.string.stool_color_black -> "黑色"
                CommonR.string.stool_color_red -> "红色"
                CommonR.string.stool_consistency_watery -> "水样"
                CommonR.string.stool_consistency_soft -> "软便"
                CommonR.string.stool_consistency_hard -> "硬便"
                CommonR.string.temperature_location_ear -> "耳温"
                CommonR.string.temperature_location_forehead -> "额温"
                CommonR.string.temperature_location_armpit -> "腋下"
                CommonR.string.temperature_location_oral -> "口腔"
                CommonR.string.temperature_location_rectal -> "肛温"
                CommonR.string.weight_unit -> "kg"
                CommonR.string.height_unit -> "cm"
                CommonR.string.unit_ml_abbr -> "ml"
                CommonR.string.unit_mg_abbr -> "mg"
                CommonR.string.unit_drop -> "滴"
                CommonR.string.unit_tablet -> "片"
                CommonR.string.legacy_growth_record_title -> "历史成长记录"
                CommonR.string.legacy_growth_empty -> "未填写测量值"
                CommonR.string.legacy_growth_photo_reference -> "图片引用"
                CommonR.string.legacy_growth_statistics_hint -> "旧版记录不计入成长趋势与百分位"
                CommonR.string.event_time -> "事件时间"
                CommonR.string.note -> "备注"
                else -> "res-$resId"
            }
        }

        override fun format(resId: Int, vararg args: Any): String {
            return when (resId) {
                CommonR.string.feeding_type_title_format -> "%1\$s喂养".format(*args)
                CommonR.string.feeding_total_minutes_format -> "共%1\$d分钟".format(*args)
                CommonR.string.feeding_breast_side_minutes_format -> "%1\$s%2\$d分钟".format(*args)
                CommonR.string.feeding_amount_unit_format -> "%1\$s%2\$s".format(*args)
                CommonR.string.statistics_value_with_unit_format -> "%1\$s%2\$s".format(*args)
                CommonR.string.min_format -> "%1\$d分钟".format(*args)
                CommonR.string.sleep_duration_format -> "%1\$d小时%2\$d分钟".format(*args)
                CommonR.string.sleep_duration_hours -> "%1\$d小时".format(*args)
                CommonR.string.sleep_duration_minutes -> "%1\$d分钟".format(*args)
                CommonR.string.event_measure_title_format -> "测量%1\$s".format(*args)
                CommonR.string.event_record_title_format -> "记录%1\$s".format(*args)
                CommonR.string.event_milestone_title_format -> "里程碑%1\$s".format(*args)
                CommonR.string.event_label_value_format -> "%1\$s：%2\$s".format(*args)
                CommonR.string.vaccine_dose_format -> "第%1\$d剂".format(*args)
                CommonR.string.event_duration_minutes_format -> "%1\$d分钟".format(*args)
                else -> get(resId).format(*args)
            }
        }
    }

    private class FakeUnits(private val imperial: Boolean = false) : StatisticsTimelineMapper.Units {
        override fun feedingUnit(): String = "ml"
        override fun feedingUnitLabelResId(): Int = CommonR.string.unit_ml_abbr
        override fun weightUnit(): String = if (imperial) "lb" else "kg"
        override fun weightUnitLabelResId(): Int = if (imperial) CommonR.string.unit_lb_abbr else CommonR.string.weight_unit
        override fun heightUnit(): String = if (imperial) "in" else "cm"
        override fun heightUnitLabelResId(): Int = if (imperial) CommonR.string.unit_in_abbr else CommonR.string.height_unit
    }
}
