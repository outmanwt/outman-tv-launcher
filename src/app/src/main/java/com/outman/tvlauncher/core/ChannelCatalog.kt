/*
 * MIT License
 * Copyright (c) 2020-PRESENT yaoxieyoulei
 *
 * 归组结构改编自 minyoad/mytv-android，提交：
 * 9263a658b373f573410adeed135b2a6c49bbc1f7
 * 源文件：core/data/src/main/java/top/yogiczy/mytv/core/data/repositories/iptv/parser/M3uIptvParser.kt
 * 改动：保留 groupBy/map/distinct 归组；输出纯 Kotlin 模型；增加稳定 ID 与旧 ID 迁移集合。
 * 不引入频道名标准化、EPG、协程或上游播放器。完整许可见 assets/licenses/mytv-android-MIT.txt。
 */
package com.outman.tvlauncher.core

import java.nio.ByteBuffer
import java.security.MessageDigest

data class ChannelRoute(val id: String, val url: String)

data class LiveChannel(
    val id: String,
    val name: String,
    val group: String,
    val routes: List<ChannelRoute>,
    val logo: String = "",
    val legacyIds: Set<String> = emptySet()
)

object ChannelCatalog {
    fun group(channels: List<Channel>): List<LiveChannel> =
        channels.groupBy { it.group }.flatMap { groupEntry ->
            groupEntry.value.groupBy { it.name }.map { nameEntry ->
                LiveChannel(
                    id = channelId(groupEntry.key, nameEntry.key),
                    name = nameEntry.key,
                    group = groupEntry.key,
                    routes = nameEntry.value.map { it.url }.distinct().map { url ->
                        ChannelRoute(sha256(url.toByteArray(Charsets.UTF_8)), url)
                    },
                    logo = nameEntry.value.first().logo,
                    legacyIds = nameEntry.value.map { it.id }.toSet()
                )
            }
        }

    private fun channelId(group: String, name: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // UTF-8 字节数的四字节大端长度前缀避免组名/频道名分隔符碰撞。
        for (value in listOf(group, name)) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return hex(digest.digest())
    }

    private fun sha256(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
