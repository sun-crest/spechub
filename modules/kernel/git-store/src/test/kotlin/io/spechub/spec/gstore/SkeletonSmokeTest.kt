package io.spechub.spec.gstore

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 骨架冒烟测试：仅验证「kernel 构建链路」可用，不验证业务（D 组落地）。
 */
class SkeletonSmokeTest {

    @Test
    fun `git-store namespace is reachable`() {
        assertEquals("io.spechub.spec.gstore", GitStore.namespace)
    }
}