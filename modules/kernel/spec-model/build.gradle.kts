// kernel/spec-model —— OpenAPI 3.1 内部模型 ★ 零框架依赖纯库（IDE 插件可直接依赖 jar，§7.4 硬约束）
// 骨架阶段只定依赖方向；核心模型类 / 序列化 / 指纹见开发计划 B1–B3。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // Jackson 3 单运行时：coord 见 gradle/libs.versions.toml（tools.jackson，非 Jackson 2）
    // api(platform) — BOM 约束须随 api 依赖传播给消费者（spec-parser / spec-index / server），否则无版本解析失败
    api(platform(libs.jackson.bom))
    api(libs.jackson.databind)
    api(libs.jackson.yaml)
    api(libs.jackson.kotlin)

    testImplementation(platform(libs.spring.boot.dependencies)) // 仅作版本约束
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter) // 版本由 BOM（6.0.3）管
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}