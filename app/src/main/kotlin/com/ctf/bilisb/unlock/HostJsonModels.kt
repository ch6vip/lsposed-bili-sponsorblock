package com.ctf.bilisb.unlock

/** Builds a host REST model through its generated descriptor, retaining host defaults. */
internal object HostJsonModels {
    fun build(cl: ClassLoader, modelName: String, updates: Map<String, Any?>, original: Any? = null): Any {
        val descriptor = cl.loadClass("${modelName}_JsonDescriptor").getConstructor().newInstance()
        val properties = descriptor.javaClass.getMethod("getProperties").invoke(descriptor) as Array<*>
        val get = descriptor.javaClass.getMethod("get", Any::class.java, Int::class.javaPrimitiveType)
        val values = Array<Any?>(properties.size) { i ->
            if (original == null) null else get.invoke(descriptor, original, i)
        }
        val remaining = updates.keys.toMutableSet()
        for ((i, property) in properties.withIndex()) {
            requireNotNull(property)
            val nameField = property.javaClass.getDeclaredField("a").apply { isAccessible = true }
            val primary = nameField.get(property) as String
            val aliases = property.javaClass.getMethod("g").invoke(property) as? Array<*>
            val key = (listOf(primary) + aliases.orEmpty().filterIsInstance<String>())
                .firstOrNull { it in updates } ?: continue
            values[i] = updates[key]
            remaining.remove(key)
        }
        require(remaining.isEmpty()) { "Host model fields not found: $remaining" }
        return descriptor.javaClass.getMethod("constructWith", Array<Any?>::class.java).invoke(descriptor, values)
    }
}
