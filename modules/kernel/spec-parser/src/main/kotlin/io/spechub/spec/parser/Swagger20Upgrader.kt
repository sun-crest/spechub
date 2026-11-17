package io.spechub.spec.parser

import io.spechub.spec.model.io.SpecModelIO
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/*
 * J2 —— Swagger 2.0 → OpenAPI 3.1 结构化升级（承接 C2 推迟部分，§6.5 / 产品方案 §6.5）。
 *
 * 在 **tree 层** 一次性完成（源文件从不回写，转换只在内存/索引层）：
 * - 根：`swagger`→`openapi: 3.1.0`；`host/basePath/schemes`→`servers`；根级 `consumes/produces` 作为默认媒体类型。
 * - `definitions`→`components.schemas`（`$ref: #/definitions/X` 重写为 `#/components/schemas/X`）；
 * - 顶层 `parameters`/`responses`→`components.parameters`/`components.responses`（逐个结构转换）；
 * - `securityDefinitions`→`components.securitySchemes`（basic→http/basic、apiKey、oauth2 flow 映射）；
 * - 每个 operation：
 *     * `in: body` 参数 → requestBody（content.<consumes|application/json>.schema）；
 *     * `in: formData` 参数组 → requestBody（application/x-www-form-urlencoded 的 object schema；file→format binary）；
 *     * query/header/path 参数：内联 type/format/items 等收拢进 `schema`、`collectionFormat multi`→style=form；
 *     * 响应 `{schema}`→`content.<produces|application/json>.schema`；
 *     * `consumes/produces` 移除（同上驱动媒体类型）。
 *
 * 失败结构化收集（ParseIssue），成功返回 internal 3.1 model（含 STRUCTURAL 升级日志）。
 */

object Swagger20Upgrader {
    private const val DEFAULT_MEDIA = "application/json"
    private const val FORM_MEDIA = "application/x-www-form-urlencoded"

    fun upgradeAndConvert(root: JsonNode): ParseResult {
        if (root !is ObjectNode) {
            return ParseResult.Failure(listOf(ParseIssue.error("root-not-object", "Swagger 2.0 文档根必须是 JSON 对象")))
        }
        val tree = root.deepCopy()
        val log = mutableListOf<UpgradeRecord>()
        val issues = mutableListOf<ParseIssue>()

        tree.remove("swagger")
        tree.put("openapi", "3.1.0")
        log += UpgradeRecord(UpgradeKind.STRUCTURAL, "\$root", "swagger: 2.0 → openapi: 3.1.0")

        val rootConsumes = tree.get("consumes")?.let { textList(it) }
        val rootProduces = tree.get("produces")?.let { textList(it) }
        tree.remove("consumes")
        tree.remove("produces")

        buildServers(tree, log)

        // ── components 落地 ────────────────────────────────────────────────
        val components = tree.get("components") as? ObjectNode ?: tree.putObject("components")

        tree.get("definitions")?.let { defs ->
            components.set("schemas", defs)
            log += UpgradeRecord(UpgradeKind.STRUCTURAL, "\$root/definitions", "definitions → components.schemas")
        }
        tree.remove("definitions")

        tree.get("parameters")?.let { params -> components.set("parameters", params) }
        tree.remove("parameters")

        tree.get("responses")?.let { resp ->
            val converted = NODE.objectNode()
            resp.properties().forEach { (name, value) ->
                val typed = value as? ObjectNode ?: return@forEach
                convertResponse(typed, rootProduces, "\$root/responses/$name", log)
                converted.set(name, typed)
            }
            components.set("responses", converted)
            log += UpgradeRecord(UpgradeKind.STRUCTURAL, "\$root/responses", "顶层 responses → components.responses")
        }
        tree.remove("responses")

        tree.get("securityDefinitions")?.let { defs ->
            val ss = NODE.objectNode()
            defs.properties().forEach { (name, value) ->
                (value as? ObjectNode)?.let { ss.set(name, convertSecurityScheme(it)) }
            }
            components.set("securitySchemes", ss)
            log += UpgradeRecord(UpgradeKind.STRUCTURAL, "\$root/securityDefinitions", "securityDefinitions → components.securitySchemes")
        }
        tree.remove("securityDefinitions")

        // ── $ref 重写 + paths 转换 ─────────────────────────────────────────
        rewriteRefs(tree, "#/definitions/", "#/components/schemas/")
        tree.get("paths")?.let { paths ->
            if (paths is ObjectNode) {
                paths.properties().forEach { (path, rawItem) ->
                    if (rawItem is ObjectNode) convertPathItem(rawItem, rootConsumes, rootProduces, "\$root/paths/$path", log)
                }
            }
        }

        return try {
            ParseResult.Success(SpecModelIO.openApiFromTree(tree), upgradeLog = log, issues = issues)
        } catch (e: Exception) {
            ParseResult.Failure(
                listOf(ParseIssue.error("model-conversion", "2.0 升级后的树→内部模型转换失败：${e.message?.take(160)}")),
            )
        }
    }

    // ── servers ─────────────────────────────────────────────────────────────

    private fun buildServers(
        tree: ObjectNode,
        log: MutableList<UpgradeRecord>,
    ) {
        val host = tree.get("host")?.asString()
        val basePath = tree.get("basePath")?.asString() ?: ""
        val schemes = tree.remove("schemes")?.let { textList(it) } ?: listOf("https")
        tree.remove("host")
        tree.remove("basePath")

        if (host.isNullOrBlank()) {
            if (basePath.isNotBlank()) {
                tree.set("servers", NODE.arrayNode().add(NODE.objectNode().put("url", basePath)))
                log += UpgradeRecord(UpgradeKind.STRUCTURAL, "\$root", "basePath → servers")
            }
            return
        }
        val servers = NODE.arrayNode()
        schemes.distinct().forEach { scheme ->
            val url = "$scheme://$host$basePath".removeSuffix("/")
            servers.add(NODE.objectNode().put("url", url))
        }
        tree.set("servers", servers)
        log += UpgradeRecord(UpgradeKind.STRUCTURAL, "\$root", "host/basePath/schemes → servers（${servers.size()} 个）")
    }

    // ── pathItem / operation ────────────────────────────────────────────────

    private fun convertPathItem(
        item: ObjectNode,
        rootConsumes: List<String>?,
        rootProduces: List<String>?,
        base: String,
        log: MutableList<UpgradeRecord>,
    ) {
        val consumes = textListOf(item, "consumes")?.takeIf { it.isNotEmpty() } ?: rootConsumes
        val produces = textListOf(item, "produces")?.takeIf { it.isNotEmpty() } ?: rootProduces
        item.remove("consumes")
        item.remove("produces")
        for (method in HTTP_METHODS) {
            val op = item.get(method) as? ObjectNode ?: continue
            convertOperation(op, consumes, produces, "$base/$method", log)
        }
    }

    private fun convertOperation(
        op: ObjectNode,
        consumes: List<String>?,
        produces: List<String>?,
        base: String,
        log: MutableList<UpgradeRecord>,
    ) {
        op.remove("consumes")
        op.remove("produces")

        // parameters 拆分：body / formData / 其它
        val params = op.get("parameters") as? ArrayNode
        val rest = NODE.arrayNode()
        val bodyParams = NODE.arrayNode()
        val formParams = NODE.arrayNode()
        params?.forEach { p ->
            if (p is ObjectNode && p.get("\$ref") != null) {
                rest.add(p.deepCopy())
                return@forEach
            }
            when (p.get("in")?.asString()) {
                "body" -> bodyParams.add(p.deepCopy())
                "formData" -> formParams.add(p.deepCopy())
                else -> if (p is ObjectNode) rest.add(convertParameter(p, base, log))
            }
        }
        if (rest.size() > 0) op.set("parameters", rest) else op.remove("parameters")

        // body 参数 → requestBody
        if (bodyParams.size() > 0) {
            val body = bodyParams.get(0) as ObjectNode
            val rb = NODE.objectNode()
            if (body.get("description") != null) rb.set("description", body.get("description"))
            if (body.get("required")?.asBoolean(false) == true) rb.put("required", true)
            val media = first(consumes) ?: DEFAULT_MEDIA
            val content = NODE.objectNode().set(media, NODE.objectNode().set("schema", ensureSchema(body)))
            rb.set("content", content)
            op.set("requestBody", rb)
            log += UpgradeRecord(UpgradeKind.STRUCTURAL, "$base/parameters[body]", "in:body 参数 → requestBody（$media）")
        }

        // formData 参数组 → urlencoded object schema
        if (formParams.size() > 0) {
            val props = NODE.objectNode()
            val requiredArr = NODE.arrayNode()
            formParams.forEach { p ->
                if (p !is ObjectNode) return@forEach
                val name = p.get("name")?.asString() ?: "field"
                if (p.get("required")?.asBoolean(false) == true) requiredArr.add(name)
                val schema = ensureSchema(p)
                if (p.get("type")?.asString() == "file") schema.put("format", "binary")
                props.set(name, schema)
            }
            val obj = NODE.objectNode().put("type", "object").set("properties", props)
            if (requiredArr.size() > 0) obj.set("required", requiredArr)
            val content = NODE.objectNode().set(FORM_MEDIA, NODE.objectNode().set("schema", obj))
            op.set("requestBody", NODE.objectNode().set("content", content))
            log += UpgradeRecord(UpgradeKind.STRUCTURAL, "$base/parameters[formData]", "in:formData 参数组 → requestBody（$FORM_MEDIA）")
        }

        // responses：schema → content.<produces>
        op.get("responses")?.let { responses ->
            if (responses is ObjectNode) {
                responses.properties().forEach { (code, raw) ->
                    if (raw is ObjectNode) convertResponse(raw, produces, "$base/responses/$code", log)
                }
            }
        }
    }

    // ── parameter / response / securityScheme ───────────────────────────────

    private fun convertParameter(
        node: ObjectNode,
        base: String,
        log: MutableList<UpgradeRecord>,
    ): ObjectNode {
        val schema = NODE.objectNode()
        for (key in SCHEMA_KEYS) node.get(key)?.let { schema.set(key, it) }
        for (key in SCHEMA_KEYS) node.remove(key)
        if (node.get("collectionFormat")?.asString() == "multi") {
            node.put("style", "form").put("explode", true)
        }
        node.remove("collectionFormat")
        node.remove("allowEmptyValue")
        if (schema.size() > 0) node.set("schema", schema)
        val name = node.get("name")?.asString() ?: "param"
        log += UpgradeRecord(UpgradeKind.STRUCTURAL, "$base/parameters/$name", "2.0 参数内联字段收拢进 schema")
        return node
    }

    private fun convertResponse(
        node: ObjectNode,
        produces: List<String>?,
        base: String,
        log: MutableList<UpgradeRecord>,
    ) {
        val mediaType = first(produces) ?: DEFAULT_MEDIA
        node.get("schema")?.let { schema ->
            node.set("content", NODE.objectNode().set(mediaType, NODE.objectNode().set("schema", schema)))
            node.remove("schema")
            log += UpgradeRecord(UpgradeKind.STRUCTURAL, base, "响应 schema → content.$mediaType.schema")
        }
    }

    private fun convertSecurityScheme(node: ObjectNode): ObjectNode {
        val out = NODE.objectNode()
        when (node.get("type")?.asString()) {
            "basic" -> {
                out.put("type", "http").put("scheme", "basic")
            }

            "apiKey" -> {
                out.put("type", "apiKey")
                node.get("name")?.let { out.set("name", it) }
                node.get("in")?.let { out.set("in", it) }
            }

            "oauth2" -> {
                out.put("type", "oauth2")
                val flowNode = NODE.objectNode()
                for (k in listOf("authorizationUrl", "tokenUrl", "refreshUrl")) node.get(k)?.let { flowNode.set(k, it) }
                node.get("scopes")?.let { flowNode.set("scopes", it) }
                val oauthKey =
                    when (node.get("flow")?.asString()) {
                        "password" -> "password"
                        "application" -> "clientCredentials"
                        "accessCode" -> "authorizationCode"
                        else -> "implicit"
                    }
                out.set("flows", NODE.objectNode().set(oauthKey, flowNode))
            }

            else -> {
                node.get("type")?.let { out.set("type", it) }
            }
        }
        return out
    }

    // ── $ref 重写 + 公共辅助 ───────────────────────────────────────────────

    private fun rewriteRefs(
        node: JsonNode,
        from: String,
        to: String,
    ) {
        if (node is ObjectNode) {
            node.get("\$ref")?.let { ref ->
                val text = ref.asText()
                if (text.startsWith(from)) node.put("\$ref", to + text.removePrefix(from))
            }
            node.properties().forEach { (_, v) -> rewriteRefs(v, from, to) }
        } else if (node.isArray) {
            node.forEach { rewriteRefs(it, from, to) }
        }
    }

    private val SCHEMA_KEYS =
        listOf(
            "type",
            "format",
            "items",
            "enum",
            "default",
            "minimum",
            "maximum",
            "exclusiveMinimum",
            "exclusiveMaximum",
            "minLength",
            "maxLength",
            "pattern",
            "minItems",
            "maxItems",
            "uniqueItems",
            "multipleOf",
            "minProperties",
            "maxProperties",
        )

    /** 2.0 parameter → 3.1 schema：有 schema 用 schema，否则把内联字段构造为 schema 对象 */
    private fun ensureSchema(param: ObjectNode): ObjectNode {
        (param.get("schema") as? ObjectNode)?.let { return it }
        val schema = NODE.objectNode()
        for (key in SCHEMA_KEYS) param.get(key)?.let { schema.set(key, it) }
        return schema
    }

    private fun textListOf(
        node: ObjectNode,
        key: String,
    ): List<String>? = (node.get(key) as? ArrayNode)?.let { textList(it) }

    private fun textList(arr: JsonNode): List<String> = (arr as? ArrayNode)?.mapNotNull { if (it.isTextual) it.asString() else null } ?: emptyList()

    private fun first(list: List<String>?): String? = list?.firstOrNull()

    private val HTTP_METHODS = listOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

    private val NODE: JsonNodeFactory = JsonNodeFactory.instance
}
