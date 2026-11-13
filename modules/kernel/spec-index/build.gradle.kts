// kernel/spec-index —— SQLite(sqlite-jdbc) 索引 DAO（真源的可重建投影，§6.4 决策 7）
// 骨架阶段只定依赖方向；表结构 / 唯一键 / api↔schema 引用见开发计划 E4。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":kernel:spec-model"))

    implementation(libs.sqlite.jdbc)

    testImplementation(project(":kernel:spec-model"))
    testImplementation(platform(libs.spring.boot.dependencies)) // 仅作版本约束
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter) // 版本由 BOM（6.0.3）管
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}