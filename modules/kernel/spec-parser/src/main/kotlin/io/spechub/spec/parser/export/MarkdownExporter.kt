package io.spechub.spec.parser.export

import io.spechub.spec.model.MediaType
import io.spechub.spec.model.OpenApiDoc
import io.spechub.spec.model.Operation
import io.spechub.spec.model.Parameter
import io.spechub.spec.model.Response
import io.spechub.spec.model.Schema

/**
 * J4 —— Markdown 导出（用户可读的接口文档手册）。
 * 从 OpenApiDoc 渲染：标题/描述/服务端、每个 path 的方法卡片（参数表、请求体、响应、schema 摘要）。
 * kernel 内纯文本模板，IntelliJ 插件可复用；docx/pdf 由 pandoc 转（软依赖，见 DocumentService）。
 */
object MarkdownExporter {

    fun export(doc: OpenApiDoc): String = buildString {
        val title = doc.info.title.ifBlank { "API 文档" }
        val desc = doc.info.description
        val version = doc.info.version
        append("# ").append(title).append('\n')
        if (!desc.isNullOrBlank()) append('\n').append(desc.trimEnd()).append('\n')
        if (!version.isNullOrBlank()) append("\n> 版本 ").append(version).append('\n')

        if (doc.servers.isNotEmpty()) {
            append("\n## 服务端\n\n")
            doc.servers.forEachIndexed { i, s ->
                append("1. `").append(s.url).append("`")
                if (!s.description.isNullOrBlank()) append(" — ").append(s.description)
                append('\n')
            }
        }

        if (doc.paths.isEmpty()) {
            append("\n*（暂无接口）*\n")
            return@buildString
        }

        // 按字母序的 path 组
        doc.paths.toSortedMap().forEach { (path, item) ->
            val ops = item.operations()
            if (ops.isEmpty()) return@forEach
            append("\n## ").append(path).append('\n')
            ops.forEach { (method, op) ->
                val osum = op.summary
                val odesc = op.description
                val oopid = op.operationId
                append("\n### ").append(method.uppercase()).append(" `").append(path).append("`\n")
                if (!osum.isNullOrBlank()) append("\n").append(osum.trimEnd()).append('\n')
                if (!odesc.isNullOrBlank()) append("\n").append(odesc.trimEnd()).append('\n')
                if (oopid != null) append("\n> operationId: `").append(oopid).append("`\n")
                if (op.parameters.isNotEmpty()) {
                    append("\n**参数**\n\n")
                    append("| 名称 | 位置 | 类型 | 必需 | 说明 |\n")
                    append("|---|---|---|---|---|\n")
                    op.parameters.forEach { p ->
                        append("| `").append(p.name).append("` | ").append(p.`in`)
                            .append(" | ").append(schemaInline(p.schema)).append(" | ")
                            .append(if (p.required) "是" else "—").append(" | ").append(p.description ?: "").append(" |\n")
                    }
                }
                op.requestBody?.let { rb ->
                    append("\n**请求体**\n\n")
                    rb.content.forEach { (media, mt) ->
                        append("- `").append(media).append("`")
                        if (rb.required) append("（必需）")
                        append("：\n")
                        append("\n```text\n").append(schemaBlock(rb.firstSchema())).append("\n```\n")
                    }
                }
                if (op.responses.isNotEmpty()) {
                    append("\n**响应**\n\n")
                    op.responses.toSortedMap().forEach { (code, resp) ->
                        append("- **").append(code).append("** ").append(resp.description ?: "")
                        resp.content.entries.firstOrNull()?.let { (media, mt) ->
                            append(" — `").append(media).append("`")
                        }
                        append('\n')
                        resp.content.values.firstOrNull()?.let { mt ->
                            if (mt.schema != null) {
                                append("\n```text\n").append(schemaBlock(mt.schema)).append("\n```\n")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun schemaInline(s: Schema?): String = when {
        s == null -> "*"
        s.enum.isNotEmpty() -> s.enum.joinToString(" | ") { it.toString() }
        else -> s.typeNames()?.joinToString(",") ?: "*"
    }

    /** 递归缩进的 schema 摘要（属性/数组/enum/const/example） */
    private fun schemaBlock(s: Schema?, depth: Int = 0): String = buildString {
        if (s == null) { append("*"); return@buildString }
        val indent = "  ".repeat(depth)
        append(s.typeNames()?.filter { it != "null" }?.joinToString(" | ")?.ifBlank { "*" } ?: "*")
        s.format?.let { append(" <").append(it).append(">") }
        if (s.enum.isNotEmpty()) append(" enum(").append(s.enum.joinToString(" | ") { it.toString() }).append(")")
        s.example?.let { append(" example=").append(it) }
        s.description?.let { append(" — ").append(it) }
        s.ref?.let { append(" (\$ref=").append(it).append(")") }
        append('\n')
        if (depth < 4) {
            s.properties.entries.take(30).forEach { (name, ps) ->
                append(indent).append("- `").append(name).append("`")
                if (s.required.contains(name)) append(" ⭑")
                append(": ")
                append(schemaInline(ps))
                if (ps.typeNames()?.contains("object") == true || ps.properties.isNotEmpty() || ps.items != null) {
                    append('\n')
                    append(indent).append(schemaBlock(ps, depth + 1).trimEnd('\n')).append('\n')
                } else {
                    append('\n')
                }
            }
        }
    }
}