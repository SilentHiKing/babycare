/**
 * 多语言文件生成插件
 */

// 首先配置仓库和依赖
buildscript {
    repositories {
        mavenCentral()
        google()
    }

    dependencies {
        classpath("com.squareup.okhttp3:okhttp:4.11.0")
        classpath("com.google.code.gson:gson:2.10.1")
    }
}

// 导入需要的类
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import com.google.gson.Gson
import com.google.gson.JsonObject

// 直接在脚本中配置路径
val projectPaths = mapOf(
    "app" to File(project(":app").projectDir, "src/main/res/").absolutePath,
    "common" to File(project(":common").projectDir, "src/main/res/").absolutePath,
    "baby_recyclerview" to File(project(":baby_recyclerview").projectDir, "src/main/res/").absolutePath,
)

val googleplayPaths = mapOf(
    "common" to File(project(":common").projectDir, "src/app_oversea/res/").absolutePath,
)

val languageMap = listOf("en", "zh-Hans", "zh-Hant", "ja", "ko")

// JSON 文件路径配置
val localJsonPath = File(project.rootProject.projectDir, "tools/language/output.json").absolutePath

/**
 * 从 Excel 生成多语言 JSON 文件任务
 */
tasks.register("generateLanguageJson") {
    group = "i18n"
    description = "从 Excel 生成多语言 JSON 文件"

    // 强制任务始终执行
    outputs.upToDateWhen { false }

    doLast {
        val pythonScript = File(project.rootProject.projectDir, "tools/language/language_converter.py")
        val excelFile = File(project.rootProject.projectDir, "tools/language/多语言对照表.xlsx")
        val outputFile = File(project.rootProject.projectDir, "tools/language/output.json")

        println("🔍 调试信息:")
        println("  Python脚本路径: ${pythonScript.absolutePath}")
        println("  Excel文件路径: ${excelFile.absolutePath}")
        println("  输出JSON路径: ${outputFile.absolutePath}")
        println("  Python脚本存在: ${pythonScript.exists()}")
        println("  Excel文件存在: ${excelFile.exists()}")

        if (!pythonScript.exists()) {
            println("❌ Python 脚本不存在: ${pythonScript.absolutePath}")
            return@doLast
        }

        if (!excelFile.exists()) {
            println("❌ Excel 文件不存在: ${excelFile.absolutePath}")
            return@doLast
        }

        try {
            println("开始从 Excel 生成 JSON 文件...")

            // 检查 Python 是否可用
            val pythonCheck = ProcessBuilder("python", "--version")
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val pythonCheckExitCode = pythonCheck.waitFor()
            if (pythonCheckExitCode != 0) {
                println("❌ Python 不可用，尝试使用 python3...")
                // 可以尝试使用 python 命令
            }

            val process = ProcessBuilder(
                "python",
                pythonScript.absolutePath
            )
                .directory(File(project.rootProject.projectDir, "tools/language"))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                println("✅ Python 脚本执行成功")

                // 检查文件是否真的生成了
                if (outputFile.exists()) {
                    println("✅ 多语言 JSON 文件生成成功")
                    println("📁 文件位置: ${outputFile.absolutePath}")
                    println("📊 文件大小: ${outputFile.length()} 字节")
                } else {
                    println("❌ Python 脚本执行成功，但未生成输出文件")
                    println("请检查 Python 脚本中的输出路径设置")
                }
            } else {
                println("❌ Python 脚本执行失败，退出码: $exitCode")
            }
        } catch (e: Exception) {
            println("❌ 执行 Python 脚本时出错: ${e.message}")
            e.printStackTrace()
        }
    }
}

/**
 * 从本地JSON文件生成多语言文件任务
 */
tasks.register("fetchInternationalLanguageList") {
    group = "i18n"
    description = "从本地JSON文件生成多语言strings.xml文件"

    // 强制任务始终执行
    outputs.upToDateWhen { false }
    doLast {
        println("开始从本地JSON文件生成多语言文件...")
        println("JSON文件路径: $localJsonPath")

        val jsonFile = File(localJsonPath)
        if (!jsonFile.exists()) {
            println("❌ JSON文件不存在: $localJsonPath")
            println("请先运行 generateLanguageJson 任务生成 JSON 文件")
            return@doLast
        }

        try {
            // 读取本地 JSON 文件
            val jsonText = jsonFile.readText(Charsets.UTF_8)
            println("✓ JSON文件读取成功")

            // 使用 Gson 解析 JSON 数据
            val gson = Gson()
            val json = gson.fromJson(jsonText, JsonObject::class.java)
            val data = json.getAsJsonObject("data")

            // 处理普通项目路径
            println("处理普通版本多语言文件...")
            projectPaths.forEach { (projectName, resDir) ->
                processProjectData(
                    projectName = projectName,
                    resDir = resDir,
                    data = data,
                    languageMap = languageMap,
                    isGooglePlay = false
                )
                println("✓ 完成项目: $projectName")
            }

            // 处理 Google Play 项目路径
            println("处理Google Play版本多语言文件...")
            googleplayPaths.forEach { (projectName, resDir) ->
                processProjectData(
                    projectName = projectName,
                    resDir = resDir,
                    data = data,
                    languageMap = languageMap,
                    isGooglePlay = true
                )
                println("✓ 完成项目: $projectName")
            }

            println("🎉 多语言文件生成完成！")

        } catch (e: Exception) {
            println("❌ 处理JSON文件时出错: ${e.message}")
            e.printStackTrace()
        }
    }
}

// 明确指定任务依赖关系：fetchInternationalLanguageList 必须在 generateLanguageJson 之后执行
tasks.named("fetchInternationalLanguageList") {
    mustRunAfter("generateLanguageJson")
}

/**
 * 完整的多语言更新流程任务
 */
tasks.register("updateAllLanguages") {
    group = "i18n"
    description = "完整的多语言更新流程：从Excel生成JSON，再生成Android资源文件"

    dependsOn("generateLanguageJson", "fetchInternationalLanguageList")

    doLast {
        println("🎉 完整的多语言更新流程已完成！")
    }
}



/**
 * 处理项目数据并生成 strings.xml 文件
 */
fun processProjectData(
    projectName: String,
    resDir: String,
    data: JsonObject,
    languageMap: List<String>,
    isGooglePlay: Boolean
) {
    val projectData = data.getAsJsonArray(projectName) ?: run {
        println("⚠️  项目 $projectName 没有多语言数据")
        return
    }

    // 删除原来的 string.xml 文件
    languageMap.forEach { langCode ->
        getValuesDirNames(langCode, isGooglePlay).forEach { valuesDirName ->
            val valuesDirPath = File(resDir, valuesDirName)
            if (valuesDirPath.exists()) {
                val stringsFile = File(valuesDirPath, "strings.xml")
                if (stringsFile.exists()) {
                    stringsFile.delete()
                }
            }
        }
    }

    // 生成新的 strings.xml 文件
    projectData.forEach { entry ->
        val codeKey = entry.asJsonObject.get("codeKey").asString
        val languages = entry.asJsonObject.getAsJsonObject("language")

        languageMap.forEach { langCode ->
            val languageValue = languages.get(langCode)?.asString
            if (languageValue != null) {
                getValuesDirNames(langCode, isGooglePlay).forEach { valuesDirName ->
                    val valuesDirPath = File(resDir, valuesDirName)
                    if (!valuesDirPath.exists()) {
                        valuesDirPath.mkdirs()
                    }
                    val stringsFile = File(valuesDirPath, "strings.xml")
                    if (!stringsFile.exists()) {
                        stringsFile.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
                    }
                    // 写入翻译项
                    var convertedString = convertToAndroidString(languageValue)
                    convertedString = convertedString.replace("%ld", "%d")
                    val convertedStr = escapeSingleQuote(convertedString)
                    stringsFile.appendText("    <string name=\"$codeKey\">$convertedStr</string>\n")
                }
            }
        }
    }

    // 完成后关闭所有文件资源
    languageMap.forEach { langCode ->
        getValuesDirNames(langCode, isGooglePlay).forEach { valuesDirName ->
            val valuesDirPath = File(resDir, valuesDirName)
            val stringsFile = File(valuesDirPath, "strings.xml")
            if (stringsFile.exists()) {
                stringsFile.appendText("</resources>\n")
            }
        }
    }
}

/**
 * 获取 values 目录名称
 *
 * 繁体中文历史上同时存在台湾与香港资源目录，因此统一由同一份 zh-Hant
 * 翻译生成两个目录，避免其中一个目录继续依赖手写 XML。
 */
fun getValuesDirNames(langCode: String, isGooglePlay: Boolean): List<String> {
    return when {
        isGooglePlay -> {
            when (langCode) {
                "en" -> listOf("values")
                "zh-Hant" -> listOf("values-zh-rTW", "values-zh-rHK")
                "zh-Hans" -> listOf("values-zh")
                else -> listOf("values-$langCode")
            }
        }
        else -> {
            when (langCode) {
                "zh-Hans" -> listOf("values")
                "zh-Hant" -> listOf("values-zh-rTW", "values-zh-rHK")
                else -> listOf("values-$langCode")
            }
        }
    }
}

/**
 * 转换字符串为 Android 格式
 */
fun convertToAndroidString(inputString: String): String {
    var counter = 0
    var result = inputString.replace(Regex("%@")) {
        counter++
        "%${counter}\$s"
    }
    // 转换 & 为 &amp;
    result = result.replace("&", "&amp;")
    return result
}

/**
 * 转义单引号
 */
fun escapeSingleQuote(inputString: String): String {
    return if (inputString.contains("'")) {
        // Android XML 中单引号只需要一个反斜杠；写两个会把反斜杠显示到界面上。
        inputString.replace("'", "\\'")
    } else {
        inputString
    }
}
