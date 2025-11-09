pluginManagement {
    repositories {
        // 阿里云 Gradle 插件镜像
        maven {
            url = uri("https://maven.aliyun.com/repository/gradle-plugin/")
        }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugin/") }
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        // 阿里云公共仓库
        maven {
            url = uri("https://maven.aliyun.com/repository/public/")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        // ====== 阿里云镜像 ======
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven {
            url = uri("https://maven.aliyun.com/repository/central/")
        }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/spring") }
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }

        // ====== 清华大学镜像 ======
        maven { url = uri("https://mirrors.tuna.tsinghua.edu.cn/maven/") }

        // ====== 华为云镜像 ======
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }

        // ====== 腾讯云镜像 ======
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }


        maven {
            url = uri("https://jitpack.io")
            credentials {
                username = "nodrinknoback@gmail.com"
                password = "h123581347"
            }

        }
        google()
        mavenCentral()

    }
}

rootProject.name = "babycare"
include(":app")
include(":babydata")
include(":common")
include(":components")
include(":baby_recyclerview")
