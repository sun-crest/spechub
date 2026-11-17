package io.spechub.spec.parser.export

import io.spechub.spec.model.OpenApiDoc
import io.spechub.spec.model.Schema

/**
 * J4 —— 静态 HTML 门户导出（类 ReadMe.io 的自包含单页，可对外发布，§5.5）。
 * 纯静态：服务端预渲染全部接口 + 内联 CSS + 一个几十行的 vanilla JS 搜索过滤；
 * 无外部依赖、无后端，双击 index.html 即可离线查看。
 */
object HtmlPortalExporter {
    fun export(doc: OpenApiDoc): String =
        buildString {
            val title = doc.info.title.ifBlank { "API 文档" }
            val desc = doc.info.description
            val version = doc.info.version
            append("<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<title>").append(esc(title)).append(" · API 文档</title>")
            append("<style>")
            append(CSS)
            append("</style></head><body>")

            append("<header><h1>").append(esc(title)).append("</h1>")
            desc?.takeIf { it.isNotBlank() }?.let { append("<p class=\"desc\">").append(esc(it)).append("</p>") }
            if (doc.servers.isNotEmpty()) append("<p class=\"server\">服务器：").append(doc.servers.joinToString(" ；") { esc(it.url) }).append("</p>")
            append("<div class=\"search\"><input id=\"q\" placeholder=\"搜索接口 / 路径 / 摘要…\" oninput=\"filter()\">")
            append("<span id=\"count\"></span></div></header>")

            append("<main id=\"list\">")
            doc.paths.toSortedMap().forEach { (path, item) ->
                item.operations().forEach { (method, op) ->
                    val kw = listOf(path, op.summary, op.description, op.operationId, (op.tags + listOf()).joinToString(" ")).filterNotNull().joinToString(" ").lowercase()
                    append("<details class=\"api m-").append(method.lowercase()).append("\" data-k=\"")
                    append(esc(kw))
                    append("\"><summary><span class=\"pill m-").append(method.lowercase()).append("\">").append(method.uppercase())
                    append("</span><code>").append(esc(path)).append("</code><span class=\"sum\">").append(esc(op.summary ?: ""))
                    append("</span></summary>")

                    append("<div class=\"body\">")
                    op.description?.let { if (it.isNotBlank()) append("<p>").append(esc(it)).append("</p>") }
                    if (op.parameters.isNotEmpty()) {
                        append("<h4>参数</h4><table><thead><tr><th>名称</th><th>位置</th><th>类型</th><th>必需</th><th>说明</th></tr></thead><tbody>")
                        op.parameters.forEach { p ->
                            append("<tr><td><code>")
                                .append(esc(p.name))
                                .append("</code></td><td>")
                                .append(p.`in`)
                                .append("</td><td><code>")
                                .append(esc(schemaInline(p.schema)))
                                .append("</code></td>")
                                .append("<td>")
                                .append(if (p.required) "是" else "")
                                .append("</td><td>")
                                .append(esc(p.description ?: ""))
                                .append("</td></tr>")
                        }
                        append("</tbody></table>")
                    }
                    op.requestBody?.let { rb ->
                        append("<h4>请求体</h4>")
                        rb.content.forEach { (media, mt) ->
                            append("<p><code>").append(esc(media)).append("</code>")
                            if (rb.required) append("（必需）")
                            append("</p><pre>").append(esc(schemaBlock(rb.firstSchema()))).append("</pre>")
                        }
                    }
                    if (op.responses.isNotEmpty()) {
                        append("<h4>响应</h4>")
                        op.responses.toSortedMap().forEach { (code, resp) ->
                            append("<p><strong>").append(esc(code)).append("</strong> ").append(esc(resp.description ?: ""))
                            resp.content.entries
                                .firstOrNull()
                                ?.let { (media, _) -> append(" — <code>").append(esc(media)).append("</code>") }
                            append("</p>")
                            resp.content.values
                                .firstOrNull()
                                ?.let { mt -> if (mt.schema != null) append("<pre>").append(esc(schemaBlock(mt.schema))).append("</pre>") }
                        }
                    }
                    append("</div></details>")
                }
            }
            append("</main>")
            append("<script>")
            append(
                """
function filter(){
  var q=document.getElementById('q').value.trim().toLowerCase();
  var all=document.querySelectorAll('details.api');var n=0;
  for(var i=0;i<all.length;i++){var d=all[i];
    var ok=!q||(d.getAttribute('data-k')||'').indexOf(q)>=0;
    d.style.display=ok?'':'none';if(ok)n++;}
  document.getElementById('count').textContent=n+' 条接口';
}
document.addEventListener('DOMContentLoaded',function(){filter();});
                """.trimIndent(),
            )
            append("</script></body></html>")
        }

    private fun schemaInline(s: Schema?): String =
        when {
            s == null -> "*"
            s.enum.isNotEmpty() -> s.enum.joinToString(" | ") { it.toString() }
            else -> s.typeNames()?.joinToString(",") ?: "*"
        }

    private fun schemaBlock(
        s: Schema?,
        depth: Int = 0,
    ): String =
        buildString {
            if (s == null) {
                append("*")
                return@buildString
            }
            val indent = "  ".repeat(depth)
            append(
                s
                    .typeNames()
                    ?.filter { it != "null" }
                    ?.joinToString(" | ")
                    ?.ifBlank { "*" } ?: "*",
            )
            s.format?.let { append(" <").append(it).append(">") }
            if (s.enum.isNotEmpty()) append(" enum(").append(s.enum.joinToString(" | ") { it.toString() }).append(")")
            s.example?.let { append(" example=").append(it) }
            s.ref?.let { append(" (≈").append(it).append(")") }
            append("\n")
            if (depth < 4) {
                val shown = s.properties.entries.take(30)
                if (shown.isNotEmpty()) {
                    for ((name, ps) in shown) {
                        append(indent).append("- ").append(name)
                        if (s.required.contains(name)) append(" *")
                        append(": ").append(schemaInline(ps)).append("\n")
                        if (ps.typeNames()?.contains("object") == true || ps.properties.isNotEmpty() || ps.items != null) {
                            append(schemaBlock(ps, depth + 1))
                        }
                    }
                }
            }
        }

    private fun esc(s: String): String =
        buildString {
            for (c in s) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(c)
                }
            }
        }

    private val CSS =
        """
body{font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;margin:0;background:#fafbfc;color:#24292f}
header{background:#fff;border-bottom:1px solid #e5e7eb;padding:24px 32px}
header h1{margin:0 0 6px;font-size:22px}
.desc{color:#57606a;margin:4px 0}
.server{color:#57606a;font-size:13px;margin:4px 0}
.search{display:flex;align-items:center;gap:12px;margin-top:14px}
.search input{flex:0 1 420px;padding:8px 12px;border:1px solid #d0d7de;border-radius:8px;font-size:14px}
#count{font-size:12px;color:#57606a}
main{padding:24px 32px;max-width:960px}
details.api{border:1px solid #e5e7eb;border-radius:10px;background:#fff;margin-bottom:10px;overflow:hidden}
summary{cursor:pointer;display:flex;align-items:center;gap:12px;padding:12px 16px;list-style:none}
summary::-webkit-details-marker{display:none}
.pill{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;font-weight:600;border-radius:6px;padding:2px 8px;color:#fff;flex:0 0 auto}
.m-get .pill{background:#61affe}.m-post .pill{background:#49cc90}.m-put .pill{background:#fca130}
.m-delete .pill{background:#f93e3e}.m-patch .pill{background:#50e3c2}.m-get{border-left:3px solid #61affe}
.m-post{border-left:3px solid #49cc90}.m-put{border-left:3px solid #fca130}.m-delete{border-left:3px solid #f93e3e}
summary code{font-family:ui-monospace,Menlo,monospace;font-size:13px}
.sum{color:#57606a;font-size:13px;margin-left:auto;text-align:right}
.body{padding:0 16px 14px;border-top:1px dashed #eee;margin-top:8px}
.body h4{margin:12px 0 6px;font-size:13px}
table{border-collapse:collapse;width:100%;font-size:13px}
th,td{text-align:left;padding:6px 10px;border:1px solid #eee}
th{background:#f6f8fa;font-weight:600}
pre{background:#1b1b1f;color:#e6edf3;padding:12px;border-radius:8px;overflow:auto;font-size:12px;line-height:1.5}
code{font-family:ui-monospace,Menlo,monospace}
        """.trimIndent()
}
