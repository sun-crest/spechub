package io.spechub.spec.index

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 骨架冒烟测试：仅验证「kernel 构建链路 + 跨模块依赖（index→spec-model）」可用，不验证业务（E4 落地）。
 */
class SkeletonSmokeTest {

    @Test
    fun `index depends on spec-model`() {
        assertEquals("io.spechub.spec.model", SpecIndex.consumesModel)
    }
}