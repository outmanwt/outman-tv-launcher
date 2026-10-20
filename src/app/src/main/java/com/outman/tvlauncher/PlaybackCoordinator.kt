package com.outman.tvlauncher

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.outman.tvlauncher.core.PlaybackPhase
import com.outman.tvlauncher.core.PlaybackPolicy

@UnstableApi
class PlaybackCoordinator(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onAspectRatio: (Float) -> Unit = {},
    private val onState: (PlaybackPhase) -> Unit = {},
    private val onTerminalFailure: (code: String, allowAlternateRoute: Boolean) -> Unit = { _, _ -> },
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var listener: Player.Listener? = null
    private var surface: SurfaceView? = null
    private var currentUrl: String? = null
    private var generation = 0L
    private var active = false
    private var released = false
    private var retryCount = 0
    private var liveRecoveryCount = 0
    private var frameRendered = false
    private var successReported = false
    private var retryTask: Runnable? = null
    private var watchdog: Runnable? = null

    init {
        // Media3 内部异常日志可能携带源地址；只保留本类的脱敏状态日志。
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
    }

    val instanceCount: Int get() = if (player == null) 0 else 1

    fun play(url: String, surface: SurfaceView) {
        requireMainThread()
        if (released) return
        invalidateAttempt()
        disposePlayer()
        this.surface = surface
        currentUrl = url
        active = true
        retryCount = 0
        liveRecoveryCount = 0
        successReported = false
        val uri = Uri.parse(url).normalizeScheme()
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
            fail("地址无效，仅支持 HTTP/HTTPS", "INVALID_URI")
            return
        }
        currentUrl = uri.toString()
        startAttempt()
    }

    fun attach(surface: SurfaceView) {
        requireMainThread()
        if (released || this.surface === surface) return
        this.surface = surface
        player?.let {
            it.clearVideoSurface()
            frameRendered = false
            it.setVideoSurfaceView(surface)
            armWatchdog(generation, it)
        }
    }

    // 离开界面即释放声音、解码器和网络请求；返回前台由 Activity 再次 play。
    fun stop() {
        requireMainThread()
        active = false
        invalidateAttempt()
        disposePlayer()
        surface = null
        currentUrl = null
        publish("已停止", "STOPPED")
    }

    fun release() {
        requireMainThread()
        if (released) return
        stop()
        released = true
    }

    fun retry() {
        requireMainThread()
        if (!active || released || currentUrl == null || surface == null) return
        invalidateAttempt()
        disposePlayer()
        retryCount = 0
        liveRecoveryCount = 0
        startAttempt()
    }

    private fun startAttempt() {
        val url = currentUrl ?: return
        val target = surface ?: return
        if (!active || released) return
        val token = generation
        frameRendered = false
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, http))
            .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(0) {
                override fun getRetryDelayMsFor(
                    loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
                ): Long = C.TIME_UNSET
            })
        val current = ExoPlayer.Builder(appContext)
            .setLooper(Looper.getMainLooper())
            .setMediaSourceFactory(sources)
            .build()
        player = current
        current.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        current.setHandleAudioBecomingNoisy(true)
        val callbacks = object : Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                if(isCurrent(token,current) && size.height>0) onAspectRatio(size.width * size.pixelWidthHeightRatio / size.height)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!isCurrent(token, current)) return
                when (playbackState) {
                    Player.STATE_BUFFERING, Player.STATE_READY -> refreshPlaybackState(token, current)
                    Player.STATE_ENDED -> fail("播放已结束，可重试或换台", "ENDED")
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                refreshPlaybackState(token, current)
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                refreshPlaybackState(token, current)
            }

            override fun onRenderedFirstFrame() {
                if (!isCurrent(token, current)) return
                frameRendered = true
                refreshPlaybackState(token, current)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!isCurrent(token, current)) return
                Log.w(TAG, "state=ERROR generation=$token code=${error.errorCodeName}")
                if (!mayAdvance(current)) {
                    fail("播放暂停期间源发生错误，请手动重连或换台", "PAUSED_SOURCE_ERROR", false)
                    return
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    if (liveRecoveryCount >= MAX_LIVE_RECOVERIES) {
                        fail("直播窗口持续失效，请重试或换台", error.errorCodeName)
                    } else {
                        liveRecoveryCount++
                        cancelWatchdog()
                        frameRendered = false
                        publish("正在恢复直播位置", "LIVE_RECOVERY")
                        current.seekToDefaultPosition()
                        current.prepare()
                        current.play()
                        armWatchdog(token, current)
                    }
                    return
                }
                val status = httpStatus(error)
                when {
                    status == 401 || status == 403 -> fail("播放源拒绝访问，请检查授权", error.errorCodeName, false)
                    status == 404 || status == 410 -> fail("播放源不存在，请换台", error.errorCodeName)
                    status != null && status in 400..499 && status != 408 && status != 429 ->
                        fail("播放请求被拒绝，请检查播放源", error.errorCodeName, false)
                    isTransient(error, status) -> scheduleRetry(error.errorCodeName)
                    else -> fail("无法播放此源，请重试或换台", error.errorCodeName)
                }
            }
        }
        listener = callbacks
        current.addListener(callbacks)
        current.setVideoSurfaceView(target)
        publish("正在连接", "PREPARING")
        try {
            current.setMediaItem(MediaItem.fromUri(url))
            current.prepare()
            current.play()
            refreshPlaybackState(token, current)
        } catch (_: IllegalArgumentException) {
            fail("播放地址或媒体格式不受支持，请换台", "INVALID_MEDIA")
        } catch (_: IllegalStateException) {
            fail("媒体格式不受支持或播放器不可用，请换台", "UNSUPPORTED_MEDIA")
        } catch (_: UnsupportedOperationException) {
            fail("媒体格式不受支持，请换台", "UNSUPPORTED_MEDIA")
        }
    }

    private fun mayAdvance(current: ExoPlayer): Boolean = PlaybackPolicy.mayAdvance(
        current.playWhenReady, current.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
    )

    private fun needsWatchdog(current: ExoPlayer): Boolean = PlaybackPolicy.needsWatchdog(
        current.playWhenReady, current.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
        current.playbackState == Player.STATE_READY, frameRendered,
    )

    private fun refreshPlaybackState(token: Long, current: ExoPlayer) {
        if (!isCurrent(token, current) || current.playerError != null) return
        val phase = PlaybackPolicy.phase(current.playWhenReady,
            current.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            current.playbackState == Player.STATE_READY, frameRendered)
        when (phase) {
            PlaybackPhase.SUPPRESSED -> { cancelWatchdog(); publish("声音被其他应用暂时占用", "SUPPRESSED") }
            PlaybackPhase.PAUSED -> { cancelWatchdog(); publish("播放已暂停，可在设置中重新连接", "PAUSED") }
            PlaybackPhase.PLAYING -> reportReady(token, current)
            else -> { publish("缓冲中", "BUFFERING"); armWatchdog(token, current) }
        }
    }

    private fun reportReady(token: Long, current: ExoPlayer) {
        if (!isCurrent(token, current) || !mayAdvance(current) || current.playbackState != Player.STATE_READY || !frameRendered) return
        cancelWatchdog()
        publish("正在播放", "PLAYING")
        // READY 本身不代表视频成功；每次选台只在真实首帧与 READY 同时成立后记忆一次。
        if (!successReported) {
            successReported = true
            Log.i(TAG, "state=FIRST_FRAME generation=$token instances=$instanceCount")
            onFirstFrame()
        }
    }

    private fun armWatchdog(token: Long, current: ExoPlayer) {
        if (watchdog != null || !isCurrent(token, current) || !needsWatchdog(current)) return
        val task = Runnable {
            watchdog = null
            if (isCurrent(token, current) && needsWatchdog(current)) scheduleRetry("BUFFER_OR_FIRST_FRAME_TIMEOUT")
        }
        watchdog = task
        handler.postDelayed(task, STALL_TIMEOUT_MS)
    }

    private fun scheduleRetry(code: String) {
        if (!active || released) return
        invalidateAttempt()
        disposePlayer()
        if (retryCount >= RETRY_DELAYS_MS.size) {
            fail("连接失败，已停止当前线路重试", code)
            return
        }
        val delay = RETRY_DELAYS_MS[retryCount++]
        val token = generation
        publish("连接中断，${delay / 1000} 秒后重试（$retryCount/3）", "RETRY_$code")
        val task = Runnable {
            retryTask = null
            if (active && !released && generation == token) startAttempt()
        }
        retryTask = task
        handler.postDelayed(task, delay)
    }

    private fun fail(message: String, code: String, allowAlternateRoute: Boolean = true) {
        invalidateAttempt()
        disposePlayer()
        publish(message, "ERROR_$code")
        // Defer to avoid constructing a replacement player inside an old player callback.
        val token = generation
        handler.post {
            if (active && !released && generation == token) onTerminalFailure(code, allowAlternateRoute)
        }
    }

    private fun isTransient(error: PlaybackException, status: Int?): Boolean =
        status == 408 || status == 429 || (status != null && status in 500..599) ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT

    private fun httpStatus(error: Throwable): Int? {
        var cause: Throwable? = error
        repeat(12) {
            val current = cause ?: return null
            if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
            cause = current.cause
        }
        return null
    }

    private fun isCurrent(token: Long, current: ExoPlayer): Boolean =
        active && !released && token == generation && player === current

    private fun invalidateAttempt() {
        generation++
        retryTask?.let(handler::removeCallbacks)
        retryTask = null
        cancelWatchdog()
    }

    private fun cancelWatchdog() {
        watchdog?.let(handler::removeCallbacks)
        watchdog = null
    }

    private fun disposePlayer() {
        val old = player ?: return
        player = null
        listener?.let(old::removeListener)
        listener = null
        old.stop()
        old.clearVideoSurface()
        old.release()
        Log.i(TAG, "state=RELEASED generation=$generation instances=$instanceCount")
    }

    private fun publish(message: String, state: String) {
        // 不传 Throwable 或原始 URL，避免日志泄露查询参数、令牌和私有源。
        Log.i(TAG, "state=$state generation=$generation instances=$instanceCount")
        val phase = when {
            state.startsWith("ERROR_") -> PlaybackPhase.ERROR
            state.startsWith("RETRY_") -> PlaybackPhase.RETRYING
            state == "LIVE_RECOVERY" -> PlaybackPhase.RECOVERING
            state == "PREPARING" -> PlaybackPhase.PREPARING
            state == "BUFFERING" -> PlaybackPhase.BUFFERING
            state == "PLAYING" -> PlaybackPhase.PLAYING
            state == "SUPPRESSED" -> PlaybackPhase.SUPPRESSED
            state == "PAUSED" -> PlaybackPhase.PAUSED
            else -> PlaybackPhase.STOPPED
        }
        onState(phase)
        onStatus(message)
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "播放器操作必须在主线程执行" }
    }

    private companion object {
        const val TAG = "TVPlayback"
        const val STALL_TIMEOUT_MS = 20_000L
        const val MAX_LIVE_RECOVERIES = 2
        val RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L, 8_000L)
    }
}
