// SpecHub 根构建：统一 group/version + JVM 侧公共编译目标（Java 25 LTS）。
// 插件只在根声明（apply false）做版本对齐，各模块自行 alias；依赖方向见 §7.4。
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "io.spechub"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    // JVM 侧统一 Java 25（最新 LTS）：toolchain 锁定 jvmTarget/javac，避免本机默认 JDK 漂移
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(25)

            // ── Java 可空元数据的编译器级强制（方案 A · 2026-11-15 实证 + 依赖树核对，勿当 bug 修）──
            // 三选项按「语义族」覆盖，不按注解库逐个配：
            //  -Xjsr305=strict                      JSR-305 族：javax.annotation @Nullable/@NonNull，
            //                                       以及带 JSR-305 TypeQualifierNickname 元标注的库；
            //  -Xspecify-annotations=strict         JSpecify 族（org.jspecify.annotations）——Boot 4 /
            //                                       Spring 7 主体的通道（非 JSR-305，二者互补）；
            //  -Xtype-use=specify                   泛型参数位置的可空标注（List<@Nullable T> 之类）。
            // org.jetbrains.annotations 是编译器内置通道、天然恒严，无对应开关也不需要。
            // 语义要点：
            //  - @NullMarked 作用域内：未标注成员按非空、@Nullable 按可空，逆着接=编译错误；
            //  - 无 @NullMarked 的库（Jackson 树系等）：保持 platform type 原有自由度——「未标注
            //    Java API 一律按 Type? 从严接」仍是代码规范，编译器不强制；
            //  - convertValue 进 Kotlin data class 的路径空安全由声明主导，与此无关。
            // 依赖树核对（2026-11-15 实证，决定「无需再加参数」）：kernel compileClasspath 仅
            // org.jetbrains:annotations；server runtimeClasspath 含 org.jspecify:jspecify 1.0.1
            //（Spring 7 传递）+ org.jetbrains:annotations + jakarta.annotation-api（非可空契约）。
            // 全链无 findbugs-jsr305 / spotbugs；spotbugs 属非 JSR-305 元谱系、默认不被识别，
            // 未来若引入按「未标注按可空」兜底即可，勿预先堆参数。
            // strict 顺带把多余的 !! 暴露成 warning（不阻断）。写码纪律见 .claude/CLAUDE.md。
            compilerOptions {
                freeCompilerArgs.addAll(
                    "-Xjsr305=strict",
                    "-Xspecify-annotations=strict",
                    "-Xtype-use=specify",
                )
            }
        }
    }
    // 测试 JVM 释放 native access（sqlite-jdbc 在 JDK 25 的 restricted 加载警告消隐）
    tasks.withType<Test>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}