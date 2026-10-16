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
 * 3.20.4 的等效入口为 kntr.base.moss.api.c.d（六参）。通过宿主 ProtoBuf 和
 * serializer 将 K 模型转换到普通 protobuf 响应处理管线，再还原为宿主 K 类型。
 * 复用 [PlayViewHook.ResponseTransformer] 的受限判定、超时和原响应回退；未启用时只观测。
 */
object KPlayViewHook {

    private const val PLAY_VIEW_UNITE = "PlayViewUnite"

    fun install(module: XposedModule, cl: ClassLoader) {
        try {
            val candidates = HostTargets.K_SEND_LAYOUTS.flatMap { (name, method, arity) ->
                val cls = runCatching { Class.forName(name, false, cl) }.getOrNull()
                cls?.declaredMethods.orEmpty().filter {
                    it.name == method && it.parameterTypes.size == arity &&
                        it.parameterTypes[4].isInterface && it.parameterTypes[4].methods.any { cb -> cb.name == "onNext" }
                }
            }
            if (candidates.isEmpty()) {
                HookProbe.miss(module, "k:send", "No supported K send signature")
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
                var effectiveReq = req
                val bridge = runCatching {
                    val config = UnlockConfig.load(module)
                    if (!config.enabled || config.servers.isEmpty()) return@runCatching null
                    val proto = args.getOrNull(6) ?: cl.loadClass("kotlinx.serialization.protobuf.ProtoBuf")
                        .getField("Default").get(null)
                    val codec = HostKCodec(proto)
                    val reqSerializer = args.getOrNull(2) ?: return@runCatching null
                    val replySerializer = args.getOrNull(3) ?: return@runCatching null
                    val javaReq = cl.loadClass(HostTargets.PLAY_VIEW_UNITE_REQ_CLASS)
                        .getMethod("parseFrom", ByteArray::class.java).invoke(null, codec.encode(reqSerializer, req ?: return@runCatching null))
                    val originalFacts = PlayViewHook.extractRequestFacts(module, javaReq)
                    val targetQn = if (QualityPolicy.shouldPatchQuality(config.fullScreenQuality, config.halfScreenQuality)) {
                        QualityPolicy.resolveTargetQn(config.fullScreenQuality, config.halfScreenQuality)
                    } else 0
                    val needsPatch = (config.cacheUnlock && originalFacts.isDownload) || targetQn != 0
                    var patchedReq = javaReq
                    if (needsPatch) {
                        var requestBytes = javaReq.javaClass.getMethod("toByteArray").invoke(javaReq) as ByteArray
                        if (config.cacheUnlock && originalFacts.isDownload) requestBytes = CacheRequestPatch.patch(requestBytes)
                        if (targetQn != 0) requestBytes = CacheRequestPatch.patchQuality(requestBytes, targetQn)
                        val decodedRequest = codec.decode(reqSerializer, requestBytes)
                        require(req.javaClass.isInstance(decodedRequest))
                        patchedReq = cl.loadClass(HostTargets.PLAY_VIEW_UNITE_REQ_CLASS)
                            .getMethod("parseFrom", ByteArray::class.java).invoke(null, requestBytes)
                        effectiveReq = decodedRequest
                    }
                    val facts = PlayViewHook.extractRequestFacts(module, patchedReq).copy(isDownload = originalFacts.isDownload)
                    KReplyBridge(codec, replySerializer, cl,
                        PlayViewHook.ResponseTransformer(module, cl, patchedReq, facts))
                }.getOrNull()
                val wrapped = runCatching { Proxy.newProxyInstance(
                    cl,
                    arrayOf(m.parameterTypes[4]),
                    InvocationHandler { _, callbackMethod, callbackArgs ->
                        runCatching { when (callbackMethod.name) {
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
                        } }
                        val forwarded = callbackArgs?.map { it }?.toTypedArray()
                        if (bridge != null && callbackMethod.name == "onNext" && !forwarded.isNullOrEmpty()) {
                            forwarded[0]?.let { value -> forwarded[0] = bridge.apply(value) }
                        }
                        if (bridge != null && callbackMethod.name == "onError") {
                            val rescued = bridge.rescue(callbackArgs?.firstOrNull() as? Throwable)
                            if (rescued != null) {
                                val onNext = m.parameterTypes[4].methods.single { it.name == "onNext" }
                                val completed = m.parameterTypes[4].methods.single { it.name == "onCompleted" }
                                onNext.invoke(callback, rescued)
                                completed.invoke(callback)
                                return@InvocationHandler null
                            }
                        }
                        try { callbackMethod.invoke(callback, *(forwarded ?: emptyArray())) }
                        catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException ?: e }
                    },
                ) }.getOrNull() ?: return@intercept chain.proceed()
                val replacement = Array<Any?>(args.size) { args[it] }
                replacement[1] = effectiveReq
                replacement[4] = wrapped
                chain.proceed(replacement)
            }
    }

    private class KReplyBridge(
        private val codec: HostKCodec,
        private val serializer: Any,
        private val cl: ClassLoader,
        private val transformer: PlayViewHook.ResponseTransformer,
    ) {
        fun apply(value: Any): Any = codec.transform(value, serializer, serializer) { bytes ->
            val javaReply = cl.loadClass(HostTargets.PLAY_VIEW_UNITE_REPLY_CLASS)
                .getMethod("parseFrom", ByteArray::class.java).invoke(null, bytes)
            val result = transformer.apply("k:onNext", javaReply)
            if (result == null || result === javaReply) null
            else result.javaClass.getMethod("toByteArray").invoke(result) as ByteArray
        }

        fun rescue(error: Throwable?): Any? = runCatching {
            val result = transformer.rescueError(error) ?: return null
            codec.decode(serializer, result.javaClass.getMethod("toByteArray").invoke(result) as ByteArray)
        }.getOrNull()
    }

    /** 方法描述符 `xr1.g`：b=serviceName, c=methodName（toString 也一并记下便于核对）。 */
    private fun describeMethod(descriptor: Any?): String {
        if (descriptor == null) return ""
        val text = runCatching { descriptor.toString() }.getOrDefault("")
        return if (text.contains("bilibili.app.playerunite.v1") && text.contains(PLAY_VIEW_UNITE)) PLAY_VIEW_UNITE else ""
    }

    private fun streamCount(reply: Any?): Int {
        if (reply == null) return -1
        val vodInfo = invoke(reply, "getVodInfo") ?: return -2
        val list = (invoke(vodInfo, "getStreamListList") ?: invoke(vodInfo, "getStreamList")) as? List<*> ?: return -3
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
