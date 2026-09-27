pluginManagement {
    // 国内镜像**只在本机开发时启用**：GitHub Actions 会自动设置 CI=true。
    // 原因：Gradle 把仓库返回的 401/403 视为致命错误并直接终止构建，而不是
    // "该仓库没有此构件就换下一个"。海外 runner 访问 maven.aliyun.com 可能被拒，
    // 表现为构建刚开始（几十秒内）就 exit 1 —— 本地却完全正常，极难排查。
    // 镜像的作用是绕过国内访问 Maven Central / GitHub Releases 的超时，海外 runner
    // 不存在这个问题，因此 CI 上只用官方仓库。
    val useAliyunMirror = System.getenv("CI").isNullOrBlank()
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
        // 镜像站直接提供文件本体，构建更稳定。
        if (useAliyunMirror) {
            maven("https://maven.aliyun.com/repository/central")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        if (System.getenv("CI").isNullOrBlank()) {
            maven("https://maven.aliyun.com/repository/central")
        }
        mavenCentral()
    }
}

rootProject.name = "CheckInApp"
include(":app")
