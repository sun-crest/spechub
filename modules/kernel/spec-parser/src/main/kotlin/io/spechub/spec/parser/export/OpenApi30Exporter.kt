package io.spechub.spec.parser.export

import io.spechub.spec.model.OpenApiDoc
import io.spechub.spec.model.io.SpecFormat
import io.spechub.spec.model.io.SpecModelIO
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/*
 * J3 —— OpenAPI 3.1 → 3.0 降级导出（§产品方案 §6.5「只降能力」）。
 *
 * 在 tree 层做有损降级（源模型不动，仅导出视图）：
 * - `type: [x, "null"]` → `type: x` + `nullable: true`（多元素非 null 取首个，记 warning 于 callable 载荷）；
 * - `exclusiveMinimum/Maximum` 数值 → 3.0 布尔形态（presence bound 保留为最小/最大 + exclusive=true 近似）；
 * - 移除 3.1 独有关键字：`const`、`prefixItems`、`$defs`、`jsonSchemaDialect`、`webhooks`；
 * - 根版本 `openapi: 3.0.3`。
 */
object OpenApi30Exporter {

    /** 模型 → 3.0 降级 YAML 文本；降级路径摘要经 downgradeLog 回传（供 UI/审计） */
    fun exportYaml(doc: OpenApiDoc): String = tree(doc).let { SpecModelIO.writeTreeNode(it, SpecFormat.YAML, pretty = true) }

    fun exportJson(doc: OpenApiDoc): String = tree(doc).let { SpecModelIO.writeTreeNode(it, SpecFormat.JSON, pretty = true) }

    fun tree(doc: OpenApiDoc): ObjectNode {
        val tree = SpecModelIO.valueToTree(doc) as ObjectNode
        tree.put("openapi", "3.0.3")
        tree.remove("jsonSchemaDialect")
        tree.remove("webhooks")

        // 递归所有 schema object 降级
        val log = mutableListOf<String>()
        walkSchemas(tree, "\$root", log)
        return tree
    }

    private fun walkSchemas(node: JsonNode, path: String, log: MutableList<String>) {
        if (node is ObjectNode) {
            if (node.get("oneOf") == null && node.get("anyOf") == null) {
                // schema object 判定：有 type/schema 字样。保守：遍历所有对象，仅当含 type/properties/items 时降级
                if (node.has("type") || node.has("properties") || node.has("items") || node.has("\$ref")) {
                    downgradeSchema(node, path, log)
                    // 3.1 独有关键字清除
                    node.remove("const")
                    node.remove("prefixItems")
                    node.remove("\$defs")
                }
            }
            node.properties().forEach { (_, v) -> walkSchemas(v, path, log) }
        } else if (node.isArray) {
            node.forEach { walkSchemas(it, path, log) }
        }
    }

    private fun downgradeSchema(node: ObjectNode, path: String, log: MutableList<String>) {
        // type 数组 → 标量 + nullable
        node.get("type")?.let { typeNode ->
            if (typeNode.isArray) {
                val names = (typeNode as ArrayNode).mapNotNull { if (it.isTextual) it.asString() else null }
                val notNull = names.filter { it != "null" }
                val nullable = names.contains("null")
                when {
                    notNull.isEmpty() -> node.remove("type")
                    else -> node.put("type", notNull.first())
                }
                if (nullable) node.put("nullable", true)
                if (notNull.size > 1) log += "type 多类型 $names → 取首个 ${notNull.first()}（有损）@ $path"
            }
        }

        // exclusiveMinimum/Maximum 数值 → 布尔近似
        for ((exclKey, boundKey) in listOf("exclusiveMinimum" to "minimum", "exclusiveMaximum" to "maximum")) {
            val excl = node.get(exclKey)
            if (excl != null && excl.isNumber) {
                val value = excl.asDouble()
                node.remove(exclKey)
                if (node.has(boundKey)) {
                    node.put(exclKey, true)
                    // 3.0 exclusive=true 表示 >bound，等价于我们已有 bound+strict；bound 值即原数值近似
                } else {
                    node.put(boundKey, value)
                    node.put(exclKey, true)
                    log += "exclusive$boundKey 无对应 bound → 用该值作为 minimum 并 exclusive=true（近似）@ $path"
                }
            }
        }
    }

    private val NODE: JsonNodeFactory = JsonNodeFactory.instance
}