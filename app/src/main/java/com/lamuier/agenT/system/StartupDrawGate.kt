package com.lamuier.agenT.system

import android.view.View
import android.view.ViewTreeObserver

/** 本地缓存恢复时保留系统启动画面，避免先画应用加载页再跳首页。 */
internal class StartupDrawGate(private val view: View) {
    private var released = false
    private val listener = ViewTreeObserver.OnPreDrawListener { released }

    init {
        view.viewTreeObserver.addOnPreDrawListener(listener)
    }

    /** 由成功组合后的首页调用；放行一次后，后续刷新不再阻塞绘制。 */
    fun release() {
        if (released) return
        released = true
        // View 附着到窗口后 observer 可能已替换，应从当前 observer 移除监听。
        view.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
    }
}
