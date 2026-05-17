# BabyCare Android UI 开发规范

本文档是 BabyCare 当前 UI 优化的落地规范。视觉源头以根目录 `DESIGN.md` 为准，Android 资源以 `common` 模块的 theme、color、style、dimen 为准。

当前执行版本为 **Soft Utility 2.0**：在 Soft Care + Data Clarity 基线下，配色必须温暖、活泼、干净，禁止回退到老式糖果图标、脏粉控件底、沉闷灰紫或高饱和旧蓝。

## 产品定位

BabyCare 是婴儿生活记录与规律洞察工具，不是装饰型相册应用。界面优先保证父母在疲劳、夜间、单手操作时能快速完成记录，并在白天看懂宝宝的喂养、睡眠、排泄、健康与成长趋势。

核心体验顺序：

1. 快速记录。
2. 当前状态判断。
3. 今日与近期规律洞察。
4. 成长瞬间沉淀。

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
- 柔和背景色只能搭配深色文字或用于头像底、浅卡片、轻量状态背景，禁止直接用于白字按钮或选中态底色。

## Soft Utility 2.0 色板

功能色固定，不随男孩 / 女孩主题变化：

- 喂养：`feeding_primary #247BA0`，浅底 `feeding_bg #EAF7F9`。
- 睡眠：`sleep_primary #6E65B7`，浅底 `sleep_bg #F1EEFB`。
- 排泄：`event_diaper #F59E32`，浅底 `event_diaper_light #FFE9C7`。
- 健康：`event_health #1FAE9B`，浅底 `event_health_light #D8F4EE`。
- 成长：`event_growth #5FAE63`，浅底 `event_growth_light #E1F2DF`。
- 里程碑：`event_milestone #D95C75`，浅底 `event_milestone_light #FCE2E8`。
- 护理：`event_care #4E9FE6`，活动：`event_activity #9A72E6`，其他：`event_other #73808C`。

记录控件底色固定使用：

- `control_surface_default #FFFDFC`：未选中控件、输入框、普通记录控件。
- `control_surface_selected #FFF3E8`：选中 Chip、选中事件卡、轻量当前态。
- `control_border_default #E3ECEB`：未选中控件和输入框 hairline 边框，统一使用 `@dimen/surface_stroke_width`（0.1dp）。
- `event_icon_surface #FFF2D9`：通用事件图标承托面。

选中态默认是浅暖底 + 0.1dp 品牌/语义描边 + 品牌/语义文字，不做白字实色块。保存按钮和真正主行动按钮例外，继续使用品牌实色底。

喂养页的辅食分类和子类型位于喂养类型下方，选中态必须参考上方喂养类型分段控件：`control_surface_selected` 浅暖底、`?attr/colorBrand` 描边、`?attr/colorBrand` 文字。辅食 Chip 文字复用 `selector_radio_text_color`，不得另建一套 `feeding_primary` 或固定蓝色选中态，避免同页出现两套状态色。

确认弹窗按钮必须清楚区分主次操作：确认按钮使用 `?attr/colorBrand` 实色底和 `?attr/colorOnBrand` 文字；取消按钮使用 `control_surface_default` 浅底、`control_border_default` 边框、`?attr/colorBrand` 文字，按压态切到 `control_surface_selected` + 品牌描边。取消按钮不能做成接近空白或禁用态，也不能在按压态写死旧蓝色。

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
- 品牌：`?attr/colorBrand`、`?attr/colorBrandLight`、`?attr/colorBrandDark`、`?attr/colorOnBrand`。
- 图标：`?attr/iconColorPrimary`、`?attr/iconColorSecondary`、`?attr/iconColorBrand`。

禁止在页面 XML 中新增硬编码 `#RRGGBB`。如需新颜色，先判断它是业务功能色还是主题 token，再放入 `common/src/main/res/values` 与 `values-night` 的对应资源文件。

## 字体与字号

统一使用 `TextAppearance.BabyCare.*`：

- `Display1`：首页状态时长、关键统计数字。
- `Display2`：卡片内重要数字。
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

## 本地化

所有用户可见文本必须来自 `strings.xml`。`tools:text` 仅用于预览，可以写示例文本。Kotlin 中拼接用户可见文本时必须通过字符串资源和占位符完成。

## MVVM 与列表规则

- Fragment 只渲染状态、转发事件、执行导航。
- ViewModel 只管理 UI 状态、校验和调用 Repository，不持有 View 或 Fragment。
- Adapter 只绑定 UI Model，不写业务流程判断。
- 数据库、磁盘和网络操作必须在 `Dispatchers.IO` 或统一调度器执行。
- Flow 收集必须绑定生命周期。

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
