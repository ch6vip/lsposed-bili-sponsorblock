package com.ctf.bilisb.unlock

/** 6.6.0 DEX 常量核实的缓存权限路径；不修改播放权限、会员状态或缓存鉴权响应。 */
internal object DownloadRightsPatch {
    fun patch(reply: ByteArray): ByteArray {
        val any = WireSplice.firstMessage(reply, 6) ?: return reply
        val type = WireSplice.firstMessage(any, 1)?.decodeToString()
        if (type != ViewTabHook.VIEW_PGC_ANY_TYPE_URL) return reply
        val withSeason = WireSplice.transformMessage(reply, 6) { supplement ->
            WireSplice.transformMessage(supplement, 2) { pgc ->
                WireSplice.transformMessage(pgc, 1) { ogv ->
                    updateMessage(ogv, 5) { rights ->
                        // pgcanymodel.Rights: allow_download=1, only_vip_download=15,
                        // new_allow_download=16（与 common.Rights 是不同消息）。
                        setVarints(rights, mapOf(1 to 1L, 15 to 0L, 16 to 1L))
                    }
                }
            }
        }
        return WireSplice.transformMessage(withSeason, 5) { tab ->
            WireSplice.transformMessage(tab, 1) { tabModule ->
                if (varint(tabModule, 1) != 1L) tabModule else {
                    WireSplice.transformMessage(tabModule, 2) { introduction ->
                        WireSplice.transformMessage(introduction, 2) { module ->
                            if (varint(module, 1) != 13L) module else {
                                WireSplice.transformMessage(module, 12) { section ->
                                    WireSplice.transformMessage(section, 7, ::patchEpisode)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun patchEpisode(episode: ByteArray): ByteArray = updateMessage(episode, 22) {
        setVarints(it, mapOf(1 to 1L))
    }

    private fun varint(bytes: ByteArray, field: Int): Long? =
        WireSplice.parse(bytes).firstOrNull { it.field == field && it.wireType == 0 }?.varint()

    private fun updateMessage(bytes: ByteArray, field: Int, transform: (ByteArray) -> ByteArray): ByteArray =
        if (WireSplice.firstMessage(bytes, field) != null) {
            WireSplice.transformMessage(bytes, field, transform)
        } else bytes + WireSplice.message(field, transform(byteArrayOf()))

    private fun setVarints(bytes: ByteArray, values: Map<Int, Long>): ByteArray {
        val unchanged = WireSplice.emit(WireSplice.parse(bytes).filterNot { it.field in values })
        return values.entries.fold(unchanged) { out, (field, value) ->
            out + WireSplice.varintElem(field, value)
        }
    }
}
