pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // 国内镜像优先：部分依赖（如 kotlin-compiler-embeddable）在 Maven Central
        // 会 302 跳转到 GitHub Releases，国内网络常连接超时导致构建失败。
        // 镜像站直接提供文件本体，构建更稳定；海外网络下同样可用。
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
    }
}

rootProject.name = "CheckInApp"
include(":app")
