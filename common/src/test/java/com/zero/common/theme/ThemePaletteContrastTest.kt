package com.zero.common.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

class ThemePaletteContrastTest {

    @Test
    fun `light theme action colors follow accessible babycare palette`() {
        val colors = loadColors("common/src/main/res/values/colors_theme.xml")

        // 行动主色需要同时保持 Soft Care 的柔和感，以及 Data Clarity 所需的白字可读性。
        assertEquals("#2A74B8", colors.getValue("boy_brand"))
        assertEquals("#1F5F99", colors.getValue("boy_brand_dark"))
        assertEquals("#D9EAF8", colors.getValue("boy_brand_light"))
        assertEquals("#EEF7FE", colors.getValue("boy_bg_brand"))
        assertEquals("#B8526C", colors.getValue("girl_brand"))
        assertEquals("#94394F", colors.getValue("girl_brand_dark"))
        assertEquals("#FADBE1", colors.getValue("girl_brand_light"))
        assertEquals("#FFF7F8", colors.getValue("girl_bg_brand"))

        listOf(
            "boy_brand",
            "boy_brand_dark",
            "boy_btn_pressed",
            "girl_brand",
            "girl_brand_dark",
            "girl_btn_pressed"
        ).forEach { colorName ->
            assertContrastAtLeast(colorName, colors.getValue(colorName), "#FFFFFF", 4.5)
        }
    }

    @Test
    fun `night theme action colors keep white text readable`() {
        val colors = loadColors("common/src/main/res/values-night/colors_theme.xml")

        // 深色模式不能直接使用高明度糖果色做按钮底色，否则白字会失去可读性。
        listOf(
            "boy_brand",
            "boy_brand_dark",
            "boy_btn_pressed",
            "girl_brand",
            "girl_brand_dark",
            "girl_btn_pressed"
        ).forEach { colorName ->
            assertContrastAtLeast(colorName, colors.getValue(colorName), "#FFFFFF", 4.5)
        }
    }

    @Test
    fun `brand foreground remains readable on both control surfaces in every theme`() {
        listOf(false, true).forEach { night ->
            val colors = palette(night)
            listOf("boy", "girl").forEach { gender ->
                val foreground = resolveThemeColor(gender, night, "colorBrandForeground", colors)
                listOf("surface_default", "control_surface_default", "control_surface_selected").forEach { surface ->
                    // 按钮底通过白字测试不代表能作控件文字，必须单独验证真实前景与背景组合。
                    assertContrastAtLeast("$gender/$night/$surface", foreground, colors.getValue(surface), 4.5)
                }
            }
        }
    }

    @Test
    fun `status title and elapsed time stay readable across baby themes and states`() {
        listOf(false, true).forEach { night ->
            val colors = palette(night)
            listOf("boy", "girl").forEach { gender ->
                listOf("colorStatusTextPrimary", "colorStatusTextSecondary").forEach { textToken ->
                    val foreground = resolveThemeColor(gender, night, textToken, colors)
                    listOf("status_awake_start", "status_sleeping_start", "status_feeding_start").forEach { surface ->
                        assertContrastAtLeast("$gender/$night/$textToken/$surface", foreground, colors.getValue(surface), 4.5)
                    }
                }
            }
        }
    }

    @Test
    fun `informational hint text meets normal text contrast on page and control surfaces`() {
        listOf(false, true).forEach { night ->
            val colors = palette(night)
            listOf("bg_app_default", "surface_default", "control_surface_default", "control_surface_selected").forEach { surface ->
                assertContrastAtLeast("hint/$night/$surface", colors.getValue("text_hint_default"), colors.getValue(surface), 4.5)
            }
        }
    }

    @Test
    fun `feeding and sleep card text stays readable in light and night palettes`() {
        listOf(false, true).forEach { night ->
            val colors = palette(night)
            listOf("feeding", "sleep").forEach { category ->
                // 辅助说明和今日次数同样是有效信息，不能只校验大号时长数字。
                listOf("primary_dark", "secondary").forEach { role ->
                    val token = "${category}_$role"
                    assertContrastAtLeast(token, colors.getValue(token), colors.getValue("${category}_bg"), 4.5)
                }
            }
        }
    }

    @Test
    fun `calendar and summary text remain readable on their surfaces`() {
        listOf(false, true).forEach { night ->
            val colors = palette(night)
            listOf("text_primary_default", "text_secondary_default", "text_hint_default").forEach { text ->
                assertContrastAtLeast("calendar/$night/$text", colors.getValue(text), colors.getValue("surface_default"), 4.5)
            }
            listOf("text_primary_default", "feeding_primary", "sleep_primary").forEach { text ->
                assertContrastAtLeast("summary/$night/$text", colors.getValue(text), colors.getValue("control_surface_default"), 4.5)
            }
        }
    }

    private fun palette(night: Boolean): Map<String, String> {
        val daytime = loadColors("common/src/main/res/values/colors_base.xml") +
            loadColors("common/src/main/res/values/colors_theme.xml")
        return if (night) {
            daytime + loadColors("common/src/main/res/values-night/colors.xml") +
                loadColors("common/src/main/res/values-night/colors_theme.xml")
        } else {
            daytime
        }
    }

    /** 读取实际主题映射，避免测试只验证色值，却遗漏布局所用 token 被错误覆盖。 */
    private fun resolveThemeColor(
        gender: String,
        night: Boolean,
        token: String,
        colors: Map<String, String>
    ): String {
        val directory = if (night) "values-night" else "values"
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(repoRoot(), "common/src/main/res/$directory/styles_$gender.xml"))
        val items = document.getElementsByTagName("item")
        val mappings = (0 until items.length).associate { index ->
            val element = items.item(index) as Element
            element.getAttribute("name") to element.textContent.trim()
        }
        var reference = mappings.getValue(token)
        while (reference.startsWith("?attr/")) {
            reference = mappings.getValue(reference.removePrefix("?attr/"))
        }
        return colors.getValue(reference.removePrefix("@color/"))
    }

    private fun loadColors(relativePath: String): Map<String, String> {
        val file = File(repoRoot(), relativePath)
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)

        val nodes = document.getElementsByTagName("color")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent.trim().uppercase()
        }
    }

    private fun assertContrastAtLeast(
        colorName: String,
        foreground: String,
        background: String,
        minimum: Double
    ) {
        val contrast = contrastRatio(foreground, background)
        assertTrue(
            "$colorName contrast is $contrast; expected at least $minimum for readable text.",
            contrast >= minimum
        )
    }

    private fun contrastRatio(first: String, second: String): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        val lighter = maxOf(firstLuminance, secondLuminance)
        val darker = minOf(firstLuminance, secondLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: String): Double {
        val rgb = color.removePrefix("#").let { hex ->
            val normalized = if (hex.length == 8) hex.substring(2) else hex
            listOf(
                normalized.substring(0, 2).toInt(16),
                normalized.substring(2, 4).toInt(16),
                normalized.substring(4, 6).toInt(16)
            )
        }

        val channels = rgb.map { channel ->
            val srgb = channel / 255.0
            if (srgb <= 0.03928) {
                srgb / 12.92
            } else {
                ((srgb + 0.055) / 1.055).pow(2.4)
            }
        }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
    }

    private fun repoRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir")) { "user.dir is not set" }
        var dir: File? = File(userDir).canonicalFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        return requireNotNull(dir) { "Cannot locate repository root from user.dir" }
    }
}
