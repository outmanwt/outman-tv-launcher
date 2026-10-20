package com.outman.tvlauncher.core

import java.net.URI
import java.security.MessageDigest

object M3uParser {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_CHANNELS = 5000

    private data class Metadata(
        val name: String,
        val group: String,
        val tvgId: String,
        val logo: String
    )

    private val attributes = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s]+))""")
    private val duration = Regex("""^[+-]?\d+(?:\.\d+)?(?:\s|$)""")
    private val unsupportedExtensions = listOf(
        "#EXTVLCOPT", "#EXTHTTP", "#KODIPROP", "#EXT-X-KEY", "#EXT-X-SESSION-KEY"
    )

    fun parse(text: String): ParseResult {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "播放列表超过 2 MiB 上限"
        }
        val channels = mutableListOf<Channel>()
        val seen = mutableSetOf<Pair<String, String>>()
        var skipped = 0
        var entries = 0
        var pending: Metadata? = null
        var hasPending = false
        var unsupported = false

        fun countEntry() {
            entries++
            require(entries <= MAX_CHANNELS) { "播放列表超过 5000 条上限" }
        }

        for (rawLine in text.removePrefix("\uFEFF").lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                if (hasPending) {
                    skipped++
                    unsupported = false
                }
                countEntry()
                pending = parseMetadata(line.substringAfter(':'))
                hasPending = true
                continue
            }
            if (line.startsWith('#')) {
                if (unsupportedExtensions.any {
                        line.equals(it, ignoreCase = true) ||
                            line.startsWith("$it:", ignoreCase = true)
                    }) {
                    unsupported = true
                }
                continue
            }

            val metadata = pending
            if (!hasPending) countEntry()
            if (!hasPending || metadata == null || unsupported || !isHttpUrl(line) ||
                !seen.add(metadata.name to line)
            ) {
                skipped++
            } else {
                channels += Channel(
                    id = stableId(metadata, line),
                    name = metadata.name,
                    group = metadata.group,
                    url = line,
                    logo = metadata.logo
                )
            }
            pending = null
            hasPending = false
            unsupported = false
        }
        if (hasPending) skipped++
        require(channels.isNotEmpty()) { "未找到有效 HTTP/HTTPS 频道；请检查列表格式和不受支持的扩展" }
        return ParseResult(channels.toList(), skipped)
    }

    private fun parseMetadata(value: String): Metadata? {
        var quote: Char? = null
        var delimiter = -1
        for ((index, char) in value.withIndex()) {
            if (quote != null) {
                if (char == quote) quote = null
            } else if (char == '"' || char == '\'') {
                quote = char
            } else if (char == ',') {
                delimiter = index
                break
            }
        }
        if (delimiter < 0) return null
        val header = value.substring(0, delimiter).trim()
        if (!duration.containsMatchIn(header)) return null
        val name = value.substring(delimiter + 1).trim()
        if (name.isEmpty()) return null
        val fields = attributes.findAll(header).associate {
            it.groupValues[1].lowercase(java.util.Locale.ROOT) to
                (it.groups[2]?.value ?: it.groups[3]?.value ?: it.groupValues[4]).trim()
        }
        return Metadata(
            name = name,
            group = fields["group-title"].orEmpty().ifBlank { "未分组" },
            tvgId = fields["tvg-id"].orEmpty(),
            logo = fields["tvg-logo"].orEmpty().takeIf { isHttpUrl(it) }.orEmpty()
        )
    }

    private fun isHttpUrl(value: String): Boolean {
        if (value.any { it.isWhitespace() || it.isISOControl() }) return false
        return try {
            val uri = URI(value)
            (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 1..65535)
        } catch (_: java.net.URISyntaxException) {
            false
        }
    }

    private fun stableId(metadata: Metadata, url: String): String {
        // 长度前缀避免字段中的分隔符造成同一摘要输入。
        val input = listOf(metadata.group, metadata.name, url, metadata.tvgId)
            .joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
