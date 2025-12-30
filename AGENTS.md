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

- 提交历史偏好 **简短、单行摘要**（通常为版本或功能标签，如 `v6`）。
- 每次提交保持聚焦，用一句话清晰描述改动内容。
- PR 需包含：
  - 清晰的改动说明
  - 测试说明（运行过的命令）
  - UI 改动需提供截图

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
- 异步逻辑符合线程与生命周期规范
- 不引入 View / Adapter / 回调相关内存泄漏
- MVVM 分层未被破坏
