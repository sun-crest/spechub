package io.spechub.spec.index

import io.spechub.spec.model.SpecModel

/*
 * SpecHub kernel · spec-index —— SQLite 索引 DAO
 *
 * 骨架阶段占位（开发计划：E4 SQLite 表 + 唯一键 + api↔schema 引用）。
 * 本文件在 E4 落地时删除。
 */
object SpecIndex {
    const val namespace = "io.spechub.spec.index"
    val consumesModel = SpecModel.namespace // 依赖方向自证：index → spec-model
}