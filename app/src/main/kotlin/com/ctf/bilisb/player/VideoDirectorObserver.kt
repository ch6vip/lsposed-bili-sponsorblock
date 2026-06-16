package com.ctf.bilisb.player

import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.regex.Pattern

/**
 * 播放器 video director 观察者。
 *
 * 复刻 APK 中 `PlayerHookProvider.h(obj, new so(1, obj))` 的链路:
 * 播放器切到新视频时,`VideoDirectorObserver.onStart(current, previous)` 回调触发,
 * 对 `current` 调用 `getLogDescription()` 得到形如 `....aid: 12345, cid: 67890` 的字符串,
 * 再用正则 `^.*aid:\s(\d+),\scid:\s(\d+)$` 提取 aid / cid。
 *
 * 这条路径是 APK 原版 SponsorBlock 获取 video id 的唯一来源(见
 * `PlayerHookProvider.g()` 与 `vg.java:173` 的正则),不依赖 `PlayerParamsV2` 字段反射。
 */
object VideoDirectorObserver {
    // 与 APK `vg.java:173` 中 `new yl("^.*aid:\\s(\\d+),\\scid:\\s(\\d+)$")` 一致。
    private val descriptionPattern = Pattern.compile("^.*aid:\\s(\\d+),\\scid:\\s(\\d+)$")

    // APK `PlayerHookProvider` init: videoDirectorObserverInterfaceName
    private const val OBSERVER_INTERFACE = "tv.danmaku.biliplayerv2.service.VideoDirectorObserver"

    // APK 取 director 服务时按顺序尝试的三个方法名(getPlayDirectorMethod1/2/3Name)。
    private val directorServiceGetters = listOf(
        "getPlayDirectorServiceV3",
        "getVideoPlayDirectorService",
    )

    // APK: addVideoDirectorObserverMethodName = "addVideoDirectorObserver"
    private const val REGISTER_METHOD = "addVideoDirectorObserver"

    // APK: getLogDescriptionMethodName = "getLogDescription"
    private const val LOG_DESCRIPTION_METHOD = "getLogDescription"

    /**
     * 在 [playerContainer] 上注册 video director 观察者。
     *
     * 容器创建后(对应 APK `onPlayerContainerCreate` 里调用 `PlayerHookProvider.h`):
     * 1. 从容器取 director 服务(两个候选方法名按序尝试,APK 第三个候选是 BuildConfig.FLAVOR 空串,跳过)。
     * 2. 在 director 服务上调用 `addVideoDirectorObserver(proxy)`,proxy 实现
     *    `VideoDirectorObserver` 接口。
     * 3. proxy 的双参 void 方法(即 `onStart(current, previous)`)触发时,
     *    解析 `current.getLogDescription()` 得到 aid / cid,回调给 [onVideoIds]。
     *
     * @return 注册成功返回 true;任一步骤失败(接口/方法找不到)返回 false 并记日志。
     */
    fun register(
        module: XposedModule,
        playerContainer: Any,
        onVideoIds: (aid: Long, cid: Long) -> Unit,
    ): Boolean {
        val classLoader = playerContainer.javaClass.classLoader
            ?: run {
                module.info("director observer: missing classloader on ${playerContainer.javaClass.name}")
                return false
            }

        val observerClass = runCatching {
            Class.forName(OBSERVER_INTERFACE, false, classLoader)
        }.getOrElse { throwable ->
            module.info("director observer: interface not found $OBSERVER_INTERFACE: ${throwable.javaClass.name}")
            return false
        }

        val directorService = resolveDirectorService(module, playerContainer) ?: return false

        val proxy = Proxy.newProxyInstance(
            classLoader,
            arrayOf(observerClass),
            DirectorInvocationHandler(module, onVideoIds),
        )

        val registered = invokeOn(module, directorService, REGISTER_METHOD, proxy)
        if (registered == null) {
            module.info(
                "director observer: $REGISTER_METHOD failed on ${directorService.javaClass.name}",
            )
            return false
        }
        module.info("director observer registered on ${directorService.javaClass.name}")
        return true
    }

    private fun resolveDirectorService(module: XposedModule, playerContainer: Any): Any? {
        for (name in directorServiceGetters) {
            val service = invokeOn(module, playerContainer, name)
            if (service != null) {
                return service
            }
        }
        module.info(
            "director observer: no director service on ${playerContainer.javaClass.name} " +
                "(tried ${directorServiceGetters.joinToString { it }})",
        )
        return null
    }

    private fun invokeOn(module: XposedModule, target: Any, methodName: String, vararg args: Any?): Any? {
        return runCatching {
            val method = if (args.isEmpty()) {
                target.javaClass.getDeclaredMethod(methodName)
            } else {
                // 注册 observer 时参数是接口类型,反射装箱后无法精确匹配,
                // 退化为按名 + 参数数量匹配(与 APK 的 xl.a 宽松调用一致)。
                target.javaClass.declaredMethods.first { m ->
                    m.name == methodName && m.parameterTypes.size == args.size
                }
            }
            method.isAccessible = true
            method.invoke(target, *args)
        }.onFailure { throwable ->
            module.info("director observer invoke $methodName failed: ${throwable.javaClass.name}: ${throwable.message}")
        }.getOrNull()
    }

    private class DirectorInvocationHandler(
        private val module: XposedModule,
        private val onVideoIds: (aid: Long, cid: Long) -> Unit,
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            // APK `PlayerHookProvider.g()` 判定 onStart 的条件:
            //   returnType == void && parameterTypes.length == 2 && param[0] == param[1]
            val argArray = args ?: return null
            val isOnStart = method.returnType == Void.TYPE &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == method.parameterTypes[1]

            if (!isOnStart || argArray.isEmpty()) {
                // 其他回调方法(Object 等基类方法、director 的别的 void 方法)直接放过。
                return null
            }

            val current = argArray[0] ?: return null
            val description = invokeOn(module, current, LOG_DESCRIPTION_METHOD) as? String
            if (description.isNullOrBlank()) {
                return null
            }

            val ids = parseIds(description) ?: return null
            module.info("director onStart aid=${ids.first} cid=${ids.second} desc=$description")
            onVideoIds(ids.first, ids.second)
            return null
        }

        private fun parseIds(description: String): Pair<Long, Long>? {
            val matcher = descriptionPattern.matcher(description)
            if (!matcher.matches()) {
                return null
            }
            val aid = matcher.group(1)?.toLongOrNull() ?: return null
            val cid = matcher.group(2)?.toLongOrNull() ?: return null
            if (aid <= 0 || cid <= 0) {
                return null
            }
            return aid to cid
        }
    }
}
