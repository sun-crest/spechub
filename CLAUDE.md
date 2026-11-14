# SpecHub · 项目工作规则（随仓库共享）

> 本文件随仓库分发，是跨协作者（人 / Claude / CI）共享的项目规则。本机专属的会话环境适配
> （如纯文本模型约束）不在此文件，见被 `.gitignore` 忽略的 `.claude/CLAUDE.md`（若本机存在）。

## Kotlin 可空性与 Java 互操作（方案 A · 2026-11-15 实证落地）

JVM 侧 Kotlin 编译统一开启 `-Xjsr305=strict -Xspecify-annotations=strict -Xtype-use=specify`
（root `build.gradle.kts` 一处统一配置，勿在各模块重复声明）。写码纪律（已实测，行为勿当 bug 修）：

1. **带 JSpecify / JSR-305 null 元数据的 Java API**：编译器严格按标注强制类型——`@Nullable` 必须按可空接
   （逆着接编译不过）；`@NullMarked` 作用域内未标注成员按非空，可放心非空直收。
2. **未标注可空性的 Java API**（典型：Jackson 3 树系 `JsonNode.get/findValue/path` 的返回值，
   以及大多数不讲 JSpecify 的三方库方法）：编译保持 platform type、不受强制——**一律按 `Type?`
   从严接收**（`?.` + 默认值，必要处 `checkNotNull`），禁止以非空类型直接接收。
   当前范例：`SpecParser.detect` 的 `tree.get("openapi")?.asString()`。
3. **`convertValue` / `readValue` 进 Kotlin data class 的路径不在此约束内**：空安全由 data class 声明的
   可空/默认值主导——缺缺席可选字段填默认值；缺必填或显式 null 打非空字段 fail-fast（`KotlinInvalidNullException`）。
4. **禁写多余的 `!!`**：strict 会把「编译器已确认非空」的值上的 `!!` 报 `Unnecessary non-null assertion`
   warning——表达「应有但缺失须带信息失败」用 `checkNotNull(x) { "原因" }`。

> 机制注释与依赖树核对（为何无需按注解库逐个加参数：JSR-305 / JSpecify / type-use / JetBrains 内置
> 四通道的族与库对应）见 root `build.gradle.kts` 的 compilerOptions 块。