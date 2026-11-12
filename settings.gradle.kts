rootProject.name = "spechub"

// 说明：当前部署网络环境对 Maven Central (repo.maven.apache.org) 直接访问被 Sonatype 403 封禁，
// 因此依赖与插件仓库统一走同步镜像（阿里云公共 + 华为云兜底），不配置 mavenCentral()。
pluginManagement {
    repositories {
        maven(url = uri("https://maven.aliyun.com/repository/gradle-plugin"))
        maven(url = uri("https://maven.aliyun.com/repository/public"))
    }
}

dependencyResolutionManagement {
    repositories {
        maven(url = uri("https://maven.aliyun.com/repository/public"))
        maven(url = uri("https://maven.aliyun.com/repository/central"))
        maven(url = uri("https://repo.huaweicloud.com/repository/maven"))
    }
}

// ── JVM 侧（依赖方向：一切只朝 kernel 依赖，见 §7.4）──
// 路径约定：JVM 模块一律放 modules/ 下；projectDir 映射使 include 路径与目录一致。
include(":kernel")
project(":kernel").projectDir = file("modules/kernel")

val jvmModules = listOf(
    "kernel:spec-model" to "modules/kernel/spec-model",
    "kernel:spec-parser" to "modules/kernel/spec-parser",
    "kernel:git-store" to "modules/kernel/git-store",
    "kernel:spec-index" to "modules/kernel/spec-index",
    "server" to "modules/server",
)

jvmModules.forEach { (path, dir) ->
    include(":$path")
    project(":$path").projectDir = file(dir)
}