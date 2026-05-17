# Android 输入框键盘遮挡问题技术方案

## 背景

记录事件页面中，底部 `EditText` 聚焦后软键盘弹出，输入框仍可能被键盘遮挡。最初尝试通过 `android:windowSoftInputMode="adjustResize"` 处理，但真机日志显示：窗口已经被压缩，输入框仍无法稳定滚动到键盘上方。

该问题不是单一页面问题，而是本项目表单页面的通用问题。项目使用了 edge-to-edge、固定底部按钮、动态表单、`NestedScrollView`、多个 Fragment 页面共存等结构，单靠系统默认的 resize 与焦点滚动不足以覆盖所有场景。

## 现象与日志证据

典型日志如下：

```text
root=Rect(0, 117 - 1080, 2310) imeVisible=false imeBottom=0
root=Rect(0, 117 - 1080, 1485) imeVisible=true imeBottom=825
deltaY=595 scrollY=770
```

这些信息说明：

- `adjustResize` 已生效：键盘弹出后，根视图底部从 `2310` 变为 `1485`。
- delegate 已算出仍需滚动：`deltaY=595`。
- 但 `NestedScrollView` 的 `scrollY` 长时间停在 `770`，说明滚动容器没有足够的底部可滚空间，或者滚动请求没有继续推进到底部输入框可见的位置。

另一类关键日志：

```text
input=null scrollRect=null deltaY=0
root=Rect(0, 0 - 0, 0)
```

这些信息说明：

- `input=null`：输入框被完全裁剪后，使用 `getGlobalVisibleRect()` 无法再拿到输入框位置。
- `root=Rect(0, 0 - 0, 0)`：隐藏 Fragment 的监听也参与了计算，产生无效滚动判断。

## 为什么只加 adjustResize 不够

传统简单页面只加 `adjustResize` 通常能解决，是因为它一般满足这些条件：

- 非 edge-to-edge 或 insets 处理简单。
- 页面只有一个清晰的滚动容器。
- 没有固定底部按钮压缩滚动区域。
- 内容底部有足够留白。
- 系统默认的 `requestRectangleOnScreen` 能可靠滚到焦点输入框。

本项目不满足这些条件：

- `MainActivity` 使用 edge-to-edge，需要自己处理 `statusBars`、`navigationBars`、`ime` insets。
- 记录页面底部有固定保存按钮，`NestedScrollView` 约束到按钮上方。
- 事件详情与备注区是动态显示的，输入框可能靠近内容末尾。
- 输入框完全被键盘裁剪后，`getGlobalVisibleRect()` 会返回 false。
- 多个 Fragment 可能同时存在于 View 层级里，隐藏页面的全局焦点监听会产生干扰。

因此，正确策略是：`adjustResize` 作为基础能力保留，同时由公共组件统一处理“焦点输入框滚动到键盘上方”。

## 当前落地方案

### 1. Manifest 保留 adjustResize

`MainActivity` 需要保留：

```xml
android:windowSoftInputMode="adjustResize"
```

它负责让系统在 IME 显示时压缩窗口可用高度，是后续滚动计算的基础。

### 2. 根布局统一处理 IME inset

`StatusBarUtil` 在 edge-to-edge 下对根布局设置系统栏与键盘 inset：

- 顶部使用 `statusBars`。
- 底部使用 `max(ime, navigationBars)`。
- 保留 `SOFT_INPUT_ADJUST_RESIZE`，兼容旧系统。

这样避免键盘与导航栏在不同系统版本下出现不一致的可用区域。

### 3. BaseFragment 安装公共滚动 delegate

所有继承 `BaseFragment` 的页面会安装 `KeyboardSafeScrollDelegate`：

```kotlin
keyboardSafeScrollDelegate = KeyboardSafeScrollDelegate.install(view)
```

在 `onDestroyView()` 中释放：

```kotlin
keyboardSafeScrollDelegate?.dispose()
keyboardSafeScrollDelegate = null
```

这样每个 Fragment 的输入框滚动行为由同一套逻辑兜底，避免每个页面重复处理。

### 4. 使用未裁剪坐标计算输入框位置

不能依赖：

```kotlin
editText.getGlobalVisibleRect(inputRect)
```

原因是输入框被键盘完全遮挡或被父容器裁剪时，该方法会返回 false。

当前使用：

```kotlin
val inputRect = Rect(0, 0, editText.width, editText.height)
(scrollParent as ViewGroup).offsetDescendantRectToMyCoords(editText, inputRect)
```

这样可以拿到输入框在滚动内容坐标系里的真实位置，即使它当前不可见，也能计算出应该滚动的距离。

### 5. 避免隐藏 Fragment 干扰

滚动前会检查：

- 当前焦点输入框是否属于该 Fragment 的 root。
- root 是否可见且有有效 global rect。
- `EditText` 是否 attached 且 shown。

核心判断：

```kotlin
root.containsDescendant(editText)
root.hasUsableGlobalRect()
```

这样可以过滤掉隐藏 Fragment 的全局焦点事件，避免出现 `root=Rect(0, 0 - 0, 0)` 的错误计算。

### 6. 记录稳定窗口底部，避免重复扣减键盘高度

在 `adjustResize` 生效后，`rootRect.bottom` 可能已经是键盘上沿。如果再执行 `rootRect.bottom - imeBottom`，就会重复扣减键盘高度，导致安全区域计算过小。

当前方案会在键盘未显示时记录：

```kotlin
lastStableWindowBottom
```

键盘显示时结合以下信息取最保守的可见底部：

- `getWindowVisibleDisplayFrame()`
- `WindowInsetsCompat.Type.ime()`
- `WindowInsetsCompat.Type.navigationBars()`
- `rootRect.bottom`
- `lastStableWindowBottom`

这样兼容 Android 10 等 IME inset 不稳定的机型。

### 7. 键盘态临时补足 ScrollView 底部可滚空间

底部输入框靠近内容末尾时，即使算出 `deltaY`，ScrollView 也可能已经滚到底，无法继续把输入框顶到键盘上方。

当前方案会在键盘显示时对最近的 `ScrollView` / `NestedScrollView` 临时设置 bottom padding：

```kotlin
targetPaddingBottom = originalPaddingBottom + imeBottom + revealGapPx
```

并设置：

```kotlin
clipToPadding = false
```

键盘隐藏或 Fragment 销毁时恢复原始 padding 与 `clipToPadding`。这样底部输入框也有足够空间滚到键盘上方。

### 8. 使用确定性 scrollTo

当前使用：

```kotlin
scrollParent.scrollTo(0, scrollParent.scrollY + deltaY)
```

相比只依赖 `smoothScrollBy()`，`scrollTo()` 更适合这里的兜底逻辑：只要计算出需要滚动的距离，就立即把焦点输入框推到安全区域内。

## 当前相关文件

- `app/src/main/AndroidManifest.xml`
  - `MainActivity` 配置 `adjustResize`。
- `common/src/main/java/com/zero/common/util/StatusBarUtil.kt`
  - edge-to-edge 下统一处理系统栏与 IME inset。
- `components/src/main/java/com/zero/components/base/BaseFragment.kt`
  - 安装与释放 `KeyboardSafeScrollDelegate`。
- `components/src/main/java/com/zero/components/base/KeyboardSafeScrollDelegate.kt`
  - 聚焦输入框滚动、日志、临时 padding、隐藏 Fragment 过滤等核心逻辑。
- `app/src/test/java/com/zero/babycare/ui/UiResourcePolicyTest.kt`
  - 通过资源策略测试锁定该方案，避免后续回退为单纯 `adjustResize`。

## 排查日志

需要设备侧开启日志：

```bash
adb shell setprop log.tag.BabyCareKeyboard DEBUG
adb logcat -c
adb logcat -v time -s BabyCareKeyboard:D
```

复现输入框被遮挡时重点看：

- `root` / `visibleFrame`
  - 判断 `adjustResize` 是否生效。
- `imeVisible` / `imeBottom`
  - 判断系统是否报告键盘显示和高度。
- `input`
  - 判断输入框坐标是否可计算。
- `deltaY`
  - 判断是否算出需要滚动的距离。
- `scrollY`
  - 判断滚动请求是否推进。
- `canScrollDown`
  - 判断是否已滚到底。
- `paddingBottom`
  - 判断键盘态临时底部 padding 是否生效。
- `stableBottom`
  - 判断完整窗口底部是否被记录。

## 回归验证

相关改动至少运行：

```bash
./gradlew test
./gradlew assembleDebug
```

针对键盘方案的定向测试：

```bash
./gradlew :app:testDebugUnitTest --tests "com.zero.babycare.ui.UiResourcePolicyTest.base fragments scroll focused edit texts above the ime"
```

真机复测建议覆盖：

- 记录事件页底部备注输入框。
- 记录事件页体温、用药、成长等带单位输入框。
- 其他带底部输入框的记录页。
- 键盘打开后再次点击同一个输入框。
- 页面已滚动后再点击输入框。
- 浅色 / 深色模式。
- 男孩 / 女孩主题。

## 适用原则

后续新增页面如果需要处理输入框键盘遮挡，应优先继承并复用 `BaseFragment` 的公共能力，不要在业务 Fragment 中重复写键盘滚动逻辑。

只有在以下场景才考虑页面级特殊处理：

- 页面不是 `ScrollView` / `NestedScrollView` 容器。
- 页面使用 BottomSheet、Dialog 或自定义窗口。
- 页面有多个嵌套滚动容器，最近滚动父容器不是实际应滚动的容器。

这种情况下应优先扩展 `KeyboardSafeScrollDelegate` 的通用能力，而不是在业务页面分叉实现。
