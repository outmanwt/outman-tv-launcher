import com.outman.tvlauncher.core.Channel
import com.outman.tvlauncher.core.ChannelCatalog
import com.outman.tvlauncher.core.ChannelRoute
import com.outman.tvlauncher.core.M3uParser
import com.outman.tvlauncher.core.RouteSession

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

private fun channel(
    id: String = "旧标识", name: String = "CCTV5", group: String = "央视",
    url: String = "https://example.com/live.m3u8", logo: String = ""
) = Channel(id, name, group, url, logo)

private fun routes(count: Int) = (0 until count).map {
    ChannelRoute("route-$it", "https://example.com/$it.m3u8")
}

private fun exhaust(session: RouteSession): List<String> {
    val attempted = mutableListOf<String>()
    var current = session.start()
    while (current != null) {
        check(attempted.size < 10) { "自动选线没有终止" }
        attempted.add(current.id)
        current = session.onFailure(current.id)
    }
    return attempted
}

fun main() {
    test("空频道目录为空") { check(ChannelCatalog.group(emptyList()).isEmpty()) }
    test("同组同名合并线路且保留全部旧标识") {
        val result = ChannelCatalog.group(listOf(channel("旧一"), channel("旧二", url = "https://example.com/2"))).single()
        check(result.name == "CCTV5" && result.group == "央视")
        check(result.routes.size == 2 && result.legacyIds == setOf("旧一", "旧二"))
    }
    test("重复URL去重但不丢重复记录的旧标识") {
        val result = ChannelCatalog.group(listOf(channel("旧一"), channel("旧二"), channel("旧一"))).single()
        check(result.routes.size == 1 && result.legacyIds == setOf("旧一", "旧二"))
    }
    test("不同组同名同URL不合并") {
        val result = ChannelCatalog.group(listOf(channel(), channel(group = "地方")))
        check(result.size == 2 && result.map { it.id }.distinct().size == 2)
    }
    test("CCTV5与CCTV5加号独立") {
        check(ChannelCatalog.group(listOf(channel(), channel(name = "CCTV5+"))).size == 2)
    }
    test("频道名高清后缀与空格保守保留") {
        check(ChannelCatalog.group(listOf(channel(), channel(name = "CCTV5 HD"), channel(name = "CCTV5 "))).size == 3)
    }
    test("同旧标识不构成跨频道归组依据") {
        check(ChannelCatalog.group(listOf(channel(), channel(name = "另一个频道"))).size == 2)
    }
    test("目录和线路保持初次出现顺序") {
        val result = ChannelCatalog.group(listOf(
            channel("一", group = "乙", name = "B", url = "https://example.com/2"),
            channel("二", group = "甲", name = "A"),
            channel("三", group = "乙", name = "C"),
            channel("四", group = "乙", name = "B", url = "https://example.com/1")
        ))
        check(result.map { it.name } == listOf("B", "C", "A"))
        check(result.first().routes.map { it.url } == listOf("https://example.com/2", "https://example.com/1"))
    }
    test("图标沿用首条记录") {
        val result = ChannelCatalog.group(listOf(channel(logo = "首图"), channel(logo = "次图"))).single()
        check(result.logo == "首图")
    }
    test("频道ID不受线路URL与排序旧ID或图标变化影响") {
        val one = ChannelCatalog.group(listOf(channel("一"), channel("二", url = "https://example.com/2"))).single()
        val two = ChannelCatalog.group(listOf(channel("三", url = "https://example.com/3", logo = "新图"))).single()
        check(one.id == two.id)
        val reversed = ChannelCatalog.group(listOf(channel("二", url = "https://example.com/2"), channel("一"))).single()
        check(one.id == reversed.id && one.routes.toSet() == reversed.routes.toSet())
    }
    test("长度前缀避免组名与频道名拼接碰撞") {
        val result = ChannelCatalog.group(listOf(channel(group = "ab", name = "c"), channel(group = "a", name = "bc")))
        check(result[0].id != result[1].id)
        val delimiters = ChannelCatalog.group(listOf(channel(group = "a|b", name = "c"), channel(group = "a", name = "b|c")))
        check(delimiters[0].id != delimiters[1].id)
    }
    test("频道ID遵循UTF8四字节大端长度前缀SHA256") {
        val result = ChannelCatalog.group(listOf(channel(name = "CCTV5+"))).single()
        check(result.id == "01d07c8f84214a359c4ea692dd325df96722f540bb7ff1d90b9cde72acd9a5ae")
    }
    test("线路ID为URL的完整SHA256且与频道无关") {
        val result = ChannelCatalog.group(listOf(channel(), channel(name = "其他")))
        check(result[0].routes.single().id == "8ccfd4e9d8fe47210cd08ce5941d78626a3413d493e96aa061679fa49d2f91f6")
        check(result[0].routes == result[1].routes)
    }
    test("URL不剥离查询参数") {
        val result = ChannelCatalog.group(listOf(channel(url = "https://example.com/live?k=1"), channel(url = "https://example.com/live?k=2"))).single()
        check(result.routes.size == 2 && result.routes[0].id != result.routes[1].id)
    }
    test("现有解析输出兼容归组与旧ID迁移") {
        val parsed = M3uParser.parse("""#EXTM3U
#EXTINF:-1 tvg-id="old-one" group-title="央视",CCTV5
https://example.com/a
#EXTINF:-1 tvg-id="old-two" group-title="央视",CCTV5
https://example.com/b
#EXTINF:-1 tvg-id="old-one" group-title="央视",CCTV5+
https://example.com/c
""")
        val grouped = ChannelCatalog.group(parsed.channels)
        check(grouped.size == 2 && grouped[0].routes.size == 2)
        check(grouped[0].legacyIds == parsed.channels.take(2).map { it.id }.toSet())
    }
    test("空线路终止且反复启动不复活") {
        val session = RouteSession(emptyList(), now = { 0L })
        check(session.start() == null && session.exhausted && session.attemptedCount == 0)
        repeat(5) { check(session.start() == null) }
    }
    test("默认从首线启动且启动幂等") {
        val pool = routes(3)
        val session = RouteSession(pool, now = { 0L })
        repeat(5) { check(session.start() == pool[0]) }
        check(session.attemptedCount == 1 && !session.exhausted)
    }
    test("上次成功线路优先且失效优先ID回落首条") {
        val pool = routes(3)
        check(RouteSession(pool, pool[2].id, { 0L }).start() == pool[2])
        check(RouteSession(pool, "缺失", { 0L }).start() == pool[0])
    }
    test("优先末尾线路后环形穷尽全部线路") {
        val pool = routes(4)
        val session = RouteSession(pool, pool[3].id, { 0L })
        check(exhaust(session) == listOf("route-3", "route-0", "route-1", "route-2"))
        check(session.exhausted && session.attemptedCount == 4)
    }
    test("每条仅尝试一次且冷却过期不复活会话") {
        var clock = 0L
        val session = RouteSession(routes(2), now = { clock }, cooldownMs = 1)
        check(exhaust(session) == listOf("route-0", "route-1"))
        clock = 100L
        repeat(10) { check(session.start() == null && session.onFailure("route-1") == null) }
        check(session.attemptedCount == 2)
    }
    test("默认预算最多四条不是无限扫描播放") {
        val session = RouteSession(routes(7), now = { 0L })
        check(exhaust(session) == listOf("route-0", "route-1", "route-2", "route-3"))
        check(session.exhausted && session.attemptedCount == 4)
    }
    test("自定义较小预算包含首条") {
        val session = RouteSession(routes(4), now = { 0L }, maxAutomaticRoutes = 2)
        check(exhaust(session) == listOf("route-0", "route-1") && session.attemptedCount == 2)
    }
    test("线路预算不可越过四条硬上限") {
        for (budget in listOf(-1, 0, 5)) invalid { RouteSession(routes(2), now = { 0L }, maxAutomaticRoutes = budget) }
    }
    test("拒绝负数冷却") { invalid { RouteSession(routes(1), now = { 0L }, cooldownMs = -1) } }
    test("重复线路ID不额外消耗预算") {
        val pool = routes(2)
        check(exhaust(RouteSession(listOf(pool[0], pool[0], pool[1]), now = { 0L })) == listOf("route-0", "route-1"))
    }
    test("成功确认仅作用当前线路且不会重置预算") {
        val session = RouteSession(routes(3), now = { 0L })
        session.start()
        check(!session.onSuccess("route-2") && session.lastSuccessfulId == null)
        check(session.onSuccess("route-0") && session.lastSuccessfulId == "route-0")
        check(session.attemptedCount == 1)
        check(session.onFailure("route-0")?.id == "route-1")
        check(session.onSuccess("route-1") && session.lastSuccessfulId == "route-1")
        check(session.attemptedCount == 2)
    }
    test("旧线路重复失败不跳过新线路") {
        val session = RouteSession(routes(3), now = { 0L })
        session.start()
        session.onFailure("route-0")
        repeat(5) { check(session.onFailure("route-0")?.id == "route-1") }
        check(session.attemptedCount == 2 && !session.onSuccess("route-0"))
    }
    test("未启动和耗尽后成功回调不记忆成功") {
        val session = RouteSession(routes(1), now = { 0L })
        check(!session.onSuccess("route-0"))
        exhaust(session)
        check(!session.onSuccess("route-0") && session.lastSuccessfulId == null)
    }
    test("手动选线可在首次启动之前建立新轮次") {
        val session = RouteSession(routes(3), now = { 0L })
        check(session.selectManually("route-2")?.id == "route-2")
        check(session.start()?.id == "route-2" && session.attemptedCount == 1)
    }
    test("手动选线重置预算且可覆盖所选线路冷却") {
        val session = RouteSession(routes(5), now = { 0L })
        exhaust(session)
        check(session.selectManually("route-0")?.id == "route-0")
        check(!session.exhausted && session.attemptedCount == 1)
        check(session.onFailure("route-0")?.id == "route-4")
        check(session.attemptedCount == 2)
        check(session.onFailure("route-4") == null)
    }
    test("无效手动ID不重置会话或预算") {
        val session = RouteSession(routes(2), now = { 0L })
        session.start()
        check(session.selectManually("缺失") == null)
        check(session.currentRoute?.id == "route-0" && session.attemptedCount == 1)
        exhaust(session)
        check(session.selectManually("缺失") == null && session.exhausted)
    }
    test("手动重置保留其它失败线路的冷却") {
        var clock = 0L
        val session = RouteSession(routes(3), now = { clock }, cooldownMs = 60_000)
        session.start()
        session.onFailure("route-0")
        session.onFailure("route-1")
        clock = 59_999L
        session.selectManually("route-2")
        check(session.onFailure("route-2") == null && session.attemptedCount == 1)
    }
    test("冷却期限到达后手动新轮次可尝试其它失败线") {
        var clock = 0L
        val session = RouteSession(routes(3), now = { clock }, cooldownMs = 60_000)
        exhaust(session)
        clock = 60_000L
        session.selectManually("route-2")
        check(session.onFailure("route-2")?.id == "route-0")
        check(session.onFailure("route-0")?.id == "route-1")
        check(session.onFailure("route-1") == null && session.attemptedCount == 3)
    }
    test("时钟回退保守保留失败冷却") {
        var clock = 100L
        val session = RouteSession(routes(2), now = { clock })
        exhaust(session)
        clock = 0L
        session.selectManually("route-1")
        check(session.onFailure("route-1") == null && session.attemptedCount == 1)
    }
    test("冷却属于当前对象不影响独立会话") {
        val pool = routes(2)
        exhaust(RouteSession(pool, now = { 0L }))
        check(RouteSession(pool, now = { 0L }).start() == pool[0])
    }
    test("零冷却仍保证每轮每条只选一次") {
        val session = RouteSession(routes(3), now = { 0L }, cooldownMs = 0)
        check(exhaust(session).size == 3)
        session.selectManually("route-2")
        check(exhaust(session) == listOf("route-2", "route-0", "route-1"))
    }
    test("从任意优先索引出发预算与无重复性质") {
        for (count in 1..9) for (preferred in 0 until count) for (budget in 1..4) {
            val pool = routes(count)
            val session = RouteSession(pool, pool[preferred].id, { 0L }, budget)
            val seen = exhaust(session)
            check(seen.size == minOf(count, budget))
            check(seen.distinct() == seen && seen.first() == pool[preferred].id)
            check(seen == (0 until minOf(count, budget)).map { pool[(preferred + it) % count].id })
            check(session.exhausted && session.start() == null)
        }
    }
    println("全部 $passed 项线路测试通过")
}
