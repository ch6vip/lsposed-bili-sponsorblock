package com.ctf.bilisb.hook

import android.os.Handler
import android.os.HandlerThread
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.settings.EnhanceFlags
import com.ctf.bilisb.util.info
import com.ctf.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * 首页（推荐 feed）不自动刷新（移植自 BiliTamer (MIT) 的 HomeNoAutoRefreshHooks.java）。
 *
 * BiliTamer 逆向结论（6.3.0 落点，6.5.0 沿用同类结构）：
 *  com.bilibili.pegasus.vm.PegasusViewModel 的 feed 加载入口第 3 个参数是
 *  PegasusFlush 枚举，标明刷新来源：
 *   - AUTO_BACK_FROM_BACKGROUND(1)：从后台切回时自动刷新（拦截）
 *   - AUTO_BACK_FROM_OTHER_PAGE(9)：从其它页面返回时自动刷新（拦截）
 *   - PULL_DOWN(6)：手动下拉刷新（保留）
 *   - NORMAL(0)：首次加载（保留）
 *   - TAB_CLICK(5)/TAB_DOUBLE_CLICK(13)：点击 tab（保留）
 *
 * 方法名跨版本会变（6.3.0 是 z0，6.4.0 改名 y0），所以**不依赖方法名**：
 * 结构匹配 —— 任意「第 3 参类型是 PegasusFlush」的方法即视为加载入口（跨版本稳定）。
 *
 * 仅当「ViewModel 已有内容」且刷新类型为上述两种自动刷新时短路。
 * 必须放行空状态：厂商 ROM 常在切回 App 时回收进程/重建页面，重建后 ViewModel 无内容，
 * 此时的加载虽带 AUTO_BACK 类型，却是恢复首屏的唯一途径——无条件短路会让首页一直空白。
 *
 * 类找不到时延迟重试（BiliTamer 同款 postDelayed 模式），上限 30 次；
 * 重试跑在自建后台线程，不在主线程做类加载/方法扫描这类重活。
 */
object HomeNoAutoRefreshHooks {

    private const val VM_CLASS = "com.bilibili.pegasus.vm.PegasusViewModel"
    private const val FLUSH_CLS = "com.bilibili.pegasus.data.request.PegasusFlush"
    private const val MAX_ATTEMPTS = 30
    private const val RETRY_DELAY_MS = 500L

    private val homeAttempts = AtomicInteger(0)

    /** 延迟重试用的自建后台 handler：重试会做类加载 + 全类方法扫描，不能压主线程。 */
    private val retryHandler: Handler by lazy {
        val thread = HandlerThread("BiliSB-HomeRetry")
        thread.isDaemon = true
        thread.start()
        Handler(thread.looper)
    }

    fun install(module: XposedModule, cl: ClassLoader) {
        tryInstall(module, cl)
    }

    private fun tryInstall(module: XposedModule, cl: ClassLoader) {
        if (homeAttempts.incrementAndGet() > MAX_ATTEMPTS) {
            HookProbe.miss(module, "noAutoRefresh", "give up after $MAX_ATTEMPTS attempts")
            return
        }
        try {
            val vm = Class.forName(VM_CLASS, false, cl)
            val flush = Class.forName(FLUSH_CLS, false, cl)
            // 结构匹配（跨版本稳定）：任意「第 3 参是 PegasusFlush」的方法。
            // 6.3.0 方法名 z0，6.4.0 改名 y0 —— 不再依赖方法名。
            val entry = vm.declaredMethods.firstOrNull { m ->
                m.parameterTypes.size >= 3 && m.parameterTypes[2] == flush
            }
            if (entry == null) {
                HookProbe.miss(module, "noAutoRefresh", "PegasusViewModel flush entry not found (name-agnostic match failed)")
                return
            }
            entry.isAccessible = true
            val autoBack = enumValue(flush, "AUTO_BACK_FROM_BACKGROUND")
            val autoOther = enumValue(flush, "AUTO_BACK_FROM_OTHER_PAGE")
            val getState = findGetState(vm)
            runCatching { module.deoptimize(entry) }
            module.hook(entry)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // 决策与 proceed 分离：runCatching 里只做判定（绝不 proceed），
                    // 我们的异常最多退化成「不拦截」，proceed 整个回调只发生一次。
                    val block = runCatching {
                        // 开关每次回调实时读快照，保证热生效
                        if (!EnhanceFlags.snapshot(module).noAutoRefresh) return@runCatching false
                        // 参数里若带 Context/View，顺手捕获供 EnhanceFlags 读设置
                        chain.getArgs().forEach { arg -> if (arg != null) EnhanceFlags.captureContext(arg) }
                        val flushType = chain.getArg(2)
                        if (flushType == null || (flushType !== autoBack && flushType !== autoOther)) {
                            return@runCatching false
                        }
                        // 仅拦「已有内容」的自动刷新；空状态（页面/进程重建后）必须放行，
                        // 否则首屏永远空白。
                        if (!hasContent(chain.getArg(0), getState)) {
                            HookProbe.first(module, "noAutoRefreshEmptyState", 3) {
                                "auto refresh allowed (empty state)"
                            }
                            return@runCatching false
                        }
                        HookProbe.first(module, "noAutoRefreshBlocked", 3) {
                            "auto refresh blocked ($flushType)"
                        }
                        true
                    }.getOrElse { t ->
                        module.warn("noAutoRefresh callback failed: ${t.javaClass.simpleName}: ${t.message}")
                        false
                    }
                    if (block) null else chain.proceed() // 短路：不刷新，保留现有列表
                }
            HookProbe.ok(module, "noAutoRefresh", "PegasusViewModel#${entry.name} (has-content guard)")
            module.info("HomeNoAutoRefreshHooks installed -> PegasusViewModel.${entry.name} (has-content guard)")
        } catch (e: ClassNotFoundException) {
            // 类还没加载（首页类按需加载），延迟重试
            HookProbe.miss(module, "noAutoRefresh", "class not loaded yet, retry attempt=${homeAttempts.get()}")
            scheduleRetry(module, cl)
        } catch (t: Throwable) {
            HookProbe.miss(module, "noAutoRefresh", "install threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun scheduleRetry(module: XposedModule, cl: ClassLoader) {
        runCatching {
            retryHandler.postDelayed({ tryInstall(module, cl) }, RETRY_DELAY_MS)
        }.onFailure { t ->
            module.warn("noAutoRefresh: retry scheduling failed: ${t.message}")
        }
    }

    /** 找 getState() 方法（本类或父类）。 */
    private fun findGetState(vm: Class<*>): Method? {
        return runCatching {
            var c: Class<*>? = vm
            while (c != null) {
                runCatching { return c.getMethod("getState") }
                c = c.superclass
            }
            null
        }.getOrNull()
    }

    /**
     * 判断 ViewModel 当前是否已有 feed 内容：
     * 反射 getState() -> 遍历其字段找第一个 List，非空即视为有内容。
     */
    private fun hasContent(vmInstance: Any?, getState: Method?): Boolean {
        if (vmInstance == null || getState == null) return false
        return runCatching {
            val state = getState.invoke(vmInstance) ?: return false
            for (f in state.javaClass.declaredFields) {
                f.isAccessible = true
                val v = f.get(state) ?: continue
                if (v is List<*>) return v.isNotEmpty()
            }
            false
        }.getOrDefault(false)
    }

    private fun enumValue(enumCls: Class<*>, name: String): Any? {
        return runCatching {
            enumCls.enumConstants?.firstOrNull { c ->
                c != null && name == (c as Enum<*>).name
            }
        }.getOrNull()
    }
}
