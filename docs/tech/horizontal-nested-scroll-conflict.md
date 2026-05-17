# Android 横向列表与纵向页面滑动冲突技术方案

## 背景

记录事件页面中，事件类型大类使用横向 `RecyclerView`，外层页面使用纵向 `NestedScrollView`。用户在事件类型区域横向滑动时，如果手势里同时包含 `dx` 和 `dy`，外层纵向容器容易提前拦截，导致横向滚动不灵敏。

这是 Android 触摸分发中的常见问题：子 View 需要横向滑动，父 View 需要纵向滑动，两者在同一条手势链上竞争事件所有权。

## 现象

典型体验表现：

- 横向滑动事件类型列表时，需要非常“平直”地滑才容易触发。
- 手指略微斜一点，页面会优先上下滚动。
- 横向列表像是被纵向 `NestedScrollView` 抢走手势。
- 快速短距离横滑比慢速横滑更容易丢失。

## 原实现的问题

原方案在 `ACTION_MOVE` 中直接用下面的条件决定是否让父级拦截：

```kotlin
val disallowParentIntercept = abs(deltaX) > abs(deltaY)
view.parent.requestDisallowInterceptTouchEvent(disallowParentIntercept)
```

这个判断过于敏感，问题在于：

- 真实手势很少是纯水平，早期移动帧经常混有 `dy` 抖动。
- 如果某一帧 `abs(deltaX) <= abs(deltaY)`，代码会立刻允许父级拦截。
- 父级一旦拦截，子 `RecyclerView` 会收到 `ACTION_CANCEL`，后续横向滑动就被中断。
- 每一帧都重新判断方向，没有“方向锁定”，所以手势所有权不稳定。

结论：不能用逐帧 `dx > dy` 作为横纵冲突的最终方案。

## Android 事件分发依据

Android 官方文档对这类问题给出两个关键机制：

- `ViewGroup.onInterceptTouchEvent(MotionEvent)`：父容器可以在事件分发给子 View 前决定是否拦截。
- `ViewParent.requestDisallowInterceptTouchEvent(boolean)`：子 View 可以请求父级及祖先不要通过 `onInterceptTouchEvent()` 拦截当前触摸序列。

官方文档同时建议使用 `ViewConfiguration.scaledTouchSlop` 判断滚动意图。`touchSlop` 是系统认为手势从点击变为滚动前允许的最小移动距离，用于过滤手指抖动。

参考：

- [Manage touch events in a ViewGroup](https://developer.android.com/develop/ui/views/touch-and-input/gestures/viewgroup)
- [Input events overview](https://developer.android.com/develop/ui/views/touch-and-input/input-events)
- [ViewGroup.requestDisallowInterceptTouchEvent](https://developer.android.com/reference/android/view/ViewGroup#requestDisallowInterceptTouchEvent(boolean))

## 方案原则

本项目采用“触摸阈值 + 方向锁定”的方案：

1. `ACTION_DOWN` 后先保护子 `RecyclerView`，请求父级不要拦截。
2. 移动距离未超过 `touchSlop` 前，不判定方向，也不释放父级。
3. 超过阈值后只判定一次主要方向。
4. 横向意图成立后，锁定给子 `RecyclerView`，直到 `ACTION_UP` 或 `ACTION_CANCEL`。
5. 纵向意图成立后，释放给外层 `NestedScrollView`，保证页面仍能自然上下滚动。

这样可以避免早期 dy 抖动导致父级提前抢走手势。

## 当前落地实现

新增通用组件：

```text
components/src/main/java/com/zero/components/touch/HorizontalNestedScrollTouchDelegate.kt
```

记录事件页面接入：

```kotlin
HorizontalNestedScrollTouchDelegate.attachTo(binding.rvCategory)
```

核心实现点如下。

### 1. 使用 RecyclerView.OnItemTouchListener

当前实现不是给 `RecyclerView` 设置普通 `setOnTouchListener`，而是使用：

```kotlin
RecyclerView.SimpleOnItemTouchListener()
```

原因：

- 更贴近 `RecyclerView` 的触摸分发模型。
- 不覆盖业务层可能已有的 `OnTouchListener`。
- 只负责手势仲裁，不消费事件，返回 `false` 让 `RecyclerView` 继续处理点击、滚动、选择等行为。

### 2. ACTION_DOWN 先保护子 View

```kotlin
MotionEvent.ACTION_DOWN -> {
    activePointerId = event.getPointerId(0)
    downX = event.x
    downY = event.y
    directionLock = ScrollDirectionLock.UNDECIDED
    recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
}
```

这里先禁止父级拦截，是为了让子 `RecyclerView` 能拿到后续移动帧，有机会完成方向判断。

### 3. touchSlop 内保持未决，不释放父级

```kotlin
if (absDx < touchSlop && absDy < touchSlop) {
    recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
    return
}
```

手指刚开始移动时的 dx/dy 抖动不代表真实意图。只有超过 `touchSlop` 后，才开始判定滚动方向。

### 4. 横向意图使用容错比例

```kotlin
val horizontalIntent = absDx > touchSlop && absDx >= absDy * HORIZONTAL_INTENT_RATIO
```

当前 `HORIZONTAL_INTENT_RATIO = 0.8f`。

这表示只要横向位移明显超过阈值，并且没有被纵向位移显著压过，就认为用户有横向意图。这个比例让略微斜向的横滑也能被识别，避免要求用户必须完全水平滑动。

### 5. 横向锁定后不再逐帧释放

```kotlin
directionLock = ScrollDirectionLock.HORIZONTAL
recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
```

一旦锁定横向，后续 `ACTION_MOVE` 都继续禁止父级拦截，直到 `ACTION_UP` / `ACTION_CANCEL`。

这是体验改善的关键：手势所有权在一次滑动中保持稳定，不会因为某一帧 dy 偏大而被父级抢走。

### 6. 纵向意图明确时释放父级

```kotlin
if (absDy > touchSlop) {
    directionLock = ScrollDirectionLock.VERTICAL
    recyclerView.parent?.requestDisallowInterceptTouchEvent(false)
}
```

如果用户确实是纵向滑动页面，外层 `NestedScrollView` 仍然可以接管，避免事件类型区域变成页面滚动的“死区”。

### 7. 只在子列表可横向滚动时锁定横向

```kotlin
val canScrollHorizontally = recyclerView.canScrollForDrag(deltaX)
```

如果子列表已经滚到边界，且当前拖动方向无法继续滚动，则不会强行锁死父级，避免边界处体验僵硬。

## 当前相关文件

- `components/src/main/java/com/zero/components/touch/HorizontalNestedScrollTouchDelegate.kt`
  - 通用横向子列表与纵向父容器冲突处理。
- `app/src/main/java/com/zero/babycare/home/record/event/EventRecordFragment.kt`
  - 记录事件页 `rvCategory` 接入该 delegate。
- `app/src/main/res/layout/fragment_event_record.xml`
  - `rvCategory` 设置 `android:nestedScrollingEnabled="false"`。
- `app/src/test/java/com/zero/babycare/ui/UiResourcePolicyTest.kt`
  - 资源策略测试锁定该方案，避免回退为逐帧 `dx > dy`。

## 适用场景

该方案适用于：

- 横向 `RecyclerView` 嵌套在纵向 `NestedScrollView` 中。
- 横向 tab、分类、快捷入口列表嵌在纵向表单或详情页中。
- 希望横向轻微斜滑也能稳定触发，同时保留页面纵向滚动。

暂不适用于：

- 内外层都需要复杂 nested fling 联动的场景。
- 横向列表嵌在横向分页容器中。
- 自定义 ViewGroup 已经重写完整 `onInterceptTouchEvent()` 仲裁逻辑的页面。

这些场景需要基于父容器实现单独的方向仲裁。

## 回归验证

定向测试：

```bash
./gradlew :app:testDebugUnitTest --tests "com.zero.babycare.ui.UiResourcePolicyTest.event category horizontal list keeps parent from stealing horizontal swipes"
```

完整验证：

```bash
./gradlew test
./gradlew assembleDebug
```

真机复测建议：

- 在事件类型区域慢速横滑。
- 在事件类型区域快速短横滑。
- 略微斜向横滑，确认列表仍能横向滚动。
- 明确纵向滑动，确认页面仍能上下滚动。
- 横向列表滚到左右边界后继续拖动，确认页面不会卡死。
- 点击事件类型 item，确认点击选择不受影响。

## 后续扩展建议

如果其他页面也出现横向列表与纵向页面冲突，应优先复用：

```kotlin
HorizontalNestedScrollTouchDelegate.attachTo(recyclerView)
```

不要在业务页面重复写 `setOnTouchListener`。如果后续要支持更多容器类型，应扩展 `components` 中的通用 delegate，而不是在 `app` 模块分叉实现。
