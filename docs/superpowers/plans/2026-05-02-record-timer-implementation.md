# Record Timer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统一 BabyCare 记录计时器的开始时间、结束时间、展示时长、保存时长和进行中恢复逻辑。

**Architecture:** 保持 `RecordTimerPanelView` 为纯 UI 组合控件，在 `app/home/record` 增加纯 Kotlin 计时策略模型，由 `RecordTimerController` 统一应用业务模式。睡眠和活动使用时间范围时长，喂养保留当前有效计时累计能力。

**Tech Stack:** Android ViewBinding、Kotlin、JUnit4、现有 `DateUtils`、现有 `OngoingRecordManager`、现有 `RecordView/TimerCounter`。

---

## File Structure

- Create: `app/src/main/java/com/zero/babycare/home/record/TimerSessionPolicy.kt`
  - 纯 Kotlin 计时策略，定义 `TimerMode`、`TimerStatus`、`DurationSource`、`TimerSessionState`，并计算展示/保存时长。
- Create: `app/src/test/java/com/zero/babycare/home/record/TimerSessionPolicyTest.kt`
  - 覆盖睡眠不扣暂停、喂养保留有效计时、活动使用时间范围、无效范围返回 0。
- Create: `common/src/main/java/com/zero/common/util/DurationFormatUtils.kt`
  - 纯 Kotlin 时钟格式化，超过 1 小时显示 `H:mm:ss`。
- Create: `common/src/test/java/com/zero/common/util/DurationFormatUtilsTest.kt`
  - 覆盖 `00:00`、`59:59`、`1:00:00`、`2:05:09`。
- Modify: `components/src/main/java/com/zero/components/widget/TimerCounter.kt`
  - 将 `formatToMinSec` 和 `formatToHourMinSec` 委托给 `DurationFormatUtils`。
- Modify: `app/src/main/java/com/zero/babycare/home/record/RecordTimerController.kt`
  - 添加业务模式配置，开始计时时回传最终 `startAt`，睡眠/活动继续计时时从 `startAt` 恢复跨度。
- Modify: `app/src/main/java/com/zero/babycare/home/OngoingRecordManager.kt`
  - `startSleep/startFeeding` 支持传入已确定的开始时间。
- Modify: `app/src/main/java/com/zero/babycare/home/record/FeedingRecordFragment.kt`
  - 使用 `TimerMode.FEEDING`，开始计时持久化真实 `startAt`，保存时通过策略计算喂养时长。
- Modify: `app/src/main/java/com/zero/babycare/home/record/SleepRecordFragment.kt`
  - 使用 `TimerMode.SLEEP`，开始计时持久化真实 `startAt`，保存时长固定为 `sleepEnd - sleepStart`。
- Modify: `app/src/main/java/com/zero/babycare/home/record/event/EventRecordFragment.kt`
  - 活动类事件使用 `TimerMode.ACTIVITY`，保存前若仍在计时则自动暂停并写入结束时间。

---

### Task 1: Add Duration Formatting Utility

**Files:**
- Create: `common/src/main/java/com/zero/common/util/DurationFormatUtils.kt`
- Create: `common/src/test/java/com/zero/common/util/DurationFormatUtilsTest.kt`
- Modify: `components/src/main/java/com/zero/components/widget/TimerCounter.kt`

- [ ] **Step 1: Write the failing tests**

Create `common/src/test/java/com/zero/common/util/DurationFormatUtilsTest.kt`:

```kotlin
package com.zero.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatUtilsTest {

    @Test
    fun `formatTimerClock shows minutes and seconds below one hour`() {
        assertEquals("00:00", DurationFormatUtils.formatTimerClock(0L))
        assertEquals("00:05", DurationFormatUtils.formatTimerClock(5_000L))
        assertEquals("59:59", DurationFormatUtils.formatTimerClock(3_599_000L))
    }

    @Test
    fun `formatTimerClock shows hours when duration reaches one hour`() {
        assertEquals("1:00:00", DurationFormatUtils.formatTimerClock(3_600_000L))
        assertEquals("2:05:09", DurationFormatUtils.formatTimerClock(7_509_000L))
    }

    @Test
    fun `formatTimerClock clamps negative values to zero`() {
        assertEquals("00:00", DurationFormatUtils.formatTimerClock(-1L))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :common:testDebugUnitTest --tests "com.zero.common.util.DurationFormatUtilsTest"
```

Expected: FAIL because `DurationFormatUtils` does not exist.

- [ ] **Step 3: Add the utility**

Create `common/src/main/java/com/zero/common/util/DurationFormatUtils.kt`:

```kotlin
package com.zero.common.util

import java.util.Locale

/**
 * 计时器时钟格式化工具。
 *
 * 这里保留数字时钟表达，避免把长睡眠展示成 120:00 这类难扫读的分钟累计格式。
 */
object DurationFormatUtils {

    fun formatTimerClock(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }
}
```

- [ ] **Step 4: Wire `TimerCounter` to the utility**

In `components/src/main/java/com/zero/components/widget/TimerCounter.kt`, add the import:

```kotlin
import com.zero.common.util.DurationFormatUtils
```

Replace the two formatting methods with:

```kotlin
@SuppressLint("DefaultLocale")
fun formatToMinSec(ms: Long): String {
    return DurationFormatUtils.formatTimerClock(ms)
}

@SuppressLint("DefaultLocale")
fun formatToHourMinSec(ms: Long): String {
    return DurationFormatUtils.formatTimerClock(ms)
}
```

- [ ] **Step 5: Run tests**

Run:

```powershell
.\gradlew.bat :common:testDebugUnitTest --tests "com.zero.common.util.DurationFormatUtilsTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add -- common/src/main/java/com/zero/common/util/DurationFormatUtils.kt common/src/test/java/com/zero/common/util/DurationFormatUtilsTest.kt components/src/main/java/com/zero/components/widget/TimerCounter.kt
git commit -m "统一计时器长时长显示"
```

---

### Task 2: Add Timer Session Policy

**Files:**
- Create: `app/src/main/java/com/zero/babycare/home/record/TimerSessionPolicy.kt`
- Create: `app/src/test/java/com/zero/babycare/home/record/TimerSessionPolicyTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/zero/babycare/home/record/TimerSessionPolicyTest.kt`:

```kotlin
package com.zero.babycare.home.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerSessionPolicyTest {

    @Test
    fun `sleep saved duration always uses time range`() {
        val duration = TimerSessionPolicy.calculateSavedDuration(
            mode = TimerMode.SLEEP,
            startAt = 1_000L,
            endAt = 121_000L,
            activeDuration = 60_000L
        )

        assertEquals(120_000L, duration.valueMs)
        assertEquals(DurationSource.TIME_RANGE, duration.source)
    }

    @Test
    fun `activity saved duration uses time range`() {
        val duration = TimerSessionPolicy.calculateSavedDuration(
            mode = TimerMode.ACTIVITY,
            startAt = 1_000L,
            endAt = 301_000L,
            activeDuration = 120_000L
        )

        assertEquals(300_000L, duration.valueMs)
        assertEquals(DurationSource.TIME_RANGE, duration.source)
    }

    @Test
    fun `feeding saved duration keeps valid active timer duration`() {
        val duration = TimerSessionPolicy.calculateSavedDuration(
            mode = TimerMode.FEEDING,
            startAt = 1_000L,
            endAt = 301_000L,
            activeDuration = 180_000L
        )

        assertEquals(180_000L, duration.valueMs)
        assertEquals(DurationSource.ACTIVE_TIMER, duration.source)
    }

    @Test
    fun `feeding saved duration falls back to time range when active duration is invalid`() {
        val duration = TimerSessionPolicy.calculateSavedDuration(
            mode = TimerMode.FEEDING,
            startAt = 1_000L,
            endAt = 301_000L,
            activeDuration = 400_000L
        )

        assertEquals(300_000L, duration.valueMs)
        assertEquals(DurationSource.TIME_RANGE, duration.source)
    }

    @Test
    fun `invalid time range returns zero duration`() {
        val duration = TimerSessionPolicy.calculateSavedDuration(
            mode = TimerMode.SLEEP,
            startAt = 301_000L,
            endAt = 1_000L,
            activeDuration = 10_000L
        )

        assertEquals(0L, duration.valueMs)
        assertEquals(DurationSource.NONE, duration.source)
    }

    @Test
    fun `range based modes resume from start timestamp`() {
        assertTrue(TimerSessionPolicy.shouldResumeFromStart(TimerMode.SLEEP))
        assertTrue(TimerSessionPolicy.shouldResumeFromStart(TimerMode.ACTIVITY))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.zero.babycare.home.record.TimerSessionPolicyTest"
```

Expected: FAIL because `TimerSessionPolicy` and related types do not exist.

- [ ] **Step 3: Add the policy model**

Create `app/src/main/java/com/zero/babycare/home/record/TimerSessionPolicy.kt`:

```kotlin
package com.zero.babycare.home.record

/**
 * 记录计时器的业务模式。
 *
 * 不同记录类型对“时长”的含义不同：睡眠和活动看开始到结束的跨度，
 * 喂养保留可暂停累计的有效计时时长。
 */
enum class TimerMode {
    FEEDING,
    SLEEP,
    ACTIVITY
}

enum class TimerStatus {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED
}

enum class DurationSource {
    NONE,
    TIME_RANGE,
    ACTIVE_TIMER
}

data class TimerSessionState(
    val startAt: Long = 0L,
    val endAt: Long? = null,
    val status: TimerStatus = TimerStatus.IDLE,
    val activeDuration: Long = 0L,
    val displayDuration: Long = 0L,
    val durationSource: DurationSource = DurationSource.NONE
)

data class TimerDuration(
    val valueMs: Long,
    val source: DurationSource
)

object TimerSessionPolicy {

    fun calculateTimeRangeDuration(startAt: Long, endAt: Long?): TimerDuration {
        val end = endAt ?: return TimerDuration(0L, DurationSource.NONE)
        if (startAt <= 0L || end <= startAt) {
            return TimerDuration(0L, DurationSource.NONE)
        }
        return TimerDuration(end - startAt, DurationSource.TIME_RANGE)
    }

    fun calculateDisplayDuration(
        mode: TimerMode,
        state: TimerSessionState,
        now: Long = System.currentTimeMillis()
    ): TimerDuration {
        val effectiveEnd = state.endAt ?: now
        val range = calculateTimeRangeDuration(state.startAt, effectiveEnd)
        if (mode != TimerMode.FEEDING) {
            return range
        }

        return when {
            state.activeDuration > 0L && range.valueMs > 0L && state.activeDuration <= range.valueMs ->
                TimerDuration(state.activeDuration, DurationSource.ACTIVE_TIMER)
            range.valueMs > 0L -> range
            else -> TimerDuration(0L, DurationSource.NONE)
        }
    }

    fun calculateSavedDuration(
        mode: TimerMode,
        startAt: Long,
        endAt: Long,
        activeDuration: Long
    ): TimerDuration {
        val range = calculateTimeRangeDuration(startAt, endAt)
        if (range.valueMs <= 0L) {
            return TimerDuration(0L, DurationSource.NONE)
        }

        if (mode == TimerMode.FEEDING && activeDuration > 0L && activeDuration <= range.valueMs) {
            return TimerDuration(activeDuration, DurationSource.ACTIVE_TIMER)
        }

        return range
    }

    fun shouldResumeFromStart(mode: TimerMode): Boolean {
        return mode == TimerMode.SLEEP || mode == TimerMode.ACTIVITY
    }
}
```

- [ ] **Step 4: Run tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.zero.babycare.home.record.TimerSessionPolicyTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add -- app/src/main/java/com/zero/babycare/home/record/TimerSessionPolicy.kt app/src/test/java/com/zero/babycare/home/record/TimerSessionPolicyTest.kt
git commit -m "新增记录计时策略模型"
```

---

### Task 3: Wire Timer Controller Modes

**Files:**
- Modify: `app/src/main/java/com/zero/babycare/home/record/RecordTimerController.kt`

- [ ] **Step 1: Update controller config and callbacks**

In `RecordTimerController.kt`, replace the existing `Config` and `Callbacks` definitions with:

```kotlin
data class Config(
    val invalidEndTimeMessageRes: Int,
    val mode: TimerMode = TimerMode.FEEDING,
    val shouldIgnoreInput: () -> Boolean = { false }
)

data class Callbacks(
    val onStartTimeChanged: (Long) -> Unit = {},
    val onEndTimeChanged: (Long?) -> Unit = {},
    val onTimerStart: (Long) -> Unit = {},
    val onTimerResume: () -> Unit = {},
    val onTimerPause: (Long) -> Unit = {},
    val onDirty: () -> Unit = {}
)
```

- [ ] **Step 2: Add a helper for continuing after pause**

Add this private method inside `RecordTimerController`:

```kotlin
private fun continueTimerAfterPause() {
    handleTimerResume()
    if (TimerSessionPolicy.shouldResumeFromStart(config.mode) && startInput.hasValidTime()) {
        val offset = (System.currentTimeMillis() - startInput.getTimestamp()).coerceAtLeast(0L)
        timerView.startFromOffset(offset)
    } else {
        timerView.resumeFromPause()
    }
}
```

- [ ] **Step 3: Use the helper in status changes**

In `setupTimerCounter()`, replace the `RecordState.PAUSE -> RecordState.RECORDING` handling with:

```kotlin
current == RecordState.PAUSE && next == RecordState.RECORDING -> {
    if (isEndTimeManuallyModified()) {
        showResumeTimerConfirmDialog()
    } else {
        continueTimerAfterPause()
    }
}
```

- [ ] **Step 4: Make start callback receive the final start time**

Replace `handleTimerStart()` with:

```kotlin
private fun handleTimerStart() {
    if (startInput.hasValidTime()) {
        val startTimestamp = startInput.getTimestamp()
        val offset = (System.currentTimeMillis() - startTimestamp).coerceAtLeast(0L)
        if (offset > 0L) {
            timerView.setPauseOffset(offset)
        }
    }

    clearEndTime(notify = true)
    callbacks.onDirty()

    timerView.post {
        val timerStartTime = timerView.getStartTimestamp()
        if (timerStartTime > 0L) {
            setStartTime(timerStartTime, notify = true)
            callbacks.onTimerStart(timerStartTime)
        }
    }
}
```

- [ ] **Step 5: Keep confirmation resume on the same path**

In `showResumeTimerConfirmDialog()`, replace the `onConfirm` block with:

```kotlin
onConfirm = {
    continueTimerAfterPause()
}
```

- [ ] **Step 6: Compile app to catch signature changes**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: FAIL because Fragment callbacks still use zero-argument `onTimerStart`. The next task fixes call sites.

---

### Task 4: Persist Real Ongoing Start Time

**Files:**
- Modify: `app/src/main/java/com/zero/babycare/home/OngoingRecordManager.kt`
- Modify: `app/src/main/java/com/zero/babycare/home/record/FeedingRecordFragment.kt`
- Modify: `app/src/main/java/com/zero/babycare/home/record/SleepRecordFragment.kt`
- Modify: `app/src/main/java/com/zero/babycare/home/record/event/EventRecordFragment.kt`

- [ ] **Step 1: Update ongoing manager signatures**

In `OngoingRecordManager.kt`, replace `startSleep` with:

```kotlin
fun startSleep(babyId: Int, startTime: Long = System.currentTimeMillis()) {
    if (startTime <= 0L) return
    MMKVStore.put(sleepStartKey(babyId), startTime)
    MMKVStore.remove(KEY_ONGOING_SLEEP_START)
    MMKVStore.remove(KEY_ONGOING_SLEEP_BABY_ID)
    trackBabyId(babyId)
}
```

Replace `startFeeding` with:

```kotlin
fun startFeeding(
    babyId: Int,
    feedingType: Int = 0,
    startTime: Long = System.currentTimeMillis()
) {
    if (startTime <= 0L) return
    MMKVStore.put(feedingStartKey(babyId), startTime)
    MMKVStore.put(feedingTypeKey(babyId), feedingType)
    MMKVStore.remove(KEY_ONGOING_FEEDING_START)
    MMKVStore.remove(KEY_ONGOING_FEEDING_BABY_ID)
    MMKVStore.remove(KEY_ONGOING_FEEDING_TYPE)
    trackBabyId(babyId)
}
```

- [ ] **Step 2: Configure feeding timer mode and callback**

In `FeedingRecordFragment.setupTimerController()`, update the config:

```kotlin
config = RecordTimerController.Config(
    invalidEndTimeMessageRes = R.string.end_time_must_after_start,
    mode = TimerMode.FEEDING,
    shouldIgnoreInput = { isProgrammaticChange }
),
```

Replace the `onTimerStart` callback with:

```kotlin
onTimerStart = { startTime ->
    mainVm.getCurrentBabyInfo()?.babyId?.let { babyId ->
        OngoingRecordManager.startFeeding(babyId, selectedFeedingType.type, startTime)
    }
},
```

- [ ] **Step 3: Configure sleep timer mode and callback**

In `SleepRecordFragment.setupTimerController()`, update the config:

```kotlin
config = RecordTimerController.Config(
    invalidEndTimeMessageRes = R.string.sleep_end_must_after_start,
    mode = TimerMode.SLEEP,
    shouldIgnoreInput = { isProgrammaticChange }
),
```

Replace the `onTimerStart` callback with:

```kotlin
onTimerStart = { startTime ->
    mainVm.getCurrentBabyInfo()?.babyId?.let { babyId ->
        OngoingRecordManager.startSleep(babyId, startTime)
    }
},
```

- [ ] **Step 4: Configure activity timer mode**

In `EventRecordFragment.inflateActivityDetail()`, update the config:

```kotlin
config = RecordTimerController.Config(
    invalidEndTimeMessageRes = R.string.event_end_time_invalid,
    mode = TimerMode.ACTIVITY
),
```

- [ ] **Step 5: Compile app**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add -- app/src/main/java/com/zero/babycare/home/OngoingRecordManager.kt app/src/main/java/com/zero/babycare/home/record/RecordTimerController.kt app/src/main/java/com/zero/babycare/home/record/FeedingRecordFragment.kt app/src/main/java/com/zero/babycare/home/record/SleepRecordFragment.kt app/src/main/java/com/zero/babycare/home/record/event/EventRecordFragment.kt
git commit -m "修正进行中计时开始时间"
```

---

### Task 5: Apply Saved Duration Rules

**Files:**
- Modify: `app/src/main/java/com/zero/babycare/home/record/FeedingRecordFragment.kt`
- Modify: `app/src/main/java/com/zero/babycare/home/record/SleepRecordFragment.kt`
- Modify: `app/src/main/java/com/zero/babycare/home/record/event/EventRecordFragment.kt`

- [ ] **Step 1: Update feeding saved duration calculation**

In `FeedingRecordFragment.saveRecord()`, replace:

```kotlin
var duration = binding.timerPanel.timerView.getDuration()
val timeRangeDuration = DateUtils.calculateDuration(startTime, endTime)

if (duration <= 0 || duration > timeRangeDuration) {
    duration = timeRangeDuration
}
```

with:

```kotlin
val duration = TimerSessionPolicy.calculateSavedDuration(
    mode = TimerMode.FEEDING,
    startAt = startTime,
    endAt = endTime,
    activeDuration = binding.timerPanel.timerView.getDuration()
).valueMs
```

- [ ] **Step 2: Update sleep saved duration calculation**

In `SleepRecordFragment.saveSleepRecord()`, replace:

```kotlin
var duration = binding.timerPanel.timerView.getDuration()
val timeRangeDuration = DateUtils.calculateDuration(startTime, endTime)
if (duration <= 0 || duration > timeRangeDuration) {
    duration = timeRangeDuration
}
```

with:

```kotlin
val duration = TimerSessionPolicy.calculateSavedDuration(
    mode = TimerMode.SLEEP,
    startAt = startTime,
    endAt = endTime,
    activeDuration = binding.timerPanel.timerView.getDuration()
).valueMs
```

- [ ] **Step 3: Auto-pause running activity timers before save**

In `EventRecordFragment.kt`, add this import:

```kotlin
import com.zero.components.widget.RecordView.RecordState
```

Add this private method near `saveRecord()`:

```kotlin
private fun syncRunningActivityTimerBeforeSave() {
    val subtype = vm.selectedSubtype.value ?: return
    if (!EventType.hasDuration(subtype.type)) return

    val root = binding.containerDetail
    if (root.childCount == 0) return

    val detailBinding = LayoutEventDetailActivityBinding.bind(root.getChildAt(0))
    if (detailBinding.timerPanel.timerView.currentShowState == RecordState.RECORDING) {
        detailBinding.timerPanel.timerView.forcePause(triggerCallback = true)
    }
}
```

In `saveRecord()`, place this call before `syncDetailInputs()`:

```kotlin
syncRunningActivityTimerBeforeSave()
```

- [ ] **Step 4: Run focused tests and compile**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.zero.babycare.home.record.TimerSessionPolicyTest" :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add -- app/src/main/java/com/zero/babycare/home/record/FeedingRecordFragment.kt app/src/main/java/com/zero/babycare/home/record/SleepRecordFragment.kt app/src/main/java/com/zero/babycare/home/record/event/EventRecordFragment.kt
git commit -m "统一记录保存时长规则"
```

---

### Task 6: Add Regression Coverage For Controller-Safe Rules

**Files:**
- Modify: `app/src/test/java/com/zero/babycare/home/record/TimerSessionPolicyTest.kt`

- [ ] **Step 1: Add tests for display duration**

Append these tests to `TimerSessionPolicyTest`:

```kotlin
@Test
fun `sleep display duration while running uses now minus start`() {
    val duration = TimerSessionPolicy.calculateDisplayDuration(
        mode = TimerMode.SLEEP,
        state = TimerSessionState(
            startAt = 10_000L,
            endAt = null,
            status = TimerStatus.RUNNING,
            activeDuration = 30_000L
        ),
        now = 130_000L
    )

    assertEquals(120_000L, duration.valueMs)
    assertEquals(DurationSource.TIME_RANGE, duration.source)
}

@Test
fun `feeding display duration uses active duration when valid`() {
    val duration = TimerSessionPolicy.calculateDisplayDuration(
        mode = TimerMode.FEEDING,
        state = TimerSessionState(
            startAt = 10_000L,
            endAt = 130_000L,
            status = TimerStatus.PAUSED,
            activeDuration = 70_000L
        ),
        now = 200_000L
    )

    assertEquals(70_000L, duration.valueMs)
    assertEquals(DurationSource.ACTIVE_TIMER, duration.source)
}

@Test
fun `feeding display duration falls back to range when active duration is missing`() {
    val duration = TimerSessionPolicy.calculateDisplayDuration(
        mode = TimerMode.FEEDING,
        state = TimerSessionState(
            startAt = 10_000L,
            endAt = 130_000L,
            status = TimerStatus.PAUSED,
            activeDuration = 0L
        ),
        now = 200_000L
    )

    assertEquals(120_000L, duration.valueMs)
    assertEquals(DurationSource.TIME_RANGE, duration.source)
}
```

- [ ] **Step 2: Run policy tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.zero.babycare.home.record.TimerSessionPolicyTest"
```

Expected: PASS.

- [ ] **Step 3: Commit**

```powershell
git add -- app/src/test/java/com/zero/babycare/home/record/TimerSessionPolicyTest.kt
git commit -m "补充计时策略回归测试"
```

---

### Task 7: Full Verification

**Files:**
- Verify only, no source edits.

- [ ] **Step 1: Run unit tests for touched modules**

Run:

```powershell
.\gradlew.bat :common:testDebugUnitTest :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Build debug APK**

Run:

```powershell
.\gradlew.bat assembleDebug
```

Expected: PASS.

- [ ] **Step 3: Manual QA on device or emulator**

Run:

```powershell
.\gradlew.bat installDebug
```

Manual scenarios:

1. 喂养页：手动输入一个过去开始时间，点击开始，返回首页再进入，开始时间仍是手动输入的时间。
2. 喂养页：开始、暂停、继续、暂停、保存，喂养时长保留有效计时累计。
3. 睡眠页：开始、暂停、等待、继续、暂停、保存，数据库记录的 `sleepDuration` 等于 `sleepEnd - sleepStart`。
4. 活动事件：选择户外活动，点击开始后直接保存，页面应自动写入结束时间并允许保存。
5. 长睡眠：超过 1 小时的计时显示为 `1:00:00` 格式。

- [ ] **Step 4: Check git status**

Run:

```powershell
git status --short --branch
```

Expected: only pre-existing `.codex/skills/ui-ux-pro-max/SKILL.md` local deletion may remain; timer implementation files should be committed.

---

## Self-Review

- Spec coverage:
  - 睡眠不扣暂停：Task 2、Task 5、Task 6。
  - 进行中开始时间不失真：Task 3、Task 4。
  - 活动事件使用时间范围：Task 2、Task 4、Task 5。
  - 长时长显示：Task 1。
  - 测试覆盖：Task 1、Task 2、Task 6、Task 7。
- Scope check:
  - 母乳左右侧独立计时、通知栏、锁屏控制不在本计划内。
- Type consistency:
  - `TimerMode`、`TimerSessionPolicy`、`TimerDuration`、`DurationSource` 在 Task 2 定义，后续任务使用相同命名。
