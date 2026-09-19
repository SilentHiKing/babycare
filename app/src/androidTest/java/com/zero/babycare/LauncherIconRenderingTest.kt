package com.zero.babycare

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 只加载并绘制图标资源，不启动业务页面或读取宝宝资料。 */
@RunWith(AndroidJUnit4::class)
class LauncherIconRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun launcherVariantsKeepTheCareMarkAndAdaptiveSafeArea() {
        // 统一绘制为216px；旧版72dp画布与自适应72dp可见区域应有相同的图形比例。
        val size = 216
        val bitmaps = mutableListOf<Bitmap>()
        fun capture(drawable: Drawable, variant: String, pixels: Int = size): Bitmap {
            val bitmap = render(drawable, pixels)
            bitmaps += bitmap
            saveScreenshot(bitmap, variant)
            return bitmap
        }
        try {
            // 先保留图形，后续像素断言失败时仍可区分资源问题和系统包装。
            val mark = capture(requireNotNull(context.getDrawable(com.zero.common.R.drawable.ic_babycare_mark)), "mark-reference")
            val legacyIcons = mutableListOf<Pair<String, Bitmap>>()
            listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).forEach { night ->
                val configuration = Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                }
                val resourcesContext = context.createConfigurationContext(configuration)
                val mode = if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
                listOf("legacy" to com.zero.common.R.drawable.ic_babycare_launcher_legacy,
                    "legacy-round" to com.zero.common.R.drawable.ic_babycare_launcher_legacy_round).forEach { (name, id) ->
                    val variant = "$name-$mode"
                    legacyIcons += variant to capture(requireNotNull(resourcesContext.getDrawable(id)), variant)
                }
            }

            // PackageManager 走Launcher真实的资源解析，能发现旧密度位图或错误版本覆盖仍被选中。
            val applicationIcon = context.packageManager.getApplicationIcon(context.packageName)
            val current = capture(applicationIcon, "package-manager")
            val resourceIcon = requireNotNull(context.getDrawable(R.mipmap.ic_launcher))
            val resolved = capture(resourceIcon, "resolved-launcher")
            Log.i("LauncherIconRenderingTest", "图标解析：版本=${Build.VERSION.SDK_INT}，" +
                "PM=${applicationIcon.javaClass.simpleName}(${applicationIcon.intrinsicWidth}x${applicationIcon.intrinsicHeight})，" +
                "资源=${resourceIcon.javaClass.simpleName}(${resourceIcon.intrinsicWidth}x${resourceIcon.intrinsicHeight})")
            val round = capture(requireNotNull(context.getDrawable(R.mipmap.ic_launcher_round)), "resolved-round")
            val foreground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                capture(requireNotNull(context.getDrawable(com.zero.common.R.drawable.ic_babycare_adaptive_foreground)),
                    "adaptive-foreground", 324)
            } else null
            val monochrome = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && resourceIcon is AdaptiveIconDrawable) {
                resourceIcon.monochrome?.let { capture(it, "monochrome", 324) }
            } else null

            legacyIcons.forEach { (name, bitmap) -> assertCareMarkVisible(mark, bitmap, name) }
            assertCareMarkVisible(mark, resolved, "resolved-launcher")
            assertCareMarkVisible(mark, round, "resolved-round")
            if (applicationIcon is BitmapDrawable) {
                // OEM可将自适应图标预栅格化。只容许已测得的1px边缘误差，不调整大小或位置。
                val match = compareRasterizedMark(mark, current)
                Log.i("LauncherIconRenderingTest", "系统位图检查：${match.details}")
                assertTrue("系统位图改变照护标志：${match.details}", match.accepted)
            } else {
                assertCareMarkVisible(mark, current, "package-manager")
            }
            assertRasterizedComparisonRejectsWrongIcons(mark)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                assertTrue("API26及以上资源必须解析到自适应图标", resourceIcon is AdaptiveIconDrawable)
                assertInsideSafeCircle(requireNotNull(foreground))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    assertSameAlpha(foreground, requireNotNull(monochrome) { "API33及以上必须提供单色主题图标" })
                }
            }
        } finally { bitmaps.forEach(Bitmap::recycle) }
    }

    private fun render(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.mutate().apply { setBounds(0, 0, size, size) }.draw(Canvas(bitmap))
        return bitmap
    }

    private data class RasterizedMatch(
        val coverage: Double,
        val precision: Double,
        val maximumEdgeShift: Int,
        val centerDistance: Double
    ) {
        val accepted: Boolean get() = coverage >= 0.98 && precision >= 0.98 &&
            maximumEdgeShift <= 1 && centerDistance <= 1.0
        val details: String get() = "覆盖率=$coverage，反向精度=$precision，最大边界偏移=${maximumEdgeShift}px，质心距离=${centerDistance}px"
    }

    /** 双向匹配同时保护笔画和负空间；包围框与质心阻止算法用近邻容忍掩盖整体变形。 */
    private fun compareRasterizedMark(mark: Bitmap, icon: Bitmap): RasterizedMatch {
        require(mark.width == icon.width && mark.height == icon.height)
        val width = mark.width
        val height = mark.height
        val expected = IntArray(width * height).also { mark.getPixels(it, 0, width, 0, 0, width, height) }
        val actual = IntArray(width * height).also { icon.getPixels(it, 0, width, 0, 0, width, height) }
        val ink = expected.first { Color.alpha(it) >= 250 }
        val reference = BooleanArray(expected.size) { Color.alpha(expected[it]) >= 250 }
        val candidate = BooleanArray(actual.size) { index ->
            val pixel = actual[index]
            Color.alpha(pixel) >= 250 && abs(Color.red(ink) - Color.red(pixel)) <= 8 &&
                abs(Color.green(ink) - Color.green(pixel)) <= 8 && abs(Color.blue(ink) - Color.blue(pixel)) <= 8
        }
        fun withinOnePixel(mask: BooleanArray, index: Int): Boolean {
            val x = index % width
            val y = index / width
            // 欧氏距离至多1px，只查自身及上下左右，不能把对角或更宽邻域混入容差。
            return mask[index] || (x > 0 && mask[index - 1]) || (x + 1 < width && mask[index + 1]) ||
                (y > 0 && mask[index - width]) || (y + 1 < height && mask[index + width])
        }
        fun geometry(mask: BooleanArray): DoubleArray {
            val points = mask.indices.filter { mask[it] }
            if (points.isEmpty()) return doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
            return doubleArrayOf(points.minOf { it % width }.toDouble(), points.minOf { it / width }.toDouble(),
                points.maxOf { it % width }.toDouble(), points.maxOf { it / width }.toDouble(),
                points.map { it % width }.average(), points.map { it / width }.average())
        }
        val expectedCount = reference.count { it }
        val actualCount = candidate.count { it }
        if (actualCount == 0) return RasterizedMatch(0.0, 0.0, Int.MAX_VALUE, Double.POSITIVE_INFINITY)
        val expectedGeometry = geometry(reference)
        val actualGeometry = geometry(candidate)
        return RasterizedMatch(
            reference.indices.count { reference[it] && withinOnePixel(candidate, it) }.toDouble() / expectedCount,
            candidate.indices.count { candidate[it] && withinOnePixel(reference, it) }.toDouble() / actualCount,
            (0..3).maxOf { abs(expectedGeometry[it] - actualGeometry[it]).roundToInt() },
            hypot(expectedGeometry[4] - actualGeometry[4], expectedGeometry[5] - actualGeometry[5])
        )
    }

    /** 错图、填实、缩放和局部裁剪都必须被拒绝，避免后续扩大容差只让真实设备“过测”。 */
    private fun assertRasterizedComparisonRejectsWrongIcons(mark: Bitmap) {
        assertTrue("原图应通过位图比较", compareRasterizedMark(mark, mark).accepted)
        val width = mark.width
        val height = mark.height
        val original = IntArray(width * height).also { mark.getPixels(it, 0, width, 0, 0, width, height) }
        val ink = original.first { Color.alpha(it) >= 250 }
        listOf("错轮廓", "实心", "缩小90%", "放大110%", "去头", "底部裁剪").forEach { variant ->
            val pixels = IntArray(original.size) { index ->
                val x = index % width
                val y = index / width
                when (variant) {
                    "错轮廓" -> if (x in 60..155 && y in 30..180 && (x < 70 || x > 145 || y < 40 || y > 170)) ink else Color.TRANSPARENT
                    "实心" -> if (hypot(x - width / 2.0, y - height / 2.0) < 70) ink else Color.TRANSPARENT
                    "去头" -> if (y < 95) Color.TRANSPARENT else original[index]
                    "底部裁剪" -> if (y >= 178) Color.TRANSPARENT else original[index]
                    else -> {
                        val scale = if (variant == "缩小90%") 0.9 else 1.1
                        val sourceX = ((x - width / 2.0) / scale + width / 2.0).roundToInt()
                        val sourceY = ((y - height / 2.0) / scale + height / 2.0).roundToInt()
                        if (sourceX in 0 until width && sourceY in 0 until height) original[sourceY * width + sourceX] else Color.TRANSPARENT
                    }
                }
            }
            val wrong = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            try {
                val match = compareRasterizedMark(mark, wrong)
                assertFalse("必须拒绝${variant}：${match.details}", match.accepted)
            } finally { wrong.recycle() }
        }
    }

    /** 核心实色笔画须保留原颜色和位置，不能被模板位图、遮罩或错误inset替代。 */
    private fun assertCareMarkVisible(mark: Bitmap, icon: Bitmap, name: String) {
        var opaqueMarkPixels = 0
        var retainedPixels = 0
        var markColor: Int? = null
        for (y in 0 until mark.height) {
            for (x in 0 until mark.width) {
                val expected = mark.getPixel(x, y)
                if (Color.alpha(expected) < 250) continue
                opaqueMarkPixels++
                markColor = expected
                val actual = icon.getPixel(x, y)
                if (Color.alpha(actual) >= 250 &&
                    abs(Color.red(expected) - Color.red(actual)) <= 8 &&
                    abs(Color.green(expected) - Color.green(actual)) <= 8 &&
                    abs(Color.blue(expected) - Color.blue(actual)) <= 8) retainedPixels++
            }
        }
        assertTrue("照护标志不能为空", opaqueMarkPixels > 0)
        assertTrue("图标未保留完整照护笔画或比例：$name，保留=$retainedPixels/$opaqueMarkPixels",
            retainedPixels >= opaqueMarkPixels * 0.98)
        val ink = requireNotNull(markColor)
        var unexpectedInk = 0
        for (y in 0 until mark.height) {
            for (x in 0 until mark.width) {
                if (Color.alpha(mark.getPixel(x, y)) != 0) continue
                val actual = icon.getPixel(x, y)
                if (Color.alpha(actual) >= 250 &&
                    abs(Color.red(ink) - Color.red(actual)) <= 8 &&
                    abs(Color.green(ink) - Color.green(actual)) <= 8 &&
                    abs(Color.blue(ink) - Color.blue(actual)) <= 8) unexpectedInk++
            }
        }
        assertTrue("图标负空间不能被前景色填满：$name，多余笔画像素=$unexpectedInk",
            unexpectedInk <= mark.width * mark.height * 0.01)
    }

    /** 在108dp实际前景画布中逐像素验证中心66dp安全圆，包含线宽和圆角线帽。 */
    private fun assertInsideSafeCircle(bitmap: Bitmap) {
        val scale = bitmap.width / 108.0
        val center = bitmap.width / 2.0
        var maximumRadius = 0.0
        var visiblePixels = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) <= 16) continue
                visiblePixels++
                maximumRadius = maxOf(maximumRadius, hypot(x + 0.5 - center, y + 0.5 - center))
            }
        }
        assertTrue("自适应前景不能为空", visiblePixels > 0)
        // 仅容许1px抗锯齿边缘误差，不用外接矩形代替圆形遮罩的安全范围。
        assertTrue("关键图形越出66dp安全圆：实际半径=${maximumRadius / scale}dp",
            maximumRadius <= 33 * scale + 1)
    }

    private fun assertSameAlpha(foreground: Bitmap, monochrome: Bitmap) {
        var mismatches = 0
        for (y in 0 until foreground.height) {
            for (x in 0 until foreground.width) {
                if (abs(Color.alpha(foreground.getPixel(x, y)) - Color.alpha(monochrome.getPixel(x, y))) > 16) {
                    mismatches++
                }
            }
        }
        assertTrue("单色图标不能丢失标志或把透明背景变成实色块：不同像素=$mismatches", mismatches == 0)
    }

    private fun saveScreenshot(bitmap: Bitmap, variant: String) {
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
        val directory = File(context.filesDir, "audit-screens")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建图标截图目录" }
        File(directory, "launcher-api${Build.VERSION.SDK_INT}-$variant.png").outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法保存图标截图" }
        }
    }
}
