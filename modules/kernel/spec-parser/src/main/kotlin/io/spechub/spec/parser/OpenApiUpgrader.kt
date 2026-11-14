package io.spechub.spec.parser

import io.spechub.spec.model.io.SpecModelIO
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.BooleanNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode

/*
 * C2 / C3 —— 3.0 → 3.1 语义升级（P0 覆盖）+ 升级变更日志。
 *
 * 在 **tree 层** 完成（先升级再入内部模型，见 §6.5「转换只在内存/索引层一次性完成」，源文件从不回写）。
 * 覆盖：
 * - `nullable: true` → `type: [..., "null"]`（自动；缺 type 时无法合并 → 需人工复核）
 * - `exclusiveMinimum/Maximum` 布尔 → 数值（数值 = 对应 bound，并移除 bound 防叠加；缺 bound → 复核）
 * - `type` 单字符串 → 数组（3.1 允许字符串但统一数组，简化下游 typeNames）
 * - 递归覆盖 properties/items/additionalProperties/allOf…/$defs 等嵌套 Schema
 *
 * 变更全程留痕于 UpgradeRecord（C3），供评审流标记「自动转换 / 待人工复核」。
 */

/** 3.0 根升级入口：tree → 升级（原地副本）→ 内部模型 → ParseResult（含升级日志与警告） */
class OpenApiUpgrader private constructor() {

    private val log = mutableListOf<UpgradeRecord>()
    private val issues = mutableListOf<ParseIssue>()

    companion object {
        fun upgradeAndConvert(root: JsonNode): ParseResult {
            if (root !is ObjectNode) {
                return ParseResult.Failure(listOf(ParseIssue.error("root-not-object", "OpenAPI 文档根必须是 JSON 对象")))
            }
            val upgrader = OpenApiUpgrader()
            val tree = root.deepCopy() // 不污染调用方传入的树
            upgrader.walkSchemas(tree, "\$root")
            return try {
                ParseResult.Success(
                    doc = SpecModelIO.openApiFromTree(tree),
                    upgradeLog = upgrader.log,
                    issues = upgrader.issues,
                )
            } catch (e: Exception) {
                ParseResult.Failure(
                    listOf(ParseIssue.error("model-conversion", "升级后的树→内部模型转换失败：${e.message?.take(160)}")),
                )
            }
        }
    }

    /** 遍历整树，定位所有 Schema Object 并升级（components.schemas.* 顶层 + 任意 `schema` 键值） */
    private fun walkSchemas(node: JsonNode, path: String) {
        when {
            node is ObjectNode -> {
                for ((k, v) in node.properties()) {
                    when {
                        // components.schemas.<name> 的每个值是 Schema 顶层
                        k == "schemas" && v is ObjectNode ->
                            v.properties().forEach { (name, schema) ->
                                upgradeSchemaObject(schema, "$path/$k/$name")
                            }
                        // 任意 `schema:` 键（parameters/requestBody/content/response/header 等）
                        k == "schema" && v is ObjectNode -> upgradeSchemaObject(v, "$path/$k")
                        else -> walkSchemas(v, "$path/$k")
                    }
                }
            }
            node.isArray -> (node as ArrayNode).forEachIndexed { i, item -> walkSchemas(item, "$path[$i]") }
            else -> Unit
        }
    }

    /** 升级单个 Schema Object（递归进嵌套 Schema 关键字） */
    private fun upgradeSchemaObject(node: JsonNode, path: String) {
        if (node !is ObjectNode) return

        val nullable = (node.remove("nullable") as? BooleanNode)?.booleanValue() ?: false

        // type：字符串 → 数组；数组保留；非法形态移除并告警
        val typeNode = node.get("type")
        val typeArr: ArrayNode? = when {
            typeNode is ArrayNode -> typeNode
            typeNode is StringNode -> {
                val arr = JsonNodeFactory.instance.arrayNode().add(typeNode.stringValue())
                node.set("type", arr)
                log += UpgradeRecord(UpgradeKind.TYPE_ARRAY, path, "type `${typeNode.stringValue()}` → 数组形态 `[${typeNode.stringValue()}]`")
                arr
            }
            typeNode != null -> {
                node.remove("type")
                issues += ParseIssue.warning("non-scalar-type", "type 不是字符串/数组（已忽略该值）", path)
                null
            }
            else -> null
        }

        // nullable → 数组追加 "null"
        if (nullable) {
            if (typeArr != null) {
                if (typeArr.asSequence().none { it is StringNode && it.stringValue() == "null" }) {
                    typeArr.add("null")
                    log += UpgradeRecord(UpgradeKind.NULLABLE_TO_TYPE, path, "nullable: true → type 追加 \"null\"")
                }
            } else {
                issues += ParseIssue.warning(
                    "nullable-without-type",
                    "nullable: true 但没有 type 可合并，已忽略 nullable（语义上该属性接受任意类型）",
                    path,
                )
                log += UpgradeRecord(
                    UpgradeKind.NULLABLE_TO_TYPE, path,
                    "nullable: true 但缺 type，无法合并为 [..., \"null\"]",
                    ReviewFlag.NEEDS_REVIEW,
                )
            }
        }

        // exclusive 布尔 → 数值
        upgradeExclusiveBound(node, "minimum", "exclusiveMinimum", path)
        upgradeExclusiveBound(node, "maximum", "exclusiveMaximum", path)

        // 递归：嵌套 Schema 关键字
        node.get("properties").let { v -> if (v is ObjectNode) v.properties().forEach { (name, s) -> upgradeSchemaObject(s, "$path/properties/$name") } }
        node.get("patternProperties").let { v -> if (v is ObjectNode) v.properties().forEach { (name, s) -> upgradeSchemaObject(s, "$path/patternProperties/$name") } }
        node.get("items").let { v -> if (v is ObjectNode) upgradeSchemaObject(v, "$path/items") }
        node.get("additionalProperties").let { v -> if (v is ObjectNode) upgradeSchemaObject(v, "$path/additionalProperties") }
        node.get("contains")?.let { if (it is ObjectNode) upgradeSchemaObject(it, "$path/contains") }
        node.get("propertyNames")?.let { if (it is ObjectNode) upgradeSchemaObject(it, "$path/propertyNames") }
        node.get("not")?.let { if (it is ObjectNode) upgradeSchemaObject(it, "$path/not") }
        node.get("if")?.let { if (it is ObjectNode) upgradeSchemaObject(it, "$path/if") }
        node.get("then")?.let { if (it is ObjectNode) upgradeSchemaObject(it, "$path/then") }
        node.get("else")?.let { if (it is ObjectNode) upgradeSchemaObject(it, "$path/else") }
        node.get("prefixItems")?.let { v -> if (v is ArrayNode) v.forEachIndexed { i, s -> if (s is ObjectNode) upgradeSchemaObject(s, "$path/prefixItems[$i]") } }
        for (key in listOf("allOf", "anyOf", "oneOf")) {
            node.get(key)?.let { v ->
                if (v is ArrayNode) v.forEachIndexed { i, s -> if (s is ObjectNode) upgradeSchemaObject(s, "$path/$key[$i]") }
            }
        }
        node.get("\$defs").let { v -> if (v is ObjectNode) v.properties().forEach { (name, s) -> upgradeSchemaObject(s, "$path/\$defs/$name") } }
    }

    /** `exclusiveMinimum: true`（布尔）→ 数值；数值形态（已是 3.1）不动 */
    private fun upgradeExclusiveBound(obj: ObjectNode, boundKey: String, exclKey: String, path: String) {
        val excl = obj.get(exclKey) ?: return
        if (excl !is BooleanNode) return // 数值 = 已是 3.1

        obj.remove(exclKey)
        if (!excl.booleanValue()) return // false → 无约束，删除即可

        val bound = obj.get(boundKey)
        if (bound != null && bound.isNumber) {
            obj.set(exclKey, bound.deepCopy())
            obj.remove(boundKey) // 3.1 exclusive* 数值已含语义；保留 minimum 会叠加，故移除
            log += UpgradeRecord(
                UpgradeKind.EXCLUSIVE_BOUND, path,
                "exclusive$boundKey: true + $boundKey=$bound → $exclKey=${bound}（原 $boundKey 移除）",
            )
        } else {
            issues += ParseIssue.warning("exclusive-without-bound", "exclusive$boundKey: true 但缺少数字型 $boundKey，无法转数值（已忽略）", path)
            log += UpgradeRecord(
                UpgradeKind.EXCLUSIVE_BOUND, path,
                "exclusive$boundKey: true 但缺 $boundKey，无法转数值",
                ReviewFlag.NEEDS_REVIEW,
            )
        }
    }
}