package com.ctf.bilisb.player

import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * 8.96.0 原版 video director 监听器。
 *
 * 8.96.0 原版路径:`getPlayDirectorServiceV3()` 返回 `PlayDirectorServiceV3`,
 * 调用 `addVideoDirectorObserver(VideoDirectorObserver)`,其中 `VideoDirectorObserver` 接口的
 * `onItemStart` 等回调可能包含 Video 或 aid/cid 信息。
 */
object VideoDirectorListener {
    private const val GET_PLAY_DIRECTOR_SERVICE_V3 = "getPlayDirectorServiceV3"
    private const val ADD_VIDEO_DIRECTOR_OBSERVER = "addVideoDirectorObserver"
    private val registeredContainers = ConcurrentHashMap.newKeySet<Int>()

    /**
     * 在 [playerContainer] 上注册 video director 监听器。
     */
    fun register(module: XposedModule, playerContainer: Any, onVideoIds: (aid: Long, cid: Long) -> Unit) {
        val containerKey = System.identityHashCode(playerContainer)
        if (!registeredContainers.add(containerKey)) {
            return
        }

        val directorService = runCatching {
            val method = playerContainer.javaClass.getDeclaredMethod(GET_PLAY_DIRECTOR_SERVICE_V3).apply { isAccessible = true }
            method.invoke(playerContainer)
        }.getOrNull() ?: run {
            module.info("videoDirector: getPlayDirectorServiceV3 null on ${playerContainer.javaClass.name}")
            registeredContainers.remove(containerKey)
            return
        }

        // VideoDirectorObserver 接口(8.96.0 原版)
        val observerInterface = runCatching {
            playerContainer.javaClass.classLoader?.loadClass("tv.danmaku.biliplayerv2.service.VideoDirectorObserver")
        }.getOrNull() ?: run {
            module.info("videoDirector: VideoDirectorObserver interface not found")
            registeredContainers.remove(containerKey)
            return
        }

        val observer = Proxy.newProxyInstance(
            playerContainer.javaClass.classLoader,
            arrayOf(observerInterface),
            VideoDirectorObserverHandler(module, onVideoIds)
        )

        runCatching {
            val method = directorService.javaClass.getDeclaredMethod(ADD_VIDEO_DIRECTOR_OBSERVER, observerInterface)
                .apply { isAccessible = true }
            method.invoke(directorService, observer)
            module.info("videoDirector: observer registered on ${directorService.javaClass.name}")
        }.onFailure {
            registeredContainers.remove(containerKey)
            module.info("videoDirector: addVideoDirectorObserver failed: ${it.message}")
        }
    }

    fun unregister(playerContainer: Any) {
        registeredContainers.remove(System.identityHashCode(playerContainer))
    }

    private class VideoDirectorObserverHandler(
        private val module: XposedModule,
        private val onVideoIds: (Long, Long) -> Unit,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "toString" -> "VideoDirectorObserverProxy"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
            // VideoDirectorObserver 接口方法:onItemStart / onItemWillChange / onItemCompleted / onPlayableParamsChanged
            if (args != null && args.isNotEmpty()) {
                for (arg in args) {
                    // 如果参数是 Video 对象,提取 aid
                    if (arg.javaClass.name == "tv.danmaku.biliplayerv2.service.Video") {
                        extractFromVideo(arg)
                        return null
                    }
                    // 如果参数是 *PlayableParams(有 getLogDescription),用正则提取
                    if (arg.javaClass.name.contains("PlayableParams")) {
                        extractFromPlayableParams(arg)
                        return null
                    }
                }
            }
            return null
        }

        private fun extractFromVideo(video: Any) {
            val aidStr = runCatching {
                val field = video.javaClass.getDeclaredField("a").apply { isAccessible = true }
                field.get(video) as? String
            }.getOrNull() ?: return

            val aid = aidStr.toLongOrNull() ?: return

            var cid: Long = 0L
            for (field in video.javaClass.declaredFields) {
                val value = runCatching {
                    field.isAccessible = true
                    field.get(video)
                }.getOrNull()

                if (value is Long && value > 0 && value != aid) {
                    if (cid == 0L) cid = value
                }
            }

            module.info("videoDirector: FOUND aid=$aid cid=$cid (from Video)")
            onVideoIds(aid, cid)
        }

        private fun extractFromPlayableParams(params: Any) {
            // PlayableParams.getLogDescription() → "...aid: 12345, cid: 67890..."
            val logDesc = runCatching {
                val method = params.javaClass.getDeclaredMethod("getLogDescription").apply { isAccessible = true }
                method.invoke(params) as? String
            }.getOrNull() ?: run {
                module.info("videoDirector: getLogDescription not found or null on ${params.javaClass.name}")
                return
            }

            // 正则提取 aid/cid(与 APK vg.java:173 一致)
            val regex = Regex("""^.*aid:\s(\d+),\scid:\s(\d+)$""")
            val match = regex.find(logDesc) ?: run {
                module.info("videoDirector: aid/cid regex not match in: $logDesc")
                return
            }

            val aid = match.groupValues[1].toLongOrNull() ?: return
            val cid = match.groupValues[2].toLongOrNull() ?: return

            module.info("videoDirector: FOUND aid=$aid cid=$cid (from PlayableParams)")
            onVideoIds(aid, cid)
        }
    }
}
