package com.ctf.bilisb.unlock

import org.junit.Assert.*
import org.junit.Test

/** Simulates host-generated descriptors, including null alternate names and reordered fields. */
class HostJsonModelsTest {
    class Property(@JvmField val a: String, private val aliases: Array<String>? = null) {
        fun g(): Array<String>? = aliases
    }
    data class Model(val unknown: Any, val modules: List<String>, val id: Long)
    class Model_JsonDescriptor {
        fun getProperties(): Array<Property> = arrayOf(Property("opaque"), Property("modules", arrayOf("sections")), Property("season_id"))
        fun get(model: Any, index: Int): Any = (model as Model).let {
            when (index) { 0 -> it.unknown; 1 -> it.modules; else -> it.id }
        }
        fun constructWith(values: Array<Any?>): Any = Model(values[0] ?: "default", values[1] as? List<String> ?: emptyList(), values[2] as? Long ?: 0L)
    }
    @Test fun changesOnlyNamedPropertyAndPreservesOpaqueMetadata() {
        val metadata = Any()
        val old = Model(metadata, listOf("preview"), 123)
        val changed = HostJsonModels.build(javaClass.classLoader!!, Model::class.java.name, mapOf("modules" to listOf("preview", "feature")), old) as Model
        assertSame(metadata, changed.unknown)
        assertEquals(123L, changed.id)
        assertEquals(listOf("preview", "feature"), changed.modules)
        assertEquals(listOf("preview"), old.modules)
    }
    @Test fun aliasesWorkAndMissingFieldsFailBeforeCreatingModel() {
        val changed = HostJsonModels.build(javaClass.classLoader!!, Model::class.java.name, mapOf("sections" to listOf("feature"))) as Model
        assertEquals(listOf("feature"), changed.modules)
        assertThrows(IllegalArgumentException::class.java) {
            HostJsonModels.build(javaClass.classLoader!!, Model::class.java.name, mapOf("wrongName" to 1))
        }
    }
}
