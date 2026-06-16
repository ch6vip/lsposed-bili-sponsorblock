package com.ctf.bilisb.player

/**
 * 播放器容器桥接。
 *
 * 只负责从 `Ch1.g`(PlayerContainer)上取播放器 core / android context,
 * 用于后续 seek 跳过与 toast 提示。
 *
 * video id(aid / cid)不由这里取 —— 那来自 [VideoDirectorObserver] 的
 * `onStart` 回调,与 APK `PlayerHookProvider.g()` 链路一致。
 */
object PlayerBridge {
    // APK: getPlayerServiceMethodName = "getPlayerCoreService"
    private const val GET_CORE_METHOD = "getPlayerCoreService"

    // APK: getContextMethodName = "getContext"
    private const val GET_CONTEXT_METHOD = "getContext"

    fun coreService(playerContainer: Any): Any? {
        return invokeNoArg(playerContainer, GET_CORE_METHOD)
    }

    fun context(playerContainer: Any): Any? {
        return invokeNoArg(playerContainer, GET_CONTEXT_METHOD)
    }

    fun contextHash(playerContainer: Any): Int {
        return context(playerContainer)?.hashCode() ?: 0
    }

    private fun invokeNoArg(target: Any, methodName: String): Any? {
        return runCatching {
            val method = target.javaClass.getDeclaredMethod(methodName).apply { isAccessible = true }
            method.invoke(target)
        }.getOrNull()
    }
}
