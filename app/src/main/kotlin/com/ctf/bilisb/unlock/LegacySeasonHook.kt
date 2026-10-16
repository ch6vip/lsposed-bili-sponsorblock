package com.ctf.bilisb.unlock

import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HostTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

/** 3.20.4 section REST response adapter; preserves all metadata and native results on failure. */
internal object LegacySeasonHook {
    private val executor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "BiliSB-OldSeason").apply { isDaemon = true } }
    }

    fun install(module: XposedModule, cl: ClassLoader): Boolean {
        val api = runCatching { cl.loadClass(HostTargets.LEGACY_SECTION_API) }.getOrNull() ?: return false
        runCatching {
            val factory = cl.loadClass(HostTargets.LEGACY_RETROFIT_FACTORY)
            val create = factory.declaredMethods.single {
                it.name == "b" && it.parameterTypes.contentEquals(arrayOf(Class::class.java))
            }
            module.hook(create).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val original = chain.proceed()
                if (chain.getArg(0) != api || original == null) return@intercept original
                runCatching {
                    Proxy.newProxyInstance(cl, arrayOf(api)) { _, method, args ->
                        if (method.name != HostTargets.LEGACY_SECTION_METHOD) return@newProxyInstance invoke(original, method, args)
                        val sid = (args?.getOrNull(0) as? Number)?.toInt() ?: 0
                        val config = UnlockConfig.load(module)
                        if (sid <= 0 || !config.enabled || config.servers.isEmpty()) return@newProxyInstance invoke(original, method, args)
                        val forwarded = args?.map { it }?.toTypedArray() ?: return@newProxyInstance invoke(original, method, args)
                        val continuation = forwarded.getOrNull(1)
                        if (continuation != null && method.parameterTypes[1].isInterface) {
                            forwarded[1] = Proxy.newProxyInstance(cl, arrayOf(method.parameterTypes[1])) { _, cb, cbArgs ->
                                val delivery = cbArgs?.map { it }?.toTypedArray()
                                if (cb.name == "resumeWith" && !delivery.isNullOrEmpty()) {
                                    delivery[0] = transform(module, cl, sid, delivery[0])
                                }
                                invoke(continuation, cb, delivery)
                            }
                        }
                        transform(module, cl, sid, invoke(original, method, forwarded))
                    }
                }.getOrDefault(original)
            }
            HookProbe.ok(module, "seasonMoss", "legacy REST ${api.name}#${HostTargets.LEGACY_SECTION_METHOD}")
        }.onFailure { HookProbe.miss(module, "seasonMoss", "legacy REST: ${it.javaClass.simpleName}: ${it.message}") }
        return true
    }

    private fun transform(module: XposedModule, cl: ClassLoader, sid: Int, original: Any?): Any? {
        if (original == null || original.javaClass.name != HostTargets.LEGACY_OKRETRO_SUCCESS_RESPONSE_CLASS) return original
        return runCatching {
            val model = original.javaClass.getMethod("a").invoke(original) ?: return original
            if (model.javaClass.name != HostTargets.LEGACY_SECTION_MODEL) return original
            val current = model.javaClass.getMethod("d").invoke(model) as? List<*> ?: return original
            // A feature section already populated by the host is authoritative.
            val hasFeature = current.filterNotNull().any { section ->
                val type = section.javaClass.getMethod("m").invoke(section) as? Enum<*>
                type?.name == "FEATURE" && (section.javaClass.getMethod("b").invoke(section) as? List<*>)?.isNotEmpty() == true
            }
            if (hasFeature) return original
            val episodes = BoundedCall.await(executor, 3000) { ViewTabHook.getOrFetchEpisodes(module, sid) }
            if (episodes.isEmpty()) return original
            val feature = LegacySectionModels.section(cl, sid, episodes)
            val newModel = HostJsonModels.build(cl, HostTargets.LEGACY_SECTION_MODEL, mapOf("modules" to current + feature), model)
            original.javaClass.getConstructor(Any::class.java).newInstance(newModel).also {
                HookProbe.first(module, "seasonMoss:legacyInjected", 3) { "sid=$sid episodes=${episodes.size}" }
            }
        }.getOrDefault(original)
    }

    private fun invoke(target: Any, method: Method, args: Array<out Any?>?): Any? = try {
        method.invoke(target, *(args ?: emptyArray()))
    } catch (e: InvocationTargetException) { throw e.targetException ?: e }
}

internal object LegacySectionModels {
    fun section(cl: ClassLoader, sid: Int, episodes: List<SeasonEpisode>): Any {
        require(sid > 0 && episodes.isNotEmpty())
        val pkg = HostTargets.LEGACY_EPISODE_PKG
        val entries = episodes.map { ep ->
            HostJsonModels.build(cl, "$pkg.OGVEpisode", mapOf(
                "ep_id" to ep.epId, "ep_index" to ep.epIndex,
                "aid" to ep.aid, "cid" to ep.cid, "cover" to ep.cover,
                "title" to ep.title, "long_title" to ep.longTitle,
                "link" to ep.link.ifBlank { "bilibili://bangumi/play/ep${ep.epId}" },
                "bvid" to ep.bvid, "pub_time" to ep.pubTime, "duration" to ep.duration,
            ))
        }
        return HostJsonModels.build(cl, "$pkg.EpisodeSection", mapOf(
            "title" to "正片", "section_id" to sid.toLong(), "episodes" to entries,
            "type" to cl.loadClass("$pkg.SectionType").getField("FEATURE").get(null),
        ))
    }
}
