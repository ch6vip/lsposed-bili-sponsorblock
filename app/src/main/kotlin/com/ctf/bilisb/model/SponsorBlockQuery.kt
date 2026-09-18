package com.ctf.bilisb.model

/**
 * 片段拉取查询。
 *
 * 协议上 `/api/skipSegments/{hashPrefix}` 只按 bvid 的 hash 前缀返回、由客户端按 videoID 过滤,
 * cid/actionType 不参与请求与缓存维度 —— cid 仅用于提交与日志。
 */
data class SponsorBlockQuery(
    val bvid: String,
    val cid: Long,
)
