# 计时控件业务逻辑整改设计

日期：2026-05-02

## 背景

当前 `RecordTimerPanelView` 是计时器、开始时间输入框、结束时间输入框和时间选择入口的组合控件。业务逻辑主要分散在 `RecordTimerController`、`RecordView`、`TimerCounter`、喂养/睡眠/事件页面，以及 `OngoingRecordManager`。

本次设计目标是统一开始时间、结束时间、计时长度和进行中记录之间的关系，避免 UI 显示、保存数据和恢复状态不一致。

## 竞品参考结论

参考 Huckleberry、Mela、NapNap、Baby Life Tracker、MilkMode、ParentLove、Baby Connect 等宝宝记录产品后，计时器的共性规则如下：

- 支持一键开始/停止，也支持补录过去的开始时间和结束时间。
- 后台或离开页面后，进行中计时应能通过开始时间恢复，而不是依赖页面进程内计数。
- 睡眠记录以入睡时间和醒来时间为核心，最终时长应来自 `endAt - startAt`。
- 喂养计时可以支持暂停、继续、左右侧切换；喂养的有效计时时长可以与开始/结束时间跨度不同。
- 保存前应让用户能理解当前记录的开始、结束和时长来源。

## 当前问题

### 进行中开始时间可能失真

当用户先手动输入过去的开始时间，再点击计时器开始时，界面计时会按过去时间展示，但 `OngoingRecordManager.startFeeding/startSleep()` 当前保存的是 `System.currentTimeMillis()`。页面恢复后，开始时间会变成点击计时器的时间，导致状态卡、恢复计时和保存数据不一致。

### 时长语义不统一

当前保存喂养和睡眠时都优先取 `timerView.getDuration()`，只有当计时器时长为 0 或超过 `end - start` 时才回退到时间差。这样会导致睡眠记录在暂停/继续后出现 `sleepDuration < sleepEnd - sleepStart`，不符合睡眠记录语义。

### 状态来源过多

计时状态同时存在于 `RecordView/TimerCounter`、`RecordTimerController`、页面 Fragment 和 `OngoingRecordManager`。后续扩展母乳左右侧计时、锁屏恢复或跨页面状态时，容易出现多份状态互相覆盖。

### 长时长显示不符合场景

当前 `formatToMinSec()` 对超过 1 小时的记录会显示为 `120:00` 这类分钟累计格式。睡眠、户外活动、亲子活动等长时长场景应显示为 `2:00:00` 或更适合中文界面的小时分钟格式。

## 设计目标

- `RecordTimerPanelView` 保持纯 UI 组件，不承载业务规则。
- 开始时间、结束时间、时长由统一状态模型管理。
- 睡眠记录最终时长固定为 `endAt - startAt`，暂停/继续不扣除暂停时间。
- 喂养记录保留可暂停累计能力，但明确区分“时间跨度”和“有效计时时长”。
- 进行中记录恢复只依赖持久化的 `startAt` 和业务类型，不依赖 View 内部计数器。
- 手动输入、时间选择器、计时器点击三种入口必须得到一致结果。

## 统一状态模型

新增或等价抽象一个计时会话状态：

```kotlin
data class TimerSessionState(
    val startAt: Long = 0L,
    val endAt: Long? = null,
    val status: TimerStatus = TimerStatus.Idle,
    val activeDuration: Long = 0L,
    val displayDuration: Long = 0L,
    val durationSource: DurationSource = DurationSource.None
)
```

建议语义：

- `startAt`：业务开始时间，是进行中记录恢复的唯一开始时间来源。
- `endAt`：业务结束时间；进行中时为空。
- `status`：`Idle / Running / Paused / Completed`。
- `activeDuration`：真正计时器累计运行时长，主要用于喂养。
- `displayDuration`：页面展示时长，由业务策略计算。
- `durationSource`：标记展示和保存时长来源，如 `TimeRange / ActiveTimer / Manual`。

## 页面业务规则

### 睡眠记录

- 开始：设置 `sleepStart = startAt`。
- 暂停：可以临时填入 `sleepEnd = now`，用于用户确认醒来时间。
- 继续：清空 `sleepEnd`，继续展示从 `startAt` 到当前时间的跨度。
- 保存：强制使用 `sleepDuration = sleepEnd - sleepStart`。
- 暂停/继续不扣除暂停时间。

### 喂养记录

- 开始：设置 `feedingStart = startAt`。
- 暂停：填入 `feedingEnd = now`，并累计 `activeDuration`。
- 继续：如果结束时间被手动修改过，继续前确认会清空结束时间。
- 保存：
  - 母乳/混合场景优先保留当前有效计时时长能力。
  - 若没有有效计时时长，使用 `feedingEnd - feedingStart`。
  - 左右乳房手动分钟数不得超过最终喂养时长。
- 后续可扩展左右侧独立计时，但不纳入本轮实现。

### 活动类事件

- 活动类事件使用开始/结束时间表达时长。
- 保存时使用 `endTime - time`，不使用计时器累计运行时长。
- 若用户点击计时器暂停，结束时间自动设为当前时间。

## 交互规则

### 修改开始时间

- 设置新的 `startAt`。
- 清空 `endAt`。
- 重置计时累计。
- 若存在进行中记录，同步持久化的 `startAt`。

### 修改结束时间

- 如果正在计时，先暂停当前计时器。
- 校验 `endAt > startAt`。
- 根据业务类型刷新展示时长：
  - 睡眠/活动：展示 `endAt - startAt`。
  - 喂养：手动结束时间场景展示 `endAt - startAt`，已累计的 active duration 可作为保存候选。

### 点击开始

- 如果已有有效 `startAt`，从该时间恢复展示。
- 如果没有 `startAt`，使用当前时间作为 `startAt`。
- 持久化进行中记录时必须保存最终确定的 `startAt`，不得再次用当前时间覆盖。

### 点击暂停

- 设置 `endAt = now`。
- 睡眠/活动展示 `now - startAt`。
- 喂养累计本段 active duration。

### 点击继续

- 如果 `endAt` 是自动暂停产生的，可以直接清空并继续。
- 如果 `endAt` 被用户手动修改过，必须提示继续后会清空结束时间。

## 实施范围

### 第一阶段

- 修正进行中记录开始时间持久化逻辑。
- 睡眠保存时长改为 `sleepEnd - sleepStart`。
- 活动事件保存/展示时长统一使用时间范围。
- 长时长展示支持小时。
- 为核心计时状态补充单元测试。

### 第二阶段

- 将 `RecordTimerController` 内部规则收敛到统一状态模型。
- 减少 Fragment 直接读取 `timerView.getDuration()` 的场景。
- 明确 `DurationSource`，用于调试和后续 UI 展示。

### 后续阶段

- 母乳左右侧计时器。
- 记住上次喂养侧。
- 锁屏/通知/桌面组件继续计时入口。

## 测试策略

- 单元测试覆盖：
  - 手动开始 + 点击计时开始后，进行中 `startAt` 不被当前时间覆盖。
  - 睡眠暂停/继续后保存，`sleepDuration == sleepEnd - sleepStart`。
  - 结束时间早于开始时间时拒绝保存。
  - 跨天时间：23:50 到 00:30 计算为正向 40 分钟。
  - 超过 1 小时时长显示为小时格式。
- 页面回归：
  - 喂养记录新建、暂停、继续、保存。
  - 睡眠记录新建、暂停、继续、保存。
  - 活动类事件新建和编辑。
  - 从首页状态卡恢复进行中喂养/睡眠。

## 不纳入本轮

- 不实现母乳左右侧独立计时。
- 不实现通知栏或锁屏计时控制。
- 不调整计时器视觉样式，除非实现中必须配合长时长显示。
- 不改动 JitPack 明文账号密码配置。

## 验收标准

- 睡眠记录最终时长永远等于 `sleepEnd - sleepStart`。
- 手动设置过去开始时间后再开始计时，退出重进仍恢复到同一个开始时间。
- 喂养记录不丢失当前暂停/继续累计能力。
- 活动类事件保存的结束时间和展示时长一致。
- 计时器超过 1 小时显示清晰，不出现 `120:00` 这类不适合长时长场景的展示。
- 新逻辑有单元测试覆盖，且不破坏现有 MVVM、资源和 UI 规范。
