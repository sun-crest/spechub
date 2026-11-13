// modules/server —— Spring Boot 4 聚合层：REST / 摄取（webhook+轮询）/ mock / MCP remote / 导出子进程
// 骨架阶段：最小可启动空壳（A4 backend 可构建可 up）；SPECHUB_* 配置绑定与单测基建见开发计划 E1。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

dependencies {
    // 内核（依赖方向唯一规则：只允许朝 kernel，见 §7.4）—— A 阶段先行声明，E3 摄取主线用上
    implementation(project(":kernel:spec-model"))
    implementation(project(":kernel:spec-parser"))
    implementation(project(":kernel:git-store"))
    implementation(project(":kernel:spec-index"))

    implementation(platform(libs.spring.boot.dependencies))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator") // /actuator/health → compose healthcheck

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter) // 版本由 BOM（6.0.3）管
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.bootJar {
    archiveFileName.set("spechub-server.jar") // compose/backend/Dockerfile 固定引用此名
}