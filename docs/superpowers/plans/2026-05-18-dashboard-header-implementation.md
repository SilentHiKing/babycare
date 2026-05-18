# Dashboard Header Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Dashboard toolbar with a baby identity header, move Dashboard section titles into their cards without right-side helper text, and restore left-edge swipe access to the drawer.

**Architecture:** Keep shared toolbar capability in `components`, common icon/background resources in `common`, Dashboard composition in `app`, and drawer gesture ownership in `MainActivity`. The implementation is UI-only: no Room schema, prediction algorithm, repository, or localization source changes.

**Tech Stack:** Android XML, Kotlin, ViewBinding, `BaseToolbar`, `DrawerLayout`, JUnit resource policy tests, Gradle.

---

## File Structure

- Create `common/src/main/res/drawable/ic_chevron_down.xml`: shared line icon for the baby identity dropdown affordance.
- Create `common/src/main/res/drawable/bg_toolbar_identity_avatar.xml`: shared circular soft avatar surface using theme tokens.
- Modify `components/src/main/res/layout/layout_base_tool_bar.xml`: add a hidden identity-title layout alongside the existing centered title.
- Modify `components/src/main/java/com/zero/components/widget/BaseToolbar.kt`: add `showIdentityTitle(...)`, reset identity mode when standard title modes are used, and keep comments in 简体中文.
- Modify `app/src/main/java/com/zero/babycare/home/DashboardFragment.kt`: configure the toolbar as baby identity, remove the menu button, and keep click behavior in the Fragment/ViewModel boundary.
- Modify `app/src/main/res/layout/fragment_dashboard.xml`: move `next_prediction` and `quick_record` into their cards as left-only 14sp medium titles.
- Modify `app/src/main/java/com/zero/babycare/MainActivity.kt`: unlock the drawer and add Dashboard-only left-edge swipe detection.
- Create `app/src/test/java/com/zero/babycare/ui/DashboardHeaderPolicyTest.kt`: resource-level regression tests without touching the currently dirty `UiResourcePolicyTest.kt`.

## Task 1: Add Shared Visual Resources And Policy Tests

**Files:**
- Create: `common/src/main/res/drawable/ic_chevron_down.xml`
- Create: `common/src/main/res/drawable/bg_toolbar_identity_avatar.xml`
- Create: `app/src/test/java/com/zero/babycare/ui/DashboardHeaderPolicyTest.kt`

- [ ] **Step 1: Write the failing resource policy test**

Create `app/src/test/java/com/zero/babycare/ui/DashboardHeaderPolicyTest.kt`:

```kotlin
package com.zero.babycare.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashboardHeaderPolicyTest {

    @Test
    fun `dashboard keeps section titles inside cards without right helper text`() {
        val layout = File(repoRoot(), "app/src/main/res/layout/fragment_dashboard.xml").readText()

        assertFalse(
            "Dashboard should not keep an external prediction title above the card.",
            layout.contains("""android:id="@+id/tvPredictionTitle"""")
        )
        assertFalse(
            "Dashboard should not keep an external quick record title above the card.",
            layout.contains("""android:id="@+id/tvQuickRecordTitle"""")
        )
        assertTrue(
            "Prediction card should own its left-only section title.",
            layout.contains("""android:id="@+id/tvPredictionSectionTitle"""") &&
                layout.contains("""android:text="@string/next_prediction"""")
        )
        assertTrue(
            "Quick record card should own its left-only section title.",
            layout.contains("""android:id="@+id/tvQuickRecordSectionTitle"""") &&
                layout.contains("""android:text="@string/quick_record"""")
        )
        assertFalse(
            "Dashboard title rows should not add right-side helper copy.",
            layout.contains("based_on_recent_records") ||
                layout.contains("common_entry") ||
                layout.contains("基于近期记录") ||
                layout.contains("常用入口")
        )
    }

    @Test
    fun `toolbar identity header uses themed resources and line chevron`() {
        val toolbarLayout = File(
            repoRoot(),
            "components/src/main/res/layout/layout_base_tool_bar.xml"
        ).readText()
        val chevron = File(repoRoot(), "common/src/main/res/drawable/ic_chevron_down.xml")
        val avatarBg = File(repoRoot(), "common/src/main/res/drawable/bg_toolbar_identity_avatar.xml")

        assertTrue(
            "BaseToolbar should expose a hidden identity title mode.",
            toolbarLayout.contains("""android:id="@+id/llIdentityTitle"""") &&
                toolbarLayout.contains("""android:id="@+id/tvIdentityTitle"""") &&
                toolbarLayout.contains("""android:id="@+id/ivIdentityChevron"""")
        )
        assertTrue(
            "Chevron icon should be a tintable stroked vector.",
            chevron.exists() &&
                chevron.readText().contains("""android:strokeWidth="2"""") &&
                chevron.readText().contains("""android:strokeColor="?attr/iconColorSecondary"""")
        )
        assertTrue(
            "Identity avatar background should use theme surface variant, not a hardcoded color.",
            avatarBg.exists() &&
                avatarBg.readText().contains("""android:shape="oval"""") &&
                avatarBg.readText().contains("""android:color="?attr/colorSurfaceVariant"""")
        )
    }

    private fun repoRoot(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        while (current.parentFile != null && !File(current, "settings.gradle.kts").exists()) {
            current = current.parentFile
        }
        return current
    }
}
```

- [ ] **Step 2: Run the new test and verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.zero.babycare.ui.DashboardHeaderPolicyTest"
```

Expected: FAIL because `tvPredictionTitle`, `tvQuickRecordTitle`, `llIdentityTitle`, `ic_chevron_down.xml`, and `bg_toolbar_identity_avatar.xml` do not exist in the required final form.

- [ ] **Step 3: Add the shared chevron icon**

Create `common/src/main/res/drawable/ic_chevron_down.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">

    <path
        android:pathData="M6,9L12,15L18,9"
        android:strokeWidth="2"
        android:strokeColor="?attr/iconColorSecondary"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:fillColor="@android:color/transparent" />
</vector>
```

- [ ] **Step 4: Add the identity avatar background**

Create `common/src/main/res/drawable/bg_toolbar_identity_avatar.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="?attr/colorSurfaceVariant" />
</shape>
```

- [ ] **Step 5: Commit Task 1**

Run:

```powershell
git add common/src/main/res/drawable/ic_chevron_down.xml common/src/main/res/drawable/bg_toolbar_identity_avatar.xml app/src/test/java/com/zero/babycare/ui/DashboardHeaderPolicyTest.kt
git commit -m "Add dashboard header resource tests"
```

## Task 2: Add Identity Title Mode To BaseToolbar

**Files:**
- Modify: `components/src/main/res/layout/layout_base_tool_bar.xml`
- Modify: `components/src/main/java/com/zero/components/widget/BaseToolbar.kt`
- Test: `app/src/test/java/com/zero/babycare/ui/DashboardHeaderPolicyTest.kt`

- [ ] **Step 1: Update the toolbar layout**

In `components/src/main/res/layout/layout_base_tool_bar.xml`, insert this block after `rvLeftActions` and before `tvTitle`:

```xml
    <!-- 首页身份标题：只在 Dashboard 等需要对象身份的页面显示。 -->
    <LinearLayout
        android:id="@+id/llIdentityTitle"
        android:layout_width="wrap_content"
        android:layout_height="56dp"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:gravity="center_vertical"
        android:minWidth="48dp"
        android:orientation="horizontal"
        android:paddingStart="16dp"
        android:paddingEnd="12dp"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        tools:visibility="visible">

        <FrameLayout
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:background="@drawable/bg_toolbar_identity_avatar">

            <ImageView
                android:id="@+id/ivIdentityAvatar"
                android:layout_width="24dp"
                android:layout_height="24dp"
                android:layout_gravity="center"
                android:contentDescription="@null"
                android:src="@drawable/ic_baby_default"
                app:tint="?attr/iconColorBrand" />
        </FrameLayout>

        <TextView
            android:id="@+id/tvIdentityTitle"
            style="@style/TextAppearance.BabyCare.Heading2"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="12dp"
            android:ellipsize="end"
            android:maxWidth="180dp"
            android:singleLine="true"
            android:textColor="?attr/colorTextPrimary"
            tools:text="龙总" />

        <ImageView
            android:id="@+id/ivIdentityChevron"
            android:layout_width="24dp"
            android:layout_height="24dp"
            android:layout_marginStart="4dp"
            android:contentDescription="@null"
            android:src="@drawable/ic_chevron_down"
            app:tint="?attr/iconColorSecondary" />
    </LinearLayout>
```

- [ ] **Step 2: Add BaseToolbar identity mode code**

Modify `components/src/main/java/com/zero/components/widget/BaseToolbar.kt` with these methods and helper calls:

```kotlin
    /**
     * 显示对象身份标题，适合首页这类以宝宝为主对象的页面。
     * 该模式会隐藏居中标题和左侧动作，避免菜单按钮继续抢占首页第一视觉。
     */
    fun showIdentityTitle(title: CharSequence, listener: (() -> Unit)? = null) {
        binding.rvLeftActions.visibility = View.GONE
        leftActionAdapter.submitList(emptyList())
        onLeftActionClick = null

        binding.tvTitle.visibility = View.GONE
        binding.llIdentityTitle.visibility = View.VISIBLE
        binding.tvIdentityTitle.text = title
        binding.llIdentityTitle.isClickable = listener != null
        binding.llIdentityTitle.isFocusable = listener != null
        binding.llIdentityTitle.setOnClickListener {
            listener?.invoke()
        }
        requestTitleInsetsUpdate()
    }

    private fun showCenteredTitle() {
        binding.llIdentityTitle.visibility = View.GONE
        binding.llIdentityTitle.setOnClickListener(null)
        binding.llIdentityTitle.isClickable = false
        binding.llIdentityTitle.isFocusable = false
        binding.tvTitle.visibility = View.VISIBLE
    }
```

Update `setLeftActions(...)` so standard toolbar modes restore the centered title:

```kotlin
    private fun setLeftActions(
        actions: List<ToolbarAction>,
        listener: ((ToolbarAction) -> Unit)? = null
    ) {
        showCenteredTitle()
        if (actions.isEmpty()) {
            hideLeftButton()
            return
        }
        onLeftActionClick = listener
        binding.rvLeftActions.visibility = View.VISIBLE
        leftActionAdapter.submitList(actions)
        requestTitleInsetsUpdate()
    }
```

Update `hideLeftButton()` to restore the centered title for ordinary pages:

```kotlin
    fun hideLeftButton() {
        showCenteredTitle()
        binding.rvLeftActions.visibility = View.GONE
        onLeftActionClick = null
        leftActionAdapter.submitList(emptyList())
        requestTitleInsetsUpdate()
    }
```

Update `updateTitleInsets()` so it does nothing when the identity title owns the toolbar:

```kotlin
    private fun updateTitleInsets() {
        if (binding.tvTitle.visibility != View.VISIBLE) return

        // 为了保证标题视觉居中，用左右区域的最大宽度做对称内边距
        val leftWidth = if (binding.rvLeftActions.visibility == View.VISIBLE) {
            binding.rvLeftActions.width
        } else {
            0
        }
        val rightWidth = if (binding.rvActions.visibility == View.VISIBLE) {
            binding.llRightActions.width
        } else {
            0
        }
        val reserved = maxOf(leftWidth, rightWidth)
        binding.tvTitle.updatePaddingRelative(
            start = titleBasePaddingStart + reserved,
            end = titleBasePaddingEnd + reserved
        )
    }
```

- [ ] **Step 3: Run the toolbar policy test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.zero.babycare.ui.DashboardHeaderPolicyTest.toolbar*"
```

Expected: PASS for the toolbar resource test.

- [ ] **Step 4: Commit Task 2**

Run:

```powershell
git add components/src/main/res/layout/layout_base_tool_bar.xml components/src/main/java/com/zero/components/widget/BaseToolbar.kt
git commit -m "Add toolbar identity title mode"
```

## Task 3: Apply Baby Identity Header On Dashboard

**Files:**
- Modify: `app/src/main/java/com/zero/babycare/home/DashboardFragment.kt`

- [ ] **Step 1: Replace menu-button setup with identity setup**

In `DashboardFragment.initView(...)`, replace the current toolbar block:

```kotlin
        // 设置标题：显示宝宝名称
        updateToolbarTitle()

        // 左侧菜单按钮 - 打开侧边栏
        binding.toolbar.showMenuButton {
            (activity as? MainActivity)?.openDrawer()
        }

        // 隐藏右侧按钮
        binding.toolbar.hideAction()
```

with:

```kotlin
        setupDashboardToolbar()
```

- [ ] **Step 2: Replace title refresh on page reveal**

In `onHiddenChanged(...)`, replace:

```kotlin
            updateToolbarTitle()
```

with:

```kotlin
            setupDashboardToolbar()
```

- [ ] **Step 3: Replace `updateToolbarTitle()` with `setupDashboardToolbar()`**

Replace the existing `private fun updateToolbarTitle()` method with:

```kotlin
    private fun setupDashboardToolbar() {
        val baby = mainVm.getCurrentBabyInfo()
        val title = baby?.name
            ?.takeIf { it.isNotBlank() }
            ?: getString(com.zero.common.R.string.no_baby_yet)

        // 首页头部表达当前宝宝身份，点击进入宝宝列表或创建流程；侧边栏只保留手势入口。
        binding.toolbar.showIdentityTitle(title) {
            if (baby != null) {
                mainVm.navigateTo(NavTarget.AllChildren)
            } else {
                mainVm.navigateTo(NavTarget.BabyInfo.create())
            }
        }
        binding.toolbar.hideAction()
    }
```

Remove the unused import if the compiler flags it:

```kotlin
import com.zero.babycare.MainActivity
```

- [ ] **Step 4: Run a compile check for the app module**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: PASS. If the import removal is needed, fix it and rerun the same command.

- [ ] **Step 5: Commit Task 3**

Run:

```powershell
git add app/src/main/java/com/zero/babycare/home/DashboardFragment.kt
git commit -m "Use baby identity dashboard header"
```

## Task 4: Move Dashboard Section Titles Into Cards

**Files:**
- Modify: `app/src/main/res/layout/fragment_dashboard.xml`
- Test: `app/src/test/java/com/zero/babycare/ui/DashboardHeaderPolicyTest.kt`

- [ ] **Step 1: Update the prediction card title**

In `app/src/main/res/layout/fragment_dashboard.xml`, remove the external `tvPredictionTitle` block. Change `cardPrediction` to use `android:layout_marginTop="24dp"` and add this title at the top of the card:

```xml
                <TextView
                    android:id="@+id/tvPredictionSectionTitle"
                    style="@style/TextAppearance.BabyCare.Action"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/next_prediction"
                    android:textColor="?attr/colorTextPrimary"
                    app:layout_constraintStart_toStartOf="parent"
                    app:layout_constraintTop_toTopOf="parent" />
```

Update `ivPredictFeeding` constraints:

```xml
                app:layout_constraintTop_toBottomOf="@id/tvPredictionSectionTitle"
                android:layout_marginTop="8dp"
```

Keep the prediction title row left-only. Do not add a right-side `TextView`.

- [ ] **Step 2: Wrap quick record in a card with a left-only title**

Replace the external `tvQuickRecordTitle` and the standalone `rvQuickRecord` with:

```xml
            <androidx.constraintlayout.widget.ConstraintLayout
                android:id="@+id/cardQuickRecord"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="24dp"
                android:background="@drawable/bg_card_prediction"
                android:outlineProvider="background"
                android:padding="16dp">

                <TextView
                    android:id="@+id/tvQuickRecordSectionTitle"
                    style="@style/TextAppearance.BabyCare.Action"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/quick_record"
                    android:textColor="?attr/colorTextPrimary"
                    app:layout_constraintStart_toStartOf="parent"
                    app:layout_constraintTop_toTopOf="parent" />

                <androidx.recyclerview.widget.RecyclerView
                    android:id="@+id/rvQuickRecord"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="8dp"
                    android:clipToPadding="false"
                    android:nestedScrollingEnabled="false"
                    android:overScrollMode="never"
                    app:layout_constraintEnd_toEndOf="parent"
                    app:layout_constraintStart_toStartOf="parent"
                    app:layout_constraintTop_toBottomOf="@id/tvQuickRecordSectionTitle" />

            </androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 3: Run the Dashboard header policy test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.zero.babycare.ui.DashboardHeaderPolicyTest.dashboard*"
```

Expected: PASS for the Dashboard card title test.

- [ ] **Step 4: Commit Task 4**

Run:

```powershell
git add app/src/main/res/layout/fragment_dashboard.xml
git commit -m "Move dashboard section titles into cards"
```

## Task 5: Restore Drawer Access With Dashboard Left-Edge Swipe

**Files:**
- Modify: `app/src/main/java/com/zero/babycare/MainActivity.kt`

- [ ] **Step 1: Add imports and gesture fields**

Add imports:

```kotlin
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.drawerlayout.widget.DrawerLayout
import kotlin.math.abs
```

Add fields inside `MainActivity`:

```kotlin
    private val drawerEdgeWidthPx by lazy { dpToPx(DRAWER_EDGE_WIDTH_DP) }
    private val drawerOpenDistancePx by lazy { dpToPx(DRAWER_OPEN_DISTANCE_DP) }
    private val drawerTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var drawerGestureStartX = 0f
    private var drawerGestureStartY = 0f
    private var isTrackingDrawerGesture = false
```

Add constants in the companion area near other private constants. If there is no companion object, add one near the top of the class:

```kotlin
    companion object {
        private const val DRAWER_EDGE_WIDTH_DP = 32f
        private const val DRAWER_OPEN_DISTANCE_DP = 48f
        private const val DRAWER_HORIZONTAL_RATIO = 1.5f
    }
```

- [ ] **Step 2: Unlock DrawerLayout during drawer setup**

In `setupDrawer()`, add this before configuring drawer content:

```kotlin
        // 首页不再显示菜单按钮，侧边栏必须保持可通过边缘手势打开。
        binding.drawerLayout.setDrawerLockMode(
            DrawerLayout.LOCK_MODE_UNLOCKED,
            GravityCompat.START
        )
```

- [ ] **Step 3: Add dispatch-level gesture tracking**

Add this override and helper methods to `MainActivity`:

```kotlin
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (handleDashboardDrawerGesture(event)) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun handleDashboardDrawerGesture(event: MotionEvent): Boolean {
        if (vm.navTarget.value !is NavTarget.Dashboard || isDrawerOpen()) {
            resetDrawerGesture()
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawerGestureStartX = event.x
                drawerGestureStartY = event.y
                isTrackingDrawerGesture = event.x <= drawerEdgeWidthPx
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTrackingDrawerGesture) return false

                val deltaX = event.x - drawerGestureStartX
                val deltaY = event.y - drawerGestureStartY
                val horizontalEnough = deltaX > drawerOpenDistancePx &&
                    deltaX > drawerTouchSlop &&
                    deltaX > abs(deltaY) * DRAWER_HORIZONTAL_RATIO

                if (horizontalEnough) {
                    openDrawer()
                    resetDrawerGesture()
                    return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> resetDrawerGesture()
        }

        return false
    }

    private fun resetDrawerGesture() {
        isTrackingDrawerGesture = false
        drawerGestureStartX = 0f
        drawerGestureStartY = 0f
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }
```

- [ ] **Step 4: Run compile check**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 5: Commit Task 5**

Run:

```powershell
git add app/src/main/java/com/zero/babycare/MainActivity.kt
git commit -m "Enable dashboard drawer edge swipe"
```

## Task 6: Full Verification

**Files:**
- No source edits unless verification reveals a concrete issue.

- [ ] **Step 1: Run targeted policy tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.zero.babycare.ui.DashboardHeaderPolicyTest"
```

Expected: PASS.

- [ ] **Step 2: Run existing UI resource policy tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.zero.babycare.ui.UiResourcePolicyTest"
```

Expected: PASS. If this fails because `UiResourcePolicyTest.kt` has unrelated existing local edits, record the failure and do not revert those edits.

- [ ] **Step 3: Build Debug APK**

Run:

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 4: Manual QA on device or emulator**

Verify:

1. Dashboard header shows avatar, baby name, and chevron; there is no menu icon.
2. Tapping the baby identity area navigates to all babies when a baby exists.
3. Left-edge right swipe opens the drawer on Dashboard.
4. Vertical Dashboard scrolling does not open the drawer.
5. Feeding, sleep, quick record, and bottom quick action taps still work.
6. Prediction and quick record titles are inside their cards, left-only, with no right-side helper text.
7. Boy / girl themes and light / dark mode keep title colors readable and visually quiet.

- [ ] **Step 5: Final commit if verification required small fixes**

If verification required edits, commit them:

```powershell
git add common/src/main/res/drawable/ic_chevron_down.xml common/src/main/res/drawable/bg_toolbar_identity_avatar.xml components/src/main/res/layout/layout_base_tool_bar.xml components/src/main/java/com/zero/components/widget/BaseToolbar.kt app/src/main/java/com/zero/babycare/home/DashboardFragment.kt app/src/main/res/layout/fragment_dashboard.xml app/src/main/java/com/zero/babycare/MainActivity.kt app/src/test/java/com/zero/babycare/ui/DashboardHeaderPolicyTest.kt
git commit -m "Polish dashboard header verification issues"
```

If no edits were needed after Task 5, do not create an empty commit.

## Self-Review

Spec coverage:

- Baby identity header: Task 2 and Task 3.
- No menu button: Task 3.
- Drawer right-swipe access: Task 5.
- Section titles inside cards: Task 4.
- No right-side helper copy: Task 1 test and Task 4 layout requirements.
- No new localization strings: File structure and Task 4 reuse existing strings.
- Visual title sizing and colors: Task 2 and Task 4 use `Heading2` for baby name and `Action` with `colorTextPrimary` for card titles.

Red flag scan:

- No banned incomplete markers or unspecified implementation steps.
- Every source-editing task includes concrete file paths and code snippets.

Type consistency:

- `showIdentityTitle(...)` is defined in Task 2 and called in Task 3 with the same signature.
- `tvPredictionSectionTitle`, `tvQuickRecordSectionTitle`, and `cardQuickRecord` are introduced in Task 4 and asserted in Task 1.
- Drawer gesture constants and fields are introduced before use in Task 5.
