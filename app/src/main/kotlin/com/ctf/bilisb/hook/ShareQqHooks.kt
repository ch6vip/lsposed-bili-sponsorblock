package com.ctf.bilisb.hook

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HookResolve
import com.ctf.bilisb.settings.EnhanceFlags
import com.ctf.bilisb.util.info
import com.ctf.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 分享面板增强：给国际版 B 站的分享面板补回「分享到 QQ」入口
 * （移植自 BiliTamer (MIT) 的 ShareHooks.java）。
 *
 * BiliTamer 逆向结论（6.3.0）：
 *  - 分享面板渠道由服务端 ShareChannels（above_channels/below_channels）下发，
 *    客户端白名单本身包含 "QQ"，渠道项渲染所需图标/文案在应用内均有硬编码
 *    —— 缺的只是服务端不给 QQ 渠道；
 *  - 点击渠道统一走 ShareTargetTask -> 分享引擎（BShare/com.bilibili.socialize），
 *    国际版自带 QQ 互联 SDK（com.tencent.tauth）与 assets/share_config.json 的
 *    qq.appId=100951776，QQAssistActivity 也在，完整链路原生存在；
 *  - 因此只需向 ShareChannels.getAboveChannels() 的返回值注入
 *    share_channel="QQ" 的 ChannelItem，入口与执行都复用原生路径。
 *    （实测视频页：WEIXIN 在 above（第一排社媒），QQ 应与微信同排注入 above；
 *    above/below 都注入会出现两个 QQ，实机验证过。）
 *
 * 注入点选 getter 而非 API 回调：视频页（supermenu v2）、番剧、fasthybrid 等
 * 多个面板最终都通过 ShareChannels bean 的 getter 读取渠道列表，一处注入全覆盖；
 * getter 可能被多次调用，按「已有 share_channel=QQ 则跳过」幂等处理。
 * 未安装 QQ 时注入项会被面板自身的渠道安装检查过滤，无需自行判定。
 *
 * ── 与 BiliTamer 的关键差异（6.4.0+ 行为）────────────────────────────────
 * BiliTamer 在 6.4.0+（以 `tv.danmaku.bili.home.widget.top.HomeAppBarLayout`
 * 的存在为特征）**禁用**渠道注入：QQ 25201「非官方应用」校验假设宿主被重签名
 * （LSPosed 模块自签密钥 ≠ QQ 互联登记签名，任何 SDK 参数层的 sign 伪装都无效）。
 *
 * 本项目宿主是 **官方签名包**：LSPosed 不重签宿主，进程内读到的仍是 B 站官方
 * 证书（MD5 [OFFICIAL_SIGN_MD5]，即 QQ 互联 appid=100951776 登记的签名），
 * 25201 校验必然通过 —— 所以渠道注入**保留启用**，仅记录探测结果。
 * tauth 绕过（ACTION_SEND 降级）同样保留，作为原生卡片分享路径异常时的兜底
 * （分享形态降级为文本链接，无签名校验，稳定可用）。
 */
object ShareQqHooks {

    private const val SHARE_CHANNEL_QQ = "QQ"

    /**
     * B 站官方签名证书 MD5（danmaku.tv / Bbcallen，国内 9.8.0 与国际版双包同证书；
     * 从 APK Signing Block v2 提取，QQ 互联 appid=100951776 登记的即此签名）。
     * 仅作文档：这是渠道注入在本项目可安全保留的依据。
     */
    private const val OFFICIAL_SIGN_MD5 = "7194d531cbe7960a22007b9f6bdaa38b"

    /**
     * 分享文案来源：tauth Bundle 里没有标题文本（6.4.0 实测只有视频/封图两个
     * http 链接值），文案在面板打开时的 ShareChannels.text 字段里——注入点缓存
     * 实例，tauth 触发时读取。
     */
    @Volatile
    private var lastShareChannels: Any? = null

    fun install(module: XposedModule, cl: ClassLoader) {
        // 6.4.0+ 特征探测（HomeAppBarLayout 为 6.4.0 首页引入）。
        // 与 BiliTamer 不同：我们不据此禁用注入，理由见类注释（官方签名宿主无 25201 问题）。
        val appBarV64 = runCatching {
            Class.forName("tv.danmaku.bili.home.widget.top.HomeAppBarLayout", false, cl)
        }.isSuccess
        HookProbe.skip(
            module,
            "shareQqHostProbe",
            if (appBarV64) {
                "6.4.0+ detected (HomeAppBarLayout); QQ channel injection KEPT " +
                    "(official-signed host, differs from BiliTamer which disables it)"
            } else {
                "6.4.0 marker not present"
            },
        )

        installGroup(module, "shareQqInject") { installChannelInjection(module, cl) }
        installGroup(module, "shareQqTauth") { installTauthBypass(module, cl) }
    }

    /** 单组独立兜底：某组安装失败只记 MISS，不影响其它组。 */
    private inline fun installGroup(module: XposedModule, key: String, block: () -> Unit) {
        runCatching { block() }.onFailure { t ->
            HookProbe.miss(module, key, "install threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ------------------------------------------------------------ 渠道注入

    private fun installChannelInjection(module: XposedModule, cl: ClassLoader) {
        val sc = Class.forName(
            "com.bilibili.lib.sharewrapper.online.api.ShareChannels",
            false,
            cl,
        )
        val channelItemClass = Class.forName(
            "com.bilibili.lib.sharewrapper.online.api.ShareChannels\$ChannelItem",
            false,
            cl,
        )
        val getShareChannel = channelItemClass.getMethod("getShareChannel")
        val setName = channelItemClass.getMethod("setName", String::class.java)
        val setShareChannel = channelItemClass.getMethod("setShareChannel", String::class.java)
        val setPicture = channelItemClass.getMethod("setPicture", String::class.java)

        val above = HookResolve.declaredMethod(sc, listOf("getAboveChannels"))
            ?: throw IllegalStateException("ShareChannels#getAboveChannels not found")

        val loggedOnce = AtomicBoolean(false)
        runCatching { module.deoptimize(above) }
        module.hook(above)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                // getter 型 hook：先走原方法拿渠道列表，再决定是否注入。
                val result = chain.proceed()
                var finalResult: Any? = result
                runCatching {
                    if (!EnhanceFlags.snapshot(module).shareQq) return@runCatching
                    // 记录面板 bean（tauth 取分享文案用）
                    lastShareChannels = chain.getThisObject()
                    HookProbe.first(module, "shareQqBeanCached", 1) {
                        "panel bean cached, text=${lastShareText() ?: "null"}"
                    }
                    finalResult = ensureQqChannelAbove(
                        module,
                        result,
                        loggedOnce,
                        channelItemClass,
                        getShareChannel,
                        setName,
                        setShareChannel,
                        setPicture,
                    )
                }.onFailure { t ->
                    module.warn("share: inject failed: ${t.javaClass.simpleName}: ${t.message}")
                }
                finalResult
            }
        HookProbe.ok(module, "shareQqInject", "ShareChannels#getAboveChannels")
    }

    /**
     * 向 above 渠道列表注入 QQ（幂等：已有 share_channel=QQ 则原样返回）。
     * 信任宿主返回可变列表（与 BiliTamer 一致）；列表不可变时 add 会抛出，
     * 由外层 runCatching 记日志后返回原结果，不影响面板。
     */
    private fun ensureQqChannelAbove(
        module: XposedModule,
        listObj: Any?,
        once: AtomicBoolean,
        channelItemClass: Class<*>,
        getShareChannel: Method,
        setName: Method,
        setShareChannel: Method,
        setPicture: Method,
    ): Any? {
        @Suppress("UNCHECKED_CAST")
        val list: MutableList<Any> = when (listObj) {
            is List<*> -> listObj as MutableList<Any>
            else -> ArrayList()
        }
        for (item in list) {
            // 元素若为 null，getShareChannel.invoke 会抛出并被 runCatching 吞掉，天然跳过
            val channel = runCatching { getShareChannel.invoke(item) }.getOrNull()
            if (SHARE_CHANNEL_QQ == channel) {
                return listObj ?: list // 已有 QQ 渠道（服务端下发或上一次注入），不重复加
            }
        }
        val item = channelItemClass.getDeclaredConstructor().newInstance()
        setName.invoke(item, "QQ")
        setShareChannel.invoke(item, SHARE_CHANNEL_QQ)
        setPicture.invoke(item, "") // 空 picture 时面板回退到本地硬编码 QQ 图标
        list.add(item)
        if (once.compareAndSet(false, true)) {
            module.info("[probe] share: QQ channel injected into above channels, size=${list.size}")
        }
        return listObj ?: list
    }

    // ------------------------------------------------------------ tauth 绕过（兜底）

    /**
     * QQ 分享 25201 定论（BiliTamer 2026-09 实测）：错误弹窗出现在 QQ 进程，
     * QQ 侧直接读取调用方真实签名对比 QQ 互联平台登记值——重签名密钥永远不匹配。
     * 本项目宿主为官方签名，正常不会触发 25201，原生卡片分享可用；
     * 本段保留为**兜底**：若 SDK 路径异常（未装 QQ 互联组件、配置变更等），
     * 改走系统 ACTION_SEND 定向 QQ（无签名校验），吞掉原调用。
     * 分享形态降级为文本链接（非结构化卡片）。
     */
    private fun installTauthBypass(module: XposedModule, cl: ClassLoader) {
        val tencent = Class.forName("com.tencent.tauth.Tencent", false, cl)
        val listener = Class.forName("com.tencent.tauth.IUiListener", false, cl)
        var hooked = 0
        for (name in listOf("shareToQQ", "shareToQzone")) {
            val method = HookResolve.declaredMethod(
                tencent,
                listOf(name),
                Activity::class.java,
                Bundle::class.java,
                listener,
            )
            if (method == null) {
                HookProbe.miss(module, "shareQqTauth:$name", "method unavailable")
                continue
            }
            runCatching { module.deoptimize(method) }
            module.hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // 平台类型显判空：实参可能为 null（describeArgs 的教训）
                    val activity = chain.getArg(0) as? Activity
                    if (activity != null) EnhanceFlags.captureContext(activity)
                    // 决策与 proceed 分离：runCatching 里只做判定/启动 ACTION_SEND，
                    // 原调用 proceed 只会发生一次（兜底失败也走 proceed，弹 25201 由 QQ 侧处理）。
                    var swallow = false
                    runCatching {
                        if (!EnhanceFlags.snapshot(module).shareQq) return@runCatching
                        val bundle = chain.getArg(1) as? Bundle
                        if (activity == null || bundle == null) return@runCatching
                        val text = buildShareText(module, bundle) ?: return@runCatching // 没有可分享链接，走原路
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                            setPackage("com.tencent.mobileqq")
                        }
                        activity.startActivity(intent)
                        module.info("share: QQ via ACTION_SEND ($name), text=$text")
                        swallow = true // 吞掉 tauth 调用
                    }.onFailure { t ->
                        module.warn("share: tauth bypass failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                    if (swallow) null else chain.proceed()
                }
            HookProbe.ok(module, "shareQqTauth:$name", "Tencent#$name(Activity,Bundle,IUiListener)")
            hooked++
        }
        module.info("share: tauth bypass hooked, methods=$hooked")
    }

    /**
     * 组装 ACTION_SEND 文本。Bundle 全值扫描（get 而非 getString，标题可能以
     * 非 String 类型存放）：url 优先 bilibili 域；标题 = 首个非 url 长文本。
     */
    @Suppress("DEPRECATION") // 有意用 Bundle.get 做全类型扫描（BiliTamer 同款），不用 getString
    private fun buildShareText(module: XposedModule, bundle: Bundle): String? {
        var url: String? = null
        var fallbackUrl: String? = null
        var title: String? = null
        runCatching {
            for (key in bundle.keySet()) {
                val raw = bundle.get(key) ?: continue
                val v = raw.toString()
                if (v.isEmpty()) continue
                if (v.startsWith("http://") || v.startsWith("https://")) {
                    if (fallbackUrl == null) fallbackUrl = v
                    if (url == null && (v.contains("b23.tv") || v.contains("bilibili.com"))) {
                        url = v
                    }
                } else if (title == null && v.length > 8 && !v.startsWith("[")) {
                    title = v
                }
            }
        }.onFailure { t ->
            // 扫描失败按「无标题/无链接」降级处理，不让异常逃出 hook
            module.warn("share: bundle scan failed: ${t.message}")
        }
        val link = url ?: fallbackUrl ?: return null
        var textTitle: String? = lastShareText()
        if (textTitle.isNullOrEmpty()) textTitle = title
        return buildString {
            if (!textTitle.isNullOrEmpty() && !textTitle.contains(link)) {
                append(textTitle).append('\n')
            }
            append(link)
        }.toString()
    }

    /** 从缓存的 ShareChannels bean 反射读 text 字段（取不到返回 null）。 */
    private fun lastShareText(): String? {
        val bean = lastShareChannels ?: return null
        return runCatching {
            val field = findField(bean.javaClass, "text") ?: return null
            field.isAccessible = true
            (field.get(bean) as? String)?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            runCatching { return c.getDeclaredField(name) }
            c = c.superclass
        }
        return null
    }
}
