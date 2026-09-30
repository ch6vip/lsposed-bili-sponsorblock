package com.ctf.bilisb.ui

import android.content.Context
import com.ctf.bilisb.R

/**
 * 面板文案取用口。
 *
 * [SheetStateFormatter] 刻意**不引用任何 android.\* 类型**（这样才能被纯 JVM 单测覆盖），
 * 所以它不能直接 `context.getString(...)`，改为依赖这个接口；生产实现是 [AndroidStrings]，
 * 单测实现是一个返回可预期文本的假对象。这样「文案」与「拼装逻辑」各自可测，
 * 且文案统一落在 `res/values*`（英文用户走 `values-en`）。
 */
interface SheetStrings {
    fun segmentCountOutside(count: Int): String
    fun segmentCountInside(count: Int, label: String): String
    fun statusNormal(): String
    fun statusError(): String
    fun serviceStatus(status: String, count: Int, savedSeconds: Long): String
    fun manualSummary(count: Int): String
    fun segmentOne(): String
    fun segmentItem(name: String, start: String, end: String, duration: String): String

    companion object {
        /** 便捷构造：调用方给 Context，内部选对资源表（宿主进程里 context 是宿主的）。 */
        fun forContext(context: Context): SheetStrings = AndroidStrings(context)
    }
}

/** 生产实现：默认按系统 locale 取资源（`res/values` 中文，`values-en` 英文）。 */
class AndroidStrings(private val context: Context) : SheetStrings {
    // 一律经 ModuleStrings：宿主进程里 context 是宿主的，直接 getString 会查错资源表。
    private fun s(resId: Int, vararg args: Any): String =
        ModuleStrings.get(context, resId, *args)

    override fun segmentCountOutside(count: Int): String =
        s(R.string.sheet_segment_count_outside, count)

    override fun segmentCountInside(count: Int, label: String): String =
        s(R.string.sheet_segment_count_inside, count, label)

    override fun statusNormal(): String = s(R.string.sheet_status_normal)

    override fun statusError(): String = s(R.string.sheet_status_error)

    override fun serviceStatus(status: String, count: Int, savedSeconds: Long): String =
        s(R.string.sheet_service_status, status, count, savedSeconds)

    override fun manualSummary(count: Int): String =
        s(R.string.sheet_manual_summary, count)

    override fun segmentOne(): String = s(R.string.sheet_segment_one)

    override fun segmentItem(name: String, start: String, end: String, duration: String): String =
        s(R.string.segment_duration_seconds, name, start, end, duration)
}
