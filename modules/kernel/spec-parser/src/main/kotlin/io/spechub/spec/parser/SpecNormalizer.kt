package io.spechub.spec.parser

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.spechub.spec.model.OpenApiDoc

/**
 * 多版本归一化入口（§6.5）：2.0 / 3.0 / 3.1 → 内部 spec-model。
 *
 * 三方言采用两通道（升 3.0 / 直读 3.1）—— 这是本轮实现的真实边界：
 *  swagger-parser 2.1.x 的 Schema.type 是 String，**无法表达 3.1 的数组 type**（type: [string, "null"] 会丢成 null）。
 *  因此 3.1 走 spec-model 自身 Jackson 直读（内部模型即 3.1 语义），YAML/JSON 皆原生支持。
 *  2.0 / 3.0 走 swagger-parser 升级通道：2.0 自动完成 definitions→components、host/basePath→servers、
 *  consumes→content 等结构升级；差异字段（nullable、exclusive* 布尔→数值）由 OpenApiConverter 落成 3.1 语义并留痕。
 * 原始文件由摄取层原样保留在 gitsource，绝不回写重写；本步骤只产出内存模型 + 转换留痕。
 */
object SpecNormalizer {
    private val parser = io.swagger.parser.OpenAPIParser()

    /** 3.1 直读通道 —— 复用 spec-model 的 Jackson 序列化契约（YAML 是 JSON 超集，二者都能读）。 */
    private val yamlMapper: ObjectMapper =
        YAMLMapper.builder()
            .addModules(KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

    fun normalize(source: String): ParseResult {
        val version = detectSpecVersion(source)
        return try {
            when (version) {
                SpecVersion.V310 -> normalizeDirect3_1(source)
                SpecVersion.V2, SpecVersion.V300 -> normalizeViaSwaggerParser(source, version)
                SpecVersion.UNKNOWN -> ParseResult.Failure("无法识别 OpenAPI/Swagger 方言版本（缺 swagger:/openapi 头）", version)
            }
        } catch (e: Exception) {
            ParseResult.Failure(e.message ?: e.javaClass.simpleName, version)
        }
    }

    private fun normalizeViaSwaggerParser(source: String, version: SpecVersion): ParseResult {
        val result = parser.readContents(source, null, null)
        val api = result.openAPI
        if (api == null) {
            val msgs = result.messages?.joinToString("; ") ?: "解析失败（无诊断消息）"
            return ParseResult.Failure(msgs, version)
        }
        val (doc, notes) = OpenApiConverter.convert(api)
        val fullNotes = if (version == SpecVersion.V2) {
            listOf(ConversionNote("2.0 → 3.1 结构升级：definitions→components.schemas、host/basePath→servers、consumes→content（swagger-parser 自动完成）")) + notes
        } else {
            notes
        }
        return ParseResult.Success(doc, version, fullNotes)
    }

    private fun normalizeDirect3_1(source: String): ParseResult.Success {
        val doc = yamlMapper.readValue(source, OpenApiDoc::class.java)
        val notes = listOf(ConversionNote("3.1 原生直读（spec-model 契约），无版本级语义改动"))
        return ParseResult.Success(doc, SpecVersion.V310, notes)
    }
}