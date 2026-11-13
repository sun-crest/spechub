// SpecHub 根构建：统一 group/version + JVM 侧公共编译目标（Java 25 LTS）。
// 插件只在根声明（apply false）做版本对齐，各模块自行 alias；依赖方向见 §7.4。
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "io.spechub"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    // JVM 侧统一 Java 25（最新 LTS）：toolchain 锁定 jvmTarget/javac，避免本机默认 JDK 漂移
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(25)
        }
    }
}