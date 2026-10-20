package com.outman.tvlauncher.core

object EntryPolicy {
    enum class EntryPage { HOME, FULLSCREEN, SETUP }

    // isHomeIntent 仅表示新导航事件；配置重建不应再次传入原始 HOME intent。
    // isRecreation 仅表示配置变化重建，不包括被系统回收后的进程恢复。
    fun decide(
        hasChannels: Boolean,
        isHomeIntent: Boolean,
        bootCount: Int,
        lastBootCount: Int,
        autoPlay: Boolean,
        savedFullscreen: Boolean?,
        isRecreation: Boolean
    ): EntryPage {
        if (!hasChannels) return EntryPage.SETUP
        if (bootCount < 0) return EntryPage.HOME
        if (bootCount != lastBootCount) {
            return if (isHomeIntent && autoPlay) EntryPage.FULLSCREEN else EntryPage.HOME
        }
        if (isHomeIntent) return EntryPage.HOME
        if (isRecreation && savedFullscreen == true) return EntryPage.FULLSCREEN
        return EntryPage.HOME
    }
}
