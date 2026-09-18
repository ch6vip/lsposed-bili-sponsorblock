package com.ctf.bilisb.hook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.settings.EnhanceFlags
import com.ctf.bilisb.util.info
import com.ctf.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 首页 UI 调整（移植自 BiliTamer (MIT) 的 HomeUxHooks，Java → Kotlin，只保留两个功能）：
 *
 *  A. 底栏删 tab（开关 homeTabRemoveMessage；「我的」tab 移除能力已按需求删除）：
 *     hook tab 模型 provider 的「无参返回 ArrayList」方法（6.4.0/6.5.0 即
 *     tv.danmaku.bili.ui.main2.S 的静态方法 a()，返回 tab 模型 List），按 pageUrl
 *     前缀过滤（"bilibili://im/" / "bilibili://user_center/mine"）——底栏、pager、
 *     初始选中全部由该列表派生（BaseMainFrameFragment.pm() 消费它：index 分配、
 *     setTabs、pager 注册、im(pageUrl) 初始路由），一处过滤全链一致。
 *
 *  B. 首页顶栏消息入口（开关 homeTopbarMessage）：在 HomeAppBarLayout
 *     （tv.danmaku.bili.home.widget.top.HomeAppBarLayout，主加载器，布局 AXML 真名）
 *     上加右侧消息图标点击层，点击走深链 bilibili://im/compat/home（实测落点=
 *     底栏「消息」同款页面）。国际版搜索区右侧本就留白，图标放右缘即呈现国内版
 *     「搜索框左缩 + 右侧消息」的观感，无需改 Compose 布局。
 *
 * 关键逆向结论（移植自 BiliTamer，6.4.0 实测）：
 *  - tv.danmaku.bili.ui.main2.* 在**插件化 ClassLoader** 里加载，主加载器里的同名类
 *    是死拷贝——直接 hook 全部静默。必须 hook java.lang.ClassLoader.loadClass(String,boolean)
 *    嗅探 MainFragment 首次加载时的真实定义加载器，再一次性重装 main2 漏斗。
 *    hooker 在类加载热路径上，非目标名必须快速返回（只付一次 volatile 读的代价）。
 *  - tab 模型: BaseMainFrameFragment$o 字段 c(resource.x) → x.d = pageUrl
 *    （jadx 显示 f356164c/f357337d 为碰撞改名，真实名取末字母）。
 *    字段名随构建漂移，运行时按形状兜底（见 [pageUrlOf]）。
 *
 * 相对源码剥离的部分（本模块不需要）：
 *  - HomeTabServiceImpl 构造器捕获 / q() 事件分发、合成点击、khome HomeFrameViewModel
 *    探针 —— 全部是「顶栏头像 → 我的」入口的支撑代码；
 *  - applyTabRemoval（从顶栏 overlay 出发找活 MainFragment 立即重建）—— 主路径由
 *    loader 嗅探覆盖（MainFragment 首次加载早于底栏构建，过滤天然生效）；
 *  - 顶栏消息角标（未读数红点 + IMBadgeUnreadDataStore 轮询）—— 实现代价大，先不移植；
 *  - Compose content 探针 / CachedResourceResolver / MainResourceManager 等实验代码。
 */
object HomeTabHooks {

    // ------------------------------------------------------------------ 锚点常量

    /** main2 插件里的主框架 Fragment（类加载嗅探的目标名）。 */
    private const val MAIN_FRAGMENT_CLASS = "tv.danmaku.bili.ui.main2.MainFragment"

    /** 顶栏容器（主加载器，布局 AXML 真名，未混淆）。 */
    private const val APPBAR_CLASS = "tv.danmaku.bili.home.widget.top.HomeAppBarLayout"

    /** 底栏要移除的 tab：pageUrl 前缀（运行时探针会打印真实值便于校准）。 */
    private const val TAB_URL_REMOVE_MESSAGE = "bilibili://im/"

    /** 顶栏消息图标点击后走的深链（实测落点=底栏「消息」同款页面）。 */
    private const val MESSAGE_ROUTE_URI = "bilibili://im/compat/home"

    /** Zl() 直连兜底候选：tab provider 类（单字母类名随构建重排，9100100=S、9100300=P）。 */
    private val PROVIDER_CLASS_CANDIDATES =
        listOf("tv.danmaku.bili.ui.main2.S", "tv.danmaku.bili.ui.main2.P")

    // overlay 的 tag（幂等判定 + 防重复包裹）
    private const val TAG_OVERLAY = "bilisb_top_overlay"
    private const val TAG_WRAP = "bilisb_top_wrap"
    private const val TAG_MSG_ENTRY = "bilisb_msg_entry"

    /** 类找不到时的延迟重试上限与间隔。 */
    private const val RETRY_MAX = 30
    private const val RETRY_INTERVAL_MS = 1000L

    private val uiHandler = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ 入口

    fun install(module: XposedModule, cl: ClassLoader) {
        // 嗅探失败时的直连兜底用:6.5.0 真机实测 main2 类经默认加载器可达
        // (MineMenuInjector 用同一加载器直接 Class.forName 到 tv.danmaku.bili.ui.main2.mine.d)
        defaultCl = cl
        // 每个子功能独立 try 安装：某一类找不到/不可 hook 时不能连带丢掉其它功能
        runCatching { installLoaderSniffer(module) }.onFailure {
            HookProbe.miss(module, "homeLoaderSniffer", "install threw: ${it.javaClass.simpleName}: ${it.message}")
        }
        runCatching { scheduleFunnelWatchdog(module) }.onFailure {
            HookProbe.miss(module, "homeFunnelWatchdog", "install threw: ${it.javaClass.simpleName}: ${it.message}")
        }
        runCatching { installTopbarMessageEntry(module, cl) }.onFailure {
            HookProbe.miss(module, "homeTopbarEntry", "install threw: ${it.javaClass.simpleName}: ${it.message}")
        }
        module.info("HomeTabHooks installed")
    }

    // ------------------------------------------------------------------ A. main2 插件加载器嗅探

    /** main2 漏斗是否已随真实加载器装好（嗅探回调的快速退出判据）。 */
    private val main2FunnelsDone = AtomicBoolean(false)

    /** 真实运行时加载器（MainFragment 的定义加载器）。 */
    @Volatile
    private var mainUiLoader: ClassLoader? = null

    /** 模块默认加载器(嗅探失败时的直连兜底)。 */
    @Volatile
    private var defaultCl: ClassLoader? = null

    /**
     * 挂 java.lang.ClassLoader.loadClass(String,boolean)：MainFragment 首次加载时
     * 捕获其真实定义加载器（插件化后与主加载器不同），并一次性重装全部 main2 漏斗。
     */
    private fun installLoaderSniffer(module: XposedModule) {
        val loadClass: Method = Class.forName("java.lang.ClassLoader")
            .getDeclaredMethod("loadClass", String::class.java, Boolean::class.javaPrimitiveType)
        loadClass.isAccessible = true
        runCatching { module.deoptimize(loadClass) }
        module.hook(loadClass)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                // 类加载热路径：漏斗装好后每次只付一次 volatile 读的代价
                if (main2FunnelsDone.get()) return@intercept result
                val name = chain.getArgs().getOrNull(0) as? String ?: return@intercept result
                if (name != MAIN_FRAGMENT_CLASS || result == null) return@intercept result
                try {
                    // result 就是加载出来的 Class 对象，其 classLoader 即真实定义加载器
                    val uiCl = (result as Class<*>).classLoader
                    if (main2FunnelsDone.compareAndSet(false, true)) {
                        module.info("homeTab: MainFragment loaded by $uiCl")
                        mainUiLoader = uiCl
                        onMainUiLoader(module, uiCl)
                    }
                } catch (t: Throwable) {
                    module.warn("homeTab: loader sniffer dispatch failed: ${t.javaClass.simpleName}: ${t.message}")
                }
                result
            }
        HookProbe.ok(module, "homeLoaderSniffer", "java.lang.ClassLoader#loadClass(String,boolean)")
    }

    /** 用真实运行时加载器重装 main2 漏斗（每项独立 try，互不拖垮）。 */
    private fun onMainUiLoader(module: XposedModule, uiCl: ClassLoader) {
        runCatching { installTabListFilter(module, uiCl) }.onFailure {
            HookProbe.miss(module, "homeTabList", "install threw: ${it.javaClass.simpleName}: ${it.message}")
        }
        module.info("homeTab: main2 funnels installed under $uiCl")
    }

    /**
     * 嗅探看门狗：MainFragment 若长时间未被观察到（理论上不应发生，模块在包加载期
     * 先于宿主代码装好嗅探），30 次重试后记一条 MISS 便于定位。
     */
    private fun scheduleFunnelWatchdog(module: XposedModule, attempt: Int = 1) {
        if (main2FunnelsDone.get()) return
        if (attempt >= RETRY_MAX) {
            // 6.5.0 真机回归(2026-09-19):嗅探 30 轮未观察到 MainFragment 的 loadClass ——
            // main2 类经默认加载器即可达,嗅探前提不成立。用默认加载器直连兜底,
            // 装上了就照常记 ok;真找不到才记 miss。
            val cl = defaultCl
            if (cl != null && tryInstallTabListFilter(module, cl)) {
                main2FunnelsDone.set(true)
                HookProbe.ok(module, "homeMain2Funnels", "main2 funnel installed via default classloader (sniffer bypass)")
                return
            }
            HookProbe.miss(module, "homeMain2Funnels", "MainFragment not observed after $RETRY_MAX checks")
            return
        }
        uiHandler.postDelayed({ scheduleFunnelWatchdog(module, attempt + 1) }, RETRY_INTERVAL_MS * 2)
    }

    // ------------------------------------------------------------------ A. 底栏 tab 列表过滤（主漏斗）

    private val tabFilterHooked = AtomicBoolean(false)
    private val tabListProbe = AtomicBoolean(false)

    /**
     * tab 模型 provider 的定位（移植自 BiliTamer，9100100/9100300 双构建验证）：
     * MainFragment.Zl() 的「返回类型」就是 tab provider 类（单字母类名随构建重排），
     * provider 实现 BaseMainFrameFragment$n，其「无参返回 ArrayList」方法产出
     * 底栏+pager 的 tab 模型列表（S.a()/P.a()）。
     * 关键：不调用 Zl，只取 getReturnType() 即拿到 provider 类，再按签名 hook。
     * Zl 名漂移时按候选类名直连兜底（同样是 no-arg ArrayList 方法，不发明新 hook 点）。
     */
    private fun installTabListFilter(module: XposedModule, uiCl: ClassLoader) {
        // 未找到目标时由 retryUntilDone 延迟重试；tryInstallTabListFilter 装上后
        // 置位 tabFilterHooked，重试自然短路为 true。
        retryUntilDone(module, "homeTabList") { tryInstallTabListFilter(module, uiCl) }
    }

    /** true=已装上（或已被并发安装）；false=本次没找到目标（继续重试）。 */
    private fun tryInstallTabListFilter(module: XposedModule, uiCl: ClassLoader): Boolean {
        if (tabFilterHooked.get()) return true
        val mainFragment = runCatching { Class.forName(MAIN_FRAGMENT_CLASS, false, uiCl) }.getOrNull()
            ?: return false

        // 主路径：Zl() 的返回类型即 provider 类
        val zl: Method? = mainFragment.declaredMethods.firstOrNull {
            it.parameterTypes.isEmpty() && it.name == "Zl"
        }
        val providerCls = zl?.returnType

        // 候选 provider 类：Zl 返回类型优先，候选类名直连兜底
        val providers = ArrayList<Class<*>>(2)
        if (providerCls != null && providerCls != Void.TYPE && !providerCls.isPrimitive) {
            providers.add(providerCls)
        }
        for (name in PROVIDER_CLASS_CANDIDATES) {
            runCatching { Class.forName(name, false, uiCl) }.getOrNull()?.let { providers.add(it) }
        }

        var target: Method? = null
        var targetCls: Class<*>? = null
        val candidates = StringBuilder()
        for (cls in providers) {
            for (mm in cls.declaredMethods) {
                if (mm.parameterTypes.isNotEmpty()) continue
                if (mm.returnType != java.util.ArrayList::class.java) continue
                if (candidates.isNotEmpty()) candidates.append(", ")
                candidates.append(cls.simpleName).append('.').append(mm.name).append("()")
                if (target == null) {
                    target = mm
                    targetCls = cls
                }
            }
            if (target != null) break
        }
        val method = target ?: return false

        if (!tabFilterHooked.compareAndSet(false, true)) return true
        runCatching { method.isAccessible = true }
        runCatching { module.deoptimize(method) }
        module.hook(method)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                runCatching { filterTabList(module, result as? List<Any>) }
                    .onFailure { module.warn("homeTab: filter tab list failed: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrDefault(result)
            }
        HookProbe.ok(module, "homeTabList", "${targetCls?.name}#${method.name}() (zl=${zl != null}, candidates=$candidates)")
        return true
    }

    /**
     * 过滤 provider 产出的 tab 模型列表；首次调用打印 pageUrl 明细（现场校准过滤规则）。
     * 返回过滤后的新列表，或原列表（无需过滤/尾缀守卫拦截）。
     */
    private fun filterTabList(module: XposedModule, list: List<Any>?): List<Any>? {
        if (list == null) return list
        // 开关热生效：每次回调都读最新快照
        val snapshot = EnhanceFlags.snapshot(module)
        val rmMsg = snapshot.homeTabRemoveMessage
        val probe = tabListProbe.compareAndSet(false, true)
        if (!rmMsg && !probe) return list

        val kept = ArrayList<Any>(list.size)
        var removedMsg = 0
        var droppedMaxIdx = -1
        var keptMinAfterDrop = Int.MAX_VALUE
        for ((index, item) in list.withIndex()) {
            val url = pageUrlOf(item)
            val drop = url != null && rmMsg && url.startsWith(TAB_URL_REMOVE_MESSAGE)
            if (drop) removedMsg++
            if (drop) {
                droppedMaxIdx = maxOf(droppedMaxIdx, index)
            } else {
                if (droppedMaxIdx >= 0) keptMinAfterDrop = minOf(keptMinAfterDrop, index)
                kept.add(item)
            }
        }
        // 运行时探针：首次打印 tab 模型 pageUrl 列表（只评估一次，不刷屏）
        HookProbe.first(module, "homeTabUrls", 1) {
            "tabs[" + list.joinToString(",") { pageUrlOf(it) ?: "?" } + "]"
        }
        if (removedMsg == 0) return list

        // 尾缀守卫：只允许移除列表尾部的连续项（bar 索引与 pager 索引保持一致）。
        // 若被删项之后还有保留项（服务端调整了顺序），过滤会造成索引错位——放弃并告警。
        if (keptMinAfterDrop < Int.MAX_VALUE && keptMinAfterDrop < droppedMaxIdx) {
            module.warn(
                "homeTab: removed tabs are not at list tail (kept idx $keptMinAfterDrop " +
                    "after dropped idx $droppedMaxIdx) - skip filtering to avoid index mismatch",
            )
            return list
        }
        module.info("homeTab: tab list filtered ${list.size} -> ${kept.size} (msg=$removedMsg)")
        return kept
    }

    /**
     * 从 tab 模型对象里找 pageUrl：字段名随构建漂移，改为字段名无关扫描——
     * 先按已知字段名 c 直读（9100300 Yf0.l：a=tab_id b=tab_name c=tab_url，
     * 排除 home_tab_url 干扰），失败再第一层扫 item 的对象字段（resource.x），
     * 第二层在该对象里找以 "bilibili://" 开头的 String 字段。都找不到返回 null
     * （fail-open 不过滤）。
     */
    private fun pageUrlOf(tabItem: Any): String? {
        runCatching {
            val cf = tabItem.javaClass.getDeclaredField("c")
            cf.isAccessible = true
            val s = cf.get(tabItem) as? String
            if (s != null && s.startsWith("bilibili://") && !s.startsWith("bilibili://home?")) {
                return s
            }
        }
        return runCatching {
            for (f in tabItem.javaClass.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val v = f.get(tabItem) ?: continue
                if (v is String || v is Number || v is Boolean || v is Character) continue
                findRouteString(v)?.let { return it }
            }
            null
        }.getOrNull()
    }

    /** 在对象自身的 String 字段里找 bilibili:// 路由。 */
    private fun findRouteString(obj: Any): String? {
        return runCatching {
            for (f in obj.javaClass.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type != String::class.java) continue
                f.isAccessible = true
                val v = f.get(obj) as? String ?: continue
                if (v.startsWith("bilibili://")) return v
            }
            null
        }.getOrNull()
    }

    // ------------------------------------------------------------------ B. 顶栏消息入口

    private val topbarHooked = AtomicBoolean(false)

    /**
     * HomeAppBarLayout 未覆写 onFinishInflate（纯继承 TintAppBarLayout），挂全部构造器：
     * inflate 时子 View 在 ctor 后加入，ctor 内 view.post() 的 RunQueue 会在
     * attach 后首次遍历执行——此时子树已就绪，decorate 时机确定性成立。
     * 类在主加载器但可能晚于模块安装期才就绪，找不到时延迟重试（上限 30 次）。
     */
    private fun installTopbarMessageEntry(module: XposedModule, cl: ClassLoader) {
        retryUntilDone(module, "homeTopbarEntry") { tryInstallTopbarHook(module, cl) }
    }

    /** true=已装上；false=类还没找到（继续重试）。 */
    private fun tryInstallTopbarHook(module: XposedModule, cl: ClassLoader): Boolean {
        if (topbarHooked.get()) return true
        val barCls = runCatching { Class.forName(APPBAR_CLASS, false, cl) }.getOrNull() ?: return false
        val ctors: Array<Constructor<*>> = barCls.declaredConstructors
        if (ctors.isEmpty()) return false
        if (!topbarHooked.compareAndSet(false, true)) return true

        for (ctor in ctors) {
            runCatching { ctor.isAccessible = true }
            runCatching { module.deoptimize(ctor) }
            module.hook(ctor)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val bar = chain.getThisObject() as? View
                        // 开关热生效：开关关闭时不排 decorate（新建的顶栏不加图标）
                        if (bar != null && EnhanceFlags.snapshot(module).homeTopbarMessage) {
                            EnhanceFlags.captureContext(bar)
                            bar.post {
                                runCatching { decorate(module, bar) }.onFailure {
                                    module.warn("homeTab: decorate failed: ${it.javaClass.simpleName}: ${it.message}")
                                }
                            }
                        }
                    }
                    result
                }
        }
        HookProbe.ok(module, "homeTopbarEntry", "$APPBAR_CLASS ctors=${ctors.size}")
        return true
    }

    /** 在顶栏容器上加右侧消息图标（幂等；容器包裹方式移植自 BiliTamer v1.7.2 修复）。 */
    private fun decorate(module: XposedModule, bar: View) {
        EnhanceFlags.captureContext(bar)
        val activity = resolveActivity(bar) ?: run {
            module.warn("homeTab: no activity for appbar, skip decorate")
            return
        }
        val barGroup = bar as? ViewGroup ?: return
        if (barGroup.findViewWithTag<View>(TAG_OVERLAY) != null) {
            return // 已加过
        }
        if (!EnhanceFlags.snapshot(module).homeTopbarMessage) {
            return // 开关已关（热生效：晚到的 decorate 不再上图标）
        }
        val den = bar.resources.displayMetrics.density
        val dp = maxOf(1, Math.round(den))

        // 容器：叠在顶栏内容行之上。
        //
        // v1.7.2 修复（分区栏错位，移植自 BiliTamer）：不能把 overlay 追加到
        // HomeAppBarLayout（垂直 LinearLayout）末尾再用负 topMargin 拉回第一行——
        // 服务端下发分区栏后兄弟布局流改变，负 margin 不再成立。正确做法：把内容行
        // （childAt(0)）用一个 FrameLayout 包裹，overlay 作为该 wrapper 的第二个子 View
        // 与之同尺寸叠放；无论服务端再插多少行都不影响叠放关系，恒精确覆盖顶栏内容行。
        val overlay = FrameLayout(bar.context)
        overlay.tag = TAG_OVERLAY
        overlay.isClickable = false

        val existingWrap = barGroup.findViewWithTag<View>(TAG_WRAP)
        if (existingWrap is FrameLayout) {
            // 防御：wrapper 已在（理论上不会，顶部已幂等拦截）——把 overlay 补进现有 wrapper。
            existingWrap.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        } else {
            val contentRow = barGroup.getChildAt(0) ?: run {
                module.warn("homeTab: no content row (childAt(0)) in appbar, skip decorate")
                return
            }
            val contentLp = contentRow.layoutParams
            val wrap = FrameLayout(bar.context)
            wrap.tag = TAG_WRAP
            wrap.isClickable = false
            barGroup.removeView(contentRow)
            barGroup.addView(wrap, 0, contentLp) // wrapper 继承内容行原占位（高度/边距不变）
            // 内容行放回 wrapper 时用 MATCH_PARENT 填满 wrapper（wrapper 高度=内容行原高度，
            // 故内容行视觉尺寸不变）；overlay 同尺寸叠在其上，恒精确覆盖内容行。
            val fill = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            wrap.addView(contentRow, fill)
            wrap.addView(overlay, fill)
            module.info("homeTab: content row wrapped for stable overlay")
        }

        // 右侧消息图标（信封 + 简单点击层）。
        // 注意：BiliTamer 的未读角标（红点数字 + IMBadgeUnreadDataStore 轮询）实现代价大，
        // 本次移植先不做——只保留图标与深链跳转。
        val msgBtn = FrameLayout(bar.context)
        msgBtn.tag = TAG_MSG_ENTRY
        msgBtn.isClickable = true
        msgBtn.contentDescription = "消息"
        msgBtn.setOnClickListener { v ->
            EnhanceFlags.captureContext(v)
            val act = resolveActivity(v)
            if (act != null) {
                openRoute(module, act, MESSAGE_ROUTE_URI)
            }
        }
        val envelope = ImageView(bar.context)
        envelope.setImageDrawable(EnvelopeDrawable(Color.parseColor("#616161")))
        envelope.scaleType = ImageView.ScaleType.CENTER
        msgBtn.addView(
            envelope,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val lp = FrameLayout.LayoutParams(40 * dp, 40 * dp, Gravity.END or Gravity.CENTER_VERTICAL)
        lp.rightMargin = 10 * dp
        overlay.addView(msgBtn, lp)

        module.info("homeTab: topbar message entry decorated")
    }

    /** 深链跳转：限定本包 + NEW_TASK，失败留日志不抛给宿主。 */
    private fun openRoute(module: XposedModule, activity: Activity, uri: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            intent.setPackage(activity.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            module.info("homeTab: opened route $uri")
        }.onFailure {
            module.warn("homeTab: open route failed: $uri: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 沿 ContextWrapper 链解出 Activity（ThemeWrapper 包一层层剥）。 */
    private fun resolveActivity(v: View): Activity? {
        return runCatching {
            var c: Context? = v.context
            while (c is android.content.ContextWrapper) {
                if (c is Activity) return c
                c = c.baseContext
            }
            null
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 通用工具

    /**
     * 延迟重试：类还没就绪时按固定间隔重试，上限 [RETRY_MAX] 次，耗尽记 MISS。
     * check 返回 true（已装上）即停止。
     *
     * 重试跑在自建后台 HandlerThread 上(check 里是 Class.forName + declaredMethods
     * 全扫,不能压主线程);真正需要主线程的安装动作由各自 hook 回调自行 post。
     */
    private fun retryUntilDone(module: XposedModule, key: String, attempt: Int = 1, check: () -> Boolean) {
        val done = try {
            check()
        } catch (t: Throwable) {
            false
        }
        if (done) return
        if (attempt >= RETRY_MAX) {
            HookProbe.miss(module, key, "gave up after $RETRY_MAX retries")
            return
        }
        retryHandler.postDelayed({ retryUntilDone(module, key, attempt + 1, check) }, RETRY_INTERVAL_MS)
    }

    /** 重试专用的后台线程(Class.forName/反射扫描不上主线程)。 */
    private val retryHandler by lazy {
        val thread = android.os.HandlerThread("BiliSB-HomeTabRetry").apply { isDaemon = true }
        thread.start()
        android.os.Handler(thread.looper)
    }

    /** 代码画的信封图标：不依赖目标 App 资源，避免资源名漂移（移植自 BiliTamer）。 */
    private class EnvelopeDrawable(private val color: Int) : Drawable() {
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width() * 0.62f
            val h = b.height() * 0.44f
            val left = b.centerX() - w / 2
            val top = b.centerY() - h / 2
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.style = Paint.Style.STROKE
            p.strokeWidth = maxOf(2f, b.width() * 0.045f)
            p.color = color
            val rect = RectF(left, top, left + w, top + h)
            canvas.drawRoundRect(rect, w * 0.12f, w * 0.12f, p)
            val flap = Path()
            flap.moveTo(left, top + h * 0.12f)
            flap.lineTo(b.centerX().toFloat(), top + h * 0.62f)
            flap.lineTo(left + w, top + h * 0.12f)
            canvas.drawPath(flap, p)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
