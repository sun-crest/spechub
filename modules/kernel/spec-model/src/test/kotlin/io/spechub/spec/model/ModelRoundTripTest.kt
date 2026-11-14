package io.spechub.spec.model.io

import io.spechub.spec.model.Components
import io.spechub.spec.model.Info
import io.spechub.spec.model.MediaType
import io.spechub.spec.model.Operation
import io.spechub.spec.model.OpenApiDoc
import io.spechub.spec.model.PathItem
import io.spechub.spec.model.Response
import io.spechub.spec.model.Schema
import io.spechub.spec.model.Server
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import tools.jackson.databind.node.JsonNodeFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** B2：读；B1+B2：模型与 JSON/YAML round-trip 与容错 */
class ModelRoundTripTest {

    // ── 构造基线文档（保序 map）──────────────────────────────────────────
    private fun baselineDoc(title: String = "demo") = OpenApiDoc(
        openapi = "3.1.0",
        info = Info(title = title, version = "1.0.0", description = "round-trip 基线"),
        servers = listOf(Server("https://api.example.com/v1")),
        paths = linkedMapOf(
            "/pets" to PathItem(
                get = Operation(
                    operationId = "listPets",
                    tags = listOf("pets"),
                    responses = mapOf(
                        "200" to Response(
                            description = "OK",
                            content = mapOf(
                                "application/json" to MediaType(
                                    schema = Schema(type = Schema.typeArray("array"), items = Schema(ref = "#/components/schemas/Pet")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        components = Components(
            schemas = linkedMapOf(
                "Pet" to Schema(
                    type = Schema.typeArray("object"),
                    required = setOf("id", "name"),
                    properties = linkedMapOf(
                        "id" to Schema(type = Schema.typeArray("integer", "null"), format = "int64"),
                        "name" to Schema(type = Schema.typeArray("string")),
                    ),
                    additionalProperties = JsonNodeFactory.instance.booleanNode(false),
                ),
            ),
        ),
    )

    @Test
    fun `baseline round-trips through yaml and json`() {
        val doc = baselineDoc()
        for (format in SpecFormat.entries) {
            val text = SpecModelIO.writeDocument(doc, format)
            val back = SpecModelIO.readDocument(text, format)
            assertEquals(doc, back, "round-trip 应幂等：$format")
        }
    }

    @Test
    fun `读入 petstore 黄金样例`() {
        val doc = SpecModelIO.readDocument(loadFixture("openapi/PetStore-3.1.yaml"))
        assertAll(
            { assertEquals("3.1.0", doc.openapi) },
            { assertEquals("Pet Store", doc.info.title) },
            { assertEquals("示例宠物店 API（中文描述保真校验）", doc.info.description) },
            { assertEquals(setOf("/pets", "/pets/{petId}"), doc.paths.keys) },
            { assertEquals(1, doc.servers.size) },
        )

        val listPets = doc.paths.getValue("/pets").get
        assertNotNull(listPets)
        assertEquals("listPets", listPets.operationId)
        assertEquals(listOf("pets"), listPets.tags)
        assertEquals(listOf("integer", "null"), listPets.parameters.single().schema?.typeNames())
        assertEquals("int32", listPets.parameters.single().schema?.format)

        // $ref 字面量保真（在数组项 items 上）
        val petRef = listPets.responses.getValue("200").content.getValue("application/json").schema
        assertEquals("#/components/schemas/Pet", petRef?.items?.ref)

        val pet = doc.components!!.schemas.getValue("Pet")
        assertNotNull(pet)
        assertEquals(listOf("integer", "null"), pet.properties.getValue("id").typeNames())
        assertEquals(AdditionalPropertiesValue.Allowed(false), SpecModelIO.additionalPropertiesOf(pet.additionalProperties))
        assertEquals("petType", pet.discriminator?.propertyName)

        val security = doc.components!!.securitySchemes.getValue("bearerAuth")
        assertNotNull(security)
        assertEquals("http", security.type)
        assertEquals("JWT", security.bearerFormat)
    }

    @Test
    fun `yaml 多文档流读取`() {
        val a = baselineDoc("first")
        val b = OpenApiDoc(openapi = "3.1.0", info = Info(title = "second", version = "2.0.0"))
        val docs = SpecModelIO.readDocuments(SpecModelIO.writeDocuments(listOf(a, b)))
        assertEquals(listOf("first", "second"), docs.map { it.info.title })
    }

    @Test
    fun `type 单字符串与数组均解析为列表`() {
        val json = """
            {"openapi":"3.1.0","info":{"title":"t","version":"1"},"paths":{},
             "components":{"schemas":{"S":{"type":"string"},"T":{"type":["object","null"]}}}}
        """.trimIndent()
        val doc = SpecModelIO.readDocument(json, SpecFormat.JSON)
        assertAll(
            { assertEquals(listOf("string"), doc.components!!.schemas.getValue("S").typeNames()) },
            { assertEquals(listOf("object", "null"), doc.components!!.schemas.getValue("T").typeNames()) },
        )
    }

    @Test
    fun `additionalProperties 子 Schema 形态可拆解`() {
        val json = """
            {"openapi":"3.1.0","info":{"title":"t","version":"1"},"paths":{},
             "components":{"schemas":{"Tags":{"type":"object","additionalProperties":{"type":"string"}}}}}
        """.trimIndent()
        val doc = SpecModelIO.readDocument(json, SpecFormat.JSON)
        val ap = SpecModelIO.additionalPropertiesOf(doc.components!!.schemas.getValue("Tags").additionalProperties)
        assertEquals(AdditionalPropertiesValue.ObjectSchema::class, ap!!::class)
        assertEquals(listOf("string"), (ap as AdditionalPropertiesValue.ObjectSchema).schema.typeNames())
    }

    private fun loadFixture(path: String): String {
        val resource = javaClass.classLoader.getResource(path) ?: error("fixture 缺失：$path")
        return resource.readText()
    }
}