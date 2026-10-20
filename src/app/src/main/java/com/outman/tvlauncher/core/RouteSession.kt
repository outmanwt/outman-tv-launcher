package com.outman.tvlauncher.core

/** 单线程会话策略；调用方负责过滤旧播放代际，同一线路内部重试不在此处计数。 */
class RouteSession(
    routes: List<ChannelRoute>,
    preferredId: String? = null,
    private val now: () -> Long,
    private val maxAutomaticRoutes: Int = 4,
    private val cooldownMs: Long = 60_000
) {
    private val routes = routes.distinctBy { it.id }
    private val attempted = mutableSetOf<String>()
    private val failedAt = mutableMapOf<String, Long>()
    private var nextIndex = this.routes.indexOfFirst { it.id == preferredId }.coerceAtLeast(0)
    private var started = false

    var currentRoute: ChannelRoute? = null
        private set
    var lastSuccessfulId: String? = null
        private set
    var exhausted: Boolean = false
        private set
    val attemptedCount: Int get() = attempted.size

    init {
        require(maxAutomaticRoutes in 1..4) { "每轮线路预算必须介于 1 到 4" }
        require(cooldownMs >= 0) { "冷却时间不能为负数" }
    }

    fun start(): ChannelRoute? {
        if (started) return currentRoute
        started = true
        return advance()
    }

    fun onFailure(routeId: String): ChannelRoute? {
        if (currentRoute?.id != routeId) return currentRoute
        failedAt[routeId] = now()
        currentRoute = null
        return advance()
    }

    fun onSuccess(routeId: String): Boolean {
        if (currentRoute?.id != routeId) return false
        lastSuccessfulId = routeId
        failedAt.remove(routeId)
        return true
    }

    /** 只由用户明确选线触发；手选线路计入新预算，允许覆盖它自己的冷却。 */
    fun selectManually(routeId: String): ChannelRoute? {
        val index = routes.indexOfFirst { it.id == routeId }
        if (index < 0) return null
        attempted.clear()
        exhausted = false
        started = true
        nextIndex = (index + 1) % routes.size
        return select(routes[index])
    }

    private fun advance(): ChannelRoute? {
        if (attempted.size < maxAutomaticRoutes) {
            val timestamp = now()
            repeat(routes.size) {
                val route = routes[nextIndex]
                nextIndex = (nextIndex + 1) % routes.size
                val failed = failedAt[route.id]
                val coolingDown = failed != null && timestamp - failed < cooldownMs
                if (route.id !in attempted && !coolingDown) return select(route)
            }
        }
        currentRoute = null
        exhausted = true
        return null
    }

    private fun select(route: ChannelRoute): ChannelRoute {
        attempted.add(route.id)
        currentRoute = route
        return route
    }
}
