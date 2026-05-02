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
