package io.spechub.server.config

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info

/*
 * R11 —— OpenAPI 文档元信息（springdoc 3.x / Knife4j Next 扫描）。
 *
 * 端点级 @Operation/@Tag 按 R11 计划逐步补；本类只提供全局 title/version/description。
 * 注解来自 compileOnly 的 swagger-annotations-jakarta，运行期由文档层 starter 提供；
 * 不把 Jackson 2 带进业务编译期（见 root CLAUDE.md「Jackson 2 使用边界」）。
 */
@OpenAPIDefinition(
    info = Info(
        title = "SpecHub API",
        version = "0.1.0",
        description = "接口契约真源平台：仓库 / 规格 / 评审 / 导入导出 / mock 的 HTTP API。",
    ),
)
class OpenApiConfig
