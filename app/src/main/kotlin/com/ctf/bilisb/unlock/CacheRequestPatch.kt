package com.ctf.bilisb.unlock

import com.ctf.bilisb.unlock.proto.PlayViewUniteReq

/** 下载请求补参；保留宿主已声明的新能力位及所有未知字段。 */
internal object CacheRequestPatch {
    const val FNVAL_DASH = 16
    const val FNVAL_HDR10 = 64
    const val FNVAL_4K = 128
    const val FNVAL_DOLBY_AUDIO = 256
    const val FNVAL_DOLBY_VISION = 512
    const val FNVAL_8K = 1024
    const val FNVAL_AV1 = 2048

    const val REQUIRED_FNVAL = FNVAL_DASH or FNVAL_HDR10 or FNVAL_4K or FNVAL_DOLBY_AUDIO or
        FNVAL_DOLBY_VISION or FNVAL_8K or FNVAL_AV1

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

    fun patchQuality(bytes: ByteArray, targetQn: Int): ByteArray {
        val request = PlayViewUniteReq.parseFrom(bytes)
        val vodBuilder = request.vod.toBuilder()
            .setFnval(request.vod.fnval or REQUIRED_FNVAL)
            .setFourk(true)
        if (targetQn > 0) {
            vodBuilder.setQn(targetQn.toLong())
        } else if (targetQn == -1) {
            vodBuilder.setQn(127L)
        }
        return request.toBuilder().setVod(vodBuilder).build().toByteArray()
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
    } catch (failure: Throwable) {
        onFailure(failure)
        request
    }
}
