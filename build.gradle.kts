// SpecHub 根构建：统一 group/version + JVM 侧公共编译目标（Java 25 LTS）。
// 插件只在根声明（apply false）做版本对齐，各模块自行 alias；依赖方向见 §7.4。
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.ktlint) apply false
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

        // ── R12：ktlint 静态检查（规范 §11）──
        // 只在 Kotlin 模块挂载；插件默认把 ktlintCheck 接进 check。格式规则见根 .editorconfig。
        // 引擎版本与插件版本分列 libs.versions.toml（ktlintGradle / ktlintEngine）。
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set(libs.versions.ktlintEngine.get())
        }
    }
    // 测试 JVM 释放 native access（sqlite-jdbc 在 JDK 25 的 restricted 加载警告消隐）
    tasks.withType<Test>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }

    // ── Jackson 2 使用边界（R11 前置约束 · 2026-11-17）──
    // 业务代码只允许 Jackson 3（tools.jackson）；Jackson 2 仅允许作为 springdoc / knife4j next
    // 的运行时传递依赖存在。这里从「编译期类路径」移除 Jackson 2 的 databind/core 等实现包，
    // 使业务代码误 import 时直接编译失败，而不是等到运行期才出现两套 ObjectMapper 行为差异。
    // 例外：Jackson 3 仍复用 2.x 的 com.fasterxml.jackson.annotation（Jackson 官方 JSTEP-1），
    // annotations 不在排除列表内；kernel/spec-model 的 @JsonProperty/@JsonInclude 依赖此例外。
    configurations.matching { it.name == "compileClasspath" || it.name == "testCompileClasspath" }.configureEach {
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-databind")
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-core")
        exclude(group = "com.fasterxml.jackson.dataformat")
        exclude(group = "com.fasterxml.jackson.datatype")
        exclude(group = "com.fasterxml.jackson.module")
    }
    // check 挂上源码级校验（不依赖 ktlint/detekt，R12 落地后可迁移为规则）
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(":verifyNoJackson2Usage")
    }
}

// ── Jackson 2 源码校验：业务源码禁止出现 com.fasterxml.jackson.*（annotations 除外）──
// 依赖层的隔离由上面的 compileClasspath exclude 兜底；本任务覆盖「全限定名直接用」「测试代码」
// 以及未来可能被 IDE 自动导入的漏网场景。注释行跳过，避免文档性反向示例误报。
val jackson2UsagePattern = Regex("""com\.fasterxml\.jackson\.(?!annotation\.)""")

tasks.register("verifyNoJackson2Usage") {
    group = "verification"
    description = "禁止业务源码使用 Jackson 2（com.fasterxml.jackson.annotation 例外；Jackson 2 仅供文档层运行时传递）"

    val sources = fileTree(rootDir) {
        include("modules/**/src/main/**/*.kt", "modules/**/src/main/**/*.java")
        include("modules/**/src/test/**/*.kt", "modules/**/src/test/**/*.java")
        exclude("**/build/**")
    }
    inputs.files(sources).withPropertyName("sources")

    doLast {
        val violations = sources.flatMap { file ->
            file.readLines().withIndex().mapNotNull { (index, line) ->
                val trimmed = line.trim()
                val isComment = trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
                if (!isComment && jackson2UsagePattern.containsMatchIn(line)) {
                    "${file.relativeTo(rootDir)}:${index + 1}: $trimmed"
                } else {
                    null
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Jackson 2 使用边界违规：业务代码只允许 tools.jackson（Jackson 3），仅 com.fasterxml.jackson.annotation 例外。")
                    violations.forEach { appendLine("  - $it") }
                    appendLine("修复：改用 tools.jackson.*；springdoc / knife4j next 以 runtimeOnly 引入，端点注解用 compileOnly 的 swagger-annotations-jakarta。")
                },
            )
        }
    }
}
