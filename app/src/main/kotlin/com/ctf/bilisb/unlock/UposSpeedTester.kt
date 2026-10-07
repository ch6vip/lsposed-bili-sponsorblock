package com.ctf.bilisb.unlock

import android.net.Uri
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * UPOS 测速器（对齐 BiliRoaming AlertDialogBuilderC0522xh / C0478vh）。
 *
 * 预设 20 个主流 UPOS 节点（阿里、百度、腾讯、华为、Akamai 及海外镜像），
 * 通过向节点发送分段 HTTP 请求测量连通延迟与下载带宽。
 */
object UposSpeedTester {

    data class UposNode(
        val name: String,
        val host: String,
    )

    data class TestResult(
        val node: UposNode,
        val latencyMs: Long,
        val speedBytesPerSec: Long,
        val formattedSpeed: String,
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = error == null && speedBytesPerSec > 0
    }

    val NODES = listOf(
        UposNode("ali（阿里）", "upos-sz-mirrorali.bilivideo.com"),
        UposNode("alib（阿里）", "upos-sz-mirroralib.bilivideo.com"),
        UposNode("alio1（阿里）", "upos-sz-mirroralio1.bilivideo.com"),
        UposNode("bos（百度）", "upos-sz-mirrorbos.bilivideo.com"),
        UposNode("cos（腾讯）", "upos-sz-mirrorcos.bilivideo.com"),
        UposNode("cosb（腾讯）", "upos-sz-mirrorcosb.bilivideo.com"),
        UposNode("coso1（腾讯）", "upos-sz-mirrorcoso1.bilivideo.com"),
        UposNode("hw（华为）", "upos-sz-mirrorhw.bilivideo.com"),
        UposNode("hwb（华为）", "upos-sz-mirrorhwb.bilivideo.com"),
        UposNode("hwo1（华为）", "upos-sz-mirrorhwo1.bilivideo.com"),
        UposNode("08c（华为）", "upos-sz-mirror08c.bilivideo.com"),
        UposNode("08h（华为）", "upos-sz-mirror08h.bilivideo.com"),
        UposNode("08ct（华为）", "upos-sz-mirror08ct.bilivideo.com"),
        UposNode("tf_hw（华为）", "upos-tf-all-hw.bilivideo.com"),
        UposNode("tf_tx（腾讯）", "upos-tf-all-tx.bilivideo.com"),
        UposNode("akamai（Akamai海外）", "upos-hz-mirrorakam.akamaized.net"),
        UposNode("aliov（阿里海外）", "upos-sz-mirroraliov.bilivideo.com"),
        UposNode("cosov（腾讯海外）", "upos-sz-mirrorcosov.bilivideo.com"),
        UposNode("hwov（华为海外）", "upos-sz-mirrorhwov.bilivideo.com"),
        UposNode("hk_bcache（Bilibili海外）", "cn-hk-eq-bcache-01.bilivideo.com"),
    )

    private val pool = java.util.concurrent.ThreadPoolExecutor(
        2, 6, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(),
        { r -> Thread(r, "BiliSB-UposSpeedTest").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }

    /**
     * 格式化传输速率。
     */
    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec <= 0 -> "0 KB/s"
            bytesPerSec >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            else -> String.format(java.util.Locale.US, "%d KB/s", bytesPerSec / 1024)
        }
    }

    /**
     * 测试单个节点的连接延迟与速率。
     * [sampleUrl]: 用于测速的样本音频/视频流 URL；为空时默认构造试探地址。
     */
    fun testNode(node: UposNode, sampleUrl: String? = null, timeoutMs: Int = 4000): TestResult {
        val testUrl = if (!sampleUrl.isNullOrBlank()) {
            UposReplacer.replaceHost(sampleUrl, node.host)
        } else {
            "https://${node.host}/upgcxcode/test.mp4"
        }

        val start = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        var stream: InputStream? = null
        try {
            conn = (URL(testUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "Bilibili Freedoooooom/MarkII")
                setRequestProperty("Range", "bytes=0-1048575") // 最多读取 1MB 测速
                instanceFollowRedirects = true
            }
            conn.connect()
            val latency = (System.currentTimeMillis() - start).coerceAtLeast(1)

            val code = conn.responseCode
            // 200/206 视为有效；若为 403/404 等，记录延迟但速度置 0，避免误导排到前面
            if (code !in 200..299 && code != 206) {
                return TestResult(node, latency, 0L, "${latency}ms (响应 $code)")
            }

            stream = conn.inputStream
            val buf = ByteArray(4096)
            var totalRead = 0L
            val readStart = System.currentTimeMillis()
            val maxDuration = 2000L // 单个节点最多采样 2 秒

            while (System.currentTimeMillis() - readStart < maxDuration) {
                val n = stream.read(buf)
                if (n <= 0) break
                totalRead += n
                if (totalRead >= 1024 * 1024) break // 读满 1MB 结束
            }
            val elapsed = (System.currentTimeMillis() - readStart).coerceAtLeast(1)
            val speedBytes = (totalRead * 1000L) / elapsed
            return TestResult(node, latency, speedBytes, formatSpeed(speedBytes))
        } catch (t: Throwable) {
            val latency = System.currentTimeMillis() - start
            return TestResult(node, latency, 0L, "超时 / 失败", t.message ?: "连接失败")
        } finally {
            runCatching { stream?.close() }
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 批量并发测试所有节点。
     */
    fun testAllAsync(
        sampleUrl: String? = null,
        onProgress: (TestResult) -> Unit,
        onComplete: (List<TestResult>) -> Unit,
    ): Future<*> {
        return pool.submit {
            val results = java.util.Collections.synchronizedList(mutableListOf<TestResult>())
            val latch = java.util.concurrent.CountDownLatch(NODES.size)
            for (node in NODES) {
                if (Thread.currentThread().isInterrupted) break
                pool.submit {
                    try {
                        val res = testNode(node, sampleUrl)
                        results.add(res)
                        onProgress(res)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
            val sorted = synchronized(results) {
                results.sortedWith(
                    compareByDescending<TestResult> { it.speedBytesPerSec }.thenBy { it.latencyMs },
                )
            }
            onComplete(sorted)
        }
    }
}
