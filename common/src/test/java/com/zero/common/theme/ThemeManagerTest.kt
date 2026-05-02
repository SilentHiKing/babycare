package com.zero.common.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeManagerTest {

    @Test
    fun `baby theme list only exposes boy and girl themes`() {
        // 固定宝宝主题范围，避免后续重新引入默认主题、中性主题或额外分支。
        assertEquals(
            listOf(
                ThemeManager.BabyTheme.BOY,
                ThemeManager.BabyTheme.GIRL
            ),
            ThemeManager.BabyTheme.values().toList()
        )
    }

    @Test
    fun `unknown gender falls back to boy theme`() {
        // 未识别性别不再进入中性主题，统一回退到男孩主题。
        assertEquals(
            ThemeManager.BabyTheme.BOY,
            ThemeManager.getThemeByGender("unknown")
        )
    }
}
