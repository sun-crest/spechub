package io.spechub.spec.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 骨架冒烟测试：仅验证「kernel 构建链路 + JUnit 由 spring-boot-dependencies BOM 对齐（6.0.3）」可用，
 * 不验证业务（B 组落地）。
 */
class SkeletonSmokeTest {

    @Test
    fun `spec-model namespace is reachable`() {
        assertEquals("io.spechub.spec.model", SpecModel.namespace)
    }
}