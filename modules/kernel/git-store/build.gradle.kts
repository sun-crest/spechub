// kernel/git-store —— 系统 git CLI 子进程封装（clone/fetch/commit/push/diff，见 §7 选型表）
// 零第三方依赖（只驱动系统 git CLI）；骨架阶段只定依赖方向，实现见开发计划 D1–D4。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(platform(libs.spring.boot.dependencies)) // 仅作版本约束
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter) // 版本由 BOM（6.0.3）管
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}