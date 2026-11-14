package io.spechub.spec.parser

import io.spechub.spec.model.OpenApiDoc

/*
 * SpecHub kernel · spec-parser —— 自研 OpenAPI 解析（3.0/3.1 → 内部 3.1 归一化）
 *
 * 骨架阶段占位（开发计划：C1 直读解析 / C2 3.0→3.1 语义升级 / C3 变更日志 / C4 容错收集）。
 * 2.0 结构化升级推迟到 P2 J2（产品方案 §6.5）。
 * 本文件在 C 组落地时删除。
 */
object SpecParser {
    const val namespace = "io.spechub.spec.parser"
    val consumesModel = OpenApiDoc::class.java.name // 依赖方向自证：parser → spec-model
}