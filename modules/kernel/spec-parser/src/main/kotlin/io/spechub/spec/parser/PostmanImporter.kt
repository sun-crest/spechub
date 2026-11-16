package io.spechub.spec.parser

import io.spechub.spec.model.io.SpecModelIO
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/*
 * J1 —— Postman Collection v2.1 → OpenAPI 3.1（验证归一化管道，产品方案 J1 / §6.5）。
 *
 * 输入 collection JSON（v2.1 schema），输出内部 3.1 模型：
 * - `item[]` 递归展开（folder 嵌套 item）、`request.method` → operation；
 * - 路径：`url.path` 数组 join（`{{var}}` / `:var` 段 → `{var}` 模板）；缺省回退 `url.raw` 提取；
 * - query/header 参数 → parameters；`body.mode` raw/urlencoded/formdata → requestBody
 *   （raw 生成宽松 `{}` schema；urlencoded/formdata 构造 object schema 并支持 formdata file→binary）；
 * - 每个 request 输出最小 `200` 响应（Postman 占位描述，人工再补）；
 * - `info.name` → OpenAPI `info.title`（空回退「Postman 导入」）。
 */
object PostmanImporter {

    fun importCollection(root: JsonNode): ParseResult {
        if (root !is ObjectNode) {
            return ParseResult.Failure(listOf(ParseIssue.error("root-not-object", "Postman collection 根必须是 JSON 对象")))
        }
        val tree = root.deepCopy()
        val issues = mutableListOf<ParseIssue>()
        val info = tree.get("info") as? ObjectNode
        val title = info?.get("name")?.asString()?.takeIf { it.isNotBlank() } ?: "Postman 导入"
        val description = info?.get("description")?.asString()

        val doc = NODE.objectNode()
        doc.put("openapi", "3.1.0")
        val infoNode = NODE.objectNode().put("title", title).put("version", "1.0.0")
        if (!description.isNullOrBlank()) infoNode.put("description", description)
        doc.set("info", infoNode)
        val paths = NODE.objectNode()
        doc.set("paths", paths)

        tree.get("item")?.let { items -> if (items.isArray) walkItems(items as ArrayNode, paths, issues) }

        if (paths.size() == 0) {
            return ParseResult.Failure(listOf(ParseIssue.error("no-requests", "collection 中没有可解析的 request（item.request 缺失或格式不符）")))
        }

        return try {
            ParseResult.Success(SpecModelIO.openApiFromTree(doc), issues = issues)
        } catch (e: Exception) {
            ParseResult.Failure(
                listOf(ParseIssue.error("model-conversion", "Postman → 3.1 模型转换失败：${e.message?.take(160)}")),
            )
        }
    }

    // ── 递归展开 item（folder 可嵌套）──────────────────────────────────────

    private fun walkItems(items: ArrayNode, paths: ObjectNode, issues: MutableList<ParseIssue>) {
        for (item in items) {
            if (item !is ObjectNode) continue
            item.get("item")?.let { sub -> if (sub.isArray) walkItems(sub as ArrayNode, paths, issues) }
            val request = item.get("request") as? ObjectNode ?: continue
            val url = request.get("url") as? ObjectNode ?: continue
            val pathText = pathFromUrl(url)
            if (pathText == null) {
                val name = item.get("name")?.asString() ?: "?"
                issues += ParseIssue.warning("no-path", "request「$name」缺少可解析路径，跳过", "items")
                continue
            }

            val pathItem = paths.get(pathText) as? ObjectNode ?: NODE.objectNode().also { paths.set(pathText, it) }
            val method = request.get("method")?.asString()?.uppercase() ?: "GET"
            pathItem.set(method.lowercase(), buildOperation(request, issues))
        }
    }

    // ── path / operation ─────────────────────────────────────────────────────

    private fun pathFromUrl(url: ObjectNode): String? {
        val pathArr = url.get("path")
        if (pathArr != null && pathArr.isArray && pathArr.size() > 0) {
            val segs = mutableListOf<String>()
            for (i in 0 until pathArr.size()) {
                val s = pathArr.get(i)?.asString() ?: continue
                segs += when {
                    s.contains("{{") -> "{" + s.substringAfter("{{").substringBefore("}}").trim() + "}"
                    s.startsWith(":") && s.length > 1 -> "{" + s.substring(1) + "}"
                    else -> s
                }
            }
            return "/" + segs.joinToString("/").trimEnd('/').ifBlank { "/" }
        }
        // 回退：url.raw 提取路径
        val raw = url.get("raw")?.asString() ?: return null
        val withoutQuery = raw.substringBefore("?").substringBefore("#").trim()
        if (withoutQuery.startsWith("http://") || withoutQuery.startsWith("https://")) {
            return "/" + withoutQuery.substringAfter("://").substringAfter("/").trimEnd('/')
        }
        return "/" + withoutQuery.trimEnd('/').trimStart('/').ifBlank { null } ?: return null
    }

    private fun buildOperation(request: ObjectNode, issues: MutableList<ParseIssue>): ObjectNode {
        val op = NODE.objectNode()
        op.set("responses", NODE.objectNode().set("200", NODE.objectNode().put("description", "（Postman 导入占位）")))

        // query / header 参数
        val params = NODE.arrayNode()
        (request.get("url") as? ObjectNode)?.get("query")?.let { q ->
            if (q.isArray) {
                for (i in 0 until q.size()) {
                    val p = q.get(i) ?: continue
                    if (p is ObjectNode && p.get("disabled")?.asBoolean(false) != true) params.add(parameterNode(p, "query"))
                }
            }
        }
        request.get("header")?.let { h ->
            if (h.isArray) {
                for (i in 0 until h.size()) {
                    val p = h.get(i) ?: continue
                    if (p is ObjectNode && p.get("disabled")?.asBoolean(false) != true) params.add(parameterNode(p, "header"))
                }
            }
        }
        if (params.size() > 0) op.set("parameters", params)

        // body → requestBody
        (request.get("body") as? ObjectNode)?.let { body ->
            val rb = buildRequestBody(body)
            if (rb != null) op.set("requestBody", rb)
            else issues += ParseIssue.warning("body-unsupported", "body.mode=${body.get("mode")} 无法转 schema（跳过请求体）", "requestBody")
        }
        return op
    }

    private fun parameterNode(src: ObjectNode, `in`: String): ObjectNode {
        val node = NODE.objectNode().put("name", src.get("key")?.asString() ?: "param").put("in", `in`)
        (src.get("description") as? String)?.let { node.put("description", it) }
        node.set("schema", NODE.objectNode().put("type", "string"))
        return node
    }

    private fun buildRequestBody(body: ObjectNode): ObjectNode? {
        // mode 缺失/未知 → 不构造请求体（与下方 else 分支等价）
        val mode = body.get("mode")?.asString() ?: return null
        when (mode) {
            "raw" -> {
                val content = NODE.objectNode().set("application/json", NODE.objectNode().set("schema", NODE.objectNode()))
                return NODE.objectNode().set("content", content)
            }
            "urlencoded", "formdata" -> {
                val props = NODE.objectNode()
                val arr = body.get(mode) as? ArrayNode ?: return null
                for (i in 0 until arr.size()) {
                    val p = arr.get(i) ?: continue
                    if (p !is ObjectNode || p.get("disabled")?.asBoolean(false) == true) continue
                    val key = p.get("key")?.asString() ?: continue
                    val isFile = p.get("type")?.asString() == "file"
                    val schema = NODE.objectNode().put("type", if (isFile) "string" else inferScalar(p.get("value")?.asString()))
                    if (isFile) schema.put("format", "binary")
                    props.set(key, schema)
                }
                val schema = NODE.objectNode().put("type", "object").set("properties", props)
                val content = NODE.objectNode().set("application/x-www-form-urlencoded", NODE.objectNode().set("schema", schema))
                return NODE.objectNode().set("content", content)
            }
            else -> return null
        }
    }

    private fun inferScalar(value: String?): String = when {
        value == null || value.isBlank() -> "string"
        value.trim().toLongOrNull() != null -> "integer"
        value.trim().toDoubleOrNull() != null -> "number"
        value == "true" || value == "false" -> "boolean"
        else -> "string"
    }

    private val NODE: JsonNodeFactory = JsonNodeFactory.instance
}