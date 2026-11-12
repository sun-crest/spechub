package io.spechub.spec.parser

import io.spechub.spec.model.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 多版本归一化 golden 测试（§6.5）：
 *  - 2.0 → 3.1 结构升级（host/basePath→servers、definitions→components.schemas）
 *  - 3.0 → nullable / exclusive* 语义升级（含转换留痕）
 *  - 3.1 原样保留（type 数组含 "null"）
 *  - 无效输入 → Failure 而非抛异常
 */
class SpecNormalizerTest {

    @Test
    fun `2_0 spec converts host and definitions to 3_1 model`() {
        val src = """
            swagger: "2.0"
            info:
              title: 旧版服务
              version: 1.4.0
            host: api.old.example.com
            basePath: /v1
            schemes:
              - https
            paths:
              /users:
                get:
                  operationId: listUsers
                  parameters:
                    - name: q
                      in: query
                      type: string
                  responses:
                    '200':
                      description: ok
                      schema:
                        type: array
                        items:
                          ${'$'}ref: '#/definitions/User'
            definitions:
              User:
                type: object
                properties:
                  id:
                    type: integer
                    format: int64
                  nickname:
                    type: string
        """.trimIndent()

        val result = SpecNormalizer.normalize(src)
        val ok = assertIs<ParseResult.Success>(result)
        assertEquals(SpecVersion.V2, ok.sourceVersion)
        assertEquals("旧版服务", ok.doc.info.title)

        // 2.0 → 3.1: host+basePath → servers
        assertTrue(ok.doc.servers.any { it.url == "https://api.old.example.com/v1" }, "servers 应从 host+basePath 生成: ${ok.doc.servers}")

        // 2.0 → 3.1: definitions → components.schemas
        val user = ok.doc.components.schemas["User"]
        assertTrue(user != null, "definitions.User 应进入 components.schemas")
        assertEquals("object", user.type?.first())
        assertEquals("int64", user.properties["id"]?.format)

        // operation 与 $ref 链路完整
        val listUsers = ok.doc.paths["/users"]?.operations?.get(HttpMethod.GET)
        assertEquals("listUsers", listUsers?.operationId)
        // 2.0 参数的内联 type → 3.x schema.type
        assertEquals("string", listUsers?.parameters?.firstOrNull()?.schema?.type?.first())
        val respSchema = listUsers?.responses?.get("200")?.content?.values?.firstOrNull()?.schema
        assertEquals("#/components/schemas/User", respSchema?.items?.ref)
    }

    @Test
    fun `3_0 nullable and exclusive produce notes and 3_1 semantics`() {
        val src = """
            openapi: 3.0.3
            info:
              title: 三零
              version: 1.0.0
            paths:
              /x:
                get:
                  operationId: getX
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            nullable: true
                            properties:
                              age:
                                type: integer
                                minimum: 0
                                exclusiveMinimum: true
        """.trimIndent()

        val result = SpecNormalizer.normalize(src)
        val ok = assertIs<ParseResult.Success>(result)
        assertEquals(SpecVersion.V300, ok.sourceVersion)

        val xSchema = ok.doc.paths["/x"]?.operations?.get(HttpMethod.GET)
            ?.responses?.get("200")?.content?.get("application/json")?.schema
        assertTrue(xSchema != null)
        // nullable: true → type 数组含 "null"
        assertEquals(listOf("object", "null"), xSchema.type)
        // exclusiveMinimum: true → 数值语义, minimum 置空
        assertEquals(null, xSchema.properties["age"]?.minimum)
        assertTrue(xSchema.properties["age"]?.exclusiveMinimum?.toInt() == 0, "exclusiveMinimum 应为 0")
        // 转换留痕
        assertTrue(ok.notes.any { it.message.contains("nullable=true") }, "应有 nullable 升级留痕: ${ok.notes}")
        assertTrue(ok.notes.any { it.message.contains("exclusiveMinimum=true") }, "应有 exclusive 升级留痕: ${ok.notes}")
    }

    @Test
    fun `3_1 type array with null is preserved`() {
        val src = """
            openapi: 3.1.0
            info:
              title: 最新
              version: 2.0.0
            paths:
              /pets:
                get:
                  operationId: listPets
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              ${'$'}ref: '#/components/schemas/Pet'
            components:
              schemas:
                Pet:
                  type: object
                  properties:
                    name:
                      type: [string, "null"]
        """.trimIndent()

        val result = SpecNormalizer.normalize(src)
        val ok = assertIs<ParseResult.Success>(result)
        assertEquals(SpecVersion.V310, ok.sourceVersion)
        assertEquals(listOf("string", "null"), ok.doc.components.schemas["Pet"]?.properties?.get("name")?.type)
        assertEquals(
            "#/components/schemas/Pet",
            ok.doc.paths["/pets"]?.operations?.get(HttpMethod.GET)
                ?.responses?.get("200")?.content?.get("application/json")?.schema?.items?.ref
        )
    }

    @Test
    fun `broken spec yields failure not exception`() {
        val result = SpecNormalizer.normalize("openapi: 3.1.0\ninfo:\npaths: {{{")
        assertIs<ParseResult.Failure>(result)
    }

    @Test
    fun `empty string yields failure not exception`() {
        assertIs<ParseResult.Failure>(SpecNormalizer.normalize(""))
    }
}