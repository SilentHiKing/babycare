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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Interceptor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.io.File
import java.util.Date
import java.text.SimpleDateFormat
import com.google.gson.Gson
import com.google.gson.JsonObject

// 默认配置
val defaultProjectPaths = mapOf(
    "FDNotepad" to File(project.rootProject.projectDir, "common/src/main/res/").absolutePath,
)

val defaultGoogleplayPaths = mapOf(
    "FDNotepad" to File(project.rootProject.projectDir, "common/src/app_oversea/res/").absolutePath,
)

val defaultLanguageMap = listOf("en", "zh-Hans", "zh-Hant", "ja", "ko")

// 存储配置的扩展属性
project.extensions.extraProperties.set("i18nProjectPaths", defaultProjectPaths)
project.extensions.extraProperties.set("i18nGoogleplayPaths", defaultGoogleplayPaths)
project.extensions.extraProperties.set("i18nLanguageMap", defaultLanguageMap)

/**
 * 配置多语言任务的函数
 */
fun i18nConfig(block: I18nConfig.() -> Unit) {
    val config = I18nConfig(project)
    block(config)

    // 更新配置
    project.extensions.extraProperties.set("i18nProjectPaths", config.projectPaths)
    project.extensions.extraProperties.set("i18nGoogleplayPaths", config.googleplayPaths)
    project.extensions.extraProperties.set("i18nLanguageMap", config.languageMap)
}

/**
 * 配置类
 */
class I18nConfig(private val project: org.gradle.api.Project) {
    var projectPaths: Map<String, String> = defaultProjectPaths
    var googleplayPaths: Map<String, String> = defaultGoogleplayPaths
    var languageMap: List<String> = defaultLanguageMap

    fun projectPaths(block: MutableMap<String, String>.() -> Unit) {
        val map = projectPaths.toMutableMap()
        block(map)
        projectPaths = map
    }

    fun googleplayPaths(block: MutableMap<String, String>.() -> Unit) {
        val map = googleplayPaths.toMutableMap()
        block(map)
        googleplayPaths = map
    }

    fun languages(vararg languages: String) {
        languageMap = languages.toList()
    }
}

/**
 * 生成多语言文件任务
 */
tasks.register("fetchInternationalLanguageList") {
    group = "i18n"
    description = "从API获取多语言数据并生成strings.xml文件"

    doLast {
        println("开始获取多语言数据...")

        // 构建 OkHttpClient
        val client = createOkHttpClient()

        val apiUrl = "https://1kb5on37ga.execute-api.us-east-2.amazonaws.com/default/free_notes_international_list"
        val request = Request.Builder()
            .url(apiUrl)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                response.body?.bytes()?.let { responseBody ->
                    val jsonText = getData(responseBody)

                    // 保存 JSON 数据到本地文件
                    val outputDir = File(project.buildDir, "i18n")
                    if (!outputDir.exists()) {
                        outputDir.mkdirs()
                    }
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
                    val outputFile = File(outputDir, "international_language_data_$timestamp.json")
                    outputFile.writeText(jsonText, Charsets.UTF_8)
                    println("✓ JSON数据已保存到: ${outputFile.absolutePath}")

                    // 使用 Gson 解析 JSON 数据
                    val gson = Gson()
                    val json = gson.fromJson(jsonText, JsonObject::class.java)
                    val data = json.getAsJsonObject("data")

                    // 获取配置
                    val normalPaths = project.extensions.extraProperties.get("i18nProjectPaths") as Map<String, String>
                    val googleplayPaths = project.extensions.extraProperties.get("i18nGoogleplayPaths") as Map<String, String>
                    val languageMap = project.extensions.extraProperties.get("i18nLanguageMap") as List<String>

                    // 处理普通项目路径
                    println("处理普通版本多语言文件...")
                    normalPaths.forEach { (projectName, resDir) ->
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
                }
            } else {
                println("❌ 获取数据失败. Response Code: ${response.code}")
            }
        }
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
        val valuesDirPath = File(resDir, getValuesDirName(langCode, isGooglePlay))
        if (valuesDirPath.exists()) {
            val stringsFile = File(valuesDirPath, "strings.xml")
            if (stringsFile.exists()) {
                stringsFile.delete()
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
                val valuesDirPath = File(resDir, getValuesDirName(langCode, isGooglePlay))
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

    // 完成后关闭所有文件资源
    languageMap.forEach { langCode ->
        val valuesDirPath = File(resDir, getValuesDirName(langCode, isGooglePlay))
        val stringsFile = File(valuesDirPath, "strings.xml")
        if (stringsFile.exists()) {
            stringsFile.appendText("</resources>\n")
        }
    }
}

/**
 * 获取 values 目录名称
 */
fun getValuesDirName(langCode: String, isGooglePlay: Boolean): String {
    return when {
        isGooglePlay -> {
            when (langCode) {
                "en" -> "values"
                "zh-Hant" -> "values-zh-rTW"
                "zh-Hans" -> "values-zh"
                else -> "values-$langCode"
            }
        }
        else -> {
            when (langCode) {
                "zh-Hans" -> "values"
                "zh-Hant" -> "values-zh-rTW"
                else -> "values-$langCode"
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
        inputString.replace("'", "\\\\'")
    } else {
        inputString
    }
}

/**
 * 解密数据
 */
fun getData(value: ByteArray): String {
    return try {
        val secretCode = "GQ9i7702h0uoMNSIghy3CGDUGpDbkAjT"
        val keySpec = SecretKeySpec(secretCode.toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec)
        val result = cipher.doFinal(value)
        String(result, Charsets.UTF_8)
    } catch (e: Exception) {
        println("❌ 解密错误: ${e.message}")
        ""
    }
}

/**
 * 创建 OkHttpClient 实例
 */
fun createOkHttpClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("content-type", "application/octet-stream")
                .method(original.method, original.body)
                .build()
            chain.proceed(request)
        })
        .connectTimeout(100, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()
}