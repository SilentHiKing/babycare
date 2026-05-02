# 仓库规范（Repository Guidelines）

本文档定义了本 Android 项目的**工程结构、编码规范、架构约束以及 AI / Codex 行为规则**。  
所有人类开发者与 AI Agent 均需遵守。

---

## 项目结构与模块组织

- `app/`：Android 应用模块（Activity、Fragment、导航、UI 布局）。
- `babydata/`：数据层（Room 实体、DAO、Repository、数据库迁移）。
- `common/`：共享资源与工具（主题、Drawable、扩展函数、通用工具）。
- `components/`：可复用 UI 组件与基类（ViewBinding 辅助类、对话框、Toolbar 等）。
- `baby_recyclerview/`：自定义 RecyclerView 适配器与辅助工具。
- `tools/`：辅助脚本（语言相关工具位于 `tools/language/`）。
- 根 Gradle 配置位于 `build.gradle.kts`，版本目录位于 `gradle/libs.versions.toml`。

---

## 构建、测试与开发命令

- `./gradlew assembleDebug`：构建 Debug APK。
- `./gradlew installDebug`：将 Debug APK 安装到设备/模拟器。
- `./gradlew test`：运行 JVM 单元测试（`src/test/java`）。
- `./gradlew connectedAndroidTest`：运行仪器测试（`src/androidTest/java`）。
- `./gradlew updateAllLanguages`：刷新多语言资源。
- `./gradlew generateLanguageJson`：导出语言数据为 JSON。
- `./gradlew fetchInternationalLanguageList`：拉取支持的语言列表。

---

## 编码风格与命名规范

- Kotlin / Java 使用 **4 空格缩进**，遵循标准 Android 编码规范。
- 包名遵循 `com.zero.*`，类必须放在正确的模块包路径下。
- 资源文件使用 **snake_case**（例如：`layout_event_detail_diaper.xml`）。
- 共享扩展函数统一放在 `common/src/main/java/com/zero/common/ext`。

---

## 测试规范

- 单元测试：`*/src/test/java`（JUnit）。
- 仪器测试：`*/src/androidTest/java`（AndroidX Test + Espresso）。
- 测试类命名为 `*Test`。
- 新增的数据逻辑（`babydata`）或新的 ViewModel 行为应补充测试。
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
  - 长期持有 Context
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

---

## 异步与生命周期规范

- ViewModel 内协程 **必须使用 `viewModelScope`**
- View 层收集 Flow 必须绑定生命周期
- 磁盘 / 网络 / 数据库操作 **严禁在主线程执行**
- 必须使用 `Dispatchers.IO` 或统一调度器

---

## RecyclerView 规范（结合 MVVM）

- RecyclerView Adapter 只负责渲染列表项
- 不允许在 Adapter 中编写业务逻辑
- 列表项数据应来自 ViewModel 提供的 UI Model

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

- 所有由 AI / Codex **新生成或大幅修改的代码**，必须尽量补充**清晰、必要的代码注释**。
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

---

## 资源、主题与本地化约束

- 所有用户可见文本必须来自 `strings.xml`
- 禁止任何形式的硬编码字符串
- 主题、颜色、文字样式必须优先复用 `common/`
- 禁止在 `app/` 中重复定义 style / color / theme

---

## BabyCare UI 设计规范（强制）

所有 UI 新增、重构、视觉修复与图标调整，必须先阅读并遵守根目录 `DESIGN.md` 与 `docs/ui-guidelines.md`。
当前产品视觉基线为 **Soft Care + Data Clarity**，当前落地风格为 **Soft Utility 2.0**：温柔照护感、低噪音界面、清晰数据表达，配色必须温暖、活泼、干净，服务于父母高频记录、快速回看和理解婴儿生活规律。

### 主题与颜色

- 仅允许 **男孩 / 女孩** 两套主题，不得新增中性主题、默认主题或第三套临时主题。
- 主题色、语义色、文字色、背景色必须来自 `common/`，禁止在 `app/` 页面内写死色值或重复定义颜色。
- 男孩 / 女孩主题必须保持同一套信息层级：主色用于主要行动与当前状态，浅色用于背景提示和弱强调。
- Soft Utility 2.0 功能色必须保持暖活泼：喂养 `#247BA0`、睡眠 `#6E65B7`、排泄 `#F59E32`、健康 `#1FAE9B`、成长 `#5FAE63`、里程碑 `#D95C75`。
- 记录控件底色必须使用 `control_surface_default`、`control_surface_selected`、`control_border_default`、`event_icon_surface`，禁止回退到老式脏粉、灰底、高饱和旧蓝或沉闷灰紫。
- 除保存按钮和真正主行动按钮外，选中态优先使用浅暖底 + 1dp 描边 + 品牌/语义色文字或图标，不使用白字实色块、底部粗色条或旧蓝紫描边。
- 深色模式必须单独检查可读性，正文与关键操作的对比度目标不低于 4.5:1。
- 统计图表必须使用语义色表达事件类别，不得使用无业务含义的旧封面色或仅靠颜色传达信息。

### 布局与层级

- 页面密度以“高频照护记录”为目标：信息要紧凑、可扫读，避免营销页式大留白、装饰性卡片堆叠和不必要的插画区。
- 首页、记录页、统计页必须优先保证核心任务路径清晰：今日概览、快速记录、趋势理解不能被次要装饰抢占层级。
- 普通 View、列表项、输入框、统计模块和常规卡片原则上 **不设计阴影**。
- 禁止为普通 View 添加 `android:elevation`、`android:translationZ`、非零 `cardElevation`、伪阴影色块或阴影背景图。
- 层级表达应优先使用背景分区、边框、留白、标题层级、语义色和状态色。
- 阴影仅允许用于 Dialog、BottomSheet、系统浮层等真正覆盖层，并应优先复用 `components/` 中的统一组件。

### 字体与组件

- 字号、字重、行高必须优先使用 `TextAppearance.BabyCare.*`，不得在页面布局中随意硬编码 `textSize`、`fontFamily`。
- 新增通用文字样式必须放入 `common/`，不得在 `app/` 页面内局部复制 style。
- 交互控件触控目标应尽量不小于 48dp，控件间距至少 8dp，避免需要精确点击的小热区。
- 页面能力优先复用 `components/` 与既有组件；确需新增组件时，应放在合适模块并与现有主题 token 对齐。

### 图标规范

- 高频导航、记录、状态图标必须使用统一线性风格：24dp viewport、约 2dp stroke、round linecap / linejoin。
- 图标颜色必须使用主题属性或语义色，不得硬编码黑色、旧蓝紫色或与主题无关的色值。
- 婴儿风格应体现柔和、亲切、清晰，不得使用 emoji、卡通贴纸感图标、混合填充/线性风格或过度幼稚的装饰。
- 首页快速记录和记录事件页图标必须使用浅色圆形承托面，图标本体使用业务语义色；标签文字默认使用正文/辅助文字色，避免整段高饱和彩色文字。
- 新增通用图标应放入 `common/src/main/res/drawable`，业务专属图标才允许放在对应业务模块。

### UI 变更自检

- UI 改动必须同步检查浅色 / 深色模式，以及男孩 / 女孩主题下的观感。
- 调整主题、图标、卡片、统计图表或通用组件时，应补充或更新资源策略测试。
- UI 相关提交前至少运行对应单测与 `assembleDebug`；涉及 `DESIGN.md` 时还需运行 `npx @google/design.md lint DESIGN.md`。
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

---

## RecyclerView 使用规范（强制）

- 所有 RecyclerView 实现 **必须基于 `baby_recyclerview` 模块**
- 禁止在页面层自建通用 RecyclerView 方案
- 能力不足时必须扩展 `baby_recyclerview`

---

## AI 自检清单（强制）

AI 在输出最终结果前，必须确认：

- 模块放置与依赖方向正确
- 无硬编码用户可见字符串
- UI 改动符合 Soft Care + Data Clarity / Soft Utility 2.0、男孩 / 女孩主题、暖活泼配色、无普通 View 阴影和统一图标规范
- 异步逻辑符合线程与生命周期规范
- 不引入 View / Adapter / 回调相关内存泄漏
- MVVM 分层未被破坏
