# BabyCare Android UI 开发规范

本文档是 BabyCare 当前 UI 优化的落地规范。视觉源头以根目录 `DESIGN.md` 为准，Android 资源以 `common` 模块的 theme、color、style、dimen 为准。

当前执行版本为 **Soft Utility 2.0**：在 Soft Care + Data Clarity 基线下，配色必须温暖、活泼、干净，禁止回退到老式糖果图标、脏粉控件底、沉闷灰紫或高饱和旧蓝。

## 产品定位

BabyCare 的产品理念是不可改变且具有最高优先级的基础：记录婴儿生活，服务持续记录婴儿生活习惯的家长，帮助家长理解孩子的喂养、睡眠、排泄、健康与成长规律，并基于记录预测孩子后续可能的活动，从而减轻父母带娃难度，让孩子更健康地成长。不管是交互逻辑还是 UI 设计，都必须首先服务这一理念。

BabyCare 是婴儿生活记录与规律洞察工具，不是装饰型相册应用。所有页面结构、视觉层级、交互反馈和数据表达，都必须优先支持持续记录、规律理解、活动预测、减轻父母带娃难度与健康成长。

核心体验顺序：

1. 持续记录婴儿生活。
2. 理解今日与近期生活规律。
3. 预测后续可能活动。
4. 减轻父母带娃难度并支持健康成长。

## 主题规则

只保留两套宝宝主题：

- 男孩主题：`AppTheme.Boy`，清爽天空蓝。
- 女孩主题：`AppTheme.Girl`，低饱和花瓣粉。

默认主题 `AppTheme` 只作为 Android 样式父主题，不作为第三套宝宝视觉风格设计。未设置宝宝或无法识别性别时，使用男孩主题作为兜底。禁止新增 `Neutral`、`中性主题`、`Default baby theme` 或新的性别颜色分支。

主题色只用于品牌强调、主操作、选中态和少量视觉提示。喂养、睡眠、排泄、健康、成长、里程碑等业务类型必须使用固定功能色，不能跟随男孩/女孩主题变化。

主题色必须区分行动色与柔和背景色：

- 男孩行动色：`#2A74B8`，按压 / 深色变体：`#1F5F99`，柔和背景：`#D9EAF8` / `#EEF7FE`。
- 女孩行动色：`#B8526C`，按压 / 深色变体：`#94394F`，柔和背景：`#FADBE1` / `#FFF7F8`。
- 行动色用于主按钮、保存按钮、选中 Chip、Tab 选中底和关键强调，叠加白色文字时对比度必须不低于 4.5:1。
- 柔和背景色只能搭配深色文字或用于头像底、浅卡片、轻量状态与普通选中态，禁止承载白字按钮或白字选中控件。

品牌前景与按钮底必须分开：`colorBrand` 保留为承载白字的主按钮底色；文字、选择控件描边和浅底操作图标使用 `colorBrandForeground`。该前景在浅色模式使用对应 `brand_dark`，在深色模式使用对应 `accent`，以保证普通控件和暖选中底上的文字对比度均不低于 4.5:1。状态卡的深色辅助文字使用 `status_text_secondary`，不能将深色按钮按压色用作状态时长文字。

## Soft Utility 2.0 色板

功能色固定，不随男孩 / 女孩主题变化：

- 喂养：`feeding_primary #247BA0`，浅底 `feeding_bg #E8F5F7`。
- 睡眠：`sleep_primary #6E65B7`，浅底 `sleep_bg #F1EEFB`。
- 排泄：`event_diaper #F59E32`，浅底 `event_diaper_light #FFE9C7`。
- 健康：`event_health #1FAE9B`，浅底 `event_health_light #D8F4EE`。
- 成长：`event_growth #5FAE63`，浅底 `event_growth_light #E1F2DF`。
- 里程碑：`event_milestone #D95C75`，浅底 `event_milestone_light #FCE2E8`。
- 护理：`event_care #4E9FE6`，活动：`event_activity #9A72E6`，其他：`event_other #73808C`。

喂养与睡眠卡片上的文字使用各自的 `primary_dark` 和 `secondary`，需在对应 `bg` 上满足至少 4.5:1；`primary` 保留用于图标，不能因配色柔和而降低“距上次”和今日总量等辅助信息的可读性。

记录控件底色固定使用：

- `control_surface_default #FFFDFC`：未选中控件、输入框、普通记录控件。
- `control_surface_selected #FFF3E8`：普通选择控件、按压态和轻量当前态的统一浅暖底。
- `control_border_default #E3ECEB`：未选中控件和输入框 hairline 边框，统一使用 `@dimen/surface_stroke_width`（0.1dp）。
- `event_icon_surface #FFF2D9`：通用事件图标承托面。

普通选择控件统一采用 **Quiet Selection**：`control_surface_selected` 浅暖底 + 0.1dp 品牌描边 + 品牌文字，不做白字实色块、底部粗色条或多层浅色填充。业务功能色只用于图标、图表和数据语义，不能同时参与选中边框或选中文字。保存按钮和真正主行动按钮例外，继续使用品牌实色底。

喂养页的辅食分类和子类型位于喂养类型下方，选中态必须参考上方喂养类型分段控件：`control_surface_selected` 浅暖底、`?attr/colorBrandForeground` 描边、`?attr/colorBrandForeground` 文字。辅食 Chip 文字复用 `selector_radio_text_color`，不得另建一套 `feeding_primary`、业务色描边或固定蓝色选中态，避免同页出现两套状态色。

确认弹窗按钮必须清楚区分主次操作：确认按钮使用 `?attr/colorBrand` 实色底和 `?attr/colorOnBrand` 文字；取消按钮使用 `control_surface_default` 浅底、`control_border_default` 边框、`?attr/colorBrandForeground` 文字，按压态切到 `control_surface_selected` + 品牌前景描边。取消按钮不能做成接近空白或禁用态，也不能在按压态写死旧蓝色。

## 层级与阴影

普通页面 View 默认不设计阴影效果。卡片、列表项、输入框、统计模块、记录表单区必须通过背景、边框、留白和标题层级表达结构，不使用 `android:elevation`、`translationZ`、非零 `cardElevation` 或伪阴影色块。

只有 Dialog、BottomSheet、系统浮层等临时覆盖容器允许使用轻量阴影或遮罩，并且应由组件层统一提供。页面 XML 不单独声明阴影。

`elevationSmall`、`elevationMedium` 等历史 token 保留为兼容入口，但默认值必须为 `0dp`。如果确实需要浮层阴影，先扩展组件层能力，不在业务页面临时加。

## 页面层级语言

页面的第一层应表达当前用户正在处理的主任务或同一业务对象。主任务区可以使用整块分组容器承载连续内容，例如统计页中选中某一天的日期、年龄、摘要和时间轴；这类内容属于同一天的事件记录，应保持视觉连续，而不是拆成多张互不相关的卡片。

洞察区、汇总区和说明区是第二层，应使用独立区块标题和统一 `surface + stroke` 卡片样式。普通 surface / control surface 描边统一引用 `@dimen/surface_stroke_width`（0.1dp），区块之间用 24dp 左右的垂直距离建立清晰分段，区块内部卡片之间保持 12-16dp 间距，避免页面看起来是一整片无边界内容。

指标块用于承载短数字、状态和类别占比，统一使用浅底、0.1dp 边框、8dp 圆角。图标可以放在浅色承托面内，数字使用业务语义色，说明文字使用弱化文本色，不能用大面积彩色底或阴影制造层级。

同一页面优先通过背景分区、边框、留白、标题层级、语义色和状态色建立结构。普通页面卡片、列表项、统计模块和输入区仍然不使用阴影。统计页的“日主区 + 规律洞察区”是该规则的基准样例。

## 图标规则

所有结构性图标使用统一线性风格：24dp viewport、2dp stroke、round linecap / linejoin，颜色通过 `?attr/iconColorPrimary`、`?attr/iconColorSecondary`、`?attr/iconColorBrand` 或业务语义色注入。

婴儿生活图标需要表达“记录类型”和“状态”，不要做过度卡通化、emoji 化或厚重填充插画。若现有图标是硬编码黑色、红色或与主题不适配，优先重绘为线性矢量图标并使用 token 着色。

首页快速记录、记录事件大类和事件子类型必须使用同一套线性图标语言。快速记录和事件子类型图标应放在 48dp 左右的浅色圆形承托面内，图标本体使用对应业务语义色；文字默认使用正文/辅助文字色，不用高饱和彩色文字替代信息层级。

## 模块边界

- 通用颜色、字体、圆角、间距、Drawable 放在 `common`。
- 通用 Toolbar、Dialog、记录计时面板、输入组件放在 `components`。
- 页面布局和页面专属组合放在 `app`。
- RecyclerView 能力必须基于 `baby_recyclerview`。
- `common`、`components`、`babydata` 禁止依赖 `app`。

## Token 使用

XML 中优先使用语义属性：

- 背景：`?attr/colorBackground`、`?attr/colorSurface`、`?attr/colorSurfaceVariant`。
- 记录控件：`@color/control_surface_default`、`@color/control_surface_selected`、`@color/control_border_default`、`@color/event_icon_surface`。
- 文本：`?attr/colorTextPrimary`、`?attr/colorTextSecondary`、`?attr/colorTextHint`。
- 品牌：`?attr/colorBrand`、`?attr/colorBrandForeground`、`?attr/colorBrandLight`、`?attr/colorBrandDark`、`?attr/colorOnBrand`。
- 图标：`?attr/iconColorPrimary`、`?attr/iconColorSecondary`、`?attr/iconColorBrand`。

禁止在页面 XML 中新增硬编码 `#RRGGBB`。如需新颜色，先判断它是业务功能色还是主题 token，再放入 `common/src/main/res/values` 与 `values-night` 的对应资源文件。

## 字体与字号

统一使用 `TextAppearance.BabyCare.*`：

- `Display1`：关键统计数字。
- `Display2`：首页状态时长、卡片内重要数字。
- `Heading1`：页面内一级区块标题。
- `Heading2`：卡片标题、表单主分组。
- `Heading3`：表单子分组。
- `Body1`：主要正文、主按钮。
- `Body2`：普通说明、输入内容。
- `Label`：标签、辅助信息、短说明。

不要在布局中直接写 `android:textSize`。确实需要新层级时，先扩展 `TextAppearance.BabyCare.*`。

## 间距与尺寸

遵循 4dp / 8dp 节奏：

- 页面左右边距：手机 16dp，平板 32dp。
- 页面顶部内容间距：12dp 或 16dp。
- 区块之间：24dp。
- 卡片内边距：普通 16dp，重点状态卡 20dp。
- 表单输入高度：44dp 起。
- 主按钮高度：48dp。
- 图标触摸区域：不低于 48dp。

新增布局优先使用 `common/src/main/res/values/dimens.xml` 中的语义 dimen。不要在多个页面复制相同固定数值。

## 页面结构

首页 Dashboard：

1. 当前状态卡。
2. 距上次喂养 / 睡眠。
3. 下次预测。
4. 快速记录。
5. 统计或时间轴入口。

记录页：

1. 开始 / 结束时间。
2. 记录类型。
3. 数量、时长或详情。
4. 备注。
5. 固定底部保存按钮。

记录页的开始 / 结束时间字段使用 `bg_record_time_field`，时间值使用等宽数字特性，开始与结束列的标题均与字段左边界对齐。时间字段右侧使用统一线性 `ic_time` 图标，不使用通用新增图标或实心圆点。不要把结束时间标题右对齐，也不要使用过大的正文样式承载 `MM-dd HH:mm:ss`。

时间选择图标保留 20dp 图形和至少 48dp 触控范围；时间字段使用最小高度而非固定高度，日期与时钟的文本 span 从公共 TextAppearance 读取 sp 字号。窄窗或较大系统字号下开始 / 结束字段改为上下排列，保证输入、日期和秒级时间完整可读。

记录事件详情里的字段标签（如“大便性状”“测量部位”“疫苗名称”“备注”）统一使用 `EventDetailFieldLabel`：12sp、`colorTextSecondary`，标签到输入/选项使用 `event_detail_label_option_gap`，相邻字段组使用 `event_detail_field_gap`。字段标签只承担分组说明，输入内容和选项仍使用正文层级，避免标签与可操作选项层级过近。

统计页：

1. 日期选择。
2. 当日摘要。
3. 当日时间轴。
4. 规律洞察标题。
5. 趋势与结构。
6. 成长百分位与健康记录。

## 交互与无障碍

- 所有可点击区域至少 48dp。
- 相邻可点击元素至少 8dp 间距。
- 图标按钮必须有 `contentDescription`，纯装饰图标使用 `@null`。
- 表单错误必须出现在相关字段附近，并提供可恢复说明。
- 动效只用于状态变化、进入退出和按压反馈，时长控制在 150-300ms。
- 滚动到内容边界时不得显示发光、拉伸或回弹到头动画；页面滚动控件统一通过基类设置 `View.OVER_SCROLL_NEVER`，新增独立滚动控件或弹层滚动列表必须显式设置 `android:overScrollMode="never"` 或复用同等组件能力。
- 尊重系统字体缩放，不能为了布局稳定全局禁用缩放。
- dp / sp 保留系统密度，不以固定手机设计宽度修改全局 DisplayMetrics；窄窗口通过换行和重排适配，48dp 触控范围不能随窗口缩小。Android 14 及以后由系统处理非线性字体缩放。
- 记录页单选项使用 `WrappingRadioGroup` 按文字宽度换行，保留互斥选择和至少 48dp 热区；保存按钮按内容增高。摘要配对块使用 `AdaptiveRowLayout` 在窄窗或大字号下纵排，不能靠逐字折行挤进固定列数。
- 日历标题独占完整宽度，日期导航另起一行；星期缩写超出单元格时使用当前语言的窄格式，完整日期读屏描述不变。快速记录与事件子类型按实际可用宽度和字号减少列数，不截断标签。
- 统计日历的浏览周/月与已选统计日期分别保存，翻页不改变摘要归属；列表回收、记录点异步返回和页面恢复不能把浏览位置拉回选中日。仅日期标记变化时不重绑时间轴和洞察区；宝宝首个快照未到达时显示加载态，不能提前按“无宝宝”跳转。
- 高密度成长曲线按实际可视宽度分桶，保留首尾与每桶按原顺序排列的极值，缓存绘制 Path；密集数据不逐点画圆。采样仅影响绘制，不能改变原始记录、最新百分位、统计值或原索引的横轴间距；数据和尺寸变化时重建缓存。曲线使用 `event_growth`，参考线复用 `control_border_default` 与 `surface_stroke_width`。
- 选择类底部弹层在宽窗口中使用 `picker_sheet_max_width` 居中限宽；读屏需获得弹层用途、单选项选中状态，切换选项不能通过重建列表丢失焦点。
- 设置与备份行完整显示标题、说明和当前值，不以固定行数省略；主文案与尾部值/开关复用 `AdaptiveTrailingLayout`，窄窗或大字号时上下排列，空值不占额外行。开关保留至少 48dp 热区，并通过 `labelFor` 与标题关联。
- 通用确认/报告弹窗按当前窗口限制高度，标题与正文置于可滚动区域，操作区始终留在窗口内。按钮保留至少 48dp 热区并按完整文字增高，窄窗或大字号时上下排列，不能让长报告把确认操作推出屏幕。
- 自绘日历同样读取公共 TextAppearance 的 sp 字号；选中日期使用暖选中底、0.1dp 品牌前景描边与文字，今天兼为选中日时以下划线区分。记录点沿用弱提示层级，选中或今天不叠加记录点；完整日期、今天、有记录及选中状态仍需通过独立无障碍日期节点表达，并支持键盘选择。
- 统计摘要的数量文字必须在指标块底色上达到 4.5:1；排泄和其他类别使用正文色，业务功能色保留在对应图标上。

## 本地化

所有用户可见文本必须来自 `strings.xml`。`tools:text` 仅用于预览，可以写示例文本。Kotlin 中拼接用户可见文本时必须通过字符串资源和占位符完成。

多语言维护以 `tools/language/多语言对照表.xlsx` 为唯一源数据。新增或修改用户可见字符串时，先在 Excel 中补齐简体中文、English、繁体中文、日文、韩文，再运行 `./gradlew updateAllLanguages` 生成 `strings.xml`；不要直接把新文案写进 XML 后结束工作。

字符串放置遵循模块边界：`baby_recyclerview` 自用文案写入 Excel 的 `baby_recyclerview` sheet；其他文案能共享就优先写入 `common` sheet，仅 app 私有且不适合复用的文案才写入 `app` sheet。新增 key 必须语义稳定，优先使用 snake_case，并保留 `%1$s`、`%1$d`、`%%`、`\n` 等占位符和转义形式。

## MVVM 与列表规则

- Fragment 只渲染状态、转发事件、执行导航。
- ViewModel 只管理 UI 状态、校验和调用 Repository，不持有 View 或 Fragment。
- Adapter 只绑定 UI Model，不写业务流程判断。
- 数据库、磁盘和网络操作必须在 `Dispatchers.IO` 或统一调度器执行。
- Flow 收集必须绑定生命周期。
- 页面级加载、错误与可恢复空态统一使用 `components` 的 `PageStateView`；页面只根据 `UiState` 切换状态和转发重试事件。
- 系统栏、挖孔和键盘安全区只由 Activity 根容器统一避让，嵌套的 `BaseToolbar` 不重复增加状态栏高度。
- 首页默认字号下优先让常见手机首屏容纳完整快速记录入口：紧凑卡片使用 12dp 内边距与分组间隔、统一 24dp 功能图标，48dp 圆形入口及完整文字保持不变。预测内部用 4dp 间距，时间与剩余时长按实际字宽决定并排或纵排，依据始终在两者下方。大字号或长内容允许增高滚动，不以截字、缩小点击区域或隐藏预测依据换取首屏高度；状态操作区须避开图标和完整计时文字的最大底边。总时长与预测剩余量采用短单位但保留小时、分钟精度，仅“距上次”在超过一天时允许显示整天数。
- XML 页面按当前窗口宽度适配，不以物理“手机/平板”固定分支；中大窗口使用居中最大宽度内容列，网格按实际可用宽度重算，分屏变化不得只做等比缩放。

## 新页面自检

提交前至少确认：

- 未新增 Neutral / 中性主题。
- 未在 `app` 中新增通用 color、style、theme。
- 未给普通 View、卡片、列表项、输入框或统计模块新增阴影。
- 图标风格统一，未新增 emoji、硬编码黑色图标或卡通填充图标。
- 未硬编码用户可见字符串。
- 未硬编码页面专属颜色。
- 触摸目标不低于 48dp。
- 深色模式有对应资源。
- 业务逻辑没有进入 Adapter。
- 数据访问没有进入 Fragment。
