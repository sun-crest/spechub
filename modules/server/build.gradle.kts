// modules/server —— Spring Boot 4：REST API / remote MCP / webhook 摄取 / 导出子进程
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":kernel:spec-model"))
    implementation(project(":kernel:spec-parser"))
    implementation(project(":kernel:git-store"))
    implementation(project(":kernel:spec-index"))

    implementation(platform(libs.spring.boot.dependencies))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}