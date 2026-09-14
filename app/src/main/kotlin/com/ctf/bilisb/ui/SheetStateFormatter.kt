package com.ctf.bilisb.ui

import java.util.Locale

/**
 * 播放器内底部面板的**纯文本拼装**逻辑。
 *
 * 为什么要单独抽出来：
 * - [SponsorBlockPlayerSheet] 里的行文案原先会散落在各个 `TextView.text = ...` 里，
 *   要验证「0 个片段」「负数秒」「超长 ID」这些边界就只能起模拟器，成本太高。
 * - 本文件**不引用任何 android.\* 类型**（连 `android.text` 都不碰），因此可以被纯 JVM 单测
 *   直接覆盖；面板只剩「把字符串塞进 View」这一件没有逻辑的事。
 *
 * 纯逻辑方法的约定：对任何输入都返回**非 null**文本，不抛异常（负数/越界一律夹到合法区间），
 * 这样调用方不需要在 UI 线程里再包一层兜底。
 */
object SheetStateFormatter {

    /** 用户 ID 展示时的保留位数：32 位 hex 全显示会挤爆一行，只留够辨认的前缀。 */
    private const val USER_ID_KEEP = 12

    /** 截断标记。用单字符省略号，避免个别字体里 "..." 撑出换行。 */
    private const val ELLIPSIS = "…"

    /**
     * 第一组「片段信息」行。
     *
     * @param count       当前视频的片段数。负数按 0 处理（调用方给的统计值理论上不该为负，
     *                    但真机数据来自网络/缓存，夹一下比显示「-1 个片段」好）。
     * @param insideLabel 播放头所在片段的描述（例如 `赞助/恰饭 300.0-600.0s`）；
     *                    null / 空白表示播放头不在任何片段内。
     * @return `"1 个片段 · 播放头不在片段内"` 或 `"1 个片段 · 播放头在片段内：赞助/恰饭 300.0-600.0s"`
     */
    fun formatSegmentInfo(count: Int, insideLabel: String?): String {
        val safeCount = count.coerceAtLeast(0)
        val label = insideLabel?.trim()
        return if (label.isNullOrEmpty()) {
            "$safeCount 个片段 · 播放头不在片段内"
        } else {
            "$safeCount 个片段 · 播放头在片段内：$label"
        }
    }

    /**
     * 第二组「服务信息」行。
     *
     * @param ok           服务/模块是否工作正常（为 false 时首段显示「异常」）
     * @param skippedCount 累计跳过次数，负数按 0 处理
     * @param savedSeconds 累计节省秒数，负数按 0 处理
     * @return `"状态：正常 · 跳过 140 次 · 节省 5521 秒"`
     */
    fun formatServiceStatus(ok: Boolean, skippedCount: Int, savedSeconds: Long): String {
        val status = if (ok) "正常" else "异常"
        val count = skippedCount.coerceAtLeast(0)
        val saved = savedSeconds.coerceAtLeast(0L)
        return "状态：$status · 跳过 $count 次 · 节省 $saved 秒"
    }

    /**
     * 「手动跳过」行的说明文案。
     *
     * 0 个片段时仍然给同一句式（点击后弹出的选择列表会是空的），
     * 让调用方少一个分支；不要在这里拼「无片段」之类的新句式，否则单测与 UI 会有两套文案。
     */
    fun formatManualSummary(count: Int): String =
        "共 ${count.coerceAtLeast(0)} 个片段 · 点击选择并跳到末尾"

    /**
     * 毫秒 → `"12.3s"`。
     *
     * 负数（seek 未就绪时的 -1 之类）夹到 0.0s，不显示 `"-0.1s"`。
     *
     * **截断**而不是 `String.format("%.1f")`：后者会四舍五入，9.999s 会显示成 `"10.0s"`，
     * 比实际时长还长；更糟的是设置页把该文案回填进输入框后 `toFloat()` 会拿到 10.0 并落盘，
     * 用户没改任何东西阈值就被悄悄放大了。截断到 0.1s 与 [RemainingTimeFormatter] 的取整方向一致。
     */
    fun formatSeconds(ms: Long): String {
        val tenths = ms.coerceAtLeast(0L) / 100L
        // Locale.US：某些地区（如 de-DE）会输出 "12,3s"，与设置页 toFloat() 回填的格式不一致
        return String.format(Locale.US, "%d.%ds", tenths / 10, tenths % 10)
    }

    /**
     * 用户 ID 展示：超过 12 位截断并补省略号（`"0f8c891f3115…"`），
     * 12 位及以内原样返回，null 当空串。
     */
    fun formatUserId(id: String?): String {
        val text = id.orEmpty().trim()
        return if (text.length > USER_ID_KEEP) text.take(USER_ID_KEEP) + ELLIPSIS else text
    }

    /**
     * 片段选择列表的每一行文案：`"赞助/恰饭 300.0-600.0s (300.0s)"`。
     *
     * 区间用 `-` 而不是 `~`：与 [formatSegmentInfo] 里的区间写法保持一致，
     * 避免同一个片段在「片段信息」和「选择列表」里长得不一样。
     * 时长按 `end - start` 算并夹到非负（脏数据里 end < start 时不能显示负时长）。
     */
    fun formatManualSegmentItem(displayName: String, startMs: Long, endMs: Long): String {
        val name = displayName.trim().ifEmpty { "片段" }
        val start = formatSeconds(startMs)
        val end = formatSeconds(endMs)
        val duration = formatSeconds((endMs - startMs).coerceAtLeast(0L))
        return "$name $start-$end ($duration)"
    }
}
