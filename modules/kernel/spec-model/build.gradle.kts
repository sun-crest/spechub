// kernel/spec-model —— OpenAPI 3.1 内部模型（★ 零框架依赖纯库，IDE 插件可直接依赖）
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.kotlin)
    api(libs.jackson.yaml)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}