package io.spechub.spec.parser.export

import io.spechub.spec.model.OpenApiDoc
import io.spechub.spec.model.io.SpecFormat
import io.spechub.spec.model.io.SpecModelIO
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/*
 * J3 —— OpenAPI 3.1 → Postman Collection v2.1 导出（契约复用：text tool/Kong 导入等）。
 * 每个 path × method 一条 item：request.method/url（server[0]+path）/query/header/body（application/json raw 简化）。
 */
object PostmanExporter {
    fun exportJson(doc: OpenApiDoc): String {
        val collection = NODE.objectNode()
        collection.set(
            "info",
            NODE
                .objectNode()
                .put("name", doc.info.title)
                .put("description", doc.info.description ?: "")
                .put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"),
        )

        val items = NODE.arrayNode()
        doc.paths.forEach { (path, item) ->
            item.operations().forEach { (method, op) ->
                items.add(requestItem(doc, path, method, op))
            }
        }
        collection.set("item", items)
        return SpecModelIO.writeTreeNode(collection, SpecFormat.JSON, pretty = true)
    }

    private fun requestItem(
        doc: OpenApiDoc,
        path: String,
        method: String,
        op: io.spechub.spec.model.Operation,
    ): ObjectNode {
        val item = NODE.objectNode()
        item.put("name", op.summary?.takeIf { it.isNotBlank() } ?: op.operationId?.takeIf { it.isNotBlank() } ?: "$method $path")

        val req = NODE.objectNode().put("method", method.uppercase())
        val baseUrl = doc.servers.firstOrNull()?.url ?: "http://localhost"
        val urlRaw = baseUrl.trimEnd('/') + path

        val url = NODE.objectNode().put("raw", urlRaw)
        val urlPath = NODE.arrayNode()
        path.trim('/').split('/').filter { it.isNotBlank() }.forEach { seg ->
            urlPath.add(if (seg.startsWith("{") && seg.endsWith("}")) "{{${seg.substring(1, seg.length - 1)}}}" else seg)
        }
        url.set("path", urlPath)

        // query 参数
        val query = NODE.arrayNode()
        op.parameters.filter { it.`in` == io.spechub.spec.model.Parameter.IN_QUERY && it.name.isNotBlank() }.forEach { p ->
            query.add(
                NODE
                    .objectNode()
                    .put("key", p.name)
                    .put("value", "{{${p.name}}}")
                    .put("description", p.description ?: "")
                    .put("disabled", p.required.not()),
            )
        }
        if (query.size() > 0) url.set("query", query)
        req.set("url", url)

        // header 参数
        val headers = NODE.arrayNode()
        op.parameters.filter { it.`in` == io.spechub.spec.model.Parameter.IN_HEADER && it.name.isNotBlank() }.forEach { p ->
            headers.add(NODE.objectNode().put("key", p.name).put("value", "{{${p.name}}}"))
        }
        if (headers.size() > 0) req.set("header", headers)

        // body（application/json → raw 占位）
        op.requestBody?.content?.get("application/json")?.let {
            val body = NODE.objectNode().put("mode", "raw").put("raw", "{}")
            req.set("body", body)
        }

        item.set("request", req)
        return item
    }

    private val NODE: JsonNodeFactory = JsonNodeFactory.instance
}
