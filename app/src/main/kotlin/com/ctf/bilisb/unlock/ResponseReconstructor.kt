package com.ctf.bilisb.unlock

import com.ctf.bilisb.host.HostTargets

/**
 * 用**宿主自己的 bapis 类**（wire bytes → parseFrom）重建 `PlayViewUniteReply`。
 *
 * 设计说明（docs/UNLOCK_PLAN.md §4.1）：参考实现把 `param.result` 换成自己 proto 包的
 * 实例，其与宿主消费侧的类型关系依赖宿主内部细节，不宜照搬；本实现改为——
 * 内层消息用 [UnlockWire] 手工构造 wire bytes（字段号运行时实测），再用宿主类的
 * `parseFrom` 反序列化成宿主自己的实例；顶层 reply 用宿主 Builder 合并
 * （setVodInfo/setSupplement/setPlayArc 在 PlayViewUniteReply$b 上索引实证存在）。
 * 任何一步失败抛出，由调用方退化为放行原响应。
 *
 * 为什么不用 Builder 反射：宿主 protobuf 版本对嵌套消息只生成 builder 重载
 * （addStreamList(Stream$b)），Builder 获取链路（newBuilderForType/dynamicMethod）
 * 跨版本不稳；wire bytes + parseFrom 只依赖字段号（U2 已证明字节级兼容）。
 */
object ResponseReconstructor {

    private fun parseHost(cl: ClassLoader, className: String, bytes: ByteArray): Any =
        cl.loadClass(className).getMethod("parseFrom", ByteArray::class.java).invoke(null, bytes)

    private fun invoke(target: Any, name: String, arg: Any?) {
        val m = target.javaClass.methods.firstOrNull { f ->
            f.name == name && f.parameterTypes.size == 1 &&
                (f.parameterTypes[0] == arg?.javaClass || arg == null)
        } ?: throw NoSuchMethodException("${target.javaClass.name}.$name(${arg?.javaClass?.name})")
        m.isAccessible = true
        m.invoke(target, arg)
    }

    /**
     * 重建响应：
     *  1. vod_info ← 漫游响应的 DASH 双轨；
     *  2. supplement ← 空弹窗的 PGC 载荷（清 area_limit）；
     *  3. play_arc ← cid 保持请求侧（避免响应侧 0/异 cid 覆盖）。
     */
    fun rebuildReply(
        cl: ClassLoader,
        hostReply: Any?,
        data: PlayurlData,
        reqCid: Long,
        reqAid: Long,
    ): Any {
        val replyCls = cl.loadClass(HostTargets.PLAY_VIEW_UNITE_REPLY_CLASS)
        val builder = if (hostReply != null) {
            replyCls.getMethod("newBuilder", replyCls).invoke(null, hostReply)
        } else {
            replyCls.getMethod("newBuilder").invoke(null)
        }

        val vodInfo = parseHost(cl, HostTargets.VOD_INFO_CLASS, UnlockWire.buildVodInfoBytes(data))
        invoke(builder, "setVodInfo", vodInfo)

        // supplement：解析原值（自备 schema，未知字段保留）→ 清 view_info（去 area_limit
        // 弹窗）→ business.is_preview=false（episode_info 等 UI 数据原样保留）。
        // 参考 reconstructResponseUnite 同语义：不改 playArc（原响应的 cid/video_type/
        // duration 已是本集正确值），playArcConf 走 newBuilder(reply) 合并原样保留。
        val origSupplementBytes = runCatching {
            val supplement = replyCls.methods.firstOrNull { f -> f.name == "getSupplement" }
                ?.invoke(hostReply ?: return@runCatching null as ByteArray?)
            val value = supplement?.javaClass?.methods
                ?.firstOrNull { f -> f.name == "getValue" }?.invoke(supplement)
            value?.javaClass?.getMethod("toByteArray")?.invoke(value) as? ByteArray
        }.getOrNull()

        val pgcPayload = if (origSupplementBytes != null && origSupplementBytes.isNotEmpty()) {
            com.ctf.bilisb.unlock.proto.PlayViewReply.parseFrom(origSupplementBytes).toBuilder()
                .clearViewInfo()
                .setBusiness(
                    com.ctf.bilisb.unlock.proto.PlayViewReply.parseFrom(origSupplementBytes)
                        .business.toBuilder().setIsPreview(false).build(),
                )
                .build()
                .toByteArray()
        } else {
            UnlockWire.buildPgcPayloadBytes()
        }
        val supplement = parseHost(
            cl,
            "com.google.protobuf.Any",
            UnlockWire.buildAnyBytes(PlayViewDecision.PGC_ANY_MODEL_TYPE_URL, pgcPayload),
        )
        invoke(builder, "setSupplement", supplement)

        return builder.javaClass.getMethod("build").invoke(builder)
    }
}
