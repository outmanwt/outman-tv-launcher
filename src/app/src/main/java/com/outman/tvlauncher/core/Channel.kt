package com.outman.tvlauncher.core

data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val url: String,
    val logo: String = ""
)

data class ParseResult(val channels: List<Channel>, val skipped: Int)
