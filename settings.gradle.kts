rootProject.name = "spechub"

// ── 仓库策略 ──────────────────────────────────────────────
// Maven Central / Gradle Plugin Portal 2026-11-14 实测可达，为主仓库；
// 阿里云 / 华为云镜像作兜底——旧代码曾记录「直连被 Sonatype 403」（见 git 历史 settings），
// 若环境再次生效，镜像仍可服务（Gradle 按顺序回退）。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven(url = uri("https://maven.aliyun.com/repository/gradle-plugin"))
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven(url = uri("https://maven.aliyun.com/repository/public"))
        maven(url = uri("https://repo.huaweicloud.com/repository/maven"))
    }
}

// ── JVM 侧（依赖方向：一切只朝 kernel，见 §7.4 硬约束）──
// include 简写路径，projectDir 重映射到 modules/ 目录；JVM 模块一律放 modules/ 下。
// 说明：包含嵌套路径（:kernel:spec-model）时，父级 :kernel 也须显式映射目录（Gradle 9 要求实际目录存在）。
include(":kernel")
project(":kernel").projectDir = file("modules/kernel")

include(":kernel:spec-model", ":kernel:spec-parser", ":kernel:git-store", ":kernel:spec-index")
include(":server")

project(":kernel:spec-model").projectDir = file("modules/kernel/spec-model")
project(":kernel:spec-parser").projectDir = file("modules/kernel/spec-parser")
project(":kernel:git-store").projectDir = file("modules/kernel/git-store")
project(":kernel:spec-index").projectDir = file("modules/kernel/spec-index")
project(":server").projectDir = file("modules/server")