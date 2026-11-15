// modules/mock —— Mock 规则引擎（I3，§7.4：依赖 kernel 的独立模块，部署并入 backend 进程）
// 依赖方向唯一规则：只允许朝 kernel；本模块零 Spring，由 server 装配调用（MVC + 虚拟线程，见 §7 选型）。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":kernel:spec-model"))
    implementation(project(":kernel:spec-index"))

    testImplementation(project(":kernel:spec-model"))
    testImplementation(project(":kernel:spec-index"))
    testImplementation(project(":kernel:spec-parser"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter) // 版本由 BOM（6.0.3）管
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}