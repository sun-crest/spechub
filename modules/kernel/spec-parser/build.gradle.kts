// kernel/spec-parser —— 多版本归一化：2.0/3.0/3.1 → 内部 spec-model（§6.5）
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":kernel:spec-model"))
    implementation(libs.swagger.parser)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}