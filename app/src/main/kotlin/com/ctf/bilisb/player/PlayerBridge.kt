package com.ctf.bilisb.player

import com.ctf.bilisb.util.info
import com.ctf.bilisb.util.AidBvidConverter
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
        val videoIds = extractIdsFromPlayerParams(module, playerContainer)
        val currentPositionMs = (invokeNoArg(core, "getCurrentPosition") as? Number)?.toLong() ?: 0L
        val durationMs = (invokeNoArg(core, "getDuration") as? Number)?.toLong() ?: 0L
        val bvid = videoIds?.bvid ?: videoIds?.aid?.takeIf { it > 0 }?.let(AidBvidConverter::aidToBvid).orEmpty()

        return PlayerState(
            aid = videoIds?.aid ?: 0L,
            bvid = bvid,
            cid = videoIds?.cid ?: 0L,
            durationMs = durationMs,
            currentPositionMs = currentPositionMs,
        )
    }

    fun contextHash(playerContainer: Any): Int {
        val context = invokeNoArg(playerContainer, "getContext")
        return context?.hashCode() ?: 0
    }

    fun context(playerContainer: Any): Any? {
        return invokeNoArg(playerContainer, "getContext")
    }

    fun coreService(playerContainer: Any): Any? {
        return invokeNoArg(playerContainer, "getPlayerCoreService")
    }

    private fun extractIdsFromPlayerParams(module: XposedModule, playerContainer: Any): VideoIds? {
        val params = invokeNoArg(playerContainer, "getPlayerParams") ?: return null
        val direct = findVideoIds(params, 0, mutableSetOf())
        if (direct != null && (direct.aid > 0 || direct.bvid.isNotBlank()) && direct.cid > 0) {
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

    private fun findVideoIds(target: Any, depth: Int, seen: MutableSet<Int>): VideoIds? {
        if (depth > 2 || !seen.add(System.identityHashCode(target))) {
            return null
        }

        val ids = VideoIds(
            aid = readLongByCandidate(target, "aid", "avId", "avid"),
            cid = readLongByCandidate(target, "cid"),
            bvid = readStringByCandidate(target, "bvid", "bvId"),
        )
        if ((ids.aid > 0 || ids.bvid.isNotBlank()) && ids.cid > 0) {
            return ids
        }

        target.javaClass.declaredFields.forEach { field ->
            val nested = runCatching {
                field.isAccessible = true
                field.get(target)
            }.getOrNull() ?: return@forEach
            if (nested.javaClass.name.startsWith("java.")) {
                return@forEach
            }
            val nestedIds = findVideoIds(nested, depth + 1, seen)
            if (nestedIds != null) {
                return nestedIds
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

    private fun readStringByCandidate(target: Any, vararg names: String): String {
        for (name in names) {
            val value = runCatching {
                target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
            }.getOrNull()
            if (value is String && value.isNotBlank()) {
                return value
            }
            val getterName = "get${name.replaceFirstChar { it.uppercaseChar() }}"
            val getterValue = invokeNoArg(target, getterName)
            if (getterValue is String && getterValue.isNotBlank()) {
                return getterValue
            }
        }
        return ""
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

    private data class VideoIds(
        val aid: Long,
        val cid: Long,
        val bvid: String,
    )
}
