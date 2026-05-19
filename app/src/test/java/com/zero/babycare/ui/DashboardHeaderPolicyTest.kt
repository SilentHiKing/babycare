package com.zero.babycare.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashboardHeaderPolicyTest {

    @Test
    fun `dashboard section titles stay inside cards without helper copy`() {
        val layout = File(repoRoot(), "app/src/main/res/layout/fragment_dashboard.xml").readText()
        val fragment = File(repoRoot(), "app/src/main/java/com/zero/babycare/home/DashboardFragment.kt").readText()

        // 首页标题只做弱分组，不再作为卡片外的大号章节标题抢占视觉焦点。
        assertFalse("Prediction title should not stay outside the card.", layout.contains("@+id/tvPredictionTitle"))
        assertFalse("Quick record title should not stay outside the card.", layout.contains("@+id/tvQuickRecordTitle"))
        assertTrue("Prediction card should contain an internal title.", layout.contains("@+id/tvPredictionSectionTitle"))
        assertTrue("Quick record card should contain an internal title.", layout.contains("@+id/tvQuickRecordSectionTitle"))
        assertTrue("Quick record list should be wrapped in a quiet card surface.", layout.contains("@+id/cardQuickRecord"))

        val forbiddenHelperCopy = listOf(
            "based_on_recent_records",
            "common_entry",
            "基于近期记录",
            "常用入口"
        )
        val offenders = forbiddenHelperCopy.filter { layout.contains(it) }
        assertTrue("Section title rows should not add right-side helper copy: $offenders", offenders.isEmpty())
        assertFalse("Dashboard header should not show the drawer menu button.", fragment.contains("showMenuButton"))
    }

    @Test
    fun `prediction title is visually secondary to prediction content`() {
        val layout = File(repoRoot(), "app/src/main/res/layout/fragment_dashboard.xml").readText()
        val predictionTitleBlock = requireBlock(layout, "@+id/tvPredictionSectionTitle")
        val predictionFirstRowBlock = requireBlock(layout, "@+id/ivPredictFeeding")

        // 预测卡里标题只做分组提示，不能和“无法预测/预测时间”使用同一主文本层级。
        assertTrue(
            "Prediction title should use label typography.",
            predictionTitleBlock.contains("""style="@style/TextAppearance.BabyCare.Label"""")
        )
        assertTrue(
            "Prediction title should use hint text color.",
            predictionTitleBlock.contains("""android:textColor="?attr/colorTextHint"""")
        )
        assertFalse(
            "Prediction title should not reuse primary text color.",
            predictionTitleBlock.contains("""android:textColor="?attr/colorTextPrimary"""")
        )
        assertFalse(
            "Prediction title should not stay in the same medium action level as content.",
            predictionTitleBlock.contains("""style="@style/TextAppearance.BabyCare.Action"""")
        )
        assertTrue(
            "Prediction content should remain primary so users read the result first.",
            layout.contains("""android:id="@+id/tvPredictFeedingTime"""") &&
                requireBlock(layout, "@+id/tvPredictFeedingTime")
                    .contains("""android:textColor="?attr/colorTextPrimary"""")
        )
        assertTrue(
            "Prediction title and first row need enough vertical separation.",
            predictionFirstRowBlock.contains("""android:layout_marginTop="14dp"""")
        )
    }

    @Test
    fun `dashboard drawer opens from left edge gesture`() {
        val activity = File(repoRoot(), "app/src/main/java/com/zero/babycare/MainActivity.kt").readText()

        // 首页去掉显式菜单按钮后，MainActivity 必须保留可发现的左边缘右滑入口。
        assertTrue("Drawer should be explicitly unlocked for the start edge.", activity.contains("DrawerLayout.LOCK_MODE_UNLOCKED"))
        assertTrue("Activity should inspect touch events for the dashboard edge gesture.", activity.contains("dispatchTouchEvent"))
        assertTrue("Drawer gesture should only apply to Dashboard.", activity.contains("NavTarget.Dashboard"))
        assertTrue("Drawer gesture should keep a narrow edge start zone.", activity.contains("DRAWER_EDGE_WIDTH_DP"))
        assertTrue("Drawer gesture should require a meaningful horizontal drag.", activity.contains("DRAWER_OPEN_DISTANCE_DP"))
        assertTrue("Drawer gesture should reject mostly vertical scrolls.", activity.contains("DRAWER_HORIZONTAL_RATIO"))
    }

    @Test
    fun `toolbar layout exposes identity title mode`() {
        val layout = File(repoRoot(), "components/src/main/res/layout/layout_base_tool_bar.xml").readText()

        // 首页需要复用 BaseToolbar 的沉浸状态栏能力，同时用身份区替代居中页面标题。
        assertTrue("Toolbar should include an identity title container.", layout.contains("@+id/llIdentityTitle"))
        assertTrue("Toolbar identity should show the current baby name.", layout.contains("@+id/tvIdentityTitle"))
        assertTrue("Toolbar identity should show a dropdown chevron.", layout.contains("@+id/ivIdentityChevron"))
        assertTrue("Toolbar identity should use the shared baby icon.", layout.contains("@drawable/ic_baby_default"))
        assertTrue("Toolbar identity avatar should use a themed soft surface.", layout.contains("@drawable/bg_toolbar_identity_avatar"))
        assertTrue("Toolbar identity chevron should use the shared chevron icon.", layout.contains("@drawable/ic_chevron_down"))
    }

    @Test
    fun `identity header resources use soft utility tokens`() {
        val chevron = File(repoRoot(), "common/src/main/res/drawable/ic_chevron_down.xml")
        val avatar = File(repoRoot(), "common/src/main/res/drawable/bg_toolbar_identity_avatar.xml")

        assertTrue("Identity chevron icon should exist in common resources.", chevron.isFile)
        assertTrue("Identity avatar background should exist in common resources.", avatar.isFile)

        val chevronContent = chevron.readText()
        val avatarContent = avatar.readText()
        assertTrue("Chevron should use a 24dp vector viewport.", chevronContent.contains("viewportWidth=\"24\""))
        assertTrue("Chevron should use the secondary icon theme color.", chevronContent.contains("strokeColor=\"?attr/iconColorSecondary\""))
        assertTrue("Chevron stroke should match the shared line icon weight.", chevronContent.contains("strokeWidth=\"2\""))
        assertFalse("Chevron should stay tintable and avoid hardcoded colors.", Regex("""#[0-9A-Fa-f]{6,8}""").containsMatchIn(chevronContent))
        assertTrue("Avatar background should be oval.", avatarContent.contains("<shape") && avatarContent.contains("android:shape=\"oval\""))
        assertTrue("Avatar background should use themed surface variant.", avatarContent.contains("?attr/colorSurfaceVariant"))
    }

    private fun repoRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir")) { "user.dir is not set" }
        var dir: File? = File(userDir).canonicalFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        return requireNotNull(dir) { "Cannot locate repository root from user.dir" }
    }

    private fun requireBlock(layout: String, viewId: String): String {
        val idIndex = layout.indexOf(viewId)
        require(idIndex >= 0) { "Cannot find view id $viewId" }
        val startIndex = layout.lastIndexOf('<', idIndex)
        val endIndex = layout.indexOf('>', idIndex)
        require(startIndex >= 0 && endIndex > startIndex) { "Cannot extract view block for $viewId" }
        return layout.substring(startIndex, endIndex + 1)
    }
}
