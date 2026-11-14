package io.spechub.spec.parser

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 骨架冒烟测试：仅验证「kernel 构建链路 + 跨模块依赖（parser→spec-model）」可用，不验证业务（C 组落地）。
 */
class SkeletonSmokeTest {

    @Test
    fun `parser depends on spec-model`() {
        assertEquals("io.spechub.spec.model.OpenApiDoc", SpecParser.consumesModel)
    }
}