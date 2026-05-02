package com.zero.babycare.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiResourcePolicyTest {

    @Test
    fun `app module does not keep template colors file`() {
        val appColors = File(repoRoot(), "app/src/main/res/values/colors.xml")

        // 通用颜色必须沉淀到 common，避免 app 模块继续保留模板色或重复主题 token。
        assertFalse(
            "app/src/main/res/values/colors.xml should be removed; define shared colors in common instead.",
            appColors.exists()
        )
    }

    @Test
    fun `shared component layouts do not hardcode text size`() {
        val layoutDir = File(repoRoot(), "components/src/main/res/layout")
        val offenders = layoutDir.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (line.contains("android:textSize=\"")) {
                        "${file.relativeTo(repoRoot()).invariantSeparatorsPath}:${index + 1}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        // 公共组件必须通过 TextAppearance.BabyCare.* 继承字号，避免控件间视觉漂移。
        assertTrue(
            "Hardcoded textSize found in shared component layouts: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `page layouts do not add view shadows`() {
        val roots = listOf(
            File(repoRoot(), "app/src/main/res/layout"),
            File(repoRoot(), "components/src/main/res/layout")
        )
        val offenders = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "xml" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        val normalized = line.trim()
                        val isAllowedZeroCardElevation = normalized.contains("cardElevation=\"0dp\"")
                        val isAllowedNoStateAnimator = normalized.contains("stateListAnimator=\"@null\"")
                        val usesShadow = normalized.contains("android:elevation=\"") ||
                            normalized.contains("android:translationZ=\"") ||
                            (normalized.contains("cardElevation=\"") && !isAllowedZeroCardElevation) ||
                            (normalized.contains("stateListAnimator=\"") && !isAllowedNoStateAnimator) ||
                            normalized.contains("opacity_shadow")

                        if (usesShadow) {
                            "${file.relativeTo(repoRoot()).invariantSeparatorsPath}:${index + 1}"
                        } else {
                            null
                        }
                    }
                }
                .toList()
        }

        // 页面层级通过背景、边框、留白表达，普通 View 不使用阴影或伪阴影分隔线。
        assertTrue(
            "View shadow usage found in page/component layouts: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `theme elevation tokens default to zero`() {
        val styleFiles = listOf(
            File(repoRoot(), "common/src/main/res/values/styles.xml"),
            File(repoRoot(), "common/src/main/res/values-night/styles.xml")
        )

        val offenders = styleFiles.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val normalized = line.trim()
                val isElevationToken = normalized.contains("name=\"elevationSmall\"") ||
                    normalized.contains("name=\"elevationMedium\"")
                if (isElevationToken && !normalized.contains(">0dp<")) {
                    "${file.relativeTo(repoRoot()).invariantSeparatorsPath}:${index + 1}"
                } else {
                    null
                }
            }
        }

        // 历史 elevation token 只保留兼容入口，默认不产生 View 阴影。
        assertTrue(
            "Elevation tokens must default to 0dp: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `core navigation and record icons use unified line style`() {
        val iconPaths = listOf(
            "common/src/main/res/drawable/ic_feeding.xml",
            "common/src/main/res/drawable/ic_sleep.xml",
            "common/src/main/res/drawable/ic_time.xml",
            "common/src/main/res/drawable/ic_statistics.xml",
            "common/src/main/res/drawable/ic_record.xml",
            "common/src/main/res/drawable/ic_record_start.xml",
            "common/src/main/res/drawable/ic_all_babies.xml",
            "common/src/main/res/drawable/ic_settings.xml",
            "common/src/main/res/drawable/ic_baby_awake.xml",
            "common/src/main/res/drawable/ic_baby_feeding.xml",
            "common/src/main/res/drawable/ic_baby_sleeping.xml"
        )

        val offenders = iconPaths.mapNotNull { relativePath ->
            val file = File(repoRoot(), relativePath)
            val content = file.readText()
            val hasLineStroke = content.contains("strokeWidth=")
            val hasLegacyHardcodedStyle = listOf(
                "#000000",
                "#4B535D",
                "#4FC3F7",
                "#9575CD"
            ).any { content.contains(it, ignoreCase = true) }

            if (!hasLineStroke || hasLegacyHardcodedStyle) {
                relativePath
            } else {
                null
            }
        }

        // 高频入口图标必须统一为线性矢量，不保留旧的黑色/糖果色填充风格。
        assertTrue(
            "Core icons should use unified line style: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `statistics structure charts use semantic colors`() {
        val source = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/statistics/StatisticsViewModel.kt"
        ).readText()

        // 统计结构图必须使用喂养、排泄、健康等语义色，不能复用封面装饰色。
        assertFalse(
            "Statistics structure charts should not use decorative color_cover_* resources.",
            source.contains("color_cover_")
        )
    }

    @Test
    fun `event and quick record icons use soft utility line style`() {
        val iconDir = File(repoRoot(), "common/src/main/res/drawable")
        val eventIcons = iconDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("ic_event_") && it.extension == "xml" }
            .toList()

        val offenders = eventIcons.mapNotNull { file ->
            val content = file.readText()
            val hasStroke = content.contains("strokeWidth=")
            val hasVisibleFill = Regex("""fillColor="(?!@android:color/transparent)[^"]+"""")
                .containsMatchIn(content)
            val hasHardcodedColor = Regex("""#[0-9A-Fa-f]{6,8}""").containsMatchIn(content)

            if (!hasStroke || hasVisibleFill || hasHardcodedColor) {
                file.relativeTo(repoRoot()).invariantSeparatorsPath
            } else {
                null
            }
        }

        // 快速记录与记录事件页图标必须统一为现代线性风格，避免旧式实心糖果图标。
        assertTrue(
            "Event icons must be stroked, tintable, and free of visible fills/hardcoded colors: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `record controls avoid tinted legacy surface backgrounds`() {
        val drawablePaths = listOf(
            "common/src/main/res/drawable/selector_radio_bg.xml",
            "common/src/main/res/drawable/bg_r6_block_gridview.xml",
            "common/src/main/res/drawable/bg_event_icon_circle.xml",
            "common/src/main/res/drawable/selector_event_category_bg.xml"
        )

        val offenders = drawablePaths.mapNotNull { relativePath ->
            val content = File(repoRoot(), relativePath).readText()
            val stillUsesLegacySurface = content.contains("colorSurfaceVariant")
            val missesWarmControlToken = !content.contains("control_surface_default") &&
                !content.contains("event_icon_surface") &&
                !content.contains("control_surface_selected")

            if (stillUsesLegacySurface || missesWarmControlToken) {
                relativePath
            } else {
                null
            }
        }

        // 记录页输入框、未选中分段控件和事件图标底不再复用偏脏的 surfaceVariant。
        assertTrue(
            "Record controls should use Soft Utility 2.0 control surfaces: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `soft utility palette stays warm and lively`() {
        val colors = File(repoRoot(), "common/src/main/res/values/colors_base.xml").readText()
        val expectedColors = listOf(
            "feeding_primary\">#247BA0",
            "sleep_primary\">#6E65B7",
            "event_diaper\">#F59E32",
            "event_health\">#1FAE9B",
            "event_growth\">#5FAE63",
            "event_milestone\">#D95C75",
            "control_surface_default\">#FFFDFC",
            "control_surface_selected\">#FFF3E8"
        )
        val missing = expectedColors.filterNot { colors.contains(it) }

        // Soft Utility 2.0 不能退回灰冷、老气或脏粉控件色；主语义色应活泼但低噪音。
        assertTrue(
            "Soft Utility 2.0 warm palette tokens are missing or changed: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `event selection states avoid legacy heavy accents`() {
        val categoryAdapter = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/home/record/event/EventCategoryAdapter.kt"
        ).readText()
        val subtypeAdapter = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/home/record/event/EventSubtypeAdapter.kt"
        ).readText()

        val offenders = buildList {
            if (categoryAdapter.contains("com.zero.common.R.color.White100")) {
                add("category selected icon should not turn white on a light warm chip")
            }
            if (subtypeAdapter.contains("com.zero.common.R.color.darkblue")) {
                add("subtype selected border should not use legacy darkblue")
            }
            if (subtypeAdapter.contains("selectedIndicator.visibility = if (isSelected) View.VISIBLE")) {
                add("subtype selection should use a soft border instead of a bottom bar")
            }
            if (!subtypeAdapter.contains("control_surface_selected")) {
                add("subtype selected card should reuse the Soft Utility selected surface")
            }
        }

        // 事件页选中态应是轻量描边和浅暖底，不能退回白字实色块或旧蓝色强调。
        assertTrue(
            "Event selection state still uses heavy or legacy accents: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `quick record uses soft icon surfaces instead of colored text only`() {
        val quickRecordLayout = File(
            repoRoot(),
            "app/src/main/res/layout/item_quick_record.xml"
        ).readText()
        val quickRecordAdapter = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/home/QuickRecordAdapter.kt"
        ).readText()

        val offenders = buildList {
            if (!quickRecordLayout.contains("@+id/iconContainer")) {
                add("quick record item should have a stable icon surface container")
            }
            if (!quickRecordLayout.contains("@drawable/bg_event_icon_circle")) {
                add("quick record icon should sit on the shared warm event icon surface")
            }
            if (!quickRecordAdapter.contains("lightColorResId")) {
                add("quick record adapter should apply category light surfaces")
            }
            if (quickRecordAdapter.contains("tvName.setTextColor(color)")) {
                add("quick record label should not rely on saturated category color")
            }
        }

        // 首页快速记录需要温暖轻量的图标面，不能只用旧式高饱和彩色文字表达类别。
        assertTrue(
            "Quick record item still looks like legacy colored-text shortcuts: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `feeding solid food chips align with feeding type selection states`() {
        val categoryBg = File(
            repoRoot(),
            "common/src/main/res/drawable/selector_solid_category_bg.xml"
        ).readText()
        val subtypeBg = File(
            repoRoot(),
            "common/src/main/res/drawable/selector_solid_subtype_bg.xml"
        ).readText()
        val radioText = File(
            repoRoot(),
            "common/src/main/res/color/selector_radio_text_color.xml"
        ).readText()
        val feedingTypeBg = File(
            repoRoot(),
            "common/src/main/res/drawable/selector_radio_bg.xml"
        ).readText()
        val styles = File(repoRoot(), "common/src/main/res/values/styles.xml").readText()
        val feedingSource = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/home/record/FeedingRecordFragment.kt"
        ).readText()
        val solidStyles = Regex(
            """<style name="Solid(?:Category|Subtype)RadioButton">[\s\S]*?</style>"""
        ).findAll(styles).map { it.value }.toList()

        val offenders = buildList {
            if (categoryBg.contains("semantics_blue") || subtypeBg.contains("semantics_blue")) {
                add("solid food chips still use legacy semantics_blue")
            }
            if (!categoryBg.contains("control_surface_selected") || !subtypeBg.contains("control_surface_selected")) {
                add("solid food selected backgrounds should use Soft Utility selected surface")
            }
            if (!feedingTypeBg.contains("?attr/colorBrand") ||
                !categoryBg.contains("?attr/colorBrand") ||
                !subtypeBg.contains("?attr/colorBrand")
            ) {
                add("solid food selected borders should follow feeding type brand color")
            }
            if (!radioText.contains("?attr/colorBrand")) {
                add("feeding type text selector should use brand color for selected text")
            }
            if (solidStyles.size != 2 ||
                solidStyles.any {
                    it.contains("selector_solid_food_text_color") ||
                        !it.contains("@color/selector_radio_text_color")
                }
            ) {
                add("solid food styles should reuse the feeding type radio text selector")
            }
            if (!feedingSource.contains("selector_radio_text_color") ||
                feedingSource.contains("selector_solid_food_text_color")
            ) {
                add("dynamic solid subtype chips should reuse feeding type radio text selector")
            }
            if (!feedingSource.contains("ContextCompat.getColorStateList") ||
                feedingSource.contains("getColorStateList(com.zero.common.R.color.selector_radio_text_color, null")
            ) {
                add("dynamic solid subtype text selector should be resolved with the themed context")
            }
            if (!feedingSource.contains("TextAppearance_BabyCare_Label") || feedingSource.contains("textSize = 12f")) {
                add("dynamic solid subtype chips should reuse typography tokens instead of hardcoded text size")
            }
        }

        // 辅食位于喂养类型下方，选中态颜色必须跟上方分段控件一致，避免同页出现两套状态色。
        assertTrue(
            "Solid food chip colors are not aligned with feeding type selection states: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `feeding form labels and time fields stay visually aligned`() {
        val feedingLayout = File(
            repoRoot(),
            "app/src/main/res/layout/fragment_feeding_record.xml"
        ).readText()
        val timePanel = File(
            repoRoot(),
            "components/src/main/res/layout/view_record_timer_panel.xml"
        ).readText()
        val timeFieldBg = File(
            repoRoot(),
            "common/src/main/res/drawable/bg_record_time_field.xml"
        ).readText()

        val insetLabelPattern = Regex(
            """android:paddingHorizontal="10dp"[\s\S]{0,160}android:text="@string/(solid_food_type|solid_food_name|solid_food_amount|other_food_name|feeding_amount|note)""""
        )
        val offenders = buildList {
            if (insetLabelPattern.containsMatchIn(feedingLayout)) {
                add("feeding form labels still have extra horizontal padding and will not align with inputs")
            }
            if (!timePanel.contains("@drawable/bg_record_time_field")) {
                add("time fields should use the dedicated light record time background")
            }
            if (!timePanel.contains("@drawable/ic_time") || timePanel.contains("?attr/ic_color_add")) {
                add("time fields should use the line time icon instead of the generic add icon")
            }
            if (!timePanel.contains("fontFeatureSettings=\"tnum\"")) {
                add("time values should use tabular numbers for calmer scanning")
            }
            if (timePanel.contains("center_vertical|end")) {
                add("end time label should align to its field start instead of floating to the right")
            }
            if (timePanel.contains("TextAppearance.BabyCare.Body1")) {
                add("time values should not use oversized Body1 styling")
            }
            if (!timeFieldBg.contains("control_surface_default") || !timeFieldBg.contains("control_border_default")) {
                add("time field background should use Soft Utility control tokens")
            }
        }

        // 标题、字段和时间值都要落在同一视觉节奏内，避免截图中的错位和大号时间块压迫感。
        assertTrue(
            "Feeding form alignment or time field treatment is off: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `sleep note label aligns with note input`() {
        val sleepLayout = File(
            repoRoot(),
            "app/src/main/res/layout/fragment_sleep_record.xml"
        ).readText()

        val insetNoteLabelPattern = Regex(
            """android:paddingHorizontal="10dp"[\s\S]{0,120}android:text="@string/note""""
        )

        // 备注标题和输入框都处在同一个表单列内，标题不能额外缩进，否则会和输入框左边界错位。
        assertFalse(
            "Sleep note label should not have extra horizontal padding before the note input.",
            insetNoteLabelPattern.containsMatchIn(sleepLayout)
        )
    }

    @Test
    fun `confirm dialog cancel button uses visible secondary action style`() {
        val popupLayout = File(
            repoRoot(),
            "components/src/main/res/layout/popup_confirm_dialog.xml"
        ).readText()
        val cancelBg = File(
            repoRoot(),
            "components/src/main/res/drawable/bg_btn_cancel.xml"
        ).readText()
        val confirmBg = File(
            repoRoot(),
            "components/src/main/res/drawable/bg_btn_confirm.xml"
        ).readText()

        val offenders = buildList {
            if (!popupLayout.contains("android:id=\"@+id/tv_cancel\"") ||
                !popupLayout.contains("TextAppearance.BabyCare.Action") ||
                !popupLayout.contains("android:textColor=\"?attr/colorBrand\"")
            ) {
                add("cancel button should use action typography and brand text color")
            }
            if (!popupLayout.contains("android:layout_height=\"48dp\"")) {
                add("dialog buttons should keep a 48dp touch target")
            }
            if (!cancelBg.contains("control_surface_default") ||
                !cancelBg.contains("control_surface_selected") ||
                !cancelBg.contains("control_border_default") ||
                !cancelBg.contains("?attr/colorBrand")
            ) {
                add("cancel button background should have a visible soft surface, border, and pressed state")
            }
            if (Regex("""#[0-9A-Fa-f]{6,8}""").containsMatchIn(confirmBg)) {
                add("confirm button pressed state should not hardcode legacy color values")
            }
        }

        // 弹窗的取消按钮是明确的次要操作，不能弱到像禁用态；同时确认按钮不能保留旧蓝色按压态。
        assertTrue(
            "Confirm dialog action buttons are not aligned with Soft Utility 2.0: $offenders",
            offenders.isEmpty()
        )
    }

    private fun repoRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir")) { "user.dir is not set" }
        var dir: File? = File(userDir).canonicalFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        return requireNotNull(dir) { "Cannot locate repository root from user.dir" }
    }
}
