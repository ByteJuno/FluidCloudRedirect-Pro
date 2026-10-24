package com.mo.fkLTY.engine

import android.content.ClipboardManager
import android.content.Context
import com.mo.fkLTY.hook.HookLog

/**
 * 从剪贴板里捞出用户刚才复制的分享地址。
 *
 * 只在「引导安装」这条降级路径上用。正常情况下 metis 会把
 * `qklink://<hash>?url=<真实地址>` 交给目标 App，分享地址是跟着 Intent 走的；
 * 但卸载源 App 后它只给一个商店意图，真实分享地址在整个链路上已经不存在了。
 * 此时用户剪贴板里那段口令往往还在（卡片本来就是从它生成的），
 * 捞出来能让目标 App 直接落到分享页，而不是仅仅被拉起来。
 *
 * Android 10+ 限制后台读剪贴板：拿不到就返回 null，退化为「只启动 App」，不影响主流程。
 * 读到的内容不写日志正文（只写命中的域名级别信息由调用方决定）。
 */
internal object ClipboardShare {

    fun findUrl(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return null
            val clip = cm.primaryClip ?: return null
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i) ?: continue
                val text = item.text?.toString() ?: item.uri?.toString() ?: continue
                IntentInspector.findUrl(text)?.let { return it }
            }
            null
        } catch (t: Throwable) {
            HookLog.once(
                "clipboard-fail",
                "Clipboard unreadable: ${t.javaClass.simpleName}: ${t.message}",
            )
            null
        }
    }
}
