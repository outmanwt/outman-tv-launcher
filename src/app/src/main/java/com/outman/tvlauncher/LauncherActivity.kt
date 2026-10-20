package com.outman.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
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

/**
 * 首页版式：深蓝渐变背景 + 4 列网格（左 3 窄频道卡 / 中跨 2 列大预览卡 /
 * 右 2 频道卡 / 底部 4 张彩色应用大卡），白色唯一前景色，白色焦点描边，Tab 两级焦点态。
 * 度量换算基准：1920px 截图 = 960dp，即 1dp = 2px。
 */
@androidx.media3.common.util.UnstableApi
class LauncherActivity : Activity() {
    // 深蓝色板（按 1920×1080 版式定标）
    private val bgTop = Color.rgb(6, 40, 98)
    private val bgBottom = Color.rgb(38, 104, 213)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: ChannelStore
    private lateinit var playback: PlaybackCoordinator
    private lateinit var appRepo: AppRepository
    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var topbar: LinearLayout
    private lateinit var status: TextView
    private lateinit var clock: TextView
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var tabRow: LinearLayout
    private lateinit var contentGrid: LinearLayout
    private lateinit var leftCol: LinearLayout
    private lateinit var rightCol: LinearLayout
    private lateinit var previewSlot: FrameLayout
    private lateinit var video: AspectVideoFrame
    private lateinit var surface: SurfaceView
    private lateinit var videoLabel: TextView
    private lateinit var tagPill: TextView
    private lateinit var emptyHint: TextView
    private lateinit var appRow: LinearLayout
    private var dialog: AlertDialog? = null
    private var channels: List<LiveChannel> = emptyList()
    private var selected: LiveChannel? = null
    private var routeSession: RouteSession? = null
    private var activeRoute: ChannelRoute? = null
    private var selectedGroup = "全部频道"
    private var selectedPillId = View.NO_ID
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
    private val clockTick: Runnable = object : Runnable {
        override fun run() { updateClock(); handler.postDelayed(this, 30000) }
    }

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
    private fun text(value: String, size: Float = 15f, color: Int = Color.WHITE) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
    }
    private fun strokeDrawable(radiusDp: Int, widthDp: Int = 2) = GradientDrawable().apply {
        setStroke(dp(widthDp), Color.WHITE); cornerRadius = dp(radiusDp).toFloat()
    }
    private fun solid(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(bgTop, bgBottom))
        }
        home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(14), dp(48), 0) }

        // ---- 顶栏：标题 / 状态 / 时间 / 导入 / 设置 ----
        topbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        topbar.addView(text("OUTMAN TV", 17f).apply { typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(-2, dp(26)).apply { rightMargin = dp(18) })
        status = text("请导入自己的 M3U 频道文件", 12f, 0xB3FFFFFF.toInt()).apply { maxLines = 1 }
        topbar.addView(status, LinearLayout.LayoutParams(0, dp(26), 1f).apply { rightMargin = dp(18) })
        clock = text("", 13f, 0xE6FFFFFF.toInt())
        topbar.addView(clock, LinearLayout.LayoutParams(-2, dp(26)).apply { rightMargin = dp(18) })
        topbar.addView(topAction("导入") { showImportOptions() },
            LinearLayout.LayoutParams(dp(58), dp(26)).apply { rightMargin = dp(10) })
        topbar.addView(topAction("设置") { showSettings() }, LinearLayout.LayoutParams(dp(58), dp(26)))
        home.addView(topbar)

        // ---- Tab 行：分组胶囊，两级焦点态 ----
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScroll = HorizontalScrollView(this).apply {
            isFocusable = false; isHorizontalScrollBarEnabled = false; addView(tabRow)
        }
        home.addView(tabScroll, LinearLayout.LayoutParams(-1, dp(37)).apply { topMargin = dp(6) })

        // ---- 主内容区：左 3 窄卡 / 中大卡跨 2 列 / 右 2 卡，总高 241dp ----
        contentGrid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        leftCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contentGrid.addView(leftCol, LinearLayout.LayoutParams(dp(197), -1))
        previewSlot = FrameLayout(this)
        contentGrid.addView(previewSlot, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(25), 0, dp(25), 0) })
        rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contentGrid.addView(rightCol, LinearLayout.LayoutParams(dp(197), -1))
        home.addView(contentGrid, LinearLayout.LayoutParams(-1, dp(241)).apply { topMargin = dp(32) })

        // ---- 底栏应用区：4 张彩色大卡 + 卡下名称 ----
        appRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        home.addView(appRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(25) })

        root.addView(home, FrameLayout.LayoutParams(-1, -1))

        // ---- 大预览卡：视频满幅 + 底部信息条 + 左上角台名药丸 ----
        video = AspectVideoFrame(this).apply {
            id = View.generateViewId(); isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(9).toFloat())
                }
            }
            setOnClickListener { if (selected == null) showImportOptions() else setFullscreen(!fullscreen) }
            setOnFocusChangeListener { _, focused -> foreground = if (focused && !fullscreen) strokeDrawable(9) else null }
            setOnKeyListener { _, key, event ->
                if (fullscreen && event.action == KeyEvent.ACTION_DOWN && key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)) {
                    showFullscreenInfo(); true
                } else false
            }
        }
        surface = SurfaceView(this).apply { isFocusable = false }
        video.addView(surface, FrameLayout.LayoutParams(-1, -1))
        emptyHint = text("你的频道，你的首页\n\n导入 M3U 后，即可开始观看\n按确认打开导入", 18f, Color.WHITE).apply {
            gravity = Gravity.CENTER; setLineSpacing(dp(4).toFloat(), 1f)
            background = solid(0xC40E1B33.toInt(), 9); setPadding(dp(16), 0, dp(16), 0)
        }
        video.addView(emptyHint, FrameLayout.LayoutParams(-1, -1))
        tagPill = text("", 12f, Color.WHITE).apply {
            background = solid(0x8C000000.toInt(), 9); setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        video.addView(tagPill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })
        videoLabel = text("尚未选择频道", 13f, Color.WHITE).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF2000000.toInt(), 0x00000000))
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        video.addView(videoLabel, FrameLayout.LayoutParams(-1, dp(58), Gravity.BOTTOM))
        previewSlot.addView(video, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        refreshGroups(); refreshChannels(); refreshApps(); updateTitle(); updateClock()
        handler.postDelayed(clockTick, 30000)
        video.requestFocus()
    }

    private fun topAction(label: String, action: () -> Unit): TextView = text(label, 13f, Color.WHITE).apply {
        id = View.generateViewId(); isFocusable = true; isFocusableInTouchMode = true
        gravity = Gravity.CENTER; background = null
        setOnClickListener { action() }
        setOnFocusChangeListener { _, focused ->
            background = if (focused) strokeDrawable(13) else null
        }
    }

    private fun darkButton(label: String, action: () -> Unit): Button = Button(this).apply {
        id = View.generateViewId(); text = label; textSize = 15f; isAllCaps = false
        setTextColor(Color.WHITE); isFocusable = true; isFocusableInTouchMode = true
        minHeight = 0; minimumHeight = 0
        setPadding(dp(12), dp(9), dp(12), dp(9)); gravity = Gravity.CENTER
        background = darkButtonBg(false)
        setOnFocusChangeListener { _, focused -> background = darkButtonBg(focused) }
        setOnClickListener { action() }
    }
    private fun darkButtonBg(focused: Boolean) = GradientDrawable().apply {
        setColor(if (focused) 0xFF26538C.toInt() else 0xFF11284A.toInt())
        cornerRadius = dp(9).toFloat()
        if (focused) setStroke(dp(2), Color.WHITE)
    }

    // ============ Tab（分组胶囊）两级焦点态 ============

    private fun refreshGroups() {
        tabRow.removeAllViews()
        (listOf("全部频道") + channels.map { it.group }.distinct()).forEach { group ->
            val box = LinearLayout(this).apply {
                id = View.generateViewId(); orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER; isFocusable = true; isFocusableInTouchMode = true
                setPadding(dp(16), 0, dp(16), 0); tag = group
                setOnClickListener { pickGroup(group) }
                setOnFocusChangeListener { _, _ -> restyleTabs() }
                nextFocusDownId = if (::video.isInitialized) video.id else View.NO_ID
            }
            box.addView(text(group, 16f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            box.addView(View(this@LauncherActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(3)).apply { topMargin = dp(2) }
                setBackgroundColor(Color.WHITE)
                visibility = View.INVISIBLE
            })
            tabRow.addView(box, LinearLayout.LayoutParams(-2, dp(37)).apply { rightMargin = dp(14) })
        }
        restyleTabs()
    }

    private fun restyleTabs() {
        selectedPillId = View.NO_ID
        var tabHasFocus = false
        for (i in 0 until tabRow.childCount) {
            val box = tabRow.getChildAt(i)
            if (box.hasFocus()) tabHasFocus = true
            if (box.tag == selectedGroup) selectedPillId = box.id
        }
        for (i in 0 until tabRow.childCount) {
            val box = tabRow.getChildAt(i) as LinearLayout
            val isSelected = box.tag == selectedGroup
            val label = box.getChildAt(0) as TextView
            val underline = box.getChildAt(1)
            when {
                isSelected && tabHasFocus -> {
                    box.background = solid(Color.WHITE, 18)
                    label.setTextColor(bgTop)
                    underline.visibility = View.INVISIBLE
                }
                isSelected -> {
                    box.background = null
                    label.setTextColor(Color.WHITE)
                    underline.visibility = View.VISIBLE
                }
                box.hasFocus() -> {
                    box.background = strokeDrawable(18)
                    label.setTextColor(Color.WHITE)
                    underline.visibility = View.INVISIBLE
                }
                else -> {
                    box.background = null
                    label.setTextColor(0xB3FFFFFF.toInt())
                    underline.visibility = View.INVISIBLE
                }
            }
        }
        if (selectedPillId != View.NO_ID && ::video.isInitialized) video.nextFocusUpId = selectedPillId
    }

    private fun pickGroup(group: String) {
        if (group == selectedGroup) { video.requestFocus(); return }
        selectedGroup = group
        val list = visibleChannels()
        if (list.isNotEmpty()) selected = list.first()
        refreshChannels(); updateTitle()
        if (started) resumePlayback()
        video.requestFocus()
    }

    // ============ 主区：频道窗口（当前 ±3 / +2） ============

    private fun visibleChannels(): List<LiveChannel> =
        channels.filter { selectedGroup == "全部频道" || it.group == selectedGroup }

    private fun channelHue(channel: LiveChannel): Int {
        val raw = channel.id.hashCode()
        return ((raw % 360) + 360) % 360
    }

    private fun channelCard(channel: LiveChannel, heightDp: Int): FrameLayout = FrameLayout(this).apply {
        id = View.generateViewId(); isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        tag = channel.id
        val hue = channelHue(channel)
        val top = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.42f, 0.44f))
        val bottom = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.30f, 0.24f))
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = dp(9).toFloat()
        }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(9).toFloat())
            }
        }
        setOnFocusChangeListener { _, focused -> foreground = if (focused) strokeDrawable(9) else null }
        setOnClickListener { selectChannel(channel) }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { view.requestFocus(); true }
                MotionEvent.ACTION_UP -> { view.performClick(); true }
                else -> true
            }
        }
        val label = LinearLayout(this@LauncherActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        label.addView(View(this@LauncherActivity).apply {
            background = GradientDrawable().apply { setColor(0xFFF44336.toInt()); cornerRadius = dp(3).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6))
        }, LinearLayout.LayoutParams(dp(6), dp(6)))
        label.addView(text(channel.name, 13f, Color.WHITE).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(7) })
        addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, dp(12), dp(9)) })
    }

    private fun spacer(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(heightDp)).apply { bottomMargin = dp(25) }
        visibility = View.INVISIBLE
    }

    private fun refreshChannels() {
        leftCol.removeAllViews(); rightCol.removeAllViews()
        val list = visibleChannels()
        val idx = list.indexOfFirst { it.id == selected?.id }.let { if (it < 0) 0 else it }
        val left = ((idx - 3) until idx).mapNotNull { list.getOrNull(it) }
        val right = ((idx + 1)..(idx + 2)).mapNotNull { list.getOrNull(it) }
        val leftViews = mutableListOf<FrameLayout>()
        val rightViews = mutableListOf<FrameLayout>()
        (left + List<LiveChannel?>(3 - left.size) { null }).forEach { channel ->
            if (channel == null) leftCol.addView(spacer(63))
            else channelCard(channel, 63).also { card ->
                leftViews.add(card)
                leftCol.addView(card, LinearLayout.LayoutParams(-1, dp(63)).apply { bottomMargin = dp(25) })
            }
        }
        (right + List<LiveChannel?>(2 - right.size) { null }).forEach { channel ->
            if (channel == null) rightCol.addView(spacer(108))
            else channelCard(channel, 108).also { card ->
                rightViews.add(card)
                rightCol.addView(card, LinearLayout.LayoutParams(-1, dp(108)).apply { bottomMargin = dp(25) })
            }
        }
        // 显式焦点连线：左列 <-> 大卡 <-> 右列
        leftViews.forEachIndexed { i, card ->
            card.nextFocusRightId = if (i == leftViews.size - 1) video.id
            else leftViews.getOrNull(i + 1)?.id ?: video.id
            if (i > 0) card.nextFocusLeftId = leftViews[i - 1].id
            card.nextFocusUpId = selectedPillId
        }
        rightViews.forEachIndexed { i, card ->
            card.nextFocusLeftId = video.id
            card.nextFocusUpId = selectedPillId
            if (i > 0) card.nextFocusUpId = rightViews[i - 1].id
        }
        if (leftViews.isNotEmpty()) video.nextFocusLeftId = leftViews.last().id
        if (rightViews.isNotEmpty()) video.nextFocusRightId = rightViews[0].id
    }

    private fun updateTitle() {
        tagPill.text = selected?.group ?: "直播"
        videoLabel.text = (selected?.name ?: "尚未选择频道") + "  ·  " + if (fullscreen) "返回回首页" else "按确认全屏"
        video.contentDescription = "中央预览：" + (selected?.name ?: "导入频道")
    }

    private fun selectChannel(channel: LiveChannel) {
        selected = channel; updateTitle(); refreshChannels()
        emptyHint.text = "正在连接 ${channel.name}"; emptyHint.visibility = View.VISIBLE
        if (started) resumePlayback()
        video.requestFocus()
        Log.i("TVLauncher", "channel_selected id=${channel.id.take(12)}")
    }

    private fun updateStatus(message: String) {
        if (!::status.isInitialized) return
        status.text = message
        if (playbackPhase != PlaybackPhase.PLAYING && playbackPhase != PlaybackPhase.STOPPED) {
            videoLabel.text = (selected?.name ?: "直播") + "  ·  " + message
            videoLabel.visibility = View.VISIBLE
        } else if (playbackPhase == PlaybackPhase.PLAYING) {
            updateTitle()
            if (fullscreen) showFullscreenInfo()
        }
    }

    private fun updateClock() {
        clock.text = DateFormat.getTimeFormat(this).format(System.currentTimeMillis())
    }

    private fun setFullscreen(value: Boolean) {
        fullscreen = value
        if (value) {
            // 同一 Surface 保留在原父容器：隐藏其余区域、取消边距，避免第二播放器与重绑黑帧。
            topbar.visibility = View.GONE
            tabScroll.visibility = View.GONE
            leftCol.visibility = View.GONE
            rightCol.visibility = View.GONE
            appRow.visibility = View.GONE
            home.setPadding(0, 0, 0, 0)
            contentGrid.layoutParams.let { it as LinearLayout.LayoutParams }.apply {
                topMargin = 0; height = 0; weight = 1f
            }
            (previewSlot.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
            tagPill.visibility = View.GONE
            showFullscreenInfo()
        } else {
            topbar.visibility = View.VISIBLE
            tabScroll.visibility = View.VISIBLE
            leftCol.visibility = View.VISIBLE
            rightCol.visibility = View.VISIBLE
            appRow.visibility = View.VISIBLE
            home.setPadding(dp(48), dp(14), dp(48), 0)
            contentGrid.layoutParams.let { it as LinearLayout.LayoutParams }.apply {
                topMargin = dp(32); height = dp(241); weight = 0f
            }
            (previewSlot.layoutParams as LinearLayout.LayoutParams).setMargins(dp(25), 0, dp(25), 0)
            tagPill.visibility = View.VISIBLE
            handler.removeCallbacks(hideInfo); videoLabel.visibility = View.VISIBLE
        }
        video.foreground = if (!value && video.hasFocus()) strokeDrawable(9) else null
        updateTitle(); contentGrid.requestLayout(); video.requestFocus()
        Log.i("TVLauncher", "page=${if (value) "FULLSCREEN" else "HOME"} instances=${playback.instanceCount}")
    }
    private fun showFullscreenInfo() {
        handler.removeCallbacks(hideInfo); videoLabel.visibility = View.VISIBLE
        handler.postDelayed(hideInfo, 3500)
    }

    // ============ 底栏应用区：彩色渐变大卡 ============

    private fun dominantColor(icon: android.graphics.drawable.Drawable?): Int? {
        if (icon == null) return null
        return runCatching {
            val size = 32
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            icon.setBounds(0, 0, size, size)
            icon.draw(canvas)
            var best = 0
            var bestScore = 0f
            val hsv = FloatArray(3)
            for (x in 0 until size) for (y in 0 until size) {
                val p = bmp.getPixel(x, y)
                if (Color.alpha(p) < 220) continue
                Color.colorToHSV(p, hsv)
                val score = hsv[1] * 3f + hsv[2]
                if (score > bestScore) { bestScore = score; best = p }
            }
            if (bestScore > 1.2f) best else null
        }.getOrNull()
    }

    private fun appGradient(icon: android.graphics.drawable.Drawable?): GradientDrawable {
        val base = dominantColor(icon) ?: Color.rgb(56, 108, 190)
        val hsv = FloatArray(3).also { Color.colorToHSV(base, it) }
        hsv[1] = (hsv[1] * 0.85f).coerceIn(0.25f, 0.75f)
        hsv[2] = hsv[2].coerceIn(0.45f, 0.8f)
        val top = Color.HSVToColor(hsv)
        hsv[2] = (hsv[2] * 0.6f).coerceAtLeast(0.18f)
        val bottom = Color.HSVToColor(hsv)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
    }

    private fun appSlot(tag: String?, gradient: GradientDrawable, icon: android.graphics.drawable.Drawable?, label: String, action: () -> Unit): LinearLayout {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val card = FrameLayout(this).apply {
            id = View.generateViewId(); isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            if (tag != null) this.tag = tag
            background = gradient.apply { cornerRadius = dp(14).toFloat() }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                }
            }
            setOnFocusChangeListener { _, focused -> foreground = if (focused) strokeDrawable(14) else null }
            setOnClickListener { action() }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { view.requestFocus(); true }
                    MotionEvent.ACTION_UP -> { view.performClick(); true }
                    else -> true
                }
            }
            if (icon != null) {
                val image = ImageView(this@LauncherActivity).apply { setImageDrawable(icon) }
                addView(image, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
            } else {
                addView(text(label, 15f, Color.WHITE).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            }
        }
        column.addView(card, LinearLayout.LayoutParams(dp(197), dp(111)))
        column.addView(text(label, 14f, Color.WHITE).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(197), -2).apply { topMargin = dp(8) })
        return column
    }

    private fun refreshApps() {
        appRow.removeAllViews()
        val apps = runCatching { appRepo.load() }.getOrDefault(emptyList())
        val slots = mutableListOf<LinearLayout>()
        apps.take(3).forEach { app ->
            val slot = appSlot(app.component.flattenToString(), appGradient(app.icon), app.icon, app.label) {
                try { lastApp = app.component.flattenToString(); goingExternal = true; playback.stop(); appRepo.launch(app) }
                catch (_: Exception) { goingExternal = false; toast("应用无法启动，可能已卸载或受系统限制"); resumePlayback(); refreshApps() }
            }
            slots.add(slot)
        }
        if (apps.size > 3) {
            slots.add(appSlot(null, GradientDrawable().apply {
                setColor(0xFF153E6B.toInt()); cornerRadius = dp(14).toFloat()
            }, null, "全部应用") { showAllApps(apps) })
        }
        while (slots.size < 4) {
            slots.add(appSlot(null, GradientDrawable().apply {
                setColor(0xFF0D2C4E.toInt()); cornerRadius = dp(14).toFloat()
            }, null, "待安装") { toast("安装更多应用后出现在这里") })
        }
        slots.forEach { slot ->
            appRow.addView(slot, LinearLayout.LayoutParams(dp(197), -2).apply { rightMargin = dp(25) })
        }
        // 应用区向主区的返回路径：列对齐
        val columnUp = listOf(
            leftCol.getChildAt(2)?.id ?: video.id,
            video.id,
            video.id,
            rightCol.getChildAt(1)?.id ?: video.id
        )
        slots.forEachIndexed { i, slot ->
            val card = slot.getChildAt(0) as FrameLayout
            if (i < columnUp.size) card.nextFocusUpId = columnUp[i]
            if (i > 0) card.nextFocusLeftId = (slots[i - 1].getChildAt(0) as FrameLayout).id
        }
        if (slots.isNotEmpty()) video.nextFocusDownId = (slots[1].getChildAt(0) as FrameLayout).id
    }

    private fun showAllApps(apps: List<AppRepository.App>) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        val scroll = ScrollView(this).apply { addView(box) }
        apps.forEach { app ->
            box.addView(darkButton(app.label) {
                dialog?.dismiss()
                try { lastApp = app.component.flattenToString(); goingExternal = true; playback.stop(); appRepo.launch(app) }
                catch (_: Exception) { goingExternal = false; toast("应用无法启动"); resumePlayback() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        box.addView(darkButton("系统设置") { dialog?.dismiss(); openExternal(Intent(Settings.ACTION_SETTINGS)) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        showDialog(AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("全部应用").setView(scroll).setNegativeButton("返回", null).create())
    }

    // ============ 导入 / 设置 / 线路（逻辑保持，弹窗换深色主题） ============

    private fun showImportOptions() {
        showDialog(AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("导入频道")
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
        val d=AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("M3U 订阅地址").setView(edit).setPositiveButton("导入",null).setNegativeButton("取消",null).create()
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
                    refreshGroups(); refreshChannels(); refreshApps(); updateTitle(); setFullscreen(false)
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
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener {_,v -> store.preferences.edit().putBoolean("autoPlay",v).apply()}
        })
        box.addView(text("需要系统允许默认 HOME。首次无频道先导入；普通 HOME 返回仍进首页。",12f,0xB3FFFFFF.toInt()).apply { setPadding(0,dp(6),0,dp(6)) })
        box.addView(darkButton("设为默认桌面") {dialog?.dismiss();requestHome()},itemLayout(44))
        box.addView(darkButton("选择播放线路") {dialog?.dismiss();showRoutes()},itemLayout(44))
        box.addView(darkButton("重新连接当前频道") {dialog?.dismiss(); if(started && selected!=null) {resumePlayback();toast("正在重新连接")}},itemLayout(44))
        box.addView(darkButton("导入 / 更新频道") {dialog?.dismiss();showImportOptions()},itemLayout(44))
        box.addView(darkButton("打开系统设置 / 恢复其他桌面") {dialog?.dismiss();openExternal(Intent(Settings.ACTION_SETTINGS))},itemLayout(44))
        box.addView(text("Outman TV 0.3.0 · 本机存储 · 单路直播\n若原厂强制桌面，本应用不会擅自禁用原桌面或修改系统。",11f,0x99FFFFFF.toInt()).apply { setPadding(0,dp(8),0,0) })
        showDialog(AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("设置").setView(box).setNegativeButton("返回",null).create())
    }
    private fun itemLayout(height: Int = 39) = LinearLayout.LayoutParams(-1, dp(height)).apply { bottomMargin = dp(7) }
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
        showDialog(AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("${channel.name} · 播放线路")
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
        if(::appRow.isInitialized) {
            val appFocus = if(returning) lastApp else currentFocus?.tag as? String
            refreshApps()
            if(!fullscreen && appFocus!=null) {
                appRow.post { if(!fullscreen) appRow.findViewWithTag<View>(appFocus)?.requestFocus() }
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
        lastApp?.let { key -> appRow.post { if(!fullscreen) appRow.findViewWithTag<View>(key)?.requestFocus() } }
        Log.i("TVLauncher","new_intent_home=${intent.hasCategory(Intent.CATEGORY_HOME)}")
    }
    override fun onSaveInstanceState(out:Bundle) {out.putBoolean("fullscreen",fullscreen);out.putString("channel",selected?.id);out.putString("processToken",PROCESS_TOKEN);super.onSaveInstanceState(out)}
    companion object { private val PROCESS_TOKEN = java.util.UUID.randomUUID().toString() }
    @Deprecated("TV back key API 24+")
    override fun onBackPressed() {
        if(fullscreen) setFullscreen(false) else {video.requestFocus();toast("已在首页，确认中央画面进入全屏")}
    }
}
