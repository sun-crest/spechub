package io.spechub.spec.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.security.MessageDigest

/** spec-model 统一的 JSON / YAML 序列化入口（单例，供指纹与快照使用）。 */
object SpecJson {
    /** canonical JSON：字段顺序固定（Kotlin data class 属性顺序），用于 specHash 与 Version 快照。 */
    val json: ObjectMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .build()

    val yaml: ObjectMapper = YAMLMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()
}

fun OpenApiDoc.toCanonicalJson(): String = SpecJson.json.writeValueAsString(this)

/** 归一化模型指纹 —— 作为 Version 发布快照主键（§6.4 决策 5 / P2 I6）。 */
fun OpenApiDoc.specHash(): String {
    val canonical = toCanonicalJson()
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}