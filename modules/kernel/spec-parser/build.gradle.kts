// kernel/spec-parser —— 自研 OpenAPI 解析：3.0/3.1 → 内部 3.1 归一化（Jackson 3 / tools.jackson，§6.5）
// 骨架阶段只定依赖方向；解析 / 升级映射 / 变更日志 / 容错见开发计划 C1–C4。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":kernel:spec-model"))

    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
    implementation(libs.jackson.kotlin)

    testImplementation(project(":kernel:spec-model"))
    testImplementation(platform(libs.spring.boot.dependencies)) // 仅作版本约束
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter) // 版本由 BOM（6.0.3）管
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}