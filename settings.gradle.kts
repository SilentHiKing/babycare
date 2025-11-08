pluginManagement {
    repositories {
        // 阿里云 Gradle 插件镜像
        maven {
            url = uri("https://maven.aliyun.com/repository/gradle-plugin/")
        }
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
        // 阿里云 Google 仓库（Android相关）
        maven {
            url = uri("https://maven.aliyun.com/repository/google/")
        }
        // 阿里云公共仓库
        maven {
            url = uri("https://maven.aliyun.com/repository/public/")
        }
        // 阿里云 Central 仓库
        maven {
            url = uri("https://maven.aliyun.com/repository/central/")
        }

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
