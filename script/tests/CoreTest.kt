import com.outman.tvlauncher.core.EntryPolicy
import com.outman.tvlauncher.core.EntryPolicy.EntryPage
import com.outman.tvlauncher.core.M3uParser
import com.outman.tvlauncher.core.PlaybackPolicy
import com.outman.tvlauncher.core.PlaybackPhase
import com.outman.tvlauncher.core.ExternalNavigation

private var passed = 0

private fun test(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("通过：$name")
    } catch (failure: Throwable) {
        throw AssertionError("失败：$name", failure)
    }
}

private fun invalid(block: () -> Unit) {
    try {
        block()
    } catch (_: IllegalArgumentException) {
        return
    }
    error("预期抛出 IllegalArgumentException")
}

private fun entry(
    name: String = "测试频道",
    url: String = "https://example.com/live.m3u8",
    attributes: String = ""
): String = "#EXTINF:-1 $attributes,$name\n$url\n"

private fun decide(
    hasChannels: Boolean = true,
    isHomeIntent: Boolean = false,
    bootCount: Int = 3,
    lastBootCount: Int = 3,
    autoPlay: Boolean = true,
    savedFullscreen: Boolean? = null,
    isRecreation: Boolean = false
): EntryPage = EntryPolicy.decide(
    hasChannels, isHomeIntent, bootCount, lastBootCount, autoPlay, savedFullscreen, isRecreation
)

fun main() {
    for (play in listOf(false, true)) for (suppressed in listOf(false, true))
        for (ready in listOf(false, true)) for (frame in listOf(false, true)) {
            test("播放状态矩阵 play=$play suppressed=$suppressed ready=$ready frame=$frame") {
                val phase=PlaybackPolicy.phase(play,suppressed,ready,frame)
                check(PlaybackPolicy.mayAdvance(play,suppressed)==(play && !suppressed))
                check(PlaybackPolicy.needsWatchdog(play,suppressed,ready,frame)==(play && !suppressed && !(ready && frame)))
                check(phase.keepScreenOn==(play && !suppressed))
                if(suppressed) check(phase==PlaybackPhase.SUPPRESSED)
                else if(!play) check(phase==PlaybackPhase.PAUSED)
                else check(phase==if(ready && frame) PlaybackPhase.PLAYING else PlaybackPhase.BUFFERING)
            }
        }
    test("焦点抑制后恢复只在等待首帧时重启看门狗") {
        check(!PlaybackPolicy.needsWatchdog(true,true,false,false))
        check(PlaybackPolicy.needsWatchdog(true,false,false,false))
        check(!PlaybackPolicy.needsWatchdog(true,false,true,true))
    }
    test("停止错误暂停抑制均不保屏") {
        listOf(PlaybackPhase.STOPPED,PlaybackPhase.ERROR,PlaybackPhase.PAUSED,PlaybackPhase.SUPPRESSED).forEach {check(!it.keepScreenOn)}
    }
    test("连接缓冲播放重试恢复均保屏") {
        listOf(PlaybackPhase.PREPARING,PlaybackPhase.BUFFERING,PlaybackPhase.PLAYING,PlaybackPhase.RETRYING,PlaybackPhase.RECOVERING).forEach {check(it.keepScreenOn)}
    }
    test("外部入口首次成功立即停止回退") {
        var calls=0
        check(ExternalNavigation.launchFirst(listOf({calls++}, {error("不应调用")})))
        check(calls==1)
    }
    test("外部入口拒绝和缺失继续回退") {
        var calls=0
        check(ExternalNavigation.launchFirst(listOf({calls++;throw SecurityException()}, {calls++;throw IllegalStateException()}, {calls++})))
        check(calls==3)
    }
    test("所有系统入口失败可恢复当前播放") {
        check(!ExternalNavigation.launchFirst(listOf({throw SecurityException()}, {throw IllegalStateException()})))
    }
    test("空入口列表返回失败") {check(!ExternalNavigation.launchFirst(emptyList()))}
    test("普通扩展 M3U 元数据") {
        val result = M3uParser.parse("#EXTM3U\n" + entry(attributes =
            "tvg-id=\"demo\" group-title=\"演示\" tvg-logo=\"https://example.com/logo.png\""))
        check(result.skipped == 0)
        val channel = result.channels.single()
        check(channel.name == "测试频道" && channel.group == "演示")
        check(channel.logo == "https://example.com/logo.png")
        check(channel.id.matches(Regex("[0-9a-f]{64}")))
    }
    test("UTF8 BOM 与 CRLF") {
        check(M3uParser.parse("\uFEFF#EXTM3U\r\n" + entry().replace("\n", "\r\n")).channels.size == 1)
    }
    test("空行与普通注释不截断条目") {
        val text = "#EXTM3U\n#EXTINF:-1,频道\n\n# 普通注释\nhttps://example.com/live\n"
        check(M3uParser.parse(text).channels.single().name == "频道")
    }
    test("无分组与空分组默认未分组") {
        check(M3uParser.parse(entry()).channels.single().group == "未分组")
        check(M3uParser.parse(entry(attributes = "group-title=\" \"")).channels.single().group == "未分组")
    }
    test("属性引号里的逗号不截断名称") {
        val channel = M3uParser.parse(entry("频道,高清", attributes = "group-title=\"A,B\"")).channels.single()
        check(channel.name == "频道,高清" && channel.group == "A,B")
    }
    test("单引号及无引号属性") {
        val channel = M3uParser.parse(entry(attributes = "group-title='测试组' tvg-id=demo")).channels.single()
        check(channel.group == "测试组")
    }
    test("标签属性与协议大小写兼容") {
        val text = "#extinf:-1 GROUP-TITLE=\"演示\",频道\nHTTPS://example.com/live\n"
        check(M3uParser.parse(text).channels.single().group == "演示")
    }
    test("URL 与名称首尾空格修剪") {
        val channel = M3uParser.parse(entry("  频道  ", "  https://example.com/live  ")).channels.single()
        check(channel.name == "频道" && channel.url == "https://example.com/live")
    }
    test("同名同 URL 去重并统计") {
        val result = M3uParser.parse(entry() + entry(attributes = "group-title=\"另组\""))
        check(result.channels.size == 1 && result.skipped == 1)
    }
    test("同名不同 URL 不合并") {
        val result = M3uParser.parse(entry() + entry(url = "https://example.com/other"))
        check(result.channels.size == 2 && result.channels[0].id != result.channels[1].id)
    }
    test("同 URL 不同名称不合并") {
        check(M3uParser.parse(entry("频道A") + entry("频道B")).channels.size == 2)
    }
    test("CCTV5 与 CCTV5+ 不误合并") {
        val channels = M3uParser.parse(entry("CCTV5") + entry("CCTV5+")).channels
        check(channels.size == 2 && channels[0].id != channels[1].id)
    }
    test("频道 ID 重复解析和重排序稳定") {
        val first = entry("A")
        val second = entry("B", "https://example.com/b")
        val left = M3uParser.parse(first + second).channels.associate { it.name to it.id }
        val right = M3uParser.parse(second + first).channels.associate { it.name to it.id }
        check(left == right)
    }
    test("频道 ID 包含分组名称 URL 及 tvg-id") {
        val base = M3uParser.parse(entry()).channels.single().id
        listOf(entry("另一个名称"), entry(url = "https://example.com/other"),
            entry(attributes = "group-title=\"另组\""), entry(attributes = "tvg-id=\"another\""))
            .forEach { check(M3uParser.parse(it).channels.single().id != base) }
    }
    test("tvg-id 相同不覆盖不同频道") {
        val channels = M3uParser.parse(entry("A", attributes = "tvg-id=\"same\"") +
            entry("B", attributes = "tvg-id=\"same\"")).channels
        check(channels.size == 2 && channels[0].id != channels[1].id)
    }
    test("空文本及纯注释拒绝") {
        listOf("", "\uFEFF", " \n ", "#EXTM3U\n# 注释").forEach { invalid { M3uParser.parse(it) } }
    }
    test("裸 URL 无元数据跳过") {
        val result = M3uParser.parse("https://example.com/orphan\n" + entry())
        check(result.skipped == 1 && result.channels.size == 1)
    }
    test("缺名称及损坏 EXTINF 跳过") {
        val result = M3uParser.parse(entry("") + "#EXTINF:-1\nhttps://example.com/bad\n" +
            "#EXTINF:wrong,名称\nhttps://example.com/wrong\n" + entry())
        check(result.skipped == 3 && result.channels.size == 1)
    }
    test("未闭合属性引号拒绝") {
        val result = M3uParser.parse("#EXTINF:-1 group-title=\"broken,频道\nhttps://example.com/bad\n" + entry())
        check(result.skipped == 1)
    }
    test("缺 URL 与连续 EXTINF 各计一次跳过") {
        val result = M3uParser.parse("#EXTINF:-1,丢失\n" + entry() + "#EXTINF:-1,尾部丢失\n")
        check(result.skipped == 2 && result.channels.size == 1)
    }
    test("不支持协议逐条跳过") {
        val unsupported = listOf("udp://example.com:1234", "rtp://example.com/live", "file:///tmp/live", "ftp://example.com/live")
        val result = M3uParser.parse(unsupported.joinToString("") { entry(url = it) } + entry())
        check(result.skipped == unsupported.size)
    }
    test("非法主机端口与 URI 跳过") {
        val urls = listOf("https:///live", "https://", "/relative", "https://bad host/live", "https://example.com:0/a",
            "https://example.com:65536/a", "https://example.com:abc/a", "https://example.com/%xx", "https://example.com/a\u0000")
        val result = M3uParser.parse(urls.joinToString("") { entry(url = it) } + entry())
        check(result.skipped == urls.size)
    }
    test("HTTP 查询字符串和合法 IPv6 保留") {
        val urls = listOf("http://example.com:8080/live?a=1&b=2", "https://[2001:db8::1]:443/live")
        check(M3uParser.parse(urls.joinToString("") { entry(url = it) }).channels.map { it.url } == urls)
    }
    test("不支持的头部与 DRM 扩展跳过整个条目") {
        val extensions = listOf("#EXTVLCOPT:http-referrer=https://example.com", "#EXTHTTP:{\"User-Agent\":\"demo\"}",
            "#KODIPROP:inputstream.adaptive.license_type=demo", "#EXT-X-KEY:METHOD=AES-128", "#EXT-X-SESSION-KEY:METHOD=AES-128")
        val text = extensions.joinToString("") { "#EXTINF:-1,扩展频道\n$it\nhttps://example.com/header\n" }
        check(M3uParser.parse(text + entry()).skipped == extensions.size)
    }
    test("元数据前请求头扩展不被忽略") {
        check(M3uParser.parse("#EXTVLCOPT:http-user-agent=demo\n" + entry("头部频道") + entry()).skipped == 1)
    }
    test("URL 尾部管道请求头不被假装支持") {
        val result = M3uParser.parse(entry(url = "https://example.com/live|User-Agent=demo") + entry())
        check(result.skipped == 1)
    }
    test("不安全或非法图标地址清空但保留频道") {
        listOf("file:///tmp/icon.png", "javascript:alert(1)", "not-a-url").forEach {
            val result = M3uParser.parse(entry(attributes = "tvg-logo=\"$it\""))
            check(result.channels.single().logo.isEmpty() && result.skipped == 0)
        }
    }
    test("2MiB 精确边界允许，超过拒绝") {
        val prefix = entry() + "#"
        val exact = prefix + "x".repeat(M3uParser.MAX_BYTES - prefix.toByteArray(Charsets.UTF_8).size)
        check(M3uParser.parse(exact).channels.size == 1)
        invalid { M3uParser.parse(exact + "x") }
    }
    test("UTF8 多字节大小限制而非字符计数") {
        invalid { M3uParser.parse(entry() + "#" + "中".repeat(M3uParser.MAX_BYTES / 3)) }
    }
    test("5000 频道精确边界允许") {
        val text = (1..M3uParser.MAX_CHANNELS).joinToString("") { entry("频道$it", "https://example.com/$it") }
        check(M3uParser.parse(text).channels.size == M3uParser.MAX_CHANNELS)
        invalid { M3uParser.parse(text + entry("超限")) }
    }
    test("重复及损坏条目也受数量上限约束") {
        invalid { M3uParser.parse(entry().repeat(M3uParser.MAX_CHANNELS + 1)) }
        invalid { M3uParser.parse(entry() + "#EXTINF:-1,丢失\n".repeat(M3uParser.MAX_CHANNELS)) }
    }
    test("全部无效时拒绝替换频道库") {
        invalid { M3uParser.parse(entry(url = "udp://example.com/live")) }
    }
    test("首次无频道总是进入设置") {
        check(decide(hasChannels = false, isHomeIntent = true, bootCount = 4, savedFullscreen = true) == EntryPage.SETUP)
    }
    test("新设备启动周期 HOME 自动全屏") {
        check(decide(isHomeIntent = true, bootCount = 4) == EntryPage.FULLSCREEN)
    }
    test("新周期优先于旧保存页面") {
        check(decide(isHomeIntent = true, bootCount = 4, savedFullscreen = false, isRecreation = true) == EntryPage.FULLSCREEN)
    }
    test("新周期关闭自动播放进入首页") {
        check(decide(isHomeIntent = true, bootCount = 4, autoPlay = false, savedFullscreen = true) == EntryPage.HOME)
    }
    test("同周期新 HOME 覆盖全屏保存状态") {
        check(decide(isHomeIntent = true, savedFullscreen = true, isRecreation = true) == EntryPage.HOME)
    }
    test("同周期手动启动与进程恢复保守首页") {
        check(decide(savedFullscreen = true) == EntryPage.HOME)
    }
    test("同周期无新导航配置重建恢复全屏") {
        check(decide(savedFullscreen = true, isRecreation = true) == EntryPage.FULLSCREEN)
    }
    test("配置重建首页与无状态仍在首页") {
        check(decide(savedFullscreen = false, isRecreation = true) == EntryPage.HOME)
        check(decide(savedFullscreen = null, isRecreation = true) == EntryPage.HOME)
    }
    test("未知启动周期即使恢复全屏也回首页") {
        check(decide(bootCount = -1, lastBootCount = -1, savedFullscreen = true, isRecreation = true) == EntryPage.HOME)
        check(decide(bootCount = -1, isHomeIntent = true) == EntryPage.HOME)
    }
    test("新周期手动入口不执行开机全屏") {
        check(decide(bootCount = 4, savedFullscreen = true, isRecreation = true) == EntryPage.HOME)
    }
    test("已知首次启动周期 HOME 可自动全屏") {
        check(decide(bootCount = 0, lastBootCount = -1, isHomeIntent = true) == EntryPage.FULLSCREEN)
    }
    test("关闭自动播放不阻止配置重建恢复用户全屏") {
        check(decide(autoPlay = false, savedFullscreen = true, isRecreation = true) == EntryPage.FULLSCREEN)
    }
    println("全部 $passed 项核心测试通过")
}
