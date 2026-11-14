package io.spechub.spec.model.io

import io.spechub.spec.model.OpenApiDoc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode
import java.math.BigDecimal
import java.security.MessageDigest

/*
 * Spechub kernel · spec-model io —— 归一化模型指纹 specHash（开发计划 B3）。
 *
 * 用途：作为 Version 发布快照的主键（P2 I6），也是「文档是否变化」的稳定判别。
 *
 * 归一化规则（对象为已统一到内部 3.1 的模型树）：
 * - 键排序：对象成员按 key 字典序，吸收 paths/components/properties 的书写顺序差异；
 * - 数值规范化：BigDecimal stripTrailingZeros，吸收 `1` / `1.0` / `1.00` 笔法差异；
 * - 删除 null 与空对象/空数组（模型层 @JsonInclude(NON_EMPTY) 已省略，防御性处理）；
 * - Boolean false / 数值 0 明确保留：模型可区分「显式设置」与「缺省」的场景仍区分对待，
 *   不做领域级语义折叠（如 explode:false 与省略）——那是 parser 层归一化（C2）的职责，
 *   升级映射完成后本指纹即为「统一 3.1 文档」的确定性指纹。
 * - 数组保持原序（成员个数与顺序是文档内容），字符串原样保真（含 $ref 字面量）。
 */

object SpecHash {

    /** 文档 → 64 位小写十六进制 SHA-256 */
    fun of(doc: OpenApiDoc): String {
        val tree = SpecModelIO.valueToTree(doc)
        return sha256Hex(canonicalString(tree).toByteArray(Charsets.UTF_8))
    }

    /** 树 → 规范化字符串（幂等；输出决定哈希，不稳定勿改） */
    fun canonicalString(node: JsonNode?): String {
        if (node == null || node.isNull || node.isMissingNode) return "null"
        return when {
            node.isObject -> canonicalObject(node as ObjectNode)
            node.isArray -> canonicalArray(node as ArrayNode)
            node.isBoolean -> node.booleanValue().toString()
            node.isNumber -> canonicalNumber(node.decimalValue())
            node is StringNode -> jsonString(node.stringValue())
            else -> jsonString(node.toString())
        }
    }

    private fun canonicalObject(o: ObjectNode): String {
        val entries = o.properties().asSequence()
            .filter { (_, v) -> !v.isNull && !v.isMissingNode && !isEmptyish(v) }
            .sortedBy { it.key }
            .toList()
        if (entries.isEmpty()) return "{}"
        return entries.joinToString(prefix = "{", separator = ",", postfix = "}") { (k, v) ->
            "${jsonString(k)}:${canonicalString(v)}"
        }
    }

    private fun canonicalArray(a: ArrayNode): String {
        if (a.isEmpty) return "[]"
        return a.joinToString(prefix = "[", separator = ",", postfix = "]") { canonicalString(it) }
    }

    private fun isEmptyish(v: JsonNode): Boolean =
        (v.isObject && v.isEmpty) || (v.isArray && v.isEmpty)

    /** 数值规范：`1.0`→`1`、`100` 不丢位、`0.50`→`0.5` */
    private fun canonicalNumber(bd: BigDecimal): String {
        val stripped = bd.stripTrailingZeros()
        return if (stripped.scale() < 0) stripped.setScale(0).toPlainString() else stripped.toPlainString()
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}