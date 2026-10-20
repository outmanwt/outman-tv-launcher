package com.outman.tvlauncher

import android.content.Context
import android.util.AtomicFile
import com.outman.tvlauncher.core.Channel
import com.outman.tvlauncher.core.M3uParser
import com.outman.tvlauncher.core.ParseResult
import java.io.File
import java.io.InputStream

class ChannelStore(context: Context) {
    data class PreparedImport(val text: String, val result: ParseResult)
    private val file = AtomicFile(File(context.filesDir, "channels.m3u"))
    val preferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    fun load(): List<Channel> = synchronized(LOCK) {
        try { M3uParser.parse(file.openRead().use { readLimited(it) }).channels } catch (_: Exception) { emptyList() }
    }
    // Worker threads only read/parse. A destroyed Activity can never publish an old import.
    fun prepare(input: InputStream): PreparedImport {
        val text = readLimited(input)
        val parsed = M3uParser.parse(text)
        require(parsed.channels.isNotEmpty()) { "没有有效的 HTTP/HTTPS 频道" }
        return PreparedImport(text, parsed)
    }
    fun commit(prepared: PreparedImport) = synchronized(LOCK) {
        val out = file.startWrite()
        try { out.write(prepared.text.toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
    private fun readLimited(input: InputStream): String {
        val max = 2 * 1024 * 1024
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            check(!Thread.currentThread().isInterrupted) { "导入已取消" }
            val size = input.read(buffer)
            if (size < 0) break
            require(output.size() + size <= max) { "频道文件不能超过 2 MiB" }
            output.write(buffer, 0, size)
        }
        return output.toString(Charsets.UTF_8.name())
    }
    companion object { private val LOCK = Any() }
}
