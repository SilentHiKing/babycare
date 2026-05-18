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
    fun `statistics timeline keeps time and content to the right of the rail`() {
        val timelineLayouts = listOf(
            "app/src/main/res/layout/item_timeline_event.xml" to "tvTime",
            "app/src/main/res/layout/item_timeline_feeding.xml" to "tvTime",
            "app/src/main/res/layout/item_timeline_sleep.xml" to "tvStartTime"
        )

        val offenders = timelineLayouts.flatMap { (relativePath, timeViewId) ->
            val content = File(repoRoot(), relativePath).readText()
            buildList {
                if (!content.contains("""android:id="@+id/spaceTimelineRail"""") ||
                    !content.contains("""app:layout_constraintStart_toStartOf="parent"""")
                ) {
                    add("$relativePath should anchor the timeline rail to the left content edge")
                }
                if (!content.contains(
                        """app:layout_constraintStart_toEndOf="@id/spaceTimelineRail""""
                    )
                ) {
                    add("$relativePath should put $timeViewId to the right of the rail")
                }
                if (!content.contains(
                        """app:layout_constraintStart_toStartOf="@id/$timeViewId""""
                    )
                ) {
                    add("$relativePath should align event content with the time column")
                }
                if (content.contains("""android:layout_width="@dimen/statistics_timeline_time_width"""") ||
                    content.contains("""app:layout_constraintStart_toEndOf="@id/$timeViewId"""")
                ) {
                    add("$relativePath should not keep the old separate left time column")
                }
            }
        }

        // 统计页当日记录按时间扫读，轨道只负责定位，时间和内容必须在轨道右侧形成单一阅读列。
        assertTrue(
            "Statistics timeline should use left rail with right-side time/content: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `statistics calendar does not overlap selected today and record states`() {
        val source = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/statistics/widget/BabyCalendarView.kt"
        ).readText()

        // 选中态和今天态已经足够强，记录点不能继续叠在同一个日期 cell 上制造状态噪音。
        assertTrue(
            "Calendar record dots should be suppressed for selected and today cells.",
            source.contains("val shouldDrawRecordDot = hasRecord && !isSelected && !isToday") &&
                source.contains("if (shouldDrawRecordDot)")
        )
    }

    @Test
    fun `statistics calendar expands with continuous animated height`() {
        val source = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/statistics/widget/BabyCalendarView.kt"
        ).readText()

        // 展开/折叠高度必须直接使用浮点动画值；如果用 ceil 后的整数行测量，会一行一行跳变。
        assertTrue(
            "Calendar measurement should use animatedRowCount directly for smooth height changes.",
            source.contains("cellHeight * animatedRowCount") &&
                !source.contains("cellHeight * visibleRowCount")
        )
    }

    @Test
    fun `statistics calendar record dots stay close to their own date`() {
        val source = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/statistics/widget/BabyCalendarView.kt"
        ).readText()

        // 记录点是日期文字的弱提示，不能依赖选中圆半径放到 cell 底部，否则会贴近下一行的今天态。
        assertTrue(
            "Calendar record dots should be positioned from date text, not selected circle radius.",
            source.contains("recordDotTextGap = 4 * density") &&
                source.contains("dateTextSize / 2 + recordDotTextGap") &&
                !source.contains("centerY + selectedRadius + dotRadius + 2")
        )
    }

    @Test
    fun `statistics calendar date selection does not auto collapse month view`() {
        val source = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/statistics/adapter/StatisticsCalendarAdapter.kt"
        ).readText()

        // 月视图用于连续选日期和对比有记录日期，选中某天后不应强制折回周视图。
        assertFalse(
            "Statistics calendar adapter should not auto-collapse to week mode after date selection.",
            source.contains("setViewMode(BabyCalendarView.ViewMode.WEEK)")
        )
    }

    @Test
    fun `statistics calendar navigation arrows use one secondary button treatment`() {
        val layout = File(
            repoRoot(),
            "app/src/main/res/layout/item_statistics_calendar.xml"
        ).readText()

        val offenders = buildList {
            if (!layout.contains("""android:layout_width="@dimen/statistics_calendar_nav_button_size"""") ||
                !layout.contains("""android:layout_height="@dimen/statistics_calendar_nav_button_size"""")
            ) {
                add("month navigation arrows should use the shared 40dp button size")
            }
            if (!layout.contains("""android:padding="@dimen/statistics_calendar_nav_icon_padding"""")) {
                add("month navigation arrows should use the shared icon padding")
            }
            if (!layout.contains("""android:background="?attr/selectableItemBackgroundBorderless"""")) {
                add("month navigation arrows should keep a familiar borderless touch state")
            }
            if (!layout.contains("""app:tint="?attr/colorTextSecondary"""")) {
                add("month navigation arrows should be secondary controls, not hint-level controls")
            }
            if (layout.contains("""android:layout_width="32dp"""") ||
                layout.contains("""android:padding="4dp"""")
            ) {
                add("month navigation arrows should not keep the old small 32dp treatment")
            }
        }

        // 页面返回是主导航，月份切换是页面内次级导航；三枚箭头要共享线性图标和按钮节奏。
        assertTrue(
            "Statistics calendar arrows are visually inconsistent: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `statistics timeline gives the final record enough bottom padding`() {
        val adapter = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/statistics/adapter/TimelineAdapter.kt"
        ).readText()
        val timelineLayouts = listOf(
            "app/src/main/res/layout/item_timeline_event.xml",
            "app/src/main/res/layout/item_timeline_feeding.xml",
            "app/src/main/res/layout/item_timeline_sleep.xml"
        )

        val offenders = buildList {
            if (!adapter.contains("applyTimelineSpacing(spaceBottom, position)") ||
                !adapter.contains("statistics_timeline_last_bottom_gap")
            ) {
                add("TimelineAdapter should enlarge only the last record bottom spacer")
            }
            timelineLayouts.forEach { relativePath ->
                val layout = File(repoRoot(), relativePath).readText()
                if (!layout.contains("""android:layout_height="@dimen/statistics_timeline_item_bottom_gap"""")) {
                    add("$relativePath should use the shared default bottom gap dimen")
                }
            }
        }

        // 当日主区底部留白只需要加强最后一条记录，不能把每条记录都拉散。
        assertTrue(
            "Statistics timeline final record bottom padding is too tight: $offenders",
            offenders.isEmpty()
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
    fun `surface stroke resources use shared hairline token`() {
        val surfaceStrokePaths = listOf(
            "app/src/main/res/drawable/bg_event_record_section.xml",
            "app/src/main/res/drawable/bg_settings_group_surface_border.xml",
            "app/src/main/res/drawable/bg_setting_switch_track.xml",
            "common/src/main/res/drawable/bg_surface_stroke_control_border.xml",
            "common/src/main/res/drawable/bg_surface_group_sides_control_border.xml",
            "common/src/main/res/drawable/bg_r16_surface_stroke_control_border.xml",
            "common/src/main/res/drawable/bg_r16_top_surface_stroke_control_border.xml",
            "common/src/main/res/drawable/bg_r16_bottom_surface_stroke_control_border.xml",
            "common/src/main/res/drawable/bg_r16_top_surface_group_control_border.xml",
            "common/src/main/res/drawable/bg_r16_bottom_surface_group_control_border.xml",
            "common/src/main/res/drawable/bg_r6_block_gridview.xml",
            "common/src/main/res/drawable/bg_r8_metric_tile.xml",
            "common/src/main/res/drawable/bg_record_time_field.xml",
            "common/src/main/res/drawable/selector_radio_bg.xml",
            "common/src/main/res/drawable/selector_event_category_bg.xml",
            "common/src/main/res/drawable/selector_solid_category_bg.xml",
            "common/src/main/res/drawable/selector_solid_subtype_bg.xml",
            "components/src/main/res/drawable/bg_btn_cancel.xml"
        )
        val legacyStrokeWidth = Regex("""android:width="(?:0\.5|0\.75|1|1\.5)dp"""")

        val offenders = surfaceStrokePaths.mapNotNull { relativePath ->
            val content = File(repoRoot(), relativePath).readText()
            val usesSharedHairline = content.contains("@dimen/surface_stroke_width")
            val hardcodesLegacyStrokeWidth = legacyStrokeWidth.containsMatchIn(content)

            if (!usesSharedHairline || hardcodesLegacyStrokeWidth) {
                relativePath
            } else {
                null
            }
        }
        val commonDimens = File(repoRoot(), "common/src/main/res/values/dimens.xml").readText()

        // 普通 surface/control surface 的边界统一成 0.1dp，语义强调边框和图标线宽不进入这套 token。
        assertTrue(
            "Surface + stroke resources should use @dimen/surface_stroke_width=0.1dp: $offenders",
            offenders.isEmpty() && commonDimens.contains("<dimen name=\"surface_stroke_width\">0.1dp</dimen>")
        )
    }

    @Test
    fun `baby info birth measurement units follow settings`() {
        val layout = File(repoRoot(), "app/src/main/res/layout/fragment_update_info.xml").readText()
        val fragment = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/babyinfo/UpdateInfoFragment.kt"
        ).readText()
        val viewModel = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/babyinfo/UpdateInfoViewModel.kt"
        ).readText()

        val offenders = buildList {
            if (!layout.contains("android:id=\"@+id/tvWeightUnit\"") ||
                !layout.contains("android:id=\"@+id/tvHeightUnit\"")
            ) {
                add("birth weight and height unit labels should be bindable views")
            }
            if (layout.contains("@string/unit_g")) {
                add("birth weight should not keep a fixed gram label in the layout")
            }
            if (layout.contains("@string/unit_cm")) {
                add("birth height should not keep a fixed centimeter label in the layout")
            }
            if (!fragment.contains("bindUnitLabels(unitState)") ||
                !fragment.contains("vm.formatBirthWeight") ||
                !fragment.contains("vm.parseBirthWeightToStorage") ||
                !fragment.contains("vm.formatBirthHeight") ||
                !fragment.contains("vm.parseBirthHeightToStorage")
            ) {
                add("UpdateInfoFragment should render and save birth measurements through unit-aware ViewModel APIs")
            }
            if (!viewModel.contains("UnitConfig.getWeightUnit()") ||
                !viewModel.contains("UnitConfig.getHeightUnit()") ||
                !viewModel.contains("birthWeightToDisplay") ||
                !viewModel.contains("birthWeightToStorageGrams")
            ) {
                add("UpdateInfoViewModel should read settings units and preserve BabyInfo storage units")
            }
        }

        // 宝宝信息页不能固定写死克/厘米；显示跟随设置，存储仍保持历史兼容单位。
        assertTrue(
            "Baby info birth measurement units are not connected to settings: $offenders",
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
    fun `event record page resets create state and releases activity timer`() {
        val source = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/home/record/event/EventRecordFragment.kt"
        ).readText()

        val offenders = buildList {
            if (!source.contains("prepareCreateModeState()")) {
                add("create mode should reset transient form state whenever the page is entered")
            }
            if (!source.contains("binding.etNote.setText(\"\")")) {
                add("create mode should clear the retained note EditText")
            }
            if (!source.contains("releaseActivityTimer()") ||
                !source.contains("timerPanel.timerView.release()") ||
                !source.contains("override fun onDestroyView()")
            ) {
                add("activity timer should be released when detail views are removed or the page is destroyed")
            }
        }

        // 主页面使用常驻 Fragment + show/hide，事件记录页必须主动清理临时表单和计时器状态。
        assertTrue(
            "Event record create lifecycle cleanup is incomplete: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `event record save is guarded while database write is pending`() {
        val fragment = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/home/record/event/EventRecordFragment.kt"
        ).readText()
        val viewModel = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/home/record/event/EventRecordViewModel.kt"
        ).readText()
        val repository = File(
            repoRoot(),
            "babydata/src/main/java/com/zero/babydata/room/BabyRepository.kt"
        ).readText()

        val offenders = buildList {
            if (!fragment.contains("if (saveInProgress) return") ||
                !fragment.contains("setSaveInProgress(true)") ||
                !fragment.contains("setSaveInProgress(false)") ||
                !fragment.contains("binding.btnSave.isEnabled = !inProgress")
            ) {
                add("save button should be disabled until the write callback returns")
            }
            if (!fragment.contains("if (view == null) return")) {
                add("setSaveInProgress should not touch binding before onCreateView or after onDestroyView")
            }
            if (!viewModel.contains("onFailure") || !repository.contains("errorCallback")) {
                add("event save should surface repository write failures back to the UI")
            }
        }

        // 父母记录时可能连续点击保存；写入中需要明确防抖，并且数据库失败不能静默。
        assertTrue(
            "Event record save flow is not guarded: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `event record page uses clear sections and stable event type targets`() {
        val page = File(repoRoot(), "app/src/main/res/layout/fragment_event_record.xml").readText()
        val category = File(repoRoot(), "app/src/main/res/layout/item_event_category.xml").readText()
        val subtype = File(repoRoot(), "app/src/main/res/layout/item_event_subtype.xml").readText()
        val noteSectionBg = File(
            repoRoot(),
            "app/src/main/res/drawable/bg_event_record_note_section.xml"
        ).takeIf { it.isFile }?.readText().orEmpty()

        val offenders = buildList {
            listOf("sectionTime", "sectionEventType", "sectionDetail", "sectionNote").forEach { id ->
                if (!page.contains("""android:id="@+id/$id"""")) {
                    add("event record layout should expose $id as a visual section")
                }
            }
            if (!page.contains("@drawable/bg_event_record_section")) {
                add("event record sections should use one surface + stroke container treatment")
            }
            if (!category.contains("""android:minHeight="48dp"""")) {
                add("event category chips should keep a 48dp touch target")
            }
            if (!subtype.contains("""android:minHeight="@dimen/event_record_subtype_min_height"""")) {
                add("event subtype cards should keep a fixed minimum height")
            }
            if (!subtype.contains("""android:maxLines="2"""")) {
                add("event subtype labels should cap wrapping to keep grid rows stable")
            }
            if (Regex("""android:id="@\+id/cardTime"[\s\S]{0,240}selectableItemBackground""")
                    .containsMatchIn(page)
            ) {
                add("event record time card should not show a broad pressed overlay")
            }
            if (!page.contains("""android:background="@drawable/bg_event_record_note_section"""") ||
                noteSectionBg.isBlank() ||
                noteSectionBg.contains("<stroke")
            ) {
                add("event record note section should use a fill-only outer surface and leave the border to the EditText")
            }
        }

        // 记录页需要清晰分区和稳定网格，避免不同类型文案导致控件跳高、视觉散乱。
        assertTrue(
            "Event record layout hierarchy or targets are unstable: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `event category horizontal list keeps parent from stealing horizontal swipes`() {
        val page = File(repoRoot(), "app/src/main/res/layout/fragment_event_record.xml").readText()
        val fragment = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/home/record/event/EventRecordFragment.kt"
        ).readText()
        val touchDelegateFile = File(
            repoRoot(),
            "components/src/main/java/com/zero/components/touch/HorizontalNestedScrollTouchDelegate.kt"
        )
        val touchDelegate = touchDelegateFile.takeIf { it.isFile }?.readText().orEmpty()
        val rvCategoryBlock = Regex(
            """<androidx\.recyclerview\.widget\.RecyclerView[\s\S]*?android:id="@\+id/rvCategory"[\s\S]*?/>"""
        ).find(page)?.value.orEmpty()

        val offenders = buildList {
            if (!rvCategoryBlock.contains("""android:nestedScrollingEnabled="false"""")) {
                add("event category RecyclerView should disable nested scrolling inside NestedScrollView")
            }
            if (!fragment.contains("HorizontalNestedScrollTouchDelegate.attachTo(binding.rvCategory)")) {
                add("EventRecordFragment should use the shared horizontal nested scroll touch delegate")
            }
            if (!fragment.contains("binding.rvCategory.itemAnimator = null")) {
                add("event category RecyclerView should disable item change animations for instant selected state")
            }
            if (!touchDelegateFile.isFile ||
                !touchDelegate.contains("RecyclerView.SimpleOnItemTouchListener") ||
                !touchDelegate.contains("ViewConfiguration.get(recyclerView.context).scaledTouchSlop") ||
                !touchDelegate.contains("ScrollDirectionLock") ||
                !touchDelegate.contains("HORIZONTAL_INTENT_RATIO")
            ) {
                add("horizontal touch delegate should use RecyclerView item touch dispatch with touchSlop direction locking")
            }
            if (!touchDelegate.contains("MotionEvent.ACTION_DOWN") ||
                !touchDelegate.contains("MotionEvent.ACTION_MOVE") ||
                !touchDelegate.contains("MotionEvent.ACTION_UP") ||
                !touchDelegate.contains("MotionEvent.ACTION_CANCEL") ||
                !touchDelegate.contains("requestDisallowInterceptTouchEvent(true)") ||
                !touchDelegate.contains("requestDisallowInterceptTouchEvent(false)")
            ) {
                add("horizontal touch delegate should keep parent intercept disabled until the gesture is resolved")
            }
            if (!touchDelegate.contains("absDx < touchSlop && absDy < touchSlop") ||
                !touchDelegate.contains("directionLock = ScrollDirectionLock.HORIZONTAL") ||
                !touchDelegate.contains("directionLock = ScrollDirectionLock.VERTICAL") ||
                !touchDelegate.contains("canScrollHorizontally")
            ) {
                add("horizontal touch delegate should not release the parent from raw dx/dy noise before touchSlop")
            }
        }

        // 事件大类横向列表在纵向表单里，必须显式降低父级 NestedScrollView 的手势抢占。
        assertTrue(
            "Event category horizontal scrolling can still conflict with vertical scrolling: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `event duration detail does not auto fill start time from page event time`() {
        val fragment = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/home/record/event/EventRecordFragment.kt"
        ).readText()
        val viewModel = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/home/record/event/EventRecordViewModel.kt"
        ).readText()

        val offenders = buildList {
            if (fragment.contains("val startTime = vm.eventTime.value") &&
                fragment.contains("activityTimerController?.setStartTime(startTime, notify = false)")
            ) {
                add("activity detail should not copy the page event time into start input before timer start")
            }
            if (!fragment.contains("vm.durationStartTime.value")) {
                add("activity detail should render only an explicit duration start time")
            }
            if (!viewModel.contains("durationStartTime")) {
                add("EventRecordViewModel should track explicit duration start separately from page event time")
            }
        }

        // 活动类事件的开始时间必须来自开始计时或手动开始时间输入，不能来自页面默认事件时间。
        assertTrue(
            "Event duration start time is still coupled to page event time: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `event detail numeric inputs keep units inside left aligned fields`() {
        val temperature = File(
            repoRoot(),
            "app/src/main/res/layout/layout_event_detail_temperature.xml"
        ).readText()
        val growth = File(repoRoot(), "app/src/main/res/layout/layout_event_detail_growth.xml").readText()
        val medicine = File(repoRoot(), "app/src/main/res/layout/layout_event_detail_medicine.xml").readText()

        val offenders = buildList {
            if (!temperature.contains("""android:id="@+id/layoutTemperatureInput"""") ||
                !temperature.contains("""android:id="@+id/tvTemperatureUnit"""") ||
                temperature.contains("""android:id="@+id/etTemperature"""") &&
                Regex("""android:id="@\+id/etTemperature"[\s\S]{0,260}android:gravity="center"""")
                    .containsMatchIn(temperature) ||
                !Regex("""android:id="@\+id/etTemperature"[\s\S]{0,260}android:background="@null"""")
                    .containsMatchIn(temperature) ||
                !Regex("""android:id="@\+id/etTemperature"[\s\S]{0,260}android:gravity="start\|center_vertical"""")
                    .containsMatchIn(temperature)
            ) {
                add("temperature input should be one left-aligned field with the unit inside")
            }
            if (!growth.contains("""android:id="@+id/layoutGrowthValueInput"""") ||
                !Regex("""android:id="@\+id/etValue"[\s\S]{0,260}android:background="@null"""")
                    .containsMatchIn(growth) ||
                !Regex("""android:id="@\+id/etValue"[\s\S]{0,260}android:gravity="start\|center_vertical"""")
                    .containsMatchIn(growth) ||
                Regex("""android:id="@\+id/etValue"[\s\S]{0,260}android:gravity="center"""")
                    .containsMatchIn(growth)
            ) {
                add("growth value input should match the birth measurement inline unit treatment")
            }
            if (!medicine.contains("""android:id="@+id/layoutDosageInput"""") ||
                !Regex("""android:id="@\+id/etDosage"[\s\S]{0,260}android:background="@null"""")
                    .containsMatchIn(medicine) ||
                !Regex("""android:id="@\+id/etDosage"[\s\S]{0,260}android:gravity="start\|center_vertical"""")
                    .containsMatchIn(medicine) ||
                Regex("""android:id="@\+id/etDosage"[\s\S]{0,260}android:gravity="center"""")
                    .containsMatchIn(medicine)
            ) {
                add("medicine dosage input should keep the editable value left-aligned with the unit selector inside")
            }
        }

        // 带单位的数值输入要和出生体重一致：左侧输入，右侧单位在同一个输入框承托面里。
        assertTrue(
            "Event detail unit inputs are not aligned with the shared inline unit pattern: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `main window applies ime insets for keyboard safe forms`() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        val activityMain = File(repoRoot(), "app/src/main/res/layout/activity_main.xml").readText()
        val statusBarUtil = File(
            repoRoot(),
            "common/src/main/java/com/zero/common/util/StatusBarUtil.kt"
        ).readText()

        val offenders = buildList {
            if (!manifest.contains("""android:windowSoftInputMode="adjustResize"""")) {
                add("MainActivity should request adjustResize for legacy IME behavior")
            }
            if (activityMain.contains("""android:fitsSystemWindows="true"""")) {
                add("activity root should not consume window insets before shared IME handling")
            }
            if (!statusBarUtil.contains("WindowInsetsCompat.Type.ime()") ||
                !statusBarUtil.contains("WindowInsetsCompat.Type.navigationBars()") ||
                !statusBarUtil.contains("coerceAtLeast") ||
                !statusBarUtil.contains("SOFT_INPUT_ADJUST_RESIZE")
            ) {
                add("edge-to-edge setup should apply IME and navigation-bar bottom insets to the root view")
            }
        }

        // 全屏窗口不能只依赖 adjustResize；需要把 IME inset 作为根布局 bottom padding 统一下发。
        assertTrue(
            "Keyboard-safe window inset handling is incomplete: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `base fragments scroll focused edit texts above the ime`() {
        val baseFragment = File(
            repoRoot(),
            "components/src/main/java/com/zero/components/base/BaseFragment.kt"
        ).readText()
        val delegateFile = File(
            repoRoot(),
            "components/src/main/java/com/zero/components/base/KeyboardSafeScrollDelegate.kt"
        )
        val delegate = delegateFile.takeIf { it.isFile }?.readText().orEmpty()

        val offenders = buildList {
            if (!baseFragment.contains("KeyboardSafeScrollDelegate.install(view)") ||
                !baseFragment.contains("keyboardSafeScrollDelegate?.dispose()")
            ) {
                add("BaseFragment should install and dispose the shared keyboard-safe scroll delegate")
            }
            if (!delegateFile.isFile) {
                add("KeyboardSafeScrollDelegate should live in components base for project-wide form pages")
            }
            if (!delegate.contains("OnGlobalFocusChangeListener") ||
                !delegate.contains("newFocus as? EditText") ||
                delegate.contains("setOnFocusChangeListener")
            ) {
                add("keyboard-safe scrolling should observe focus globally without overwriting business focus listeners")
            }
            if (!delegate.contains("setOnTouchListener(touchRevealListener)") ||
                !delegate.contains("MotionEvent.ACTION_UP") ||
                !delegate.contains("attachEditTextTouchListeners(root)") ||
                !delegate.contains("WeakHashMap<EditText, Boolean>")
            ) {
                add("delegate should reveal focused inputs again when the same EditText is tapped while IME is already open")
            }
            if (!delegate.contains("WindowInsetsCompat.Type.ime()") ||
                !delegate.contains("NestedScrollView") ||
                !delegate.contains("ScrollView") ||
                !delegate.contains("getGlobalVisibleRect") ||
                !delegate.contains("offsetDescendantRectToMyCoords") ||
                !delegate.contains("scrollTo(0, scrollParent.scrollY + deltaY)") ||
                !delegate.contains("getWindowVisibleDisplayFrame") ||
                !delegate.contains("visibleFrameBottom") ||
                !delegate.contains("lastStableWindowBottom")
            ) {
                add("delegate should combine IME insets, stable window bounds, legacy visible display frame, and unclipped descendant coordinates")
            }
            if (!delegate.contains("applyKeyboardScrollPadding(scrollParent)") ||
                !delegate.contains("restoreKeyboardScrollPadding()") ||
                !delegate.contains("ScrollPaddingState") ||
                !delegate.contains("clipToPadding = false") ||
                !delegate.contains("setPadding(")
            ) {
                add("delegate should add temporary bottom scroll padding so bottom fields can scroll above the keyboard")
            }
            if (!delegate.contains("containsDescendant(editText)") ||
                !delegate.contains("hasUsableGlobalRect()") ||
                delegate.contains("editText.getGlobalVisibleRect(inputRect)")
            ) {
                add("delegate should ignore inactive fragment roots and must not depend on clipped EditText visible rects")
            }
            if (!delegate.contains("BabyCareKeyboard") ||
                !delegate.contains("Log.isLoggable") ||
                !delegate.contains("Log.d(TAG")
            ) {
                add("delegate should expose opt-in keyboard geometry logs for device-specific diagnosis")
            }
            if (!delegate.contains("removeOnGlobalFocusChangeListener") ||
                !delegate.contains("removeOnLayoutChangeListener")
            ) {
                add("delegate should remove listeners with the fragment view lifecycle")
            }
        }

        // adjustResize 只能改变可用区域，输入框是否滚到键盘上方要由 Fragment 统一处理。
        assertTrue(
            "Focused EditText keyboard reveal behavior is not project-wide safe: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `base fragments disable scroll boundary effects project wide`() {
        val baseFragment = File(
            repoRoot(),
            "components/src/main/java/com/zero/components/base/BaseFragment.kt"
        ).readText()
        val delegateFile = File(
            repoRoot(),
            "components/src/main/java/com/zero/components/base/ScrollBoundaryEffectDelegate.kt"
        )
        val delegate = delegateFile.takeIf { it.isFile }?.readText().orEmpty()
        val uiGuidelines = File(repoRoot(), "docs/ui-guidelines.md").readText()

        val offenders = buildList {
            if (!baseFragment.contains("ScrollBoundaryEffectDelegate.applyTo(view)")) {
                add("BaseFragment should apply the project-wide scroll boundary policy")
            }
            if (!delegateFile.isFile ||
                !delegate.contains("View.OVER_SCROLL_NEVER") ||
                !delegate.contains("root.post") ||
                !delegate.contains("ViewGroup") ||
                !delegate.contains("childCount")
            ) {
                add("scroll boundary delegate should recursively disable overscroll for the current view tree")
            }
            if (!uiGuidelines.contains("滚动到内容边界时不得显示发光、拉伸或回弹到头动画") ||
                !uiGuidelines.contains("android:overScrollMode=\"never\"") ||
                !uiGuidelines.contains("View.OVER_SCROLL_NEVER")
            ) {
                add("UI guidelines should document the no-overscroll-edge-effect rule")
            }
        }

        // Android 12+ 的 stretch 和旧系统 glow 都属于边界 EdgeEffect，BabyCare 页面统一关闭。
        assertTrue(
            "Scroll boundary edge effects should be disabled project-wide: $offenders",
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
        val timeEditText = File(
            repoRoot(),
            "components/src/main/java/com/zero/components/widget/TimeEditText.kt"
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
            if (!timePanel.contains("android:layout_height=\"56dp\"") ||
                !timePanel.contains("android:maxLines=\"2\"") ||
                timePanel.contains("android:scrollHorizontally=\"true\"")
            ) {
                add("time fields should use a two-line compact date/time display instead of a cramped one-line timestamp")
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
            if (!timeEditText.contains("SpannableString") ||
                !timeEditText.contains("record_time_date_current_year_format") ||
                !timeEditText.contains("record_time_clock_format") ||
                !timeEditText.contains("AbsoluteSizeSpan(RECORD_TIME_CLOCK_TEXT_SIZE_SP") ||
                !timeEditText.contains("ForegroundColorSpan(resolveThemeColor(com.zero.common.R.attr.colorTextHint))") ||
                !timeEditText.contains("TypefaceSpan(\"sans-serif-medium\")")
            ) {
                add("TimeEditText should render date as weak context and HH:mm:ss as the primary readable value")
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

    @Test
    fun `app module uses shared picker sheets instead of raw picker APIs`() {
        val sourceRoot = File(repoRoot(), "app/src/main/java")
        val forbiddenImports = listOf(
            "import android.app.DatePickerDialog",
            "import android.app.TimePickerDialog",
            "import com.lxj.xpopup.XPopup",
            "import com.lxj.xpopupext.listener.CommonPickerListener",
            "import com.lxj.xpopupext.listener.TimePickerListener",
            "import com.lxj.xpopupext.popup.CommonPickerPopup",
            "import com.lxj.xpopupext.popup.TimePickerPopup"
        )

        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val content = file.readText()
                forbiddenImports.mapNotNull { forbidden ->
                    if (content.contains(forbidden)) {
                        "${file.relativeTo(repoRoot()).invariantSeparatorsPath} uses $forbidden"
                    } else {
                        null
                    }
                }
            }
            .toList()

        // 选择器和时间选择器必须沉淀在 components，避免业务页继续继承第三方默认视觉。
        assertTrue(
            "Business pages should use DialogHelper picker APIs instead of raw picker classes: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `shared picker sheets use flat list visuals`() {
        val dateTimeLayout = File(
            repoRoot(),
            "components/src/main/res/layout/popup_babycare_date_time_sheet.xml"
        ).readText()
        val choiceLayout = File(
            repoRoot(),
            "components/src/main/res/layout/popup_babycare_choice_sheet.xml"
        ).readText()
        val dateTimeSource = File(
            repoRoot(),
            "components/src/main/java/com/zero/components/base/util/BabyCareDateTimePopup.kt"
        ).readText()
        val dialogHelperSource = File(
            repoRoot(),
            "components/src/main/java/com/zero/components/base/util/DialogHelper.kt"
        ).readText()
        val choiceSource = File(
            repoRoot(),
            "components/src/main/java/com/zero/components/base/util/BabyCareChoicePopup.kt"
        ).readText()
        val bottomSheetSource = File(
            repoRoot(),
            "components/src/main/java/com/zero/components/base/util/BabyCareBottomSheetPopup.kt"
        ).readText()
        val timerControllerSource = File(
            repoRoot(),
            "app/src/main/java/com/zero/babycare/home/record/RecordTimerController.kt"
        ).readText()

        val offenders = buildList {
            if (dateTimeLayout.contains("com.contrarywind.view.WheelView") ||
                dateTimeSource.contains("com.contrarywind.view.WheelView") ||
                dateTimeSource.contains("ArrayWheelAdapter")
            ) {
                add("date/time picker should not use the 3D wheel implementation")
            }
            if (!dateTimeLayout.contains("androidx.recyclerview.widget.RecyclerView")) {
                add("date/time picker should render flat scroll columns with RecyclerView")
            }
            if (!dateTimeLayout.contains("@drawable/bg_picker_fade_top") ||
                !dateTimeLayout.contains("@drawable/bg_picker_fade_bottom")
            ) {
                add("date/time picker should use top and bottom gradient masks")
            }
            if (dateTimeLayout.contains("android:id=\"@+id/tv_title\"")) {
                add("date/time picker should hide the center title to match the quiet sheet header")
            }
            if (!dateTimeLayout.contains("android:layout_height=\"48dp\"")) {
                add("date/time picker header should use the same compact 48dp height as choice picker")
            }
            if (!dateTimeLayout.contains("android:paddingBottom=\"8dp\"")) {
                add("date/time picker should avoid excessive bottom whitespace")
            }
            if (!dateTimeSource.contains("DATE_TIME_ITEM_TEXT_SIZE_SP = 16f")) {
                add("date/time picker column text should use the compact 16sp size")
            }
            if (!dialogHelperSource.contains("MONTH_DAY_TIME") ||
                !dialogHelperSource.contains("MONTH_DAY_TIME_SECOND") ||
                !dialogHelperSource.contains("DATE_TIME_SECOND") ||
                !dialogHelperSource.contains("showMonthDayTimeSheet") ||
                !dialogHelperSource.contains("showMonthDayTimeSecondSheet") ||
                !dialogHelperSource.contains("showDateTimeSecondSheet")
            ) {
                add("date/time picker should expose date-time and month-day-time modes with optional seconds")
            }
            if (!dateTimeLayout.contains("android:id=\"@+id/picker_date_time_group_gap\"")) {
                add("date/time picker should visually separate month/day and hour/minute groups")
            }
            if (!dateTimeLayout.contains("android:id=\"@+id/picker_date_group\"") ||
                !dateTimeLayout.contains("android:id=\"@+id/picker_time_group\"") ||
                !dateTimeLayout.contains("android:id=\"@+id/list_second\"")
            ) {
                add("date/time picker should lay out date columns and time columns as two explicit groups")
            }
            if (dateTimeLayout.contains("picker_selection_top_line") ||
                dateTimeLayout.contains("picker_date_top_line") ||
                dateTimeLayout.contains("picker_time_top_line")
            ) {
                add("date/time picker selection lines should stay continuous; grouping should come from spacing")
            }
            if (!dateTimeSource.contains("groupGap.visibility = if (showDateGroup && showTimeGroup)")) {
                add("date/time picker group gap should appear only when date and time columns are both visible")
            }
            if (!dateTimeSource.contains("MONTH_DAY_TIME_GROUP_GAP_DP = 56") ||
                !dateTimeSource.contains("FULL_DATE_TIME_GROUP_GAP_DP = 32") ||
                !dateTimeSource.contains("if (showYearColumn()) FULL_DATE_TIME_GROUP_GAP_DP else MONTH_DAY_TIME_GROUP_GAP_DP")
            ) {
                add("date/time picker should use a larger gap for month-day/time grouping and a compact gap for full date-time")
            }
            if (!dateTimeSource.contains("picker_second_format")) {
                add("date/time picker should render the optional seconds column from localized resources")
            }
            if (!timerControllerSource.contains("DialogHelper.showMonthDayTimeSecondSheet") ||
                timerControllerSource.contains("calculateSmartTimestampForStartTime") ||
                timerControllerSource.contains("calculateSmartTimestampForEndTime")
            ) {
                add("record timer picker should use the selected month/day/hour/minute/second timestamp directly")
            }
            if (choiceSource.contains("bg_picker_option_selected")) {
                add("choice picker should use list rows and a right check mark, not a bordered selected block")
            }
            if (choiceLayout.contains("android:id=\"@+id/tv_title\"")) {
                add("choice picker should hide the center title to keep the sheet header quiet")
            }
            if (Regex("""android:id="@\+id/option_container"[\s\S]{0,240}android:paddingHorizontal""")
                    .containsMatchIn(choiceLayout)
            ) {
                add("choice picker options should not add container padding on top of row padding")
            }
            if (!choiceSource.contains("setPadding(dp(16), 0, dp(16), 0)")) {
                add("choice picker rows should align labels and checks to the same 16dp header edge")
            }
            if (!choiceLayout.contains("android:layout_height=\"48dp\"")) {
                add("choice picker header should be compact enough for short option lists")
            }
            if (!choiceLayout.contains("android:paddingBottom=\"8dp\"")) {
                add("choice picker should avoid excessive bottom whitespace for two-item lists")
            }
            if (!choiceSource.contains("CHOICE_ITEM_TEXT_SIZE_SP = 15f")) {
                add("choice picker item text should use the compact 15sp size")
            }
            if (!choiceSource.contains("setTextColor(context.resolveThemeColor(com.zero.common.R.attr.colorTextPrimary))") ||
                choiceSource.contains("if (isSelected) com.zero.common.R.attr.colorBrand else")
            ) {
                add("choice picker item text should stay primary text color and leave brand emphasis to the check icon")
            }
            if (!choiceSource.contains("Typeface.create(")) {
                add("choice picker should use font weight, not extra color blocks, to softly distinguish selected text")
            }
            if (!choiceSource.contains("gravity = Gravity.CENTER") ||
                !choiceSource.contains("FrameLayout.LayoutParams") ||
                !choiceSource.contains("Gravity.END or Gravity.CENTER_VERTICAL")
            ) {
                add("choice picker item text should be centered while the check icon stays right-aligned")
            }
            if (!dateTimeSource.contains("protectSheetDragForContentTouch(event, contentTouchArea)") ||
                !dateTimeSource.contains("R.id.picker_frame") ||
                !choiceSource.contains("protectSheetDragForContentTouch(event, contentTouchArea)") ||
                !choiceSource.contains("R.id.option_container") ||
                !bottomSheetSource.contains("requestDisallowInterceptTouchEvent(isContentTouch)")
            ) {
                add("picker sheets should protect content touches by disallowing parent intercept without changing XPopup drag layout")
            }
            if (bottomSheetSource.contains("bottomPopupContainer.enableDrag(") ||
                bottomSheetSource.contains("val enableSheetDrag")
            ) {
                add("picker sheets should not toggle SmartDragLayout enableDrag at runtime because it changes XPopup layout mode")
            }
            if (!dateTimeSource.contains("isNestedScrollingEnabled = false")) {
                add("date/time picker columns should disable nested scrolling so XPopup SmartDragLayout cannot consume column fling")
            }
            if (!dialogHelperSource.contains(".moveUpToKeyboard(false)")) {
                add("picker sheets should opt out of XPopup keyboard move-up because they do not show the IME")
            }
            if (!bottomSheetSource.contains("override fun onKeyboardHeightChange") ||
                !bottomSheetSource.contains("BabyCarePickerSheet") ||
                !bottomSheetSource.contains("Log.d(TAG")
            ) {
                add("picker sheets should log unexpected nonzero keyboard height callbacks for diagnosis")
            }
        }

        // 选择器的视觉必须统一为平面列表：选择项靠分割线和右侧勾确认，时间项靠渐变蒙层突出中心数字。
        assertTrue(
            "Shared picker sheets are not aligned with flat list picker visuals: $offenders",
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
