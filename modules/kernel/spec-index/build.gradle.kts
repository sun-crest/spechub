// kernel/spec-index —— SQLite(sqlite-jdbc) 索引 DAO：可重建投影（§6.4 决策 7）
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":kernel:spec-model"))
    implementation(libs.sqlite.jdbc)

    testImplementation(kotlin("test"))
    testImplementation(project(":kernel:spec-parser"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}