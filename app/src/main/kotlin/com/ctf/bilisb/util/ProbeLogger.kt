package com.ctf.bilisb.util

import io.github.libxposed.api.XposedModule
import com.ctf.bilisb.util.info
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

object ProbeLogger {
    private val seenClasses = ConcurrentHashMap.newKeySet<String>()

    fun dumpClassOnce(module: XposedModule, prefix: String, target: Any?) {
        if (target == null) {
            module.info("$prefix target=null")
            return
        }

        val clazz = target.javaClass
        if (!seenClasses.add(clazz.name)) {
            return
        }

        module.info("$prefix class=${clazz.name}")

        clazz.declaredFields.forEach { field ->
            runCatching {
                field.isAccessible = true
                val value = field.get(target)
                val valueText = when (value) {
                    null -> "null"
                    is CharSequence -> "\"$value\""
                    else -> value.toString()
                }
                module.info("$prefix field ${field.name}:${field.type.name} = $valueText")
            }
        }

        clazz.declaredMethods
            .asSequence()
            .filterNot { it.isSynthetic || Modifier.isPrivate(it.modifiers) }
        .take(24)
        .forEach { method ->
            module.info("$prefix method ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
        }
    }
}
