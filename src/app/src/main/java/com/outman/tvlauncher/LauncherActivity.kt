package com.outman.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.*
import com.outman.tvlauncher.core.ChannelCatalog
import com.outman.tvlauncher.core.LiveChannel
import com.outman.tvlauncher.core.ChannelRoute
import com.outman.tvlauncher.core.RouteSession
import android.os.SystemClock
import com.outman.tvlauncher.core.EntryPolicy.EntryPage
import com.outman.tvlauncher.core.EntryPolicy
import com.outman.tvlauncher.core.ExternalNavigation
import com.outman.tvlauncher.core.PlaybackPhase
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

@androidx.media3.common.util.UnstableApi
class LauncherActivity : Activity() {
    private val ink = Color.rgb(28, 47, 51)
    private val muted = Color.rgb(100, 121, 124)
    private val accent = Color.rgb(9, 116, 100)
    private val paper = Color.rgb(241, 246, 244)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: ChannelStore
    private lateinit var playback: PlaybackCoordinator
    private lateinit var appRepo: AppRepository
    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var previewSlot: FrameLayout
    private lateinit var video: AspectVideoFrame
    private lateinit var surface: SurfaceView
    private lateinit var videoLabel: TextView
    private lateinit var emptyHint: TextView
    private lateinit var status: TextView
    private lateinit var currentTitle: TextView
    private lateinit var groupList: LinearLayout
    private lateinit var channelList: LinearLayout
    private lateinit var appsRow: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var appSection: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var categoryScroll: ScrollView
    private lateinit var channelScroll: ScrollView
    private var dialog: AlertDialog? = null
    private var channels: List<LiveChannel> = emptyList()
    private var selected: LiveChannel? = null
    private var routeSession: RouteSession? = null
    private var activeRoute: ChannelRoute? = null
    private var selectedGroup = "全部频道"
    private var fullscreen = false
    private var started = false
    private var goingExternal = false
    private var resumeAfterExternal = false
    private var needsHomeOnResume = false
    private var lastApp: String? = null
    private var importGeneration = 0
    private var importBusy = false
    private var playbackPhase = PlaybackPhase.STOPPED
    private val hideInfo = Runnable { if (fullscreen && playbackPhase == PlaybackPhase.PLAYING) videoLabel.visibility = View.GONE }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        store = ChannelStore(this)
        appRepo = AppRepository(this)
        channels = ChannelCatalog.group(store.load())
        val recent = saved?.getString("channel") ?: store.preferences.getString("lastSuccessful", null)
        selected = channels.firstOrNull { it.id == recent || recent in it.legacyIds } ?: channels.firstOrNull()
        playback = PlaybackCoordinator(this, { updateStatus(it) }, {
            selected?.let { channel ->
                val edit = store.preferences.edit().putString("lastSuccessful", channel.id)
                activeRoute?.let {
                    if (routeSession?.onSuccess(it.id) == true) edit.putString("route.${channel.id}", it.id)
                }
                edit.apply()
            }
            emptyHint.visibility = View.GONE
        }, { ratio -> if(::video.isInitialized) video.videoRatio=ratio }, { phase ->
            playbackPhase=phase
            if(started && selected!=null && phase.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }, { _, allowAlternate -> onRouteFailure(allowAlternate) })
        buildUi()
        val boot = runCatching { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
        val previous = store.preferences.getInt("lastBoot", -1)
        val sameProcessRecreation = saved?.getString("processToken") == PROCESS_TOKEN
        val isHome = saved == null && intent.hasCategory(Intent.CATEGORY_HOME)
        val page = EntryPolicy.decide(channels.isNotEmpty(), isHome, boot, previous,
            store.preferences.getBoolean("autoPlay", true), saved?.getBoolean("fullscreen"), sameProcessRecreation)
        if (boot >= 0) store.preferences.edit().putInt("lastBoot", boot).apply()
        setFullscreen(page == EntryPage.FULLSCREEN)
        Log.i("TVLauncher", "entry=$page boot=$boot previous=$previous home=$isHome restored=${saved != null}")
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 15f, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
    }
    private fun background(color: Int = Color.WHITE, border: Int = Color.TRANSPARENT, width: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(9).toFloat(); if (width > 0) setStroke(dp(width), border)
    }
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        id = View.generateViewId(); text = label; textSize = 14f; isAllCaps = false
        setTextColor(ink); isFocusable = true; isFocusableInTouchMode = true; minHeight = 0; minimumHeight = 0
        setPadding(dp(10), dp(6), dp(10), dp(6)); gravity = Gravity.CENTER
        background = background()
        setOnFocusChangeListener { _, focused ->
            background = background(if (focused) Color.rgb(211,239,228) else Color.WHITE, if(focused) accent else Color.rgb(224,233,229), if(focused) 2 else 1)
            elevation = if (focused) dp(3).toFloat() else 0f
        }
        setOnClickListener { action() }
        setOnTouchListener { view,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { view.requestFocus(); true }
                MotionEvent.ACTION_UP -> { view.performClick(); true }
                else -> true
            }
        }
    }
    private fun itemLayout(height: Int = 39) = LinearLayout.LayoutParams(-1, dp(height)).apply { bottomMargin = dp(7) }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(paper) }
        home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24),dp(19),dp(24),dp(15)) }
        header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logo.addView(text("OUTMAN / TV", 24f).apply { typeface = Typeface.DEFAULT_BOLD })
        logo.addView(text("LIVE AT HOME   ·   只看你想看的", 10f, muted).apply { setPadding(0,dp(4),0,0) })
        header.addView(logo, LinearLayout.LayoutParams(0,dp(52),1f))
        header.addView(button("导入频道") { showImportOptions() }, LinearLayout.LayoutParams(dp(98),dp(36)).apply { rightMargin=dp(9) })
        header.addView(button("设置") { showSettings() }, LinearLayout.LayoutParams(dp(70),dp(36)))
        home.addView(header)
        val section = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(10),0,dp(10)) }
        currentTitle = text("现在观看",18f).apply { typeface=Typeface.DEFAULT_BOLD }
        section.addView(currentTitle,LinearLayout.LayoutParams(0,-2,1f))
        section.addView(text("确认选台  /  中央确认全屏  /  返回首页",11f,muted))
        home.addView(section)
        body = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        groupList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        categoryScroll=ScrollView(this).apply { isFillViewport=false; isFocusable=false; addView(groupList) }
        body.addView(categoryScroll,LinearLayout.LayoutParams(dp(125),-1))
        previewSlot=FrameLayout(this)
        body.addView(previewSlot,LinearLayout.LayoutParams(0,-1,1f).apply { setMargins(dp(12),0,dp(12),0) })
        channelList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        channelScroll=ScrollView(this).apply { isFocusable=false; addView(channelList) }
        body.addView(channelScroll,LinearLayout.LayoutParams(dp(176),-1))
        home.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        status=text("请导入自己的 M3U 频道文件",12f,muted).apply { setPadding(0,dp(8),0,dp(5)) }
        home.addView(status,LinearLayout.LayoutParams(-1,dp(31)))
        appSection=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        appSection.addView(text("我的应用",15f).apply { typeface=Typeface.DEFAULT_BOLD; setPadding(0,0,0,dp(8)) })
        appsRow=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        appSection.addView(HorizontalScrollView(this).apply { isFocusable=false; isHorizontalScrollBarEnabled=false; addView(appsRow) },LinearLayout.LayoutParams(-1,dp(74)))
        home.addView(appSection)
        root.addView(home,FrameLayout.LayoutParams(-1,-1))
        video=AspectVideoFrame(this).apply {
            id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true; isClickable=true
            descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(3),dp(3),dp(3),dp(3))
            background=background(Color.rgb(16,26,25),accent,2)
            setOnClickListener { if(selected==null) showImportOptions() else setFullscreen(!fullscreen) }
            setOnFocusChangeListener { _, focused -> background=background(Color.rgb(16,26,25),if(focused) accent else Color.rgb(90,110,104),if(focused) 3 else 1) }
            setOnKeyListener { _, key, event ->
                if (fullscreen && event.action==KeyEvent.ACTION_DOWN && key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)) {
                    showFullscreenInfo(); true
                } else false
            }
        }
        surface=SurfaceView(this).apply { isFocusable=false }
        video.addView(surface,FrameLayout.LayoutParams(-1,-1))
        emptyHint=text("你的频道，你的首页\n\n导入 M3U 后，即可开始观看\n按确认打开导入",18f,ink).apply { gravity=Gravity.CENTER; setBackgroundColor(Color.rgb(225,237,231)); setPadding(dp(16),0,dp(16),0) }
        video.addView(emptyHint,FrameLayout.LayoutParams(-1,-1))
        videoLabel=text("尚未选择频道",13f,Color.WHITE).apply { setBackgroundColor(0xB0223230.toInt()); setPadding(dp(12),dp(7),dp(12),dp(7)) }
        video.addView(videoLabel,FrameLayout.LayoutParams(-1,dp(36),Gravity.BOTTOM))
        previewSlot.addView(video,FrameLayout.LayoutParams(-1,-1))
        setContentView(root)
        refreshGroups(); refreshChannels(); refreshApps(); updateTitle(); video.requestFocus()
    }

    private fun refreshGroups() {
        groupList.removeAllViews()
        (listOf("全部频道") + channels.map { it.group }.distinct()).forEach { group ->
            val b=button(group) { selectedGroup=group; refreshChannels(); video.nextFocusLeftId=itGroupId(group) }
            b.tag=group
            groupList.addView(b,itemLayout())
            b.nextFocusRightId=video.id
        }
        if(groupList.childCount>0) video.nextFocusLeftId=groupList.getChildAt(0).id
    }
    private fun itGroupId(group:String):Int = (0 until groupList.childCount).map {groupList.getChildAt(it)}.firstOrNull {it.tag==group}?.id ?: View.NO_ID
    private fun refreshChannels() {
        channelList.removeAllViews()
        val visible=channels.filter { selectedGroup=="全部频道" || it.group==selectedGroup }
        if(visible.isEmpty()) channelList.addView(button("导入你的频道") { showImportOptions() },itemLayout(48))
        visible.forEach { channel ->
            val b=button((if(selected?.id==channel.id) "• " else "")+channel.name) { selectChannel(channel) }
            b.tag=channel.id; b.gravity=Gravity.CENTER_VERTICAL or Gravity.START
            channelList.addView(b,itemLayout()); b.nextFocusLeftId=video.id
        }
        if(channelList.childCount>0) video.nextFocusRightId=channelList.getChildAt(0).id
    }
    private fun updateTitle() {
        currentTitle.text=selected?.name ?: "现在观看"
        videoLabel.text=(selected?.name ?: "尚未选择频道")+"  ·  "+if(fullscreen) "返回回首页" else "按确认全屏"
        video.contentDescription="中央预览："+(selected?.name ?: "导入频道")
    }
    private fun selectChannel(channel: LiveChannel) {
        selected=channel; updateTitle(); refreshChannels()
        emptyHint.text="正在连接 ${channel.name}"; emptyHint.visibility=View.VISIBLE
        if(started) resumePlayback()
        channelList.findViewWithTag<View>(channel.id)?.requestFocus()
        Log.i("TVLauncher","channel_selected id=${channel.id.take(12)}")
    }
    private fun updateStatus(message:String) {
        if (!::status.isInitialized) return
        status.text=message
        if(playbackPhase!=PlaybackPhase.PLAYING && playbackPhase!=PlaybackPhase.STOPPED) {
            videoLabel.text=(selected?.name ?: "直播")+"  ·  "+message
            videoLabel.visibility=View.VISIBLE
        } else if(playbackPhase==PlaybackPhase.PLAYING) {
            updateTitle()
            if(fullscreen) showFullscreenInfo()
        }
    }
    private fun setFullscreen(value:Boolean) {
        fullscreen=value
        if(value) {
            // 同一 Surface 保留在原父容器：隐藏其余区域、取消首页边距，避免第二播放器与重绑黑帧。
            header.visibility=View.GONE
            home.getChildAt(1).visibility=View.GONE
            categoryScroll.visibility=View.GONE; channelScroll.visibility=View.GONE
            status.visibility=View.GONE; appSection.visibility=View.GONE
            home.setPadding(0,0,0,0)
            (previewSlot.layoutParams as LinearLayout.LayoutParams).setMargins(0,0,0,0)
            video.setPadding(0,0,0,0)
            showFullscreenInfo()
        } else {
            header.visibility=View.VISIBLE; home.getChildAt(1).visibility=View.VISIBLE
            categoryScroll.visibility=View.VISIBLE; channelScroll.visibility=View.VISIBLE
            status.visibility=View.VISIBLE; appSection.visibility=View.VISIBLE
            home.setPadding(dp(24),dp(19),dp(24),dp(15))
            (previewSlot.layoutParams as LinearLayout.LayoutParams).setMargins(dp(12),0,dp(12),0)
            video.setPadding(dp(3),dp(3),dp(3),dp(3)); handler.removeCallbacks(hideInfo); videoLabel.visibility=View.VISIBLE
        }
        updateTitle(); previewSlot.requestLayout(); video.requestFocus()
        Log.i("TVLauncher","page=${if(value) "FULLSCREEN" else "HOME"} instances=${playback.instanceCount}")
    }
    private fun showFullscreenInfo() {
        handler.removeCallbacks(hideInfo); videoLabel.visibility=View.VISIBLE
        handler.postDelayed(hideInfo,3500)
    }
    private fun refreshApps() {
        appsRow.removeAllViews()
        val apps=runCatching {appRepo.load()}.getOrDefault(emptyList())
        apps.forEach { app ->
            val b=button(app.label) {
                try { lastApp=app.component.flattenToString(); goingExternal=true; playback.stop(); appRepo.launch(app) }
                catch (_:Exception) { goingExternal=false; toast("应用无法启动，可能已卸载或受系统限制"); resumePlayback(); refreshApps() }
            }.apply {
                tag=app.component.flattenToString(); textSize=12f; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
                app.icon?.let { it.setBounds(0,0,dp(28),dp(28)); setCompoundDrawables(null,it,null,null); compoundDrawablePadding=dp(3) }
            }
            appsRow.addView(b,LinearLayout.LayoutParams(dp(112),dp(68)).apply {rightMargin=dp(9)})
            b.nextFocusUpId=video.id
        }
        val settings=button("系统设置") { openExternal(Intent(Settings.ACTION_SETTINGS)) }
        appsRow.addView(settings,LinearLayout.LayoutParams(dp(112),dp(68))); settings.nextFocusUpId=video.id
        video.nextFocusDownId=appsRow.getChildAt(0).id
        video.nextFocusUpId=header.getChildAt(1).id
    }

    private fun showImportOptions() {
        showDialog(AlertDialog.Builder(this).setTitle("导入频道")
            .setMessage("使用你有权观看的 M3U 文件或订阅地址。只保存到本机，不上传。HTTP 地址可能被网络窃听；优先使用 HTTPS。")
            .setPositiveButton("本地文件") { _,_ -> pickFile() }
            .setNeutralButton("订阅地址") { _,_ -> showUrlInput() }
            .setNegativeButton("取消",null).create())
    }
    private fun pickFile() {
        try {
            goingExternal=true
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),100)
        } catch(_:Exception) { goingExternal=false; toast("系统未提供文件选择器，请使用订阅地址导入") }
    }
    private fun showUrlInput() {
        val edit=EditText(this).apply { hint="https://example.com/channels.m3u"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; isSingleLine=true; setPadding(dp(20),dp(12),dp(20),dp(12)) }
        val d=AlertDialog.Builder(this).setTitle("M3U 订阅地址").setView(edit).setPositiveButton("导入",null).setNegativeButton("取消",null).create()
        showDialog(d)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val raw=edit.text.toString().trim()
            val uri=runCatching { URL(raw) }.getOrNull()
            if(uri==null || uri.protocol !in listOf("http","https") || uri.host.isBlank()) {edit.error="请输入有效的 HTTP/HTTPS 地址"; return@setOnClickListener}
            d.dismiss(); importRemote(raw)
        }
    }
    private fun importRemote(url:String) {
        importAsync {
            val connection=URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout=8000; connection.readTimeout=8000; connection.instanceFollowRedirects=true
            try {
                require(connection.responseCode in 200..299) {"订阅响应失败"}
                connection.inputStream.use {store.prepare(it)}
            } finally {connection.disconnect()}
        }
    }
    private fun importAsync(action: () -> ChannelStore.PreparedImport) {
        if(importBusy) {toast("正在导入，请稍候");return}
        importBusy=true; val token=++importGeneration; status.text="正在导入频道…"
        worker.execute {
            val result=runCatching(action)
            runOnUiThread {
                if(isDestroyed || token!=importGeneration) return@runOnUiThread
                importBusy=false
                val committed=result.mapCatching { prepared -> store.commit(prepared); prepared.result }
                committed.onSuccess {parsed ->
                    channels=ChannelCatalog.group(parsed.channels); selectedGroup="全部频道"
                    val id=store.preferences.getString("lastSuccessful",null)
                    selected=channels.firstOrNull {it.id==id || id in it.legacyIds} ?: channels.first()
                    refreshGroups(); refreshChannels(); updateTitle(); setFullscreen(false)
                    toast("已导入 ${channels.size} 个频道 / ${channels.sumOf { it.routes.size }} 条线路，跳过 ${parsed.skipped} 项")
                    Log.i("TVLauncher","import_success channels=${channels.size} skipped=${parsed.skipped}")
                    if(started) resumePlayback()
                }.onFailure {status.text="导入失败：文件无有效频道、超限或网络不可用；已保留原频道";toast("导入失败，已保留原频道");Log.w("TVLauncher","import_failed type=${it.javaClass.simpleName}")}
            }
        }
    }
    @Deprecated("Platform document picker compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==100 && resultCode==RESULT_OK) data?.data?.let {uri ->
            importAsync { contentResolver.openInputStream(uri)?.use {store.prepare(it)} ?: error("无法读取文件") }
        }
    }
    private fun showSettings() {
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(8),dp(22),dp(8))}
        box.addView(CheckBox(this).apply {
            text="设备新启动后直接全屏播放"; isChecked=store.preferences.getBoolean("autoPlay",true)
            setOnCheckedChangeListener {_,v -> store.preferences.edit().putBoolean("autoPlay",v).apply()}
        })
        box.addView(text("需要系统允许默认 HOME。首次无频道先导入；普通 HOME 返回仍进首页。",12f,muted))
        box.addView(button("设为默认桌面") {dialog?.dismiss();requestHome()},itemLayout(44))
        box.addView(button("选择播放线路") {dialog?.dismiss();showRoutes()},itemLayout(44))
        box.addView(button("重新连接当前频道") {dialog?.dismiss(); if(started && selected!=null) {resumePlayback();toast("正在重新连接")}},itemLayout(44))
        box.addView(button("导入 / 更新频道") {dialog?.dismiss();showImportOptions()},itemLayout(44))
        box.addView(button("打开系统设置 / 恢复其他桌面") {dialog?.dismiss();openExternal(Intent(Settings.ACTION_SETTINGS))},itemLayout(44))
        box.addView(text("Outman TV 0.2.0 · 本机存储 · 单路直播\n若原厂强制桌面，本应用不会擅自禁用原桌面或修改系统。",11f,muted))
        showDialog(AlertDialog.Builder(this).setTitle("设置").setView(box).setNegativeButton("返回",null).create())
    }
    private fun requestHome() {
        val resolved=runCatching {
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()
        if(resolved?.activityInfo?.packageName==packageName) {toast("已是默认桌面");return}
        val attempts=mutableListOf<() -> Unit>()
        if(Build.VERSION.SDK_INT>=29) {
            attempts.add {
                val role=getSystemService(RoleManager::class.java)
                check(role!=null && role.isRoleAvailable(RoleManager.ROLE_HOME) && !role.isRoleHeld(RoleManager.ROLE_HOME))
                startActivity(role.createRequestRoleIntent(RoleManager.ROLE_HOME))
            }
        }
        attempts.add {startActivity(Intent(Settings.ACTION_HOME_SETTINGS))}
        attempts.add {startActivity(Intent(Settings.ACTION_SETTINGS))}
        launchExternal(attempts)
    }
    private fun openExternal(intent:Intent) = launchExternal(listOf({startActivity(intent)}))
    private fun launchExternal(attempts:List<() -> Unit>) {
        goingExternal=true
        playback.stop()
        if(!ExternalNavigation.launchFirst(attempts)) {
            goingExternal=false
            toast("系统未提供此入口")
            if(started) resumePlayback()
        }
    }
    private fun showDialog(value:AlertDialog) { dialog?.dismiss();dialog=value;value.show(); value.setOnDismissListener { if(dialog===value) dialog=null } }
    private fun toast(value:String)=Toast.makeText(this,value,Toast.LENGTH_LONG).show()

    private fun resumePlayback() {
        val channel = selected ?: return
        routeSession = RouteSession(channel.routes,
            store.preferences.getString("route.${channel.id}", null),
            now = { android.os.SystemClock.elapsedRealtime() })
        val route = routeSession?.start()
        if (route == null) {
            activeRoute = null
            playback.stop()
            updateStatus("当前频道没有可用线路")
        } else playRoute(route)
    }
    private fun playRoute(route: ChannelRoute) {
        activeRoute = route
        emptyHint.text="正在连接 ${selected?.name ?: "频道"}"
        emptyHint.visibility=View.VISIBLE
        playback.play(route.url,surface)
    }
    private fun onRouteFailure(allowAlternate: Boolean) {
        if (!started || !allowAlternate) return
        val failed = activeRoute ?: return
        val next = routeSession?.onFailure(failed.id)
        if (next == null) {
            activeRoute = null
            emptyHint.text="当前轮次线路已尝试完毕\n可在设置中手选线路或重新连接"
            emptyHint.visibility=View.VISIBLE
            updateStatus("线路尝试已停止，请手动重连或选择线路")
        } else {
            Log.i("TVLauncher", "route_fallback attempt=${routeSession?.attemptedCount}")
            playRoute(next)
        }
    }
    private fun showRoutes() {
        val channel = selected ?: run { toast("请先导入频道"); return }
        if (channel.routes.isEmpty()) { toast("当前频道没有线路"); return }
        val labels = channel.routes.mapIndexed { index, route ->
            "线路 ${index + 1}" + if(route.id == activeRoute?.id) " · 当前" else ""
        }.toTypedArray()
        val current = channel.routes.indexOfFirst { it.id == activeRoute?.id }
        showDialog(AlertDialog.Builder(this).setTitle("${channel.name} · 播放线路")
            .setSingleChoiceItems(labels, current) { choice, index ->
                choice.dismiss()
                if (selected?.id != channel.id || !started) return@setSingleChoiceItems
                val session = routeSession ?: RouteSession(channel.routes,
                    now = { android.os.SystemClock.elapsedRealtime() }).also { routeSession = it }
                session.selectManually(channel.routes[index].id)?.let(::playRoute)
            }.setNegativeButton("返回", null).create())
    }
    override fun onStart() {super.onStart();started=true;resumePlayback();resumeAfterExternal=false}
    override fun onPause() {
        if(goingExternal) {playback.stop();resumeAfterExternal=true}
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        if(resumeAfterExternal) {resumePlayback();resumeAfterExternal=false}
        val returning = needsHomeOnResume || goingExternal
        if(returning) {setFullscreen(false);needsHomeOnResume=false;goingExternal=false}
        if(::appsRow.isInitialized) {
            val appFocus = if(returning) lastApp else currentFocus?.tag as? String
            refreshApps()
            if(!fullscreen && appFocus!=null) {
                appsRow.post { if(!fullscreen) appsRow.findViewWithTag<View>(appFocus)?.requestFocus() }
            }
        }
    }
    override fun onStop() {
        started=false;playback.stop();handler.removeCallbacks(hideInfo);window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(!isChangingConfigurations) needsHomeOnResume=true
        super.onStop()
    }
    override fun onDestroy() {dialog?.dismiss();playback.release();handler.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy()}
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent);dialog?.dismiss();setFullscreen(false)
        lastApp?.let { key -> appsRow.post { if(!fullscreen) appsRow.findViewWithTag<View>(key)?.requestFocus() } }
        Log.i("TVLauncher","new_intent_home=${intent.hasCategory(Intent.CATEGORY_HOME)}")
    }
    override fun onSaveInstanceState(out:Bundle) {out.putBoolean("fullscreen",fullscreen);out.putString("channel",selected?.id);out.putString("processToken",PROCESS_TOKEN);super.onSaveInstanceState(out)}
    companion object { private val PROCESS_TOKEN = java.util.UUID.randomUUID().toString() }
    @Deprecated("TV back key API 24+")
    override fun onBackPressed() {
        if(fullscreen) setFullscreen(false) else {video.requestFocus();toast("已在首页，确认中央画面进入全屏")}
    }
}
