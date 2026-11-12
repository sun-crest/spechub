package io.spechub.spec.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FingerprintTest {
    @Test
    fun `hash is stable for identical doc`() {
        val a = OpenApiDoc(info = Info("x", "1"), paths = emptyMap())
        val b = OpenApiDoc(info = Info("x", "1"), paths = emptyMap())
        assertEquals(a.specHash(), b.specHash())
    }

    @Test
    fun `hash differs when content changes`() {
        val a = OpenApiDoc(info = Info("x", "1"))
        val b = OpenApiDoc(info = Info("x", "2"))
        assertNotEquals(a.specHash(), b.specHash())
    }

    @Test
    fun `hash is insensitive to map insertion order`() {
        fun docWith(insertion: List<Pair<String, PathItem>>) = OpenApiDoc(
            paths = LinkedHashMap<String, PathItem>().apply {
                insertion.forEach { (k, v) -> put(k, v) }
            }
        )
        val asc = docWith(listOf("/a" to PathItem(), "/b" to PathItem()))
        val desc = docWith(listOf("/b" to PathItem(), "/a" to PathItem()))
        assertEquals(asc.specHash(), desc.specHash())
    }
}