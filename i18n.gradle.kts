/**
 * 多语言资源生成任务。
 *
 * Excel 是唯一源数据；所有输入先完成解析与校验，随后逐文件原子替换，避免失败时留下半份 XML。
 */

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

buildscript {
    repositories {
        mavenCentral()
        google()
    }

    dependencies {
        classpath("com.google.code.gson:gson:2.10.1")
    }
}

val projectPaths = mapOf(
    "app" to File(project(":app").projectDir, "src/main/res").absolutePath,
    "common" to File(project(":common").projectDir, "src/main/res").absolutePath,
    "baby_recyclerview" to File(project(":baby_recyclerview").projectDir, "src/main/res").absolutePath,
)

val googlePlayPaths = mapOf(
    "common" to File(project(":common").projectDir, "src/app_oversea/res").absolutePath,
)

val languageMap = listOf("en", "zh-Hans", "zh-Hant", "ja", "ko")
val localJsonFile = File(project.rootProject.projectDir, "tools/language/output.json")

val generateLanguageJson = tasks.register("generateLanguageJson") {
    group = "i18n"
    description = "从 Excel 生成并校验多语言 JSON"

    val pythonScript = File(project.rootProject.projectDir, "tools/language/language_converter.py")
    val excelFile = File(project.rootProject.projectDir, "tools/language/多语言对照表.xlsx")
    inputs.files(pythonScript, excelFile)
    outputs.file(localJsonFile)

    doLast {
        requireInputFile(pythonScript, "Python 脚本")
        requireInputFile(excelFile, "多语言 Excel")

        val pythonPrefix = findPythonCommand()
            ?: throw GradleException("未找到可用的 Python 3，请安装后重试")
        val command = pythonPrefix + pythonScript.absolutePath
        val exitCode = ProcessBuilder(command)
            .directory(pythonScript.parentFile)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor()

        if (exitCode != 0) {
            throw GradleException("多语言 JSON 生成失败，Python 退出码：$exitCode")
        }
        if (!localJsonFile.isFile || localJsonFile.length() == 0L) {
            throw GradleException("Python 执行完成但未生成有效 JSON：${localJsonFile.absolutePath}")
        }
        println("多语言 JSON 已生成：${localJsonFile.absolutePath}")
    }
}

val fetchInternationalLanguageList = tasks.register("fetchInternationalLanguageList") {
    group = "i18n"
    description = "校验本地 JSON 并原子生成 Android strings.xml"
    dependsOn(generateLanguageJson)
    inputs.file(localJsonFile)
    outputs.upToDateWhen { false }

    doLast {
        requireInputFile(localJsonFile, "多语言 JSON")
        val root = try {
            Gson().fromJson(localJsonFile.readText(Charsets.UTF_8), JsonObject::class.java)
        } catch (exception: Exception) {
            throw GradleException("多语言 JSON 解析失败", exception)
        }
        val data = root?.get("data")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: throw GradleException("多语言 JSON 缺少 data 对象")

        // 全部内容先在内存中生成并完成校验，确认无误后才触碰现有资源文件。
        val generatedFiles = linkedMapOf<File, String>()
        projectPaths.forEach { (projectName, resDir) ->
            generatedFiles.putAll(
                buildProjectFiles(projectName, resDir, data, languageMap, isGooglePlay = false)
            )
        }
        googlePlayPaths.forEach { (projectName, resDir) ->
            generatedFiles.putAll(
                buildProjectFiles(projectName, resDir, data, languageMap, isGooglePlay = true)
            )
        }

        writeFilesAtomically(generatedFiles)
        println("多语言资源生成完成，共更新 ${generatedFiles.size} 个 strings.xml")
    }
}

tasks.register("updateAllLanguages") {
    group = "i18n"
    description = "从 Excel 完整刷新所有 Android 多语言资源"
    dependsOn(fetchInternationalLanguageList)
}

fun requireInputFile(file: File, label: String) {
    if (!file.isFile) {
        throw GradleException("$label 不存在：${file.absolutePath}")
    }
}

/** 查找可用的 Python 3 启动方式，Windows 同时支持 py -3。 */
fun findPythonCommand(): List<String>? {
    val candidates = listOf(listOf("python"), listOf("python3"), listOf("py", "-3"))
    return candidates.firstOrNull { prefix ->
        runCatching {
            ProcessBuilder(prefix + "--version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor() == 0
        }.getOrDefault(false)
    }
}

/** 将一个 sheet 转换为目标模块的所有语言文件；任一缺失翻译都会中止整次生成。 */
fun buildProjectFiles(
    projectName: String,
    resDir: String,
    data: JsonObject,
    languageMap: List<String>,
    isGooglePlay: Boolean
): Map<File, String> {
    val projectElement = data.get(projectName)
        ?: throw GradleException("多语言 JSON 缺少项目：$projectName")
    if (!projectElement.isJsonArray) {
        throw GradleException("项目 $projectName 的多语言数据不是数组")
    }

    val outputs = linkedMapOf<File, StringBuilder>()
    languageMap.forEach { langCode ->
        getValuesDirNames(langCode, isGooglePlay).forEach { valuesDirName ->
            val target = File(File(resDir, valuesDirName), "strings.xml")
            outputs[target] = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
        }
    }

    val seenKeys = mutableSetOf<String>()
    projectElement.asJsonArray.forEachIndexed { index, entry ->
        if (!entry.isJsonObject) {
            throw GradleException("项目 $projectName 第 ${index + 1} 项不是对象")
        }
        val item = entry.asJsonObject
        val codeKey = item.get("codeKey")?.asString?.trim().orEmpty()
        if (codeKey.isEmpty()) {
            throw GradleException("项目 $projectName 第 ${index + 1} 项缺少 codeKey")
        }
        if (!seenKeys.add(codeKey)) {
            throw GradleException("项目 $projectName 存在重复 codeKey：$codeKey")
        }
        val languages = item.get("language")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: throw GradleException("$projectName/$codeKey 缺少 language 对象")

        languageMap.forEach { langCode ->
            val languageElement = languages.get(langCode)
                ?: throw GradleException("$projectName/$codeKey 缺少 $langCode 翻译")
            if (languageElement.isJsonNull) {
                throw GradleException("$projectName/$codeKey 的 $langCode 翻译为空")
            }
            val value = languageElement.asString
            val androidValue = escapeSingleQuote(
                convertToAndroidString(value).replace("%ld", "%d")
            )
            getValuesDirNames(langCode, isGooglePlay).forEach { valuesDirName ->
                val target = File(File(resDir, valuesDirName), "strings.xml")
                outputs.getValue(target)
                    .append("    <string name=\"")
                    .append(codeKey)
                    .append("\">")
                    .append(androidValue)
                    .append("</string>\n")
            }
        }
    }

    if (seenKeys.isEmpty()) {
        throw GradleException("项目 $projectName 没有可生成的多语言条目")
    }
    return outputs.mapValues { (_, builder) -> builder.append("</resources>\n").toString() }
}

/** 所有临时文件写完后再原子替换正式文件，生成异常不会破坏上一份可用资源。 */
fun writeFilesAtomically(outputs: Map<File, String>) {
    val stagedFiles = linkedMapOf<File, File>()
    try {
        outputs.forEach { (target, content) ->
            target.parentFile.mkdirs()
            val staged = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
            staged.writeText(content, Charsets.UTF_8)
            stagedFiles[target] = staged
        }
        stagedFiles.forEach { (target, staged) ->
            try {
                Files.move(
                    staged.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    } finally {
        stagedFiles.values.forEach { staged ->
            if (staged.exists()) staged.delete()
        }
    }
}

fun getValuesDirNames(langCode: String, isGooglePlay: Boolean): List<String> {
    return when {
        isGooglePlay -> when (langCode) {
            "en" -> listOf("values")
            "zh-Hant" -> listOf("values-zh-rTW", "values-zh-rHK")
            "zh-Hans" -> listOf("values-zh")
            else -> listOf("values-$langCode")
        }
        else -> when (langCode) {
            "zh-Hans" -> listOf("values")
            "zh-Hant" -> listOf("values-zh-rTW", "values-zh-rHK")
            else -> listOf("values-$langCode")
        }
    }
}

fun convertToAndroidString(input: String): String {
    var placeholderIndex = 0
    return input
        .replace(Regex("%@")) {
            placeholderIndex += 1
            "%${placeholderIndex}\$s"
        }
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

fun escapeSingleQuote(input: String): String {
    return input.replace("'", "\\'")
}
