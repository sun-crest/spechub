package io.spechub.spec.mock

import io.spechub.spec.index.ApiRecord
import io.spechub.spec.index.MockRuleRecord
import io.spechub.spec.index.SpecIndexStore
import io.spechub.spec.index.Stage
import io.spechub.spec.model.MediaType
import io.spechub.spec.model.Operation
import io.spechub.spec.model.Response
import io.spechub.spec.model.Schema
import io.spechub.spec.model.io.SpecModelIO
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.NullNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode
import java.time.Instant
import java.util.UUID

/*
 * MockEngine —— 按 spec 生成 Mock 响应（I3，§7 选型：MVC + Java 25 虚拟线程，同步代码、同步调试）。
 *
 * 数据流：请求 method + 实际路径（含 {template} 段展开）→ API 匹配 → 取响应 schema
 * → 递归生成样例：schema 类型直生成，命中 MockRule（I2 三档）的字段用规则值；
 * $ref 解析到同 repo components/schemas（按当前消费档位 release/draft 取组件投影）。
 * 消费视图：release 优先、draft 回退（I6 双层版本——Mock 与对外发布契约一致）。
 *
 * 模块边界（§7.4）：只依赖 kernel（spec-model / spec-index），零 Spring；由 server 装配。
 */
class MockEngine(internal val index: SpecIndexStore) {

    /** 单个 mock 响应 */
    data class MockResponse(
        val status: Int,
        val reason: String?,
        val mediaType: String,
        val body: String,
    )

    /** 命中 api + 生成结果（controller / 前端预览共用的返回结构） */
    data class MockResult(
        val apiId: Long?,
        val method: String,
        val path: String,
        val summary: String?,
        val stage: String,
        val matchedPath: String,
        val response: MockResponse,
    )

    private val json = JsonMapper.builder().build()

    /** script=SEQ() / pattern 兜底的确定性计数 */
    private var seq = 0

    /**
     * 按模拟请求生成 Mock 响应；repo 内无匹配 api 时返回 null（404 路由层兜底）。
     * @param requestPath 实际请求路径（不含 query；controller 负责剥离）
     */
    fun generate(repoId: Long, method: String, requestPath: String): MockResult? {
        val rules = index.listMockRules(repoId)
        // release 优先（消费者默认 release 快照），无 release 回退 draft——Mock 与发布契约一致
        val releaseApis = index.listApis(repoId = repoId, stage = Stage.RELEASE)
        val stage = if (releaseApis.isNotEmpty()) Stage.RELEASE else Stage.DRAFT
        val candidates = releaseApis.ifEmpty { index.listApis(repoId = repoId, stage = Stage.DRAFT) }

        val api = match(candidates, method, requestPath) ?: return null
        val schemas = index.listSchemas(repoId, stage)
            .mapNotNull { s ->
                val schema = try {
                    SpecModelIO.toModel(SpecModelIO.parseToTree(s.specJson), Schema::class.java)
                } catch (e: Exception) {
                    null
                }
                schema?.let { s.name to it }
            }
            .toMap()

        val op = try {
            SpecModelIO.toModel(SpecModelIO.parseToTree(api.specJson), Operation::class.java)
        } catch (e: Exception) {
            return null
        }
        val chosen = pickResponse(op) ?: return null
        val (mediaType, body) = renderBody(api.id, chosen.response, schemas, rules)
        return MockResult(
            apiId = api.id,
            method = api.method,
            path = api.path,
            summary = op.summary ?: api.summary,
            stage = stage,
            matchedPath = requestPath,
            response = MockResponse(chosen.status, reasonOf(chosen.status), mediaType, body),
        )
    }

    /** 匹配 API：精确路径优先，其次 `{template}` 段级展开（每段通配）。方法不区分大小写。 */
    private fun match(apis: List<ApiRecord>, method: String, requestPath: String): ApiRecord? {
        val m = method.uppercase()
        val p = requestPath.trim('/')
        return apis.firstOrNull { it.method.uppercase() == m && it.path.trim('/') == p }
            ?: apis.firstOrNull { it.method.uppercase() == m && segmentMatch(it.path, p) }
    }

    private fun segmentMatch(template: String, actual: String): Boolean {
        val t = template.trim('/').split('/')
        val a = actual.trim('/').split('/')
        if (t.size != a.size) return false
        return t.indices.all { i -> (t[i].startsWith("{") && t[i].endsWith("}")) || t[i] == a[i] }
    }

    private data class Chosen(val status: Int, val response: Response)

    /** 挑响应：具体数字码越小越优先 > 2XX 范围  > default；状态码按 Key 语义归一（2XX→200、default→200） */
    private fun pickResponse(op: Operation): Chosen? {
        val sorted = op.responses.entries.sortedWith(
            compareBy(
                { if (it.key == "default") 1 else 0 },
                { if (it.key.uppercase().endsWith("XX")) 1 else 0 },
                { it.key.toIntOrNull() ?: 999 },
            ),
        )
        val chosen = sorted.firstOrNull() ?: return null
        val status = when {
            chosen.key.equals("default", true) -> 200
            chosen.key.uppercase().endsWith("XX") -> (chosen.key.firstOrNull()?.digitToIntOrNull() ?: 2) * 100
            else -> chosen.key.toIntOrNull() ?: 200
        }
        return Chosen(status, chosen.value)
    }

    private fun reasonOf(status: Int): String? = REASONS[status]

    /** 渲染响应体：挑 json 媒体类型（返回非 null），用其 schema 生成样例 */
    private fun renderBody(
        apiId: Long?,
        resp: Response,
        schemas: Map<String, Schema>,
        rules: List<MockRuleRecord>,
    ): Pair<String, String> {
        val entry: Pair<String, MediaType> = (resp.content["application/json"]?.let { "application/json" to it })
            ?: resp.content.entries.firstOrNull { it.value.schema != null }?.let { it.key to it.value }
            ?: resp.content.entries.firstOrNull()?.let { it.key to it.value }
            ?: return "application/json" to "{}"
        val (mediaType, mt) = entry
        val node = generateJson(mt, schemas, rules, apiId)
        return mediaType to json.writeValueAsString(node)
    }

    private fun generateJson(mt: MediaType, schemas: Map<String, Schema>, rules: List<MockRuleRecord>, apiId: Long?): JsonNode {
        val schema = mt.schema ?: return NullNode.instance
        return generate(schema, "", 0, schemas, rules, apiId)
    }

    // ── 递归样例生成 ────────────────────────────────────────────────────────────

    /**
     * @param pathKey 点号路径（`items[].name` 规范形式，数组下标归一为 []），规则匹配用；根为空
     */
    private fun generate(
        schema: Schema,
        pathKey: String,
        depth: Int,
        schemas: Map<String, Schema>,
        rules: List<MockRuleRecord>,
        apiId: Long?,
    ): JsonNode {
        if (depth > 8) return NullNode.instance
        val s = resolveRef(schema, schemas) ?: return NullNode.instance

        // 1) 规则命中优先（可覆盖任意字段）
        val rule = matchRule(pathKey, apiId, rules)
        if (rule != null) applyRule(rule)?.let { return it }

        // 2) 组合关键字
        val combos = s.oneOf.ifEmpty { s.anyOf }
        if (combos.isNotEmpty()) return generate(combos.first(), pathKey, depth + 1, schemas, rules, apiId)
        if (s.allOf.isNotEmpty()) return generateObject(s, pathKey, depth, schemas, rules, apiId)

        // 3) 显式样例 / 约束优先（本地变量规避跨模块 smart cast）
        s.const?.let { return it }
        s.example?.let { return it }
        s.default?.let { return it }
        if (s.`enum`.isNotEmpty()) return s.`enum`.first()

        return when (primaryType(s)) {
            "object" -> generateObject(s, pathKey, depth, schemas, rules, apiId)
            "array" -> {
                val arr: ArrayNode = JsonNodeFactory.instance.arrayNode()
                val n = (s.minItems ?: 1).coerceAtLeast(1)
                repeat(n) { arr.add(generate(s.items ?: Schema(), "$pathKey[]", depth + 1, schemas, rules, apiId)) }
                arr
            }
            "boolean" -> JsonNodeFactory.instance.booleanNode(s.default?.asBoolean(false) ?: true)
            "integer" -> JsonNodeFactory.instance.numberNode(
                s.default?.asInt(0) ?: s.minimum?.toInt() ?: 0,
            )
            "number" -> JsonNodeFactory.instance.numberNode(
                s.default?.asDouble(0.0) ?: s.minimum?.toDouble() ?: 0.0,
            )
            "string" -> StringNode.valueOf(stringValue(s, pathKey.lastSegment()))
            else -> if (s.properties.isNotEmpty() || s.items != null) {
                if (s.items != null) generate(s, pathKey, depth + 1, schemas, rules, apiId)
                else generateObject(s, pathKey, depth, schemas, rules, apiId)
            } else {
                NullNode.instance
            }
        }
    }

    private fun generateObject(
        s: Schema,
        pathKey: String,
        depth: Int,
        schemas: Map<String, Schema>,
        rules: List<MockRuleRecord>,
        apiId: Long?,
    ): ObjectNode {
        val node: ObjectNode = JsonNodeFactory.instance.objectNode()
        for ((name, prop) in s.properties) {
            val childKey = if (pathKey.isEmpty()) name else "$pathKey.$name"
            node.set(name, generate(prop, childKey, depth + 1, schemas, rules, apiId))
        }
        return node
    }

    /** type 缺省时的结构推断（null 分支剥离） */
    private fun primaryType(s: Schema): String? {
        val names = s.typeNames()?.filter { it != "null" }
        if (!names.isNullOrEmpty()) return names.first()
        return when {
            s.properties.isNotEmpty() || s.additionalProperties != null -> "object"
            s.items != null -> "array"
            s.`enum`.isNotEmpty() -> "string"
            else -> null
        }
    }

    private fun stringValue(s: Schema, fieldName: String?): String {
        patternValue(s.format)?.let { return it }
        return fieldName ?: "string"
    }

    // ── $ref 解析 ────────────────────────────────────────────────────────────────

    private fun resolveRef(s: Schema, schemas: Map<String, Schema>): Schema? {
        var current = s
        var hops = 0
        while (hops < 12) {
            // 循环条件拆分：ref 为空即停，避免对可空 ref 做非空断言
            val ref = current.ref ?: break
            val name = ref.substringAfterLast("/")
            val target = schemas[name] ?: return null
            current = target
            hops++
        }
        return current
    }

    // ── 规则引擎（I2 三档：value / pattern / script）─────────────────────────────

    private fun matchRule(pathKey: String, apiId: Long?, rules: List<MockRuleRecord>): MockRuleRecord? =
        rules.filter { ruleMatches(it, pathKey) }.sortedWith(
            compareByDescending<MockRuleRecord> { it.apiId == apiId }
                .thenByDescending { it.fieldPattern.length },
        ).firstOrNull()

    private fun ruleMatches(rule: MockRuleRecord, pathKey: String): Boolean = when (rule.ruleType) {
        RuleType.PATTERN -> regexMatch(rule.fieldPattern, pathKey)
        else -> globMatch(rule.fieldPattern, pathKey)
    }

    private fun applyRule(rule: MockRuleRecord): JsonNode? = when (rule.ruleType) {
        RuleType.VALUE -> literalNode(rule.ruleValue)
        RuleType.PATTERN -> StringNode.valueOf(patternValue(rule.ruleValue.trim()) ?: rule.ruleValue)
        RuleType.SCRIPT -> StringNode.valueOf(evaluateScript(rule.ruleValue))
        else -> null
    }

    /** value 规则的"字面 JSON"解析：合法 JSON 原样（数字/布尔/对象…），否则当文本 */
    private fun literalNode(text: String): JsonNode {
        return try {
            json.readTree(text.trim())
        } catch (e: Exception) {
            StringNode.valueOf(text)
        }
    }

    // ── 内置格式样例（schema.format 与 pattern 规则共用）─────────────────────────

    private fun patternValue(format: String?): String? {
        if (format == null) return null
        return when (format.lowercase()) {
            "email" -> "user@example.com"
            "uuid" -> "9f1d7e3a-2c44-4b8e-8f6a-1a2b3c4d5e6f"
            "uri", "url" -> "https://example.com/api/resource"
            "date-time" -> "2026-01-01T00:00:00Z"
            "date" -> "2026-01-01"
            "time" -> "00:00:00Z"
            "ipv4" -> "192.168.1.1"
            "hostname" -> "example.com"
            "byte" -> "aGVsbG8="
            else -> null
        }
    }

    // ── script DSL（简单表达式求值：内置函数集）────────────────────────────────

    private fun evaluateScript(expr: String): String {
        val fn = Regex("^([A-Z_][A-Z0-9_]*)\\s*\\((.*)\\)$").matchEntire(expr.trim())
        if (fn != null) {
            val name = fn.groupValues[1]
            val args = fn.groupValues[2].split(",").map { it.trim().trim('"', '\'') }
            return when (name) {
                "RANDOM_INT" -> {
                    val lo = args.getOrNull(0)?.toIntOrNull() ?: 0
                    val hi = args.getOrNull(1)?.toIntOrNull() ?: lo
                    if (hi < lo) lo.toString() else (lo..hi).random().toString()
                }
                "RANDOM_STR" -> {
                    val len = (args.getOrNull(0)?.toIntOrNull() ?: 8).coerceIn(1, 64)
                    (1..len).map { ("abcdefghijklmnopqrstuvwxyz0123456789").random() }.joinToString("")
                }
                "UUID" -> UUID.randomUUID().toString()
                "NOW" -> Instant.now().toString()
                "SEQ" -> (seq++).toString()
                else -> expr.trim()
            }
        }
        return expr.trim()
    }

    // ── 匹配基础工具 ─────────────────────────────────────────────────────────────

    private fun globMatch(pattern: String, value: String): Boolean {
        val regex = "^" + pattern.split("*").joinToString(".*") { Regex.escape(it) } + "$"
        return Regex(regex).matches(value)
    }

    private fun regexMatch(pattern: String, value: String): Boolean = try {
        Regex(pattern).matches(value)
    } catch (e: Exception) {
        globMatch(pattern, value)
    }

    private fun String.lastSegment(): String? = if (isEmpty()) null else substringAfterLast(".")
        .let { s -> if (s == "[]") null else s }

    companion object {
        private val REASONS: Map<Int, String> = mapOf(
            200 to "OK", 201 to "Created", 202 to "Accepted", 204 to "No Content",
            400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
            404 to "Not Found", 409 to "Conflict", 422 to "Unprocessable Entity",
            500 to "Internal Server Error",
        )
    }
}

/** 规则类型常量（I2 三档） */
object RuleType {
    const val VALUE = "value"
    const val PATTERN = "pattern"
    const val SCRIPT = "script"
}