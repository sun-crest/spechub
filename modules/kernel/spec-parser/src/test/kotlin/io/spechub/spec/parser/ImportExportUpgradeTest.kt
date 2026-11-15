package io.spechub.spec.parser

import io.spechub.spec.model.io.SpecModelIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/** J1/J2 —— Postman collection 导入 + Swagger 2.0 结构化升级（验证归一化管道） */
class ImportExportUpgradeTest {

    private val swagger20 = """
        {
          "swagger": "2.0",
          "info": { "title": "Pet Store 2.0", "version": "1.0.0" },
          "host": "petstore.example.com", "basePath": "/v1", "schemes": ["https"],
          "consumes": ["application/json"], "produces": ["application/json"],
          "paths": {
            "/pets": {
              "get": {
                "operationId": "listPets",
                "parameters": [ { "name": "limit", "in": "query", "type": "integer", "required": false } ],
                "responses": { "200": { "description": "ok", "schema": { "type": "array", "items": { "${'$'}ref": "#/definitions/Pet" } } } }
              },
              "post": {
                "operationId": "createPet",
                "parameters": [ { "name": "body", "in": "body", "required": true, "schema": { "${'$'}ref": "#/definitions/Pet" } } ],
                "responses": { "201": { "description": "created", "schema": { "${'$'}ref": "#/definitions/Pet" } } }
              }
            }
          },
          "definitions": {
            "Pet": { "type": "object", "properties": { "id": { "type": "integer" }, "name": { "type": "string", "nullable": true } } }
          },
          "securityDefinitions": { "api_key": { "type": "apiKey", "name": "X-API-Key", "in": "header" } }
        }
    """.trimIndent()

    private val postman = """
        {
          "info": { "name": "PMS API", "description": "pets manager" },
          "item": [
            { "name": "list pets", "request": { "method": "GET", "url": { "raw": "http://localhost:8000/pets?limit=10", "path": ["pets"], "query": [ { "key": "limit", "value": "10" } ] } } },
            { "name": "get pet", "request": { "method": "GET", "url": { "raw": "http://localhost:8000/pets/{{petId}}", "path": ["pets", "{{petId}}"] } } },
            { "name": "create pet", "request": { "method": "POST", "url": { "raw": "http://localhost:8000/pets", "path": ["pets"] }, "body": { "mode": "urlencoded", "urlencoded": [ { "key": "name", "value": "dog" } ] } } }
          ]
        }
    """.trimIndent()

    @Test
    fun `J2 swagger-2-0 结构化升级 servers用definitions纯ref重写 body参数 安全方案`() {
        val result = SpecParser.parse(swagger20)
        assertTrue(result is ParseResult.Success, "2.0 应升级成功：$result")
        result as ParseResult.Success

        val doc = result.doc
        assertEquals("3.1.0", doc.openapi)
        assertEquals("Pet Store 2.0", doc.info.title)
        assertEquals("https://petstore.example.com/v1", doc.servers.firstOrNull()?.url, "host/basePath/schemes → servers")

        // paths /pets GET+POST
        val pets = doc.paths["/pets"]!!
        assertEquals("listPets", pets.get?.operationId)
        assertEquals("integer", pets.get?.parameters?.firstOrNull()?.schema?.typeNames()?.firstOrNull(), "query 参数内联 type → schema")
        // POST body 参数 → requestBody
        val rb = pets.post?.requestBody
        assertNotNull(rb, "in:body → requestBody")
        assertEquals("application/json", rb.content.keys.firstOrNull())
        assertEquals("#/components/schemas/Pet", rb.firstSchema()?.ref, "\$ref 已重写")

        // definitions → components.schemas + nullable 置（走 2.0 分支无 nullable 升级? 2.0 无 nullable 字段，schema 原样）
        val pet = doc.components?.schemas?.get("Pet")
        assertNotNull(pet, "definitions.Pet → components.schemas.Pet")
        assertEquals("object", pet.typeNames()?.firstOrNull())

        // components.parameters：顶层参数也收了（本例无顶层）
        assertTrue(result.upgradeLog.any { it.kind == UpgradeKind.STRUCTURAL }, "升级日志标记 STRUCTURAL")

        // securityDefinitions → securitySchemes
        val scheme = doc.components?.securitySchemes?.get("api_key")
        assertNotNull(scheme)
        assertEquals("apiKey", scheme.type)
        assertEquals("X-API-Key", scheme.name)
    }

    @Test
    fun `J2 响应 schema 入 content 媒体类型`() {
        val result = SpecParser.parse(swagger20) as ParseResult.Success
        val resp = result.doc.paths["/pets"]!!.post?.responses?.get("200")  // POST 是 201
        // GET 200 → content json
        val get200 = result.doc.paths["/pets"]!!.get?.responses?.get("200")
        assertEquals("application/json", get200?.content?.keys?.firstOrNull(), "produces → content")
        assertEquals("application/json", result.doc.paths["/pets"]!!.post?.responses?.get("201")?.content?.keys?.firstOrNull())
    }

    @Test
    fun `J1 Postman 导入 路径变量 requestBody query`() {
        val result = PostmanImporter.importCollection(SpecModelIO.parseToTree(postman))
        assertTrue(result is ParseResult.Success, "postman 应导入成功：$result")
        result as ParseResult.Success

        val doc = result.doc
        assertEquals("PMS API", doc.info.title)
        assertEquals(3, doc.paths.values.sumOf { it.operations().size }, "3 个 request")

        val pets = doc.paths["/pets"]!!
        assertNotNull(pets.get, "GET /pets")
        assertNotNull(pets.post, "POST /pets 同 path 合并")
        assertEquals("limit", pets.get?.parameters?.firstOrNull()?.name, "query 参数")
        assertEquals("query", pets.get?.parameters?.firstOrNull()?.`in`)

        // {{petId}} → {petId} 模板
        assertTrue(doc.paths.containsKey("/pets/{petId}"), "路径变量 {{petId}} → /pets/{petId}，实际 ${doc.paths.keys}")

        // urlencoded → requestBody
        val rb = pets.post?.requestBody
        assertNotNull(rb)
        assertEquals("application/x-www-form-urlencoded", rb.content.keys.firstOrNull())
        assertEquals("name", rb.firstSchema()?.properties?.keys?.firstOrNull())
    }

    @Test
    fun `J2 畸形版本不抛错 结构化失败或成功皆容错`() {
        // 根不是对象 → 结构化失败；host 为数字 → 不 crash（升级路径容错）
        val notObj = SpecParser.parse("""["swagger","2.0"]""")
        assertTrue(notObj is ParseResult.Failure)
        val weird = SpecParser.parse("""{"swagger":"2.0","info":{"title":"x","version":"1"},"host":123,"paths":{}}""")
        assertTrue(weird is ParseResult.Success, "host 非字符串不抛错：$weird")
    }
}