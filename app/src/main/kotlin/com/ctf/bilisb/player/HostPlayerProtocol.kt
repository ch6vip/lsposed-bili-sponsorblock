package com.ctf.bilisb.player

import com.ctf.bilisb.host.HookResolve
import com.ctf.bilisb.host.HostTargets
import java.lang.reflect.Modifier

/** Typed reflection for host short names. Never invoke an unrelated q/P/D with the same arity. */
internal object HostPlayerProtocol {
    fun invokeTyped(target: Any, names: List<String>, returnTypes: Collection<String>): Any? {
        for (name in names) {
            val method = HookResolve.forTarget(target, listOf(name)) ?: continue
            if (Modifier.isStatic(method.modifiers) || !matchesType(method.returnType, returnTypes)) continue
            val value = runCatching { method.invoke(target) }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    fun readTypedField(target: Any, types: Collection<String>): Any? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || !matchesType(field.type, types)) continue
                val value = runCatching { field.isAccessible = true; field.get(target) }.getOrNull()
                if (value != null) return value
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun matchesType(type: Class<*>, names: Collection<String>): Boolean {
        if (type.name in names) return true
        if (type.interfaces.any { matchesType(it, names) }) return true
        return type.superclass?.let { matchesType(it, names) } ?: false
    }

    fun currentVideo(service: Any): Any? = invokeTyped(
        service, HostTargets.DIRECTOR_CURRENT_VIDEO_METHODS, HostTargets.DIRECTOR_CURRENT_VIDEO_CLASSES,
    )

    fun knownVideoIds(video: Any): Pair<Long, Long>? {
        // Keep accessors paired with their exact return types: Video$e in 3.20.4 is NOT
        // the old Video$e video interface, and T() is interaction metadata, not aid/cid.
        val details = invokeTyped(
            video, listOf(HostTargets.VIDEO_DETAILS_ACCESSOR), listOf(HostTargets.VIDEO_DETAILS_CLASS),
        )
        if (details != null) {
            val aid = (HookResolve.invokeNoArg(details, HostTargets.VIDEO_DETAILS_AID_METHODS) as? Number)?.toLong()
            val cid = (HookResolve.invokeNoArg(details, HostTargets.VIDEO_DETAILS_CID_METHODS) as? Number)?.toLong()
            if (aid != null && cid != null && aid > 0 && cid > 0) return aid to cid
        }

        // 6.7.1 主路径：Video$e#D() -> KL1.f（P2P 稿件元数据，c=aid / d=cid）。
        // 先于 Video$a 尝试：KL1.f 是 6.7.1 上非空且字段语义被宿主装配代码实证的那个。
        val playDetails = invokeTyped(
            video, listOf(HostTargets.PLAY_DETAILS_ACCESSOR), listOf(HostTargets.PLAY_DETAILS_CLASS),
        )
        if (playDetails != null) {
            val aid = (HookResolve.readField(playDetails, HostTargets.PLAY_DETAILS_AID_FIELD) as? Number)?.toLong()
            val cid = (HookResolve.readField(playDetails, HostTargets.PLAY_DETAILS_CID_FIELD) as? Number)?.toLong()
            if (aid != null && cid != null && aid > 0 && cid > 0) return aid to cid
        }

        val params = invokeTyped(
            video, listOf(HostTargets.VIDEO_PARAMS_ACCESSOR), listOf(HostTargets.VIDEO_PARAMS_CLASS),
        )
        if (params != null) {
            val aid = (HookResolve.readField(params, HostTargets.VIDEO_PARAMS_AID_FIELD) as? Number)?.toLong()
            val cid = (HookResolve.readField(params, HostTargets.VIDEO_PARAMS_CID_FIELD) as? Number)?.toLong()
            if (aid != null && cid != null && aid > 0 && cid > 0) return aid to cid
        }
        return null
    }
}
