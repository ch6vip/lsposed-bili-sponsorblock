package com.ctf.bilisb.unlock

import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HostTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * S 线 S1 观测钩（docs/SEARCH_UNLOCK_PLAN.md）：`KSearchMoss.searchAll/searchByType`
 * 只读观测，不改任何行为。要回答的问题（§4）：
 *  1. 搜索页实际调用的方法与请求字段（keyword/type/pn/ps…，getter 全 dump）；
 *  2. 响应字节形态（javalite toByteArray / kotlinx serializer encode 双策略）；
 *  3. K serializer 通道（Companion.serializer() + 自建 ProtoBuf）是否可行——
 *     S3「自备 wire 字节→宿主反序列化」的前置验证；
 *  4. 回调接口形态（Ky1/b 的接口方法名，等价 MossResponseHandler 与否）。
 * 捕获上限每方法 6 份，落 `unlock_capture/search_*.bin`。
 */
object SearchObservationHook {

    /**
     * 搜索 moss 服务候选：K 变体（kotlinx）与非 K 变体（javalite + MossResponseHandler）
     * 并存；search2 页面实测未触发 K 变体（S1 第一轮 2026-10-05），非 K 才是
     * BiliRoaming 钩的形态（M0 case 7/8）。两个都挂，探针告诉我们谁在跑。
     */
    private val MOSS_CLASSES = listOf(
        "com.bapis.bilibili.polymer.app.search.v1.SearchMoss",
        "com.bapis.bilibili.polymer.app.search.v1.KSearchMoss",
    )
    private val METHODS = listOf("searchAll", "searchByType")
    private const val MAX_RETRY = 20
    private const val RETRY_DELAY_MS = 1000L
    private const val MAX_CAPTURES_PER_METHOD = 6

    private val attempts = AtomicInteger(0)
    private val captured = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 宿主自己的 ProtoBuf 实例：5 参重载 `searchAll(req, ser, deser, cb, ProtoBuf)` 的
     * 第 5 参。捕获后 kotlinx encode 直接用它（比自建实例更贴近宿主编码配置）。
     */
    @Volatile
    private var hostProtoBuf: Any? = null

    fun install(module: XposedModule, cl: ClassLoader) {
        tryInstall(module, cl)
    }

    private fun tryInstall(module: XposedModule, cl: ClassLoader) {
        if (attempts.incrementAndGet() > MAX_RETRY) {
            HookProbe.miss(module, "search:observe", "give up after $MAX_RETRY attempts")
            return
        }
        try {
            val hooked = mutableListOf<String>()
            for (mossName in MOSS_CLASSES) {
                val moss = runCatching { Class.forName(mossName, false, cl) }.getOrNull() ?: continue
                val tag = mossName.substringAfterLast('.').take(1) // "S"=非K / "K"=K
                for (name in METHODS) {
                    for (m in moss.declaredMethods.filter {
                            it.name == name && it.parameterTypes.size >= 2
                        }) {
                        runCatching { m.isAccessible = true }
                        runCatching { module.deoptimize(m) }
                        hookOne(module, cl, m, tag)
                        hooked += "$tag.${m.name}(${m.parameterTypes.size})"
                    }
                }
            }
            if (hooked.isEmpty()) {
                HookProbe.first(module, "search:observeRetry", 3) {
                    "method not found yet, attempt=${attempts.get()}"
                }
                retry(module, cl)
                return
            }
            HookProbe.ok(module, "search:observe", hooked.joinToString(", "))
            probeKSerializerChannel(module, cl)
            dumpFieldNumbers(module, cl)
        } catch (e: ClassNotFoundException) {
            HookProbe.first(module, "search:observeRetry", 3) {
                "class not loaded yet, attempt=${attempts.get()}"
            }
            retry(module, cl)
        } catch (t: Throwable) {
            HookProbe.miss(module, "search:observe", "install threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun retry(module: XposedModule, cl: ClassLoader) {
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ tryInstall(module, cl) }, RETRY_DELAY_MS)
    }

    private fun hookOne(module: XposedModule, cl: ClassLoader, m: Method, tag: String) {
        val methodName = m.name
        val label = "$tag.$methodName"
        module.hook(m)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                // 5 参 moss-ktx 形态：args[4] 就是宿主 ProtoBuf，捕获复用
                if (m.parameterTypes.size == 5 && chain.args.size >= 5 &&
                    chain.args[4] != null && hostProtoBuf == null
                ) {
                    hostProtoBuf = chain.args[4]
                    HookProbe.first(module, "search:hostProtoBuf", 1) {
                        "captured ${chain.args[4]!!.javaClass.name}"
                    }
                }
                HookProbe.first(module, "search:req:$label", 5) {
                    "${dumpGetters(chain.args.getOrNull(0))}"
                }
                // 请求字节捕获（javalite toByteArray）：wire 解析 type/keyword/pn 用
                runCatching {
                    (chain.args.getOrNull(0))?.let { req ->
                        val bytes = req.javaClass.methods
                            .firstOrNull { it.name == "toByteArray" && it.parameterTypes.isEmpty() }
                            ?.invoke(req) as? ByteArray
                        if (bytes != null && bytes.isNotEmpty()) {
                            val dir = java.io.File(HostTargets.HOST_DATA_DIRS.first(), "unlock_capture")
                            dir.mkdirs()
                            java.io.File(dir, "search_req_$label.bin").outputStream().use { it.write(bytes) }
                            HookProbe.first(module, "search:reqBytes:$label", 3) { "${bytes.size}B" }
                        }
                    }
                }
                // 观测不改数据：请求原样透传，只包第二参回调捕获响应
                if (chain.args.size >= 2 && chain.args[1] != null) {
                    val handler = chain.args[1]
                    val wrapped = runCatching {
                        Proxy.newProxyInstance(
                            cl,
                            handler.javaClass.interfaces,
                            { _, method, args ->
                                if (args != null && args.isNotEmpty() &&
                                    isResponseCarrier(method.name)
                                ) {
                                    observeResponse(module, cl, label, method.name, args[0])
                                }
                                method.invoke(handler, *(args ?: emptyArray()))
                            },
                        )
                    }.getOrNull()
                    if (wrapped != null) {
                        val result = chain.proceed(arrayOf(chain.args[0], wrapped))
                        if (result == null || isSuspendedMarker(result)) return@intercept null
                        observeResponse(module, cl, label, "return", result)
                        return@intercept null
                    }
                    HookProbe.first(module, "search:cbWrapFail:$label", 2) {
                        "interfaces=${handler.javaClass.interfaces.toList()}"
                    }
                }
                val result = chain.proceed(chain.args.toTypedArray())
                observeResponse(module, cl, label, "return", result)
                result
            }
    }

    /** 请求/响应对象的 no-arg getter 全 dump（K 类是普通 getter，javalite 同形）。 */
    private fun dumpGetters(obj: Any?): String {
        if (obj == null) return "null"
        return runCatching {
            obj.javaClass.declaredMethods
                .filter {
                    it.name.startsWith("get") && it.parameterTypes.isEmpty() &&
                        it.returnType == String::class.java || it.returnType.isPrimitive ||
                        it.name in setOf("getType", "getKeyword")
                }
                .sortedBy { it.name }
                .take(16)
                .joinToString(" ") { m ->
                    runCatching {
                        val v = m.invoke(obj)
                        "${m.name.removePrefix("get").lowercase()}=$v"
                    }.getOrDefault("${m.name}=?")
                }
                .take(300)
        }.getOrDefault("${obj.javaClass.name}(dump fail)")
    }

    private fun isResponseCarrier(name: String): Boolean =
        name == "onNext" || name == "onCompleted" || name == "resumeWith" || name == "onSuccess"

    private fun isSuspendedMarker(result: Any?): Boolean =
        result != null && result.javaClass == Any::class.java &&
            result.toString() == "COROUTINE_SUSPENDED"

    /**
     * 响应捕获：javalite toByteArray 优先（非 K 形态），kotlinx 通道兜底
     * （Companion.serializer() + 自建 ProtoBuf encodeToByteArray）。
     */
    private fun observeResponse(
        module: XposedModule,
        cl: ClassLoader,
        methodName: String,
        via: String,
        reply: Any?,
    ) {
        if (reply == null) return
        val counter = captured.computeIfAbsent(methodName) { AtomicInteger(0) }
        val n = counter.incrementAndGet()
        if (n > MAX_CAPTURES_PER_METHOD) return
        // 字节策略一：javalite toByteArray
        var bytes = runCatching {
            reply.javaClass.methods.firstOrNull { it.name == "toByteArray" && it.parameterTypes.isEmpty() }
                ?.invoke(reply) as? ByteArray
        }.getOrNull()
        var viaBytes = "javalite"
        // 字节策略二：kotlinx serializer
        if (bytes == null) {
            bytes = kotlinxEncode(module, cl, reply)
            viaBytes = "kotlinx"
        }
        if (bytes != null) {
            runCatching {
                val dir = java.io.File(HostTargets.HOST_DATA_DIRS.first(), "unlock_capture")
                dir.mkdirs()
                val f = java.io.File(dir, "search_${methodName}_${via}_$n.bin")
                f.outputStream().use { it.write(bytes) }
                HookProbe.first(module, "search:capture:$methodName", 6) {
                    "${f.name} ${bytes.size}B via=$viaBytes"
                }
            }
        } else {
            HookProbe.first(module, "search:noBytes:$methodName", 2) {
                "via=$via cls=${reply.javaClass.name} getters=[${dumpGetters(reply).take(160)}]"
            }
        }
        if (via == "onNext" || via == "resumeWith") {
            HookProbe.first(module, "search:resp:$methodName", 4) {
                "via=$via cls=${reply.javaClass.name}"
            }
        }
    }

    /**
     * K serializer 通道：Companion.serializer() 拿 KSerializer，自建
     * kotlinx.serialization.protobuf.ProtoBuf 实例做 encode。S1 顺带验证 S3 前置。
     */
    private fun kotlinxEncode(module: XposedModule, cl: ClassLoader, obj: Any): ByteArray? {
        return runCatching {
            val companion = obj.javaClass.declaredFields
                .firstOrNull { it.name == "Companion" && java.lang.reflect.Modifier.isStatic(it.modifiers) }
                ?.get(null) ?: return@runCatching null.also {
                    HookProbe.first(module, "search:kserializer", 2) { "${obj.javaClass.simpleName}: no Companion" }
                }
            val serializer = companion.javaClass.methods
                .firstOrNull { it.name == "serializer" && it.parameterTypes.isEmpty() }
                ?.invoke(companion) ?: return@runCatching null.also {
                    HookProbe.first(module, "search:kserializer", 2) { "${obj.javaClass.simpleName}: no serializer()" }
                }
            val protobuf = hostProtoBuf ?: protoBufInstance(cl, module) ?: return@runCatching null.also {
                HookProbe.first(module, "search:kserializer", 2) { "no ProtoBuf instance" }
            }
            val encode = protobuf.javaClass.methods.firstOrNull {
                it.name == "encodeToByteArray" && it.parameterTypes.size == 2
            } ?: return@runCatching null.also {
                HookProbe.first(module, "search:kserializer", 2) { "no encodeToByteArray" }
            }
            encode.invoke(protobuf, serializer, obj) as ByteArray
        }.onFailure { t ->
            HookProbe.first(module, "search:kserializerFail", 2) {
                "${t.javaClass.simpleName}: ${t.message}"
            }
        }.getOrNull()
    }

    /** 自建 ProtoBuf 兜底：优先静态 Default 实例，再逐构造器试默认值，失败留痕。 */
    private fun protoBufInstance(cl: ClassLoader, module: XposedModule): Any? {
        return runCatching {
            val cls = cl.loadClass("kotlinx.serialization.protobuf.ProtoBuf")
            // fun interface 形态：默认实例挂在 Companion（ProtoBuf.Companion.Default）
            runCatching {
                val companion = cls.getDeclaredField("Companion").get(null)
                for (f in companion.javaClass.declaredFields) {
                    if (f.type == cls && f.name in setOf("Default", "DEFAULT")) {
                        f.isAccessible = true
                        return@runCatching f.get(companion)
                    }
                }
            }
            for (f in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                    f.type == cls && f.name in setOf("Default", "DEFAULT")
                ) {
                    f.isAccessible = true
                    return@runCatching f.get(null)
                }
            }
            for (ctor in cls.declaredConstructors) {
                val args = ctor.parameterTypes.map { t ->
                    when (t) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
                }.toTypedArray()
                runCatching {
                    ctor.isAccessible = true
                    return@runCatching ctor.newInstance(*args)
                }.onFailure { t2 ->
                    HookProbe.first(module, "search:protoBufCtor", 3) {
                        "ctor(${ctor.parameterTypes.size}) failed: ${t2.javaClass.simpleName}"
                    }
                }
            }
            null
        }.getOrNull()
    }

    /**
     * javalite 消息类的 *_FIELD_NUMBER 静态 dump（S2/S3 的 wire 拼装依据）：
     * SearchAllResponse（nav 注入位）、SearchByTypeRequest（type/keyword 位）、
     * SearchByTypeResponse（重建目标）。
     */
    private fun dumpFieldNumbers(module: XposedModule, cl: ClassLoader) {
        runCatching {
            for (name in listOf(
                "com.bapis.bilibili.polymer.app.search.v1.SearchAllRequest",
                "com.bapis.bilibili.polymer.app.search.v1.SearchAllResponse",
                "com.bapis.bilibili.polymer.app.search.v1.SearchByTypeRequest",
                "com.bapis.bilibili.polymer.app.search.v1.SearchByTypeResponse",
                "com.bapis.bilibili.polymer.app.search.v1.Item",
                "com.bapis.bilibili.polymer.app.search.v1.Nav",
            )) {
                val cls = runCatching { Class.forName(name, true, cl) }.getOrNull() ?: continue
                val dump = cls.declaredFields
                    .filter { it.name.endsWith("_FIELD_NUMBER") }
                    .sortedBy { it.name }
                    .joinToString(", ") { f ->
                        "${f.name.removeSuffix("_FIELD_NUMBER").lowercase()}=" +
                            runCatching { f.get(null) }.getOrDefault("?")
                    }
                HookProbe.first(module, "search:fields:${name.substringAfterLast('.')}", 1) { dump }
            }
        }
    }

    /** S1 前置验证独立探针：不依赖搜索触发，install 时即验证 serializer 可达性。 */
    private fun probeKSerializerChannel(module: XposedModule, cl: ClassLoader) {
        runCatching {
            val respCls = cl.loadClass("com.bapis.bilibili.polymer.app.search.v1.KSearchByTypeResponse")
            val companion = respCls.declaredFields
                .firstOrNull { it.name == "Companion" }?.get(null)
            val serializer = companion?.javaClass
                ?.methods?.firstOrNull { it.name == "serializer" && it.parameterTypes.isEmpty() }
            val protobuf = protoBufInstance(cl, module)
            HookProbe.first(module, "search:kChannel", 1) {
                "companion=${companion != null} serializer=${serializer != null} " +
                    "protoBuf=${protobuf != null} " +
                    "encode=${protobuf != null && serializer != null && protobuf.javaClass.methods.any { it.name == "encodeToByteArray" && it.parameterTypes.size == 2 }}"
            }
        }.onFailure { t ->
            HookProbe.first(module, "search:kChannel", 1) {
                "probe threw: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
    }
}
