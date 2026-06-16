package com.ctf.bilisb.player

import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule
import java.util.regex.Pattern

object PlayerBridge {
    private val logDescriptionPattern = Pattern.compile("^.*aid:\\s(\\d+),\\scid:\\s(\\d+)$")

    fun extractState(module: XposedModule, playerContainer: Any): PlayerState? {
        val core = invokeNoArg(playerContainer, "getPlayerCoreService") ?: run {
            module.info("player core service missing on ${playerContainer.javaClass.name}")
            return null
        }

        // APK reference:
        // PlayerHookProvider.q/n/o/z call getPlayerCoreService(), getCurrentPosition(),
        // getDuration() and seekTo(int, boolean). Video ids are not stored on the core
        // service; they are obtained from video-director callbacks. Until that observer
        // module is implemented, we expose duration/progress and leave aid/bvid/cid empty.
        val ids = extractIdsFromPlayerParams(module, playerContainer)
        val currentPositionMs = (invokeNoArg(core, "getCurrentPosition") as? Number)?.toLong() ?: 0L
        val durationMs = (invokeNoArg(core, "getDuration") as? Number)?.toLong() ?: 0L

        return PlayerState(
            aid = ids?.first ?: 0L,
            bvid = "",
            cid = ids?.second ?: 0L,
            durationMs = durationMs,
            currentPositionMs = currentPositionMs,
        )
    }

    private fun extractIdsFromPlayerParams(module: XposedModule, playerContainer: Any): Pair<Long, Long>? {
        val params = invokeNoArg(playerContainer, "getPlayerParams") ?: return null
        val direct = readLongByCandidate(params, "aid", "avId", "avid") to
            readLongByCandidate(params, "cid")
        if (direct.first > 0 && direct.second > 0) {
            return direct
        }

        // This is a runtime probe path. PlayerParamsV2 was not present in the
        // currently decompiled dex set, so field names are treated as uncertain.
        module.info("player params probe class=${params.javaClass.name}")
        params.javaClass.declaredFields.take(24).forEach { field ->
            runCatching {
                field.isAccessible = true
                module.info("player params field ${field.name}:${field.type.name}=${field.get(params)}")
            }
        }
        return null
    }

    private fun readLongByCandidate(target: Any, vararg names: String): Long {
        for (name in names) {
            val value = runCatching {
                target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
            }.getOrNull()
            if (value is Number) {
                return value.toLong()
            }
            val getterName = "get${name.replaceFirstChar { it.uppercaseChar() }}"
            val getterValue = invokeNoArg(target, getterName)
            if (getterValue is Number) {
                return getterValue.toLong()
            }
        }
        return 0L
    }

    private fun parseIds(description: String?): Pair<Long, Long>? {
        if (description.isNullOrBlank()) {
            return null
        }
        val matcher = logDescriptionPattern.matcher(description)
        if (!matcher.matches()) {
            return null
        }
        val aid = matcher.group(1)?.toLongOrNull() ?: return null
        val cid = matcher.group(2)?.toLongOrNull() ?: return null
        return aid to cid
    }

    private fun invokeNoArg(target: Any, methodName: String): Any? {
        return runCatching {
            val method = target.javaClass.getDeclaredMethod(methodName).apply { isAccessible = true }
            method.invoke(target)
        }.getOrNull()
    }
}
