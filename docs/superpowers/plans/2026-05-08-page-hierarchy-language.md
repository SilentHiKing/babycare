# 页面层级语言与统计页视觉重构执行计划

> 给执行 Agent：必须按任务逐项执行。推荐使用 `superpowers:subagent-driven-development` 分任务实现；如果在当前会话内执行，使用 `superpowers:executing-plans`。每完成一个任务就运行对应验证并提交一次小提交。

## 目标

为 BabyCare 建立一套可复用的页面层级语言，并把它落到 `StatisticsFragment`：

- “某一天发生了什么”保持为一个完整的日主区。
- “这些数据说明什么”独立为后续洞察区。
- 指标、时间轴、洞察卡使用统一容器、边框、留白和语义色规则。
- 统计周统一为周一到周日，避免趋势区、日历区和业务统计范围不一致。

## 已确认的视觉决策

这些是本轮已确认的产品决策，执行时不要重新发散：

1. 保留“日主区是一个整体”，但给它更强的容器感：外层 16dp 圆角、1dp `control_border_default` 边框、内部连续白底，无阴影。日历顶部、摘要、时间轴仍连在一起，但和洞察区拉开 24dp。
2. 在日主区后加一个洞察区标题，例如“规律洞察”，下面每张洞察卡使用统一 `surface + stroke` 卡片样式；趋势、结构、成长、百分位、健康彼此间距 12-16dp。这样“今日发生了什么”和“这些数据说明什么”会清楚分层。
3. 摘要四宫格改为独立指标块：每个 item 用浅底 + 边框 + 语义色图标承托面；数字用语义色，说明用 hint 色。不要大面积彩色底，也不要阴影。
4. 时间轴改成固定列布局：时间 48dp / 轨道 20dp / 图标 36dp / 内容自适应。竖线用更清晰的边框色，点保留事件语义色；标题、详情、备注统一从文本列左边界开始。
5. 周范围固定为周一到周日：`WeekFields.of(DayOfWeek.MONDAY, 4)`，同步更新趋势统计、趋势标题、日历周视图，并补测试覆盖 `2026-05-08 -> 05.04-05.10`。

## 约束

- 遵守根目录 `AGENTS.md`、`DESIGN.md`、`docs/ui-guidelines.md`。
- UI 文案必须来自 `strings.xml`，不能在 Kotlin 或 XML 中硬编码用户可见文本。
- 颜色、背景、文字层级优先复用 `common/` 的主题资源；不在 `app/` 局部新增重复 token。
- 普通 View、卡片、统计模块不加阴影，不使用 `android:elevation`、`translationZ` 或 `cardElevation` 表达层级。
- 保持现有 MVVM：Fragment 只装配 UI 和转发事件，业务规则进入 ViewModel / mapper / UI model。
- RecyclerView 继续基于现有 `baby_recyclerview` / BRVAH 适配器，不新建通用列表框架。
- 代码注释如需新增，使用简体中文，解释原因而不是重复代码行为。
- 当前工作区已有无关变更，执行时不要触碰：
  - `.codex/skills/ui-ux-pro-max/SKILL.md`
  - `docs/superpowers/plans/2026-05-06-statistics-fragment-redesign.md`

## 文件清单

新增：

- `common/src/main/res/drawable/bg_r16_surface_stroke_control_border.xml`
- `common/src/main/res/drawable/bg_r16_top_surface_stroke_control_border.xml`
- `common/src/main/res/drawable/bg_surface_stroke_control_border.xml`
- `common/src/main/res/drawable/bg_r16_bottom_surface_stroke_control_border.xml`
- `common/src/main/res/drawable/bg_r8_metric_tile.xml`
- `app/src/main/java/com/zero/babycare/statistics/adapter/StatisticsInsightHeaderAdapter.kt`
- `app/src/main/res/layout/item_statistics_insight_header.xml`

修改：

- `docs/ui-guidelines.md`
- `app/src/main/java/com/zero/babycare/statistics/mapper/StatisticsDateRange.kt`
- `app/src/test/java/com/zero/babycare/statistics/StatisticsDateRangeTest.kt`
- `app/src/main/java/com/zero/babycare/statistics/StatisticsViewModel.kt`
- `app/src/main/java/com/zero/babycare/statistics/widget/BabyCalendarView.kt`
- `app/src/main/java/com/zero/babycare/statistics/StatisticsFragment.kt`
- `app/src/main/java/com/zero/babycare/statistics/adapter/TimelineAdapter.kt`
- `app/src/main/res/layout/item_statistics_calendar.xml`
- `app/src/main/res/layout/item_statistics_baby_age.xml`
- `app/src/main/res/layout/item_statistics_summary.xml`
- `app/src/main/res/layout/item_statistics_empty.xml`
- `app/src/main/res/layout/item_timeline_feeding.xml`
- `app/src/main/res/layout/item_timeline_sleep.xml`
- `app/src/main/res/layout/item_timeline_event.xml`
- `app/src/main/res/layout/item_statistics_trend.xml`
- `app/src/main/res/layout/item_statistics_structure.xml`
- `app/src/main/res/layout/item_statistics_growth.xml`
- `app/src/main/res/layout/item_statistics_growth_percentile.xml`
- `app/src/main/res/layout/item_statistics_health.xml`
- `app/src/main/res/layout/item_statistics_trend_card.xml`
- `app/src/main/res/layout/item_statistics_growth_percentile_item.xml`
- 可能需要补充 `app/src/main/res/values/strings.xml` 中的 `statistics_insight_title`。

## 任务 0：执行前确认

- [ ] 阅读设计规范。

```powershell
Get-Content -Path 'DESIGN.md' -TotalCount 220
Get-Content -Path 'docs\ui-guidelines.md' -TotalCount 260
```

- [ ] 查看当前工作区状态，确认无关变更不纳入本次提交。

```powershell
git status --short
```

预期允许存在：

```text
 D .codex/skills/ui-ux-pro-max/SKILL.md
?? docs/superpowers/plans/2026-05-06-statistics-fragment-redesign.md
```

## 任务 1：把页面层级语言写入规范并补齐通用背景

### 1.1 更新 `docs/ui-guidelines.md`

- [ ] 增加“页面层级语言”小节，内容包括：
  - 主任务区使用整块分组容器，表达同一业务对象或同一工作流。
  - 洞察区、汇总区、说明区使用独立 section 标题和统一 `surface + stroke` 卡片。
  - 指标块统一为浅底、1dp 边框、8dp 圆角，图标可使用浅色承托面。
  - 同一页面优先用背景分区、边框、留白、标题层级表达层级，不用阴影。
  - 统计页的日主区和洞察区是该规则的第一处落地样例。

### 1.2 新增通用背景资源

- [ ] 新增完整卡片 / section 背景。

`common/src/main/res/drawable/bg_r16_surface_stroke_control_border.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/colorSurface" />
    <stroke
        android:width="1dp"
        android:color="@color/control_border_default" />
    <corners android:radius="16dp" />
</shape>
```

- [ ] 新增连续分组顶部背景。

`common/src/main/res/drawable/bg_r16_top_surface_stroke_control_border.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/colorSurface" />
    <stroke
        android:width="1dp"
        android:color="@color/control_border_default" />
    <corners
        android:topLeftRadius="16dp"
        android:topRightRadius="16dp" />
</shape>
```

- [ ] 新增连续分组中段背景。

`common/src/main/res/drawable/bg_surface_stroke_control_border.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/colorSurface" />
    <stroke
        android:width="1dp"
        android:color="@color/control_border_default" />
</shape>
```

- [ ] 新增连续分组底部背景。

`common/src/main/res/drawable/bg_r16_bottom_surface_stroke_control_border.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/colorSurface" />
    <stroke
        android:width="1dp"
        android:color="@color/control_border_default" />
    <corners
        android:bottomLeftRadius="16dp"
        android:bottomRightRadius="16dp" />
</shape>
```

- [ ] 新增指标块背景。

`common/src/main/res/drawable/bg_r8_metric_tile.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/control_surface_default" />
    <stroke
        android:width="1dp"
        android:color="@color/control_border_default" />
    <corners android:radius="8dp" />
</shape>
```

### 1.3 验证与提交

- [ ] 运行资源策略测试。

```powershell
.\gradlew :app:testDebugUnitTest --tests "*UiResourcePolicyTest*"
```

- [ ] 只提交本任务文件。

```powershell
git add -- docs/ui-guidelines.md common/src/main/res/drawable/bg_r16_surface_stroke_control_border.xml common/src/main/res/drawable/bg_r16_top_surface_stroke_control_border.xml common/src/main/res/drawable/bg_surface_stroke_control_border.xml common/src/main/res/drawable/bg_r16_bottom_surface_stroke_control_border.xml common/src/main/res/drawable/bg_r8_metric_tile.xml
git commit -m "style: add page hierarchy containers"
```

## 任务 2：统一统计周为周一到周日

### 2.1 先补失败测试

- [ ] 在 `StatisticsDateRangeTest.kt` 增加测试，覆盖 2026-05-08。

```kotlin
@Test
fun statisticsWeekIsMondayThroughSunday() {
    val date = LocalDate.of(2026, 5, 8)

    val range = StatisticsDateRange.week(date)

    assertThat(range.startDate).isEqualTo(LocalDate.of(2026, 5, 4))
    assertThat(range.endDate).isEqualTo(LocalDate.of(2026, 5, 10))
    assertThat(range.label).isEqualTo("05.04-05.10")
}
```

- [ ] 如果现有测试断言“默认周从地区设置开始”，改为断言统计业务固定使用周一。

### 2.2 修改 `StatisticsDateRange`

- [ ] 增加统一入口。

```kotlin
fun statisticsWeekFields(): WeekFields = WeekFields.of(DayOfWeek.MONDAY, 4)
```

- [ ] `week(...)` 默认使用该入口。

```kotlin
fun week(
    selectedDate: LocalDate,
    weekFields: WeekFields = statisticsWeekFields()
): DateRange {
    val firstDay = weekFields.firstDayOfWeek
    val start = selectedDate.with(TemporalAdjusters.previousOrSame(firstDay))
    val end = start.plusDays(6)
    return DateRange(startDate = start, endDate = end, label = formatRange(start, end))
}
```

### 2.3 修改业务消费方

- [ ] `StatisticsViewModel.buildTrendOverview(...)` 使用 `StatisticsDateRange.week(selectedDate)`，不要再用系统地区周设置。
- [ ] `BabyCalendarView` 的周标题、周行、月份补位都使用 `StatisticsDateRange.statisticsWeekFields()`。
- [ ] 检查 `2026-05-08` 对应日历行展示为 `05.04-05.10`。

### 2.4 验证与提交

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.zero.babycare.statistics.StatisticsDateRangeTest"
.\gradlew :app:compileDebugKotlin
```

```powershell
git add -- app/src/main/java/com/zero/babycare/statistics/mapper/StatisticsDateRange.kt app/src/test/java/com/zero/babycare/statistics/StatisticsDateRangeTest.kt app/src/main/java/com/zero/babycare/statistics/StatisticsViewModel.kt app/src/main/java/com/zero/babycare/statistics/widget/BabyCalendarView.kt
git commit -m "fix: use monday statistics week"
```

## 任务 3：强化日主区容器与摘要四宫格

### 3.1 日主区连续背景

- [ ] `item_statistics_calendar.xml` 根容器使用：
  - `@drawable/bg_r16_top_surface_stroke_control_border`
  - 左右外边距 16dp，顶部按现有页面节奏保留。
  - 内部 padding 保持紧凑，不增加阴影。

- [ ] `item_statistics_baby_age.xml` 根容器使用：
  - `@drawable/bg_surface_stroke_control_border`
  - 左右外边距与日历一致。
  - 与上下模块保持连续白底。

- [ ] `item_statistics_summary.xml` 根容器使用：
  - `@drawable/bg_surface_stroke_control_border`
  - 左右外边距与日历一致。
  - 底部不要先闭合圆角，因为后面还有时间轴或空状态。

### 3.2 摘要四宫格指标块

- [ ] 四个 item 的外层都使用 `@drawable/bg_r8_metric_tile`。
- [ ] item 内部建议：
  - 最小高度 72dp。
  - padding 12dp。
  - 两列之间 8dp，行间 8dp。
  - 图标承托面继续使用现有 `bg_event_icon_surface` 或同等 common 资源。
  - 数字文字使用对应事件语义色。
  - 描述文字使用 `@color/textHint` 或已有 hint token。
- [ ] 不使用大面积彩色底、阴影、旧蓝紫描边或硬编码色值。

### 3.3 验证与提交

```powershell
.\gradlew :app:compileDebugKotlin
```

```powershell
git add -- app/src/main/res/layout/item_statistics_calendar.xml app/src/main/res/layout/item_statistics_baby_age.xml app/src/main/res/layout/item_statistics_summary.xml
git commit -m "style: strengthen statistics day section"
```

## 任务 4：重构时间轴对齐与闭合日主区

### 4.1 统一时间轴行结构

- [ ] `item_timeline_feeding.xml`、`item_timeline_sleep.xml`、`item_timeline_event.xml` 都改为固定列：
  - 时间列：48dp。
  - 轨道列：20dp。
  - 图标列：36dp。
  - 内容列：`0dp + layout_weight=1`。
- [ ] 轨道列包含：
  - 1dp 竖线，颜色 `@color/control_border_default`。
  - 事件点，颜色继续使用事件语义色。
- [ ] 图标列只放事件图标，不承担文字对齐。
- [ ] 标题、详情、备注都从内容列左边界开始。
- [ ] 触控目标和上下 padding 保持舒适，避免时间轴变得拥挤。

### 4.2 适配首尾背景

- [ ] `TimelineAdapter` 新增或调整行背景逻辑：
  - 时间轴列表中间行使用 `@drawable/bg_surface_stroke_control_border`。
  - 时间轴最后一行使用 `@drawable/bg_r16_bottom_surface_stroke_control_border`。
  - 如果日期无记录，`item_statistics_empty.xml` 负责使用底部圆角背景闭合日主区。
- [ ] 背景判断只做 UI 分组，不把业务判断写进 Adapter。

### 4.3 验证与提交

```powershell
.\gradlew :app:compileDebugKotlin
```

```powershell
git add -- app/src/main/java/com/zero/babycare/statistics/adapter/TimelineAdapter.kt app/src/main/res/layout/item_timeline_feeding.xml app/src/main/res/layout/item_timeline_sleep.xml app/src/main/res/layout/item_timeline_event.xml app/src/main/res/layout/item_statistics_empty.xml
git commit -m "style: align statistics timeline"
```

## 任务 5：增加洞察区标题并统一洞察卡

### 5.1 文案资源

- [ ] 如果尚不存在，向 `strings.xml` 添加：

```xml
<string name="statistics_insight_title">规律洞察</string>
```

### 5.2 新增洞察标题布局

- [ ] 新增 `item_statistics_insight_header.xml`。

要求：

- 根容器左右 16dp。
- 顶部外边距 24dp，底部外边距 8dp。
- 标题使用现有标题文字样式，文本来自 `@string/statistics_insight_title`。
- 可选副标题只在已有明确文案时添加；本次默认不增加新解释性文案，避免页面噪音。

### 5.3 新增标题 Adapter

- [ ] 新增 `StatisticsInsightHeaderAdapter.kt`。

实现要求：

- 单 item Adapter，只负责渲染标题布局。
- 不持有 Fragment / Context 长引用。
- 不写业务逻辑。
- 绑定类为 `ItemStatisticsInsightHeaderBinding`。

### 5.4 装配到 `StatisticsFragment`

- [ ] 在现有 `ConcatAdapter` 中，将标题插入到日主区之后、洞察卡之前。
- [ ] 推荐顺序：
  - 日历
  - 宝宝年龄
  - 当日摘要
  - 时间轴或空状态
  - `StatisticsInsightHeaderAdapter`
  - 趋势概览
  - 结构图
  - 生长趋势
  - 成长曲线百分位
  - 健康与疫苗

### 5.5 统一洞察卡视觉

- [ ] 以下布局根容器使用 `@drawable/bg_r16_surface_stroke_control_border`：
  - `item_statistics_trend.xml`
  - `item_statistics_structure.xml`
  - `item_statistics_growth.xml`
  - `item_statistics_growth_percentile.xml`
  - `item_statistics_health.xml`
- [ ] 每张洞察卡左右外边距 16dp。
- [ ] 卡片之间纵向间距 12-16dp。
- [ ] 内部 padding 16dp。
- [ ] 结构、趋势、成长、健康内部的小指标块复用 `@drawable/bg_r8_metric_tile`。
- [ ] `item_statistics_growth.xml` 如存在嵌套卡片视觉，改成内部普通分区，避免卡片套卡片。
- [ ] 不新增阴影，不写死颜色。

### 5.6 验证与提交

```powershell
.\gradlew :app:compileDebugKotlin
```

```powershell
git add -- app/src/main/java/com/zero/babycare/statistics/StatisticsFragment.kt app/src/main/java/com/zero/babycare/statistics/adapter/StatisticsInsightHeaderAdapter.kt app/src/main/res/layout/item_statistics_insight_header.xml app/src/main/res/layout/item_statistics_trend.xml app/src/main/res/layout/item_statistics_structure.xml app/src/main/res/layout/item_statistics_growth.xml app/src/main/res/layout/item_statistics_growth_percentile.xml app/src/main/res/layout/item_statistics_health.xml app/src/main/res/layout/item_statistics_trend_card.xml app/src/main/res/layout/item_statistics_growth_percentile_item.xml app/src/main/res/values/strings.xml
git commit -m "style: separate statistics insight section"
```

## 任务 6：最终验证

- [ ] 运行统计范围测试。

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.zero.babycare.statistics.StatisticsDateRangeTest"
```

- [ ] 运行资源策略测试。

```powershell
.\gradlew :app:testDebugUnitTest --tests "*UiResourcePolicyTest*"
```

- [ ] 运行 app 单测。

```powershell
.\gradlew :app:testDebugUnitTest
```

- [ ] 构建 Debug APK。

```powershell
.\gradlew assembleDebug
```

- [ ] 如果有设备或模拟器，安装并人工检查。

```powershell
adb devices
.\gradlew installDebug
```

人工检查项：

- 男孩浅色主题：日主区整体容器清楚，和洞察区分离明显。
- 女孩浅色主题：品牌色变化不影响喂养、睡眠、尿布、健康等语义色。
- 深色模式：主任务区边框、洞察卡边框、时间轴竖线和正文均可读。
- 当日摘要：四个 item 都是独立指标块，图标有浅色承托面，数字语义色明确。
- 时间轴：时间列、轨道列、图标列、内容列稳定左对齐；竖线连续且不刺眼。
- 趋势概览：选择 `2026-05-08` 时，本周范围显示 `05.04-05.10`。
- 无记录日期：空状态位于日主区内，并关闭日主区底部圆角。

- [ ] 查看最终工作区状态。

```powershell
git status --short
```

预期：只剩本轮明确计划外的既有无关项，不要把它们提交。

## 自检

- 页面层级语言已纳入任务 1。
- 日主区整体容器、16dp 圆角、1dp 边框、连续白底、无阴影已覆盖在任务 1、3、4。
- 洞察区标题与统一 `surface + stroke` 卡片已覆盖在任务 5。
- 摘要四宫格独立指标块已覆盖在任务 3。
- 时间轴固定列和更清晰竖线已覆盖在任务 4。
- 周一到周日统计周、趋势范围、日历周视图和 `2026-05-08 -> 05.04-05.10` 测试已覆盖在任务 2。
- 验证覆盖统计测试、资源策略测试、app 单测、`assembleDebug` 和可选设备人工 QA。
- 没有要求新增阴影、硬编码页面颜色或绕过 MVVM。
