package com.mo.fkLTY.hook

import android.content.SharedPreferences
import android.util.Log
import com.mo.fkLTY.engine.RedirectPrefs
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * hook 端日志:LSPosed 管理器日志中过滤 "FluidRedirect" 即可查看。
 * 消息统一带旧模块的 "[IntentRedirect]" 前缀,便于过渡期对账(02 号文档 §6)。
 */
object HookLog {
    const val TAG = "FluidRedirect"
    const val PREFIX = "[IntentRedirect]"

    @Volatile
    private var module: XposedModule? = null

    private val onceKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 卡片取证日志的每进程配额:排查期需要看到每次点击,又不能无限刷 */
    private val cardCount = java.util.concurrent.atomic.AtomicInteger(0)
    private const val CARD_LOG_LIMIT = 40

    /** 框架实例化入口后立即绑定;每进程一次 */
    fun bind(m: XposedModule) {
        module = m
        onceKeys.clear()
        cardCount.set(0)
    }

    val isBound: Boolean
        get() = module != null

    fun log(msg: String) {
        module?.log(Log.INFO, TAG, "$PREFIX $msg")
    }

    fun log(msg: String, tr: Throwable) {
        module?.log(Log.INFO, TAG, "$PREFIX $msg", tr)
    }

    /** 一次性日志:同类异常每进程只打一次(沿用旧模块 logOnce 语义) */
    fun once(key: String, msg: String) {
        if (onceKeys.add(key)) log(msg)
    }

    /** 详细日志:受远程偏好 verbose_log 控制(如监控位 startByZoom 观测) */
    fun verbose(msg: String) {
        if (prefs()?.getBoolean(RedirectPrefs.KEY_VERBOSE_LOG, false) == true) log(msg)
    }

    /**
     * 卡片取证日志:不受 verbose_log 开关控制,但每进程有配额。
     * 理由:排查"卡片到底走没走 hook"时必须无条件留痕,否则用户没开开关就什么都看不到。
     */
    fun card(msg: String) {
        if (cardCount.incrementAndGet() <= CARD_LOG_LIMIT) log(msg)
    }

    /** hook 端远程偏好(hooked 进程内只读,libxposed API 语义) */
    fun prefs(): SharedPreferences? = try {
        module?.getRemotePreferences(RedirectPrefs.GROUP)
    } catch (_: Throwable) {
        null
    }
}
