package com.ctf.bilisb.unlock

import java.lang.reflect.Method

/**
 * 用**宿主自己的 bapis 类**（反射）重建 `PlayViewUniteReply`。
 *
 * 设计说明（docs/UNLOCK_PLAN.md §4.1 的落地形态）：参考实现把 `param.result` 换成
 * 自己 proto 包的实例，其与宿主消费侧的类型关系依赖宿主内部细节，不宜照搬；
 * 本实现改为全反射操作宿主类——newBuilderForType/setter 均来自宿主自己的类，
 * 零跨类加载器类型问题。任何一步失败抛出，由调用方退化为放行原响应。
 *
 * 字段 setter 名与 [com.ctf.bilisb.unlock.proto] schema 的字段一一对应
 * （字段号运行时实测，见 bilisb_unlock.proto 头注）。
 */
object ResponseReconstructor {

    /** 反射调用：忽略参数类型精确匹配不存在时的查找失败。 */
    private fun invoke(target: Any, name: String, vararg args: Any?) {
        val argTypes = args.map { it?.javaClass?.resolvePrimitive() ?: Any::class.java }
        val m = findMethod(target.javaClass, name, argTypes)
            ?: throw NoSuchMethodException("${target.javaClass.name}.$name(${argTypes.joinToString()})")
        m.isAccessible = true
        m.invoke(target, *args)
    }

    private fun findMethod(cls: Class<*>, name: String, argTypes: List<Class<*>> = emptyList()): Method? =
        cls.methods.firstOrNull { m ->
            m.name == name && m.parameterTypes.size == argTypes.size &&
                m.parameterTypes.zip(argTypes).all { (p, a) ->
                    p == a || !p.isPrimitive || p == a.resolvePrimitive()
                }
        }

    private fun Class<*>.resolvePrimitive(): Class<*> = when (this) {
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        else -> this
    }

    /**
     * 重建响应：
     *  1. 从现有 hostReply（可空）起 builder，保留未知字段；
     *  2. vod_info ← [data]（DASH 双轨：video 按 preferCodec 过滤、audio 原样）；
     *  3. play_arc_conf ← 全能力放行（is_support=true）；
     *  4. supplement ← 空 ViewInfo 的 PGC PlayViewReply（清掉 area_limit 弹窗）。
     */
    fun rebuildReply(
        cl: ClassLoader,
        hostReply: Any?,
        data: PlayurlData,
        reqCid: Long,
        reqAid: Long,
    ): Any {
        val replyCls = cl.loadClass(com.ctf.bilisb.host.HostTargets.PLAY_VIEW_UNITE_REPLY_CLASS)
        val builder = if (hostReply != null) {
            replyCls.getMethod("newBuilder", replyCls).invoke(null, hostReply)
        } else {
            replyCls.getMethod("newBuilder").invoke(null)
        }

        // ---- vod_info ----
        val vodInfoCls = cl.loadClass(com.ctf.bilisb.host.HostTargets.VOD_INFO_CLASS)
        val vodInfoBuilder = vodInfoCls.getMethod("newBuilder").invoke(null)
        invoke(vodInfoBuilder, "setQuality", data.quality)
        invoke(vodInfoBuilder, "setFormat", data.format)
        invoke(vodInfoBuilder, "setTimelength", data.timelength)
        invoke(vodInfoBuilder, "setVideoCodecid", data.videoCodecid)
        for (track in data.videos) {
            val streamBuilder = vodInfoBuilder.javaClass.getMethod("addStreamListBuilder").invoke(vodInfoBuilder)
            val dashVideo = buildDashVideo(cl, track, audioId = 0)
            invoke(streamBuilder, "setDashVideo", dashVideo)
        }
        for (track in data.audios) {
            val dashItem = buildDashItem(cl, track)
            invoke(vodInfoBuilder, "addDashAudio", dashItem)
        }
        invoke(builder, "setVodInfo", vodInfoCls.getMethod("build").invoke(vodInfoBuilder))

        // ---- play_arc_conf：全能力放行 ----
        val arcConfCls = cl.loadClass("com.bapis.bilibili.app.playerunite.playershared.ArcConf")
        val arcConfBuilder = arcConfCls.getMethod("newBuilder").invoke(null)
        invoke(arcConfBuilder, "setIsSupport", true)
        val arcConf = arcConfCls.getMethod("build").invoke(arcConfBuilder)
        val playArcConfCls = cl.loadClass("com.bapis.bilibili.app.playerunite.playershared.PlayArcConf")
        val playArcConfBuilder = playArcConfCls.getMethod("newBuilder").invoke(null)
        // 指标能力索引：参考实现对 supportedPlayArcIndices 全量置 is_support=true；
        // 索引语义随版本漂移，全量 0..N 置位最稳（宿主只认认识的索引）
        for (idx in 0..15) {
            invoke(playArcConfBuilder, "putArcConfs", idx, arcConf)
        }
        invoke(builder, "setPlayArcConf", playArcConfCls.getMethod("build").invoke(playArcConfBuilder))

        // ---- supplement：空弹窗的 PGC PlayViewReply（清 area_limit）----
        val pgcReplyCls = cl.loadClass(com.ctf.bilisb.host.HostTargets.PLAY_VIEW_REPLY_CLASS)
        val pgcBuilder = pgcReplyCls.getMethod("newBuilder").invoke(null)
        val businessCls = cl.loadClass("com.bapis.bilibili.pgc.gateway.player.v2.PlayViewBusinessInfo")
        val businessBuilder = businessCls.getMethod("newBuilder").invoke(null)
        invoke(businessBuilder, "setIsPreview", false)
        invoke(pgcBuilder, "setBusiness", businessCls.getMethod("build").invoke(businessBuilder))
        val viewInfoCls = cl.loadClass("com.bapis.bilibili.pgc.gateway.player.v2.ViewInfo")
        val viewInfoBuilder = viewInfoCls.getMethod("newBuilder").invoke(null)
        invoke(pgcBuilder, "setViewInfo", viewInfoCls.getMethod("build").invoke(viewInfoBuilder))
        val pgcReply = pgcReplyCls.getMethod("build").invoke(pgcBuilder)
        val pgcBytes = pgcReply.javaClass.getMethod("toByteArray").invoke(pgcReply) as ByteArray

        val anyBuilder = runCatching {
            // 优先复用现有 supplement 的类型（同一 Any 类）
            val existing = findMethod(replyCls, "getSupplement").let { m ->
                m?.let { if (hostReply != null) m.invoke(hostReply) else null }
            }
            existing?.javaClass?.getMethod("newBuilderForType")?.invoke(existing)
        }.getOrNull() ?: cl.loadClass("com.google.protobuf.Any").getMethod("newBuilder").invoke(null)
        invoke(anyBuilder, "setTypeUrl", PlayViewDecision.PGC_ANY_MODEL_TYPE_URL)
        invoke(anyBuilder, "setValue", pgcBytes)
        invoke(builder, "setSupplement", anyBuilder.javaClass.getMethod("build").invoke(anyBuilder))

        // ---- play_arc：cid 保持请求侧（预取请求 respCid 可能为 0，避免 0 覆盖）----
        if (reqCid != 0L) {
            val playArcCls = cl.loadClass("com.bapis.bilibili.app.playerunite.playershared.PlayArc")
            val playArcBuilder = runCatching {
                val existingArc = findMethod(replyCls, "getPlayArc")?.let {
                    if (hostReply != null) it.invoke(hostReply) else null
                }
                existingArc?.javaClass?.getMethod("newBuilderForType")?.invoke(existingArc)
                    ?: playArcCls.getMethod("newBuilder").invoke(null)
            }.getOrNull() ?: return replyCls.getMethod("build").invoke(builder)
            invoke(playArcBuilder, "setCid", reqCid)
            if (reqAid != 0L) invoke(playArcBuilder, "setAid", reqAid)
            invoke(builder, "setPlayArc", playArcCls.getMethod("build").invoke(playArcBuilder))
        }

        return replyCls.getMethod("build").invoke(builder)
    }

    private fun buildDashVideo(cl: ClassLoader, track: DashTrack, audioId: Int): Any {
        val cls = cl.loadClass("com.bapis.bilibili.app.playerunite.playershared.DashVideo")
        val b = cls.getMethod("newBuilder").invoke(null)
        invoke(b, "setBaseUrl", track.baseUrl)
        for (bk in track.backupUrls) invoke(b, "addBackupUrl", bk)
        invoke(b, "setBandwidth", track.bandwidth)
        invoke(b, "setCodecid", track.codecid)
        if (track.md5.isNotEmpty()) invoke(b, "setMd5", track.md5)
        if (track.size > 0) invoke(b, "setSize", track.size)
        if (audioId != 0) invoke(b, "setAudioId", audioId)
        return cls.getMethod("build").invoke(b)
    }

    private fun buildDashItem(cl: ClassLoader, track: DashTrack): Any {
        val cls = cl.loadClass("com.bapis.bilibili.app.playerunite.playershared.DashItem")
        val b = cls.getMethod("newBuilder").invoke(null)
        invoke(b, "setId", track.id)
        invoke(b, "setBaseUrl", track.baseUrl)
        for (bk in track.backupUrls) invoke(b, "addBackupUrl", bk)
        invoke(b, "setBandwidth", track.bandwidth)
        invoke(b, "setCodecid", track.codecid)
        if (track.md5.isNotEmpty()) invoke(b, "setMd5", track.md5)
        if (track.size > 0) invoke(b, "setSize", track.size)
        return cls.getMethod("build").invoke(b)
    }
}
