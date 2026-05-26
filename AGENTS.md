# 仓库规范（Repository Guidelines）

本文档定义了本 Android 项目的**工程结构、编码规范、架构约束以及 AI / Codex 行为规则**。  
所有人类开发者与 AI Agent 均需遵守。

---

## 项目结构与模块组织

- `app/`：Android 应用模块，包名 `com.zero.babycare`。当前业务目录包括 `home/`（Dashboard、快速记录、喂养/睡眠/事件记录、预测）、`statistics/`（统计、时间轴、WHO 成长标准）、`settings/`（设置、备份导入导出）、`reminder/`（提醒调度与通知）、`babyinfo/`、`babies/`、`navigation/`。
- `app/src/main/assets/who_growth/`：WHO 成长标准 JSON 数据，配套生成脚本位于 `tools/who/generate_who_growth_data.py`。修改成长标准时必须同步校验解析和统计测试。
- `babydata/`：数据层模块，包名 `com.zero.babydata`。包含 `entity/`、`room/`、`domain/`、`backup/`，负责 Room 实体、DAO、Repository、数据库迁移、备份导入导出数据模型与逻辑。
- `babydata/schemas/`：Room schema 导出目录，由 KSP 参数 `room.schemaLocation` 生成。数据库版本、实体或迁移变化必须提交对应 schema。
- `common/`：共享资源与工具模块，包名 `com.zero.common`。放置主题、颜色、Drawable、TextAppearance、dimen、扩展函数、MMKV 封装、主题管理、单位/日期/语言等通用工具。
- `common/src/app_oversea/res/`：Google Play / overseas 变体语言资源输出目录，由多语言脚本生成，不能手工维护。
- `components/`：可复用 UI 与基础能力模块，包名 `com.zero.components`。包含 BaseActivity/BaseFragment、ViewBinding 辅助类、`UiState` / BaseViewModel、Toolbar、Dialog、BottomSheet、记录计时面板、时间输入和触摸/键盘滚动辅助能力。
- `baby_recyclerview/`：项目内 RecyclerView 基础能力模块，包名 `com.chad.library.adapter4`。封装 BRVAH 4 风格的 Adapter、LoadState、拖拽滑动、LayoutManager 与 ViewHolder 能力。
- `docs/`：项目设计、技术说明和阶段性计划文档。UI 相关变更必须同步参考 `DESIGN.md` 与 `docs/ui-guidelines.md`。
- `tools/`：辅助脚本目录。多语言工具位于 `tools/language/`，成长数据工具位于 `tools/who/`。
- 根 Gradle 配置位于 `settings.gradle.kts`、`build.gradle.kts`，版本目录位于 `gradle/libs.versions.toml`，多语言任务由 `i18n.gradle.kts` 挂载到根工程。

当前 Gradle 模块固定为：

- `:app`
- `:babydata`
- `:common`
- `:components`
- `:baby_recyclerview`

当前模块依赖方向：

- `app` → `babydata`、`components`、`common`、`baby_recyclerview`
- `babydata` → `common`
- `components` → `common`、`baby_recyclerview`
- `common` 与 `baby_recyclerview` 不依赖业务模块

---

## 构建、测试与开发命令

- Windows / PowerShell 优先使用 `.\gradlew.bat <task>`；macOS / Linux 使用 `./gradlew <task>`。以下命令用 `./gradlew` 表示，Windows 下替换为 `.\gradlew.bat`。
- `./gradlew assembleDebug`：构建 Debug APK，UI、资源、依赖或 AndroidManifest 变更后至少运行一次。
- `./gradlew installDebug`：将 Debug APK 安装到设备/模拟器。
- `./gradlew test`：运行所有 JVM 单元测试（`src/test/java`）。
- `./gradlew :app:testDebugUnitTest`：运行 app 模块 JVM 单元测试，适合业务逻辑、ViewModel、统计、预测、资源策略变更。
- `./gradlew :common:testDebugUnitTest`：运行 common 模块 JVM 单元测试，适合主题、单位、日期、工具类变更。
- `./gradlew connectedAndroidTest`：运行仪器测试（`src/androidTest/java`），需要已连接设备或模拟器。
- `./gradlew generateLanguageJson`：从 `tools/language/多语言对照表.xlsx` 导出 `tools/language/output.json`。
- `./gradlew fetchInternationalLanguageList`：从本地 JSON 生成 Android `strings.xml`。
- `./gradlew updateAllLanguages`：完整多语言刷新流程，等价于先生成 JSON 再生成各模块字符串资源。
- `npx @google/design.md lint DESIGN.md`：仅当修改 `DESIGN.md` 时运行。

---

## 编码风格与命名规范

- 当前工具链：AGP 8.13.0、Kotlin 2.2.0、KSP 2.2.0-2.0.2、compileSdk 36、targetSdk 36、minSdk 24、Java/Kotlin JVM target 11。
- Kotlin / Java 使用 **4 空格缩进**，遵循标准 Android 编码规范。
- 包名遵循 `com.zero.*`，类必须放在正确的模块包路径下。
- 资源文件使用 **snake_case**（例如：`layout_event_detail_diaper.xml`）。
- 共享扩展函数统一放在 `common/src/main/java/com/zero/common/ext`。
- 当前 UI 主体仍以 XML + ViewBinding / DataBinding 为主；`app` 已启用 Compose，但新增 Compose 代码必须先确认确有必要，并复用现有主题 token、资源字符串和 MVVM 状态流。
- 依赖版本统一写入 `gradle/libs.versions.toml`。新增三方库前必须确认现有 AndroidX、Material、Room、MMKV、Gson、XPopup、baby_recyclerview 或项目模块能力无法满足需求。

---

## 测试规范

- 单元测试：`*/src/test/java`（JUnit）。
- 仪器测试：`*/src/androidTest/java`（AndroidX Test + Espresso）。
- 测试类命名为 `*Test`。
- 单元测试遵循必要性原则：非关键逻辑、非回归高风险改动不强制新增或运行单元测试，尤其是纯 UI 布局、样式、图标、间距等视觉调整。
- 新增的数据逻辑（`babydata`）或新的 ViewModel 行为应补充测试。
- 涉及统计、预测、计时、资源策略、主题对比度、单位换算、备份导入导出的改动，应优先补充或更新现有对应测试（例如 `Statistics*Test`、`LocalRulePredictorTest`、`TimerSessionPolicyTest`、`UiResourcePolicyTest`、`ThemePaletteContrastTest`）。
- Room 实体、DAO、迁移或数据库版本变化时，必须同步更新 `babydata/schemas/` 并补充迁移或关键 DAO 行为测试。
- 未设置强制覆盖率目标，优先覆盖关键路径与回归风险点。

---

## 提交与 Pull Request 规范

- 提交历史偏好 **简短、单行摘要**。
- 每次提交保持聚焦，用一句话清晰描述改动内容。
- PR 需包含：
  - 清晰的改动说明


---

## 安全与配置

- 本地 SDK 路径与密钥放在 `local.properties`，**禁止提交到仓库**。
- 禁止在 `settings.gradle.kts`、`build.gradle.kts`、源码、资源或文档中新增明文账号、密码、Token、签名信息或私有仓库凭据。需要凭据时使用本地 Gradle properties、环境变量或 CI Secret。
- 如需 Release 混淆规则，使用各模块内的 `proguard-rules.pro`。 

---

# Android MVVM 编码规范（强制）

本项目 UI 层统一采用 **MVVM（Model–View–ViewModel）架构**。  
以下规则对所有 UI 功能开发 **强制生效**。

---

## MVVM 分层职责

### View（Activity / Fragment / Compose）

- 只负责：
  - UI 渲染（根据 State 渲染）
  - 用户输入采集与事件转发
  - 导航或导航意图触发
- 禁止：
  - 直接访问 Repository / 数据库 / 网络
  - 编写业务规则或流程判断
  - 启动不受生命周期控制的异步任务

---

### ViewModel

- 只负责：
  - 管理 UI 状态（State）
  - 接收 View 的事件（Event）
  - 调用 UseCase / Repository
  - 处理 UI 级逻辑（校验、状态组合、错误映射）
- 禁止：
  - 持有 View / Activity / Fragment 引用
  - 长期持有 Activity / Fragment Context；确需 Application Context 时，仅允许使用 `AndroidViewModel` 或显式 Application 级依赖，并说明原因
  - 直接操作 UI（Toast、Dialog、导航）
  - 使用 `GlobalScope`

---

### Model（Repository / UseCase）

- Repository：
  - 负责数据获取与持久化
  - 封装 Room / Network / Cache 细节
- UseCase（如存在）：
  - 承载业务规则与流程
  - ViewModel 不应直接编写复杂业务逻辑

---

## 单向数据流（强制）

- View → Event → ViewModel  
- ViewModel → State / Effect → View

推荐结构：
- `UiState`：页面状态（可重放）
- `UiEvent`：用户操作
- `UiEffect`：一次性动作（Toast、导航等）

当前项目已有基础能力：
- 通用 `UiState`、`BaseViewModel`、`BaseViewModel1` 位于 `components/src/main/java/com/zero/components/base/vm`。
- 页面状态优先以 `StateFlow` 对外暴露只读流，内部使用 `MutableStateFlow`。
- 一次性 UI 行为（Toast、Dialog、导航、文件选择、权限请求）由 View 层执行，ViewModel 只输出状态或 effect 意图。

---

## 异步与生命周期规范

- ViewModel 内协程 **必须使用 `viewModelScope`**
- View 层收集 Flow 必须绑定生命周期
- 磁盘 / 网络 / 数据库操作 **严禁在主线程执行**
- 必须使用 `Dispatchers.IO` 或统一调度器
- `BroadcastReceiver`、通知、备份导入导出、Room 查询等后台路径必须显式控制线程，不得阻塞主线程或依赖不受生命周期约束的任务。

---

## RecyclerView 规范（结合 MVVM）

- RecyclerView Adapter 只负责渲染列表项
- 不允许在 Adapter 中编写业务逻辑
- 列表项数据应来自 ViewModel 提供的 UI Model
- 新增通用列表、页面列表和业务列表优先使用 `baby_recyclerview` 中的 `BaseQuickAdapter`、`BaseSingleItemAdapter`、`QuickAdapterHelper` 等能力。
- 当前项目存在少量高度定制的原生 `RecyclerView.Adapter`（如日历、单项统计区块或内部组件）。修改这些实现时只能保持其专用边界，不得扩散为新的通用列表方案；若能力已能由 `baby_recyclerview` 承载，应顺手迁移。

---

# AI / Codex 强约束规则（必须遵守）

以下规则对所有在本仓库中运行的 AI / Codex Agent **强制生效**。

- 违反任一规则，均视为错误结果  
- 如请求与规则冲突，必须先说明冲突并给出合规替代方案

---

## 语言约束（强制）

- 所有 AI / Codex 的**解释性输出、分析说明、注释与文档**，**默认使用简体中文**。
- 仅当用户明确要求使用其他语言时，才允许切换语言。
- **代码本身不进行语言翻译**：
  - 类名、方法名、变量名统一使用英文
  - API 名称、库名称保持原样

---

## AI 代码注释规范（强制）

- 所有由 AI / Codex **新生成或大幅修改的关键代码**，必须补充**简洁明了、清晰必要的代码注释**。
- 注释语言 **必须使用简体中文**，用于解释：
  - 该代码块的目的与职责
  - 关键逻辑的设计原因
  - 非直观或容易误解的实现细节
- 注释应重点覆盖：
  - 核心业务逻辑
  - 状态转换与条件分支
  - 异步流程、线程切换与生命周期处理
  - 容易出错或存在约束条件的代码段

---

### 注释范围与要求

- 注释应：
  - 说明「**为什么这样写**」，而不仅是「**做了什么**」
  - 保持简洁、准确，避免冗余废话
- 不强制要求对每一行代码写注释，但：
  - **关键类**
  - **关键方法**
  - **关键判断与流程**
    必须有对应说明

---

### 注释与代码职责边界

- 代码标识符（类名、方法名、变量名）：
  - 统一使用英文
  - 不进行中文翻译
- 代码注释与文档说明：
  - 统一使用简体中文
- 不得通过注释弥补糟糕的命名或违反规范的结构设计。

---

### 例外说明

- 对于简单、直观、无需解释的代码（如标准 Getter、简单 View 绑定），可适当省略注释。
- 若因代码本身已高度自解释而未添加注释，AI 必须确认该代码**对维护者仍然清晰易懂**。

## 模块依赖与边界约束

- `common/` 与 `components/` **禁止依赖** `app/` 或业务模块
- `babydata/` **禁止依赖** UI 模块
- `app/` 不得引入循环依赖
- 业务逻辑与业务资源禁止放入 `common/` 或 `components/`
- `common/` 只放跨模块稳定资源与工具，不放 BabyCare 单页面业务流程。
- `components/` 可依赖 `common/` 和 `baby_recyclerview`，用于沉淀通用 UI 容器、输入控件、弹窗、Toolbar、基础 Fragment / Activity 与 ViewModel 辅助能力。
- `babydata/` 可依赖 `common/`，但不得依赖 `components/`、`app/` 或任何 Android UI 类型。
- `baby_recyclerview/` 保持为列表基础设施模块，不反向依赖 BabyCare 业务模块。

---

## 资源、主题与本地化约束

- 所有用户可见文本必须来自 `strings.xml`
- 禁止任何形式的硬编码用户可见字符串；`tools:text` 仅允许用于 XML 预览
- 主题、颜色、文字样式必须优先复用 `common/`
- 禁止在 `app/` 中重复定义 style / color / theme
- `strings.xml`、`tools/language/output.json`、`common/src/app_oversea/res/values*/strings.xml` 均为生成结果，除紧急排查外不得手动编辑后直接提交。
- Kotlin 中的用户可见拼接必须通过字符串资源占位符完成，不得用字符串模板直接拼接界面文案。

---

## 多语言开发规范（强制）

- 多语言源数据以 `tools/language/多语言对照表.xlsx` 为准，`strings.xml` 与 `tools/language/output.json` 均视为脚本生成结果。
- 新增或修改任何用户可见字符串时，必须先写入 `tools/language/多语言对照表.xlsx`，补齐当前脚本支持的语言列（简体中文、English、繁体中文、日文、韩文），再运行 `./gradlew updateAllLanguages` 生成 XML。
- 禁止只手动修改 `strings.xml` 来新增或改动文案；如因排查临时改过 XML，提交前必须同步回 Excel 并重新运行生成脚本。
- 当前脚本语言映射为 `en`、`zh-Hans`、`zh-Hant`、`ja`、`ko`，生成目录覆盖各模块 `src/main/res/values*`，其中繁体中文会生成 `values-zh-rTW` 与 `values-zh-rHK`。
- `common` 还会生成 Google Play / overseas 资源到 `common/src/app_oversea/res/values*`，新增通用文案时必须检查该输出。
- 字符串归属优先级：
  - `baby_recyclerview` 模块自用文案必须写入 Excel 的 `baby_recyclerview` sheet，并生成到 `baby_recyclerview/src/main/res/values*/strings.xml`。
  - 除 `baby_recyclerview` 外，能复用或可能跨页面/跨模块使用的文案优先写入 `common` sheet，并生成到 `common/src/main/res/values*/strings.xml`。
  - 仅 `app` 模块单页面私有、明确不适合共享的文案，才允许写入 `app` sheet。
- 新增 `codeKey` 必须语义清晰、保持稳定，优先使用 snake_case；不得为了某个页面复制含义相同的已有 key。
- 翻译必须贴合 BabyCare 场景与父母高频记录语境，避免生硬直译；涉及喂养、睡眠、排泄、健康、成长等业务词时应保持全项目一致。
- 多语言值必须保留 Android 格式化占位符与转义形式，例如 `%1$s`、`%1$d`、`%%`、`\n`；不同语言不得遗漏、改序或改类型。
- 运行 `./gradlew updateAllLanguages` 后，至少检查相关生成文件，并在提交前运行 `./gradlew assembleDebug`；涉及资源策略或文案引用规则变化时还应运行对应单测或 `./gradlew test`。

---

## 数据、备份、提醒与成长标准约束

- Room 数据库入口位于 `babydata/src/main/java/com/zero/babydata/room/BabyDatabase.kt`，DAO、Repository 与迁移集中在 `babydata/room/`。实体字段、索引、版本号或迁移变化必须同步更新 schema 与迁移逻辑。
- `babydata/backup/` 负责备份数据模型、导入、导出和去重策略；`app/settings/backup/` 负责界面、文件选择、报告展示和用户反馈。不得把备份业务规则写进 Fragment 或 Adapter。
- `app/reminder/` 负责提醒调度、开机恢复、通知构建与后台检查。通知文案必须来自资源；权限、渠道和开关状态必须在发送前检查；Receiver 不得执行长时间主线程工作。
- `app/home/prediction/` 是喂养 / 睡眠预测入口，新增预测策略时应通过 `Predictor` / `PredictionManager` 扩展，不直接在 Dashboard 或提醒代码中复制算法。
- WHO 成长标准 JSON 位于 `app/src/main/assets/who_growth/`，解析和截断规则位于 `app/statistics/standard` 与 `app/statistics/mapper`。修改数据文件、年龄截断、性别解析或百分位逻辑时，必须运行相关 `StatisticsGrowthCutoffTest`、`WhoSexParserTest` 等测试。
- 宝宝姓名、生日、健康、成长、备份内容属于敏感数据。日志、异常消息、通知和导出报告只展示完成用户任务所必需的信息。

---

## BabyCare UI 设计规范（强制）

所有 UI 新增、重构、视觉修复与图标调整，必须先阅读并遵守根目录 `DESIGN.md` 与 `docs/ui-guidelines.md`。
当前产品视觉基线为 **Soft Care + Data Clarity**，当前落地风格为 **Soft Utility 2.0**：温柔照护感、低噪音界面、清晰数据表达，配色必须温暖、活泼、干净，服务于父母高频记录、快速回看和理解婴儿生活规律。

### 产品与信息层级

- BabyCare 是婴儿生活记录与规律洞察工具，不是装饰型相册或营销页；界面优先服务疲劳、夜间、单手场景下的快速记录和快速判断。
- 核心体验优先级固定为：快速记录、当前状态判断、今日与近期规律洞察、成长瞬间沉淀。
- 页面密度以“高频照护记录”为目标：信息紧凑、可扫读，避免营销页式大留白、装饰卡片堆叠和不必要插画区。
- 首页、记录页、统计页必须优先保证核心任务路径清晰：今日概览、快速记录、趋势理解不能被次要装饰抢占层级。
- 页面第一层应表达当前主任务或同一业务对象；同一天的日期、年龄、摘要和时间轴等连续内容应保持视觉连续，不拆成多张互不相关的卡片。
- 洞察区、汇总区和说明区是第二层，应使用独立区块标题和统一 `surface + stroke` 样式建立分段。

### 主题与颜色

- 仅允许 **男孩 / 女孩** 两套宝宝主题：`AppTheme.Boy` 与 `AppTheme.Girl`。默认 `AppTheme` 只作为 Android 样式父主题，不作为第三套宝宝视觉风格；未设置宝宝或无法识别性别时使用男孩主题兜底。
- 禁止新增 `Neutral`、中性主题、默认宝宝主题或新的性别颜色分支。
- 主题色、语义色、文字色、背景色必须来自 `common/`；禁止在 `app/` 页面内硬编码 `#RRGGBB` 或重复定义通用 color / style / theme。
- 男孩 / 女孩主题必须区分行动色与柔和背景色：行动色用于主按钮、保存按钮、选中态和关键强调；柔和背景色只用于头像底、浅卡片、轻量状态背景，不承载白字。
- 行动色叠加白字时对比度必须不低于 4.5:1；深色模式必须单独定义与检查，不能从浅色模式直接推断。
- 喂养、睡眠、排泄、健康、成长、里程碑等业务类型必须使用固定功能色，不随男孩 / 女孩主题变化：喂养 `#247BA0`、睡眠 `#6E65B7`、排泄 `#F59E32`、健康 `#1FAE9B`、成长 `#5FAE63`、里程碑 `#D95C75`。
- 护理、活动、其他等事件扩展色必须沿用 `common` 中的 Soft Utility 2.0 语义色，不得回退到老式高饱和旧蓝、脏粉、灰底或沉闷灰紫。
- XML 优先使用语义属性和 token：`?attr/colorBackground`、`?attr/colorSurface`、`?attr/colorTextPrimary`、`?attr/colorBrand`、`?attr/colorOnBrand`、`?attr/iconColor*`、`@color/control_*`、`@color/event_*`。
- 如确需新增颜色，必须先判断是主题 token 还是业务功能色，再放入 `common/src/main/res/values` 与 `values-night` 对应资源文件。

### 记录控件与状态

- 记录控件、输入框、分段控件和普通 Chip 必须使用 `control_surface_default`、`control_surface_selected`、`control_border_default`、`event_icon_surface`。
- 普通 surface / control surface 描边统一引用 `@dimen/surface_stroke_width`（0.1dp），不得随意改为 1dp、粗描边或底部粗色条。
- 除保存按钮和真正主行动按钮外，选中态优先使用浅暖底 + 0.1dp 品牌/语义描边 + 品牌/语义文字或图标；不使用白字实色块、旧蓝紫描边或高饱和彩色文字。
- 喂养页辅食分类和子类型的选中态必须与上方喂养类型分段控件一致：`control_surface_selected` 浅暖底、`?attr/colorBrand` 描边、`?attr/colorBrand` 文字，并复用 `selector_radio_text_color`。
- 辅食 Chip 不得另建 `feeding_primary` 或固定蓝色选中态，避免同页出现两套状态色。
- 确认弹窗按钮必须清楚区分主次操作：确认按钮使用 `?attr/colorBrand` 实色底和 `?attr/colorOnBrand` 文字；取消按钮使用 `control_surface_default` 浅底、`control_border_default` 边框、`?attr/colorBrand` 文字，按压态切到 `control_surface_selected` + 品牌描边。
- 取消按钮不能做成接近空白或禁用态，也不能在按压态写死旧蓝色。

### 布局与层级

- 普通 View、列表项、输入框、统计模块和常规卡片原则上 **不设计阴影**。
- 禁止为普通 View 添加 `android:elevation`、`android:translationZ`、非零 `cardElevation`、伪阴影色块或阴影背景图。
- `elevationSmall`、`elevationMedium` 等历史 token 仅作兼容入口，默认值必须为 `0dp`。
- 层级表达优先使用背景分区、0.1dp 边框、留白、标题层级、语义色和状态色。
- Dialog、BottomSheet、系统浮层等临时覆盖容器允许使用轻量阴影或遮罩，但必须优先由 `components/` 统一提供，页面 XML 不单独声明阴影。
- 指标块统一使用浅底、0.1dp 边框、8dp 圆角；数字可使用业务语义色，说明文字使用弱化文本色，不能用大面积彩色底或阴影制造层级。
- 区块之间建议使用约 24dp 垂直间距，区块内部卡片之间保持 12-16dp 间距，避免页面看起来是一整片无边界内容。

### 字体与组件

- 字号、字重、行高必须优先使用 `TextAppearance.BabyCare.*`，不得在页面布局中随意硬编码 `textSize`、`fontFamily`。
- 新增通用文字样式必须放入 `common/`，不得在 `app/` 页面内局部复制 style。
- 正文最小 14sp，关键正文优先 16sp；统计数字可以使用 Display 样式，但卡片标题和表单标签不能滥用大字号。
- 交互控件触控目标应尽量不小于 48dp，控件间距至少 8dp，避免需要精确点击的小热区。
- 页面能力优先复用 `components/` 与既有组件；确需新增组件时，应放在合适模块并与现有主题 token 对齐。
- Toolbar 统一使用 `BaseToolbar`；通用 Dialog、BottomSheet、记录计时面板、输入组件应放在 `components`。
- 新增布局优先使用 `common/src/main/res/values/dimens.xml` 中的语义 dimen，不得在多个页面复制相同固定数值。
- 底部固定按钮和快捷操作栏必须给滚动内容预留 bottom padding，避免内容被遮挡。
- 滚动控件默认不得显示发光、拉伸或回弹到头动画；新增独立滚动控件或弹层滚动列表必须设置 `android:overScrollMode="never"` 或复用同等组件能力。

### 图标规范

- 高频导航、记录、状态图标必须使用统一线性风格：24dp viewport、约 2dp stroke、round linecap / linejoin。
- 图标颜色必须使用主题属性或语义色，不得硬编码黑色、旧蓝紫色或与主题无关的色值。
- 婴儿生活图标应表达“记录类型”和“状态”，保持柔和、亲切、清晰；不得使用 emoji、卡通贴纸感图标、混合填充/线性风格或过度幼稚装饰。
- 首页快速记录、记录事件大类和事件子类型必须使用同一套线性图标语言。
- 快速记录和事件子类型图标应放在 48dp 左右的浅色圆形承托面内，图标本体使用对应业务语义色；标签文字默认使用正文/辅助文字色，避免整段高饱和彩色文字。
- 新增通用图标应放入 `common/src/main/res/drawable`，业务专属图标才允许放在对应业务模块。
- 统计图表必须使用语义色表达事件类别，并提供文字说明；不得使用无业务含义的旧封面色或仅靠颜色传达信息。

### 页面结构基准

- 首页 Dashboard 顺序：当前状态卡、距上次喂养 / 睡眠、下次预测、快速记录、统计或时间轴入口。
- 记录页顺序：开始 / 结束时间、记录类型、数量 / 时长 / 详情、备注、固定底部保存按钮。
- 记录页开始 / 结束时间字段使用 `bg_record_time_field`，时间值使用等宽数字特性，标题与字段左边界对齐，右侧使用统一线性 `ic_time` 图标。
- 不要把结束时间标题右对齐，不要用过大的正文样式承载 `MM-dd HH:mm:ss`，也不要使用通用新增图标或实心圆点替代时间图标。
- 统计页顺序：日期选择、当日摘要、当日时间轴、规律洞察标题、趋势与结构、成长百分位与健康记录。

### UI 变更自检

- UI 改动必须同步检查浅色 / 深色模式，以及男孩 / 女孩主题下的观感。
- 调整主题、图标、卡片、统计图表或通用组件时，应补充或更新资源策略测试。
- UI 相关提交前至少运行 `assembleDebug`；纯视觉调整不强制运行单元测试，只有涉及资源策略、主题对比度、状态流、业务逻辑或回归高风险路径时才运行对应单测。涉及 `DESIGN.md` 时还需运行 `npx @google/design.md lint DESIGN.md`。
- 若新增了可复用 UI 模式，必须同步更新 `DESIGN.md` 或 `docs/ui-guidelines.md`，避免规范与实现脱节。

---

## 线程、生命周期与内存安全约束

- 严禁主线程 I/O
- 禁止使用 `GlobalScope`
- 自定义 View 必须解除监听
- Adapter / ViewHolder 不得引入内存泄漏

---

## 工具使用规范（强制）

- 常用工具与基础能力 **必须优先复用已有依赖或模块**
- 禁止重复造轮子
- 新增工具需放入合适的共享模块
- 新增依赖必须先写入 `gradle/libs.versions.toml`，并确认不会破坏模块边界或引入与现有能力重复的库。

---

## RecyclerView 使用规范（强制）

- 新增通用 RecyclerView 能力与业务列表 **必须优先基于 `baby_recyclerview` 模块**
- 禁止在页面层自建通用 RecyclerView 方案
- 能力不足时优先扩展 `baby_recyclerview` 或 `components` 中已有列表辅助能力
- 当前已有的原生 `RecyclerView.Adapter` 只允许作为专用、局部实现继续维护；重构或新增同类列表时应评估迁移到 `baby_recyclerview`

---

## AI 自检清单（强制）

AI 在输出最终结果前，必须确认：

- 模块放置与依赖方向正确
- 无硬编码用户可见字符串
- 新增或修改文案已同步 `tools/language/多语言对照表.xlsx` 并运行生成任务
- UI 改动符合 Soft Care + Data Clarity / Soft Utility 2.0、男孩 / 女孩主题、暖活泼配色、无普通 View 阴影和统一图标规范
- 异步逻辑符合线程与生命周期规范
- Room schema、备份、提醒、预测或 WHO 成长数据变更已有对应测试或明确验证
- 不引入 View / Adapter / 回调相关内存泄漏
- MVVM 分层未被破坏
