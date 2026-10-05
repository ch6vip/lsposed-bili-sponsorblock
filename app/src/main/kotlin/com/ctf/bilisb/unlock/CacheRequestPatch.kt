package com.ctf.bilisb.unlock

import com.ctf.bilisb.unlock.proto.PlayViewUniteReq

/** 下载请求补参；保留宿主已声明的新能力位及所有未知字段。 */
internal object CacheRequestPatch {
    const val REQUIRED_FNVAL = 16 or 64 or 128 or 256 or 512 or 1024 or 2048

    fun patch(bytes: ByteArray): ByteArray {
        val request = PlayViewUniteReq.parseFrom(bytes)
        if (request.vod.download <= 0) return bytes
        return request.toBuilder().setVod(
            request.vod.toBuilder()
                .setFnval(request.vod.fnval or REQUIRED_FNVAL)
                .setFourk(true)
                .setDownload(0),
        ).build().toByteArray()
    }

    /** 序列化、补参或宿主 parseFrom 任一步失败，都保留原对象。 */
    fun <T : Any> apply(
        request: T,
        serialize: (T) -> ByteArray,
        parse: (ByteArray) -> T,
        onFailure: (Throwable) -> Unit,
    ): T = try {
        val bytes = serialize(request)
        val patched = patch(bytes)
        if (patched === bytes) request else parse(patched)
    } catch (failure: Exception) {
        onFailure(failure)
        request
    }
}
