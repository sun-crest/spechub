package io.spechub.spec.parser

private val OPENAPI_RE = Regex("""openapi\s*:\s*['"]?(\d+)\.(\d+).*""")

/** 粗探测源 spec 的方言版本（精确解析交给 swagger-parser，这里只用于版本标注）。 */
fun detectSpecVersion(source: String): SpecVersion {
    for (line in source.lineSequence()) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("swagger:") -> return SpecVersion.V2
            OPENAPI_RE.matches(trimmed) -> {
                val minor = OPENAPI_RE.find(trimmed)?.groupValues?.get(2)?.toIntOrNull() ?: 0
                return if (minor >= 1) SpecVersion.V310 else SpecVersion.V300
            }
        }
    }
    return SpecVersion.UNKNOWN
}