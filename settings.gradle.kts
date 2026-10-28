import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        // GitHub 托管运行器使用官方源，避免国内镜像缺失或跨境访问失败。
        if (System.getenv("GITHUB_ACTIONS") == "true") {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            // 本地继续优先使用国内镜像。
            maven {
                name = "AliyunGoogle"
                url = uri("https://maven.aliyun.com/repository/google")
            }
            maven {
                name = "AliyunCentral"
                url = uri("https://maven.aliyun.com/repository/central")
            }
            maven {
                name = "AliyunGradlePlugin"
                url = uri("https://maven.aliyun.com/repository/gradle-plugin")
            }
            // 镜像未同步插件时允许从官方源补齐。
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("GITHUB_ACTIONS") == "true") {
            google()
            mavenCentral()
        } else {
            maven {
                name = "AliyunGoogle"
                url = uri("https://maven.aliyun.com/repository/google")
            }
            maven {
                name = "AliyunCentral"
                url = uri("https://maven.aliyun.com/repository/central")
            }
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "BYDMateCN"
include(":app")
include(":bydauto-stubs")
