package com.ctf.bilisb.unlock

import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HostTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * K/gRPC 版播放链路（**离线下载引擎走的正是这条**）。
 *
 * 宿主 6.6.0 有两套播放传输，互相独立：
 *  - MOSS：`com.bilibili.lib.moss.api` 之上的 `PlayerMoss`（播放器链路，[PlayViewHook] 覆盖）；
 *  - K/gRPC：`xr1.j` → `grpc.biliapi.net`（**离线下载引擎链路**）。下载引擎在 `:download` 进程里
 *    `new KPlayerMoss().playViewUnite(req, continuation)`（`video.biz.offline.base.infra.download.TaskGroup`），
 *    我们原先只挂 MOSS 版，于是该调用无人拦截 → 引擎拿到宿主自己的区域受限响应 → 流为空 →
 *    `ADDRESS_EMPTY` → 任务建好后立刻「已暂停：缓存失败，请删除重试」（2026-10-05 真机现象）。
 *
 * 拦截点选 `xr1.j.b(descriptor, req, ser, deser, callback, h, protoBuf)`：它是 K 侧唯一的发送入口，
 * 一次就能拿到方法描述符、请求对象与响应回调（`xr1.m` 由它内部 new 出来，回调即换成了我们的代理）。
 *
 * 本文件目前只做**观测**：确认受限内容上到底是 `onError` 还是「空 `onNext`」，据此再定替换形态。
 *
 * 替换形态待定，但**核心已就位**：K 适配器将来只需把 `xr1.j` 请求转成
 * [RoamingUnlockCore.UnlockRequestFacts]、把 `onNext` 的 reply 序列化成字节，即可复用同一份
 * 网络/解析/重建（[RoamingUnlockCore]）。本文件在此之前保持只观测，不做任何替换。
 */
object KPlayViewHook {

    private const val TAG = "KPlayViewHook"
    private const val SEND_CLASS = "xr1.j"
    private const val SEND_METHOD = "b"
    private const val PLAY_VIEW_UNITE = "PlayViewUnite"

    fun install(module: XposedModule, cl: ClassLoader) {
        try {
            val cls = Class.forName(SEND_CLASS, false, cl)
            val candidates = cls.declaredMethods.filter {
                it.name == SEND_METHOD && it.parameterTypes.size >= 6
            }
            if (candidates.isEmpty()) {
                HookProbe.miss(module, "k:send", "$SEND_CLASS#$SEND_METHOD not found")
                return
            }
            for (m in candidates) {
                runCatching { m.isAccessible = true }
                runCatching { module.deoptimize(m) }
                hookOne(module, cl, m)
            }
            HookProbe.ok(module, "k:send", candidates.joinToString(", ") { "${it.name}(${it.parameterTypes.size})" })
        } catch (t: Throwable) {
            HookProbe.miss(module, "k:send", "install threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun hookOne(module: XposedModule, cl: ClassLoader, m: Method) {
        module.hook(m)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val args = chain.args
                val descriptor = args.getOrNull(0)
                val req = args.getOrNull(1)
                val callback = args.getOrNull(4)
                val method = describeMethod(descriptor)
                if (method != PLAY_VIEW_UNITE) return@intercept chain.proceed()
                HookProbe.first(module, "k:req", 5) {
                    "method=$method argc=${args.size} req=${req?.javaClass?.simpleName} " +
                        "cid=${call(req, "getCid")} ep=${call(req, "getEpId")} " +
                        "season=${call(req, "getSeasonId")} qn=${call(req, "getQn")} " +
                        "fnval=${call(req, "getFnval")} download=${call(req, "getDownload")} " +
                        "cb=${callback?.javaClass?.simpleName}"
                }
                if (callback == null) return@intercept chain.proceed()
                val wrapped = Proxy.newProxyInstance(
                    cl,
                    callback.javaClass.interfaces,
                    InvocationHandler { _, callbackMethod, callbackArgs ->
                        when (callbackMethod.name) {
                            "onNext" -> {
                                val value = callbackArgs?.firstOrNull()
                                HookProbe.first(module, "k:resp", 5) {
                                    "value=${value?.javaClass?.simpleName} streams=${streamCount(value)}"
                                }
                            }
                            "onError" -> {
                                val err = callbackArgs?.firstOrNull()
                                HookProbe.first(module, "k:respErr", 5) {
                                    "error=${err?.javaClass?.simpleName}: $err"
                                }
                            }
                        }
                        callbackMethod.invoke(callback, *(callbackArgs ?: emptyArray()))
                    },
                )
                chain.proceed(arrayOf(args[0], args[1], args[2], args[3], wrapped, args[5], args[6]))
            }
    }

    /** 方法描述符 `xr1.g`：b=serviceName, c=methodName（toString 也一并记下便于核对）。 */
    private fun describeMethod(descriptor: Any?): String {
        if (descriptor == null) return ""
        return call(descriptor, "toString")?.toString()?.contains(PLAY_VIEW_UNITE)?.let {
            if (it) PLAY_VIEW_UNITE else ""
        } ?: ""
    }

    private fun streamCount(reply: Any?): Int {
        if (reply == null) return -1
        val vodInfo = invoke(reply, "getVodInfo") ?: return -2
        val list = invoke(vodInfo, "getStreamList") as? List<*> ?: return -3
        return list.size
    }

    private fun call(target: Any?, name: String): Any? = invoke(target, name)

    private fun invoke(target: Any?, name: String): Any? {
        if (target == null) return null
        return runCatching {
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                ?.invoke(target)
        }.getOrNull()
    }
}
