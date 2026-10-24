package com.mo.fkLTY.hook

import android.content.Context
import android.content.Intent
import com.mo.fkLTY.engine.RedirectEngine
import io.github.libxposed.api.XposedInterface

/**
 * 兜底 hook:`PendingIntent.getActivity/getActivities` 的创建时刻。
 *
 * 为什么还要这一层:流体云的卡片不一定由 metis 自己"现场" startActivity。
 * 很常见的写法是 metis 事先把点击动作包成 PendingIntent,交给卡片宿主(SystemUI)去发送。
 * 那种情况下真正发起跳转的是 system_server,metis 进程里根本不会有 startActivity 调用,
 * [StartActivityHooker] 一次都不会触发——用户看到的就是"改了规则没反应"。
 *
 * 在创建时刻把内层 Intent 改掉,之后无论谁来 send、什么时候 send,发出去的都是改写后的目标,
 * 覆盖了这条旁路。改写仍由 [RedirectEngine] 判定,不命中就原样放行。
 */
class PendingIntentHooker(private val source: String) : XposedInterface.Hooker {

    override fun intercept(chain: XposedInterface.Chain): Any? {
        try {
            val context = chain.getArg(0) as? Context
            if (context != null) {
                // getActivity(Context, int, Intent, int[, Bundle]) / getActivities(..., Intent[], ...)
                // 内层 Intent 固定在参数 2
                when (val arg = chain.getArg(2)) {
                    is Intent -> RedirectEngine.applyToIntent(context, arg, source)
                    is Array<*> -> arg.forEach { item ->
                        if (item is Intent) RedirectEngine.applyToIntent(context, item, source)
                    }
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            HookLog.once(
                "err:pending:$source",
                "PendingIntentHooker error: ${t.javaClass.simpleName}: ${t.message}",
            )
        }
        return chain.proceed()
    }
}
