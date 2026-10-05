package com.ctf.bilisb.unlock

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** 等待结束后取消未完成任务，避免超时请求继续排队、运行和修改状态。 */
internal object BoundedCall {
    fun <T> await(executor: ExecutorService, timeoutMs: Long, block: () -> T): T {
        val future = executor.submit(Callable(block))
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } finally {
            if (!future.isDone) future.cancel(true)
        }
    }
}
