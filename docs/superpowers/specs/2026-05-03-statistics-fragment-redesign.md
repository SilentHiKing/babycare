# StatisticsFragment 重构设计规格

日期：2026-05-03

## 背景

当前 `StatisticsFragment` 的截图和代码结构显示，页面顶部的日期选择、当日摘要与页面底部的宝宝日龄、当日时间轴在业务上都属于“选中某一天的婴儿事件记录”，但中间插入了趋势、结构、成长百分位、健康疫苗等周期统计模块。用户点选某一天后，必须滚过大量长期统计内容才能看完整这一天的记录，业务语义被切开。

本次重构采用已确认的 A 方案：**当日主区 + 后续洞察区**。页面先完整解释“选中这一天发生了什么”，再解释“围绕这一天所在周期，宝宝的规律有什么变化”。

竞品参考：

- Huckleberry：强调喂养、睡眠、尿布等记录与周总结，记录和规律洞察分层呈现。参考：https://explore.huckleberrycare.com/app/
- Nara Baby：强调 easy-to-read log、routine、recent activities，优先帮助照护者快速回看当天记录。参考：https://nara.com/nara-baby-tracker/support/faq
- Glow Baby：强调 tracking 之外提供 summaries、graphs、insights，说明日常记录和统计洞察是两个层级。参考：https://glowing.com/glow-baby-app

## 目标

1. 让 `selectedDate` 成为统计页的主状态。
2. 将日期选择、宝宝日龄、当日摘要、当日时间轴合并为连续的“选中日记录区”。
3. 将趋势、结构、成长、健康疫苗下移为“规律洞察区”，并清楚标注统计范围。
4. 收敛 ViewModel 状态输出，符合 MVVM 单向数据流。
5. 将时间轴业务文案、图标、颜色映射前移到 ViewModel/Mapper，Adapter 只负责渲染 UI Model。
6. 遵守 BabyCare Soft Care + Data Clarity / Soft Utility 2.0 视觉规范。

## 非目标

1. 不新增中性主题或第三套主题。
2. 不新增业务通用颜色、style、theme 到 `app/`。
3. 不重做整站导航，不把统计页拆成多个一级页面。
4. 不引入新的图表库；现有自定义结构图和百分位展示先在当前技术栈内演进。
5. 不在本次设计中新增记录编辑能力，只保留点击时间轴进入已有编辑页。

## 信息架构

页面整体顺序：

1. Toolbar：标题“统计”，返回到入口页面。
2. 选中日记录区：
   - 日期标题行：上一周期、当前周/月标题、下一周期、今天。
   - 周视图日历，支持展开月视图。
   - 宝宝日龄 Chip 与当天记录数量。
   - 当日摘要 2x2 指标：喂养、睡眠、尿布、其他。
   - 当日时间轴；无记录时在同一区块内展示空状态。
3. 规律洞察区：
   - 洞察区标题与基准日期。
   - 趋势概览：选中日所在周、月、年。
   - 结构图：选中日所在月份的喂养、排泄、健康结构。
   - 成长趋势：截至选中日的最近成长记录。
   - WHO 百分位：截至选中日的成长点和最新百分位。
   - 健康与疫苗：选中日所在月份的健康/疫苗统计。

## 业务逻辑

`selectedDate` 是整页主状态。所有模块必须明确使用以下时间范围：

- 当日摘要和时间轴：`selectedDate` 当天 00:00:00 到 23:59:59。
- 趋势概览：`selectedDate` 所在周、所在月、所在年。
- 结构图：`selectedDate` 所在月。
- 健康与疫苗：`selectedDate` 所在月。
- 成长趋势和 WHO 百分位：只统计记录时间小于等于 `selectedDate` 当天结束时间的成长记录，避免历史日期显示未来录入的体重、身高、头围。
- 日龄：按宝宝出生日期到 `selectedDate` 计算；出生日期晚于 `selectedDate` 时显示“宝宝还未出生”的既有文案。

选择日期时，ViewModel 应同步刷新当日记录区与洞察区。切换日历展示月份时，只刷新该展示月份的“有记录日期”标记；如果用户实际选择了新日期，再刷新整页状态。

## 状态与事件模型

建议新增或重构为以下 UI 状态：

```kotlin
data class StatisticsUiState(
    val babyId: Int,
    val selectedDate: LocalDate,
    val datesWithRecords: Set<LocalDate>,
    val dayRecord: DayRecordSectionUiModel,
    val insights: InsightSectionUiModel,
    val isLoading: Boolean,
    val errorMessage: String?
)
```

建议事件模型：

```kotlin
sealed interface StatisticsUiEvent {
    data class SelectDate(val date: LocalDate) : StatisticsUiEvent
    data class ChangeMonth(val yearMonth: YearMonth) : StatisticsUiEvent
    data object GoToday : StatisticsUiEvent
    data object Refresh : StatisticsUiEvent
    data class OpenTimelineItem(val target: TimelineEditTarget) : StatisticsUiEvent
}
```

Fragment 只负责收集 `StatisticsUiState`、转发 `StatisticsUiEvent`、执行导航和返回。数据库查询、时间范围计算、文案拼装、颜色/图标语义映射不进入 Fragment。

## UI Model 边界

建议新增以下 UI Model：

- `DayRecordSectionUiModel`
  - 日期标题
  - 日龄文案
  - 当天记录数量
  - `summaryMetrics: List<SummaryMetricUiModel>`
  - `timelineItems: List<TimelineUiItem>`
  - 空状态
- `SummaryMetricUiModel`
  - 类型：喂养、睡眠、尿布、其他
  - 标题文案资源
  - 主值
  - 辅助值
  - 图标资源
  - 语义色资源
  - 浅色承托面资源
- `TimelineUiItem`
  - 稳定 ID
  - 时间文案
  - 标题
  - 详情
  - 备注
  - 图标资源
  - 语义色资源
  - 浅色承托面资源
  - 编辑目标
- `InsightSectionUiModel`
  - 趋势概览
  - 结构图
  - 成长趋势
  - WHO 百分位
  - 健康疫苗

`TimelineAdapter` 只绑定 `TimelineUiItem`。现有 `TimelineAdapter` 中的事件类型判断、喂养详情拼装、健康/成长/里程碑详情拼装应迁移到 ViewModel 层附近的 Mapper，避免 Adapter 继续承担业务解释。

## 布局样式

### 选中日记录区

样式方向：

- 使用一个连续的浅色承托区，整合日期、日龄、摘要、时间轴。
- 默认周视图，展开后显示月视图。
- 日龄 Chip 使用浅暖底 + 1dp 边框 + 品牌/语义文字，不使用白字实色块。
- 当日摘要从当前 4 等分横排改为 2x2 指标，保证窄屏下主数字和辅助信息都可读。
- 时间轴紧跟摘要区，避免被周期统计切开。
- 无记录空状态放在选中日记录区内部，并提供返回首页/快速记录入口的既有行为。

### 规律洞察区

样式方向：

- 洞察区下移，标题显示“规律洞察”与基准日期。
- 每张洞察卡显示统计范围，例如“05.01-05.31”或“截至 05.02”。
- 趋势卡优先做可扫读摘要，避免堆砌大段文本。
- 结构图继续使用业务语义色，并保留文字图例，不能只靠颜色表达信息。
- 成长与健康信息保持紧凑，优先展示最近有效数据和空状态说明。

### 视觉约束

- 不在 `app/` 页面 XML 中硬编码色值。
- 用户可见文本必须来自 `strings.xml`。
- 普通卡片、列表项、统计模块不添加 elevation、translationZ、非零 cardElevation 或伪阴影。
- 图标沿用 24dp 线性风格，颜色来自主题属性或业务语义色。
- 男孩/女孩主题保持同一信息层级；主题色只影响品牌强调和选中态，不改变业务功能色。
- 深色模式需要单独检查文本、图标、边框和浅承托面的可读性。

## 实现顺序

1. 新增/重构 `StatisticsUiState`、`StatisticsUiEvent` 和各区块 UI Model。
2. 提取 `TimelineUiItem` Mapper，将现有 Adapter 内的业务文案和资源映射迁移出去。
3. 调整 `StatisticsViewModel`：
   - 以 `selectedDate` 驱动整页状态。
   - 增加“截至选中日”的成长查询/过滤。
   - 输出单一主状态或更少的区块状态。
4. 调整 `StatisticsFragment`：
   - 收集主状态。
   - 转发事件。
   - 保留导航与返回逻辑。
5. 调整 RecyclerView 组成：
   - 将日历、日龄、摘要、时间轴组织为连续的选中日记录区。
   - 将趋势、结构、成长、百分位、健康组织为洞察区。
6. 调整 XML 样式：
   - 摘要指标改为 2x2。
   - 选中日记录区形成连续视觉容器。
   - 洞察卡标注统计范围。
7. 补充字符串资源、必要的 UI Model 单元测试。

## 测试策略

必须覆盖：

1. 选择历史日期时，成长趋势和 WHO 百分位不包含未来成长记录。
2. `selectedDate` 改变后，当日摘要、时间轴、周/月/年趋势、月结构、月健康统计同步更新。
3. 无宝宝信息时仍跳转到宝宝信息页。
4. 无出生日期时，日龄区域显示既有提示或隐藏策略。
5. 无当日记录时，空状态位于选中日记录区内。
6. 无成长记录、缺失性别或出生日期时，WHO 百分位展示明确空状态。

验证命令：

```bash
./gradlew test
./gradlew assembleDebug
```

UI 自检：

- 浅色/深色模式。
- 男孩/女孩主题。
- 小屏宽度下 2x2 摘要指标不挤压文字。
- 月视图展开/收起动画不造成布局错乱。
- 时间轴点击仍能进入对应记录编辑页。

## 风险与约束

1. `TimelineAdapter` 当前承担较多业务文案拼装，迁移到 Mapper 时容易遗漏事件类型，需要逐类对照现有逻辑。
2. 成长百分位目前基于所有记录计算，改为“截至选中日”后需要确认数据查询排序和最新值取值。
3. ConcatAdapter 现有顺序调整后，需要保证空状态 Adapter 不再被加到洞察区之后。
4. 日历月份切换与日期选择是两个不同事件，不能因翻月导致整页统计误刷新。
5. 统计区的周期文案必须明确，避免用户误解为当天数据。

## 已确认决策

1. 采用 A 方案：当日主区 + 后续洞察区。
2. 成长数据按“截至选中日期”展示，不把未来记录混进历史日期。
3. UI 样例方向确认：选中日记录区连续呈现，洞察区下移，摘要使用 2x2 指标。
4. 实现前先完成 ViewModel/UI Model 边界收敛，再做布局样式调整。

## 自检

- 未发现占位需求或未完成条目。
- 业务范围聚焦在 `StatisticsFragment`，不扩展到其他页面重构。
- MVVM 职责明确：Fragment 渲染和转发，ViewModel 管理状态，Adapter 只绑定 UI Model。
- UI 约束遵守 BabyCare `DESIGN.md` 与 `docs/ui-guidelines.md`。
- 测试策略覆盖主要回归风险。
