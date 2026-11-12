package io.spechub.spec.model

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpecModelTest {
    private val jsonMapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val yamlMapper: YAMLMapper = YAMLMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private fun sampleDoc() = OpenApiDoc(
        openapi = "3.1.1",
        info = Info(title = "用户服务", version = "1.0.0"),
        servers = listOf(Server(url = "https://api.example.com/v1")),
        paths = mapOf(
            "/users" to PathItem(
                operations = mapOf(
                    HttpMethod.GET to Operation(
                        operationId = "listUsers",
                        summary = "列出用户",
                        tags = listOf("users"),
                        parameters = listOf(
                            Parameter(name = "page", location = "query", schema = Schema.of("integer").copy(format = "int32"))
                        ),
                        responses = mapOf(
                            "200" to Response(
                                description = "OK",
                                content = mapOf(
                                    "application/json" to MediaType(
                                        schema = Schema.of("array").copy(items = Schema(ref = "#/components/schemas/User"))
                                    )
                                )
                            )
                        )
                    )
                )
            )
        ),
        components = Components(
            schemas = mapOf(
                "User" to Schema.of("object").copy(
                    properties = mapOf(
                        "id" to Schema.of("integer").copy(format = "int64"),
                        "name" to Schema.of("string"),
                        "deletedAt" to Schema.of("string", Schema.TYPE_NULL).copy(format = "date-time")
                    ),
                    required = listOf("id", "name")
                )
            )
        )
    )

    @Test
    fun `json round trip preserves model`() {
        val doc = sampleDoc()
        val back = jsonMapper.readValue(jsonMapper.writeValueAsString(doc), OpenApiDoc::class.java)
        assertEquals(doc, back)
    }

    @Test
    fun `yaml round trip preserves model`() {
        val doc = sampleDoc()
        val back = yamlMapper.readValue(yamlMapper.writeValueAsString(doc), OpenApiDoc::class.java)
        assertEquals(doc, back)
    }

    @Test
    fun `single type serializes as scalar string`() {
        val s = Schema.of("string")
        val json = jsonMapper.writeValueAsString(s)
        assertTrue(json.contains("\"type\":\"string\""), "单元素 type 应为标量: $json")
    }

    @Test
    fun `two-element type with null serializes as array and survives yaml`() {
        val s = Schema.of("string", Schema.TYPE_NULL)
        val yaml = yamlMapper.writeValueAsString(s)
        val back = yamlMapper.readValue(yaml, Schema::class.java)
        assertEquals(listOf("string", "null"), back.type)
    }

    @Test
    fun `parameter in location is serialized as in`() {
        val p = Parameter(name = "page", location = "query")
        val json = jsonMapper.writeValueAsString(p)
        assertTrue(json.contains("\"in\":\"query\""), "location 应输出为 in: $json")
    }
}