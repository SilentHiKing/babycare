pluginManagement {
    repositories {
        // 国内镜像优先，提升大陆网络环境下的插件解析稳定性。
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin/") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugin/") }
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        maven { url = uri("https://maven.aliyun.com/repository/public/") }

        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像放在官方仓库之前，解析失败时仍可回退到官方源。
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central/") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/spring") }
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
        maven { url = uri("https://mirrors.tuna.tsinghua.edu.cn/maven/") }
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }

        google()
        mavenCentral()

        // JitPack 仅允许解析项目实际使用的 XPopup 依赖，避免扩大制品来源范围。
        exclusiveContent {
            forRepository {
                maven {
                    name = "JitPack"
                    url = uri("https://jitpack.io")
                }
            }
            filter {
                includeGroup("com.github.li-xiaojun")
            }
        }

    }
}

rootProject.name = "babycare"
include(":app")
include(":babydata")
include(":common")
include(":components")
include(":baby_recyclerview")
